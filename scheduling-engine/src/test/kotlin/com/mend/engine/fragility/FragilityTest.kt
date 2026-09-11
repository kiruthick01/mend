package com.mend.engine.fragility

import com.mend.engine.TEST_DATE
import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.AdherenceOutcome
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FragilityTest {

    private var counter = 0

    private fun entry(
        taskId: String,
        outcome: AdherenceOutcome,
    ) = AdherenceLogEntry(
        id = "e${counter++}",
        taskId = taskId,
        date = TEST_DATE,
        assignedSlotStart = TEST_DATE.atTime(9, 0),
        assignedSlotEnd = TEST_DATE.atTime(10, 0),
        outcome = outcome,
    )

    private fun log(taskId: String, vararg outcomes: AdherenceOutcome) =
        outcomes.map { entry(taskId, it) }

    // --- eligibility is narrow on purpose -----------------------------------

    @Test
    fun `untouched work is not fragile`() {
        val f = assessFragility("t1", emptyList(), isSequential = true)
        assertFalse(f.pileupRisk)
        assertFalse(f.delayNegotiationEligible)
    }

    @Test
    fun `one skip is not yet a pileup`() {
        val f = assessFragility(
            "t1",
            log("t1", AdherenceOutcome.MISSED),
            isSequential = true,
        )
        assertEquals(1, f.skipCount)
        assertFalse(f.pileupRisk)
        assertFalse(f.delayNegotiationEligible)
        assertTrue(f.reason.contains("threshold is 2"))
    }

    @Test
    fun `two skips on sequential work earns the right to negotiate`() {
        val f = assessFragility(
            "t1",
            log("t1", AdherenceOutcome.MISSED, AdherenceOutcome.MISSED),
            isSequential = true,
        )
        assertTrue(f.pileupRisk)
        assertTrue(f.delayNegotiationEligible)
        assertTrue(f.reason.contains("nothing downstream can start"))
    }

    @Test
    fun `parallelisable work is never eligible however often it is skipped`() {
        val f = assessFragility(
            "t1",
            log(
                "t1",
                AdherenceOutcome.MISSED,
                AdherenceOutcome.MISSED,
                AdherenceOutcome.MISSED,
                AdherenceOutcome.MISSED,
            ),
            isSequential = false,
        )
        assertEquals(4, f.skipCount)
        assertTrue(f.pileupRisk)
        assertFalse(f.delayNegotiationEligible)
        assertTrue(f.reason.contains("other work to do instead"))
    }

    @Test
    fun `lateness is not a skip`() {
        val f = assessFragility(
            "t1",
            log("t1", AdherenceOutcome.LATE, AdherenceOutcome.LATE, AdherenceOutcome.LATE),
            isSequential = true,
        )
        assertEquals(0, f.skipCount)
        assertEquals(3, f.lateCount)
        assertFalse(f.delayNegotiationEligible)
    }

    @Test
    fun `a negotiated outcome is not counted as a skip`() {
        val f = assessFragility(
            "t1",
            log("t1", AdherenceOutcome.NEGOTIATED, AdherenceOutcome.NEGOTIATED),
            isSequential = true,
        )
        assertEquals(0, f.skipCount)
        assertFalse(f.delayNegotiationEligible)
    }

    @Test
    fun `another task's history does not make this one fragile`() {
        val mixed = log("other", AdherenceOutcome.MISSED, AdherenceOutcome.MISSED) +
            log("t1", AdherenceOutcome.ON_TIME)
        val f = assessFragility("t1", mixed, isSequential = true)
        assertEquals(0, f.skipCount)
        assertFalse(f.delayNegotiationEligible)
    }

    @Test
    fun `every assessment explains itself`() {
        val cases = listOf(
            assessFragility("a", emptyList(), true),
            assessFragility("b", log("b", AdherenceOutcome.MISSED), true),
            assessFragility(
                "c",
                log("c", AdherenceOutcome.MISSED, AdherenceOutcome.MISSED),
                true,
            ),
            assessFragility(
                "d",
                log("d", AdherenceOutcome.MISSED, AdherenceOutcome.MISSED),
                false,
            ),
        )
        assertTrue(cases.all { it.reason.isNotBlank() })
        // Four distinct situations produce four distinct explanations.
        assertEquals(4, cases.map { it.reason }.distinct().size)
    }

    // --- adherence rate ------------------------------------------------------

    @Test
    fun `an untouched task is assumed reliable rather than punished`() {
        assertEquals(1.0, assessFragility("t1", emptyList(), true).adherenceRate)
    }

    @Test
    fun `adherence rate is completions over everything recorded`() {
        val f = assessFragility(
            "t1",
            log(
                "t1",
                AdherenceOutcome.ON_TIME,
                AdherenceOutcome.ON_TIME,
                AdherenceOutcome.MISSED,
                AdherenceOutcome.LATE,
            ),
            isSequential = true,
        )
        assertEquals(0.5, f.adherenceRate)
    }

    // --- negotiating ---------------------------------------------------------

    private fun eligible() = assessFragility(
        "t1",
        log("t1", AdherenceOutcome.MISSED, AdherenceOutcome.MISSED),
        isSequential = true,
    )

    private fun ineligible() = assessFragility("t1", emptyList(), isSequential = true)

    @Test
    fun `refusal is the default`() {
        val n = negotiateDelay(ineligible(), Duration.ofHours(12))
        assertFalse(n.wasGranted)
        assertNull(n.granted)
        assertTrue(n.reason.contains("not eligible"))
    }

    @Test
    fun `eligible work can negotiate a delay`() {
        val n = negotiateDelay(eligible(), Duration.ofHours(12))
        assertTrue(n.wasGranted)
        assertEquals(Duration.ofHours(12), n.granted)
    }

    @Test
    fun `a delay is capped rather than granted unbounded`() {
        val n = negotiateDelay(eligible(), Duration.ofDays(30))
        assertEquals(MAX_NEGOTIATED_DELAY, n.granted)
        assertTrue(n.reason.contains("cap"))
    }

    @Test
    fun `a zero or negative delay is refused`() {
        assertFalse(negotiateDelay(eligible(), Duration.ZERO).wasGranted)
        assertFalse(negotiateDelay(eligible(), Duration.ofHours(-2)).wasGranted)
    }

    @Test
    fun `the cap is configurable for a caller that needs a tighter one`() {
        val n = negotiateDelay(
            eligible(),
            requested = Duration.ofDays(2),
            maximum = Duration.ofHours(6),
        )
        assertEquals(Duration.ofHours(6), n.granted)
    }

    @Test
    fun `a refusal still names the reason it was refused`() {
        val n = negotiateDelay(
            assessFragility(
                "t1",
                log("t1", AdherenceOutcome.MISSED, AdherenceOutcome.MISSED),
                isSequential = false,
            ),
            Duration.ofHours(4),
        )
        assertFalse(n.wasGranted)
        assertTrue(n.reason.contains("parallelisable"))
    }
}
