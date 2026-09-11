package com.mend.app.ai

import com.mend.engine.nlp.ExplanationContext
import com.mend.engine.nlp.StructuredConstraint
import com.mend.engine.nlp.TemplatedMessages
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONObject

/**
 * The real AI layer.
 *
 * Both prompts are written to make the model's job small and its output easy to
 * reject. The parser is told to return one of four shapes or nothing, and every
 * field it returns is re-parsed and then validated by the engine before anyone
 * sees it — so a confident, well-formed, completely wrong answer still cannot
 * reach the schedule.
 *
 * The explanation generator is given numbers that are already decided and asked
 * only to phrase them. If it fails for any reason the templated version is
 * returned instead, which is why a generation failure is invisible rather than
 * disruptive.
 */
class AnthropicAiBridge(
    private val client: AnthropicClient,
    private val isEnabled: () -> Boolean = { true },
) : AiBridge {

    override suspend fun availability(): AiAvailability = when {
        !isEnabled() -> AiAvailability.DISABLED
        else -> when (val probe = client.send(PARSE_SYSTEM, "ping", maxTokens = 16)) {
            is AiResult.Unavailable -> probe.reason
            else -> AiAvailability.READY
        }
    }

    override suspend fun parseNaturalLanguageInput(
        text: String,
        context: ParseContext,
    ): AiResult<StructuredConstraint> {
        if (!isEnabled()) return AiResult.Unavailable(AiAvailability.DISABLED)
        if (text.isBlank()) {
            return AiResult.Success(StructuredConstraint.Unrecognised("nothing was written"))
        }

        val prompt = buildString {
            append("Today is ${context.today} (${context.today.dayOfWeek}).\n")
            if (context.knownGoalTitles.isNotEmpty()) {
                append("Existing goals: ")
                append(context.knownGoalTitles.joinToString("; "))
                append("\n")
            }
            append("\nThe user wrote:\n")
            append(text.trim())
        }

        return when (val result = client.send(PARSE_SYSTEM, prompt, maxTokens = 512)) {
            is AiResult.Success -> AiResult.Success(interpret(result.value))
            is AiResult.Unavailable -> result
            is AiResult.Failed -> result
        }
    }

    override suspend fun generateExplanationMessage(
        context: ExplanationContext,
    ): AiResult<String> {
        val templated = TemplatedMessages.forContext(context)
        if (!isEnabled()) return AiResult.Success(templated)

        val prompt = buildString {
            append("Rewrite this so it reads naturally. Keep every number exactly ")
            append("as given, change no facts, and add nothing that is not here.\n\n")
            append(templated)
        }

        // A phrasing failure is not worth surfacing: the templated version says
        // the same thing, and an error toast about prose would be noise.
        return when (val result = client.send(EXPLAIN_SYSTEM, prompt, maxTokens = 400)) {
            is AiResult.Success -> AiResult.Success(result.value)
            else -> AiResult.Success(templated)
        }
    }

    /**
     * Reads the model's JSON back into a constraint.
     *
     * Defensive throughout. Anything missing, malformed, or of an unexpected
     * kind becomes [StructuredConstraint.Unrecognised] rather than an
     * exception — the model producing something unusable is an ordinary
     * outcome, not a crash.
     */
    private fun interpret(raw: String): StructuredConstraint = try {
        val json = JSONObject(extractJsonObject(raw))
        when (json.optString("kind")) {
            "busy_period" -> StructuredConstraint.BusyPeriod(
                from = LocalDate.parse(json.getString("from")),
                to = LocalDate.parse(json.getString("to")),
                label = json.optString("label").ifBlank { "a busy period" },
            )

            "avoid_window" -> StructuredConstraint.AvoidWindow(
                daysOfWeek = json.getJSONArray("days").let { array ->
                    (0 until array.length())
                        .mapNotNull {
                            runCatching { DayOfWeek.valueOf(array.getString(it).uppercase()) }
                                .getOrNull()
                        }
                        .toSet()
                },
                from = LocalTime.parse(json.getString("from")),
                to = LocalTime.parse(json.getString("to")),
                label = json.optString("label").ifBlank { "a preference" },
            )

            "deadline_change" -> StructuredConstraint.DeadlineChange(
                goalTitleHint = json.getString("goal"),
                newDeadline = LocalDate.parse(json.getString("deadline")),
            )

            else -> StructuredConstraint.Unrecognised(
                json.optString("reason").ifBlank { "it did not describe a change Mend can make" },
            )
        }
    } catch (e: Exception) {
        StructuredConstraint.Unrecognised("the response could not be read")
    }

    /** Pulls the first JSON object out of a reply that may be wrapped in prose. */
    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }

    private companion object {

        /**
         * The parser's whole job.
         *
         * It is told the four shapes and told to prefer saying nothing. That
         * bias is deliberate: an unrecognised input costs the user one form,
         * whereas a confidently wrong one costs them a day they did not agree
         * to — and the engine's validator is a backstop, not a substitute for
         * the model declining.
         */
        const val PARSE_SYSTEM = """
You turn a scheduling request written in plain English into one JSON object.
Reply with the JSON object only. No prose, no code fences.

Exactly one of these shapes:

{"kind":"busy_period","from":"YYYY-MM-DD","to":"YYYY-MM-DD","label":"short phrase"}
  A stretch of days largely taken up by something. "Exams from next week for
  five days", "away Thursday to Sunday".

{"kind":"avoid_window","days":["MONDAY",...],"from":"HH:MM","to":"HH:MM","label":"short phrase"}
  A recurring part of the day to keep clear of scheduled work.
  "Keep mornings free", "nothing after 9pm on weekdays".

{"kind":"deadline_change","goal":"goal title as the user referred to it","deadline":"YYYY-MM-DD"}
  A deadline that has moved. Only if the user names or clearly implies a goal.

{"kind":"unrecognised","reason":"short explanation"}
  Anything else. Use this whenever you are not confident.

Rules:
- Resolve all relative dates against the date given. Never return a relative phrase.
- Never guess a date you were not given enough information to work out.
- If the request would change what gets prioritised rather than when work can
  happen, return unrecognised: that is not something you decide.
- Prefer unrecognised over a plausible guess.
"""

        /** Phrasing only. Deliberately forbidden from adding anything. */
        const val EXPLAIN_SYSTEM = """
You rewrite short scheduling messages so they read naturally.

Rules:
- Keep every number, date and name exactly as given.
- Do not add facts, advice, encouragement or apology.
- Do not soften a deadline warning; state it plainly.
- Two or three sentences at most.
- Reply with the rewritten message only.
"""
    }
}
