package com.mend.app.repository

import com.mend.app.data.dao.ScheduleFeedbackDao
import com.mend.app.data.dao.WeightAdjustmentDao
import com.mend.app.data.dao.WeightsDao
import com.mend.app.data.entity.ScheduleFeedbackEntity
import com.mend.app.data.entity.WeightAdjustmentEntity
import com.mend.app.data.entity.WeightsEntity
import com.mend.engine.model.Weights
import com.mend.engine.weights.ScheduleFeedback
import com.mend.engine.weights.WeightKind
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * The streak rule as it actually behaves once responses are persisted.
 *
 * The engine test proves the arithmetic; this proves the bookkeeping around it,
 * which is where the subtle failure lives: a response that is counted twice
 * would make the weights drift on half the evidence they appear to need.
 */
class WeightsRepositoryTest {

    private class FakeWeightsDao : WeightsDao {
        val row = MutableStateFlow<WeightsEntity?>(null)
        override fun observe(id: Int): Flow<WeightsEntity?> = row
        override suspend fun current(id: Int): WeightsEntity? = row.value
        override suspend fun set(weights: WeightsEntity) { row.value = weights }
    }

    private class FakeAdjustmentDao : WeightAdjustmentDao {
        val rows = MutableStateFlow<List<WeightAdjustmentEntity>>(emptyList())
        override suspend fun append(adjustment: WeightAdjustmentEntity) {
            rows.value = rows.value + adjustment
        }
        override fun observeAll(): Flow<List<WeightAdjustmentEntity>> =
            rows.map { it.sortedByDescending { row -> row.at } }
        override fun observeRecent(limit: Int): Flow<List<WeightAdjustmentEntity>> =
            observeAll().map { it.take(limit) }
    }

    private class FakeFeedbackDao : ScheduleFeedbackDao {
        val rows = MutableStateFlow<List<ScheduleFeedbackEntity>>(emptyList())
        override suspend fun append(entry: ScheduleFeedbackEntity) {
            rows.value = rows.value + entry
        }
        override suspend fun unspent(): List<ScheduleFeedbackEntity> =
            rows.value.filter { it.adjustmentId == null }.sortedByDescending { it.at }
        override fun observeRecent(limit: Int): Flow<List<ScheduleFeedbackEntity>> =
            rows.map { list -> list.sortedByDescending { it.at }.take(limit) }
        override suspend fun markSpent(ids: List<String>, adjustmentId: String) {
            rows.value = rows.value.map {
                if (it.id in ids) it.copy(adjustmentId = adjustmentId) else it
            }
        }
    }

    private val start = LocalDateTime.of(2026, 9, 16, 20, 0)

    private class Fixture {
        val weightsDao = FakeWeightsDao()
        val adjustmentDao = FakeAdjustmentDao()
        val feedbackDao = FakeFeedbackDao()
        val repository = WeightsRepository(weightsDao, adjustmentDao, feedbackDao)
    }

    private suspend fun WeightsRepository.respond(
        f: Fixture,
        response: ScheduleFeedback,
        at: LocalDateTime,
    ) = recordFeedback(response, proposalId = null, now = at)

    // --- defaults ------------------------------------------------------------

    @Test
    fun `with nothing stored the documented defaults are used`() = runTest {
        val f = Fixture()
        assertEquals(Weights.DEFAULT, f.repository.current())
        assertEquals(Weights.DEFAULT, f.repository.observeWeights().first())
    }

    @Test
    fun `history starts empty`() = runTest {
        assertEquals(emptyList(), Fixture().repository.observeHistory().first())
    }

    // --- the streak ----------------------------------------------------------

