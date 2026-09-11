package com.mend.engine.adherence

import com.mend.engine.TEST_DATE
import com.mend.engine.allocation.allocate
import com.mend.engine.at
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.goalTask
import com.mend.engine.model.Flexibility
import com.mend.engine.staticBlock
import com.mend.engine.time.TimeInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissDetectionTest {

    /** 09:00-11:00 booked with two hour-long movable tasks. */
    private fun morning() = allocate(
        computeFreeTimeMap(date = TEST_DATE, dayStart = at(9), dayEnd = at(11)),
        listOf(
            goalTask("first", deadlineIn = 0, minutes = 60, flexibility = Flexibility.MOVABLE),
            goalTask("second", deadlineIn = 1, minutes = 60, flexibility = Flexibility.MOVABLE),
        ),
    )

    @Test
    fun `work still inside its slot is not a miss`() {
        val misses = detectElapsedMisses(morning(), now = TEST_DATE.atTime(9, 30))
        assertEquals(emptyList(), misses)
    }

    @Test
    fun `work whose slot has fully elapsed is a miss`() {
        val misses = detectElapsedMisses(morning(), now = TEST_DATE.atTime(10, 15))
        assertEquals(listOf("first"), misses.map { it.taskId })
    }

    @Test
    fun `a slot ending exactly now counts as elapsed`() {
        val misses = detectElapsedMisses(morning(), now = TEST_DATE.atTime(10, 0))
        assertEquals(listOf("first"), misses.map { it.taskId })
    }

    @Test
    fun `completed work is never reported as missed`() {
        val misses = detectElapsedMisses(
            morning(),
            now = TEST_DATE.atTime(11, 0),
            completedTaskIds = setOf("first"),
        )
        assertEquals(listOf("second"), misses.map { it.taskId })
    }

    @Test
    fun `work already logged is not logged twice`() {
        val misses = detectElapsedMisses(
            morning(),
            now = TEST_DATE.atTime(11, 0),
            alreadyLogged = setOf("first", "second"),
        )
        assertEquals(emptyList(), misses)
    }

    @Test
    fun `a split task is judged on its last slice, not its first`() {
        // 07:00-09:00 and 16:00-20:00, with three hours of splittable work.
        val schedule = allocate(
            computeFreeTimeMap(
                date = TEST_DATE,
                staticBlocks = listOf(staticBlock("college", from = at(9), to = at(16))),
                dayStart = at(7),
                dayEnd = at(20),
            ),
            listOf(
                goalTask(
                    "revision",
                    deadlineIn = 0,
                    minutes = 180,
                    flexibility = Flexibility.SPLITTABLE,
                ),
            ),
        )
        assertTrue(schedule.placements.single().isSplit)

        // Midday: the morning slice has gone but the evening one has not.
        assertEquals(
            emptyList(),
            detectElapsedMisses(schedule, now = TEST_DATE.atTime(12, 0)),
        )
        // After the evening slice, it is genuinely missed.
        assertEquals(
            listOf("revision"),
            detectElapsedMisses(schedule, now = TEST_DATE.atTime(19, 0)).map { it.taskId },
        )
    }

    @Test
    fun `a miss records when it was scheduled and why it counts`() {
        val miss = detectElapsedMisses(morning(), now = TEST_DATE.atTime(10, 30)).single()
        assertEquals(TEST_DATE.atTime(9, 0), miss.slot.start)
        assertEquals(TEST_DATE.atTime(10, 0), miss.slot.end)
        assertTrue(miss.reason.contains("09:00-10:00"))
        assertTrue(miss.reason.contains("without it being marked done"))
    }

    @Test
    fun `a split miss says which slice it was judged on`() {
        val schedule = allocate(
            computeFreeTimeMap(
                date = TEST_DATE,
                staticBlocks = listOf(staticBlock("college", from = at(9), to = at(16))),
                dayStart = at(7),
                dayEnd = at(20),
            ),
            listOf(
                goalTask(
                    "revision",
                    deadlineIn = 0,
                    minutes = 180,
                    flexibility = Flexibility.SPLITTABLE,
                ),
            ),
        )
        val miss = detectElapsedMisses(schedule, now = TEST_DATE.atTime(19, 0)).single()
        assertTrue(miss.reason.contains("last of 2 slices"))
    }

    @Test
    fun `an empty schedule produces no misses`() {
        val empty = allocate(
            computeFreeTimeMap(date = TEST_DATE, dayStart = at(9), dayEnd = at(11)),
            emptyList(),
        )
        assertEquals(emptyList(), detectElapsedMisses(empty, now = TEST_DATE.atTime(23, 0)))
    }

    // --- delay against a slot ------------------------------------------------

    @Test
    fun `finishing inside the slot is not late`() {
        val slot = TimeInterval(TEST_DATE.atTime(9, 0), TEST_DATE.atTime(10, 0))
        assertEquals(0, delayAgainstSlot(slot, TEST_DATE.atTime(9, 45)))
    }

    @Test
    fun `finishing exactly at the end of the slot is not late`() {
        val slot = TimeInterval(TEST_DATE.atTime(9, 0), TEST_DATE.atTime(10, 0))
        assertEquals(0, delayAgainstSlot(slot, TEST_DATE.atTime(10, 0)))
    }

    @Test
    fun `finishing after the slot is late by the difference`() {
        val slot = TimeInterval(TEST_DATE.atTime(9, 0), TEST_DATE.atTime(10, 0))
        assertEquals(35, delayAgainstSlot(slot, TEST_DATE.atTime(10, 35)))
    }

    @Test
    fun `work with no slot cannot be late for an appointment it never had`() {
        assertEquals(0, delayAgainstSlot(null, TEST_DATE.atTime(23, 59)))
    }
}
