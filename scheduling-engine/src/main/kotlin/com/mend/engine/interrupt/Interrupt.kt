package com.mend.engine.interrupt

import com.mend.engine.allocation.DaySchedule
import com.mend.engine.allocation.MINIMUM_USEFUL_SLICE
import com.mend.engine.allocation.TaskPlacement
import com.mend.engine.model.GoalTask
import com.mend.engine.time.TimeInterval
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Emergency interrupts: the one thing allowed to change *today*.
 *
 * Everything else in Mend takes effect tomorrow. This is the single exception,
 * and it is kept deliberately narrow — the category is worthless if ordinary
 * urgency can slip into it, because then every day is an emergency and the
 * mechanic stops meaning anything.
 *
 * The test is exactly the one in PROJECT_PLAN.md Phase 4:
 * `is_one_shot && (deadline is today || no future free slot exists before the
 * deadline)`. Both halves matter. A one-shot due in a week with time booked
 * for it is just a task.
 */

/** Why a task did or did not qualify as an interrupt. */
data class InterruptQualification(
    val taskId: String,
    val qualifies: Boolean,
    val reason: String,
) {
    companion object {
        fun no(taskId: String, reason: String) =
            InterruptQualification(taskId, qualifies = false, reason = reason)

        fun yes(taskId: String, reason: String) =
            InterruptQualification(taskId, qualifies = true, reason = reason)
    }
}

/**
 * Decides whether [task] is a genuine emergency.
 *
 * [futureFreeMinutesBeforeDeadline] is how much free time exists between
 * tomorrow and the deadline, which the caller computes from future free time
 * maps. Passing it in rather than computing it here keeps this function pure
 * and keeps the expensive part — building several days of free time map — at
 * the caller's discretion.
 */
fun qualifyAsInterrupt(
    task: GoalTask,
    isOneShot: Boolean,
    today: LocalDate,
    futureFreeMinutesBeforeDeadline: Long,
): InterruptQualification {
    if (!isOneShot) {
        return InterruptQualification.no(
            task.id,
            "not a one-shot: recurring or divisible work is never an interrupt",
        )
    }

    val daysLeft = ChronoUnit.DAYS.between(today, task.deadline)
    val needed = task.requestedDuration.toMinutes()

    return when {
        daysLeft < 0 -> InterruptQualification.yes(
            task.id,
            "one-shot, and the deadline passed ${-daysLeft}d ago",
        )

        daysLeft == 0L -> InterruptQualification.yes(
            task.id,
            "one-shot with a deadline of today",
        )

        futureFreeMinutesBeforeDeadline < needed -> InterruptQualification.yes(
            task.id,
            "one-shot needing ${needed}m, but only " +
                "${futureFreeMinutesBeforeDeadline}m of free time exists before " +
                "the deadline in ${daysLeft}d — there is no later slot to take",
        )

        else -> InterruptQualification.no(
            task.id,
            "one-shot, but ${futureFreeMinutesBeforeDeadline}m of free time " +
                "remains before the deadline in ${daysLeft}d, which is enough " +
                "for its ${needed}m",
        )
    }
}

/** An interrupt that has claimed a slot, with the cost of it spelled out. */
data class InterruptPlan(
    val task: GoalTask,
    /** The slot it claims, or null when the day has no free time left at all. */
    val slot: TimeInterval?,
    /** Granted only when no slot could be claimed outright. */
    val negotiatedDelayLimit: Duration?,
    /** Scheduled work this interrupt pushes out of the way. */
    val displaced: List<TaskPlacement>,
    val qualification: InterruptQualification,
    val message: String,
) {
    val isAssignedATime: Boolean get() = slot != null
}

/**
 * Makes [task] the next thing that happens.
 *
 * It claims the **next free interval only**, where "free" means time not owned
 * by a static block, a habit or protected recovery. College does not move
 * because something else became urgent, and an interrupt that could evict
 * fixed commitments would make the whole static layer meaningless.
 *
 * Goal work *is* displaceable, and that is the point: the day has to give way
 * to a genuine emergency somewhere, and ranked work is the only thing that
 * reasonably can. Whatever it pushes out is named in [InterruptPlan.displaced]
 * so the cost is stated rather than absorbed silently.
 *
 * If no free interval is long enough, the interrupt cannot simply be dropped —
 * it is an emergency — so it takes the largest remaining interval and is given
 * a [negotiatedDelayLimit] for the shortfall.
 */
