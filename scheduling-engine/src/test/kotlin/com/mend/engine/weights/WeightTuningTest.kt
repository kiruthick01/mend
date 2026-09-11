package com.mend.engine.weights

import com.mend.engine.TEST_DATE
import com.mend.engine.model.Weights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightTuningTest {

    private val now = TEST_DATE.atTime(20, 0)

    private fun feedback(vararg values: ScheduleFeedback) = values.toList()

    private fun evaluate(
        weights: Weights = Weights.DEFAULT,
        recent: List<ScheduleFeedback>,
    ) = evaluateFeedback(weights, recent, now, "a1")

    private val aggressive = ScheduleFeedback.TOO_AGGRESSIVE
    private val lax = ScheduleFeedback.TOO_LAX
    private val fine = ScheduleFeedback.JUST_RIGHT

    // --- the streak bar ------------------------------------------------------

    @Test
    fun `no feedback changes nothing`() {
        assertNull(evaluate(recent = emptyList()))
    }

    @Test
    fun `one complaint is not enough`() {
        assertNull(evaluate(recent = feedback(aggressive)))
    }

    @Test
    fun `two complaints are not enough`() {
        assertNull(evaluate(recent = feedback(aggressive, aggressive)))
    }

    @Test
    fun `three consecutive complaints move a weight`() {
        val adjustment = evaluate(recent = feedback(aggressive, aggressive, aggressive))
        assertNotNull(adjustment)
        assertEquals(WeightKind.DEADLINE, adjustment.kind)
    }

    @Test
    fun `just right in the middle breaks the streak`() {
        assertNull(evaluate(recent = feedback(aggressive, aggressive, fine, aggressive)))
    }

    @Test
    fun `a contradicting response breaks the streak`() {
        assertNull(evaluate(recent = feedback(aggressive, aggressive, lax, aggressive)))
    }

    @Test
    fun `a streak of just right never moves anything`() {
        assertNull(evaluate(recent = feedback(fine, fine, fine, fine, fine)))
    }

    @Test
    fun `only the leading run counts, not the total`() {
        // Two recent complaints on top of an older run of the same kind. The
        // older run was already acted on; it must not count twice.
        assertNull(evaluate(recent = feedback(aggressive, aggressive, fine, aggressive, aggressive, aggressive)))
    }

    @Test
    fun `a longer streak still moves exactly one step`() {
        val three = evaluate(recent = List(3) { aggressive })!!
        val ten = evaluate(recent = List(10) { aggressive })!!
        assertEquals(three.to, ten.to)
        assertEquals(10, ten.streak)
    }

    // --- direction -----------------------------------------------------------

    @Test
    fun `too aggressive lowers deadline pressure`() {
        val adjustment = evaluate(recent = List(3) { aggressive })!!
        assertEquals(WeightKind.DEADLINE, adjustment.kind)
        assertEquals(0.5, adjustment.from)
        assertEquals(0.45, adjustment.to, 1e-9)
        assertTrue(adjustment.delta < 0)
    }

    @Test
    fun `too lax raises deadline pressure`() {
        val adjustment = evaluate(recent = List(3) { lax })!!
        assertEquals(WeightKind.DEADLINE, adjustment.kind)
        assertEquals(0.55, adjustment.to, 1e-9)
        assertTrue(adjustment.delta > 0)
    }

    // --- importance is never touched automatically --------------------------

    @Test
    fun `importance is never lowered automatically`() {
        val pinned = Weights(deadline = WEIGHT_MIN, importance = 0.7, adherenceTrend = 0.25)
        val adjustment = evaluate(pinned, List(3) { aggressive })
        // Deadline is already at the floor, so adherence takes the cut, not
        // the user's own statement of what matters.
        assertEquals(WeightKind.ADHERENCE_TREND, adjustment?.kind)
    }

    @Test
    fun `importance is never raised automatically either`() {
        val pinned = Weights(deadline = WEIGHT_MAX, importance = 0.1, adherenceTrend = 0.1)
        val adjustment = evaluate(pinned, List(3) { lax })
        assertEquals(WeightKind.ADHERENCE_TREND, adjustment?.kind)
    }

    // --- bounds --------------------------------------------------------------

    @Test
    fun `a weight at the floor falls back to the other pressure term`() {
        val pinned = Weights(deadline = WEIGHT_MIN, importance = 0.45, adherenceTrend = 0.5)
        assertEquals(
            WeightKind.ADHERENCE_TREND,
            evaluate(pinned, List(3) { aggressive })?.kind,
        )
    }

    @Test
    fun `when both pressure terms are pinned nothing is logged`() {
        val pinned = Weights(
            deadline = WEIGHT_MIN,
            importance = 0.9,
            adherenceTrend = WEIGHT_MIN,
        )
        assertNull(
            evaluate(pinned, List(3) { aggressive }),
            "an adjustment that changes nothing should not be recorded",
        )
    }

    @Test
    fun `a weight never falls below the floor`() {
        val low = Weights(deadline = WEIGHT_MIN + 0.01, importance = 0.5, adherenceTrend = 0.44)
        val adjustment = evaluate(low, List(3) { aggressive })!!
        assertEquals(WEIGHT_MIN, adjustment.to)
    }

    @Test
    fun `a weight never rises above the ceiling`() {
        val high = Weights(deadline = WEIGHT_MAX - 0.01, importance = 0.1, adherenceTrend = 0.11)
        val adjustment = evaluate(high, List(3) { lax })!!
        assertEquals(WEIGHT_MAX, adjustment.to)
    }

    // --- renormalisation -----------------------------------------------------

    @Test
    fun `weights still sum to one after an adjustment`() {
        val after = evaluate(recent = List(3) { aggressive })!!.weightsAfter
        assertEquals(
            1.0,
            after.deadline + after.importance + after.adherenceTrend,
            1e-9,
        )
    }

    @Test
    fun `the adjusted weight keeps exactly the value it was given`() {
        val adjustment = evaluate(recent = List(3) { aggressive })!!
        assertEquals(adjustment.to, adjustment.weightsAfter.deadline, 1e-9)
    }

    @Test
    fun `the untouched weights keep their relative proportion`() {
        // Importance and adherence start at 0.35 and 0.15, a 7:3 split. After
        // deadline is cut they share the remainder in the same ratio.
        val after = evaluate(recent = List(3) { aggressive })!!.weightsAfter
        assertEquals(
            0.35 / 0.15,
            after.importance / after.adherenceTrend,
            1e-9,
        )
    }

    @Test
    fun `renormalisation copes with the other weights being zero`() {
        val lopsided = Weights(deadline = 0.9, importance = 0.05, adherenceTrend = 0.05)
        val after = lopsided.withWeight(WeightKind.DEADLINE, 0.4)
            .renormalised(WeightKind.DEADLINE)
        assertEquals(1.0, after.deadline + after.importance + after.adherenceTrend, 1e-9)
        assertEquals(0.4, after.deadline, 1e-9)
    }

    @Test
    fun `the resulting weights are always constructible`() {
        // Weights validates its own range, so an out-of-range renormalisation
        // would throw rather than silently producing a broken score.
        listOf(aggressive, lax).forEach { response ->
            repeat(20) {
                val adjustment = evaluate(recent = List(3) { response })
                adjustment?.weightsAfter?.let {
                    assertTrue(it.deadline in 0.0..1.0)
                    assertTrue(it.importance in 0.0..1.0)
                    assertTrue(it.adherenceTrend in 0.0..1.0)
                }
            }
        }
    }

    // --- explainability ------------------------------------------------------

    @Test
    fun `the reason names the feedback, the streak, and both values`() {
        val adjustment = evaluate(recent = List(3) { aggressive })!!
        val text = adjustment.describe()
        assertTrue(text.contains("3 consecutive"))
        assertTrue(text.contains("too aggressive"))
        assertTrue(text.contains("deadline pressure"))
        assertTrue(text.contains("0.50"))
        assertTrue(text.contains("0.45"))
        assertTrue(text.contains("rescaled"))
    }

    @Test
    fun `an automatic adjustment records what triggered it`() {
        val adjustment = evaluate(recent = List(3) { lax })!!
        assertEquals(ScheduleFeedback.TOO_LAX, adjustment.feedback)
        assertEquals(3, adjustment.streak)
        assertFalse(adjustment.isManual)
    }

    // --- manual override -----------------------------------------------------

    @Test
    fun `a manual override is recorded in the same history`() {
        val adjustment = manualAdjustment(
            Weights.DEFAULT,
            WeightKind.IMPORTANCE,
            value = 0.6,
            now = now,
            id = "m1",
        )!!
        assertTrue(adjustment.isManual)
        assertNull(adjustment.feedback)
        assertEquals(0, adjustment.streak)
        assertTrue(adjustment.describe().contains("set by hand"))
    }

    @Test
    fun `a manual override can set importance, unlike the automatic rule`() {
        val adjustment = manualAdjustment(
            Weights.DEFAULT, WeightKind.IMPORTANCE, 0.6, now, "m1",
        )!!
        assertEquals(0.6, adjustment.weightsAfter.importance, 1e-9)
        assertEquals(
            1.0,
            with(adjustment.weightsAfter) { deadline + importance + adherenceTrend },
            1e-9,
        )
    }

    @Test
    fun `a manual override is clamped to the same bounds`() {
        val tooHigh = manualAdjustment(Weights.DEFAULT, WeightKind.DEADLINE, 5.0, now, "m")!!
        val tooLow = manualAdjustment(Weights.DEFAULT, WeightKind.DEADLINE, -2.0, now, "m")!!
        assertEquals(WEIGHT_MAX, tooHigh.to)
        assertEquals(WEIGHT_MIN, tooLow.to)
    }

    @Test
    fun `setting a weight to what it already is records nothing`() {
        assertNull(
            manualAdjustment(Weights.DEFAULT, WeightKind.DEADLINE, Weights.DEFAULT.deadline, now, "m"),
        )
    }

    // --- determinism ---------------------------------------------------------

    @Test
    fun `evaluation is pure and repeatable`() {
        val recent = List(3) { aggressive }
        assertEquals(
            evaluateFeedback(Weights.DEFAULT, recent, now, "same"),
            evaluateFeedback(Weights.DEFAULT, recent, now, "same"),
        )
    }

    @Test
    fun `repeated streaks walk the weight down one step at a time`() {
        var weights = Weights.DEFAULT
        val path = mutableListOf(weights.deadline)
        repeat(4) {
            val adjustment = evaluateFeedback(weights, List(3) { aggressive }, now, "a")!!
            weights = adjustment.weightsAfter
            path += weights.deadline
        }
        assertEquals(listOf(0.50, 0.45, 0.40, 0.35, 0.30), path.map { "%.2f".format(it).toDouble() })
    }
}
