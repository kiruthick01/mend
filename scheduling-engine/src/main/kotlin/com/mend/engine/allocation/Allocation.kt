package com.mend.engine.allocation

import com.mend.engine.freetime.EntryPlacement
import com.mend.engine.freetime.FreeTimeMap
import com.mend.engine.model.Flexibility
import com.mend.engine.model.GoalTask
import com.mend.engine.model.ScheduleEntry
import com.mend.engine.model.Weights
import com.mend.engine.priority.RankedTask
import com.mend.engine.priority.rankByPriority
import com.mend.engine.time.Intervals
import com.mend.engine.time.TimeInterval
import com.mend.engine.tuning.Tuning
import java.time.Duration
import java.time.LocalDate

/**
 * Greedy slot allocation.
 *
 * The rule is deliberately blunt: rank every pending task, then walk the list
 * from the top and give each one first pick of the free time that is left.
 * There is no lookahead, no backtracking and no packing cleverness. A smarter
 * allocator would fit slightly more work into a full day and would be
 * impossible to explain when it put something somewhere surprising — and this
 * app is only worth using if its schedule can be argued with.
 *
 * Flexibility decides how a task may be bent, and nothing else:
 *  - [Flexibility.FIXED] must fit whole inside a single free interval, or it
 *    goes unscheduled.
 *  - [Flexibility.MOVABLE] is the same, but says so for a different reason:
 *    it is happy anywhere, as long as it stays in one piece.
 *  - [Flexibility.SHRINKABLE] will take a shorter slot rather than nothing,
 *    down to [MINIMUM_SHRINK_FRACTION] of what it asked for.
 *  - [Flexibility.SPLITTABLE] will spread across as many intervals as it takes.
 */

/** A shrinkable task will not be cut below this fraction of its request. */
const val MINIMUM_SHRINK_FRACTION: Double = Tuning.MINIMUM_SHRINK_FRACTION

/** Fragments shorter than this are not worth placing or leaving behind. */
val MINIMUM_USEFUL_SLICE: Duration = Tuning.MINIMUM_USEFUL_SLICE

/** The full computed shape of a day: fixed entries plus placed goal work. */
data class DaySchedule(
    val date: LocalDate,
    val freeTimeMap: FreeTimeMap,
    val placements: List<TaskPlacement>,
    val unscheduled: List<UnscheduledTask>,
    val remainingFree: List<TimeInterval>,
    val log: SchedulingLog,
) {
    /**
     * Everything that occupies time today, in time order: static blocks,
     * habits and scheduled goal work interleaved.
     */
    val timeline: List<TimelineEntry>
        get() = (
            freeTimeMap.placements.map {
                TimelineEntry.Fixed(it)
            } + placements.flatMap { placement ->
                placement.slices.map { TimelineEntry.Work(placement, it) }
            }
            ).sortedBy { it.interval.start }

    val totalScheduledMinutes: Long get() = placements.sumOf { it.scheduledMinutes }
    val remainingFreeMinutes: Long get() = Intervals.totalMinutes(remainingFree)
}

/** One row of the day's timeline. */
sealed interface TimelineEntry {
    val interval: TimeInterval

    /** A static block or habit, laid down by the free time map. */
    data class Fixed(val placement: EntryPlacement) : TimelineEntry {
        override val interval: TimeInterval get() = placement.interval
        val entry: ScheduleEntry get() = placement.entry
    }

    /** One slice of scheduled goal work. */
    data class Work(
        val placement: TaskPlacement,
        override val interval: TimeInterval,
    ) : TimelineEntry {
        val task: GoalTask get() = placement.task
    }
}

/** Where a task ended up, and how much of it fitted. */
data class TaskPlacement(
    val task: GoalTask,
    val score: Double,
    val slices: List<TimeInterval>,
    val requestedDuration: Duration,
    val reason: String,
) {
    val scheduledMinutes: Long get() = slices.sumOf { it.minutes }

    /** True when the task got less time than it asked for. */
    val isPartial: Boolean get() = scheduledMinutes < requestedDuration.toMinutes()

    val isSplit: Boolean get() = slices.size > 1
}

/** A task that could not be placed, and precisely why not. */
data class UnscheduledTask(
    val task: GoalTask,
    val score: Double,
    val reason: String,
)

/** Structured record of every decision the allocator made. */
data class SchedulingLog(val entries: List<LogEntry> = emptyList()) {

    fun render(): String = entries.joinToString("\n") { it.render() }

    fun forTask(taskId: String): List<LogEntry> = entries.filter { it.taskId == taskId }

    data class LogEntry(
        val taskId: String?,
        val kind: Kind,
        val message: String,
    ) {
        fun render(): String = "[${kind.name.lowercase()}]" +
            (taskId?.let { " $it" } ?: "") +
            " $message"
    }

    enum class Kind {
        /** The inputs the run started from. */
        INPUT,

        /** A task's score and where it landed in the ranking. */
        RANKED,

        /** A task was placed whole in one interval. */
        PLACED,

        /** A task was spread across more than one interval. */
        SPLIT,

