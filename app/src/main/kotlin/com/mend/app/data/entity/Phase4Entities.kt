package com.mend.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One night of sleep, target against actual.
 *
 * A table of its own, deliberately not rows in `adherence_log`. Sleep is not a
 * task: it has no assigned slot that was met or missed, it has a boundary that
 * was respected or eroded. PROJECT_PLAN.md asks for it as "its own adherence
 * stream", and keeping the two apart means neither has to carry columns that
 * are meaningless for the other.
 */
@Entity(tableName = "sleep_log", indices = [Index(value = ["date"], unique = true)])
data class SleepLogEntity(
    @PrimaryKey val id: String,
    /** The date the night began, so one night is always one row. */
    val date: LocalDate,
    @ColumnInfo(name = "target_bedtime") val targetBedtime: LocalDateTime,
    @ColumnInfo(name = "actual_bedtime") val actualBedtime: LocalDateTime? = null,
    @ColumnInfo(name = "target_wake") val targetWake: LocalDateTime,
    @ColumnInfo(name = "actual_wake") val actualWake: LocalDateTime? = null,
)

/**
 * Something that happened which makes the current plan wrong.
 *
 * Recorded as it occurs and cleared once the proposal it fed has been
 * answered, so the "why are you asking me this" list on a proposal is the
 * actual history rather than a guess reconstructed afterwards.
 */
@Entity(
    tableName = "schedule_events",
    indices = [Index("occurred_at"), Index("proposal_id")],
)
data class ScheduleEventEntity(
    @PrimaryKey val id: String,
    /** Discriminator: DEADLINE_ADDED, DEADLINE_CHANGED, TASK_MISSED, MANUAL_EDIT. */
    val kind: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: LocalDateTime,
    /** Pre-rendered one-liner for the proposal summary. */
    val summary: String,
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    /** Set once this event has been folded into a proposal. */
    @ColumnInfo(name = "proposal_id") val proposalId: String? = null,
)

/**
 * A proposed change to tomorrow, and what the user said about it.
 *
 * The proposed schedule itself is not stored: it is recomputed from the same
 * inputs deterministically, so persisting it would create a second copy that
 * could fall out of step. What is worth keeping is the negotiation — what was
 * proposed, why, and what the answer was.
 */
@Entity(tableName = "reshuffle_proposals", indices = [Index("effective_from")])
data class ReshuffleProposalEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
    @ColumnInfo(name = "created_at") val createdAt: LocalDateTime,
    /** The "because X, Y and Z" line shown above the three buttons. */
    val summary: String,
    /** One rendered change per line. */
    @ColumnInfo(name = "changes") val changes: String,
    /** ACCEPT, DECLINE, PROPOSE_DIFFERENT, or null while unanswered. */
    @ColumnInfo(name = "response_kind") val responseKind: String? = null,
    /** Verbatim text for PROPOSE_DIFFERENT. Phase 7 parses it; for now it is kept. */
    @ColumnInfo(name = "response_text") val responseText: String? = null,
    @ColumnInfo(name = "responded_at") val respondedAt: LocalDateTime? = null,
)
