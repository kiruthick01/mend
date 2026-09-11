package com.mend.engine.adherence

import com.mend.engine.TEST_DATE
import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.AdherenceOutcome
import com.mend.engine.model.GoalTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdherenceTrendTest {

    private var counter = 0

    private fun entry(taskId: String, outcome: AdherenceOutcome, daysAgo: Long = 0) =
        TEST_DATE.minusDays(daysAgo).let { date ->
            AdherenceLogEntry(
                id = "e${counter++}",
                taskId = taskId,
                date = date,
                assignedSlotStart = date.atTime(9, 0),
                assignedSlotEnd = date.atTime(10, 0),
                completedAt = if (outcome == AdherenceOutcome.MISSED) null else date.atTime(10, 0),
                outcome = outcome,
            )
        }

    private val types = mapOf(
        "r1" to "reading",
        "r2" to "reading",
        "r3" to "reading",
        "fresh" to "reading",
        "p1" to "past paper",
        "orphan" to null,
    )

    private fun resolve(log: List<AdherenceLogEntry>, taskId: String) =
        resolveAdherenceTrendWithSource(log, taskId, TEST_DATE) { types[it] }

    @Test
    fun `with no history at all the figure is neutral`() {
        val (value, source) = resolve(emptyList(), "r1")
        assertEquals(GoalTask.NEUTRAL_ADHERENCE, value)
        assertEquals(AdherenceSource.NEUTRAL, source)
    }

    @Test
    fun `a task with enough of its own history is scored on it`() {
        val log = listOf(
            entry("r1", AdherenceOutcome.MISSED, 0),
            entry("r1", AdherenceOutcome.MISSED, 1),
            entry("r1", AdherenceOutcome.ON_TIME, 2),
        )
        val (value, source) = resolve(log, "r1")
        assertEquals(AdherenceSource.OWN_HISTORY, source)
        assertEquals(1.0 / 3, value, 1e-9)
    }

    @Test
    fun `one bad session does not condemn a new task`() {
        // A single miss is below the signal threshold, and there is no cohort
        // history either, so the task is not punished for it.
        val (value, source) = resolve(listOf(entry("r1", AdherenceOutcome.MISSED)), "r1")
        assertEquals(GoalTask.NEUTRAL_ADHERENCE, value)
        assertEquals(AdherenceSource.NEUTRAL, source)
    }

    @Test
    fun `a task with thin history inherits the record of its own kind of work`() {
        // "reading" is honoured once in four across the cohort; "fresh" is new.
        val log = listOf(
            entry("r1", AdherenceOutcome.MISSED, 0),
            entry("r2", AdherenceOutcome.MISSED, 1),
            entry("r3", AdherenceOutcome.MISSED, 2),
            entry("r1", AdherenceOutcome.ON_TIME, 3),
        )
        val (value, source) = resolve(log, "fresh")
        assertEquals(AdherenceSource.TYPE_HISTORY, source)
        assertEquals(0.25, value)
    }

    @Test
    fun `its own history wins over the cohort once there is enough of it`() {
        val log = listOf(
            // The cohort looks terrible.
            entry("r2", AdherenceOutcome.MISSED, 0),
            entry("r3", AdherenceOutcome.MISSED, 1),
            entry("r3", AdherenceOutcome.MISSED, 2),
            // But this task itself is reliable.
            entry("r1", AdherenceOutcome.ON_TIME, 0),
            entry("r1", AdherenceOutcome.ON_TIME, 1),
            entry("r1", AdherenceOutcome.ON_TIME, 2),
        )
        val (value, source) = resolve(log, "r1")
        assertEquals(AdherenceSource.OWN_HISTORY, source)
        assertEquals(1.0, value)
    }

    @Test
    fun `a thin cohort is not borrowed from either`() {
        val log = listOf(entry("r2", AdherenceOutcome.MISSED))
        val (value, source) = resolve(log, "fresh")
        assertEquals(GoalTask.NEUTRAL_ADHERENCE, value)
        assertEquals(AdherenceSource.NEUTRAL, source)
    }

    @Test
    fun `an unrelated cohort is never borrowed from`() {
        // Plenty of "past paper" history, none of it about reading.
        val log = listOf(
            entry("p1", AdherenceOutcome.MISSED, 0),
            entry("p1", AdherenceOutcome.MISSED, 1),
            entry("p1", AdherenceOutcome.MISSED, 2),
        )
        val (value, source) = resolve(log, "fresh")
        assertEquals(GoalTask.NEUTRAL_ADHERENCE, value)
        assertEquals(AdherenceSource.NEUTRAL, source)
    }

    @Test
    fun `a task with no known type falls straight through to neutral`() {
        val log = listOf(
            entry("r1", AdherenceOutcome.MISSED, 0),
            entry("r2", AdherenceOutcome.MISSED, 1),
            entry("r3", AdherenceOutcome.MISSED, 2),
        )
        val (value, source) = resolve(log, "orphan")
        assertEquals(GoalTask.NEUTRAL_ADHERENCE, value)
        assertEquals(AdherenceSource.NEUTRAL, source)
    }

    @Test
    fun `history outside the window does not count`() {
        val log = listOf(
            entry("r1", AdherenceOutcome.MISSED, 40),
            entry("r1", AdherenceOutcome.MISSED, 41),
            entry("r1", AdherenceOutcome.MISSED, 42),
        )
        val (value, source) = resolve(log, "r1")
        assertEquals(GoalTask.NEUTRAL_ADHERENCE, value)
        assertEquals(AdherenceSource.NEUTRAL, source)
    }

    @Test
    fun `the resolved figure always lies in the valid range`() {
        val allMissed = listOf(
            entry("r1", AdherenceOutcome.MISSED, 0),
            entry("r1", AdherenceOutcome.MISSED, 1),
            entry("r1", AdherenceOutcome.MISSED, 2),
        )
        val allDone = listOf(
            entry("r1", AdherenceOutcome.ON_TIME, 0),
            entry("r1", AdherenceOutcome.ON_TIME, 1),
            entry("r1", AdherenceOutcome.ON_TIME, 2),
        )
        assertEquals(0.0, resolve(allMissed, "r1").first)
        assertEquals(1.0, resolve(allDone, "r1").first)
    }

    @Test
    fun `the simple form agrees with the sourced form`() {
        val log = listOf(
            entry("r1", AdherenceOutcome.MISSED, 0),
            entry("r1", AdherenceOutcome.MISSED, 1),
            entry("r1", AdherenceOutcome.ON_TIME, 2),
        )
        assertEquals(
            resolveAdherenceTrendWithSource(log, "r1", TEST_DATE) { types[it] }.first,
            resolveAdherenceTrend(log, "r1", TEST_DATE) { types[it] },
        )
    }

    @Test
    fun `work that keeps slipping ends up scored below work that does not`() {
        val log = listOf(
            entry("r1", AdherenceOutcome.MISSED, 0),
            entry("r1", AdherenceOutcome.MISSED, 1),
            entry("r1", AdherenceOutcome.MISSED, 2),
            entry("p1", AdherenceOutcome.ON_TIME, 0),
            entry("p1", AdherenceOutcome.ON_TIME, 1),
            entry("p1", AdherenceOutcome.ON_TIME, 2),
        )
        // A lower trend means more pressure, so the neglected task is pushed up.
        assertTrue(resolve(log, "r1").first < resolve(log, "p1").first)
    }
}
