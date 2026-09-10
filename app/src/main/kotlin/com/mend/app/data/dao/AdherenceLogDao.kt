package com.mend.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mend.app.data.entity.AdherenceLogEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Read and append only.
 *
 * There is no update and no delete here, and that is the point: the adherence
 * stream is the source of truth for behavioural patterns, so it must be
 * impossible to quietly revise. A correction is a new row.
 */
@Dao
interface AdherenceLogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(entry: AdherenceLogEntity)

    @Query("SELECT * FROM adherence_log ORDER BY date DESC, assigned_slot_start DESC")
    fun observeAll(): Flow<List<AdherenceLogEntity>>

    @Query("SELECT * FROM adherence_log WHERE task_id = :taskId ORDER BY date DESC")
    fun observeForTask(taskId: String): Flow<List<AdherenceLogEntity>>

    @Query("SELECT * FROM adherence_log WHERE date >= :since ORDER BY date DESC")
    suspend fun since(since: LocalDate): List<AdherenceLogEntity>
}
