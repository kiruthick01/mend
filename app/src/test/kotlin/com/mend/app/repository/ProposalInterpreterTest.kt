package com.mend.app.repository

import com.mend.app.ai.AiAvailability
import com.mend.app.ai.AiBridge
import com.mend.app.ai.AiResult
import com.mend.app.ai.OfflineAiBridge
import com.mend.app.ai.ParseContext
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.StaticBlock
import com.mend.engine.nlp.ExplanationContext
import com.mend.engine.nlp.StructuredConstraint
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The seam where an AI suggestion meets the deterministic engine.
 *
 * These are the tests that matter most in Phase 7, because they are what stops
 * a confident, well-formed, entirely wrong model response from becoming
 * something the user is invited to accept.
 */
class ProposalInterpreterTest {

    private val today = LocalDate.of(2026, 9, 16) // a Wednesday

    /** An AI that returns whatever it is told to. */
    private class StubBridge(
        private val result: AiResult<StructuredConstraint>,
    ) : AiBridge {
        override suspend fun availability() = AiAvailability.READY
        override suspend fun parseNaturalLanguageInput(text: String, context: ParseContext) = result
        override suspend fun generateExplanationMessage(context: ExplanationContext) =
            AiResult.Success("ignored")
    }

    private fun interpreter(result: AiResult<StructuredConstraint>) =
        ProposalInterpreter(StubBridge(result))

    private suspend fun ProposalInterpreter.run(
        blocks: List<StaticBlock> = emptyList(),
        recovery: List<ProtectedRecovery> = emptyList(),
    ) = interpret(
        text = "anything",
        today = today,
        goalTitles = listOf("Statistics exam"),
        staticBlocks = blocks,
        habits = emptyList(),
        protectedRecovery = recovery,
    )

    // --- the happy path ------------------------------------------------------

    @Test
    fun `a valid constraint comes back understood, with a summary to confirm`() = runTest {
        val outcome = interpreter(
            AiResult.Success(
                StructuredConstraint.BusyPeriod(
                    from = today.plusDays(7),
                    to = today.plusDays(11),
                    label = "exams",
                ),
            ),
        ).run()

        assertTrue(outcome is ProposalInterpreter.Outcome.Understood)
        assertTrue(outcome.summary.contains("exams"))
        assertTrue(outcome.warnings.isEmpty())
    }

    @Test
    fun `warnings survive to the confirmation screen`() = runTest {
        val outcome = interpreter(
            AiResult.Success(
                StructuredConstraint.BusyPeriod(
                    from = today.minusDays(2),
                    to = today.plusDays(3),
                    label = "exams",
                ),
            ),
        ).run()

        assertTrue(outcome is ProposalInterpreter.Outcome.Understood)
        assertTrue(outcome.warnings.single().contains("in the past"))
    }

    // --- the engine has the final say ---------------------------------------

    @Test
    fun `a constraint the engine refuses is never offered to the user`() = runTest {
        // Perfectly well-formed, and complete nonsense: a deadline in the past.
        val outcome = interpreter(
            AiResult.Success(
                StructuredConstraint.DeadlineChange("Statistics exam", today.minusDays(5)),
            ),
        ).run()

        assertTrue(
            outcome is ProposalInterpreter.Outcome.Refused,
            "a well-formed but incoherent suggestion must not reach the user",
        )
        assertTrue(outcome.reason.contains("in the past"))
    }

    @Test
    fun `a change that would empty the day is refused, however confidently parsed`() = runTest {
        val outcome = interpreter(
            AiResult.Success(
                StructuredConstraint.AvoidWindow(
                    daysOfWeek = setOf(DayOfWeek.WEDNESDAY),
                    from = LocalTime.of(6, 0),
                    to = LocalTime.of(12, 0),
                    label = "mornings",
                ),
            ),
        ).run(
            blocks = listOf(
                StaticBlock(
                    id = "college",
                    title = "College",
                    windowStart = LocalTime.of(12, 0),
                    windowEnd = LocalTime.of(22, 0),
                ),
            ),
            recovery = listOf(
                ProtectedRecovery(
                    id = "sleep",
                    title = "Sleep",
                    bedtime = LocalTime.of(22, 0),
                    wakeTime = LocalTime.of(6, 0),
                    windDown = Duration.ZERO,
                ),
            ),
        )

        assertTrue(outcome is ProposalInterpreter.Outcome.Refused)
        assertTrue(outcome.reason.contains("no usable time"))
    }

