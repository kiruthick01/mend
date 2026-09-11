package com.mend.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mend.app.data.entity.ScheduleFeedbackEntity
import com.mend.app.data.entity.WeightAdjustmentEntity
import com.mend.app.data.entity.WeightsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeightsDao {

    @Query("SELECT * FROM weights WHERE id = :id")
    fun observe(id: Int = WeightsEntity.SINGLETON_ID): Flow<WeightsEntity?>

    @Query("SELECT * FROM weights WHERE id = :id")
    suspend fun current(id: Int = WeightsEntity.SINGLETON_ID): WeightsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(weights: WeightsEntity)
}

/**
 * The adjustment history.
 *
 * Insert and read only. This is the record that answers "why is the app
 * behaving like this?", and a record that can be edited answers nothing.
 */
@Dao
interface WeightAdjustmentDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(adjustment: WeightAdjustmentEntity)

    @Query("SELECT * FROM weight_adjustments ORDER BY at DESC")
    fun observeAll(): Flow<List<WeightAdjustmentEntity>>

    @Query("SELECT * FROM weight_adjustments ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<WeightAdjustmentEntity>>
}

@Dao
interface ScheduleFeedbackDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(entry: ScheduleFeedbackEntity)

    /**
     * Responses not yet counted towards an adjustment, newest first.
     *
     * Newest first because the streak rule reads from the most recent backwards;
     * unspent because a response that has already moved a weight must not move
     * it again.
     */
    @Query(
        "SELECT * FROM schedule_feedback WHERE adjustment_id IS NULL ORDER BY at DESC",
    )
    suspend fun unspent(): List<ScheduleFeedbackEntity>

    @Query("SELECT * FROM schedule_feedback ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ScheduleFeedbackEntity>>

    @Query("UPDATE schedule_feedback SET adjustment_id = :adjustmentId WHERE id IN (:ids)")
    suspend fun markSpent(ids: List<String>, adjustmentId: String)
}
