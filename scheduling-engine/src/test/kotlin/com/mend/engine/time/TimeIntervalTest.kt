package com.mend.engine.time

import com.mend.engine.TEST_DATE
import com.mend.engine.interval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeIntervalTest {

    @Test
    fun `rejects an inverted interval`() {
        assertFailsWith<IllegalArgumentException> {
            TimeInterval(TEST_DATE.atTime(10, 0), TEST_DATE.atTime(9, 0))
        }
    }

    @Test
    fun `back-to-back intervals do not overlap`() {
        val morning = interval(9, 0, 10, 0)
        val next = interval(10, 0, 11, 0)
        assertFalse(morning.overlaps(next), "half-open intervals that touch must not overlap")
        assertFalse(next.overlaps(morning))
    }

    @Test
    fun `partially overlapping intervals overlap both ways`() {
        val a = interval(9, 0, 11, 0)
        val b = interval(10, 0, 12, 0)
        assertTrue(a.overlaps(b))
        assertTrue(b.overlaps(a))
    }

    @Test
    fun `intersect returns the shared span`() {
        val a = interval(9, 0, 11, 0)
        val b = interval(10, 0, 12, 0)
        assertEquals(interval(10, 0, 11, 0), a.intersect(b))
    }

    @Test
    fun `intersect of touching intervals is null`() {
        assertNull(interval(9, 0, 10, 0).intersect(interval(10, 0, 11, 0)))
    }

    @Test
    fun `minus punches a hole in the middle`() {
        val day = interval(9, 0, 17, 0)
        val lunch = interval(12, 0, 13, 0)
        assertEquals(
            listOf(interval(9, 0, 12, 0), interval(13, 0, 17, 0)),
            day.minus(lunch),
        )
    }

    @Test
    fun `minus clips a leading overlap`() {
        assertEquals(
            listOf(interval(11, 0, 17, 0)),
            interval(9, 0, 17, 0).minus(interval(8, 0, 11, 0)),
        )
    }

    @Test
    fun `minus of an enclosing interval leaves nothing`() {
        assertEquals(emptyList(), interval(9, 0, 17, 0).minus(interval(8, 0, 18, 0)))
    }

    @Test
    fun `minus of a non-overlapping interval changes nothing`() {
        val day = interval(9, 0, 17, 0)
        assertEquals(listOf(day), day.minus(interval(18, 0, 19, 0)))
    }

    @Test
    fun `contains uses half-open semantics at the end instant`() {
        val slot = interval(9, 0, 10, 0)
        assertTrue(slot.contains(TEST_DATE.atTime(9, 0)))
        assertTrue(slot.contains(TEST_DATE.atTime(9, 59)))
        assertFalse(slot.contains(TEST_DATE.atTime(10, 0)))
    }

    @Test
    fun `duration is reported in minutes`() {
        assertEquals(90, interval(9, 0, 10, 30).minutes)
    }
}
