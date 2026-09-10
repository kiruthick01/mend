package com.mend.engine.time

/**
 * Set operations over lists of [TimeInterval].
 *
 * These are the primitives the free time map is built from. They are kept
 * separate, tiny and total so the map itself reads as a description of policy
 * rather than a pile of date arithmetic.
 */
object Intervals {

    /**
     * Sorts, merges and de-overlaps [intervals] into a canonical, minimal,
     * ascending list. Touching intervals are merged; empty ones are dropped.
     */
    fun normalize(intervals: List<TimeInterval>): List<TimeInterval> {
        val sorted = intervals.filterNot { it.isEmpty }.sorted()
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf<TimeInterval>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            // Merge on overlap *or* on touching, so 09:00-10:00 and 10:00-11:00
            // collapse into one occupied span rather than leaving a phantom seam.
            if (!next.start.isAfter(current.end)) {
                if (next.end.isAfter(current.end)) {
                    current = TimeInterval(current.start, next.end)
                }
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    /**
     * Everything in [base] that is not covered by any interval in [cuts].
     *
     * Both sides are normalized first, so callers may pass overlapping or
     * unsorted input without surprises.
     */
    fun subtract(base: List<TimeInterval>, cuts: List<TimeInterval>): List<TimeInterval> {
        val occupied = normalize(cuts)
        if (occupied.isEmpty()) return normalize(base)

        var remaining = normalize(base)
        for (cut in occupied) {
            remaining = remaining.flatMap { it.minus(cut) }
        }
        return remaining.filterNot { it.isEmpty }
    }

    /** Total minutes covered by [intervals], counting overlaps only once. */
    fun totalMinutes(intervals: List<TimeInterval>): Long =
        normalize(intervals).sumOf { it.minutes }
}
