package com.mend.app.repository

import com.mend.app.data.dao.TaskDao
import com.mend.app.data.mapper.toEntity
import com.mend.app.data.mapper.toHabits
import com.mend.app.data.mapper.toStaticBlocks
import com.mend.engine.model.Habit
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.TaskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The static layer: the fixed shape of a day.
 *
 * Everything this repository hands out is already an engine domain type, so
 * the ViewModel and the UI never see a nullable persistence column and never
 * have to know which columns belong to which task type.
 */
class StaticLayerRepository(private val taskDao: TaskDao) {

    fun observeStaticBlocks(): Flow<List<StaticBlock>> =
        taskDao.observeByType(TaskType.STATIC_BLOCK).map { it.toStaticBlocks() }

    fun observeHabits(): Flow<List<Habit>> =
        taskDao.observeByType(TaskType.HABIT).map { it.toHabits() }

    suspend fun save(block: StaticBlock) =
        taskDao.upsert(block.toEntity(createdAt = System.currentTimeMillis()))

    suspend fun save(habit: Habit) =
        taskDao.upsert(habit.toEntity(createdAt = System.currentTimeMillis()))

    suspend fun delete(id: String) = taskDao.deleteById(id)
}
