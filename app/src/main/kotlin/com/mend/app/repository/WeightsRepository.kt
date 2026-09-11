package com.mend.app.repository

import com.mend.app.data.dao.ScheduleFeedbackDao
import com.mend.app.data.dao.WeightAdjustmentDao
import com.mend.app.data.dao.WeightsDao
import com.mend.app.data.entity.ScheduleFeedbackEntity
import com.mend.app.data.entity.WeightAdjustmentEntity
import com.mend.app.data.entity.WeightsEntity
import com.mend.engine.model.Weights
import com.mend.engine.weights.ScheduleFeedback
import com.mend.engine.weights.WeightAdjustment
import com.mend.engine.weights.WeightKind
import com.mend.engine.weights.evaluateFeedback
import com.mend.engine.weights.manualAdjustment
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The priority weights, the feedback that shapes them, and the record of every
 * change.
 *
 * The decision itself lives in the engine; this stores the inputs, asks, and
 * writes down the answer. Nothing here decides anything, which is what keeps
 * the rule testable in isolation.
 */
class WeightsRepository(
    private val weightsDao: WeightsDao,
    private val adjustmentDao: WeightAdjustmentDao,
    private val feedbackDao: ScheduleFeedbackDao,
) {

    /** The weights in force, falling back to the documented defaults. */
    fun observeWeights(): Flow<Weights> =
        weightsDao.observe().map { it?.toDomain() ?: Weights.DEFAULT }

    fun observeHistory(): Flow<List<WeightAdjustment>> =
        adjustmentDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeRecentFeedback(): Flow<List<ScheduleFeedback>> =
        feedbackDao.observeRecent().map { rows ->
            rows.mapNotNull { runCatching { ScheduleFeedback.valueOf(it.response) }.getOrNull() }
        }

    suspend fun current(): Weights = weightsDao.current()?.toDomain() ?: Weights.DEFAULT

    /**
     * Records a response and applies an adjustment if it completes a streak.
     *
     * Returns the adjustment when one was made, so the UI can say what changed
     * rather than moving the weights silently. Silent adaptation is how an app
     * becomes inexplicable to its own owner.
     */
    suspend fun recordFeedback(
        response: ScheduleFeedback,
        proposalId: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): WeightAdjustment? {
        feedbackDao.append(
            ScheduleFeedbackEntity(
                id = UUID.randomUUID().toString(),
                at = now,
                response = response.name,
                proposalId = proposalId,
            ),
        )

        val unspent = feedbackDao.unspent()
        val adjustment = evaluateFeedback(
            current = current(),
            recentFeedback = unspent.mapNotNull {
                runCatching { ScheduleFeedback.valueOf(it.response) }.getOrNull()
            },
            now = now,
            id = UUID.randomUUID().toString(),
        ) ?: return null

        persist(adjustment, now)

        // Retire exactly the responses that made up the streak, so the next one
        // starts from scratch rather than immediately re-triggering.
        feedbackDao.markSpent(
            ids = unspent.take(adjustment.streak).map { it.id },
            adjustmentId = adjustment.id,
        )
        return adjustment
    }

    /** Sets one weight by hand, recording it in the same history. */
    suspend fun overrideWeight(
        kind: WeightKind,
        value: Double,
        now: LocalDateTime = LocalDateTime.now(),
    ): WeightAdjustment? {
        val adjustment = manualAdjustment(
            current = current(),
            kind = kind,
            value = value,
            now = now,
            id = UUID.randomUUID().toString(),
        ) ?: return null

        persist(adjustment, now)
        return adjustment
    }

    private suspend fun persist(adjustment: WeightAdjustment, now: LocalDateTime) {
        adjustmentDao.append(adjustment.toEntity())
        weightsDao.set(adjustment.weightsAfter.toEntity(now))
    }
}

private fun WeightsEntity.toDomain() = Weights(
    deadline = deadline,
    importance = importance,
    adherenceTrend = adherenceTrend,
)

private fun Weights.toEntity(now: LocalDateTime) = WeightsEntity(
    deadline = deadline,
    importance = importance,
    adherenceTrend = adherenceTrend,
    updatedAt = now,
)

private fun WeightAdjustment.toEntity() = WeightAdjustmentEntity(
    id = id,
    at = at,
    kind = kind.name,
    from = from,
    to = to,
    feedback = feedback?.name,
    streak = streak,
    reason = reason,
    deadlineAfter = weightsAfter.deadline,
    importanceAfter = weightsAfter.importance,
    adherenceTrendAfter = weightsAfter.adherenceTrend,
)

private fun WeightAdjustmentEntity.toDomain() = WeightAdjustment(
    id = id,
    at = at,
    kind = WeightKind.valueOf(kind),
    from = from,
    to = to,
    feedback = feedback?.let { runCatching { ScheduleFeedback.valueOf(it) }.getOrNull() },
    streak = streak,
    weightsAfter = Weights(
        deadline = deadlineAfter,
        importance = importanceAfter,
        adherenceTrend = adherenceTrendAfter,
    ),
    reason = reason,
)
