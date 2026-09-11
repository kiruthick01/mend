package com.mend.engine.guilt

import com.mend.engine.TEST_DATE
import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.AdherenceOutcome
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuiltTripTest {

    private var counter = 0

    private fun goal(
        consequence: ConsequenceType = ConsequenceType.IRL_BINDING,
        deadlineIn: Long = 5,
        totalLessons: Int = 4,
    ) = Goal(
        id = "g1",
        title = "Statistics exam",
        deadline = TEST_DATE.plusDays(deadlineIn),
        manualImportance = 5,
        consequenceType = consequence,
        totalLessons = totalLessons,
    )

    private fun subTask(
        id: String,
        minutes: Long = 120,
        status: ProgressStatus = ProgressStatus.NOT_STARTED,
    ) = SubTask(
        id = id,
        lessonId = "l1",
        type = "revision",
        title = id,
        estimatedDuration = Duration.ofMinutes(minutes),
        status = status,
    )

    private fun lessons(vararg subTasks: SubTask) = listOf(
        Lesson(
            id = "l1",
            goalId = "g1",
            orderIndex = 0,
            title = "Revision",
            subTasks = subTasks.toList(),
        ),
    )

    private fun logEntry(
        taskId: String,
        outcome: AdherenceOutcome,
        daysAgo: Long,
        slotMinutes: Long = 30,
    ): AdherenceLogEntry {
        val date = TEST_DATE.minusDays(daysAgo)
        return AdherenceLogEntry(
            id = "e${counter++}",
            taskId = taskId,
            date = date,
            assignedSlotStart = date.atTime(18, 0),
            assignedSlotEnd = date.atTime(18, 0).plusMinutes(slotMinutes),
            completedAt = if (outcome == AdherenceOutcome.MISSED) null else date.atTime(19, 0),
            outcome = outcome,
        )
    }

    /** Three days of honoured 30-minute sessions: a sustained 30m/day. */
    private fun slowButRealHistory() = listOf(
        logEntry("a", AdherenceOutcome.ON_TIME, daysAgo = 2),
        logEntry("a", AdherenceOutcome.ON_TIME, daysAgo = 1),
        logEntry("a", AdherenceOutcome.ON_TIME, daysAgo = 0),
    )

    // --- the exclusions, which are the point --------------------------------

    @Test
    fun `a soft goal never triggers however far behind it is`() {
        val trip = evaluateGuiltTrip(
            goal(consequence = ConsequenceType.SOFT_GOAL),
            lessons(subTask("a", minutes = 6000)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertNull(trip)
    }

    @Test
    fun `a goal with no consequence never triggers`() {
        val trip = evaluateGuiltTrip(
            goal(consequence = ConsequenceType.NONE),
            lessons(subTask("a", minutes = 6000)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertNull(trip)
    }

    @Test
    fun `an IRL-binding goal that is behind does trigger`() {
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 6000)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertNotNull(trip)
        assertEquals("g1", trip.goalId)
    }

    // --- the history gate ----------------------------------------------------

    @Test
    fun `no history means no warning, however impossible the deadline looks`() {
        val trip = evaluateGuiltTrip(
            goal(deadlineIn = 1),
            lessons(subTask("a", minutes = 100_000)),
            emptyList(),
            TEST_DATE,
        )
        assertNull(trip, "a warning with no evidence behind it is exactly the noise to avoid")
    }

    @Test
    fun `one day of history is not enough to project from`() {
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 6000)),
            listOf(
                logEntry("a", AdherenceOutcome.ON_TIME, daysAgo = 0),
                logEntry("a", AdherenceOutcome.ON_TIME, daysAgo = 0),
                logEntry("a", AdherenceOutcome.ON_TIME, daysAgo = 0),
            ),
            TEST_DATE,
        )
        assertNull(trip)
    }

    @Test
    fun `misses alone do not meet the completion threshold`() {
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 6000)),
            listOf(
                logEntry("a", AdherenceOutcome.MISSED, daysAgo = 2),
                logEntry("a", AdherenceOutcome.MISSED, daysAgo = 1),
                logEntry("a", AdherenceOutcome.MISSED, daysAgo = 0),
            ),
            TEST_DATE,
        )
        assertNull(trip)
        val projection = projectPace(
            goal(),
            lessons(subTask("a", minutes = 6000)),
            listOf(
                logEntry("a", AdherenceOutcome.MISSED, daysAgo = 2),
                logEntry("a", AdherenceOutcome.MISSED, daysAgo = 1),
                logEntry("a", AdherenceOutcome.MISSED, daysAgo = 0),
            ),
            TEST_DATE,
        )
        assertEquals(ProjectionBlocker.INSUFFICIENT_HISTORY, projection.blocker)
    }

    // --- the projection gate -------------------------------------------------

    @Test
    fun `a goal comfortably on pace says nothing`() {
        // 60m left, 5 days at 30m/day is 150m of capacity.
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 60)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertNull(trip)
    }

    @Test
    fun `a goal exactly on pace says nothing`() {
        // 30m/day observed, 5 days left, 150m of work: no shortfall.
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 150)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertNull(trip)
    }

    @Test
    fun `one minute of shortfall is enough to trigger`() {
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 151)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertNotNull(trip)
    }

    @Test
    fun `finished work says nothing`() {
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 6000, status = ProgressStatus.DONE)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertNull(trip)
    }

    @Test
    fun `a deadline already past is a different conversation`() {
        val projection = projectPace(
            goal(deadlineIn = -2),
            lessons(subTask("a", minutes = 6000)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertEquals(ProjectionBlocker.DEADLINE_PASSED, projection.blocker)
        assertNull(
            evaluateGuiltTrip(
                goal(deadlineIn = -2),
                lessons(subTask("a", minutes = 6000)),
                slowButRealHistory(),
                TEST_DATE,
            ),
        )
    }

    // --- the arithmetic ------------------------------------------------------

    @Test
    fun `the projection reports the numbers behind its conclusion`() {
        val projection = projectPace(
            goal(deadlineIn = 5),
            lessons(subTask("a", minutes = 300)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertEquals(300, projection.remainingMinutes)
        assertEquals(30.0, projection.observedMinutesPerDay)
        assertEquals(5, projection.daysRemaining)
        assertEquals(150.0, projection.minutesAvailableAtThisPace)
        assertEquals(150.0, projection.shortfallMinutes)
        assertEquals(10, projection.daysNeededAtThisPace)
        assertEquals(TEST_DATE.plusDays(10), projection.projectedFinish)
        assertTrue(projection.willMissDeadline)
    }

    @Test
    fun `only this goal's own history counts towards its pace`() {
        val projection = projectPace(
            goal(),
            lessons(subTask("a", minutes = 300)),
            slowButRealHistory() + listOf(
                logEntry("unrelated", AdherenceOutcome.ON_TIME, daysAgo = 1, slotMinutes = 600),
            ),
            TEST_DATE,
        )
        assertEquals(30.0, projection.observedMinutesPerDay)
    }

    @Test
    fun `remaining work excludes what is already done`() {
        val projection = projectPace(
            goal(),
            lessons(
                subTask("a", minutes = 300, status = ProgressStatus.DONE),
                subTask("b", minutes = 120),
            ),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertEquals(120, projection.remainingMinutes)
    }

    @Test
    fun `the explanation states the arithmetic rather than a verdict`() {
        val projection = projectPace(
            goal(),
            lessons(subTask("a", minutes = 300)),
            slowButRealHistory(),
            TEST_DATE,
        )
        val text = projection.explain()
        assertTrue(text.contains("300m of work left"))
        assertTrue(text.contains("5d until the deadline"))
        assertTrue(text.contains("30m/day"))
        assertTrue(text.contains("short"))
    }

    @Test
    fun `a reachable deadline explains why it is reachable`() {
        val projection = projectPace(
            goal(),
            lessons(subTask("a", minutes = 60)),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertFalse(projection.willMissDeadline)
        assertTrue(projection.explain().contains("which is enough"))
    }

    // --- the message ---------------------------------------------------------

    @Test
    fun `the message covers progress, days remaining, the projection and the ask`() {
        val trip = evaluateGuiltTrip(
            goal(totalLessons = 4),
            lessons(subTask("a", minutes = 300), subTask("b", minutes = 300)),
            slowButRealHistory(),
            TEST_DATE,
        )!!

        // Progress so far.
        assertTrue(trip.message.contains("0 of 4 lessons done"))
        assertTrue(trip.message.contains("0/2 sub-tasks"))
        // Days remaining.
        assertTrue(trip.message.contains("5 days left"))
        // Why the projection misses.
        assertTrue(trip.message.contains("600m of work left"))
        assertTrue(trip.message.contains("30m/day"))
        assertTrue(trip.message.contains("short"))
        // The request to reshuffle.
        assertTrue(trip.reshuffleRequest.contains("different plan"))
        assertTrue(trip.reshuffleRequest.contains("tomorrow"))
    }

    @Test
    fun `the message names the projected finish and how late it is`() {
        val trip = evaluateGuiltTrip(
            goal(deadlineIn = 5),
            lessons(subTask("a", minutes = 300)),
            slowButRealHistory(),
            TEST_DATE,
        )!!
        assertTrue(trip.message.contains(TEST_DATE.plusDays(10).toString()))
        assertTrue(trip.message.contains("5d after the deadline"))
    }

    @Test
    fun `a deadline today is phrased as today`() {
        val trip = evaluateGuiltTrip(
            goal(deadlineIn = 0),
            lessons(subTask("a", minutes = 300)),
            slowButRealHistory(),
            TEST_DATE,
        )!!
        assertTrue(trip.message.contains("The deadline is today"))
    }

    @Test
    fun `misses are named in the summary when there are any`() {
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 6000)),
            slowButRealHistory() + listOf(
                logEntry("a", AdherenceOutcome.MISSED, daysAgo = 3),
                logEntry("a", AdherenceOutcome.MISSED, daysAgo = 4),
            ),
            TEST_DATE,
        )!!
        assertTrue(trip.adherenceSummary.contains("2 scheduled session(s) missed"))
    }

    @Test
    fun `honoured but insufficient sessions are described as such`() {
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 6000)),
            slowButRealHistory(),
            TEST_DATE,
        )!!
        assertTrue(trip.adherenceSummary.contains("not enough of them"))
    }

    @Test
    fun `the message is arithmetic, not admonishment`() {
        val trip = evaluateGuiltTrip(
            goal(),
            lessons(subTask("a", minutes = 6000)),
            slowButRealHistory(),
            TEST_DATE,
        )!!
        listOf("lazy", "should have", "disappointing", "failure", "again").forEach {
            assertFalse(
                trip.message.lowercase().contains(it),
                "the message should state consequence, not scold: found '$it'",
            )
        }
    }

    // --- across many goals ---------------------------------------------------

    @Test
    fun `every goal is evaluated and only the qualifying ones are returned`() {
        val binding = goal()
        val soft = goal(consequence = ConsequenceType.SOFT_GOAL).copy(id = "g2")
        val trips = evaluateAllGuiltTrips(
            listOf(
                binding to lessons(subTask("a", minutes = 6000)),
                soft to lessons(subTask("a", minutes = 6000)),
            ),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertEquals(listOf("g1"), trips.map { it.goalId })
    }

    @Test
    fun `warnings are ordered by deadline, not by how far behind they are`() {
        val urgent = goal(deadlineIn = 2).copy(id = "urgent", title = "Urgent")
        val distant = goal(deadlineIn = 20).copy(id = "distant", title = "Distant")
        // Both reference sub-task "a", so both have the same recorded history
        // to project from and the ordering is decided purely by deadline.
        val trips = evaluateAllGuiltTrips(
            listOf(
                distant to lessons(subTask("a", minutes = 100_000)),
                urgent to lessons(subTask("a", minutes = 500)),
            ),
            slowButRealHistory(),
            TEST_DATE,
        )
        assertEquals(listOf("urgent", "distant"), trips.map { it.goalId })
    }

    @Test
    fun `evaluation is pure and repeatable`() {
        val g = goal()
        val l = lessons(subTask("a", minutes = 6000))
        val log = slowButRealHistory()
        assertEquals(
            evaluateGuiltTrip(g, l, log, TEST_DATE),
            evaluateGuiltTrip(g, l, log, TEST_DATE),
        )
    }
}
