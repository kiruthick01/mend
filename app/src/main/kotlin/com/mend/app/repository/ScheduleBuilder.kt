package com.mend.app.repository

import com.mend.engine.allocation.DaySchedule
import com.mend.engine.allocation.allocate
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.model.Goal
import com.mend.engine.model.GoalTask
import com.mend.engine.model.Habit
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.ProtectedRecovery
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
        protectedRecovery: List<ProtectedRecovery> = emptyList(),
        weights: Weights = Weights.DEFAULT,
    ): DaySchedule = allocate(
        freeTimeMap = computeFreeTimeMap(date, staticBlocks, habits, protectedRecovery),
        tasks = goals.flatMap { pendingTasks(it.goal, it.lessons) },
        weights = weights,
        today = date,
    )

    /**
     * Free minutes between [from] and [until] inclusive.
     *
     * This is the "is there a later slot?" half of the interrupt test. It is
     * deliberately the *whole* free map rather than what the allocator left
     * over: the question is whether the day could accommodate this work at
     * all, not whether it currently intends to.
     */
    fun futureFreeMinutes(
        from: LocalDate,
        until: LocalDate,
        staticBlocks: List<StaticBlock>,
        habits: List<Habit>,
        protectedRecovery: List<ProtectedRecovery> = emptyList(),
    ): Long {
        if (until.isBefore(from)) return 0
        var date = from
        var total = 0L
        while (!date.isAfter(until)) {
            total += computeFreeTimeMap(
                date,
                staticBlocks,
                habits,
                protectedRecovery,
            ).totalFreeMinutes
            date = date.plusDays(1)
        }
        return total
    }

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

/**
 * Finds the one piece of work, if any, that is a genuine emergency right now.
 *
 * Deliberately returns at most one. Two simultaneous "mandatory next actions"
 * is a contradiction, and if the category ever routinely produced several it
 * would mean the qualification test had stopped being narrow enough to mean
 * anything. The most overdue wins.
 */
fun detectInterrupt(
    now: java.time.LocalDateTime,
    schedule: DaySchedule,
    goals: List<GoalWithLessons>,
    staticBlocks: List<StaticBlock>,
    habits: List<Habit>,
    protectedRecovery: List<ProtectedRecovery> = emptyList(),
): com.mend.engine.interrupt.InterruptPlan? {
    val today = now.toLocalDate()

    val candidates = goals
        .filter { it.goal.isOneShot }
        .flatMap { entry ->
            ScheduleBuilder.pendingTasks(entry.goal, entry.lessons).map { entry.goal to it }
        }

    val qualified = candidates.mapNotNull { (goal, task) ->
        val freeBefore = ScheduleBuilder.futureFreeMinutes(
            from = today.plusDays(1),
            until = task.deadline,
            staticBlocks = staticBlocks,
            habits = habits,
            protectedRecovery = protectedRecovery,
        )
        val qualification = com.mend.engine.interrupt.qualifyAsInterrupt(
            task = task,
            isOneShot = goal.isOneShot,
            today = today,
            futureFreeMinutesBeforeDeadline = freeBefore,
        )
        if (qualification.qualifies) task to qualification else null
    }

    val (task, qualification) = qualified.minByOrNull { it.first.deadline } ?: return null

    return com.mend.engine.interrupt.planInterrupt(now, schedule, task, qualification)
}
