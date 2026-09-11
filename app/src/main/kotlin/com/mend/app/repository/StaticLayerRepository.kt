package com.mend.app.repository

import com.mend.app.data.dao.TaskDao
import com.mend.app.data.mapper.toEntity
import com.mend.app.data.mapper.toHabits
import com.mend.app.data.mapper.toStaticBlocks
import com.mend.engine.model.Habit
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.TaskType
import com.mend.engine.reshuffle.ScheduleInvalidatingEvent
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The static layer: the fixed shape of a day.
 *
 * Everything this repository hands out is already an engine domain type, so
 * the ViewModel and the UI never see a nullable persistence column and never
 * have to know which columns belong to which task type.
 */
class StaticLayerRepository(
    private val taskDao: TaskDao,
    private val reshuffle: ReshuffleRepository? = null,
) {

    fun observeStaticBlocks(): Flow<List<StaticBlock>> =
        taskDao.observeByType(TaskType.STATIC_BLOCK).map { it.toStaticBlocks() }

    fun observeHabits(): Flow<List<Habit>> =
        taskDao.observeByType(TaskType.HABIT).map { it.toHabits() }

    suspend fun save(block: StaticBlock) {
        val existing = taskDao.findById(block.id) != null
        taskDao.upsert(block.toEntity(createdAt = System.currentTimeMillis()))
        recordEdit(
            if (existing) "${block.title} was changed" else "${block.title} was added",
        )
    }

    suspend fun save(habit: Habit) {
        val existing = taskDao.findById(habit.id) != null
        taskDao.upsert(habit.toEntity(createdAt = System.currentTimeMillis()))
        recordEdit(
            if (existing) "${habit.title} was changed" else "${habit.title} was added",
        )
    }

    suspend fun delete(id: String) {
        val title = taskDao.findById(id)?.title
        taskDao.deleteById(id)
        title?.let { recordEdit("$it was removed") }
    }

    private suspend fun recordEdit(summary: String) {
        reshuffle?.record(
            ScheduleInvalidatingEvent.ManualEdit(
                occurredAt = LocalDateTime.now(),
                summary = summary,
            ),
        )
    }
}
