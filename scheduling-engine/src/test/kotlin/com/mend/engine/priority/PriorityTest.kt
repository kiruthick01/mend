package com.mend.engine.priority

import com.mend.engine.TEST_DATE
import com.mend.engine.goalTask
import com.mend.engine.model.Weights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriorityTest {

    // --- deadline proximity --------------------------------------------------

    @Test
    fun `a deadline today is maximum urgency`() {
        assertEquals(1.0, deadlineProximity(TEST_DATE, TEST_DATE))
    }

    @Test
    fun `an overdue deadline stays at maximum urgency rather than overflowing`() {
        assertEquals(1.0, deadlineProximity(TEST_DATE.minusDays(30), TEST_DATE))
    }

    @Test
    fun `a deadline beyond the horizon contributes nothing`() {
        assertEquals(0.0, deadlineProximity(TEST_DATE.plusDays(30), TEST_DATE))
        assertEquals(0.0, deadlineProximity(TEST_DATE.plusDays(365), TEST_DATE))
    }

    @Test
    fun `urgency rises linearly as the deadline approaches`() {
        assertEquals(0.5, deadlineProximity(TEST_DATE.plusDays(15), TEST_DATE))
        assertEquals(0.8, deadlineProximity(TEST_DATE.plusDays(6), TEST_DATE), 1e-9)
        assertEquals(0.2, deadlineProximity(TEST_DATE.plusDays(24), TEST_DATE), 1e-9)
    }

    // --- importance ----------------------------------------------------------

    @Test
    fun `importance maps one to five onto zero to one`() {
        assertEquals(0.0, normalisedImportance(1))
        assertEquals(0.25, normalisedImportance(2))
        assertEquals(0.5, normalisedImportance(3))
        assertEquals(1.0, normalisedImportance(5))
    }

    @Test
    fun `importance outside the scale is clamped rather than trusted`() {
        assertEquals(0.0, normalisedImportance(-4))
        assertEquals(1.0, normalisedImportance(99))
    }

    // --- adherence -----------------------------------------------------------

    @Test
    fun `work that always gets done needs no push`() {
        assertEquals(0.0, adherencePressure(1.0))
    }

    @Test
    fun `work that keeps slipping is pushed up`() {
        assertEquals(1.0, adherencePressure(0.0))
    }

    @Test
    fun `the neutral default moves the score in neither direction`() {
        assertEquals(0.5, adherencePressure(0.5))
    }

    // --- the combined score --------------------------------------------------

    @Test
    fun `the score is the weighted sum of its three terms`() {
        val task = goalTask(
            "t1",
            deadlineIn = 15,
            importance = 5,
        )
        // deadline 0.5 * 0.5 + importance 1.0 * 0.35 + adherence 0.5 * 0.15
        assertEquals(0.675, computePriorityScore(task, Weights.DEFAULT, TEST_DATE), 1e-9)
    }

    @Test
    fun `an urgent important task outranks a distant unimportant one`() {
        val urgent = goalTask("urgent", deadlineIn = 1, importance = 5)
        val distant = goalTask("distant", deadlineIn = 60, importance = 1)
        assertTrue(
            computePriorityScore(urgent, today = TEST_DATE) >
                computePriorityScore(distant, today = TEST_DATE),
        )
    }

    @Test
    fun `weights actually change the ranking`() {
        val urgentButTrivial = goalTask("urgent", deadlineIn = 0, importance = 1)
        val distantButVital = goalTask("vital", deadlineIn = 25, importance = 5)

        val deadlineDriven = Weights(deadline = 0.9, importance = 0.05, adherenceTrend = 0.05)
        val importanceDriven = Weights(deadline = 0.05, importance = 0.9, adherenceTrend = 0.05)

        assertTrue(
            computePriorityScore(urgentButTrivial, deadlineDriven, TEST_DATE) >
                computePriorityScore(distantButVital, deadlineDriven, TEST_DATE),
        )
        assertTrue(
            computePriorityScore(distantButVital, importanceDriven, TEST_DATE) >
                computePriorityScore(urgentButTrivial, importanceDriven, TEST_DATE),
        )
    }

    @Test
    fun `the breakdown explains where the number came from`() {
        val breakdown = computePriorityBreakdown(
            goalTask("t1", deadlineIn = 0, importance = 5),
            Weights.DEFAULT,
            TEST_DATE,
        )
        val explanation = breakdown.explain()
        assertTrue(explanation.contains("deadline"))
        assertTrue(explanation.contains("importance"))
        assertTrue(explanation.contains("adherence"))
    }

    @Test
    fun `scoring is pure and repeatable`() {
        val task = goalTask("t1", deadlineIn = 5, importance = 3)
        assertEquals(
            computePriorityScore(task, today = TEST_DATE),
            computePriorityScore(task, today = TEST_DATE),
        )
    }

    // --- ranking and tie-breaking --------------------------------------------

    @Test
    fun `ranking puts the highest score first`() {
        val ranked = rankByPriority(
            listOf(
                goalTask("low", deadlineIn = 28, importance = 1),
                goalTask("high", deadlineIn = 0, importance = 5),
                goalTask("mid", deadlineIn = 14, importance = 3),
            ),
            today = TEST_DATE,
        )
        assertEquals(listOf("high", "mid", "low"), ranked.map { it.task.id })
    }

    @Test
    fun `equal scores are broken by the earlier deadline`() {
        // Same importance and same adherence, so scores differ only by deadline;
        // force a genuine tie by zeroing the deadline weight.
        val weights = Weights(deadline = 0.0, importance = 0.5, adherenceTrend = 0.5)
        val ranked = rankByPriority(
            listOf(
                goalTask("later", deadlineIn = 20, importance = 3),
                goalTask("sooner", deadlineIn = 2, importance = 3),
            ),
            weights,
            TEST_DATE,
        )
        assertEquals(listOf("sooner", "later"), ranked.map { it.task.id })
        assertEquals(ranked[0].score, ranked[1].score)
    }

    @Test
    fun `an exact tie is broken by importance before id`() {
        val weights = Weights(deadline = 0.0, importance = 0.0, adherenceTrend = 1.0)
        val ranked = rankByPriority(
            listOf(
                goalTask("aaa", deadlineIn = 5, importance = 1),
                goalTask("zzz", deadlineIn = 5, importance = 5),
            ),
            weights,
            TEST_DATE,
        )
        assertEquals(ranked[0].score, ranked[1].score)
        assertEquals(listOf("zzz", "aaa"), ranked.map { it.task.id })
    }

    @Test
    fun `a total tie falls back to lexicographic id so the order is stable`() {
        val weights = Weights(deadline = 0.0, importance = 0.0, adherenceTrend = 1.0)
        val tasks = listOf(
            goalTask("charlie", deadlineIn = 5, importance = 3),
            goalTask("alpha", deadlineIn = 5, importance = 3),
            goalTask("bravo", deadlineIn = 5, importance = 3),
        )
        val first = rankByPriority(tasks, weights, TEST_DATE).map { it.task.id }
        val second = rankByPriority(tasks.reversed(), weights, TEST_DATE).map { it.task.id }

        assertEquals(listOf("alpha", "bravo", "charlie"), first)
        // Same set in a different input order must produce the same schedule,
        // or bug reports become unreproducible.
        assertEquals(first, second)
    }

    @Test
    fun `ranking an empty list yields an empty ranking`() {
        assertEquals(emptyList(), rankByPriority(emptyList(), today = TEST_DATE))
    }
}
