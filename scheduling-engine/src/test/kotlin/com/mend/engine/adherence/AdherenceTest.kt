package com.mend.engine.adherence

import com.mend.engine.TEST_DATE
import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.AdherenceOutcome
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdherenceTest {

    private var counter = 0

    private fun entry(
        taskId: String,
        outcome: AdherenceOutcome,
        daysAgo: Long = 0,
        delay: Long = 0,
        slotMinutes: Long = 60,
    ): AdherenceLogEntry {
        val date = TEST_DATE.minusDays(daysAgo)
        return AdherenceLogEntry(
            id = "e${counter++}",
            taskId = taskId,
            date = date,
            assignedSlotStart = date.atTime(9, 0),
            assignedSlotEnd = date.atTime(9, 0).plusMinutes(slotMinutes),
            completedAt = if (outcome == AdherenceOutcome.MISSED) {
                null
            } else {
                date.atTime(9, 0).plusMinutes(slotMinutes + delay)
            },
            delayMinutes = delay,
            outcome = outcome,
        )
    }

    // --- rates ---------------------------------------------------------------

    @Test
    fun `a task with no history is assumed reliable rather than condemned`() {
        val stats = computeAdherence(emptyList(), "t1", TEST_DATE)
        assertEquals(0, stats.total)
        assertEquals(1.0, stats.adherenceRate)
        assertFalse(stats.hasSignal)
    }

    @Test
    fun `late counts as honoured, missed does not`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 1, delay = 20),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 2),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 3),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(4, stats.total)
        assertEquals(2, stats.completed)
        assertEquals(0.5, stats.adherenceRate)
    }

    @Test
    fun `punctuality is measured only among sessions that happened`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 1, delay = 30),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 2),
            ),
            "t1",
            TEST_DATE,
        )
        // Two happened, one of them on time. The miss does not dilute it.
        assertEquals(0.5, stats.punctualityRate)
    }

    @Test
    fun `negotiated sessions count against adherence but are tracked apart`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME),
                entry("t1", AdherenceOutcome.NEGOTIATED, daysAgo = 1),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(1, stats.negotiated)
        assertEquals(0.5, stats.adherenceRate)
    }

    @Test
    fun `another task's history is not counted`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME),
                entry("other", AdherenceOutcome.MISSED),
                entry("other", AdherenceOutcome.MISSED, daysAgo = 1),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(1, stats.total)
        assertEquals(1.0, stats.adherenceRate)
    }

    @Test
    fun `signal requires a minimum number of observations`() {
        val two = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 1),
            ),
            "t1",
            TEST_DATE,
        )
        assertFalse(two.hasSignal)
        assertTrue(two.describe().contains("too few observations"))

        val three = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 1),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 2),
            ),
            "t1",
            TEST_DATE,
        )
        assertTrue(three.hasSignal)
    }

    // --- the rolling window --------------------------------------------------

    @Test
    fun `entries outside the window are ignored entirely`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 40),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 60),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(1, stats.total)
        assertEquals(1.0, stats.adherenceRate)
    }

    @Test
    fun `the window is inclusive of its far edge`() {
        val onEdge = computeAdherence(
            listOf(entry("t1", AdherenceOutcome.MISSED, daysAgo = 13)),
            "t1",
            TEST_DATE,
            windowDays = 14,
        )
        val justOutside = computeAdherence(
            listOf(entry("t1", AdherenceOutcome.MISSED, daysAgo = 14)),
            "t1",
            TEST_DATE,
            windowDays = 14,
        )
        assertEquals(1, onEdge.total)
        assertEquals(0, justOutside.total)
    }

    @Test
    fun `future-dated entries are not counted`() {
        val stats = computeAdherence(
            listOf(entry("t1", AdherenceOutcome.MISSED, daysAgo = -3)),
            "t1",
            TEST_DATE,
        )
        assertEquals(0, stats.total)
    }

    @Test
    fun `a narrower window can be asked for`() {
        val log = (0L..9L).map { entry("t1", AdherenceOutcome.MISSED, daysAgo = it) }
        assertEquals(3, computeAdherence(log, "t1", TEST_DATE, windowDays = 3).total)
        assertEquals(10, computeAdherence(log, "t1", TEST_DATE, windowDays = 14).total)
    }

    // --- delays --------------------------------------------------------------

    @Test
    fun `average and median delay are computed over completed sessions only`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.LATE, daysAgo = 0, delay = 10),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 1, delay = 20),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 2, delay = 60),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 3),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(30.0, stats.averageDelayMinutes)
        assertEquals(30.0, stats.averageDelayWhenLateMinutes)
        assertEquals(20.0, stats.medianDelayMinutes)
        assertEquals(60, stats.worstDelayMinutes)
    }

    @Test
    fun `the median resists one disastrous day in a way the mean does not`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 0, delay = 0),
                entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 1, delay = 0),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 2, delay = 600),
            ),
            "t1",
            TEST_DATE,
        )
        // Two punctual sessions drag the mean down; the one late session is
        // reported at its own full size.
        assertEquals(200.0, stats.averageDelayMinutes)
        assertEquals(600.0, stats.averageDelayWhenLateMinutes)
        assertEquals(0.0, stats.medianDelayMinutes)
    }

    @Test
    fun `an even number of delays medians across the middle pair`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.LATE, daysAgo = 0, delay = 10),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 1, delay = 30),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(20.0, stats.medianDelayMinutes)
    }

    // --- delay trend ---------------------------------------------------------

    @Test
    fun `too little history reports unknown rather than stable`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.LATE, daysAgo = 0, delay = 10),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 1, delay = 10),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(DelayTrend.UNKNOWN, stats.delayTrend)
    }

    @Test
    fun `delays creeping up read as worsening`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.LATE, daysAgo = 5, delay = 5),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 4, delay = 5),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 1, delay = 45),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 0, delay = 55),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(DelayTrend.WORSENING, stats.delayTrend)
    }

    @Test
    fun `delays shrinking read as improving`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.LATE, daysAgo = 5, delay = 60),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 4, delay = 50),
                entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 1, delay = 0),
                entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 0, delay = 0),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(DelayTrend.IMPROVING, stats.delayTrend)
    }

    @Test
    fun `a small shift is noise, not a trend`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.LATE, daysAgo = 5, delay = 20),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 4, delay = 20),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 1, delay = 22),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 0, delay = 23),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(DelayTrend.STABLE, stats.delayTrend)
    }

    // --- per type ------------------------------------------------------------

    @Test
    fun `stats group by user-named task type`() {
        val types = mapOf(
            "r1" to "reading",
            "r2" to "reading",
            "p1" to "past paper",
        )
        val byType = computeAdherenceByType(
            listOf(
                entry("r1", AdherenceOutcome.MISSED),
                entry("r2", AdherenceOutcome.MISSED, daysAgo = 1),
                entry("p1", AdherenceOutcome.ON_TIME, daysAgo = 1),
            ),
            TEST_DATE,
        ) { types[it] }

        assertEquals(setOf("reading", "past paper"), byType.keys)
        assertEquals(0.0, byType.getValue("reading").adherenceRate)
        assertEquals(1.0, byType.getValue("past paper").adherenceRate)
    }

    @Test
    fun `unknown task ids are skipped rather than bucketed together`() {
        val byType = computeAdherenceByType(
            listOf(
                entry("known", AdherenceOutcome.ON_TIME),
                entry("mystery", AdherenceOutcome.MISSED),
            ),
            TEST_DATE,
        ) { if (it == "known") "reading" else null }

        assertEquals(setOf("reading"), byType.keys)
        assertEquals(1, byType.getValue("reading").total)
    }

    @Test
    fun `overall stats span every task`() {
        val stats = computeOverallAdherence(
            listOf(
                entry("a", AdherenceOutcome.ON_TIME),
                entry("b", AdherenceOutcome.MISSED),
            ),
            TEST_DATE,
        )
        assertEquals(2, stats.total)
        assertEquals(0.5, stats.adherenceRate)
    }

    // --- velocity ------------------------------------------------------------

    @Test
    fun `velocity is completed slot minutes over the observed span`() {
        // Three 60-minute sessions honoured across three days.
        val log = listOf(
            entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 2, slotMinutes = 60),
            entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 1, slotMinutes = 60),
            entry("t1", AdherenceOutcome.LATE, daysAgo = 0, slotMinutes = 60, delay = 10),
        )
        assertEquals(60.0, completedMinutesPerDay(log, TEST_DATE))
    }

    @Test
    fun `missed sessions contribute no velocity`() {
        val log = listOf(
            entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 1, slotMinutes = 60),
            entry("t1", AdherenceOutcome.MISSED, daysAgo = 0, slotMinutes = 600),
        )
        assertEquals(30.0, completedMinutesPerDay(log, TEST_DATE))
    }

    @Test
    fun `no completions means no velocity rather than a divide by zero`() {
        assertEquals(0.0, completedMinutesPerDay(emptyList(), TEST_DATE))
        assertEquals(
            0.0,
            completedMinutesPerDay(listOf(entry("t1", AdherenceOutcome.MISSED)), TEST_DATE),
        )
    }

    @Test
    fun `active days counts distinct dates, not entries`() {
        val log = listOf(
            entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 0),
            entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 0),
            entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 1),
        )
        assertEquals(2, activeDays(log, TEST_DATE))
    }

    // --- reporting -----------------------------------------------------------

    @Test
    fun `the description can be checked against the raw entries`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME),
                entry("t1", AdherenceOutcome.LATE, daysAgo = 1, delay = 30),
                entry("t1", AdherenceOutcome.MISSED, daysAgo = 2),
            ),
            "t1",
            TEST_DATE,
        )
        val text = stats.describe()
        assertTrue(text.contains("2/3"))
        assertTrue(text.contains("1 missed"))
        assertTrue(text.contains("30m"))
    }

    @Test
    fun `the observed span is reported`() {
        val stats = computeAdherence(
            listOf(
                entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 5),
                entry("t1", AdherenceOutcome.ON_TIME, daysAgo = 0),
            ),
            "t1",
            TEST_DATE,
        )
        assertEquals(TEST_DATE.minusDays(5), stats.firstObserved)
        assertEquals(TEST_DATE, stats.lastObserved)
    }

    @Test
    fun `an empty history reports no observed span`() {
        val stats = computeAdherence(emptyList(), "t1", TEST_DATE)
        assertNull(stats.firstObserved)
        assertNull(stats.lastObserved)
    }

    @Test
    fun `computation is pure and repeatable`() {
        val log = listOf(
            entry("t1", AdherenceOutcome.ON_TIME),
            entry("t1", AdherenceOutcome.MISSED, daysAgo = 1),
        )
        assertEquals(
            computeAdherence(log, "t1", TEST_DATE),
            computeAdherence(log, "t1", TEST_DATE),
        )
    }

    @Test
    fun `the window is measured from the date asked about, not today`() {
        val past: LocalDate = TEST_DATE.minusDays(30)
        val log = listOf(
            AdherenceLogEntry(
                id = "x",
                taskId = "t1",
                date = past,
                assignedSlotStart = past.atTime(9, 0),
                assignedSlotEnd = past.atTime(10, 0),
                completedAt = past.atTime(10, 0),
                outcome = AdherenceOutcome.ON_TIME,
            ),
        )
        assertEquals(0, computeAdherence(log, "t1", TEST_DATE).total)
        assertEquals(1, computeAdherence(log, "t1", past).total)
    }
}
