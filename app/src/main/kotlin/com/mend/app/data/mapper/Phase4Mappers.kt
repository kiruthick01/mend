package com.mend.app.data.mapper

import com.mend.app.data.entity.ReshuffleProposalEntity
import com.mend.app.data.entity.ScheduleEventEntity
import com.mend.app.data.entity.SleepLogEntity
import com.mend.app.data.entity.TaskEntity
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.TaskType
import com.mend.engine.recovery.SleepLogEntry
import com.mend.engine.reshuffle.ReshuffleResponse
import com.mend.engine.reshuffle.ScheduleInvalidatingEvent
import java.time.Duration

/** Persistence translation for the Phase 4 types. */

// --- protected recovery ------------------------------------------------------

fun ProtectedRecovery.toEntity(createdAt: Long = 0L): TaskEntity = TaskEntity(
    id = id,
    title = title,
    type = TaskType.PROTECTED_RECOVERY,
    // Recovery is not work, so it has no consequence model of its own; the
    // bedtime boundary is enforced structurally, not by guilt.
    consequenceType = ConsequenceType.NONE,
    daysOfWeek = daysOfWeek,
    windowStart = bedtime,
    windowEnd = wakeTime,
    requiredDurationMinutes = windDown.toMinutes(),
    hardStop = hardStop,
    createdAt = createdAt,
)

fun TaskEntity.toProtectedRecoveryOrNull(): ProtectedRecovery? {
    if (type != TaskType.PROTECTED_RECOVERY) return null
    val bed = windowStart ?: return null
    val wake = windowEnd ?: return null
    if (bed == wake) return null
    return ProtectedRecovery(
        id = id,
        title = title,
        daysOfWeek = daysOfWeek,
        bedtime = bed,
        wakeTime = wake,
        windDown = Duration.ofMinutes(requiredDurationMinutes ?: 0),
        hardStop = hardStop ?: true,
    )
}

fun List<TaskEntity>.toProtectedRecovery(): List<ProtectedRecovery> =
    mapNotNull { it.toProtectedRecoveryOrNull() }

// --- sleep log ---------------------------------------------------------------

fun SleepLogEntity.toDomain(): SleepLogEntry = SleepLogEntry(
    id = id,
    date = date,
    targetBedtime = targetBedtime,
    actualBedtime = actualBedtime,
    targetWake = targetWake,
    actualWake = actualWake,
)

fun SleepLogEntry.toEntity(): SleepLogEntity = SleepLogEntity(
    id = id,
    date = date,
    targetBedtime = targetBedtime,
    actualBedtime = actualBedtime,
    targetWake = targetWake,
    actualWake = actualWake,
)

// --- schedule events ---------------------------------------------------------

private const val KIND_DEADLINE_ADDED = "DEADLINE_ADDED"
private const val KIND_DEADLINE_CHANGED = "DEADLINE_CHANGED"
private const val KIND_TASK_MISSED = "TASK_MISSED"
private const val KIND_MANUAL_EDIT = "MANUAL_EDIT"

fun ScheduleInvalidatingEvent.toEntity(id: String): ScheduleEventEntity = ScheduleEventEntity(
    id = id,
    kind = when (this) {
        is ScheduleInvalidatingEvent.DeadlineAdded -> KIND_DEADLINE_ADDED
        is ScheduleInvalidatingEvent.DeadlineChanged -> KIND_DEADLINE_CHANGED
        is ScheduleInvalidatingEvent.TaskMissed -> KIND_TASK_MISSED
        is ScheduleInvalidatingEvent.ManualEdit -> KIND_MANUAL_EDIT
    },
    occurredAt = occurredAt,
    summary = describe(),
    subjectId = when (this) {
        is ScheduleInvalidatingEvent.DeadlineAdded -> goalId
        is ScheduleInvalidatingEvent.DeadlineChanged -> goalId
        is ScheduleInvalidatingEvent.TaskMissed -> taskId
        is ScheduleInvalidatingEvent.ManualEdit -> null
    },
)

/**
 * Reads an event row back.
 *
 * Everything comes back as [ScheduleInvalidatingEvent.ManualEdit] carrying the
 * summary that was rendered when it happened. Batching and display only ever
 * use `occurredAt` and `describe()`, and the alternative — persisting every
 * field of every variant so they can be rebuilt and re-rendered identically —
 * would be more machinery for output that is already stored verbatim.
 */
fun ScheduleEventEntity.toDomain(): ScheduleInvalidatingEvent =
    ScheduleInvalidatingEvent.ManualEdit(occurredAt = occurredAt, summary = summary)

// --- reshuffle proposals -----------------------------------------------------

const val RESPONSE_ACCEPT = "ACCEPT"
const val RESPONSE_DECLINE = "DECLINE"
const val RESPONSE_PROPOSE_DIFFERENT = "PROPOSE_DIFFERENT"

fun ReshuffleProposalEntity.responseOrNull(): ReshuffleResponse? = when (responseKind) {
    RESPONSE_ACCEPT -> ReshuffleResponse.Accept
    RESPONSE_DECLINE -> ReshuffleResponse.Decline
    RESPONSE_PROPOSE_DIFFERENT -> ReshuffleResponse.ProposeDifferent(responseText.orEmpty())
    else -> null
}

fun ReshuffleResponse.kind(): String = when (this) {
    ReshuffleResponse.Accept -> RESPONSE_ACCEPT
    ReshuffleResponse.Decline -> RESPONSE_DECLINE
    is ReshuffleResponse.ProposeDifferent -> RESPONSE_PROPOSE_DIFFERENT
}

fun ReshuffleResponse.text(): String? =
    (this as? ReshuffleResponse.ProposeDifferent)?.text

/** The rendered change lines, split back out for display. */
fun ReshuffleProposalEntity.changeLines(): List<String> =
    changes.lines().filter { it.isNotBlank() }