fun planInterrupt(
    now: LocalDateTime,
    schedule: DaySchedule,
    task: GoalTask,
    qualification: InterruptQualification,
): InterruptPlan {
    val needed = task.requestedDuration

    // Time still ahead of us today that is not owned by a fixed commitment.
    // Drawn from the free time map rather than what the allocator left over,
    // because goal work may be displaced by an emergency and fixed blocks may
    // not. Slivers too short to be worth interrupting for are discarded, on
    // the same reasoning as MINIMUM_USEFUL_SLICE in the allocator.
    val rest = TimeInterval(now, schedule.freeTimeMap.dayBounds.end)
    val upcoming = schedule.freeTimeMap.free
        .mapNotNull { it.intersect(rest) }
        .filter { it.duration >= MINIMUM_USEFUL_SLICE }
        .sortedBy { it.start }

    if (upcoming.isEmpty()) {
        return InterruptPlan(
            task = task,
            slot = null,
            negotiatedDelayLimit = needed,
            displaced = emptyList(),
            qualification = qualification,
            message = "${task.title} is urgent, but there is no free time left today " +
                "and fixed blocks are not available to it. It needs " +
                "${needed.toMinutes()}m as early as possible tomorrow.",
        )
    }

    val fits = upcoming.firstOrNull { it.duration >= needed }
    val slot = if (fits != null) {
        TimeInterval(fits.start, fits.start.plus(needed))
    } else {
        upcoming.maxBy { it.minutes }
    }

    val shortfall = needed.minus(slot.duration).takeIf { !it.isNegative && !it.isZero }
    val displaced = schedule.placements.filter { placement ->
        placement.slices.any { it.overlaps(slot) }
    }

    return InterruptPlan(
        task = task,
        slot = slot,
        negotiatedDelayLimit = shortfall,
        displaced = displaced,
        qualification = qualification,
        message = buildString {
            append("${task.title} is now the next thing you do: ")
            append("${slot.start.toLocalTime()}-${slot.end.toLocalTime()}")
            append(" (${slot.minutes}m).")
            if (shortfall != null) {
                append(" That is ${shortfall.toMinutes()}m short of what it needs; ")
                append("the remainder has to be negotiated.")
            }
            if (displaced.isNotEmpty()) {
                append(" It displaces ")
                append(displaced.joinToString(", ") { it.task.title })
                append(".")
            }
        },
    )
}

/** What today loses because an interrupt ran past its slot. */
data class OverrunImpact(
    val task: GoalTask,
    val overrunMinutes: Long,
    val lost: List<TaskPlacement>,
    val lostMinutes: Long,
    val message: String,
) {
    val hasCost: Boolean get() = lost.isNotEmpty()
}

/**
 * Spells out the cost of an interrupt that is still running past its slot.
 *
 * Per PROJECT_PLAN.md this message is explicit about consequence rather than
 * vague about disapproval: it names what is being lost, in minutes, because
 * "you're running late" is not information anyone can act on.
 */
fun describeOverrun(
    now: LocalDateTime,
    plan: InterruptPlan,
    schedule: DaySchedule,
): OverrunImpact {
    val slotEnd = plan.slot?.end
    if (slotEnd == null || !now.isAfter(slotEnd)) {
        return OverrunImpact(
            task = plan.task,
            overrunMinutes = 0,
            lost = emptyList(),
            lostMinutes = 0,
            message = "${plan.task.title} is still within its slot.",
        )
    }

    val overrun = ChronoUnit.MINUTES.between(slotEnd, now)

    // The window the overrun has eaten. Cost is measured by overlap, not by
    // start time: work that straddles the end of the slot is partly gone, and
    // counting it as intact would understate what the overrun actually cost.
    val eaten = TimeInterval(slotEnd, now)
    val lostBy = schedule.placements.associateWith { placement ->
        placement.slices.sumOf { slice ->
            slice.intersect(eaten)?.minutes ?: 0L
        }
    }.filterValues { it > 0 }

    val lost = lostBy.keys.toList()
    val lostMinutes = lostBy.values.sum()

    return OverrunImpact(
        task = plan.task,
        overrunMinutes = overrun,
        lost = lost,
        lostMinutes = lostMinutes,
        message = buildString {
            append("${plan.task.title} has run ${overrun}m past its slot. ")
            if (lost.isEmpty()) {
                append("Nothing else was scheduled in that time, so today is intact.")
            } else {
                append("Here is what today has lost: ")
                append(
                    lostBy.entries.joinToString(", ") { (placement, minutes) ->
                        "${placement.task.title} (${minutes}m)"
                    },
                )
                append(" — ${lostMinutes}m in total.")
            }
        },
    )
}
