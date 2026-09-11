package com.mend.app.repository

import com.mend.app.data.dao.ReshuffleProposalDao
import com.mend.app.data.dao.ScheduleEventDao
import com.mend.app.data.entity.ReshuffleProposalEntity
import com.mend.app.data.mapper.kind
import com.mend.app.data.mapper.text
import com.mend.app.data.mapper.toDomain
import com.mend.app.data.mapper.toEntity
import com.mend.engine.allocation.DaySchedule
import com.mend.engine.reshuffle.ReshuffleResponse
import com.mend.engine.reshuffle.ScheduleInvalidatingEvent
import com.mend.engine.reshuffle.batchEvents
import com.mend.engine.reshuffle.buildProposal
import com.mend.engine.reshuffle.effectiveDateFor
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * The negotiation half of reshuffling.
 *
 * Events are recorded as they happen and sit pending. When a proposal is
 * asked for, every pending event from the same day is folded into **one**
 * proposal — a prompt per edit would be noise, and noise gets dismissed
 * unread, which is worse than not asking at all.
 *
 * The proposed schedule itself is never stored. It is recomputed from the same
 * inputs deterministically, so a stored copy could only ever fall out of step
 * with the data it was derived from.
 */
class ReshuffleRepository(
    private val eventDao: ScheduleEventDao,
    private val proposalDao: ReshuffleProposalDao,
) {

    fun observeOutstanding(): Flow<ReshuffleProposalEntity?> = proposalDao.observeOutstanding()

    fun observeHistory(): Flow<List<ReshuffleProposalEntity>> = proposalDao.observeHistory()

    fun observePendingEvents() = eventDao.observePending()

    /** Records that something made the current plan wrong. */
    suspend fun record(event: ScheduleInvalidatingEvent) {
        eventDao.insert(event.toEntity(UUID.randomUUID().toString()))
    }

    /**
     * Builds a proposal from everything still pending, if anything is.
     *
     * Returns null when there is nothing to propose, or when a proposal is
     * already waiting for an answer — asking a second question before the
     * first is answered is exactly the noise the plan warns against.
     */
    suspend fun proposeIfNeeded(
        now: LocalDateTime,
        proposedSchedule: DaySchedule,
        currentSchedule: DaySchedule? = null,
    ): ReshuffleProposalEntity? {
        if (proposalDao.outstanding() != null) return null

        val pendingRows = eventDao.pending()
        if (pendingRows.isEmpty()) return null

        // Only today's events; anything older belongs to a batch that has
        // already been dealt with or is stale.
        val batch = batchEvents(pendingRows.map { it.toDomain() })
            .lastOrNull()
            ?: return null

        val proposal = buildProposal(
            id = UUID.randomUUID().toString(),
            now = now,
            triggers = batch,
            current = currentSchedule,
            proposed = proposedSchedule,
        )

        val row = ReshuffleProposalEntity(
            id = proposal.id,
            effectiveFrom = proposal.effectiveFrom,
            createdAt = proposal.createdAt,
            summary = proposal.summarise(),
            changes = proposal.changes.joinToString("\n") { it.describe() },
        )
        proposalDao.insert(row)
        eventDao.attachToProposal(pendingRows.map { it.id }, proposal.id)
        return row
    }

    /**
     * Records the user's answer.
     *
     * The engine never applies a proposal on its own; this is the only place a
     * response is written, and even Accept is an acknowledgement rather than a
     * mutation — the schedule is derived, so accepting means the new shape of
     * the day stands and the question is closed.
     */
    suspend fun respond(
        proposalId: String,
        response: ReshuffleResponse,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean = proposalDao.recordResponse(
        id = proposalId,
        kind = response.kind(),
        text = response.text(),
        at = now,
    ) > 0

    /** The earliest date any proposal may apply to. Never today. */
    fun earliestEffectiveDate(now: LocalDateTime) = effectiveDateFor(now.toLocalDate())
}
