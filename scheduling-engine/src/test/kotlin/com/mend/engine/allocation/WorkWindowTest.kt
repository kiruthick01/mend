package com.mend.engine.allocation

import com.mend.engine.TEST_DATE
import com.mend.engine.at
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Flexibility
import com.mend.engine.model.Goal
import com.mend.engine.model.GoalTask
import com.mend.engine.model.Lesson
import com.mend.engine.model.SubTask
import com.mend.engine.render
import com.mend.engine.staticBlock
import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Work confined to certain hours.
 *
 * The point of a work window is that it narrows *where* work may go without
 * reserving that time — so higher-priority work can still take it. That is what
 * makes "project time gives way when exams are announced" fall out of ordinary
 * ranking rather than needing a rule of its own, and it is what these pin down.
 */
class WorkWindowTest {

    private fun goal(
        id: String,
        deadlineIn: Long,
        importance: Int,
        window: Pair<LocalTime, LocalTime>? = null,
    ) = Goal(
        id = id,
        title = id,
        deadline = TEST_DATE.plusDays(deadlineIn),
        manualImportance = importance,
        consequenceType = ConsequenceType.SOFT_GOAL,
        totalLessons = 1,
        workWindowStart = window?.first,
        workWindowEnd = window?.second,
    )

    private fun task(goal: Goal, id: String, minutes: Long) = GoalTask.fromSubTask(
        goal = goal,
        subTask = SubTask(
            id = id,
            lessonId = "l-${goal.id}",
            type = "work",
            title = id,
            estimatedDuration = Duration.ofMinutes(minutes),
            flexibility = Flexibility.MOVABLE,
        ),
    )

    /** An evening free from 17:00 to 22:00. */
    private fun evening() = computeFreeTimeMap(TEST_DATE, dayStart = at(17), dayEnd = at(22))

    // --- the window itself ---------------------------------------------------

    @Test
    fun `a goal window reaches the task that comes from it`() {
        val project = goal("project", deadlineIn = 10, importance = 3, window = at(20) to at(22))
        val t = task(project, "build", 60)
        assertTrue(t.timeSpec.isWindowed)
        assertEquals(at(20), t.timeSpec.windowStart)
    }

    @Test
    fun `confined work is only placed inside its window`() {
        val project = goal("project", deadlineIn = 10, importance = 3, window = at(20) to at(22))
        val schedule = allocate(evening(), listOf(task(project, "build", 60)))
        assertEquals(listOf("20:00-21:00"), schedule.placements.single().slices.render())
    }

    @Test
    fun `unconfined work takes the earliest slot as before`() {
        val open = goal("open", deadlineIn = 10, importance = 3)
        val schedule = allocate(evening(), listOf(task(open, "anything", 60)))
        assertEquals(listOf("17:00-18:00"), schedule.placements.single().slices.render())
    }

    @Test
    fun `confined work with no free time in its window goes unscheduled`() {
        val project = goal("project", deadlineIn = 10, importance = 3, window = at(20) to at(22))
        val map = computeFreeTimeMap(
            TEST_DATE,
            staticBlocks = listOf(staticBlock("evening-class", from = at(19), to = at(22))),
            dayStart = at(17),
            dayEnd = at(22),
        )
        val schedule = allocate(map, listOf(task(project, "build", 60)))
        assertTrue(schedule.placements.isEmpty())
        assertTrue(schedule.unscheduled.single().reason.contains("window"))
    }

    @Test
    fun `a window shorter than the work leaves it unscheduled rather than overflowing`() {
        val project = goal("project", deadlineIn = 10, importance = 3, window = at(20) to at(20, 30))
        val schedule = allocate(evening(), listOf(task(project, "build", 120)))
        assertTrue(schedule.placements.isEmpty())
    }

    @Test
    fun `a half-open window is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Goal(
                id = "g",
                title = "g",
                deadline = TEST_DATE,
                manualImportance = 3,
                workWindowStart = at(20),
            )
        }
    }

    @Test
    fun `a backwards window is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Goal(
                id = "g",
                title = "g",
                deadline = TEST_DATE,
                manualImportance = 3,
                workWindowStart = at(22),
                workWindowEnd = at(20),
            )
        }
    }

    // --- the behaviour that was actually asked for ---------------------------

    @Test
    fun `project time gives way when exam revision becomes more urgent`() {
        // Project work can only happen 20:00-22:00. Exam revision can happen
        // anywhere, and its deadline is close.
        val project = goal("project", deadlineIn = 20, importance = 3, window = at(20) to at(22))
        val exam = goal("exam", deadlineIn = 1, importance = 5)

        val schedule = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(20), dayEnd = at(22)),
            listOf(
                task(project, "project-work", 120),
                task(exam, "revision", 120),
            ),
        )

        assertEquals(
            listOf("revision"),
            schedule.placements.map { it.task.id },
            "the exam outranks the project and takes the only window there is",
        )
        assertEquals(listOf("project-work"), schedule.unscheduled.map { it.task.id })
    }

    @Test
    fun `with room for both, the project keeps its window`() {
        val project = goal("project", deadlineIn = 20, importance = 3, window = at(20) to at(22))
        val exam = goal("exam", deadlineIn = 1, importance = 5)

        val schedule = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(17), dayEnd = at(22)),
            listOf(
                task(project, "project-work", 60),
                task(exam, "revision", 60),
            ),
        )

        val placed = schedule.placements.associate { it.task.id to it.slices.render() }
        assertEquals(listOf("17:00-18:00"), placed["revision"])
        assertEquals(listOf("20:00-21:00"), placed["project-work"])
    }

    @Test
    fun `the reason says the window is why it could not be placed`() {
        val project = goal("project", deadlineIn = 20, importance = 3, window = at(20) to at(22))
        val exam = goal("exam", deadlineIn = 0, importance = 5)
        val schedule = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(20), dayEnd = at(22)),
            listOf(task(project, "project-work", 120), task(exam, "revision", 120)),
        )
        assertTrue(
            schedule.log.forTask("project-work")
                .any { it.message.contains("window") || it.message.contains("free time") },
        )
    }

    @Test
    fun `a bare lesson inherits the window too`() {
        val project = goal("project", deadlineIn = 10, importance = 3, window = at(20) to at(22))
        val t = GoalTask.fromBareLesson(
            goal = project,
            lesson = Lesson(id = "l1", goalId = project.id, orderIndex = 0, title = "Milestone"),
            estimatedDuration = Duration.ofMinutes(60),
        )
        assertEquals(at(20), t.timeSpec.windowStart)
    }

    @Test
    fun `splittable confined work only splits within the window`() {
        val project = goal("project", deadlineIn = 10, importance = 3, window = at(20) to at(22))
        val split = GoalTask.fromSubTask(
            goal = project,
            subTask = SubTask(
                id = "long",
                lessonId = "l",
                type = "work",
                title = "long",
                estimatedDuration = Duration.ofMinutes(300),
                flexibility = Flexibility.SPLITTABLE,
            ),
        )
        val schedule = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(8), dayEnd = at(23)),
            listOf(split),
        )
        val placement = schedule.placements.single()
        assertEquals(120, placement.scheduledMinutes)
        assertTrue(placement.isPartial)
        assertEquals(listOf("20:00-22:00"), placement.slices.render())
    }
}
