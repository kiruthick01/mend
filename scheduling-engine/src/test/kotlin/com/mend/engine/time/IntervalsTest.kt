package com.mend.engine.time

import com.mend.engine.interval
import com.mend.engine.render
import kotlin.test.Test
import kotlin.test.assertEquals

class IntervalsTest {

    @Test
    fun `normalize sorts unordered input`() {
        val result = Intervals.normalize(
            listOf(interval(14, 0, 15, 0), interval(9, 0, 10, 0)),
        )
        assertEquals(listOf("09:00-10:00", "14:00-15:00"), result.render())
    }

    @Test
    fun `normalize merges overlapping intervals`() {
        val result = Intervals.normalize(
            listOf(interval(9, 0, 11, 0), interval(10, 0, 12, 0)),
        )
        assertEquals(listOf("09:00-12:00"), result.render())
    }

    @Test
    fun `normalize merges back-to-back intervals into one span`() {
        val result = Intervals.normalize(
            listOf(interval(9, 0, 10, 0), interval(10, 0, 11, 0), interval(11, 0, 12, 0)),
        )
        assertEquals(listOf("09:00-12:00"), result.render())
    }

    @Test
    fun `normalize absorbs a fully enclosed interval`() {
        val result = Intervals.normalize(
            listOf(interval(9, 0, 17, 0), interval(11, 0, 12, 0)),
        )
        assertEquals(listOf("09:00-17:00"), result.render())
    }

    @Test
    fun `normalize drops empty intervals`() {
        val result = Intervals.normalize(
            listOf(interval(9, 0, 9, 0), interval(10, 0, 11, 0)),
        )
        assertEquals(listOf("10:00-11:00"), result.render())
    }

    @Test
    fun `subtract removes overlapping cuts and leaves the gaps`() {
        val result = Intervals.subtract(
            base = listOf(interval(8, 0, 20, 0)),
            cuts = listOf(interval(9, 0, 12, 0), interval(13, 0, 14, 0)),
        )
        assertEquals(listOf("08:00-09:00", "12:00-13:00", "14:00-20:00"), result.render())
    }

    @Test
    fun `subtract with unsorted overlapping cuts still produces a clean map`() {
        val result = Intervals.subtract(
            base = listOf(interval(8, 0, 20, 0)),
            cuts = listOf(interval(13, 0, 14, 0), interval(9, 0, 12, 0), interval(11, 0, 13, 0)),
        )
        assertEquals(listOf("08:00-09:00", "14:00-20:00"), result.render())
    }

    @Test
    fun `subtract with no cuts returns the normalized base`() {
        val result = Intervals.subtract(listOf(interval(8, 0, 20, 0)), emptyList())
        assertEquals(listOf("08:00-20:00"), result.render())
    }

    @Test
    fun `subtract can consume the base entirely`() {
        val result = Intervals.subtract(
            base = listOf(interval(9, 0, 10, 0)),
            cuts = listOf(interval(8, 0, 11, 0)),
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `totalMinutes counts overlapping intervals only once`() {
        val total = Intervals.totalMinutes(
            listOf(interval(9, 0, 11, 0), interval(10, 0, 12, 0)),
        )
        assertEquals(180, total)
    }
}
