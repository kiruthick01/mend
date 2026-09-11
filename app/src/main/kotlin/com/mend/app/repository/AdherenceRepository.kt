package com.mend.app.repository

import com.mend.app.data.dao.AdherenceLogDao
import com.mend.app.data.entity.AdherenceLogEntity
import com.mend.engine.adherence.delayAgainstSlot
import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.AdherenceOutcome
import com.mend.engine.time.TimeInterval
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The append-only adherence stream.
 *
 * Every completion and every miss across the app lands here — goal sub-tasks,
 * interrupts and protected recovery alike. The stream is the only evidence the
 * behavioural features have: if a bad day is not written down, adherence never
 * degrades, the projection never sees a problem, and the guilt trip never
 * fires. Silence here becomes false reassurance downstream.
 *
 * Nothing is ever updated or deleted. A correction is a new row.
 */
class AdherenceRepository(
    private val dao: AdherenceLogDao,
    private val reshuffle: ReshuffleRepository? = null,
) {

    fun observeLog(): Flow<List<AdherenceLogEntry>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeForTask(taskId: String): Flow<List<AdherenceLogEntry>> =
        dao.observeForTask(taskId).map { rows -> rows.map { it.toDomain() } }

    suspend fun since(date: LocalDate): List<AdherenceLogEntry> =
        dao.since(date).map { it.toDomain() }

    /**
     * Records that work was done.
     *
     * With an assigned slot, lateness is measured against it. Without one — work
     * ticked off from the goal screen that was never scheduled — the entry is
     * recorded as on time against a synthetic slot ending at completion:
     * something done off-plan cannot be late for an appointment it never had.
     */
    suspend fun recordCompletion(
        taskId: String,
        assignedSlot: TimeInterval?,
        completedAt: LocalDateTime,
        estimatedDuration: Duration,
    ) {
        val slot = assignedSlot ?: TimeInterval(
            completedAt.minus(estimatedDuration),
            completedAt,
        )
        val delay = delayAgainstSlot(assignedSlot, completedAt)

        append(
            taskId = taskId,
            date = slot.start.toLocalDate(),
            slot = slot,
            completedAt = completedAt,
            delayMinutes = delay,
            outcome = if (delay > 0) AdherenceOutcome.LATE else AdherenceOutcome.ON_TIME,
        )
    }

    /**
     * Records that scheduled work did not happen.
     *
     * Also raises a [ScheduleInvalidatingEvent.TaskMissed] so the reshuffle
     * flow can offer a different plan — a miss is precisely the sort of thing
     * that makes tomorrow's schedule wrong.
     */
    suspend fun recordMiss(
        taskId: String,
        taskTitle: String,
        assignedSlot: TimeInterval,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        append(
            taskId = taskId,
            date = assignedSlot.start.toLocalDate(),
            slot = assignedSlot,
            completedAt = null,
            delayMinutes = 0,
            outcome = AdherenceOutcome.MISSED,
        )
        reshuffle?.record(
            com.mend.engine.reshuffle.ScheduleInvalidatingEvent.TaskMissed(
                occurredAt = now,
                taskId = taskId,
                taskTitle = taskTitle,
                date = assignedSlot.start.toLocalDate(),
            ),
        )
    }

    /**
     * Records work that was deliberately postponed rather than skipped.
     *
     * Kept distinct from a miss because it is a different behaviour: agreeing
     * to move something is not the same as letting it slide, and the fragility
     * tier only counts genuine skips.
     */
    suspend fun recordNegotiated(taskId: String, assignedSlot: TimeInterval) {
        append(
            taskId = taskId,
            date = assignedSlot.start.toLocalDate(),
            slot = assignedSlot,
            completedAt = null,
            delayMinutes = 0,
            outcome = AdherenceOutcome.NEGOTIATED,
        )
    }

    /** Task ids already logged on [date], so a miss is never recorded twice. */
    suspend fun loggedOn(date: LocalDate): Set<String> =
        dao.since(date).filter { it.date == date }.map { it.taskId }.toSet()

    private suspend fun append(
        taskId: String,
        date: LocalDate,
        slot: TimeInterval,
        completedAt: LocalDateTime?,
        delayMinutes: Long,
        outcome: AdherenceOutcome,
    ) {
        dao.append(
            AdherenceLogEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                date = date,
                assignedSlotStart = slot.start,
                assignedSlotEnd = slot.end,
                completedAt = completedAt,
                delayMinutes = delayMinutes,
                outcome = outcome,
            ),
        )
    }
}

private fun AdherenceLogEntity.toDomain(): AdherenceLogEntry = AdherenceLogEntry(
    id = id,
    taskId = taskId,
    date = date,
    assignedSlotStart = assignedSlotStart,
    assignedSlotEnd = assignedSlotEnd,
    completedAt = completedAt,
    delayMinutes = delayMinutes,
    outcome = outcome,
)
