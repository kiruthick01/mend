package com.mend.engine.allocation

import com.mend.engine.TEST_DATE
import com.mend.engine.at
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.goalTask
import com.mend.engine.habit
import com.mend.engine.model.Flexibility
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.Weights
import com.mend.engine.render
import com.mend.engine.staticBlock
import java.time.DayOfWeek
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllocationTest {

    /** A day with two workable gaps: 07:00-09:00 and 16:00-20:00. */
    private fun twoGapDay() = computeFreeTimeMap(
        date = TEST_DATE,
        staticBlocks = listOf(staticBlock("college", from = at(9), to = at(16))),
        dayStart = at(7),
        dayEnd = at(20),
    )

    private fun openDay(from: Int = 9, to: Int = 17) =
        computeFreeTimeMap(date = TEST_DATE, dayStart = at(from), dayEnd = at(to))

    // --- basic placement -----------------------------------------------------

    @Test
    fun `a task is placed in the earliest interval that fits it`() {
        val schedule = allocate(
            twoGapDay(),
            listOf(goalTask("read", deadlineIn = 3, minutes = 60)),
        )
        assertEquals(listOf("07:00-08:00"), schedule.placements.single().slices.render())
        assertTrue(schedule.unscheduled.isEmpty())
    }

    @Test
    fun `higher priority gets first pick of the free time`() {
        val schedule = allocate(
            openDay(9, 11),
            listOf(
                goalTask("low", deadlineIn = 29, importance = 1, minutes = 60),
                goalTask("high", deadlineIn = 0, importance = 5, minutes = 60),
            ),
        )
        val first = schedule.placements.first()
        assertEquals("high", first.task.id)
        assertEquals(listOf("09:00-10:00"), first.slices.render())
        assertEquals(listOf("10:00-11:00"), schedule.placements[1].slices.render())
    }

    @Test
    fun `nothing to place leaves the day untouched`() {
        val schedule = allocate(openDay(), emptyList())
        assertTrue(schedule.placements.isEmpty())
        assertEquals(listOf("09:00-17:00"), schedule.remainingFree.render())
    }

    @Test
    fun `placed work never overlaps a static block`() {
        val schedule = allocate(
            twoGapDay(),
            listOf(goalTask("long", deadlineIn = 0, minutes = 300)),
        )
        val college = schedule.freeTimeMap.placements.single().interval
        schedule.placements.flatMap { it.slices }.forEach { slice ->
            assertFalse(slice.overlaps(college), "$slice overlaps the college block")
        }
    }

    @Test
    fun `placed work never overlaps a habit`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            habits = listOf(habit("water", at = at(10), minutes = 15)),
            dayStart = at(9),
            dayEnd = at(12),
        )
        val schedule = allocate(map, listOf(goalTask("study", deadlineIn = 0, minutes = 120)))
        val waterSlot = map.placements.single().interval
        schedule.placements.flatMap { it.slices }.forEach { slice ->
            assertFalse(slice.overlaps(waterSlot))
        }
    }

    @Test
    fun `two tasks never occupy the same minute`() {
        val schedule = allocate(
            openDay(9, 12),
            listOf(
                goalTask("a", deadlineIn = 0, minutes = 60),
                goalTask("b", deadlineIn = 1, minutes = 60),
                goalTask("c", deadlineIn = 2, minutes = 60),
            ),
        )
        val slices = schedule.placements.flatMap { it.slices }.sorted()
        slices.zipWithNext().forEach { (a, b) ->
            assertFalse(a.overlaps(b), "$a overlaps $b")
        }
    }

    // --- overfull day --------------------------------------------------------

    @Test
    fun `an overfull day places what fits and reports the rest as unscheduled`() {
        // Two hours of free time, five hours of fixed work asking for it.
        val schedule = allocate(
            openDay(9, 11),
            listOf(
                goalTask("a", deadlineIn = 0, importance = 5, minutes = 60, flexibility = Flexibility.FIXED),
                goalTask("b", deadlineIn = 1, importance = 5, minutes = 60, flexibility = Flexibility.FIXED),
                goalTask("c", deadlineIn = 2, importance = 5, minutes = 60, flexibility = Flexibility.FIXED),
                goalTask("d", deadlineIn = 3, importance = 5, minutes = 60, flexibility = Flexibility.FIXED),
                goalTask("e", deadlineIn = 4, importance = 5, minutes = 60, flexibility = Flexibility.FIXED),
            ),
        )
        assertEquals(listOf("a", "b"), schedule.placements.map { it.task.id })
        assertEquals(listOf("c", "d", "e"), schedule.unscheduled.map { it.task.id })
        assertEquals(0, schedule.remainingFreeMinutes)
    }

    @Test
    fun `on an overfull day the lowest priority work is what gets dropped`() {
        val schedule = allocate(
            openDay(9, 10),
            listOf(
                goalTask("trivial", deadlineIn = 29, importance = 1, minutes = 60, flexibility = Flexibility.FIXED),
                goalTask("critical", deadlineIn = 0, importance = 5, minutes = 60, flexibility = Flexibility.FIXED),
            ),
        )
        assertEquals(listOf("critical"), schedule.placements.map { it.task.id })
        assertEquals(listOf("trivial"), schedule.unscheduled.map { it.task.id })
    }

    @Test
    fun `every unscheduled task carries a reason`() {
        val schedule = allocate(
            openDay(9, 10),
            listOf(
                goalTask("fits", deadlineIn = 0, minutes = 60, flexibility = Flexibility.FIXED),
                goalTask("does-not", deadlineIn = 1, minutes = 60, flexibility = Flexibility.FIXED),
            ),
        )
        val rejected = schedule.unscheduled.single()
        assertTrue(rejected.reason.isNotBlank())
        assertTrue(rejected.reason.contains("free"))
    }

    // --- FIXED ---------------------------------------------------------------

    @Test
    fun `a FIXED task that cannot fit one interval is not scheduled at all`() {
        // Two 60-minute gaps, but the task needs 90 unbroken.
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("gap", from = at(10), to = at(11))),
            dayStart = at(9),
            dayEnd = at(12),
        )
        val schedule = allocate(
            map,
            listOf(goalTask("exam", deadlineIn = 0, minutes = 90, flexibility = Flexibility.FIXED)),
        )
        assertTrue(schedule.placements.isEmpty())
        assertEquals("exam", schedule.unscheduled.single().task.id)
        assertTrue(schedule.unscheduled.single().reason.contains("unbroken"))
    }

    @Test
    fun `a FIXED task that fits exactly is placed`() {
        val schedule = allocate(
            openDay(9, 10),
            listOf(goalTask("exam", deadlineIn = 0, minutes = 60, flexibility = Flexibility.FIXED)),
        )
        assertEquals(listOf("09:00-10:00"), schedule.placements.single().slices.render())
    }

    @Test
    fun `a MOVABLE task stays whole but takes a later interval when it must`() {
        // 09:00-09:30 then 10:00-12:00; a 90-minute movable task needs the second.
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("call", from = at(9, 30), to = at(10))),
            dayStart = at(9),
            dayEnd = at(12),
        )
        val schedule = allocate(
            map,
            listOf(goalTask("draft", deadlineIn = 0, minutes = 90, flexibility = Flexibility.MOVABLE)),
        )
        val placement = schedule.placements.single()
        assertEquals(listOf("10:00-11:30"), placement.slices.render())
        assertFalse(placement.isSplit)
    }

    // --- SPLITTABLE ----------------------------------------------------------

    @Test
    fun `a SPLITTABLE task spans multiple free intervals`() {
        // 07:00-09:00 and 16:00-20:00; a three-hour task needs both.
        val schedule = allocate(
            twoGapDay(),
            listOf(goalTask("revision", deadlineIn = 0, minutes = 180, flexibility = Flexibility.SPLITTABLE)),
        )
        val placement = schedule.placements.single()
        assertTrue(placement.isSplit)
        assertEquals(listOf("07:00-09:00", "16:00-17:00"), placement.slices.render())
        assertEquals(180, placement.scheduledMinutes)
        assertFalse(placement.isPartial)
    }

    @Test
    fun `a SPLITTABLE task that fits one interval is not split needlessly`() {
        val schedule = allocate(
            twoGapDay(),
            listOf(goalTask("read", deadlineIn = 0, minutes = 45, flexibility = Flexibility.SPLITTABLE)),
        )
        val placement = schedule.placements.single()
        assertFalse(placement.isSplit)
        assertEquals(listOf("07:00-07:45"), placement.slices.render())
    }

    @Test
    fun `a SPLITTABLE task takes what it can when the day runs short`() {
        val schedule = allocate(
            openDay(9, 11),
            listOf(goalTask("marathon", deadlineIn = 0, minutes = 300, flexibility = Flexibility.SPLITTABLE)),
        )
        val placement = schedule.placements.single()
        assertTrue(placement.isPartial)
        assertEquals(120, placement.scheduledMinutes)
        assertTrue(placement.reason.contains("outstanding"))
    }

    @Test
    fun `a split task fills the earlier interval first`() {
        val schedule = allocate(
            twoGapDay(),
            listOf(goalTask("study", deadlineIn = 0, minutes = 150, flexibility = Flexibility.SPLITTABLE)),
        )
        val slices = schedule.placements.single().slices
        assertEquals(120, slices.first().minutes)
        assertEquals(30, slices[1].minutes)
    }

    // --- SHRINKABLE ----------------------------------------------------------

    @Test
    fun `a SHRINKABLE task takes its full time when it fits`() {
        val schedule = allocate(
            openDay(9, 12),
            listOf(goalTask("practice", deadlineIn = 0, minutes = 60, flexibility = Flexibility.SHRINKABLE)),
        )
        val placement = schedule.placements.single()
        assertFalse(placement.isPartial)
        assertEquals(60, placement.scheduledMinutes)
    }

    @Test
    fun `a SHRINKABLE task is cut down rather than dropped`() {
        // Only 40 minutes free for a 60-minute request: above the 50% floor.
        val schedule = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(9), dayEnd = at(9, 40)),
            listOf(goalTask("practice", deadlineIn = 0, minutes = 60, flexibility = Flexibility.SHRINKABLE)),
        )
        val placement = schedule.placements.single()
        assertTrue(placement.isPartial)
        assertEquals(40, placement.scheduledMinutes)
        assertTrue(placement.reason.contains("shrunk"))
    }

    @Test
    fun `a SHRINKABLE task is dropped rather than cut below half`() {
        // 20 minutes free for a 60-minute request is below the 30-minute floor.
        val schedule = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(9), dayEnd = at(9, 20)),
            listOf(goalTask("practice", deadlineIn = 0, minutes = 60, flexibility = Flexibility.SHRINKABLE)),
        )
        assertTrue(schedule.placements.isEmpty())
        assertTrue(schedule.unscheduled.single().reason.contains("floor"))
    }

    // --- windows -------------------------------------------------------------

    @Test
    fun `a windowed task is only placed inside its window`() {
        val schedule = allocate(
            openDay(6, 22),
            listOf(
                goalTask(
                    "library",
                    deadlineIn = 0,
                    minutes = 60,
                    flexibility = Flexibility.MOVABLE,
                    windowFrom = at(14),
                    windowTo = at(16),
                ),
            ),
        )
        assertEquals(listOf("14:00-15:00"), schedule.placements.single().slices.render())
    }

    @Test
    fun `a windowed task with no free time in its window is unscheduled`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("college", from = at(9), to = at(17))),
            dayStart = at(6),
            dayEnd = at(22),
        )
        val schedule = allocate(
            map,
            listOf(
                goalTask(
                    "library",
                    deadlineIn = 0,
                    minutes = 60,
                    windowFrom = at(10),
                    windowTo = at(12),
                ),
            ),
        )
        assertTrue(schedule.unscheduled.single().reason.contains("window"))
    }

    // --- weekday filtering ---------------------------------------------------

    @Test
    fun `a task that does not apply today is skipped with a reason`() {
        // TEST_DATE is a Wednesday.
        val schedule = allocate(
            openDay(),
            listOf(goalTask("sunday-only", deadlineIn = 0, days = setOf(DayOfWeek.SUNDAY))),
        )
        assertTrue(schedule.placements.isEmpty())
        assertTrue(
            schedule.log.forTask("sunday-only").any { it.message.contains("does not apply") },
        )
    }

    // --- the log -------------------------------------------------------------

    @Test
    fun `every placement records why it landed where it did`() {
        val schedule = allocate(
            twoGapDay(),
            listOf(goalTask("read", deadlineIn = 3, minutes = 60)),
        )
        assertTrue(schedule.placements.all { it.reason.isNotBlank() })
    }

    @Test
    fun `the log explains a placement without recomputing anything`() {
        val schedule = allocate(
            twoGapDay(),
            listOf(goalTask("read", deadlineIn = 0, importance = 4, minutes = 60)),
        )
        val entries = schedule.log.forTask("read")
        val kinds = entries.map { it.kind }
        assertTrue(SchedulingLog.Kind.RANKED in kinds, "the ranking decision must be logged")
        assertTrue(SchedulingLog.Kind.PLACED in kinds, "the placement decision must be logged")

        val ranked = entries.first { it.kind == SchedulingLog.Kind.RANKED }
        assertTrue(ranked.message.contains("score"))
        assertTrue(ranked.message.contains("deadline"))
        assertTrue(ranked.message.contains("splittable"))
    }

    @Test
    fun `the log explains a refusal, not just a placement`() {
        val schedule = allocate(
            openDay(9, 10),
            listOf(goalTask("big", deadlineIn = 0, minutes = 180, flexibility = Flexibility.FIXED)),
        )
        val skipped = schedule.log.forTask("big").single { it.kind == SchedulingLog.Kind.SKIPPED }
        assertTrue(skipped.message.contains("180m"))
        assertTrue(skipped.message.contains("60m"))
    }

    @Test
    fun `the log opens with the inputs and closes with the totals`() {
        val schedule = allocate(
            twoGapDay(),
            listOf(goalTask("read", deadlineIn = 0, minutes = 60)),
        )
        assertEquals(SchedulingLog.Kind.INPUT, schedule.log.entries.first().kind)
        assertEquals(SchedulingLog.Kind.SUMMARY, schedule.log.entries.last().kind)
        assertTrue(schedule.log.render().isNotBlank())
    }

    // --- the timeline --------------------------------------------------------

    @Test
    fun `the timeline interleaves fixed entries and scheduled work in time order`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("college", from = at(9), to = at(16))),
            habits = listOf(habit("wake", at = at(7), minutes = 15)),
            dayStart = at(7),
            dayEnd = at(19),
        )
        val schedule = allocate(
            map,
            listOf(goalTask("read", deadlineIn = 0, minutes = 60, flexibility = Flexibility.MOVABLE)),
        )
        val timeline = schedule.timeline
        assertEquals(
            timeline.map { it.interval.start },
            timeline.map { it.interval.start }.sorted(),
        )
        assertTrue(timeline.any { it is TimelineEntry.Fixed })
        assertTrue(timeline.any { it is TimelineEntry.Work })
    }

    // --- determinism ---------------------------------------------------------

    @Test
    fun `allocation is pure and repeatable`() {
        val tasks = listOf(
            goalTask("a", deadlineIn = 2, minutes = 90),
            goalTask("b", deadlineIn = 5, minutes = 45, flexibility = Flexibility.FIXED),
        )
        assertEquals(
            allocate(twoGapDay(), tasks).placements,
            allocate(twoGapDay(), tasks).placements,
        )
    }

    @Test
    fun `input order does not change the resulting schedule`() {
        val tasks = listOf(
            goalTask("a", deadlineIn = 2, minutes = 90),
            goalTask("b", deadlineIn = 5, minutes = 45),
            goalTask("c", deadlineIn = 9, minutes = 30),
        )
        assertEquals(
            allocate(twoGapDay(), tasks).placements.map { it.task.id },
            allocate(twoGapDay(), tasks.reversed()).placements.map { it.task.id },
        )
    }

    @Test
    fun `scheduled work plus remaining free never exceeds the free time available`() {
        val map = twoGapDay()
        val schedule = allocate(
            map,
            listOf(
                goalTask("a", deadlineIn = 0, minutes = 120),
                goalTask("b", deadlineIn = 1, minutes = 200),
                goalTask("c", deadlineIn = 2, minutes = 90),
            ),
        )
        assertEquals(
            map.totalFreeMinutes,
            schedule.totalScheduledMinutes + schedule.remainingFreeMinutes,
        )
    }

    @Test
    fun `weights change which task wins the contested slot`() {
        val tasks = listOf(
            goalTask("urgent-trivial", deadlineIn = 0, importance = 1, minutes = 60, flexibility = Flexibility.FIXED),
            goalTask("distant-vital", deadlineIn = 25, importance = 5, minutes = 60, flexibility = Flexibility.FIXED),
        )
        val deadlineDriven = allocate(
            openDay(9, 10),
            tasks,
            Weights(deadline = 0.9, importance = 0.05, adherenceTrend = 0.05),
        )
        val importanceDriven = allocate(
            openDay(9, 10),
            tasks,
            Weights(deadline = 0.05, importance = 0.9, adherenceTrend = 0.05),
        )
        assertEquals("urgent-trivial", deadlineDriven.placements.single().task.id)
        assertEquals("distant-vital", importanceDriven.placements.single().task.id)
    }

    @Test
    fun `a full day of static blocks leaves nothing to allocate into`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                StaticBlock(
                    id = "all-day",
                    title = "Retreat",
                    windowStart = at(8),
                    windowEnd = at(20),
                    requiredDuration = Duration.ofHours(12),
                ),
            ),
            dayStart = at(8),
            dayEnd = at(20),
        )
        val schedule = allocate(map, listOf(goalTask("read", deadlineIn = 0, minutes = 30)))
        assertTrue(schedule.placements.isEmpty())
        assertEquals("read", schedule.unscheduled.single().task.id)
    }
}