        /** A task took less time than it asked for. */
        SHRUNK,

        /** A splittable task got only part of what it asked for. */
        PARTIAL,

        /** A task could not be placed at all. */
        SKIPPED,

        /** Closing totals for the run. */
        SUMMARY,
    }
}

/**
 * Fills [freeTimeMap]'s open intervals with [tasks], highest priority first.
 *
 * Pure: no clock, no I/O, no Android. [today] is passed in rather than read,
 * so a schedule for any date can be computed and asserted on.
 *
 * Every decision, including every refusal, is recorded in
 * [DaySchedule.log]. "Why is this here?" and "why is this *not* here?" are
 * both answerable from that log alone.
 */
fun allocate(
    freeTimeMap: FreeTimeMap,
    tasks: List<GoalTask>,
    weights: Weights = Weights.DEFAULT,
    today: LocalDate = freeTimeMap.date,
): DaySchedule {
    val log = mutableListOf<SchedulingLog.LogEntry>()

    log += SchedulingLog.LogEntry(
        taskId = null,
        kind = SchedulingLog.Kind.INPUT,
        message = "allocating ${tasks.size} task(s) into " +
            "${freeTimeMap.free.size} free interval(s) totalling " +
            "${freeTimeMap.totalFreeMinutes}m on ${freeTimeMap.date}",
    )

    val applicable = tasks.filter { it.appliesOn(freeTimeMap.date.dayOfWeek) }
    tasks.filterNot { it in applicable }.forEach {
        log += SchedulingLog.LogEntry(
            taskId = it.id,
            kind = SchedulingLog.Kind.SKIPPED,
            message = "does not apply on ${freeTimeMap.date.dayOfWeek}",
        )
    }

    val ranked = rankByPriority(applicable, weights, today)
    ranked.forEachIndexed { index, rankedTask ->
        log += SchedulingLog.LogEntry(
            taskId = rankedTask.task.id,
            kind = SchedulingLog.Kind.RANKED,
            message = "rank ${index + 1}/${ranked.size}; " +
                rankedTask.breakdown.explain() +
                "; wants ${rankedTask.task.requestedDuration.toMinutes()}m " +
                "as ${rankedTask.task.flexibility.name.lowercase()}",
        )
    }

    var available = freeTimeMap.free
    val placements = mutableListOf<TaskPlacement>()
    val unscheduled = mutableListOf<UnscheduledTask>()

    for (rankedTask in ranked) {
        val outcome = place(rankedTask, available)
        when (outcome) {
            is Outcome.Placed -> {
                placements += outcome.placement
                available = Intervals.subtract(available, outcome.placement.slices)
                    .filter { it.duration >= MINIMUM_USEFUL_SLICE }
                log += SchedulingLog.LogEntry(
                    taskId = rankedTask.task.id,
                    kind = outcome.kind,
                    message = outcome.placement.reason,
                )
            }

            is Outcome.Rejected -> {
                unscheduled += UnscheduledTask(
                    task = rankedTask.task,
                    score = rankedTask.score,
                    reason = outcome.reason,
                )
                log += SchedulingLog.LogEntry(
                    taskId = rankedTask.task.id,
                    kind = SchedulingLog.Kind.SKIPPED,
                    message = outcome.reason,
                )
            }
        }
    }

    log += SchedulingLog.LogEntry(
        taskId = null,
        kind = SchedulingLog.Kind.SUMMARY,
        message = "placed ${placements.size}, unscheduled ${unscheduled.size}, " +
            "${Intervals.totalMinutes(available)}m free left",
    )

    return DaySchedule(
        date = freeTimeMap.date,
        freeTimeMap = freeTimeMap,
        placements = placements,
        unscheduled = unscheduled,
        remainingFree = available,
        log = SchedulingLog(log),
    )
}

private sealed interface Outcome {
    data class Placed(
        val placement: TaskPlacement,
        val kind: SchedulingLog.Kind,
    ) : Outcome

    data class Rejected(val reason: String) : Outcome
}

private fun place(ranked: RankedTask, available: List<TimeInterval>): Outcome {
    val task = ranked.task
    val wanted = task.requestedDuration

    val candidates = candidateIntervals(task, available)
    if (candidates.isEmpty()) {
        return Outcome.Rejected(
            if (task.timeSpec.isWindowed) {
                "no free time inside its ${task.timeSpec.windowStart}-" +
                    "${task.timeSpec.windowEnd} window"
            } else {
                "no free time left in the day"
            },
        )
    }

    return when (task.flexibility) {
        Flexibility.FIXED, Flexibility.MOVABLE -> placeWhole(ranked, candidates, wanted)
        Flexibility.SHRINKABLE -> placeShrinkable(ranked, candidates, wanted)
        Flexibility.SPLITTABLE -> placeSplittable(ranked, candidates, wanted)
    }
}

/**
 * Intervals this task is allowed to use: everything free, narrowed to its
 * window if it has one.
 */
