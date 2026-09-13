package com.mend.app.repository

import com.mend.app.MendApplication
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first

/**
 * Ticking something off from outside the app.
 *
 * The widget and the notification both offer a done button, and both know only
 * a task id — the sub-task or lesson behind it has to be found again. Doing
 * that here rather than in each surface means completion is recorded exactly
 * one way no matter where the tap came from, including the adherence row, which
 * is the part that quietly matters: a completion that skips the log would make
 * the app's own picture of how well you keep to the schedule wrong.
 */
object TaskCompletion {

    /**
     * Marks the work behind [taskId] done.
     *
     * Returns false when there was nothing pending to complete.
     */
    suspend fun complete(
        app: MendApplication,
        taskId: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val goals = app.goalRepository
        val lessons = goals.observeGoals().first().flatMap { it.lessons }

        return when (val pending = pendingWork(lessons, taskId)) {
            null -> false

            is PendingWork.Sub -> {
                goals.setSubTaskDone(
                    subTask = pending.subTask,
                    done = true,
                    now = now,
                    assignedSlot = NowNext.slotFor(app, taskId, now),
                )
                true
            }

            is PendingWork.Bare -> {
                goals.setLessonDone(
                    lesson = pending.lesson,
                    done = true,
                    now = now,
                    assignedSlot = NowNext.slotFor(app, taskId, now),
                )
                true
            }
        }
    }
}

/** What a done button can be pointing at. */
sealed interface PendingWork {
    data class Sub(val subTask: SubTask) : PendingWork

    /** A lesson with no sub-tasks, which is scheduled as itself. */
    data class Bare(val lesson: Lesson) : PendingWork
}

/**
 * The still-unfinished work behind a task id, or null when there is none.
 *
 * Returning null for work that is already done is the important case, and it
 * happens legitimately and often: a widget can sit on the home screen all day
 * offering a button for work that has since been completed, rescheduled or
 * deleted, and the redraw after a tap is not instant, so the same button gets
 * pressed twice. The adherence log is append-only, so completing something
 * twice would write two rows and quietly overstate how much was actually done.
 *
 * Separated from [TaskCompletion.complete] so the rule can be tested without a
 * database behind it.
 */
fun pendingWork(lessons: List<Lesson>, taskId: String): PendingWork? {
    val subTask = lessons.flatMap { it.subTasks }.firstOrNull { it.id == taskId }
    if (subTask != null) {
        return PendingWork.Sub(subTask).takeIf { subTask.status != ProgressStatus.DONE }
    }

    val lesson = lessons.firstOrNull { it.id == taskId && it.hasNoSubTasks }
    if (lesson != null) {
        return PendingWork.Bare(lesson).takeIf { lesson.status != ProgressStatus.DONE }
    }

    return null
}
