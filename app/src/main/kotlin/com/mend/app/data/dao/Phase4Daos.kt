package com.mend.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mend.app.data.entity.ReshuffleProposalEntity
import com.mend.app.data.entity.ScheduleEventEntity
import com.mend.app.data.entity.SleepLogEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The sleep stream.
 *
 * Insert and update only: a night in progress is completed when you wake, so
 * unlike the task adherence log this one genuinely does need to finish a row
 * it has already started. There is still no delete.
 */
@Dao
interface SleepLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SleepLogEntity)

    @Query("SELECT * FROM sleep_log ORDER BY date DESC")
    fun observeAll(): Flow<List<SleepLogEntity>>

    @Query("SELECT * FROM sleep_log WHERE date = :date")
    suspend fun forDate(date: LocalDate): SleepLogEntity?

    @Query("SELECT * FROM sleep_log WHERE date >= :since ORDER BY date DESC")
    fun observeSince(since: LocalDate): Flow<List<SleepLogEntity>>
}

@Dao
interface ScheduleEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ScheduleEventEntity)

    /** Events not yet folded into a proposal. These are what the next one batches. */
    @Query("SELECT * FROM schedule_events WHERE proposal_id IS NULL ORDER BY occurred_at")
    fun observePending(): Flow<List<ScheduleEventEntity>>

    @Query("SELECT * FROM schedule_events WHERE proposal_id IS NULL ORDER BY occurred_at")
    suspend fun pending(): List<ScheduleEventEntity>

    @Query("UPDATE schedule_events SET proposal_id = :proposalId WHERE id IN (:eventIds)")
    suspend fun attachToProposal(eventIds: List<String>, proposalId: String)
}

@Dao
interface ReshuffleProposalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(proposal: ReshuffleProposalEntity)

    @Update
    suspend fun update(proposal: ReshuffleProposalEntity)

    /** The one awaiting an answer, if any. At most one is ever outstanding. */
    @Query(
        "SELECT * FROM reshuffle_proposals WHERE response_kind IS NULL " +
            "ORDER BY created_at DESC LIMIT 1",
    )
    fun observeOutstanding(): Flow<ReshuffleProposalEntity?>

    @Query(
        "SELECT * FROM reshuffle_proposals WHERE response_kind IS NULL " +
            "ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun outstanding(): ReshuffleProposalEntity?

    @Query("SELECT * FROM reshuffle_proposals ORDER BY created_at DESC LIMIT :limit")
    fun observeHistory(limit: Int = 20): Flow<List<ReshuffleProposalEntity>>

    /**
     * Records an answer against one proposal.
     *
     * Scoped to rows that are still unanswered, so a double tap cannot
     * overwrite a decision that has already been made.
     */
    @Query(
        "UPDATE reshuffle_proposals SET response_kind = :kind, response_text = :text, " +
            "responded_at = :at WHERE id = :id AND response_kind IS NULL",
    )
    suspend fun recordResponse(
        id: String,
        kind: String,
        text: String?,
        at: java.time.LocalDateTime,
    ): Int
}
