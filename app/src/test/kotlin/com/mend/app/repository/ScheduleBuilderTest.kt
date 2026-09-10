package com.mend.app.repository

import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Flexibility
import com.mend.engine.model.Goal
import com.mend.engine.model.Habit
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.SubTask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam between stored data and the engine. What it selects as "pending"
 * decides what the whole scheduler ever sees, so it is worth pinning down.
 */
class ScheduleBuilderTest {

    private val date = LocalDate.of(2026, 9, 16)

    private val goal = Goal(
        id = "g1",
        title = "Japanese N5",
        deadline = date.plusDays(10),
        manualImportance = 4,
        consequenceType = ConsequenceType.IRL_BINDING,
        totalLessons = 3,
    )

    private fun subTask(
        id: String,
        lessonId: String = "l1",
        minutes: Long = 30,
        status: ProgressStatus = ProgressStatus.NOT_STARTED,
        flexibility: Flexibility = Flexibility.SPLITTABLE,
    ) = SubTask(
        id = id,
        lessonId = lessonId,
        type = "reading",
        title = id,
        estimatedDuration = Duration.ofMinutes(minutes),
        flexibility = flexibility,
        status = status,
    )

    private fun lesson(
        id: String,
        order: Int = 0,
        status: ProgressStatus = ProgressStatus.NOT_STARTED,
        subTasks: List<SubTask> = emptyList(),
    ) = Lesson(
        id = id,
        goalId = "g1",
        orderIndex = order,
        title = id,
        status = status,
        subTasks = subTasks,
    )

    // --- selecting pending work ---------------------------------------------

    @Test
    fun `unfinished sub-tasks become schedulable tasks`() {
        val tasks = ScheduleBuilder.pendingTasks(
            goal,
            listOf(lesson("l1", subTasks = listOf(subTask("a"), subTask("b")))),
        )
        assertEquals(listOf("a", "b"), tasks.map { it.id })
    }

    @Test
    fun `finished sub-tasks are not scheduled again`() {
        val tasks = ScheduleBuilder.pendingTasks(
            goal,
            listOf(
                lesson(
                    "l1",
                    subTasks = listOf(
                        subTask("done", status = ProgressStatus.DONE),
                        subTask("todo"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("todo"), tasks.map { it.id })
    }

    @Test
    fun `a completed lesson contributes nothing`() {
        val tasks = ScheduleBuilder.pendingTasks(
            goal,
            listOf(lesson("l1", subTasks = listOf(subTask("a", status = ProgressStatus.DONE)))),
        )
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `a bare lesson schedules itself`() {
        val tasks = ScheduleBuilder.pendingTasks(goal, listOf(lesson("bare")))
        val task = tasks.single()
        assertEquals("bare", task.id)
        assertEquals("bare", task.lessonId)
        assertEquals(null, task.subTaskId)
        assertEquals(ScheduleBuilder.BARE_LESSON_ESTIMATE, task.requestedDuration)
    }

    @Test
    fun `a completed bare lesson contributes nothing`() {
        val tasks = ScheduleBuilder.pendingTasks(
            goal,
            listOf(lesson("bare", status = ProgressStatus.DONE)),
        )
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `tasks inherit deadline and importance from their goal`() {
        val task = ScheduleBuilder.pendingTasks(
            goal,
            listOf(lesson("l1", subTasks = listOf(subTask("a")))),
        ).single()
        assertEquals(goal.deadline, task.deadline)
        assertEquals(goal.manualImportance, task.manualImportance)
        assertEquals(goal.consequenceType, task.consequenceType)
        assertEquals(goal.id, task.goalId)
    }

    @Test
    fun `a sub-task keeps its own flexibility`() {
        val task = ScheduleBuilder.pendingTasks(
            goal,
            listOf(lesson("l1", subTasks = listOf(subTask("mock", flexibility = Flexibility.FIXED)))),
        ).single()
        assertEquals(Flexibility.FIXED, task.flexibility)
    }

    @Test
    fun `a sub-task asks for exactly its estimated duration`() {
        val task = ScheduleBuilder.pendingTasks(
            goal,
            listOf(lesson("l1", subTasks = listOf(subTask("a", minutes = 75)))),
        ).single()
        assertEquals(Duration.ofMinutes(75), task.requestedDuration)
    }

    // --- the assembled day ---------------------------------------------------

    @Test
    fun `the built day places goal work into what the static layer leaves`() {
        val schedule = ScheduleBuilder.buildDaySchedule(
            date = date,
            staticBlocks = listOf(
                StaticBlock(
                    id = "college",
                    title = "College",
                    windowStart = LocalTime.of(9, 0),
                    windowEnd = LocalTime.of(16, 0),
                ),
            ),
            habits = listOf(
                Habit(id = "wake", title = "Wake", timeOfDay = LocalTime.of(7, 0)),
            ),
            goals = listOf(
                GoalWithLessons(
                    goal,
                    listOf(lesson("l1", subTasks = listOf(subTask("a", minutes = 60)))),
                ),
            ),
        )

        val placement = schedule.placements.single()
        assertEquals("a", placement.task.id)
        assertEquals(60, placement.scheduledMinutes)

        // Nothing scheduled may collide with the college block or the habit.
        val fixed = schedule.freeTimeMap.placements.map { it.interval }
        placement.slices.forEach { slice ->
            fixed.forEach { block ->
                assertTrue(!slice.overlaps(block), "$slice collides with $block")
            }
        }
    }

    @Test
    fun `a day with no goals still produces the static skeleton`() {
        val schedule = ScheduleBuilder.buildDaySchedule(
            date = date,
            staticBlocks = listOf(
                StaticBlock(
                    id = "college",
                    title = "College",
                    windowStart = LocalTime.of(9, 0),
                    windowEnd = LocalTime.of(16, 0),
                ),
            ),
            habits = emptyList(),
            goals = emptyList(),
        )
        assertTrue(schedule.placements.isEmpty())
        assertEquals(1, schedule.freeTimeMap.placements.size)
    }

    @Test
    fun `work from several goals competes in one ranking`() {
        val urgent = goal.copy(id = "urgent", deadline = date, manualImportance = 5)
        val distant = goal.copy(id = "distant", deadline = date.plusDays(29), manualImportance = 1)

        val schedule = ScheduleBuilder.buildDaySchedule(
            date = date,
            staticBlocks = emptyList(),
            habits = emptyList(),
            goals = listOf(
                GoalWithLessons(
                    distant,
                    listOf(lesson("dl", subTasks = listOf(subTask("distant-task", "dl")))),
                ),
                GoalWithLessons(
                    urgent,
                    listOf(lesson("ul", subTasks = listOf(subTask("urgent-task", "ul")))),
                ),
            ),
        )
        assertEquals("urgent-task", schedule.placements.first().task.id)
    }

    @Test
    fun `the decision log records every task considered`() {
        val schedule = ScheduleBuilder.buildDaySchedule(
            date = date,
            staticBlocks = emptyList(),
            habits = emptyList(),
            goals = listOf(
                GoalWithLessons(
                    goal,
                    listOf(lesson("l1", subTasks = listOf(subTask("a"), subTask("b")))),
                ),
            ),
        )
        assertTrue(schedule.log.forTask("a").isNotEmpty())
        assertTrue(schedule.log.forTask("b").isNotEmpty())
    }
}
