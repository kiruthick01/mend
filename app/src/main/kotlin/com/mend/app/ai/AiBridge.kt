package com.mend.app.ai

import com.mend.engine.nlp.ExplanationContext
import com.mend.engine.nlp.StructuredConstraint

/**
 * The entire AI surface of this application.
 *
 * Two methods, and PROJECT_PLAN.md Section 1 is explicit that there will never
 * be a third: language *in*, phrasing *out*. No scheduling decision passes
 * through here. The engine ranks, packs, reshuffles and refuses entirely
 * without it, which is why the interface can be swapped for [OfflineAiBridge]
 * and the app carries on working.
 *
 * Both methods are `suspend` and neither is ever called from a path that has to
 * complete on time. Nothing that fires an alarm, draws a schedule, or decides
 * what happens next waits on a network round trip.
 */
interface AiBridge {

    /** Whether calls are worth attempting at all right now. */
    suspend fun availability(): AiAvailability

    /**
     * Turns free text into one of the shapes the engine can validate.
     *
     * Never throws for ordinary failure. A model that cannot understand the
     * input returns [StructuredConstraint.Unrecognised]; an unreachable API
     * returns [AiResult.Unavailable] so the caller can offer the manual form
     * instead of an error.
     */
    suspend fun parseNaturalLanguageInput(
        text: String,
        context: ParseContext,
    ): AiResult<StructuredConstraint>

    /**
     * Rephrases an already-decided message.
     *
     * The context carries numbers the engine computed. The model may say them
     * better; it may not say anything else, and it is never the source of them.
     */
    suspend fun generateExplanationMessage(
        context: ExplanationContext,
    ): AiResult<String>
}

/** What the parser needs to resolve relative dates and name goals. */
data class ParseContext(
    /** Today, so "next week" means something. Passed in, never read from a clock. */
    val today: java.time.LocalDate,
    /** Goal titles the user might be referring to. */
    val knownGoalTitles: List<String> = emptyList(),
)

/** Why the AI layer is or is not usable, in terms the UI can explain. */
enum class AiAvailability {
    /** Configured and worth trying. */
    READY,

    /** No API key has been set. The manual form is the whole experience. */
    NO_API_KEY,

    /** Configured, but the last attempt could not reach the API. */
    OFFLINE,

    /** Deliberately switched off. */
    DISABLED,
    ;

    val isReady: Boolean get() = this == READY
}

/**
 * The outcome of an AI call.
 *
 * [Unavailable] is deliberately not an error: being offline is an expected,
 * ordinary state for this app, and modelling it as a failure would push callers
 * toward showing the user something apologetic instead of the manual form that
 * does the same job.
 */
sealed interface AiResult<out T> {

    data class Success<T>(val value: T) : AiResult<T>

    /** The API could not be reached, or is not configured. */
    data class Unavailable(val reason: AiAvailability, val detail: String? = null) :
        AiResult<Nothing>

    /** It was reached and something went wrong. Worth surfacing; not fatal. */
    data class Failed(val message: String) : AiResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
}
