package com.mend.engine.recovery

import com.mend.engine.allocation.DaySchedule
import com.mend.engine.allocation.TaskPlacement
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.tuning.Tuning
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Enforcing the bedtime boundary in real time.
 *
 * Laying sleep into the free time map stops work being *scheduled* past
 * bedtime. This is the other half: what happens when work is actually still
 * running as the boundary arrives. PROJECT_PLAN.md Phase 4 settles the policy
 * — hard stop, with a configurable threshold — so the only judgement left here
 * is how to say it.
 */

/** What should happen to work that is still running near the boundary. */
enum class SleepStopAction {
    /** Nothing to say; the boundary is not close. */
    NONE,

    /** The boundary is approaching. Warn, but do not force anything. */
    WARN,

    /** The boundary is here. Work is deferred to tomorrow. */
    STOP,

    /**
     * The boundary is here, but this block is a nudge rather than a hard stop,
     * so the decision stays with the user.
     */
    NUDGE,
}

/**
 * The verdict on work in progress as bedtime approaches, and the work it
 * affects.
 */
data class SleepStopDecision(
    val action: SleepStopAction,
    val hardStopAt: LocalDateTime?,
    val minutesUntilHardStop: Long,
    /** Work that would be cut short or dropped if the boundary is enforced. */
    val affected: List<TaskPlacement>,
    val message: String,
) {
    val isEnforced: Boolean get() = action == SleepStopAction.STOP
}

/** How long before the boundary a warning starts. */
val SLEEP_WARNING_LEAD: Duration = Tuning.SLEEP_WARNING_LEAD

/**
 * Decides what to do about work still running at [now].
 *
 * Pure: [now] is a parameter, never a clock read, so every branch is testable.
 *
 * The boundary is `bedtime - windDown`, not bedtime. Work that is still going
 * when the block itself opens has already cost sleep, which is the thing this
 * feature exists to stop.
 */
fun evaluateSleepStop(
    now: LocalDateTime,
    schedule: DaySchedule,
    recovery: ProtectedRecovery?,
): SleepStopDecision {
    val boundary = recovery?.hardStopAt(now.toLocalDate())
        ?: return SleepStopDecision(
            action = SleepStopAction.NONE,
            hardStopAt = null,
            minutesUntilHardStop = Long.MAX_VALUE,
            affected = emptyList(),
            message = "No protected sleep block is set for tonight.",
        )

    val minutesLeft = ChronoUnit.MINUTES.between(now, boundary)

    // Anything whose scheduled time has not finished by the boundary.
    val affected = schedule.placements.filter { placement ->
        placement.slices.any { it.end > boundary && it.end > now }
    }

    return when {
        minutesLeft > SLEEP_WARNING_LEAD.toMinutes() -> SleepStopDecision(
            action = SleepStopAction.NONE,
            hardStopAt = boundary,
            minutesUntilHardStop = minutesLeft,
            affected = emptyList(),
            message = "Bedtime boundary is ${minutesLeft}m away.",
        )

        minutesLeft > 0 -> SleepStopDecision(
            action = SleepStopAction.WARN,
            hardStopAt = boundary,
            minutesUntilHardStop = minutesLeft,
            affected = affected,
            message = buildString {
                append("Winding down in ${minutesLeft}m, at ")
                append("${boundary.toLocalTime()}, for a ${recovery.bedtime} bedtime.")
                if (affected.isNotEmpty()) {
                    append(" ")
                    append(describeAffected(affected, boundary))
                }
            },
        )

        !recovery.hardStop -> SleepStopDecision(
            action = SleepStopAction.NUDGE,
            hardStopAt = boundary,
            minutesUntilHardStop = minutesLeft,
            affected = affected,
            message = buildString {
                append("You are past the wind-down for a ${recovery.bedtime} bedtime.")
                if (affected.isNotEmpty()) {
                    append(" ")
                    append(describeAffected(affected, boundary))
                }
                append(" Your call — this block is a nudge, not a hard stop.")
            },
        )

        else -> SleepStopDecision(
            action = SleepStopAction.STOP,
            hardStopAt = boundary,
            minutesUntilHardStop = minutesLeft,
            affected = affected,
            message = buildString {
                append("Stopping for the night. Bedtime is ${recovery.bedtime}.")
                if (affected.isEmpty()) {
                    append(" Nothing was left running.")
                } else {
                    append(" ")
                    append(describeAffected(affected, boundary))
                    append(" It moves to tomorrow.")
                }
            },
        )
    }
}

