package com.mend.app.repository

import com.mend.app.data.dao.AdherenceLogDao
import com.mend.app.data.entity.AdherenceLogEntity
import com.mend.engine.model.AdherenceOutcome
import com.mend.engine.time.TimeInterval
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * The app-side rules about what a completion or a miss actually records.
 *
 * Backed by an in-memory DAO rather than Room: the SQL is Room's problem, the
 * outcome and delay decisions are ours, and those are what can be wrong in a
 * way that quietly corrupts every behavioural feature downstream.
 */
class AdherenceRepositoryTest {

    private class FakeAdherenceLogDao : AdherenceLogDao {
        val rows = MutableStateFlow<List<AdherenceLogEntity>>(emptyList())

        override suspend fun append(entry: AdherenceLogEntity) {
            rows.value = rows.value + entry
        }

        override fun observeAll(): Flow<List<AdherenceLogEntity>> = rows

        override fun observeForTask(taskId: String): Flow<List<AdherenceLogEntity>> =
            rows.map { list -> list.filter { it.taskId == taskId } }

        override suspend fun since(since: LocalDate): List<AdherenceLogEntity> =
            rows.value.filter { !it.date.isBefore(since) }
    }

    private val date = LocalDate.of(2026, 9, 16)
    private val slot = TimeInterval(date.atTime(9, 0), date.atTime(10, 0))

    private fun repository(dao: FakeAdherenceLogDao = FakeAdherenceLogDao()) =
        dao to AdherenceRepository(dao)

    // --- completions ---------------------------------------------------------

    @Test
    fun `finishing inside the slot is on time`() = runTest {
        val (dao, repo) = repository()
        repo.recordCompletion("t1", slot, date.atTime(9, 50), Duration.ofMinutes(60))

        val row = dao.rows.value.single()
        assertEquals(AdherenceOutcome.ON_TIME, row.outcome)
        assertEquals(0, row.delayMinutes)
        assertEquals(date, row.date)
    }

    @Test
    fun `finishing exactly at the end of the slot is on time`() = runTest {
        val (dao, repo) = repository()
        repo.recordCompletion("t1", slot, date.atTime(10, 0), Duration.ofMinutes(60))
        assertEquals(AdherenceOutcome.ON_TIME, dao.rows.value.single().outcome)
    }

    @Test
    fun `finishing after the slot is late by the difference`() = runTest {
        val (dao, repo) = repository()
        repo.recordCompletion("t1", slot, date.atTime(10, 45), Duration.ofMinutes(60))

        val row = dao.rows.value.single()
        assertEquals(AdherenceOutcome.LATE, row.outcome)
        assertEquals(45, row.delayMinutes)
    }

    @Test
    fun `work with no slot cannot be late for an appointment it never had`() = runTest {
        val (dao, repo) = repository()
        repo.recordCompletion(
            taskId = "t1",
            assignedSlot = null,
            completedAt = date.atTime(23, 30),
            estimatedDuration = Duration.ofMinutes(30),
        )

        val row = dao.rows.value.single()
        assertEquals(AdherenceOutcome.ON_TIME, row.outcome)
        assertEquals(0, row.delayMinutes)
        // A synthetic slot ending at completion, so velocity still counts it.
        assertEquals(date.atTime(23, 0), row.assignedSlotStart)
        assertEquals(date.atTime(23, 30), row.assignedSlotEnd)
    }

    @Test
    fun `an unscheduled completion is filed under the day it happened`() = runTest {
        val (dao, repo) = repository()
        repo.recordCompletion("t1", null, date.atTime(8, 15), Duration.ofMinutes(30))
        assertEquals(date, dao.rows.value.single().date)
    }

    // --- misses --------------------------------------------------------------

    @Test
    fun `a miss records no completion time`() = runTest {
        val (dao, repo) = repository()
        repo.recordMiss("t1", "Reading", slot, date.atTime(11, 0))

        val row = dao.rows.value.single()
        assertEquals(AdherenceOutcome.MISSED, row.outcome)
        assertNull(row.completedAt)
        assertEquals(0, row.delayMinutes)
    }

    @Test
    fun `a miss is filed against the day it was scheduled for`() = runTest {
        val (dao, repo) = repository()
        // Noticed after midnight, but it was Tuesday's slot that went unused.
        repo.recordMiss("t1", "Reading", slot, date.plusDays(1).atTime(0, 30))
        assertEquals(date, dao.rows.value.single().date)
    }

    @Test
    fun `a negotiated postponement is not recorded as a skip`() = runTest {
        val (dao, repo) = repository()
        repo.recordNegotiated("t1", slot)
        assertEquals(AdherenceOutcome.NEGOTIATED, dao.rows.value.single().outcome)
    }

    // --- append-only ---------------------------------------------------------

    @Test
    fun `entries accumulate rather than replacing each other`() = runTest {
        val (dao, repo) = repository()
        repo.recordCompletion("t1", slot, date.atTime(10, 0), Duration.ofMinutes(60))
        repo.recordMiss("t1", "Reading", slot, date.atTime(11, 0))
        repo.recordCompletion("t1", slot, date.atTime(9, 30), Duration.ofMinutes(60))

        assertEquals(3, dao.rows.value.size)
        assertEquals(3, dao.rows.value.map { it.id }.distinct().size)
    }

    @Test
    fun `the interface offers no way to revise history`() {
        val methods = AdherenceLogDao::class.java.methods.map { it.name }
        assertTrue(methods.none { it.startsWith("update") || it.startsWith("delete") })
    }

    // --- duplicate protection ------------------------------------------------

    @Test
    fun `already-logged ids are reported for the day asked about`() = runTest {
        val (_, repo) = repository()
        repo.recordMiss("t1", "Reading", slot, date.atTime(11, 0))
        repo.recordCompletion("t2", slot, date.atTime(10, 0), Duration.ofMinutes(60))

        assertEquals(setOf("t1", "t2"), repo.loggedOn(date))
    }

    @Test
    fun `another day's entries are not reported as logged today`() = runTest {
        val (_, repo) = repository()
        val yesterday = TimeInterval(
            date.minusDays(1).atTime(9, 0),
            date.minusDays(1).atTime(10, 0),
        )
        repo.recordMiss("t1", "Reading", yesterday, date.atTime(11, 0))

        assertEquals(emptySet(), repo.loggedOn(date))
        assertEquals(setOf("t1"), repo.loggedOn(date.minusDays(1)))
    }

    // --- reading back --------------------------------------------------------

    @Test
    fun `entries read back as engine domain types`() = runTest {
        val (_, repo) = repository()
        repo.recordCompletion("t1", slot, date.atTime(10, 20), Duration.ofMinutes(60))

        val entry = repo.observeLog().first().single()

        assertEquals("t1", entry.taskId)
        assertEquals(AdherenceOutcome.LATE, entry.outcome)
        assertEquals(20, entry.delayMinutes)
        assertEquals(slot.start, entry.assignedSlotStart)
    }
}
