package com.mend.engine.nlp

import com.mend.engine.TEST_DATE
import com.mend.engine.at
import com.mend.engine.sleep
import com.mend.engine.staticBlock
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate between a model's confident answer and the user's day.
 *
 * The point of these is not that the AI is untrustworthy in particular — it is
 * that nothing gets to propose a change to someone's schedule without the
 * deterministic engine having checked what that change would actually do.
 */
class ConstraintValidationTest {

    private fun validate(
        constraint: StructuredConstraint,
        blocks: List<com.mend.engine.model.StaticBlock> = emptyList(),
        recovery: List<com.mend.engine.model.ProtectedRecovery> = emptyList(),
    ) = validateConstraint(
        constraint = constraint,
        today = TEST_DATE,
        staticBlocks = blocks,
        protectedRecovery = recovery,
    )

    private fun busy(fromDays: Long, toDays: Long) = StructuredConstraint.BusyPeriod(
        from = TEST_DATE.plusDays(fromDays),
        to = TEST_DATE.plusDays(toDays),
        label = "exams",
    )

    // --- unrecognised --------------------------------------------------------

    @Test
    fun `an unrecognised parse is never offered as a change`() {
        val result = validate(StructuredConstraint.Unrecognised("could not read that"))
        assertTrue(result is ValidationResult.Rejected)
        assertEquals("could not read that", result.reason)
    }

    // --- busy periods --------------------------------------------------------

    @Test
    fun `a normal busy period is accepted`() {
        val result = validate(busy(7, 11))
        assertTrue(result is ValidationResult.Accepted)
        assertTrue(!result.hasWarnings)
    }

    @Test
    fun `a backwards period is rejected`() {
        val result = validate(busy(11, 7))
        assertTrue(result is ValidationResult.Rejected)
        assertTrue(result.reason.contains("backwards"))
    }

    @Test
    fun `a period entirely in the past is rejected`() {
        val result = validate(busy(-10, -3))
        assertTrue(result is ValidationResult.Rejected)
        assertTrue(result.reason.contains("already passed"))
    }

    @Test
    fun `a period that started but has not finished is accepted with a warning`() {
        // "Exams this week", typed on Wednesday, is a reasonable thing to say.
        val result = validate(busy(-2, 3))
        assertTrue(result is ValidationResult.Accepted)
        assertTrue(result.hasWarnings)
        assertTrue(result.warnings.single().contains("in the past"))
    }

    @Test
    fun `a period reaching beyond a year is rejected as a likely misreading`() {
        val result = validate(busy(1, 400))
        assertTrue(result is ValidationResult.Rejected)
        assertTrue(result.reason.contains("more than a year"))
    }

    @Test
    fun `a very long period is accepted but flagged`() {
        val result = validate(busy(1, 60))
        assertTrue(result is ValidationResult.Accepted)
        assertTrue(result.warnings.any { it.contains("long time to write off") })
    }

    @Test
    fun `a single-day period is fine`() {
        assertTrue(validate(busy(3, 3)) is ValidationResult.Accepted)
    }

    // --- deadline changes ----------------------------------------------------

    @Test
    fun `a deadline moving forward is accepted`() {
        val result = validate(
            StructuredConstraint.DeadlineChange("Statistics exam", TEST_DATE.plusDays(20)),
        )
        assertTrue(result is ValidationResult.Accepted)
    }

    @Test
    fun `a deadline in the past is rejected`() {
        val result = validate(
            StructuredConstraint.DeadlineChange("Statistics exam", TEST_DATE.minusDays(1)),
        )
        assertTrue(result is ValidationResult.Rejected)
        assertTrue(result.reason.contains("in the past"))
    }

    @Test
    fun `a deadline with no goal named is rejected`() {
        val result = validate(StructuredConstraint.DeadlineChange("  ", TEST_DATE.plusDays(5)))
        assertTrue(result is ValidationResult.Rejected)
        assertTrue(result.reason.contains("which goal"))
    }

