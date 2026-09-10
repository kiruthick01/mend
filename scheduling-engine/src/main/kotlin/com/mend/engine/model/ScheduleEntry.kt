package com.mend.engine.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/**
 * Anything that can occupy wall-clock time in a day.
 *
 * The hierarchy below is the type-level enforcement of PROJECT_PLAN.md's
 * hardest modelling constraint: *habits carry no priority score, no deadline,
 * and are never reshuffled; static blocks are excluded from bin-packing
 * entirely.* Rather than trusting convention, [Habit] and [StaticBlock]
 * simply do not implement [Schedulable] and have no fields for priority,
 * deadline, importance or flexibility. There is nothing to accidentally read.
 */
sealed interface ScheduleEntry {
    val id: String
    val title: String
    val type: TaskType

    /** Days this entry applies to. An entry recurs weekly on these days. */
    val daysOfWeek: Set<DayOfWeek>

    fun appliesOn(day: DayOfWeek): Boolean = day in daysOfWeek
}

/**
 * A [ScheduleEntry] the allocator is permitted to rank and place.
 *
 * `computePriorityScore` and the slot allocator accept this type and only
 * this type, which is what makes "habits are never ranked" a compile-time
 * fact rather than a code-review question.
 */
sealed interface Schedulable : ScheduleEntry {
    val consequenceType: ConsequenceType
    val timeSpec: TimeSpec
}

/**
 * Where inside its window a window-based block is anchored when it needs less
 * time than the window offers.
 */
enum class WindowAnchor {
    /** Sit at the start of the window; the tail of the window stays free. */
    EARLIEST,

    /** Sit at the end of the window; the head of the window stays free. */
    LATEST,
}

/**
 * College, mess, gym: a constraint on the day, not a competitor for it.
 *
 * Two shapes are supported:
 *  - **Whole window occupied** ([requiredDuration] is null): the block owns
 *    `windowStart..windowEnd` outright. College, mess.
 *  - **Partial window** ([requiredDuration] is set): the block only needs
 *    that much time somewhere inside the window — a 2-hour gym window that
 *    only needs 45 minutes of you. The remainder of the window stays free.
 *
 * Deliberately not a [Schedulable]: static blocks are never ranked, never
 * scored, and never bin-packed.
 */
data class StaticBlock(
    override val id: String,
    override val title: String,
    override val daysOfWeek: Set<DayOfWeek> = ALL_DAYS,
    val windowStart: LocalTime,
    val windowEnd: LocalTime,
    /** Time actually needed inside the window; null means the whole window. */
    val requiredDuration: Duration? = null,
    val anchor: WindowAnchor = WindowAnchor.EARLIEST,
) : ScheduleEntry {

    override val type: TaskType = TaskType.STATIC_BLOCK

    init {
        require(windowEnd > windowStart) {
            "Static block '$title': windowEnd ($windowEnd) must be after windowStart ($windowStart)"
        }
        requiredDuration?.let {
            require(!it.isNegative && !it.isZero) {
                "Static block '$title': requiredDuration must be positive"
            }
            require(it <= windowDuration) {
                "Static block '$title': requiredDuration ($it) exceeds its window ($windowDuration)"
            }
        }
    }

    val windowDuration: Duration
        get() = Duration.between(windowStart, windowEnd)

    /** How long this block actually occupies: its requirement, else the window. */
    val occupiedDuration: Duration
        get() = requiredDuration ?: windowDuration

    /** True when the block leaves usable time inside its own window. */
    val isPartialWindow: Boolean
        get() = requiredDuration != null && requiredDuration < windowDuration

    companion object {
        val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    }
}

/**
 * Brushing, water, wake time: a fixed point on the timeline.
 *
 * Has no priority, no deadline, no importance and no flexibility — not
 * nullable versions of those, none at all. If the day is packed a habit is
 * silently skipped; it is never reshuffled and never guilt-tripped.
 */
data class Habit(
    override val id: String,
    override val title: String,
    override val daysOfWeek: Set<DayOfWeek> = StaticBlock.ALL_DAYS,
    val timeOfDay: LocalTime,
    val duration: Duration = Duration.ofMinutes(5),
) : ScheduleEntry {

    override val type: TaskType = TaskType.HABIT

    init {
        require(!duration.isNegative && !duration.isZero) {
            "Habit '$title': duration must be positive"
        }
    }
}
