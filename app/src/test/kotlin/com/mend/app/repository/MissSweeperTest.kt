package com.mend.app.repository

import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Flexibility
import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.SubTask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Back-filling days the app was never opened.
 *
 * The risk here is not missing a miss — it is inventing one. A sweep that
 * blames you for work you had not created yet, or for work you actually
 * finished, would poison the adherence stream in the direction that is hardest
 * to notice and impossible to correct, since the stream is append-only.
 */
class MissSweeperTest {

    private val today = LocalDate.of(2026, 9, 16)
    private val now = today.atTime(9, 0)

    private val college = StaticBlock(
        id = "college",
        title = "College",
        windowStart = LocalTime.of(9, 0),
        windowEnd = LocalTime.of(16, 0),
    )

    private fun goal(id: String = "g1", createdOn: LocalDate? = null) = GoalWithLessons(
        goal = Goal(
            id = id,
            title = id,
            deadline = today.plusDays(10),
            manualImportance = 4,
            consequenceType = ConsequenceType.IRL_BINDING,
            totalLessons = 1,
        ),
        lessons = listOf(
            Lesson(
                id = "l-$id",
                goalId = id,
                orderIndex = 0,
                title = "Lesson",
                subTasks = listOf(
                    SubTask(
                        id = "t-$id",
                        lessonId = "l-$id",
                        type = "revision",
                        title = "Revision $id",
                        estimatedDuration = Duration.ofMinutes(60),
                        flexibility = Flexibility.MOVABLE,
                    ),
                ),
            ),
        ),
        createdOn = createdOn,
    )

    private suspend fun sweep(
        lastSwept: LocalDate?,
        goals: List<GoalWithLessons>,
        logged: Map<LocalDate, Set<String>> = emptyMap(),
    ) = MissSweeper.sweep(
        lastSwept = lastSwept,
        now = now,
        staticBlocks = listOf(college),
        habits = emptyList(),
        protectedRecovery = emptyList(),
        goals = goals,
        alreadyLoggedOn = { logged[it].orEmpty() },
    )

    @Test
    fun `days since the last sweep are recovered`() = runTest {
        val recovered = sweep(today.minusDays(3), listOf(goal()))
        assertEquals(
            listOf(today.minusDays(2), today.minusDays(1)),
            recovered.map { it.date },
        )
        assertTrue(recovered.all { it.taskId == "t-g1" })
    }

    @Test
    fun `today is left to the live sweep`() = runTest {
        val recovered = sweep(today.minusDays(2), listOf(goal()))
        assertTrue(recovered.none { it.date == today })
    }

    @Test
    fun `nothing is recovered when there is nothing to sweep`() = runTest {
        assertEquals(emptyList(), sweep(today.minusDays(1), listOf(goal())))
    }

    @Test
    fun `a goal that did not exist yet is never blamed`() = runTest {
        // Created yesterday, so the day before that is not its fault.
        val recovered = sweep(today.minusDays(4), listOf(goal(createdOn = today.minusDays(1))))
        assertEquals(listOf(today.minusDays(1)), recovered.map { it.date })
    }

    @Test
    fun `a goal created today produces no back-dated misses at all`() = runTest {
        val recovered = sweep(today.minusDays(5), listOf(goal(createdOn = today)))
        assertEquals(emptyList(), recovered)
    }

    @Test
    fun `a goal with no recorded creation date is assumed to have existed`() = runTest {
        val recovered = sweep(today.minusDays(2), listOf(goal(createdOn = null)))
        assertEquals(listOf(today.minusDays(1)), recovered.map { it.date })
    }

    @Test
    fun `completed work is never reported as missed`() = runTest {
        val done = goal().let { g ->
            g.copy(
                lessons = g.lessons.map { lesson ->
                    lesson.copy(
                        subTasks = lesson.subTasks.map { it.copy(status = ProgressStatus.DONE) },
                    )
                },
            )
        }
        assertEquals(emptyList(), sweep(today.minusDays(3), listOf(done)))
    }

    @Test
    fun `work already logged on a day is not logged again`() = runTest {
        val recovered = sweep(
            lastSwept = today.minusDays(3),
            goals = listOf(goal()),
            logged = mapOf(today.minusDays(2) to setOf("t-g1")),
        )
        assertEquals(listOf(today.minusDays(1)), recovered.map { it.date })
    }

    @Test
    fun `a past day is judged at its own end, not at this morning`() = runTest {
        // The task lands at 16:00, well after `now` (09:00). Judged against the
        // current clock it would look as though it had not happened yet; judged
        // against the end of its own day it is plainly missed.
        val recovered = sweep(today.minusDays(2), listOf(goal()))
        assertTrue(recovered.isNotEmpty())
        assertTrue(recovered.all { it.slot.end.toLocalDate() < today })
    }

    @Test
    fun `no goals means nothing to recover`() = runTest {
        assertEquals(emptyList(), sweep(today.minusDays(5), emptyList()))
    }

    @Test
    fun `a long absence is capped rather than back-filling forever`() = runTest {
        val recovered = sweep(today.minusDays(365), listOf(goal()))
        assertEquals(14, recovered.map { it.date }.distinct().size)
    }

    @Test
    fun `every recovered miss carries the slot it was scheduled for`() = runTest {
        val recovered = sweep(today.minusDays(2), listOf(goal())).first()
        assertEquals(60, recovered.slot.minutes)
        assertEquals("Revision g1", recovered.taskTitle)
    }
}
