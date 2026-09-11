package com.mend.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * The current priority weights.
 *
 * A single row, pinned to [SINGLETON_ID]. Weights are one global setting rather
 * than a per-goal one, so a table with a fixed key is more honest than a table
 * that could hold two conflicting answers.
 */
@Entity(tableName = "weights")
data class WeightsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "w_deadline") val deadline: Double,
    @ColumnInfo(name = "w_importance") val importance: Double,
    @ColumnInfo(name = "w_adherence_trend") val adherenceTrend: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: LocalDateTime? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/**
 * One recorded change to the weights.
 *
 * Append-only, like the adherence log and for the same reason: PROJECT_PLAN.md
 * requires that someone can look at this months later and understand exactly
 * why a weight changed. That only holds if the history cannot be rewritten.
 *
 * [reason] is stored rendered rather than reconstructed on read, so the
 * explanation shown in six months is the one the rule actually gave at the
 * time, even if the rule itself has since been changed.
 */
@Entity(tableName = "weight_adjustments", indices = [Index("at")])
data class WeightAdjustmentEntity(
    @PrimaryKey val id: String,
    val at: LocalDateTime,
    /** DEADLINE, IMPORTANCE or ADHERENCE_TREND. */
    val kind: String,
    @ColumnInfo(name = "from_value") val from: Double,
    @ColumnInfo(name = "to_value") val to: Double,
    /** TOO_AGGRESSIVE, TOO_LAX, JUST_RIGHT, or null for a manual override. */
    val feedback: String? = null,
    val streak: Int = 0,
    val reason: String,
    @ColumnInfo(name = "w_deadline_after") val deadlineAfter: Double,
    @ColumnInfo(name = "w_importance_after") val importanceAfter: Double,
    @ColumnInfo(name = "w_adherence_trend_after") val adherenceTrendAfter: Double,
)

/**
 * One answer to the post-decision feedback prompt.
 *
 * Kept as its own stream rather than collapsed into a running counter, because
 * the streak rule needs the actual sequence and because "why did it change?"
 * is only answerable if the responses behind it still exist.
 */
@Entity(tableName = "schedule_feedback", indices = [Index("at")])
data class ScheduleFeedbackEntity(
    @PrimaryKey val id: String,
    val at: LocalDateTime,
    /** TOO_AGGRESSIVE, TOO_LAX or JUST_RIGHT. */
    val response: String,
    /** The proposal this was about, where there was one. */
    @ColumnInfo(name = "proposal_id") val proposalId: String? = null,
    /**
     * Set once this response has contributed to an adjustment, so a streak is
     * never counted twice.
     */
    @ColumnInfo(name = "adjustment_id") val adjustmentId: String? = null,
)
