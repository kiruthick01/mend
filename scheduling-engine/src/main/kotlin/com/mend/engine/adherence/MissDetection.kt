package com.mend.engine.adherence

import com.mend.engine.allocation.DaySchedule
import com.mend.engine.allocation.TaskPlacement
import com.mend.engine.time.TimeInterval
import com.mend.engine.tuning.Tuning
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Noticing that scheduled work did not happen.
 *
 * A miss has to become an *event* for any of Phase 5 to work. Left implicit —
 * "it is still not done" — it produces no log entry, so adherence never
 * degrades, the projection never sees a problem, and the guilt trip never
 * fires. The stream is only worth trusting if the bad days are written down as
 * faithfully as the good ones.
 */

/** Scheduled work whose time has gone without it being done. */
data class MissedWork(
    val placement: TaskPlacement,
    /** The slot that elapsed. */
    val slot: TimeInterval,
    val reason: String,
) {
    val taskId: String get() = placement.task.id
}

/**
 * Finds work in [schedule] whose slot has fully elapsed by [now] and which is
 * neither done nor already logged.
 *
 * Deliberately conservative on every axis:
 *  - **Fully** elapsed. Work still in its slot is not late, it is in progress.
 *  - Only the final slice of a split task counts, so a task spread across the
 *    day is not declared missed at lunchtime because its morning slice passed.
 *  - [alreadyLogged] prevents a second entry for the same task on the same
 *    day; the stream is append-only, so a duplicate can never be tidied away.
 */
fun detectElapsedMisses(
    schedule: DaySchedule,
    now: LocalDateTime,
    completedTaskIds: Set<String> = emptySet(),
    alreadyLogged: Set<String> = emptySet(),
): List<MissedWork> =
    schedule.placements
        .filterNot { it.task.id in completedTaskIds }
        .filterNot { it.task.id in alreadyLogged }
        .mapNotNull { placement ->
            val last = placement.slices.maxByOrNull { it.end } ?: return@mapNotNull null
            if (last.end > now) return@mapNotNull null

            MissedWork(
                placement = placement,
                slot = last,
                reason = buildString {
                    append("scheduled ${last.start.toLocalTime()}-${last.end.toLocalTime()}")
                    if (placement.isSplit) {
                        append(" (last of ${placement.slices.size} slices)")
                    }
                    append("; the slot passed without it being marked done")
                },
            )
        }

/**
 * Works out how late a completion was against the slot it was given.
 *
 * Returns zero for work finished within its slot, and for work that had no
 * slot at all: something completed off-plan cannot be late for an appointment
 * it never had.
 */
fun delayAgainstSlot(slot: TimeInterval?, completedAt: LocalDateTime): Long {
    if (slot == null) return 0
    if (!completedAt.isAfter(slot.end)) return 0
    return java.time.temporal.ChronoUnit.MINUTES.between(slot.end, completedAt)
}

/** How far back a catch-up sweep will ever look. */
const val MAX_SWEEP_LOOKBACK_DAYS: Long = Tuning.MAX_SWEEP_LOOKBACK_DAYS

/**
 * The days a catch-up sweep should examine.
 *
 * Today is deliberately excluded: it is swept live while the app is open, and
 * its slots are still elapsing. Only days that are genuinely over can be
 * judged.
 *
 * The lookback is capped because the adherence window is finite anyway —
 * back-filling a month of misses after a long absence would flood the stream
 * with entries that say nothing except "you were not using the app", and that
 * is not a behavioural signal worth recording.
 */
fun datesToSweep(
    lastSwept: LocalDate?,
    today: LocalDate,
    maxLookbackDays: Long = MAX_SWEEP_LOOKBACK_DAYS,
): List<LocalDate> {
    val earliest = today.minusDays(maxLookbackDays)
    val from = when {
        lastSwept == null -> earliest
        lastSwept.isBefore(earliest) -> earliest
        // Already swept up to and including lastSwept, so start the day after.
        else -> lastSwept.plusDays(1)
    }

    if (!from.isBefore(today)) return emptyList()

    return generateSequence(from) { it.plusDays(1) }
        .takeWhile { it.isBefore(today) }
        .toList()
}