private fun candidateIntervals(
    task: GoalTask,
    available: List<TimeInterval>,
): List<TimeInterval> {
    val spec = task.timeSpec
    val start = spec.windowStart
    val end = spec.windowEnd
    if (start == null || end == null) return available

    return available.mapNotNull { interval ->
        val window = TimeInterval(
            interval.start.toLocalDate().atTime(start),
            interval.start.toLocalDate().atTime(end),
        )
        interval.intersect(window)
    }.filter { it.duration >= MINIMUM_USEFUL_SLICE }
}

/** Whole and unbroken, in the earliest interval that can hold it. */
private fun placeWhole(
    ranked: RankedTask,
    candidates: List<TimeInterval>,
    wanted: Duration,
): Outcome {
    val task = ranked.task
    val interval = candidates.firstOrNull { it.duration >= wanted }
        ?: return Outcome.Rejected(
            "needs ${wanted.toMinutes()}m unbroken as " +
                "${task.flexibility.name.lowercase()}, and the largest free " +
                "interval is only ${candidates.maxOf { it.minutes }}m",
        )

    val slice = TimeInterval(interval.start, interval.start.plus(wanted))
    return Outcome.Placed(
        placement = TaskPlacement(
            task = task,
            score = ranked.score,
            slices = listOf(slice),
            requestedDuration = wanted,
            reason = "placed whole at ${slice.start.toLocalTime()} " +
                "(${wanted.toMinutes()}m) in the earliest free interval that " +
                "fits it; rank score %.3f".format(ranked.score),
        ),
        kind = SchedulingLog.Kind.PLACED,
    )
}

/** Whole if it fits; otherwise cut down, but never below half. */
private fun placeShrinkable(
    ranked: RankedTask,
    candidates: List<TimeInterval>,
    wanted: Duration,
): Outcome {
    val whole = placeWhole(ranked, candidates, wanted)
    if (whole is Outcome.Placed) return whole

    val task = ranked.task
    val floor = Duration.ofMinutes((wanted.toMinutes() * MINIMUM_SHRINK_FRACTION).toLong())
    val largest = candidates.maxBy { it.minutes }

    if (largest.duration < floor) {
        return Outcome.Rejected(
            "shrinkable, but the largest free interval is ${largest.minutes}m, " +
                "below the ${floor.toMinutes()}m floor " +
                "(${(MINIMUM_SHRINK_FRACTION * 100).toInt()}% of its " +
                "${wanted.toMinutes()}m request)",
        )
    }

    val slice = TimeInterval(largest.start, largest.start.plus(largest.duration))
    return Outcome.Placed(
        placement = TaskPlacement(
            task = task,
            score = ranked.score,
            slices = listOf(slice),
            requestedDuration = wanted,
            reason = "shrunk from ${wanted.toMinutes()}m to ${slice.minutes}m and " +
                "placed at ${slice.start.toLocalTime()}; no interval could hold " +
                "it whole; rank score %.3f".format(ranked.score),
        ),
        kind = SchedulingLog.Kind.SHRUNK,
    )
}

/** Spread across as many intervals as it takes, earliest first. */
private fun placeSplittable(
    ranked: RankedTask,
    candidates: List<TimeInterval>,
    wanted: Duration,
): Outcome {
    val task = ranked.task
    val slices = mutableListOf<TimeInterval>()
    var remaining = wanted

    for (interval in candidates) {
        if (remaining <= Duration.ZERO) break
        val take = minOf(remaining, interval.duration)
        // A sliver left over from a previous task is not worth fragmenting
        // this one further to use.
        if (take < MINIMUM_USEFUL_SLICE && slices.isNotEmpty()) continue
        slices += TimeInterval(interval.start, interval.start.plus(take))
        remaining = remaining.minus(take)
    }

    if (slices.isEmpty()) {
        return Outcome.Rejected("splittable, but no free interval is long enough to be useful")
    }

    val scheduled = slices.sumOf { it.minutes }
    val partial = remaining > Duration.ZERO

    val reason = when {
        partial -> "split across ${slices.size} interval(s) for ${scheduled}m of the " +
            "${wanted.toMinutes()}m it wanted; the day ran out of free time, " +
            "${remaining.toMinutes()}m still outstanding; rank score %.3f"
            .format(ranked.score)

        slices.size > 1 -> "split across ${slices.size} intervals " +
            "(${slices.joinToString(", ") { "${it.start.toLocalTime()} +${it.minutes}m" }}) " +
            "to cover its ${wanted.toMinutes()}m; rank score %.3f".format(ranked.score)

        else -> "placed whole at ${slices.first().start.toLocalTime()} " +
            "(${scheduled}m); one interval was enough; rank score %.3f".format(ranked.score)
    }

    return Outcome.Placed(
        placement = TaskPlacement(
            task = task,
            score = ranked.score,
            slices = slices,
            requestedDuration = wanted,
            reason = reason,
        ),
        kind = when {
            partial -> SchedulingLog.Kind.PARTIAL
            slices.size > 1 -> SchedulingLog.Kind.SPLIT
            else -> SchedulingLog.Kind.PLACED
        },
    )
}