    @Test
    fun `a deadline of today is accepted but flagged as an interrupt risk`() {
        val result = validate(StructuredConstraint.DeadlineChange("Visa form", TEST_DATE))
        assertTrue(result is ValidationResult.Accepted)
        assertTrue(result.warnings.single().contains("interrupt"))
    }

    // --- avoid windows: the check that matters ------------------------------

    private fun mornings(from: LocalTime = at(6), to: LocalTime = at(12)) =
        StructuredConstraint.AvoidWindow(
            daysOfWeek = setOf(DayOfWeek.WEDNESDAY),
            from = from,
            to = to,
            label = "I think better after lunch",
        )

    @Test
    fun `keeping mornings free is fine on an open day`() {
        assertTrue(validate(mornings()) is ValidationResult.Accepted)
    }

    @Test
    fun `a backwards window is rejected`() {
        val result = validate(mornings(from = at(12), to = at(6)))
        assertTrue(result is ValidationResult.Rejected)
        assertTrue(result.reason.contains("backwards"))
    }

    @Test
    fun `a window with no days is rejected`() {
        val result = validate(
            StructuredConstraint.AvoidWindow(emptySet(), at(9), at(12), "x"),
        )
        assertTrue(result is ValidationResult.Rejected)
        assertTrue(result.reason.contains("which days"))
    }

    @Test
    fun `a window that would leave no usable time at all is rejected`() {
        // College 12:00-22:00 and sleep 22:00-06:00 leave only the morning.
        // Asking to keep the morning clear too leaves nothing whatsoever.
        val result = validate(
            mornings(),
            blocks = listOf(staticBlock("college", from = at(12), to = at(22))),
            recovery = listOf(sleep(bedtime = at(22), wake = at(6), windDownMinutes = 0)),
        )
        assertTrue(
            result is ValidationResult.Rejected,
            "a change that empties the day must never be offered as acceptable",
        )
        assertTrue(result.reason.contains("no usable time"))
    }

    @Test
    fun `a window that merely squeezes the day is accepted with a warning`() {
        val result = validate(
            StructuredConstraint.AvoidWindow(
                daysOfWeek = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                from = at(6),
                to = at(12),
                label = "mornings",
            ),
            blocks = listOf(
                staticBlock(
                    "wednesday-only",
                    from = at(12),
                    to = at(23, 30),
                    days = setOf(DayOfWeek.WEDNESDAY),
                ),
            ),
            // Without sleep the small hours count as free, and nothing is
            // actually tight on either day.
            recovery = listOf(sleep(bedtime = at(22), wake = at(6), windDownMinutes = 0)),
        )
        // Wednesday is squeezed to nothing, Thursday still has its afternoon,
        // so it is offered with a note rather than refused outright.
        assertTrue(result is ValidationResult.Accepted)
        assertTrue(result.hasWarnings)
        assertTrue(result.warnings.single().contains("Wednesday"))
    }

    @Test
    fun `the rejection explains itself in terms of the actual day`() {
        val result = validate(
            mornings(),
            blocks = listOf(staticBlock("college", from = at(12), to = at(22))),
            recovery = listOf(sleep(bedtime = at(22), wake = at(6), windDownMinutes = 0)),
        ) as ValidationResult.Rejected
        assertTrue(result.reason.contains("fixed blocks"))
    }

    // --- general -------------------------------------------------------------

    @Test
    fun `validation is pure and repeatable`() {
        val constraint = busy(2, 6)
        assertEquals(validate(constraint), validate(constraint))
    }

    @Test
    fun `every constraint kind describes itself for the confirmation screen`() {
        val all = listOf(
            busy(1, 3),
            StructuredConstraint.AvoidWindow(setOf(DayOfWeek.MONDAY), at(9), at(12), "x"),
            StructuredConstraint.DeadlineChange("Goal", TEST_DATE.plusDays(3)),
            StructuredConstraint.Unrecognised("no"),
        )
        assertTrue(all.all { it.describe().isNotBlank() })
        assertEquals(4, all.map { it.describe() }.distinct().size)
    }
}