    @Test
    fun `the same suggestion is fine on a day that can absorb it`() = runTest {
        val constraint = AiResult.Success(
            StructuredConstraint.AvoidWindow(
                daysOfWeek = setOf(DayOfWeek.WEDNESDAY),
                from = LocalTime.of(6, 0),
                to = LocalTime.of(12, 0),
                label = "mornings",
            ),
        )
        assertTrue(interpreter(constraint).run() is ProposalInterpreter.Outcome.Understood)
    }

    // --- the model declining --------------------------------------------------

    @Test
    fun `an unrecognised parse is reported as not understood, not as an error`() = runTest {
        val outcome = interpreter(
            AiResult.Success(StructuredConstraint.Unrecognised("that is a priority change")),
        ).run()

        assertTrue(outcome is ProposalInterpreter.Outcome.NotUnderstood)
        assertEquals("that is a priority change", outcome.reason)
    }

    // --- degradation ---------------------------------------------------------

    @Test
    fun `no API key routes to the manual form`() = runTest {
        val outcome = interpreter(
            AiResult.Unavailable(AiAvailability.NO_API_KEY, "no key set"),
        ).run()

        assertTrue(outcome is ProposalInterpreter.Outcome.NeedsManualEntry)
        assertEquals(AiAvailability.NO_API_KEY, outcome.availability)
    }

    @Test
    fun `being offline routes to the manual form too`() = runTest {
        val outcome = interpreter(
            AiResult.Unavailable(AiAvailability.OFFLINE, "no network"),
        ).run()

        assertTrue(outcome is ProposalInterpreter.Outcome.NeedsManualEntry)
        assertEquals(AiAvailability.OFFLINE, outcome.availability)
    }

    @Test
    fun `an API failure degrades rather than surfacing as an error`() = runTest {
        val outcome = interpreter(AiResult.Failed("rate limited")).run()

        assertTrue(outcome is ProposalInterpreter.Outcome.NeedsManualEntry)
        assertEquals("rate limited", outcome.detail)
    }

    @Test
    fun `the offline bridge always routes to the manual form`() = runTest {
        val outcome = ProposalInterpreter(OfflineAiBridge()).interpret(
            text = "exams from next week for five days",
            today = today,
            goalTitles = emptyList(),
            staticBlocks = emptyList(),
            habits = emptyList(),
            protectedRecovery = emptyList(),
        )
        assertTrue(outcome is ProposalInterpreter.Outcome.NeedsManualEntry)
    }

    // --- the offline bridge still explains things ---------------------------

    @Test
    fun `offline explanations are a real answer, not a failure`() = runTest {
        val result = OfflineAiBridge().generateExplanationMessage(
            ExplanationContext.GuiltTrip(
                goalTitle = "Statistics exam",
                lessonsCompleted = 1,
                totalLessons = 4,
                daysRemaining = 5,
                remainingMinutes = 600,
                observedMinutesPerDay = 30,
                shortfallMinutes = 450,
            ),
        )

        assertTrue(result is AiResult.Success)
        assertTrue(result.value.contains("Statistics exam"))
        assertTrue(result.value.contains("1 of 4 lessons"))
        assertTrue(result.value.contains("5 days left"))
        assertTrue(result.value.contains("450m short"))
    }

    @Test
    fun `offline reshuffle copy names the triggers and the changes`() = runTest {
        val result = OfflineAiBridge().generateExplanationMessage(
            ExplanationContext.Reshuffle(
                effectiveFrom = today.plusDays(1),
                triggers = listOf("Reading was missed on 2026-09-16"),
                changes = listOf("Reading moves from 09:00 to 14:00"),
            ),
        ) as AiResult.Success

        assertTrue(result.value.contains("Reading was missed"))
        assertTrue(result.value.contains("moves from 09:00"))
        assertTrue(result.value.contains(today.plusDays(1).toString()))
    }

    @Test
    fun `an offline reshuffle that changes nothing says so`() = runTest {
        val result = OfflineAiBridge().generateExplanationMessage(
            ExplanationContext.Reshuffle(
                effectiveFrom = today.plusDays(1),
                triggers = listOf("the gym window changed"),
                changes = emptyList(),
            ),
        ) as AiResult.Success

        assertTrue(result.value.contains("nothing needs to move"))
    }
}
