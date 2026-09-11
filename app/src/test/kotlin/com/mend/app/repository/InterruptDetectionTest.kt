package com.mend.app.repository

import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Goal
import com.mend.engine.model.Habit
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.SubTask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app-side half of the interrupt test: picking which stored work, if any,
 * is a genuine emergency right now.
 */
class InterruptDetectionTest {

    private val date = LocalDate.of(2026, 9, 16)
    private val now = date.atTime(8, 0)

    private val college = StaticBlock(
        id = "college",
        title = "College",
        windowStart = LocalTime.of(9, 0),
        windowEnd = LocalTime.of(17, 0),
    )

    private fun goal(
        id: String,
        deadlineIn: Long,
        oneShot: Boolean,
    ) = Goal(
        id = id,
        title = id,
        deadline = date.plusDays(deadlineIn),
        manualImportance = 4,
        consequenceType = ConsequenceType.IRL_BINDING,
        isOneShot = oneShot,
        totalLessons = 1,
    )

    private fun lesson(goalId: String, taskId: String, minutes: Long) = Lesson(
        id = "l-$goalId",
        goalId = goalId,
        orderIndex = 0,
        title = "l-$goalId",
        subTasks = listOf(
            SubTask(
                id = taskId,
                lessonId = "l-$goalId",
                type = "admin",
                title = taskId,
                estimatedDuration = Duration.ofMinutes(minutes),
            ),
        ),
    )

    private fun schedule(
        goals: List<GoalWithLessons>,
        recovery: List<ProtectedRecovery> = emptyList(),
    ) = ScheduleBuilder.buildDaySchedule(
        date = date,
        staticBlocks = listOf(college),
        habits = emptyList(),
        goals = goals,
        protectedRecovery = recovery,
    )

    private fun detect(
        goals: List<GoalWithLessons>,
        recovery: List<ProtectedRecovery> = emptyList(),
    ) = detectInterrupt(
        now = now,
        schedule = schedule(goals, recovery),
        goals = goals,
        staticBlocks = listOf(college),
        habits = emptyList(),
        protectedRecovery = recovery,
    )

    // --- future free time ----------------------------------------------------

    @Test
    fun `future free minutes accumulate across days`() {
        // College takes 8h, leaving 16h free per day over three days.
        val total = ScheduleBuilder.futureFreeMinutes(
            from = date,
            until = date.plusDays(2),
            staticBlocks = listOf(college),
            habits = emptyList(),
        )
        assertEquals(3 * 16 * 60L, total)
    }

    @Test
    fun `a deadline already past leaves no future free time`() {
        assertEquals(
            0,
            ScheduleBuilder.futureFreeMinutes(
                from = date,
                until = date.minusDays(1),
                staticBlocks = listOf(college),
                habits = emptyList(),
            ),
        )
    }

    @Test
    fun `protected sleep reduces the free time available before a deadline`() {
        val withoutSleep = ScheduleBuilder.futureFreeMinutes(
            date, date, listOf(college), emptyList(),
        )
        val withSleep = ScheduleBuilder.futureFreeMinutes(
            date,
            date,
            listOf(college),
            emptyList(),
            listOf(
                ProtectedRecovery(
                    id = "s",
                    title = "Sleep",
                    bedtime = LocalTime.of(22, 30),
                    wakeTime = LocalTime.of(6, 30),
                ),
            ),
        )
        assertTrue(withSleep < withoutSleep)
    }

    // --- detection -----------------------------------------------------------

    @Test
    fun `no goals means no interrupt`() {
        assertNull(detect(emptyList()))
    }

    @Test
    fun `a recurring goal never becomes an interrupt`() {
        val g = goal("recurring", deadlineIn = 0, oneShot = false)
        assertNull(detect(listOf(GoalWithLessons(g, listOf(lesson(g.id, "t", 30))))))
    }

    @Test
    fun `a one-shot due today becomes the mandatory next action`() {
        val g = goal("visa", deadlineIn = 0, oneShot = true)
        val plan = detect(listOf(GoalWithLessons(g, listOf(lesson(g.id, "form", 30)))))
        assertEquals("form", plan?.task?.id)
        assertTrue(plan!!.qualification.qualifies)
    }

    @Test
    fun `a one-shot with plenty of time ahead is left alone`() {
        val g = goal("visa", deadlineIn = 20, oneShot = true)
        assertNull(detect(listOf(GoalWithLessons(g, listOf(lesson(g.id, "form", 30))))))
    }

    @Test
    fun `an interrupt claims free time, never the college block`() {
        val g = goal("visa", deadlineIn = 0, oneShot = true)
        val plan = detect(listOf(GoalWithLessons(g, listOf(lesson(g.id, "form", 30)))))!!
        val collegeSlot = schedule(
            listOf(GoalWithLessons(g, listOf(lesson(g.id, "form", 30)))),
        ).freeTimeMap.placements.single().interval
        assertTrue(!plan.slot!!.overlaps(collegeSlot))
    }

    @Test
    fun `completed work cannot become an interrupt`() {
        val g = goal("visa", deadlineIn = 0, oneShot = true)
        val done = lesson(g.id, "form", 30).let { l ->
            l.copy(
                subTasks = l.subTasks.map {
                    it.copy(status = com.mend.engine.model.ProgressStatus.DONE)
                },
            )
        }
        assertNull(detect(listOf(GoalWithLessons(g, listOf(done)))))
    }

    @Test
    fun `only one interrupt is ever returned, and it is the most urgent`() {
        val soon = goal("soon", deadlineIn = 0, oneShot = true)
        val later = goal("later", deadlineIn = 1, oneShot = true)
        val plan = detect(
            listOf(
                GoalWithLessons(later, listOf(lesson(later.id, "later-task", 30))),
                GoalWithLessons(soon, listOf(lesson(soon.id, "soon-task", 30))),
            ),
        )
        assertEquals("soon-task", plan?.task?.id)
    }

    @Test
    fun `an overdue one-shot still qualifies`() {
        val g = goal("visa", deadlineIn = -2, oneShot = true)
        val plan = detect(listOf(GoalWithLessons(g, listOf(lesson(g.id, "form", 30)))))
        assertTrue(plan!!.qualification.reason.contains("2d ago"))
    }

    @Test
    fun `the plan explains why this became an emergency`() {
        val g = goal("visa", deadlineIn = 0, oneShot = true)
        val plan = detect(listOf(GoalWithLessons(g, listOf(lesson(g.id, "form", 30)))))!!
        assertTrue(plan.qualification.reason.isNotBlank())
        assertTrue(plan.message.contains("next thing you do"))
    }

    @Test
    fun `a bare habit day does not confuse detection`() {
        val g = goal("visa", deadlineIn = 0, oneShot = true)
        val goals = listOf(GoalWithLessons(g, listOf(lesson(g.id, "form", 30))))
        val plan = detectInterrupt(
            now = now,
            schedule = ScheduleBuilder.buildDaySchedule(
                date = date,
                staticBlocks = listOf(college),
                habits = listOf(Habit(id = "h", title = "Water", timeOfDay = LocalTime.of(8, 30))),
                goals = goals,
            ),
            goals = goals,
            staticBlocks = listOf(college),
            habits = listOf(Habit(id = "h", title = "Water", timeOfDay = LocalTime.of(8, 30))),
        )
        assertEquals("form", plan?.task?.id)
    }
}
