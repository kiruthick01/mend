package com.mend.app.progress

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressInsightsTest {

    private val today = LocalDate.of(2026, 10, 16)
    private val key = "g|DUOLINGO"

    private fun rec(daysAgo: Int, practiced: Boolean? = true, minutes: Int? = null, streak: Int? = null,
                    xp: Int? = null, due: Int? = null, hours: List<Int>? = null) =
        DayRecord(today.minusDays(daysAgo.toLong()), key, practiced, minutes, due, streak, xp, hours)

    @Test fun `anki sizing is 8s a card rounded up to 5 minutes within bounds`() {
        assertEquals(Duration.ofMinutes(5), ProgressInsights.ankiReviewDuration(3))
        assertEquals(Duration.ofMinutes(20), ProgressInsights.ankiReviewDuration(120)) // 16 min -> 20
        assertEquals(Duration.ofMinutes(60), ProgressInsights.ankiReviewDuration(5000))
    }

    @Test fun `typical duration needs enough samples and uses the median`() {
        assertNull(ProgressInsights.typicalDuration(listOf(rec(1, minutes = 20), rec(2, minutes = 20))))
        val records = listOf(rec(1, minutes = 10), rec(2, minutes = 20), rec(3, minutes = 200))
        assertEquals(Duration.ofMinutes(20), ProgressInsights.typicalDuration(records))
        // Days without practice do not drag the estimate down.
        assertEquals(
            Duration.ofMinutes(20),
            ProgressInsights.typicalDuration(records + rec(4, practiced = false, minutes = 1)),
        )
    }

    @Test fun `streak at risk when done yesterday but not today`() {
        assertTrue(ProgressInsights.streakAtRisk(listOf(rec(1)), today))
        assertFalse(ProgressInsights.streakAtRisk(listOf(rec(1), rec(0)), today))
        assertFalse(ProgressInsights.streakAtRisk(listOf(rec(3)), today))
    }

    @Test fun `a live profile streak counts as at risk`() {
        assertTrue(ProgressInsights.streakAtRisk(listOf(rec(0, practiced = false, streak = 12)), today))
        assertFalse(ProgressInsights.streakAtRisk(emptyList(), today))
    }

    @Test fun `recorded streak counts back from today or yesterday`() {
        assertEquals(3, ProgressInsights.recordedStreak(listOf(rec(0), rec(1), rec(2), rec(4)), today))
        assertEquals(2, ProgressInsights.recordedStreak(listOf(rec(1), rec(2)), today))
        assertEquals(0, ProgressInsights.recordedStreak(listOf(rec(2)), today))
    }

    @Test fun `protected sleep covers wind down through wake across midnight`() {
        val bed = LocalTime.of(22, 30)
        val wind = Duration.ofMinutes(30)
        val wake = LocalTime.of(6, 30)
        assertTrue(ProgressInsights.inProtectedSleep(LocalTime.of(22, 0), bed, wind, wake))
        assertTrue(ProgressInsights.inProtectedSleep(LocalTime.of(3, 0), bed, wind, wake))
        assertFalse(ProgressInsights.inProtectedSleep(LocalTime.of(21, 59), bed, wind, wake))
        assertFalse(ProgressInsights.inProtectedSleep(LocalTime.of(7, 0), bed, wind, wake))
    }

    @Test fun `peak window needs data and finds the busiest two hours`() {
        fun hours(vararg at: Pair<Int, Int>) = MutableList(24) { 0 }.also { l -> at.forEach { l[it.first] = it.second } }
        assertNull(ProgressInsights.peakWindow(listOf(rec(1, hours = hours(21 to 5))), today))
        val days = (1..4).map { rec(it, hours = hours(21 to 10, 22 to 8, 9 to 3)) }
        assertEquals(LocalTime.of(21, 0) to LocalTime.of(23, 0), ProgressInsights.peakWindow(days, today))
    }

    @Test fun `peak window late in the day stays inside one day`() {
        val h = MutableList(24) { 0 }.also { it[23] = 30 }
        val days = (1..4).map { rec(it, hours = h) }
        val window = ProgressInsights.peakWindow(days, today)!!
        assertTrue(window.second > window.first)
    }

    @Test fun `xp gained needs two readings in the window`() {
        assertNull(ProgressInsights.xpGained(listOf(rec(0, xp = 100)), today))
        assertEquals(150, ProgressInsights.xpGained(listOf(rec(5, xp = 100), rec(0, xp = 250)), today))
    }

    @Test fun `merge keeps practice and fills only what the newer reading has`() {
        val a = rec(0, practiced = true, minutes = 12, due = 40)
        val b = rec(0, practiced = false, minutes = null, due = 0)
        val m = a.merge(b)
        assertEquals(true, m.practiced)
        assertEquals(12, m.minutes)
        assertEquals(0, m.due)
    }

    @Test fun `summary line lists what is known`() {
        val line = ProgressInsights.summaryLine(
            ProgressKind.DUOLINGO,
            listOf(rec(0, practiced = true, minutes = 18, streak = 12)),
            today,
        )
        assertEquals("Duolingo · 12-day streak · 18 min · done today", line)
    }

    @Test fun `last days is oldest first`() {
        assertEquals(
            listOf(false, false, false, false, true, false, true),
            ProgressInsights.lastDays(listOf(rec(0), rec(2)), today),
        )
    }
}
