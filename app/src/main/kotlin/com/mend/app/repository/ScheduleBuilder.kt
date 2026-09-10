package com.mend.app.repository

import com.mend.engine.allocation.DaySchedule
import com.mend.engine.allocation.allocate
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.model.Goal
import com.mend.engine.model.GoalTask
import com.mend.engine.model.Habit
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.Weights
import com.mend.engine.progress.isComplete
import java.time.Duration
import java.time.LocalDate

/**
 * Turns what is stored into what the engine can schedule.
 *
 * This is a thin seam and deliberately so: it selects the pending work and
 * hands it, with the free time map, to [allocate]. Every actual decision —
 * ranking, placement, refusal — happens inside the engine where it is unit
 * tested.
 */
object ScheduleBuilder {

    /** Default estimate for a bare lesson, which carries none of its own. */
    val BARE_LESSON_ESTIMATE: Duration = Duration.ofMinutes(60)

    fun buildDaySchedule(
        date: LocalDate,
        staticBlocks: List<StaticBlock>,
        habits: List<Habit>,
        goals: List<GoalWithLessons>,
        weights: Weights = Weights.DEFAULT,
    ): DaySchedule = allocate(
        freeTimeMap = computeFreeTimeMap(date, staticBlocks, habits),
        tasks = goals.flatMap { pendingTasks(it.goal, it.lessons) },
        weights = weights,
        today = date,
    )

    /**
     * The work still outstanding under [goal].
     *
     * A lesson with sub-tasks contributes each unfinished sub-task. A lesson
     * with none contributes itself, since ticking the lesson is how it is
     * completed. Finished work contributes nothing.
     */
    fun pendingTasks(goal: Goal, lessons: List<Lesson>): List<GoalTask> =
        lessons.flatMap { lesson ->
            when {
                lesson.isComplete() -> emptyList()

                lesson.hasNoSubTasks -> listOf(
                    GoalTask.fromBareLesson(goal, lesson, BARE_LESSON_ESTIMATE),
                )

                else -> lesson.subTasks
                    .filter { it.status != ProgressStatus.DONE }
                    .map { GoalTask.fromSubTask(goal, it) }
            }
        }
}
