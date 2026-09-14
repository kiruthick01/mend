package com.mend.app.ui.components

import com.mend.engine.nlp.StructuredConstraint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The instruction that replaced the button.
 *
 * Mend reads the prose but cannot apply it, so the dialog's only remaining job
 * is to tell the user exactly what to go and do. That makes this string the
 * whole feature rather than a label on it: if it names the wrong dates, the
 * wrong goal or the wrong screen, the user follows it and gets a schedule that
 * does not match what they asked for — and they will blame the app, correctly.
 */
class HowToApplyTest {

    @Test
    fun `a busy period names the block, both dates and the screen`() {
        val text = howToApply(
            StructuredConstraint.BusyPeriod(
                from = LocalDate.of(2026, 9, 20),
                to = LocalDate.of(2026, 9, 25),
                label = "exams",
            ),
        )

        assertTrue(text.contains("exams"), text)
        assertTrue(text.contains("2026-09-20"), text)
        assertTrue(text.contains("2026-09-25"), text)
        assertTrue(text.contains("Tasks"), text)
        assertTrue(text.contains("fixed block"), text)
    }

    @Test
    fun `an avoid window names every day it applies to, readably`() {
        val text = howToApply(
            StructuredConstraint.AvoidWindow(
                daysOfWeek = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                from = LocalTime.of(18, 0),
                to = LocalTime.of(19, 30),
                label = "gym",
            ),
        )

        assertTrue(text.contains("Tuesday"), text)
        assertTrue(text.contains("Thursday"), text)
        // Not TUESDAY: this is read by a person, not a parser.
        assertTrue(!text.contains("TUESDAY"), text)
        assertTrue(text.contains("18:00"), text)
        assertTrue(text.contains("19:30"), text)
        assertTrue(text.contains("gym"), text)
    }

    @Test
    fun `a deadline change names the goal and the new date`() {
        val text = howToApply(
            StructuredConstraint.DeadlineChange(
                goalTitleHint = "JLPT N4",
                newDeadline = LocalDate.of(2026, 12, 1),
            ),
        )

        assertTrue(text.contains("JLPT N4"), text)
        assertTrue(text.contains("2026-12-01"), text)
        assertTrue(text.contains("deadline"), text)
    }

    @Test
    fun `an unrecognised constraint asks for a better sentence rather than crashing`() {
        // Filtered out before the screen is reached, but the branch has to be
        // total and must not invent an instruction for something unparsed.
        val text = howToApply(StructuredConstraint.Unrecognised("no dates in it"))

        assertTrue(text.isNotBlank())
        assertTrue(!text.contains("Tasks"), "must not send the user off to do nothing")
    }
}
