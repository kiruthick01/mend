package com.mend.engine.time

import java.time.Duration
import java.time.LocalDateTime

/**
 * A half-open span of wall-clock time: `[start, end)`.
 *
 * Half-open is deliberate. Two blocks that touch — 09:00-10:00 and
 * 10:00-11:00 — do not overlap, and the gap between them is empty rather
 * than a zero-length interval. Every containment and subtraction rule in the
 * free time map depends on that.
 */
data class TimeInterval(
    val start: LocalDateTime,
    val end: LocalDateTime,
) : Comparable<TimeInterval> {

    init {
        require(!end.isBefore(start)) {
            "TimeInterval end ($end) must not be before start ($start)"
        }
    }

    val duration: Duration get() = Duration.between(start, end)

    val minutes: Long get() = duration.toMinutes()

    val isEmpty: Boolean get() = start == end

    /** True when this interval and [other] share at least one instant. */
    fun overlaps(other: TimeInterval): Boolean =
        start < other.end && other.start < end

    /** True when [other] lies entirely inside this interval. */
    fun contains(other: TimeInterval): Boolean =
        !other.start.isBefore(start) && !other.end.isAfter(end)

    fun contains(instant: LocalDateTime): Boolean =
        !instant.isBefore(start) && instant.isBefore(end)

    /** The shared span, or null when they do not overlap. */
    fun intersect(other: TimeInterval): TimeInterval? {
        val s = maxOf(start, other.start)
        val e = minOf(end, other.end)
        return if (s < e) TimeInterval(s, e) else null
    }

    /**
     * This interval with [other] cut out of it.
     *
     * Returns zero intervals if [other] swallows this one, one interval if it
     * clips an end, and two if it punches a hole in the middle.
     */
    fun minus(other: TimeInterval): List<TimeInterval> {
        if (!overlaps(other)) return listOf(this)
        val result = mutableListOf<TimeInterval>()
        if (start < other.start) result += TimeInterval(start, other.start)
        if (other.end < end) result += TimeInterval(other.end, end)
        return result
    }

    override fun compareTo(other: TimeInterval): Int =
        compareValuesBy(this, other, { it.start }, { it.end })

    override fun toString(): String = "[$start, $end)"
}
