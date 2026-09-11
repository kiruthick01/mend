package com.mend.engine.reshuffle

import com.mend.engine.allocation.DaySchedule
import com.mend.engine.time.TimeInterval
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Negotiated reshuffling.
 *
 * Two rules shape everything here, both from PROJECT_PLAN.md:
 *
 *  1. **Every change is a conversation.** The engine proposes; it never
 *     applies. There are exactly three responses, and one of them is "no".
 *  2. **Changes take effect tomorrow.** Today is not upended retroactively.
 *     The only thing allowed to change today is a genuine emergency
 *     interrupt, which lives in `com.mend.engine.interrupt` and is a
 *     different mechanic on purpose.
 *
 * There is also a quieter rule that matters as much in practice: a prompt that
 * fires too often is noise, and noise gets dismissed unread. Same-day triggers
 * are therefore batched into a single proposal.
 */

/** Something happened that makes the current plan wrong. */
sealed interface ScheduleInvalidatingEvent {
    /** When it happened. Used for same-day batching. */
    val occurredAt: LocalDateTime

    /** One line, for the "what changed" list on the proposal. */
    fun describe(): String

    /** A deadline appeared where there was none. */
    data class DeadlineAdded(
        override val occurredAt: LocalDateTime,
        val goalId: String,
        val goalTitle: String,
        val deadline: LocalDate,
    ) : ScheduleInvalidatingEvent {
        override fun describe(): String = "$goalTitle now has a deadline of $deadline"
    }

    /** A deadline moved. */
    data class DeadlineChanged(
        override val occurredAt: LocalDateTime,
        val goalId: String,
        val goalTitle: String,
        val from: LocalDate,
        val to: LocalDate,
    ) : ScheduleInvalidatingEvent {
        override fun describe(): String = if (to < from) {
            "$goalTitle moved earlier, from $from to $to"
        } else {
            "$goalTitle moved later, from $from to $to"
        }
    }

    /** Work that was scheduled did not happen. */
    data class TaskMissed(
        override val occurredAt: LocalDateTime,
        val taskId: String,
        val taskTitle: String,
        val date: LocalDate,
    ) : ScheduleInvalidatingEvent {
        override fun describe(): String = "$taskTitle was missed on $date"
    }

    /** The user changed the inputs by hand. */
    data class ManualEdit(
        override val occurredAt: LocalDateTime,
        val summary: String,
    ) : ScheduleInvalidatingEvent {
        override fun describe(): String = summary
    }
}

/** What the user may do with a proposal. Exactly three, by design. */
sealed interface ReshuffleResponse {
    /** Take the proposed schedule from its effective date. */
    data object Accept : ReshuffleResponse

    /** Keep the current plan. The triggering events are considered answered. */
    data object Decline : ReshuffleResponse

    /**
     * Neither: the user says what they would rather do, in their own words.
     *
     * Phase 7 routes this through NLP parsing. For now the text is stored
     * verbatim and surfaced back, so nothing the user says is lost while the
     * parsing does not exist yet.
     */
    data class ProposeDifferent(val text: String) : ReshuffleResponse
}

/** A proposed schedule, the reasons for it, and what it would change. */
data class ReshuffleProposal(
    val id: String,
    /** The first day the proposal would apply to. Never today. */
    val effectiveFrom: LocalDate,
    /** Every event that contributed, batched into this one proposal. */
    val triggers: List<ScheduleInvalidatingEvent>,
    val current: DaySchedule?,
    val proposed: DaySchedule,
    val changes: List<ScheduleChange>,
    val createdAt: LocalDateTime,
    val response: ReshuffleResponse? = null,
) {
    val isAnswered: Boolean get() = response != null

    /** True when the proposal would not actually change anything. */
    val isNoOp: Boolean get() = changes.isEmpty()

    /** The "what changed and why" summary the UI shows above the three buttons. */
    fun summarise(): String = buildString {
        append("Because ")
        append(triggers.joinToString("; ") { it.describe() })
        append(", here is a different plan for $effectiveFrom onward.")
        if (changes.isEmpty()) {
            append(" As it turns out, nothing needs to move.")
        }
    }
}

/** One difference between the current plan and the proposed one. */
sealed interface ScheduleChange {
    val taskId: String
    val taskTitle: String

    fun describe(): String

    data class Added(
        override val taskId: String,
        override val taskTitle: String,
        val at: TimeInterval,
    ) : ScheduleChange {
        override fun describe(): String =
            "$taskTitle is added at ${at.start.toLocalTime()}"
    }

