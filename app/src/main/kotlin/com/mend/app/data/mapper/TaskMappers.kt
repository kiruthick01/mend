package com.mend.app.data.mapper

import com.mend.app.data.entity.TaskEntity
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Habit
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.TaskType
import com.mend.engine.model.WindowAnchor
import java.time.Duration

/**
 * Translation between the flat `tasks` table and the engine's sealed domain
 * types.
 *
 * Everything above this boundary works with [StaticBlock] and [Habit], which
 * cannot express a priority or a deadline they should not have. Everything
 * below it works with nullable columns, because SQLite has no sum types. All
 * the nullable-column handling is confined here.
 */

fun StaticBlock.toEntity(createdAt: Long = 0L): TaskEntity = TaskEntity(
    id = id,
    title = title,
    type = TaskType.STATIC_BLOCK,
    // Static blocks are constraints on the day, never work with a consequence.
    consequenceType = ConsequenceType.NONE,
    daysOfWeek = daysOfWeek,
    windowStart = windowStart,
    windowEnd = windowEnd,
    requiredDurationMinutes = requiredDuration?.toMinutes(),
    windowAnchor = anchor,
    createdAt = createdAt,
)

fun Habit.toEntity(createdAt: Long = 0L): TaskEntity = TaskEntity(
    id = id,
    title = title,
    type = TaskType.HABIT,
    // Habits have no consequence model at all, by design.
    consequenceType = ConsequenceType.NONE,
    daysOfWeek = daysOfWeek,
    timeOfDay = timeOfDay,
    durationMinutes = duration.toMinutes(),
    createdAt = createdAt,
)

/**
 * Reads a row back as a [StaticBlock], or null if the row is not one or is
 * missing the columns a static block requires.
 */
fun TaskEntity.toStaticBlockOrNull(): StaticBlock? {
    if (type != TaskType.STATIC_BLOCK) return null
    val start = windowStart ?: return null
    val end = windowEnd ?: return null
    if (end <= start) return null
    return StaticBlock(
        id = id,
        title = title,
        daysOfWeek = daysOfWeek,
        windowStart = start,
        windowEnd = end,
        requiredDuration = requiredDurationMinutes?.let(Duration::ofMinutes),
        anchor = windowAnchor ?: WindowAnchor.EARLIEST,
    )
}

/**
 * Reads a row back as a [Habit], or null if the row is not one or is missing
 * the columns a habit requires.
 */
fun TaskEntity.toHabitOrNull(): Habit? {
    if (type != TaskType.HABIT) return null
    val time = timeOfDay ?: return null
    val minutes = durationMinutes ?: return null
    if (minutes <= 0) return null
    return Habit(
        id = id,
        title = title,
        daysOfWeek = daysOfWeek,
        timeOfDay = time,
        duration = Duration.ofMinutes(minutes),
    )
}

fun List<TaskEntity>.toStaticBlocks(): List<StaticBlock> = mapNotNull { it.toStaticBlockOrNull() }

fun List<TaskEntity>.toHabits(): List<Habit> = mapNotNull { it.toHabitOrNull() }