private fun describeAffected(affected: List<TaskPlacement>, boundary: LocalDateTime): String {
    val lostMinutes = affected.sumOf { placement ->
        placement.slices.sumOf { slice ->
            if (slice.end > boundary) {
                ChronoUnit.MINUTES.between(maxOf(slice.start, boundary), slice.end)
            } else {
                0L
            }
        }
    }
    val names = affected.joinToString(", ") { it.task.title }
    return "$names would lose ${lostMinutes}m."
}

/**
 * One night of sleep, target against actual.
 *
 * A stream of its own, deliberately separate from `AdherenceLogEntry`. Sleep
 * is not a task and does not have an assigned slot that was met or missed; it
 * has a boundary that was respected or eroded, and mixing the two would make
 * both harder to read.
 */
data class SleepLogEntry(
    val id: String,
    /** The date the night *began*, so one night is always one row. */
    val date: LocalDate,
    val targetBedtime: LocalDateTime,
    val actualBedtime: LocalDateTime?,
    val targetWake: LocalDateTime,
    val actualWake: LocalDateTime?,
) {
    /** Minutes later than intended, negative when early. Null until recorded. */
    val bedtimeDriftMinutes: Long?
        get() = actualBedtime?.let { ChronoUnit.MINUTES.between(targetBedtime, it) }

    val wakeDriftMinutes: Long?
        get() = actualWake?.let { ChronoUnit.MINUTES.between(targetWake, it) }

    val targetDuration: Duration get() = Duration.between(targetBedtime, targetWake)

    val actualDuration: Duration?
        get() = if (actualBedtime != null && actualWake != null) {
            Duration.between(actualBedtime, actualWake)
        } else {
            null
        }

    /** Minutes of sleep lost against target, negative when more was had. */
    val shortfallMinutes: Long?
        get() = actualDuration?.let { targetDuration.toMinutes() - it.toMinutes() }

    val isComplete: Boolean get() = actualBedtime != null && actualWake != null
}

/** Rolling view of how well the sleep boundary is actually holding. */
data class SleepTrend(
    val nightsRecorded: Int,
    val averageBedtimeDriftMinutes: Double,
    val averageShortfallMinutes: Double,
    val nightsOnTarget: Int,
) {
    /** 0.0-1.0, the share of recorded nights that met the bedtime target. */
    val onTargetRate: Double
        get() = if (nightsRecorded == 0) 0.0 else nightsOnTarget.toDouble() / nightsRecorded
}

/** A night counts as on target if bedtime was met within this much slack. */
const val BEDTIME_ON_TARGET_SLACK_MINUTES: Long = Tuning.BEDTIME_ON_TARGET_SLACK_MINUTES

/**
 * Summarises [entries] into a trend.
 *
 * Only complete nights count. A night still in progress has no shortfall to
 * measure, and counting it as zero would flatter the average.
 */
fun computeSleepTrend(entries: List<SleepLogEntry>): SleepTrend {
    val complete = entries.filter { it.isComplete }
    if (complete.isEmpty()) {
        return SleepTrend(
            nightsRecorded = 0,
            averageBedtimeDriftMinutes = 0.0,
            averageShortfallMinutes = 0.0,
            nightsOnTarget = 0,
        )
    }

    return SleepTrend(
        nightsRecorded = complete.size,
        averageBedtimeDriftMinutes = complete.mapNotNull { it.bedtimeDriftMinutes }
            .average(),
        averageShortfallMinutes = complete.mapNotNull { it.shortfallMinutes }.average(),
        nightsOnTarget = complete.count {
            (it.bedtimeDriftMinutes ?: Long.MAX_VALUE) <= BEDTIME_ON_TARGET_SLACK_MINUTES
        },
    )
}
