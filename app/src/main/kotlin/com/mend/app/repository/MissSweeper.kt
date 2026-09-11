package com.mend.app.repository

import com.mend.engine.adherence.datesToSweep
import com.mend.engine.adherence.detectElapsedMisses
import com.mend.engine.model.Habit
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.StaticBlock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Catching up on days the app was never opened.
 *
 * The live sweep in `TodayViewModel` only covers today, so a weekend away used
 * to leave no trace at all — which quietly flatters every statistic built on
 * the adherence stream. This closes that.
 *
 * There is no stored schedule to consult for a past day, and there does not
 * need to be: a schedule is a pure function of its inputs, so a past day can be
 * recomputed. Two guards keep that honest:
 *
 *  - **Work already done drops out on its own.** Completed sub-tasks stop being
 *    pending, so they are not in the recomputed day and cannot be reported as
 *    missed.
 *  - **Goals that did not exist yet are excluded**, by creation date. Without
 *    that, adding a goal today would back-date a fortnight of failures for work
 *    nobody had committed to.
 */
object MissSweeper {

    /** One recomputed miss, ready to be written to the stream. */
    data class Recovered(
        val date: LocalDate,
        val taskId: String,
        val taskTitle: String,
        val slot: com.mend.engine.time.TimeInterval,
    )

    /**
     * Recomputes every day since [lastSwept] and reports what went unrecorded.
     *
     * [alreadyLoggedOn] is asked per date so an entry is never written twice;
     * the stream is append-only, so a duplicate could not be tidied away later.
     */
    suspend fun sweep(
        lastSwept: LocalDate?,
        now: LocalDateTime,
        staticBlocks: List<StaticBlock>,
        habits: List<Habit>,
        protectedRecovery: List<ProtectedRecovery>,
        goals: List<GoalWithLessons>,
        alreadyLoggedOn: suspend (LocalDate) -> Set<String>,
    ): List<Recovered> {
        val today = now.toLocalDate()
        val recovered = mutableListOf<Recovered>()

        for (date in datesToSweep(lastSwept, today)) {
            val existing = goals.filter { it.existedOn(date) }
            if (existing.isEmpty()) continue

            val schedule = ScheduleBuilder.buildDaySchedule(
                date = date,
                staticBlocks = staticBlocks,
                habits = habits,
                goals = existing,
                protectedRecovery = protectedRecovery,
            )

            // The whole day is over, so judge it at its own end rather than at
            // `now` — otherwise a slot late on a past day would be compared
            // against this morning and look as though it had not happened yet.
            val endOfThatDay = date.plusDays(1).atStartOfDay()

            detectElapsedMisses(
                schedule = schedule,
                now = endOfThatDay,
                alreadyLogged = alreadyLoggedOn(date),
            ).forEach { miss ->
                recovered += Recovered(
                    date = date,
                    taskId = miss.taskId,
                    taskTitle = miss.placement.task.title,
                    slot = miss.slot,
                )
            }
        }

        return recovered
    }
}

/** True when this goal already existed on [date]. */
private fun GoalWithLessons.existedOn(date: LocalDate): Boolean =
    createdOn == null || !createdOn.isAfter(date)
