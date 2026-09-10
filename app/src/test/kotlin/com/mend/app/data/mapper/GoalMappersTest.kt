package com.mend.app.data.mapper

import com.mend.app.data.entity.GoalEntity
import com.mend.app.data.entity.LessonEntity
import com.mend.app.data.entity.SubTaskEntity
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalMappersTest {

    private val deadline = LocalDate.of(2026, 12, 1)

    private fun goalEntity(id: String = "g1", total: Int = 3) = GoalEntity(
        id = id,
        title = "Japanese N5",
        deadline = deadline,
        manualImportance = 4,
        consequenceType = ConsequenceType.IRL_BINDING,
        totalLessons = total,
    )

    private fun lessonEntity(
        id: String,
        goalId: String = "g1",
        order: Int = 0,
        status: ProgressStatus = ProgressStatus.NOT_STARTED,
    ) = LessonEntity(
        id = id,
        goalId = goalId,
        orderIndex = order,
        title = id,
        status = status,
    )

    private fun subTaskEntity(
        id: String,
        lessonId: String,
        type: String = "reading",
        order: Int = 0,
        status: ProgressStatus = ProgressStatus.NOT_STARTED,
    ) = SubTaskEntity(
        id = id,
        lessonId = lessonId,
        type = type,
        title = id,
        estimatedDurationMinutes = 30,
        status = status,
        orderIndex = order,
    )

    // --- round trips ---------------------------------------------------------

    @Test
    fun `a goal round-trips through the entity`() {
        val goal = Goal(
            id = "g1",
            title = "Japanese N5",
            deadline = deadline,
            manualImportance = 4,
            consequenceType = ConsequenceType.IRL_BINDING,
            totalLessons = 5,
            lessonsCompleted = 0,
            pileupRisk = true,
            negotiatedDelayLimit = Duration.ofHours(3),
        )
        assertEquals(goal, goal.toEntity().toDomain())
    }

    @Test
    fun `a lesson round-trips with its completion timestamp`() {
        val stamp = LocalDateTime.of(2026, 9, 11, 21, 30)
        val lesson = Lesson(
            id = "l1",
            goalId = "g1",
            orderIndex = 2,
            title = "Chapter 3",
            status = ProgressStatus.DONE,
            completedAt = stamp,
        )
        assertEquals(lesson, lesson.toEntity().toDomain())
    }

    @Test
    fun `a sub-task round-trips with its pace and timestamp`() {
        val subTask = SubTask(
            id = "s1",
            lessonId = "l1",
            type = "past paper",
            title = "2019 paper",
            estimatedDuration = Duration.ofMinutes(90),
            paceComfortable = Duration.ofMinutes(45),
            status = ProgressStatus.DONE,
            completedAt = LocalDateTime.of(2026, 9, 10, 18, 0),
        )
        assertEquals(subTask, subTask.toEntity().toDomain())
    }

    @Test
    fun `an absent comfortable pace stays absent rather than defaulting`() {
        val subTask = SubTask(
            id = "s1",
            lessonId = "l1",
            type = "reading",
            title = "pages 1-10",
            estimatedDuration = Duration.ofMinutes(20),
        )
        assertNull(subTask.toEntity().toDomain().paceComfortable)
    }

    // --- hierarchy assembly --------------------------------------------------

    @Test
    fun `assembly nests sub-tasks under lessons under goals`() {
        val result = assembleGoals(
            goals = listOf(goalEntity()),
            lessons = listOf(lessonEntity("l1"), lessonEntity("l2", order = 1)),
            subTasks = listOf(
                subTaskEntity("s1", "l1"),
                subTaskEntity("s2", "l1", order = 1),
                subTaskEntity("s3", "l2"),
            ),
        )
        val (goal, lessons) = result.single()
        assertEquals("g1", goal.id)
        assertEquals(listOf("l1", "l2"), lessons.map { it.id })
        assertEquals(listOf("s1", "s2"), lessons[0].subTasks.map { it.id })
        assertEquals(listOf("s3"), lessons[1].subTasks.map { it.id })
    }

    @Test
    fun `lessons are ordered by their order index, not insertion order`() {
        val result = assembleGoals(
            goals = listOf(goalEntity()),
            lessons = listOf(
                lessonEntity("third", order = 2),
                lessonEntity("first", order = 0),
                lessonEntity("second", order = 1),
            ),
            subTasks = emptyList(),
        )
        assertEquals(listOf("first", "second", "third"), result.single().second.map { it.id })
    }

    @Test
    fun `sub-tasks are ordered by their order index`() {
        val result = assembleGoals(
            goals = listOf(goalEntity()),
            lessons = listOf(lessonEntity("l1")),
            subTasks = listOf(
                subTaskEntity("b", "l1", order = 1),
                subTaskEntity("a", "l1", order = 0),
            ),
        )
        assertEquals(listOf("a", "b"), result.single().second.single().subTasks.map { it.id })
    }

    @Test
    fun `lessons completed is derived from the rows, never read from a stored counter`() {
        val result = assembleGoals(
            goals = listOf(goalEntity(total = 3)),
            lessons = listOf(
                // Bare lesson, marked done.
                lessonEntity("bare", status = ProgressStatus.DONE),
                // Has a sub-task, and it is done.
                lessonEntity("full", order = 1),
                // Has a sub-task still outstanding.
                lessonEntity("partial", order = 2),
            ),
            subTasks = listOf(
                subTaskEntity("s1", "full", status = ProgressStatus.DONE),
                subTaskEntity("s2", "partial", status = ProgressStatus.NOT_STARTED),
            ),
        )
        assertEquals(2, result.single().first.lessonsCompleted)
    }

    @Test
    fun `a lesson marked done but holding an unfinished sub-task does not count`() {
        val result = assembleGoals(
            goals = listOf(goalEntity(total = 1)),
            lessons = listOf(lessonEntity("l1", status = ProgressStatus.DONE)),
            subTasks = listOf(subTaskEntity("s1", "l1", status = ProgressStatus.NOT_STARTED)),
        )
        assertEquals(0, result.single().first.lessonsCompleted)
    }

    @Test
    fun `lessons belonging to other goals are not mixed in`() {
        val result = assembleGoals(
            goals = listOf(goalEntity("g1"), goalEntity("g2")),
            lessons = listOf(
                lessonEntity("l1", goalId = "g1"),
                lessonEntity("l2", goalId = "g2"),
            ),
            subTasks = emptyList(),
        )
        assertEquals(listOf("l1"), result[0].second.map { it.id })
        assertEquals(listOf("l2"), result[1].second.map { it.id })
    }

    @Test
    fun `a goal with no lessons assembles to an empty hierarchy`() {
        val result = assembleGoals(listOf(goalEntity(total = 0)), emptyList(), emptyList())
        assertTrue(result.single().second.isEmpty())
        assertEquals(0, result.single().first.lessonsCompleted)
    }

    @Test
    fun `total lessons is raised so completion can never exceed the declared count`() {
        // Declared 1, but two lessons exist and both are done.
        val result = assembleGoals(
            goals = listOf(goalEntity(total = 1)),
            lessons = listOf(
                lessonEntity("l1", status = ProgressStatus.DONE),
                lessonEntity("l2", order = 1, status = ProgressStatus.DONE),
            ),
            subTasks = emptyList(),
        )
        val goal = result.single().first
        assertEquals(2, goal.lessonsCompleted)
        assertEquals(2, goal.totalLessons)
    }

    @Test
    fun `lessons in one goal may have wholly different sub-task structures`() {
        val result = assembleGoals(
            goals = listOf(goalEntity(total = 3)),
            lessons = listOf(
                lessonEntity("bare"),
                lessonEntity("one", order = 1),
                lessonEntity("three", order = 2),
            ),
            subTasks = listOf(
                subTaskEntity("a", "one", type = "listening"),
                subTaskEntity("b", "three", type = "reading"),
                subTaskEntity("c", "three", type = "writing", order = 1),
                subTaskEntity("d", "three", type = "past paper", order = 2),
            ),
        )
        val lessons = result.single().second
        assertEquals(listOf(0, 1, 3), lessons.map { it.subTasks.size })
        assertEquals(
            listOf("reading", "writing", "past paper"),
            lessons[2].subTasks.map { it.type },
        )
    }
}