    @Test
    fun `the first two complaints change nothing`() = runTest {
        val f = Fixture()
        assertNull(f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start))
        assertNull(f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(1)))
        assertEquals(Weights.DEFAULT, f.repository.current())
    }

    @Test
    fun `the third completes the streak and moves a weight`() = runTest {
        val f = Fixture()
        f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start)
        f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(1))
        val adjustment = f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(2))

        assertNotNull(adjustment)
        assertEquals(WeightKind.DEADLINE, adjustment.kind)
        assertEquals(0.45, f.repository.current().deadline, 1e-9)
    }

    @Test
    fun `the responses behind an adjustment are not counted again`() = runTest {
        val f = Fixture()
        repeat(3) { f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(it.toLong())) }
        assertEquals(0.45, f.repository.current().deadline, 1e-9)

        // A fourth complaint on its own must not immediately move it again.
        assertNull(f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(3)))
        assertEquals(0.45, f.repository.current().deadline, 1e-9)
    }

    @Test
    fun `a second full streak moves it a second step`() = runTest {
        val f = Fixture()
        repeat(6) { f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(it.toLong())) }
        assertEquals(0.40, f.repository.current().deadline, 1e-9)
        assertEquals(2, f.adjustmentDao.rows.value.size)
    }

    @Test
    fun `just right breaks the streak`() = runTest {
        val f = Fixture()
        f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start)
        f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(1))
        f.repository.respond(f, ScheduleFeedback.JUST_RIGHT, start.plusHours(2))
        assertNull(f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(3)))
        assertEquals(Weights.DEFAULT, f.repository.current())
    }

    @Test
    fun `too lax pushes the other way`() = runTest {
        val f = Fixture()
        repeat(3) { f.repository.respond(f, ScheduleFeedback.TOO_LAX, start.plusHours(it.toLong())) }
        assertEquals(0.55, f.repository.current().deadline, 1e-9)
    }

    @Test
    fun `every response is kept, whether or not it changed anything`() = runTest {
        val f = Fixture()
        repeat(4) { f.repository.respond(f, ScheduleFeedback.JUST_RIGHT, start.plusHours(it.toLong())) }
        assertEquals(4, f.feedbackDao.rows.value.size)
        assertEquals(emptyList(), f.adjustmentDao.rows.value)
    }

    // --- the history ---------------------------------------------------------

    @Test
    fun `an adjustment records its cause in full`() = runTest {
        val f = Fixture()
        repeat(3) { f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(it.toLong())) }

        val entry = f.repository.observeHistory().first().single()
        assertEquals(ScheduleFeedback.TOO_AGGRESSIVE, entry.feedback)
        assertEquals(3, entry.streak)
        assertEquals(0.5, entry.from)
        assertEquals(0.45, entry.to, 1e-9)
        assertTrue(entry.describe().contains("3 consecutive"))
    }

    @Test
    fun `the stored reason survives a round trip unchanged`() = runTest {
        val f = Fixture()
        repeat(3) { f.repository.respond(f, ScheduleFeedback.TOO_LAX, start.plusHours(it.toLong())) }
        val written = f.adjustmentDao.rows.value.single().reason
        assertEquals(written, f.repository.observeHistory().first().single().reason)
    }

    @Test
    fun `history is newest first`() = runTest {
        val f = Fixture()
        repeat(6) { f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(it.toLong())) }
        val history = f.repository.observeHistory().first()
        assertEquals(2, history.size)
        assertTrue(history[0].at > history[1].at)
    }

    // --- manual override -----------------------------------------------------

    @Test
    fun `a manual override changes the weight and is recorded`() = runTest {
        val f = Fixture()
        val adjustment = f.repository.overrideWeight(WeightKind.IMPORTANCE, 0.6, start)

        assertNotNull(adjustment)
        assertTrue(adjustment.isManual)
        assertEquals(0.6, f.repository.current().importance, 1e-9)
        assertEquals(1, f.adjustmentDao.rows.value.size)
    }

    @Test
    fun `manual and automatic changes share one history`() = runTest {
        val f = Fixture()
        repeat(3) { f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(it.toLong())) }
        f.repository.overrideWeight(WeightKind.IMPORTANCE, 0.6, start.plusHours(4))

        val history = f.repository.observeHistory().first()
        assertEquals(2, history.size)
        assertEquals(1, history.count { it.isManual })
        assertEquals(1, history.count { !it.isManual })
    }

    @Test
    fun `setting a weight to its current value records nothing`() = runTest {
        val f = Fixture()
        assertNull(f.repository.overrideWeight(WeightKind.DEADLINE, Weights.DEFAULT.deadline, start))
        assertEquals(emptyList(), f.adjustmentDao.rows.value)
    }

    @Test
    fun `weights always still sum to one after any change`() = runTest {
        val f = Fixture()
        repeat(3) { f.repository.respond(f, ScheduleFeedback.TOO_AGGRESSIVE, start.plusHours(it.toLong())) }
        f.repository.overrideWeight(WeightKind.IMPORTANCE, 0.7, start.plusHours(4))
        repeat(3) { f.repository.respond(f, ScheduleFeedback.TOO_LAX, start.plusHours(10 + it.toLong())) }

        val w = f.repository.current()
        assertEquals(1.0, w.deadline + w.importance + w.adherenceTrend, 1e-9)
    }

    @Test
    fun `the adjustment history cannot be revised`() {
        val methods = WeightAdjustmentDao::class.java.methods.map { it.name }
        assertTrue(methods.none { it.startsWith("update") || it.startsWith("delete") })
    }
}
