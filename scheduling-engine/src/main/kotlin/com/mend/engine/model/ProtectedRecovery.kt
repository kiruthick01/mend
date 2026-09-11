package com.mend.engine.model

import com.mend.engine.time.TimeInterval
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Sleep, and possibly meals: recovery that is protected rather than optional.
 *
 * Structurally its own type, not a habit and not a goal, because it behaves
 * like neither. A habit is silently skipped when the day is full; protected
 * recovery is the thing the day gets cut down *for*. Fixing chronic late-night
 * work is an explicit goal of this project, so the bedtime boundary has to be
 * able to win an argument with outstanding work.
 *
 * Like [StaticBlock] and [Habit] it is deliberately **not** [Schedulable]: it
 * is never ranked and never bin-packed. It is laid down first and everything
 * else fits around it.
 */
data class ProtectedRecovery(
    override val id: String,
    override val title: String,
    override val daysOfWeek: Set<DayOfWeek> = StaticBlock.ALL_DAYS,
    /** When the protected block opens, e.g. 22:30. */
    val bedtime: LocalTime,
    /** When it closes, e.g. 06:30. May be earlier in the clock than [bedtime]. */
    val wakeTime: LocalTime,
    /**
     * How long before [bedtime] work must already have stopped.
     *
     * The hard stop bites at `bedtime - windDown`, not at bedtime itself: if
     * work is still running when the block opens, bedtime has already been
     * missed. Configurable, per PROJECT_PLAN.md Phase 4.
     */
    val windDown: Duration = DEFAULT_WIND_DOWN,
    /**
     * True for a hard stop: work that would overrun is deferred rather than
     * allowed to eat into the block. False downgrades it to a nudge, leaving
     * the decision to the user.
     */
    val hardStop: Boolean = true,
) : ScheduleEntry {

    override val type: TaskType = TaskType.PROTECTED_RECOVERY

    init {
        require(!windDown.isNegative) {
            "ProtectedRecovery '$title': windDown must not be negative"
        }
        require(bedtime != wakeTime) {
            "ProtectedRecovery '$title': bedtime and wakeTime must differ"
        }
    }

    /** True when the block runs through midnight, as a normal night does. */
    val crossesMidnight: Boolean get() = bedtime > wakeTime

    /** How long the block lasts, whether or not it crosses midnight. */
    val duration: Duration
        get() = if (crossesMidnight) {
            // Time left in the evening, plus time taken out of the morning.
            Duration.ofDays(1)
                .minus(Duration.between(LocalTime.MIDNIGHT, bedtime))
                .plus(Duration.between(LocalTime.MIDNIGHT, wakeTime))
        } else {
            Duration.between(bedtime, wakeTime)
        }

    /**
     * The instant work must have stopped by on [date], or null when this block
     * does not apply that night.
     *
     * This is the boundary the hard stop enforces.
     */
    fun hardStopAt(date: LocalDate): LocalDateTime? =
        if (appliesOn(date.dayOfWeek)) date.atTime(bedtime).minus(windDown) else null

    /**
     * The intervals this block occupies **within** [date].
     *
     * A night that crosses midnight shows up twice on a single calendar day:
     * the tail of last night before [wakeTime], and the head of tonight from
     * [bedtime]. That is genuinely what the day looks like, and rendering it
     * any other way would hide half the protected time.
     */
    fun intervalsOn(date: LocalDate): List<TimeInterval> {
        if (!appliesOn(date.dayOfWeek)) return emptyList()
        return if (crossesMidnight) {
            listOfNotNull(
                TimeInterval(date.atStartOfDay(), date.atTime(wakeTime))
                    .takeIf { !it.isEmpty },
                TimeInterval(date.atTime(bedtime), date.plusDays(1).atStartOfDay())
                    .takeIf { !it.isEmpty },
            )
        } else {
            listOf(TimeInterval(date.atTime(bedtime), date.atTime(wakeTime)))
        }
    }

    companion object {
        /** Half an hour of wind-down before the block opens. */
        val DEFAULT_WIND_DOWN: Duration = Duration.ofMinutes(30)
    }
}
