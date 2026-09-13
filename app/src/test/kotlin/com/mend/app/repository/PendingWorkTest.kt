package com.mend.app.repository

import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the done button on the widget and the notification is allowed to act on.
 *
 * The rule that matters is the refusal: both surfaces sit outside the app for
 * hours, both can be tapped twice before they redraw, and the adherence log is
 * append-only. Completing the same work twice would write two rows and quietly
 * overstate how much was done — which then feeds adherence pressure, which then
 * feeds the schedule. A double tap must be a no-op, not a second completion.
 */
class PendingWorkTest {

    private fun sub(id: String, status: ProgressStatus = ProgressStatus.NOT_STARTED) = SubTask(
        id = id,
        lessonId = "lesson-1",
        type = "grammar",
        title = id,
        estimatedDuration = Duration.ofMinutes(30),
        status = status,
    )

    private fun lesson(
        id: String,
        status: ProgressStatus = ProgressStatus.NOT_STARTED,
        subTasks: List<SubTask> = emptyList(),
    ) = Lesson(
        id = id,
        goalId = "goal-1",
        orderIndex = 0,
        title = id,
        status = status,
        subTasks = subTasks,
    )

    @Test
    fun `pending sub-task is what gets completed`() {
        val lessons = listOf(lesson("lesson-1", subTasks = listOf(sub("a"))))

        val found = pendingWork(lessons, "a")

        assertTrue(found is PendingWork.Sub)
        assertEquals("a", found.subTask.id)
    }

    @Test
    fun `a sub-task already done is refused, so a second tap does nothing`() {
        val lessons = listOf(
            lesson("lesson-1", subTasks = listOf(sub("a", ProgressStatus.DONE))),
        )

        assertNull(pendingWork(lessons, "a"))
    }

    @Test
    fun `a lesson with no sub-tasks is completable as itself`() {
        val found = pendingWork(listOf(lesson("bare")), "bare")

        assertTrue(found is PendingWork.Bare)
        assertEquals("bare", found.lesson.id)
    }

    @Test
    fun `a bare lesson already done is refused too`() {
        assertNull(pendingWork(listOf(lesson("bare", ProgressStatus.DONE)), "bare"))
    }

    @Test
    fun `a lesson that has sub-tasks is not completable as itself`() {
        // Its completion is derived from its sub-tasks, so a button pointing at
        // the lesson would be claiming something the rollup does not agree with.
        val lessons = listOf(lesson("parent", subTasks = listOf(sub("a"))))

        assertNull(pendingWork(lessons, "parent"))
    }

    @Test
    fun `an id that matches nothing is refused rather than guessed at`() {
        val lessons = listOf(lesson("lesson-1", subTasks = listOf(sub("a"))))

        assertNull(pendingWork(lessons, "deleted-while-the-widget-was-on-screen"))
    }
}
