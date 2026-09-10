package com.mend.engine.model

import java.time.Duration
import java.time.LocalTime

/**
 * How a schedulable piece of work wants to be placed in time.
 *
 * Mirrors the `TimeSpec` block of PROJECT_PLAN.md Section 3. Only
 * [Schedulable] entries carry one — static blocks and habits do not, because
 * they are never placed by the allocator.
 */
data class TimeSpec(
    val unitDuration: UnitDuration,
    val flexibility: Flexibility,
    /** Optional window this work must land inside, e.g. "only during library hours". */
    val windowStart: LocalTime? = null,
    val windowEnd: LocalTime? = null,
    /**
     * How much time this work actually needs. When a window is set, that time
     * must fall inside it. Null falls back to one [unitDuration].
     */
    val requiredDurationWithinWindow: Duration? = null,
) {
    init {
        if (windowStart != null || windowEnd != null) {
            require(windowStart != null && windowEnd != null) {
                "TimeSpec window must set both windowStart and windowEnd, or neither"
            }
            require(windowEnd > windowStart) {
                "TimeSpec windowEnd ($windowEnd) must be after windowStart ($windowStart)"
            }
        }
        requiredDurationWithinWindow?.let {
            require(!it.isNegative && !it.isZero) {
                "TimeSpec requiredDurationWithinWindow must be positive"
            }
        }
    }

    /** The amount of time this work actually asks for. */
    val requestedDuration: Duration
        get() = requiredDurationWithinWindow ?: Duration.ofMinutes(unitDuration.minutes.toLong())

    /** True when this work is confined to a window rather than free-floating. */
    val isWindowed: Boolean get() = windowStart != null
}
