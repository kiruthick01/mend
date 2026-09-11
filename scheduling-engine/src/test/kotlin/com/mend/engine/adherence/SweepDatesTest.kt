package com.mend.engine.adherence

import com.mend.engine.TEST_DATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SweepDatesTest {

    @Test
    fun `today is never swept because its slots are still elapsing`() {
        val dates = datesToSweep(lastSwept = TEST_DATE.minusDays(3), today = TEST_DATE)
        assertTrue(TEST_DATE !in dates)
    }

    @Test
    fun `the days since the last sweep are covered, and no more`() {
        val dates = datesToSweep(lastSwept = TEST_DATE.minusDays(3), today = TEST_DATE)
        assertEquals(
            listOf(TEST_DATE.minusDays(2), TEST_DATE.minusDays(1)),
            dates,
        )
    }

    @Test
    fun `a day already swept is not swept again`() {
        val dates = datesToSweep(lastSwept = TEST_DATE.minusDays(1), today = TEST_DATE)
        assertEquals(emptyList(), dates)
    }

    @Test
    fun `a sweep state from the future asks for nothing`() {
        assertEquals(emptyList(), datesToSweep(lastSwept = TEST_DATE, today = TEST_DATE))
        assertEquals(
            emptyList(),
            datesToSweep(lastSwept = TEST_DATE.plusDays(5), today = TEST_DATE),
        )
    }

    @Test
    fun `a first run looks back the full window`() {
        val dates = datesToSweep(lastSwept = null, today = TEST_DATE)
        assertEquals(MAX_SWEEP_LOOKBACK_DAYS.toInt(), dates.size)
        assertEquals(TEST_DATE.minusDays(MAX_SWEEP_LOOKBACK_DAYS), dates.first())
        assertEquals(TEST_DATE.minusDays(1), dates.last())
    }

    @Test
    fun `a long absence is capped rather than back-filling everything`() {
        val dates = datesToSweep(lastSwept = TEST_DATE.minusDays(400), today = TEST_DATE)
        assertEquals(MAX_SWEEP_LOOKBACK_DAYS.toInt(), dates.size)
        assertEquals(TEST_DATE.minusDays(MAX_SWEEP_LOOKBACK_DAYS), dates.first())
    }

    @Test
    fun `dates come back in order with no gaps`() {
        val dates = datesToSweep(lastSwept = TEST_DATE.minusDays(6), today = TEST_DATE)
        assertEquals(dates.sorted(), dates)
        dates.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
    }

    @Test
    fun `the lookback is configurable`() {
        val dates = datesToSweep(lastSwept = null, today = TEST_DATE, maxLookbackDays = 3)
        assertEquals(3, dates.size)
        assertEquals(TEST_DATE.minusDays(3), dates.first())
    }

    @Test
    fun `a zero lookback sweeps nothing`() {
        assertEquals(
            emptyList(),
            datesToSweep(lastSwept = null, today = TEST_DATE, maxLookbackDays = 0),
        )
    }
}
