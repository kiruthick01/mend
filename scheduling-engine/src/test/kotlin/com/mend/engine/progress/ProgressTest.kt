package com.mend.engine.progress

import com.mend.engine.TEST_DATE
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressTest {

    private fun subTask(
        id: String,
        type: String = "reading",
        minutes: Long = 30,
        status: ProgressStatus = ProgressStatus.NOT_STARTED,
        comfortable: Long? = null,
        completedAt: LocalDateTime? = null,
    ) = SubTask(
        id = id,
        lessonId = "lesson",
        type = type,
        title = id,
        estimatedDuration = Duration.ofMinutes(minutes),
        paceComfortable = comfortable?.let(Duration::ofMinutes),
        status = status,
        completedAt = completedAt,
    )

    private fun lesson(
        id: String,
        order: Int = 0,
        status: ProgressStatus = ProgressStatus.NOT_STARTED,
        subTasks: List<SubTask> = emptyList(),
    ) = Lesson(
        id = id,
        goalId = "goal",
        orderIndex = order,
        title = id,
        status = status,
        subTasks = subTasks,
    )

    private fun goal(totalLessons: Int, deadline: LocalDate = TEST_DATE.plusDays(10)) = Goal(
        id = "goal",
        title = "Japanese N5",
        deadline = deadline,
        manualImportance = 3,
        consequenceType = ConsequenceType.SOFT_GOAL,
        totalLessons = totalLessons,
    )

    // --- lesson-level rollup -------------------------------------------------

    @Test
    fun `a bare lesson is complete only when marked done itself`() {
        assertFalse(lesson("l1").isComplete())
        assertTrue(lesson("l1", status = ProgressStatus.DONE).isComplete())
    }

    @Test
    fun `a lesson with sub-tasks is complete when all of them are done`() {
        val l = lesson(
            "l1",
            subTasks = listOf(
                subTask("a", status = ProgressStatus.DONE),
                subTask("b", status = ProgressStatus.DONE),
            ),
        )
        assertTrue(l.isComplete())
        assertEquals(ProgressStatus.DONE, l.derivedStatus())
    }

    @Test
    fun `a lesson with one sub-task left is in progress, not done`() {
        val l = lesson(
            "l1",
            subTasks = listOf(
                subTask("a", status = ProgressStatus.DONE),
                subTask("b", status = ProgressStatus.NOT_STARTED),
            ),
        )
        assertFalse(l.isComplete())
        assertEquals(ProgressStatus.IN_PROGRESS, l.derivedStatus())
    }

    @Test
    fun `a lesson whose sub-tasks are untouched is not started`() {
        val l = lesson("l1", subTasks = listOf(subTask("a"), subTask("b")))
        assertEquals(ProgressStatus.NOT_STARTED, l.derivedStatus())
    }

    @Test
    fun `a stale lesson status cannot override finished sub-tasks`() {
        // The lesson row still says NOT_STARTED, but every sub-task is done.
        val l = lesson(
            "l1",
            status = ProgressStatus.NOT_STARTED,
            subTasks = listOf(subTask("a", status = ProgressStatus.DONE)),
        )
        assertEquals(ProgressStatus.DONE, l.derivedStatus())
    }

    @Test
    fun `a stale lesson status cannot claim completion sub-tasks contradict`() {
        val l = lesson(
            "l1",
            status = ProgressStatus.DONE,
            subTasks = listOf(subTask("a", status = ProgressStatus.NOT_STARTED)),
        )
        assertEquals(ProgressStatus.NOT_STARTED, l.derivedStatus())
        assertFalse(l.isComplete())
    }

    @Test
    fun `lesson completion fraction counts done sub-tasks`() {
        val l = lesson(
            "l1",
            subTasks = listOf(
                subTask("a", status = ProgressStatus.DONE),
                subTask("b", status = ProgressStatus.DONE),
                subTask("c"),
                subTask("d"),
            ),
        )
        assertEquals(0.5, l.completionFraction())
    }

    @Test
    fun `a bare lesson fraction is all or nothing`() {
        assertEquals(0.0, lesson("l1").completionFraction())
        assertEquals(1.0, lesson("l1", status = ProgressStatus.DONE).completionFraction())
    }

    @Test
    fun `lessons in one goal need not share a sub-task structure`() {
        // Three lessons: one bare, one with a single sub-task, one with three
        // differently named ones. No template, and the rollup handles all of it.
        val lessons = listOf(
            lesson("bare", 0, status = ProgressStatus.DONE),
            lesson("single", 1, subTasks = listOf(subTask("x", type = "listening", status = ProgressStatus.DONE))),
            lesson(
                "many",
                2,
                subTasks = listOf(
                    subTask("p", type = "reading", status = ProgressStatus.DONE),
                    subTask("q", type = "writing"),
                    subTask("r", type = "past paper"),
                ),
            ),
        )
        val progress = computeGoalProgress(goal(3), lessons, TEST_DATE)
        assertEquals(2, progress.lessonsCompleted)
        assertEquals(3, progress.totalLessons)
        // "single" contributes one done sub-task, "many" one of its three.
        assertEquals(2, progress.subTasksCompleted)
        assertEquals(4, progress.totalSubTasks)
    }

    // --- goal-level rollup ---------------------------------------------------

    @Test
    fun `goal rollup reports completed over total`() {
        val lessons = listOf(
            lesson("l1", 0, status = ProgressStatus.DONE),
            lesson("l2", 1, status = ProgressStatus.DONE),
            lesson("l3", 2),
        )
        val progress = computeGoalProgress(goal(4), lessons, TEST_DATE)
        assertEquals(2, progress.lessonsCompleted)
        assertEquals(4, progress.totalLessons)
        assertEquals(0.5, progress.fraction)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `goal rollup never exceeds one hundred percent when extra lessons exist`() {
        // Declared 2, but 3 lessons were entered and all are done.
        val lessons = (0..2).map { lesson("l$it", it, status = ProgressStatus.DONE) }
        val progress = computeGoalProgress(goal(2), lessons, TEST_DATE)
        assertEquals(3, progress.totalLessons)
        assertEquals(1.0, progress.fraction)
        assertTrue(progress.isComplete)
    }

    @Test
    fun `a goal with no lessons reports zero rather than dividing by zero`() {
        val progress = computeGoalProgress(goal(0), emptyList(), TEST_DATE)
        assertEquals(0.0, progress.fraction)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `days remaining counts forward to the deadline`() {
        val progress = computeGoalProgress(
            goal(3, deadline = TEST_DATE.plusDays(7)),
            emptyList(),
            TEST_DATE,
        )
        assertEquals(7, progress.daysRemaining)
        assertFalse(progress.isOverdue)
    }

    @Test
    fun `a passed deadline with work outstanding is overdue`() {
        val progress = computeGoalProgress(
            goal(3, deadline = TEST_DATE.minusDays(2)),
            listOf(lesson("l1")),
            TEST_DATE,
        )
        assertEquals(-2, progress.daysRemaining)
        assertTrue(progress.isOverdue)
    }

    @Test
    fun `a finished goal past its deadline is not overdue`() {
        val lessons = listOf(lesson("l1", 0, status = ProgressStatus.DONE))
        val progress = computeGoalProgress(
            goal(1, deadline = TEST_DATE.minusDays(2)),
            lessons,
            TEST_DATE,
        )
        assertTrue(progress.isComplete)
        assertFalse(progress.isOverdue)
    }

    // --- pace ----------------------------------------------------------------

    @Test
    fun `pace is grouped by user-named sub-task type`() {
        val lessons = listOf(
            lesson("l1", subTasks = listOf(subTask("a", type = "reading"))),
            lesson("l2", 1, subTasks = listOf(subTask("b", type = "past paper"))),
            lesson("l3", 2, subTasks = listOf(subTask("c", type = "reading"))),
        )
        val pace = computePace(lessons, TEST_DATE)
        assertEquals(listOf("past paper", "reading"), pace.map { it.subTaskType })
        assertEquals(2, pace.first { it.subTaskType == "reading" }.totalCount)
    }

    @Test
    fun `actual velocity averages completed minutes over the observed days`() {
        // 60 minutes done four days ago, 60 more done today: 120 minutes over
        // a five-day window (inclusive) is 24 minutes a day.
        val lessons = listOf(
            lesson(
                "l1",
                subTasks = listOf(
                    subTask(
                        "a",
                        minutes = 60,
                        status = ProgressStatus.DONE,
                        completedAt = TEST_DATE.minusDays(4).atTime(9, 0),
                    ),
                    subTask(
                        "b",
                        minutes = 60,
                        status = ProgressStatus.DONE,
                        completedAt = TEST_DATE.atTime(9, 0),
                    ),
                ),
            ),
        )
        val pace = computePace(lessons, TEST_DATE).single()
        assertEquals(5, pace.observationDays)
        assertEquals(24.0, pace.actualMinutesPerDay)
        assertEquals(2, pace.completedCount)
    }

    @Test
    fun `a single completion today reads as one day of work`() {
        val lessons = listOf(
            lesson(
                "l1",
                subTasks = listOf(
                    subTask(
                        "a",
                        minutes = 45,
                        status = ProgressStatus.DONE,
                        completedAt = TEST_DATE.atTime(8, 0),
                    ),
                ),
            ),
        )
        val pace = computePace(lessons, TEST_DATE).single()
        assertEquals(1, pace.observationDays)
        assertEquals(45.0, pace.actualMinutesPerDay)
    }

    @Test
    fun `nothing completed yields zero velocity rather than a divide by zero`() {
        val lessons = listOf(lesson("l1", subTasks = listOf(subTask("a"))))
        val pace = computePace(lessons, TEST_DATE).single()
        assertEquals(0.0, pace.actualMinutesPerDay)
        assertEquals(1, pace.observationDays)
        assertEquals(0, pace.completedCount)
    }

    @Test
    fun `work done before tracking began does not invent a burst of velocity`() {
        val lessons = listOf(
            lesson(
                "l1",
                subTasks = listOf(
                    subTask("untracked", minutes = 600, status = ProgressStatus.DONE),
                ),
            ),
        )
        val pace = computePace(lessons, TEST_DATE).single()
        assertEquals(0.0, pace.actualMinutesPerDay)
        assertEquals(1, pace.completedCount)
    }

    @Test
    fun `declared comfortable pace is averaged across the type`() {
        val lessons = listOf(
            lesson(
                "l1",
                subTasks = listOf(
                    subTask("a", comfortable = 20),
                    subTask("b", comfortable = 40),
                ),
            ),
        )
        assertEquals(30, computePace(lessons, TEST_DATE).single().comfortableMinutesPerDay)
    }

    @Test
    fun `no declared pace leaves the comparison undefined rather than assumed`() {
        val lessons = listOf(lesson("l1", subTasks = listOf(subTask("a"))))
        val pace = computePace(lessons, TEST_DATE).single()
        assertNull(pace.comfortableMinutesPerDay)
        assertNull(pace.ratio)
        assertFalse(pace.isBehindComfortablePace)
    }

    @Test
    fun `falling short of the declared pace is reported as behind`() {
        val lessons = listOf(
            lesson(
                "l1",
                subTasks = listOf(
                    subTask(
                        "a",
                        minutes = 15,
                        comfortable = 60,
                        status = ProgressStatus.DONE,
                        completedAt = TEST_DATE.atTime(9, 0),
                    ),
                ),
            ),
        )
        val pace = computePace(lessons, TEST_DATE).single()
        assertEquals(0.25, pace.ratio)
        assertTrue(pace.isBehindComfortablePace)
    }

    @Test
    fun `meeting the declared pace is not reported as behind`() {
        val lessons = listOf(
            lesson(
                "l1",
                subTasks = listOf(
                    subTask(
                        "a",
                        minutes = 60,
                        comfortable = 60,
                        status = ProgressStatus.DONE,
                        completedAt = TEST_DATE.atTime(9, 0),
                    ),
                ),
            ),
        )
        assertFalse(computePace(lessons, TEST_DATE).single().isBehindComfortablePace)
    }

    @Test
    fun `lessons with no sub-tasks contribute nothing to the pace view`() {
        val lessons = listOf(lesson("bare", status = ProgressStatus.DONE))
        assertEquals(emptyList(), computePace(lessons, TEST_DATE))
    }

    @Test
    fun `remaining effort sums only outstanding sub-tasks`() {
        val lessons = listOf(
            lesson(
                "l1",
                subTasks = listOf(
                    subTask("a", minutes = 30, status = ProgressStatus.DONE),
                    subTask("b", minutes = 45),
                    subTask("c", minutes = 15),
                ),
            ),
        )
        assertEquals(Duration.ofMinutes(60), remainingEffort(lessons))
    }
}
