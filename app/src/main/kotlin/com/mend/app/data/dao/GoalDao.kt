package com.mend.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mend.app.data.entity.GoalEntity
import com.mend.app.data.entity.LessonEntity
import com.mend.app.data.entity.SubTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {

    @Query("SELECT * FROM goals ORDER BY deadline, title")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun findById(id: String): GoalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: GoalEntity)

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)
}

@Dao
interface LessonDao {

    @Query("SELECT * FROM lessons WHERE goal_id = :goalId ORDER BY order_index")
    fun observeForGoal(goalId: String): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lessons ORDER BY goal_id, order_index")
    fun observeAll(): Flow<List<LessonEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lesson: LessonEntity)

    @Update
    suspend fun update(lesson: LessonEntity)

    @Delete
    suspend fun delete(lesson: LessonEntity)

    @Query("SELECT COUNT(*) FROM lessons WHERE goal_id = :goalId AND status = 'DONE'")
    suspend fun countCompleted(goalId: String): Int
}

@Dao
interface SubTaskDao {

    @Query("SELECT * FROM sub_tasks WHERE lesson_id = :lessonId ORDER BY order_index")
    fun observeForLesson(lessonId: String): Flow<List<SubTaskEntity>>

    @Query("SELECT * FROM sub_tasks ORDER BY lesson_id, order_index")
    fun observeAll(): Flow<List<SubTaskEntity>>

    @Transaction
    @Query("SELECT * FROM sub_tasks WHERE lesson_id IN (:lessonIds) ORDER BY order_index")
    suspend fun forLessons(lessonIds: List<String>): List<SubTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subTask: SubTaskEntity)

    @Update
    suspend fun update(subTask: SubTaskEntity)

    @Delete
    suspend fun delete(subTask: SubTaskEntity)
}
