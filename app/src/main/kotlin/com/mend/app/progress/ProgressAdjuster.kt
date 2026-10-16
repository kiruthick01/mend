package com.mend.app.progress

import com.mend.app.repository.GoalWithLessons
import com.mend.app.repository.PendingWork
import com.mend.app.repository.SchedulingAdjuster
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow

/**
 * Lets what the linked apps report shape the schedule, without touching stored
 * goals. Two nudges, both on the next pending item of a linked goal:
 *
 *  - **Size.** Anki's due count (or, failing that, the learned median of real
 *    time spent) replaces the fixed estimate, so the allocator reserves the
 *    time the work will actually take.
 *  - **Urgency.** From [ProgressInsights.GUARD_TIME], if a run of practice is
 *    about to break, the goal's importance is raised by one (capped at 5) so
 *    the ordinary ranking pulls it forward. No separate rule: it is the same
 *    score with a different input.
 */
class ProgressAdjuster(private val store: ProgressLinkStore) : SchedulingAdjuster {

    override val changes: Flow<*> get() = store.revision

    override fun adjust(goals: List<GoalWithLessons>, now: LocalDateTime): List<GoalWithLessons> {
        val links = store.all()
        if (links.isEmpty()) return goals
        val today = now.toLocalDate()
        val afterGuard = !now.toLocalTime().isBefore(ProgressInsights.GUARD_TIME)

        return goals.map { entry ->
            val goalLinks = links.filter { it.goalId == entry.goal.id }
            if (goalLinks.isEmpty()) return@map entry

            var duration: java.time.Duration? = null
            var boost = false
            for (link in goalLinks) {
                val records = store.records(link.key)
                val todayRecord = records.firstOrNull { it.date == today }
                if (todayRecord?.practiced == true) continue

                if (duration == null) {
                    duration = if (link.kind == ProgressKind.ANKI) {
                        todayRecord?.due?.takeIf { it > 0 }?.let(ProgressInsights::ankiReviewDuration)
                    } else {
                        null
                    } ?: ProgressInsights.typicalDuration(records)
                }
                if (afterGuard && ProgressInsights.streakAtRisk(records, today)) boost = true
            }

            val target = (nextPendingWork(entry.lessons) as? PendingWork.Sub)?.subTask?.id
            val lessons = if (duration != null && target != null) {
                entry.lessons.map { lesson ->
                    lesson.copy(
                        subTasks = lesson.subTasks.map {
                            if (it.id == target) it.copy(estimatedDuration = duration) else it
                        },
                    )
                }
            } else {
                entry.lessons
            }
            val goal = if (boost) {
                entry.goal.copy(manualImportance = minOf(5, entry.goal.manualImportance + 1))
            } else {
                entry.goal
            }
            entry.copy(goal = goal, lessons = lessons)
        }
    }
}
