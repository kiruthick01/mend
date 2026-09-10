package com.mend.engine.progress

import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import java.time.Duration
import java.time.LocalDate

/**
 * Progress rollup: sub-task to lesson to goal.
 *
 * All of it is derived. Nothing here reads a stored "percent complete"
 * counter, because a counter and the rows it summarises drift apart the first
 * time a write is interrupted. The rows are the truth; these functions are the
 * summary.
 */

/**
 * The completion state a lesson actually has, given its sub-tasks.
 *
 * Two shapes, per the plan:
 *  - A lesson **with** sub-tasks is complete when every sub-task is done, and
 *    in progress as soon as any of them is. Its own stored status is ignored,
 *    so ticking the last sub-task cannot leave the lesson stale.
 *  - A lesson with **no** sub-tasks is completed by marking the lesson itself
 *    done, so its stored status is the answer.
 */
fun Lesson.derivedStatus(): ProgressStatus = when {
    hasNoSubTasks -> status
    subTasks.all { it.status == ProgressStatus.DONE } -> ProgressStatus.DONE
    subTasks.any { it.status != ProgressStatus.NOT_STARTED } -> ProgressStatus.IN_PROGRESS
    else -> ProgressStatus.NOT_STARTED
}

fun Lesson.isComplete(): Boolean = derivedStatus() == ProgressStatus.DONE

/** 0.0-1.0 within a single lesson. A bare lesson is all-or-nothing. */
fun Lesson.completionFraction(): Double = when {
    hasNoSubTasks -> if (status == ProgressStatus.DONE) 1.0 else 0.0
    else -> subTasks.count { it.status == ProgressStatus.DONE }.toDouble() / subTasks.size
}

/** Goal-level rollup, per PROJECT_PLAN.md: `lessons_completed / total_lessons`. */
data class GoalProgress(
    val goalId: String,
    val lessonsCompleted: Int,
    val totalLessons: Int,
    val subTasksCompleted: Int,
    val totalSubTasks: Int,
    val daysRemaining: Long,
) {
    /**
     * Fraction of lessons done. Guards against a goal whose declared
     * `totalLessons` has fallen behind the lessons actually entered.
     */
    val fraction: Double
        get() = if (totalLessons <= 0) 0.0 else {
            (lessonsCompleted.toDouble() / totalLessons).coerceIn(0.0, 1.0)
        }

    val isComplete: Boolean get() = totalLessons > 0 && lessonsCompleted >= totalLessons

    val isOverdue: Boolean get() = daysRemaining < 0 && !isComplete
}

/**
 * Rolls [lessons] up into a single view of [goal].
 *
 * `totalLessons` is the larger of the goal's declared count and the number of
 * lessons actually entered — declaring ten and entering twelve should not
 * report 120% done.
 */
fun computeGoalProgress(
    goal: Goal,
    lessons: List<Lesson>,
    today: LocalDate,
): GoalProgress {
    val completed = lessons.count { it.isComplete() }
    val allSubTasks = lessons.flatMap { it.subTasks }
    return GoalProgress(
        goalId = goal.id,
        lessonsCompleted = completed,
        totalLessons = maxOf(goal.totalLessons, lessons.size),
        subTasksCompleted = allSubTasks.count { it.status == ProgressStatus.DONE },
        totalSubTasks = allSubTasks.size,
        daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(today, goal.deadline),
    )
}

/**
 * Declared pace against observed velocity, for one kind of sub-task.
 *
 * Both sides are expressed in **minutes of that work per day**, so they are
 * directly comparable rather than two numbers in different units sitting next
 * to each other.
 */
data class PaceSnapshot(
    /** The user's own label for this kind of work: "reading", "past papers". */
    val subTaskType: String,
    /** What the user said they can sustain per day, if they said anything. */
    val comfortableMinutesPerDay: Long?,
    /** What they have actually averaged per day over the observed window. */
    val actualMinutesPerDay: Double,
    val completedCount: Int,
    val totalCount: Int,
    /** Days spanned by the observations, always at least 1. */
    val observationDays: Long,
) {
    /** Null when nothing was declared to compare against. */
    val ratio: Double?
        get() = comfortableMinutesPerDay
            ?.takeIf { it > 0 }
            ?.let { actualMinutesPerDay / it }

    val isBehindComfortablePace: Boolean
        get() = ratio?.let { it < 1.0 } ?: false

    val remainingCount: Int get() = totalCount - completedCount
}

/**
 * Compares declared comfortable pace against actual velocity, grouped by
 * sub-task type.
 *
 * Actual velocity is the estimated minutes of completed work of that type,
 * spread over the days from the earliest completion to [today]. It is
 * deliberately crude: a sustained-average reading, not a forecast. The
 * deadline projection that decides whether you are going to miss something
 * belongs to Phase 5, and is a different calculation on richer data.
 *
 * Lessons with no sub-tasks contribute nothing here — there is no sub-task
 * type to attribute their work to.
 */
fun computePace(
    lessons: List<Lesson>,
    today: LocalDate,
): List<PaceSnapshot> =
    lessons
        .flatMap { it.subTasks }
        .groupBy { it.type }
        .map { (type, subTasks) -> snapshotFor(type, subTasks, today) }
        .sortedBy { it.subTaskType.lowercase() }

private fun snapshotFor(
    type: String,
    subTasks: List<SubTask>,
    today: LocalDate,
): PaceSnapshot {
    val done = subTasks.filter { it.status == ProgressStatus.DONE }
    val completionDates = done.mapNotNull { it.completedAt?.toLocalDate() }

    // Span the observation window from the first recorded completion to today,
    // inclusive, so a single completion reads as one day of work rather than
    // dividing by zero.
    val observationDays = completionDates.minOrNull()
        ?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) + 1 }
        ?.coerceAtLeast(1)
        ?: 1

    // Only work with a recorded completion date counts towards velocity;
    // something marked done before the app started tracking would otherwise
    // land entirely on day one and invent a burst that never happened.
    val datedMinutes = done
        .filter { it.completedAt != null }
        .sumOf { it.estimatedDuration.toMinutes() }

    val declared = subTasks
        .mapNotNull { it.paceComfortable?.toMinutes() }
        .takeIf { it.isNotEmpty() }
        ?.let { it.sum() / it.size }

    return PaceSnapshot(
        subTaskType = type,
        comfortableMinutesPerDay = declared,
        actualMinutesPerDay = datedMinutes.toDouble() / observationDays,
        completedCount = done.size,
        totalCount = subTasks.size,
        observationDays = observationDays,
    )
}

/** Total estimated work left across [lessons], ignoring anything already done. */
fun remainingEffort(lessons: List<Lesson>): Duration =
    lessons
        .flatMap { it.subTasks }
        .filter { it.status != ProgressStatus.DONE }
        .fold(Duration.ZERO) { acc, subTask -> acc.plus(subTask.estimatedDuration) }
