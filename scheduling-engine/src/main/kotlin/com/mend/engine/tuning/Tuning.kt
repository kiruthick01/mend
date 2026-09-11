package com.mend.engine.tuning

import java.time.Duration

/**
 * Every number in the engine that was chosen rather than derived.
 *
 * PROJECT_PLAN.md specifies the *rules* precisely and the *thresholds* not at
 * all. Each constant below had to be picked to make a rule executable, and each
 * is a judgement about how this app should feel rather than a fact about how it
 * works. Scattered through six files they were effectively invisible; gathered
 * here they can be argued with, which is the point.
 *
 * Every one is referenced from exactly one place, so changing a value here
 * changes the behaviour and nothing has to be hunted down.
 *
 * The rules themselves are not tunable and are not listed here. Sleep is
 * protected, habits are never ranked, changes take effect tomorrow, the guilt
 * trip fires only for real consequences — those are the design, not settings.
 */
object Tuning {

    // --- Priority scoring ----------------------------------------------------

    /**
     * How far ahead a deadline stops adding urgency, in days.
     *
     * Larger makes distant deadlines start mattering sooner and flattens the
     * difference between next week and next month. Smaller makes the schedule
     * ignore anything not imminent.
     *
     * Thirty days suits a semester. Exam prep that runs longer than a month
     * would want more.
     */
    const val DEADLINE_HORIZON_DAYS: Long = 30

    /** Starting weights. Phase 6 tunes these from feedback; these are day one. */
    const val WEIGHT_DEADLINE: Double = 0.5
    const val WEIGHT_IMPORTANCE: Double = 0.35
    const val WEIGHT_ADHERENCE_TREND: Double = 0.15

    // --- Allocation ----------------------------------------------------------

    /**
     * How far a shrinkable task may be cut before it is dropped instead.
     *
     * Higher refuses more often and protects the integrity of a session; lower
     * crams more in at the cost of sessions too short to be worth starting.
     */
    const val MINIMUM_SHRINK_FRACTION: Double = 0.5

    /**
     * The shortest fragment worth placing or leaving behind.
     *
     * Without a floor the allocator produces four-minute study slivers that are
     * worse than useless. Raising it wastes more of the day; lowering it
     * fragments work further.
     */
    val MINIMUM_USEFUL_SLICE: Duration = Duration.ofMinutes(10)

    /**
     * Assumed length of a lesson that has no sub-tasks and so no estimate.
     *
     * A placeholder rather than a considered answer. The better fix is probably
     * to ask for a duration when a bare lesson is created.
     */
    val BARE_LESSON_ESTIMATE: Duration = Duration.ofMinutes(60)

    // --- Protected sleep -----------------------------------------------------

    /** How long before bedtime work must already have stopped. */
    val DEFAULT_WIND_DOWN: Duration = Duration.ofMinutes(30)

    /** How long before the boundary a warning starts. */
    val SLEEP_WARNING_LEAD: Duration = Duration.ofMinutes(15)

    /** Slack within which a bedtime still counts as met. */
    const val BEDTIME_ON_TARGET_SLACK_MINUTES: Long = 15

    /**
     * Past this much later than target, a night is recorded as missed rather
     * than merely late.
     *
     * Fixing chronic late-night work is a stated goal of this project, so this
     * line decides what the app is willing to call a failure. Two hours is
     * lenient; an hour would make the sleep statistics considerably harsher.
     */
    const val MISSED_BEDTIME_THRESHOLD_MINUTES: Long = 120

    // --- Delay negotiation ---------------------------------------------------

    /** Skips before work counts as piling up, from the plan's "once/twice". */
    const val FRAGILE_SKIP_THRESHOLD: Int = 2

    /**
     * The most delay that can be negotiated at once.
     *
     * An unbounded delay is a cancellation with better manners, so there has to
     * be a ceiling; two days is a guess at a useful one.
     */
    val MAX_NEGOTIATED_DELAY: Duration = Duration.ofDays(2)

    // --- Adherence -----------------------------------------------------------

    /** Days of history a rolling statistic covers. */
    const val ROLLING_WINDOW_DAYS: Long = 14

    /** Below this many observations, a rate is reported but not trusted. */
    const val MINIMUM_OBSERVATIONS_FOR_SIGNAL: Int = 3

    /** A delay shift smaller than this is noise rather than a trend. */
    const val DELAY_TREND_SENSITIVITY_MINUTES: Double = 5.0

    /** How far back a catch-up sweep will ever look. */
    const val MAX_SWEEP_LOOKBACK_DAYS: Long = 14

    // --- The guilt trip ------------------------------------------------------

    /**
     * Days of recorded activity before a deadline projection is trusted.
     *
     * These two gates decide when the app is allowed to warn you. Too strict
     * and a genuinely doomed deadline stays quiet for a week; too loose and one
     * bad Tuesday triggers a lecture. They are the numbers most worth revisiting
     * after a fortnight of real use.
     */
    const val MINIMUM_ACTIVE_DAYS_FOR_PROJECTION: Int = 3

    /** Completed sessions before a deadline projection is trusted. */
    const val MINIMUM_COMPLETIONS_FOR_PROJECTION: Int = 3
}
