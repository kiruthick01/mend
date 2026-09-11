package com.mend.engine.interrupt

import com.mend.engine.TEST_DATE
import com.mend.engine.allocation.allocate
import com.mend.engine.at
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.goalTask
import com.mend.engine.habit
import com.mend.engine.model.Flexibility
import com.mend.engine.render
import com.mend.engine.staticBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterruptTest {

    // --- qualification: deliberately narrow ---------------------------------

    @Test
    fun `recurring work is never an interrupt however urgent`() {
        val q = qualifyAsInterrupt(
            task = goalTask("daily", deadlineIn = 0, minutes = 60),
            isOneShot = false,
            today = TEST_DATE,
            futureFreeMinutesBeforeDeadline = 0,
        )
        assertFalse(q.qualifies)
        assertTrue(q.reason.contains("not a one-shot"))
    }

    @Test
    fun `a one-shot due today qualifies`() {
        val q = qualifyAsInterrupt(
            task = goalTask("form", deadlineIn = 0, minutes = 30),
            isOneShot = true,
            today = TEST_DATE,
            futureFreeMinutesBeforeDeadline = 10_000,
        )
        assertTrue(q.qualifies)
        assertTrue(q.reason.contains("deadline of today"))
    }

    @Test
    fun `a one-shot already overdue qualifies`() {
        val q = qualifyAsInterrupt(
            task = goalTask("form", deadlineIn = -3, minutes = 30),
            isOneShot = true,
            today = TEST_DATE,
            futureFreeMinutesBeforeDeadline = 0,
        )
        assertTrue(q.qualifies)
        assertTrue(q.reason.contains("3d ago"))
    }

    @Test
    fun `a one-shot with no free slot before its deadline qualifies`() {
        val q = qualifyAsInterrupt(
            task = goalTask("visa", deadlineIn = 4, minutes = 120),
            isOneShot = true,
            today = TEST_DATE,
            futureFreeMinutesBeforeDeadline = 45,
        )
        assertTrue(q.qualifies)
        assertTrue(q.reason.contains("no later slot"))
    }

    @Test
    fun `a one-shot with room to spare is just a task`() {
        val q = qualifyAsInterrupt(
            task = goalTask("visa", deadlineIn = 7, minutes = 120),
            isOneShot = true,
            today = TEST_DATE,
            futureFreeMinutesBeforeDeadline = 600,
        )
        assertFalse(q.qualifies)
        assertTrue(q.reason.contains("which is enough"))
    }

    @Test
    fun `free time exactly equal to the need does not qualify`() {
        val q = qualifyAsInterrupt(
            task = goalTask("visa", deadlineIn = 5, minutes = 120),
            isOneShot = true,
            today = TEST_DATE,
            futureFreeMinutesBeforeDeadline = 120,
        )
        assertFalse(q.qualifies)
    }

    @Test
    fun `every qualification carries its reasoning either way`() {
        val yes = qualifyAsInterrupt(
            goalTask("a", deadlineIn = 0), true, TEST_DATE, 0,
        )
        val no = qualifyAsInterrupt(
            goalTask("b", deadlineIn = 20), true, TEST_DATE, 10_000,
        )
        assertTrue(yes.reason.isNotBlank())
        assertTrue(no.reason.isNotBlank())
    }

    // --- claiming a slot -----------------------------------------------------

    /** 09:00-17:00 is college; free either side within a 07:00-21:00 day. */
    private fun collegeDay() = allocate(
        computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("college", from = at(9), to = at(17))),
            habits = listOf(habit("dinner-prep", at = at(18), minutes = 20)),
            dayStart = at(7),
            dayEnd = at(21),
        ),
        emptyList(),
    )

    private val qualified = InterruptQualification.yes("urgent", "test fixture")

    @Test
    fun `an interrupt claims the next free interval`() {
        val plan = planInterrupt(
            now = TEST_DATE.atTime(7, 30),
            schedule = collegeDay(),
            task = goalTask("urgent", deadlineIn = 0, minutes = 60),
            qualification = qualified,
        )
        assertEquals("07:30-08:30", listOf(plan.slot!!).render().single())
        assertTrue(plan.isAssignedATime)
        assertNull(plan.negotiatedDelayLimit)
    }

    @Test
    fun `an interrupt never cuts into a static block`() {
        // 10:00 is mid-college; the next genuinely free time is 17:00.
        val plan = planInterrupt(
            now = TEST_DATE.atTime(10, 0),
            schedule = collegeDay(),
            task = goalTask("urgent", deadlineIn = 0, minutes = 30),
            qualification = qualified,
        )
        val slot = plan.slot!!
        assertTrue(slot.start.hour >= 17, "interrupt claimed $slot, inside college hours")
    }

    @Test
    fun `an interrupt never cuts into a habit`() {
        val schedule = collegeDay()
        val plan = planInterrupt(
            now = TEST_DATE.atTime(17, 30),
            schedule = schedule,
            task = goalTask("urgent", deadlineIn = 0, minutes = 120),
            qualification = qualified,
        )
        val habitSlot = schedule.freeTimeMap.placements
            .first { it.entry.id == "dinner-prep" }.interval
        assertFalse(plan.slot!!.overlaps(habitSlot))
    }

    @Test
    fun `time already past today is not offered to an interrupt`() {
        val plan = planInterrupt(
            now = TEST_DATE.atTime(18, 30),
            schedule = collegeDay(),
            task = goalTask("urgent", deadlineIn = 0, minutes = 30),
            qualification = qualified,
        )
        assertTrue(!plan.slot!!.start.isBefore(TEST_DATE.atTime(18, 30)))
    }

    @Test
    fun `an interrupt too big for any gap takes the largest and negotiates the rest`() {
        val plan = planInterrupt(
            now = TEST_DATE.atTime(18, 30),
            schedule = collegeDay(),
            // Only 18:30-21:00 remains: 150 minutes for a 240-minute job.
            task = goalTask("urgent", deadlineIn = 0, minutes = 240),
            qualification = qualified,
        )
        assertEquals(150, plan.slot!!.minutes)
        assertEquals(90, plan.negotiatedDelayLimit!!.toMinutes())
        assertTrue(plan.message.contains("short of what it needs"))
    }

    @Test
    fun `an interrupt with no free time left today is deferred to tomorrow`() {
        val plan = planInterrupt(
            now = TEST_DATE.atTime(20, 59),
            schedule = collegeDay(),
            task = goalTask("urgent", deadlineIn = 0, minutes = 60),
            qualification = qualified,
        )
        assertNull(plan.slot)
        assertFalse(plan.isAssignedATime)
        assertEquals(60, plan.negotiatedDelayLimit!!.toMinutes())
        assertTrue(plan.message.contains("tomorrow"))
    }

    @Test
    fun `an interrupt names the scheduled work it displaces`() {
        val schedule = allocate(
            computeFreeTimeMap(
                date = TEST_DATE,
                dayStart = at(9),
                dayEnd = at(12),
            ),
            listOf(goalTask("revision", deadlineIn = 1, minutes = 180)),
        )
        val plan = planInterrupt(
            now = TEST_DATE.atTime(9, 0),
            schedule = schedule,
            task = goalTask("urgent", deadlineIn = 0, minutes = 60),
            qualification = qualified,
        )
        // The day is fully booked with goal work, which an emergency may push
        // aside — unlike a static block. The cost is named, not absorbed.
        assertEquals("09:00-10:00", listOf(plan.slot!!).render().single())
        assertEquals(listOf("revision"), plan.displaced.map { it.task.id })
        assertTrue(plan.message.contains("It displaces revision"))
    }

    @Test
    fun `the plan carries the qualification that justified it`() {
        val plan = planInterrupt(
            now = TEST_DATE.atTime(7, 30),
            schedule = collegeDay(),
            task = goalTask("urgent", deadlineIn = 0, minutes = 30),
            qualification = qualified,
        )
        assertTrue(plan.qualification.qualifies)
        assertEquals("urgent", plan.qualification.taskId)
    }

    // --- overrun -------------------------------------------------------------

    private fun eveningWithWork() = allocate(
        computeFreeTimeMap(date = TEST_DATE, dayStart = at(17), dayEnd = at(22)),
        listOf(
            goalTask("reading", deadlineIn = 1, minutes = 60, flexibility = Flexibility.MOVABLE),
            goalTask("problems", deadlineIn = 2, minutes = 60, flexibility = Flexibility.MOVABLE),
        ),
    )

    @Test
    fun `work still inside its slot is not an overrun`() {
        val schedule = eveningWithWork()
        val plan = planInterrupt(
            now = TEST_DATE.atTime(17, 0),
            schedule = schedule,
            task = goalTask("urgent", deadlineIn = 0, minutes = 30),
            qualification = qualified,
        )
        val impact = describeOverrun(TEST_DATE.atTime(17, 20), plan, schedule)
        assertEquals(0, impact.overrunMinutes)
        assertFalse(impact.hasCost)
    }

    @Test
    fun `an overrun says exactly what today has lost`() {
        val schedule = eveningWithWork()
        val plan = planInterrupt(
            now = TEST_DATE.atTime(17, 0),
            schedule = schedule,
            task = goalTask("urgent", deadlineIn = 0, minutes = 30),
            qualification = qualified,
        )
        // Slot ended 17:30; it is now 19:15, so 105 minutes over.
        val impact = describeOverrun(TEST_DATE.atTime(19, 15), plan, schedule)
        assertEquals(105, impact.overrunMinutes)
        assertTrue(impact.hasCost)
        assertTrue(impact.message.contains("Here is what today has lost"))
        assertTrue(impact.message.contains("reading"))
        assertTrue(impact.message.contains("in total"))
    }

    @Test
    fun `an overrun that costs nothing says so rather than implying loss`() {
        val emptyEvening = allocate(
            computeFreeTimeMap(date = TEST_DATE, dayStart = at(17), dayEnd = at(22)),
            emptyList(),
        )
        val plan = planInterrupt(
            now = TEST_DATE.atTime(17, 0),
            schedule = emptyEvening,
            task = goalTask("urgent", deadlineIn = 0, minutes = 30),
            qualification = qualified,
        )
        val impact = describeOverrun(TEST_DATE.atTime(19, 0), plan, emptyEvening)
        assertFalse(impact.hasCost)
        assertTrue(impact.message.contains("today is intact"))
    }

    @Test
    fun `the lost minutes are counted, not estimated`() {
        val schedule = eveningWithWork()
        val plan = planInterrupt(
            now = TEST_DATE.atTime(17, 0),
            schedule = schedule,
            task = goalTask("urgent", deadlineIn = 0, minutes = 30),
            qualification = qualified,
        )
        val impact = describeOverrun(TEST_DATE.atTime(22, 0), plan, schedule)
        // The slot ended 17:30. Reading (17:00-18:00) loses its last 30m to the
        // overrun; problems (18:00-19:00) loses all 60m.
        assertEquals(90, impact.lostMinutes)
        assertEquals(2, impact.lost.size)
    }

    @Test
    fun `an interrupt with no slot cannot overrun`() {
        val schedule = eveningWithWork()
        val plan = planInterrupt(
            now = TEST_DATE.atTime(21, 59),
            schedule = schedule,
            task = goalTask("urgent", deadlineIn = 0, minutes = 60),
            qualification = qualified,
        )
        assertNull(plan.slot)
        val impact = describeOverrun(TEST_DATE.atTime(23, 0), plan, schedule)
        assertEquals(0, impact.overrunMinutes)
    }
}
