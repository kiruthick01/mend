package com.mend.app.repository

import com.mend.app.data.dao.GoalDao
import com.mend.app.data.dao.LessonDao
import com.mend.app.data.dao.SubTaskDao
import com.mend.app.data.entity.GoalEntity
import com.mend.app.data.entity.LessonEntity
import com.mend.app.data.entity.SubTaskEntity
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Ticking a sub-task off must not move it.
 *
 * A sub-task is stored as a whole row, so recording a completion means writing
 * the row back — including its position within the lesson. Supplying a made-up
 * position there silently reorders the lesson as a side effect of ticking
 * something off, which for a sequential goal is a change to what comes next.
 *
 * This is exactly the kind of failure that never shows up in the code and only
 * shows up weeks later as "why is my Japanese course out of order", so it is
 * pinned here.
 */
class SubTaskOrderTest {

    private class FakeSubTaskDao : SubTaskDao {
        val rows = MutableStateFlow<List<SubTaskEntity>>(emptyList())

        override fun observeForLesson(lessonId: String): Flow<List<SubTaskEntity>> = rows

        override fun observeAll(): Flow<List<SubTaskEntity>> = rows

        override suspend fun forLessons(lessonIds: List<String>): List<SubTaskEntity> =
            rows.value.filter { it.lessonId in lessonIds }

        override suspend fun upsert(subTask: SubTaskEntity) {
            rows.value = rows.value.filterNot { it.id == subTask.id } + subTask
        }

        override suspend fun orderIndexOf(id: String): Int? =
            rows.value.firstOrNull { it.id == id }?.orderIndex

        override suspend fun upsertAll(subTasks: List<SubTaskEntity>) {
            subTasks.forEach { upsert(it) }
        }

        override suspend fun update(subTask: SubTaskEntity) = upsert(subTask)

        override suspend fun delete(subTask: SubTaskEntity) {
            rows.value = rows.value.filterNot { it.id == subTask.id }
        }
    }

    private class FakeGoalDao : GoalDao {
        val rows = MutableStateFlow<List<GoalEntity>>(emptyList())
        override fun observeAll(): Flow<List<GoalEntity>> = rows
        override suspend fun findById(id: String): GoalEntity? =
            rows.value.firstOrNull { it.id == id }
        override suspend fun upsert(goal: GoalEntity) {
            rows.value = rows.value.filterNot { it.id == goal.id } + goal
        }
        override suspend fun update(goal: GoalEntity) = upsert(goal)
        override suspend fun delete(goal: GoalEntity) {
            rows.value = rows.value.filterNot { it.id == goal.id }
        }
    }

    private class FakeLessonDao : LessonDao {
        val rows = MutableStateFlow<List<LessonEntity>>(emptyList())
        override fun observeForGoal(goalId: String): Flow<List<LessonEntity>> = rows
        override fun observeAll(): Flow<List<LessonEntity>> = rows
        override suspend fun upsert(lesson: LessonEntity) {
            rows.value = rows.value.filterNot { it.id == lesson.id } + lesson
        }
        override suspend fun upsertAll(lessons: List<LessonEntity>) {
            lessons.forEach { upsert(it) }
        }
        override suspend fun update(lesson: LessonEntity) = upsert(lesson)
        override suspend fun delete(lesson: LessonEntity) {
            rows.value = rows.value.filterNot { it.id == lesson.id }
        }
        override suspend fun countCompleted(goalId: String): Int =
            rows.value.count { it.goalId == goalId && it.status.name == "DONE" }
    }

    private fun subTask(id: String) = SubTask(
        id = id,
        lessonId = "lesson-1",
        type = "grammar",
        title = id,
        estimatedDuration = Duration.ofMinutes(30),
    )

    @Test
    fun `marking done keeps the sub-task where it was`() = runTest {
        val subTasks = FakeSubTaskDao()
        val repository = GoalRepository(FakeGoalDao(), FakeLessonDao(), subTasks)

        repository.saveSubTask(subTask("a"), orderIndex = 0)
        repository.saveSubTask(subTask("b"), orderIndex = 1)
        repository.saveSubTask(subTask("c"), orderIndex = 2)

        repository.setSubTaskDone(subTask("c"), done = true)

        val stored = subTasks.rows.value.associateBy { it.id }
        assertEquals(2, stored.getValue("c").orderIndex, "completion must not move it")
        assertEquals(ProgressStatus.DONE, stored.getValue("c").status)
        assertEquals(0, stored.getValue("a").orderIndex)
        assertEquals(1, stored.getValue("b").orderIndex)
    }

    @Test
    fun `un-ticking also keeps the position`() = runTest {
        val subTasks = FakeSubTaskDao()
        val repository = GoalRepository(FakeGoalDao(), FakeLessonDao(), subTasks)

        repository.saveSubTask(subTask("a"), orderIndex = 7)
        repository.setSubTaskDone(subTask("a"), done = true)
        repository.setSubTaskDone(subTask("a"), done = false)

        assertEquals(7, subTasks.rows.value.single().orderIndex)
    }

    @Test
    fun `an explicit position still wins`() = runTest {
        val subTasks = FakeSubTaskDao()
        val repository = GoalRepository(FakeGoalDao(), FakeLessonDao(), subTasks)

        repository.saveSubTask(subTask("a"), orderIndex = 3)
        repository.setSubTaskDone(subTask("a"), done = true, orderIndex = 9)

        assertEquals(9, subTasks.rows.value.single().orderIndex)
    }

    @Test
    fun `a sub-task the store has never seen lands at the front`() = runTest {
        val subTasks = FakeSubTaskDao()
        val repository = GoalRepository(FakeGoalDao(), FakeLessonDao(), subTasks)

        repository.setSubTaskDone(subTask("new"), done = true)

        assertEquals(0, subTasks.rows.value.single().orderIndex)
    }
}
