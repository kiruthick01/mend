package com.mend.engine.model

/**
 * The five structural kinds of thing that can occupy time in a day.
 *
 * This mirrors PROJECT_PLAN.md Section 3. Note that the *behavioural*
 * differences between these are enforced by the sealed [ScheduleEntry]
 * hierarchy, not by this enum — the enum exists so persistence and logging
 * have a stable discriminator to write down.
 */
enum class TaskType {
    /** College, mess, gym. Carves the grid; never competes for a slot. */
    STATIC_BLOCK,

    /** Work derived from a Goal/Lesson/SubTask. The only thing that is ranked. */
    GOAL_TASK,

    /** Brushing, water, wake time. Fixed points; silently skipped when the day is full. */
    HABIT,

    /** A real-world one-shot with today's deadline. Interrupts. Phase 4. */
    EMERGENCY_ONESHOT,

    /** Sleep, and possibly meals. Can compete with and override goal work. Phase 4. */
    PROTECTED_RECOVERY,
}

/**
 * What actually happens if this is missed. Drives the Phase 5 guilt-trip
 * trigger, which fires only for [IRL_BINDING].
 */
enum class ConsequenceType {
    /** Missing it has a real-world consequence: a graded submission, a fee, a flight. */
    IRL_BINDING,

    /** Self-imposed. Missing it costs progress, nothing else. */
    SOFT_GOAL,

    /** No consequence model at all. Habits are always this. */
    NONE,
}

/**
 * How much the allocator is allowed to bend a task to make it fit.
 *
 * Only ranked work carries a flexibility. Static blocks and habits do not
 * have one, because they are never bin-packed in the first place.
 */
enum class Flexibility {
    /** Must be placed whole, inside a single free interval, or not at all. */
    FIXED,

    /** May be given less than its estimated duration when the day is tight. */
    SHRINKABLE,

    /** May be broken across several free intervals. */
    SPLITTABLE,

    /** Whole and unbroken, but free to sit in any interval that fits it. */
    MOVABLE,
}

/**
 * The granularity at which work is estimated and placed.
 *
 * Fixed to the four sizes named in PROJECT_PLAN.md Section 3 so estimates
 * stay coarse and comparable rather than drifting into false precision.
 */
enum class UnitDuration(val minutes: Int) {
    QUARTER_HOUR(15),
    HALF_HOUR(30),
    HOUR(60),
    TWO_HOURS(120),
    ;

    companion object {
        /** Smallest unit that is at least [minutes], or the largest unit if none is. */
        fun coveringAtLeast(minutes: Int): UnitDuration =
            entries.firstOrNull { it.minutes >= minutes } ?: TWO_HOURS
    }
}

/** Progress state shared by lessons and sub-tasks. */
enum class ProgressStatus {
    NOT_STARTED,
    IN_PROGRESS,
    DONE,
}

/** How an assigned slot actually turned out. Append-only, see AdherenceLogEntry. */
enum class AdherenceOutcome {
    ON_TIME,
    LATE,
    MISSED,
    NEGOTIATED,
}
