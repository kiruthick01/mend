package com.mend.app.data.mapper

import com.mend.app.data.entity.GoalEntity
import com.mend.app.data.entity.LessonEntity
import com.mend.app.data.entity.SubTaskEntity
import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.model.SubTask
import com.mend.engine.progress.isComplete
import java.time.Duration

/**
 * Translation between the goal tables and the engine's goal hierarchy.
 *
 * `Goal.lessonsCompleted` is deliberately not a stored column. It is a rollup,
 * and a stored rollup drifts from the rows it summarises the first time a
 * write is interrupted, so it is recomputed from the lessons on every read.
 */

fun GoalEntity.toDomain(lessonsCompleted: Int = 0): Goal = Goal(
    id = id,
    title = title,
    deadline = deadline,
    manualImportance = manualImportance,
    consequenceType = consequenceType,
    isOneShot = isOneShot,
    totalLessons = maxOf(totalLessons, lessonsCompleted),
    lessonsCompleted = lessonsCompleted,
    pileupRisk = pileupRisk,
    negotiatedDelayLimit = negotiatedDelayLimitMinutes?.let(Duration::ofMinutes),
)

fun Goal.toEntity(createdAt: Long = 0L): GoalEntity = GoalEntity(
    id = id,
    title = title,
    deadline = deadline,
    manualImportance = manualImportance,
    consequenceType = consequenceType,
    isOneShot = isOneShot,
    totalLessons = totalLessons,
    pileupRisk = pileupRisk,
    negotiatedDelayLimitMinutes = negotiatedDelayLimit?.toMinutes(),
    createdAt = createdAt,
)

fun LessonEntity.toDomain(subTasks: List<SubTask> = emptyList()): Lesson = Lesson(
    id = id,
    goalId = goalId,
    orderIndex = orderIndex,
    title = title,
    status = status,
    completedAt = completedAt,
    subTasks = subTasks,
)

fun Lesson.toEntity(): LessonEntity = LessonEntity(
    id = id,
    goalId = goalId,
    orderIndex = orderIndex,
    title = title,
    status = status,
    completedAt = completedAt,
)

fun SubTaskEntity.toDomain(): SubTask = SubTask(
    id = id,
    lessonId = lessonId,
    type = type,
    title = title,
    estimatedDuration = Duration.ofMinutes(estimatedDurationMinutes),
    paceComfortable = paceComfortableMinutes?.let(Duration::ofMinutes),
    flexibility = flexibility,
    status = status,
    completedAt = completedAt,
)

fun SubTask.toEntity(orderIndex: Int = 0): SubTaskEntity = SubTaskEntity(
    id = id,
    lessonId = lessonId,
    type = type,
    title = title,
    estimatedDurationMinutes = estimatedDuration.toMinutes(),
    paceComfortableMinutes = paceComfortable?.toMinutes(),
    flexibility = flexibility,
    status = status,
    completedAt = completedAt,
    orderIndex = orderIndex,
)

/**
 * Assembles the full hierarchy from three flat query results.
 *
 * Done in one pass with lookup maps rather than a query per lesson, so opening
 * a goal with thirty lessons is three queries, not thirty-one.
 */
fun assembleGoals(
    goals: List<GoalEntity>,
    lessons: List<LessonEntity>,
    subTasks: List<SubTaskEntity>,
): List<Pair<Goal, List<Lesson>>> {
    val subTasksByLesson = subTasks.groupBy { it.lessonId }
    val lessonsByGoal = lessons.groupBy { it.goalId }

    return goals.map { goalEntity ->
        val domainLessons = lessonsByGoal[goalEntity.id]
            .orEmpty()
            .sortedBy { it.orderIndex }
            .map { lessonEntity ->
                lessonEntity.toDomain(
                    subTasks = subTasksByLesson[lessonEntity.id]
                        .orEmpty()
                        .sortedBy { it.orderIndex }
                        .map { it.toDomain() },
                )
            }
        goalEntity.toDomain(
            lessonsCompleted = domainLessons.count { it.isComplete() },
        ) to domainLessons
    }
}