    data class Removed(
        override val taskId: String,
        override val taskTitle: String,
        val reason: String,
    ) : ScheduleChange {
        override fun describe(): String = "$taskTitle no longer fits: $reason"
    }

    data class Moved(
        override val taskId: String,
        override val taskTitle: String,
        val from: TimeInterval,
        val to: TimeInterval,
    ) : ScheduleChange {
        override fun describe(): String =
            "$taskTitle moves from ${from.start.toLocalTime()} to ${to.start.toLocalTime()}"
    }

    data class Resized(
        override val taskId: String,
        override val taskTitle: String,
        val fromMinutes: Long,
        val toMinutes: Long,
    ) : ScheduleChange {
        override fun describe(): String = if (toMinutes > fromMinutes) {
            "$taskTitle gets ${toMinutes - fromMinutes}m more"
        } else {
            "$taskTitle loses ${fromMinutes - toMinutes}m"
        }
    }
}

/**
 * The earliest date a proposal may take effect: always tomorrow.
 *
 * A function rather than a scattered `plusDays(1)` so the rule has exactly one
 * home and cannot be quietly broken somewhere else.
 */
fun effectiveDateFor(today: LocalDate): LocalDate = today.plusDays(1)

/**
 * Groups events into the proposals they should produce.
 *
 * All events from the same calendar day collapse into one batch, so a morning
 * of edits produces a single prompt at the end rather than six. This is the
 * difference between a tool that negotiates and a tool that nags.
 */
fun batchEvents(
    events: List<ScheduleInvalidatingEvent>,
): List<List<ScheduleInvalidatingEvent>> =
    events
        .sortedBy { it.occurredAt }
        .groupBy { it.occurredAt.toLocalDate() }
        .values
        .toList()

/**
 * Builds a proposal from a batch of events and the schedule they invalidate.
 *
 * The engine computes the alternative; it does not adopt it. Applying the
 * proposal is a separate, explicit act driven by [ReshuffleResponse.Accept].
 */
fun buildProposal(
    id: String,
    now: LocalDateTime,
    triggers: List<ScheduleInvalidatingEvent>,
    current: DaySchedule?,
    proposed: DaySchedule,
): ReshuffleProposal {
    require(triggers.isNotEmpty()) { "a reshuffle proposal needs at least one trigger" }

    val effectiveFrom = effectiveDateFor(now.toLocalDate())
    require(!proposed.date.isBefore(effectiveFrom)) {
        "a proposal may not take effect before $effectiveFrom (was ${proposed.date}); " +
            "today is never rewritten retroactively"
    }

    return ReshuffleProposal(
        id = id,
        effectiveFrom = effectiveFrom,
        triggers = triggers,
        current = current,
        proposed = proposed,
        changes = diffSchedules(current, proposed),
        createdAt = now,
    )
}

/**
 * Compares two schedules and reports what actually differs.
 *
 * The point is to show the user a short list of real changes rather than two
 * timetables to compare by eye. A task that is in both and has not moved
 * produces nothing.
 */
fun diffSchedules(current: DaySchedule?, proposed: DaySchedule): List<ScheduleChange> {
    if (current == null) {
        return proposed.placements.map {
            ScheduleChange.Added(it.task.id, it.task.title, it.slices.first())
        }
    }

    val before = current.placements.associateBy { it.task.id }
    val after = proposed.placements.associateBy { it.task.id }

    val changes = mutableListOf<ScheduleChange>()

    for ((id, newPlacement) in after) {
        val old = before[id]
        if (old == null) {
            changes += ScheduleChange.Added(
                id,
                newPlacement.task.title,
                newPlacement.slices.first(),
            )
            continue
        }

        val oldStart = old.slices.first()
        val newStart = newPlacement.slices.first()
        if (oldStart.start != newStart.start) {
            changes += ScheduleChange.Moved(id, newPlacement.task.title, oldStart, newStart)
        }
        if (old.scheduledMinutes != newPlacement.scheduledMinutes) {
            changes += ScheduleChange.Resized(
                id,
                newPlacement.task.title,
                old.scheduledMinutes,
                newPlacement.scheduledMinutes,
            )
        }
    }

    for ((id, oldPlacement) in before) {
        if (id !in after) {
            val why = proposed.unscheduled.firstOrNull { it.task.id == id }?.reason
                ?: "it is no longer pending"
            changes += ScheduleChange.Removed(id, oldPlacement.task.title, why)
        }
    }

    return changes.sortedBy { it.taskTitle }
}
