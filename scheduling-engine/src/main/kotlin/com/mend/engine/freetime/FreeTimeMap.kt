package com.mend.engine.freetime

import com.mend.engine.model.Habit
import com.mend.engine.model.ScheduleEntry
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.WindowAnchor
import com.mend.engine.time.Intervals
import com.mend.engine.time.TimeInterval
import java.time.LocalDate
import java.time.LocalTime

/**
 * The shape of a single day once its immovable parts are laid down.
 *
 * [occupied] and [free] are both normalized and ascending, and together they
 * tile the whole day exactly once. [placements] records where each static
 * block and habit actually landed, which is what the Today screen renders and
 * what makes a partial-window decision auditable after the fact.
 */
data class FreeTimeMap(
    val date: LocalDate,
    val dayBounds: TimeInterval,
    val placements: List<EntryPlacement>,
    val occupied: List<TimeInterval>,
    val free: List<TimeInterval>,
) {
    val totalFreeMinutes: Long get() = Intervals.totalMinutes(free)
    val totalOccupiedMinutes: Long get() = Intervals.totalMinutes(occupied)

    /** The largest single unbroken stretch of free time, or null if there is none. */
    fun longestFreeInterval(): TimeInterval? = free.maxByOrNull { it.minutes }
}

/** Where one immovable entry was laid down, and why it landed there. */
data class EntryPlacement(
    val entry: ScheduleEntry,
    val interval: TimeInterval,
    val reason: String,
)

/**
 * Computes the free time map for [date]: everything in the day that is not
 * already spoken for by a static block or a habit.
 *
 * This is the foundation the whole scheduler stands on, so its rules are kept
 * few and blunt:
 *
 *  1. The day runs from [dayStart] to [dayEnd] (00:00 to 24:00 by default).
 *  2. Only entries whose `daysOfWeek` include this date's weekday apply.
 *  3. A **static block** with no `requiredDuration` occupies its whole window.
 *  4. A **static block** with a `requiredDuration` shorter than its window —
 *     a 2-hour gym window that needs 45 minutes — occupies only that much,
 *     anchored to the [WindowAnchor.EARLIEST] or [WindowAnchor.LATEST] end of
 *     the window. The rest of the window stays free.
 *  5. A **habit** occupies `timeOfDay` for its duration.
 *  6. Free time is the day minus the union of all of the above. Overlapping
 *     and back-to-back occupied spans are merged, so no zero-width slivers
 *     survive into the free map.
 *
 * Static blocks and habits are never reordered, shrunk or dropped here. This
 * function only reports the grid; it never negotiates with it.
 *
 * Pure: same inputs always give the same output, with no clock, no I/O and no
 * Android.
 */
fun computeFreeTimeMap(
    date: LocalDate,
    staticBlocks: List<StaticBlock> = emptyList(),
    habits: List<Habit> = emptyList(),
    dayStart: LocalTime = LocalTime.MIDNIGHT,
    dayEnd: LocalTime? = null,
): FreeTimeMap {
    val startAt = date.atTime(dayStart)
    // `null` end means "to the end of this calendar day", which is midnight of
    // the following date — LocalTime cannot express 24:00.
    val endAt = if (dayEnd == null) date.plusDays(1).atStartOfDay() else date.atTime(dayEnd)
    require(endAt.isAfter(startAt)) {
        "Day bounds for $date are empty or inverted: $startAt to $endAt"
    }
    val bounds = TimeInterval(startAt, endAt)
    val weekday = date.dayOfWeek

    val placements = buildList {
        staticBlocks
            .filter { it.appliesOn(weekday) }
            .sortedBy { it.windowStart }
            .forEach { block -> placeStaticBlock(block, date, bounds)?.let { add(it) } }

        habits
            .filter { it.appliesOn(weekday) }
            .sortedBy { it.timeOfDay }
            .forEach { habit -> placeHabit(habit, date, bounds)?.let { add(it) } }
    }.sortedBy { it.interval }

    val occupied = Intervals.normalize(placements.map { it.interval })
    val free = Intervals.subtract(listOf(bounds), occupied)

    return FreeTimeMap(
        date = date,
        dayBounds = bounds,
        placements = placements,
        occupied = occupied,
        free = free,
    )
}

private fun placeStaticBlock(
    block: StaticBlock,
    date: LocalDate,
    bounds: TimeInterval,
): EntryPlacement? {
    val windowStart = date.atTime(block.windowStart)
    val windowEnd = date.atTime(block.windowEnd)

    val interval = if (!block.isPartialWindow) {
        TimeInterval(windowStart, windowEnd)
    } else {
        val needed = block.occupiedDuration
        when (block.anchor) {
            WindowAnchor.EARLIEST -> TimeInterval(windowStart, windowStart.plus(needed))
            WindowAnchor.LATEST -> TimeInterval(windowEnd.minus(needed), windowEnd)
        }
    }

    val clipped = interval.intersect(bounds) ?: return null

    val reason = when {
        !block.isPartialWindow ->
            "static block occupies its full window ${block.windowStart}-${block.windowEnd}"
        block.anchor == WindowAnchor.EARLIEST ->
            "static block needs ${block.occupiedDuration.toMinutes()}m of the " +
                "${block.windowDuration.toMinutes()}m window " +
                "${block.windowStart}-${block.windowEnd}; anchored to the start, " +
                "remainder of the window left free"
        else ->
            "static block needs ${block.occupiedDuration.toMinutes()}m of the " +
                "${block.windowDuration.toMinutes()}m window " +
                "${block.windowStart}-${block.windowEnd}; anchored to the end, " +
                "remainder of the window left free"
    }

    return EntryPlacement(entry = block, interval = clipped, reason = reason)
}

private fun placeHabit(
    habit: Habit,
    date: LocalDate,
    bounds: TimeInterval,
): EntryPlacement? {
    val start = date.atTime(habit.timeOfDay)
    val interval = TimeInterval(start, start.plus(habit.duration))
    val clipped = interval.intersect(bounds) ?: return null

    return EntryPlacement(
        entry = habit,
        interval = clipped,
        reason = "habit fixed at ${habit.timeOfDay} for ${habit.duration.toMinutes()}m; " +
            "never ranked, never reshuffled",
    )
}
