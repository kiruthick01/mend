package com.mend.app.repository

import com.mend.engine.time.TimeInterval
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lines the widget and the lock-screen note both draw.
 *
 * Worth pinning because these two surfaces are the ones the user sees most and
 * opens least: a wrong line here is a wrong line that sits on the home screen
 * all day without anything prompting a second look.
 */
class NowNextTest {

    private val date = LocalDate.of(2026, 9, 16)

    @Test
    fun `a running task reads as time left and an end time`() {
        val state = NowNext(
            currentTitle = "Grammar 7",
            currentEndsAt = "10:20",
            minutesLeft = 40,
        )

        assertEquals("40m left  ·  until 10:20", state.remainingLine())
    }

    @Test
    fun `nothing running means no remaining line at all`() {
        assertNull(NowNext(nextTitle = "Gym", nextStartsAt = "18:00").remainingLine())
    }

    @Test
    fun `the next line names the task and when it starts`() {
        val state = NowNext(nextTitle = "Gym", nextStartsAt = "18:00")
        assertEquals("Next: Gym at 18:00", state.nextLine())
    }

    @Test
    fun `a next task with no start time still gets named`() {
        assertEquals("Next: Gym", NowNext(nextTitle = "Gym").nextLine())
    }

    @Test
    fun `nothing at all is empty, and empty is what suppresses both surfaces`() {
        assertTrue(NowNext.Empty.isEmpty)
        assertNull(NowNext.Empty.nextLine())
        assertNull(NowNext.Empty.remainingLine())
    }

    @Test
    fun `only goal work offers a done button`() {
        val fixed = NowNext(
            currentTitle = "College",
            currentSlot = TimeInterval(date.atTime(9, 0), date.atTime(10, 0)),
        )
        assertFalse(fixed.isCompletable, "a fixed block is not something you finish early")

        val work = fixed.copy(currentTaskId = "sub-1")
        assertTrue(work.isCompletable)
    }
}
