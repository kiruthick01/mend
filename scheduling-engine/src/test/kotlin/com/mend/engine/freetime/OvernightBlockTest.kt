package com.mend.engine.freetime

import com.mend.engine.TEST_DATE
import com.mend.engine.at
import com.mend.engine.render
import com.mend.engine.staticBlock
import com.mend.engine.model.WindowAnchor
import java.time.DayOfWeek
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Static blocks that run past midnight.
 *
 * A late shift, a 22:00 gym, a class that finishes after twelve — all ordinary,
 * and all impossible to express until now, because the window was required to
 * end after it started. Found by using the app rather than by a test, which is
 * its own small lesson.
 */
class OvernightBlockTest {

    /** A night shift: 23:00 to 05:15, TEST_DATE is a Wednesday. */
    private fun nightShift(days: Set<DayOfWeek> = com.mend.engine.model.StaticBlock.ALL_DAYS) =
        staticBlock("shift", from = at(23), to = at(5, 15), days = days)

    @Test
    fun `a window ending before it starts is an overnight block, not an error`() {
        val block = nightShift()
        assertTrue(block.crossesMidnight)
        assertEquals(Duration.ofHours(6).plusMinutes(15), block.windowDuration)
    }

    @Test
    fun `a window that does not wrap is unchanged`() {
        val college = staticBlock("college", from = at(9), to = at(16))
        assertFalse(college.crossesMidnight)
        assertEquals(Duration.ofHours(7), college.windowDuration)
    }

    @Test
    fun `a zero-length window is still rejected`() {
        assertFailsWith<IllegalArgumentException> {
            staticBlock("nonsense", from = at(9), to = at(9))
        }
    }

    @Test
    fun `an overnight block occupies both ends of the day`() {
        val map = computeFreeTimeMap(TEST_DATE, staticBlocks = listOf(nightShift()))
        // Last night's tail this morning, and tonight's head this evening.
        assertEquals(listOf("00:00-05:15", "23:00-24:00"), map.occupied.render())
        assertEquals(listOf("05:15-23:00"), map.free.render())
    }

    @Test
    fun `the committed total counts both halves`() {
        val map = computeFreeTimeMap(TEST_DATE, staticBlocks = listOf(nightShift()))
        assertEquals(6 * 60 + 15, map.totalOccupiedMinutes)
        assertEquals(1440 - (6 * 60 + 15), map.totalFreeMinutes)
    }

    @Test
    fun `day-of-week follows the night the block started on`() {
        // A Wednesday-only night shift runs Wednesday evening into Thursday.
        val wednesdayNights = nightShift(days = setOf(DayOfWeek.WEDNESDAY))

        val wednesday = computeFreeTimeMap(TEST_DATE, staticBlocks = listOf(wednesdayNights))
        assertEquals(listOf("23:00-24:00"), wednesday.occupied.render())

        val thursday = computeFreeTimeMap(
            TEST_DATE.plusDays(1),
            staticBlocks = listOf(wednesdayNights),
        )
        assertEquals(
            listOf("00:00-05:15"),
            thursday.occupied.render(),
            "Wednesday night runs into Thursday morning; that is what people mean",
        )
    }

    @Test
    fun `a day with neither end of an overnight block is untouched`() {
        val map = computeFreeTimeMap(
            TEST_DATE.plusDays(2),
            staticBlocks = listOf(nightShift(days = setOf(DayOfWeek.WEDNESDAY))),
        )
        assertEquals(listOf("00:00-24:00"), map.free.render())
    }

    @Test
    fun `an overnight partial window anchors to the start of its own night`() {
        // A 22:00-02:00 gym window needing only 45 minutes.
        val gym = staticBlock(
            "gym",
            from = at(22),
            to = at(2),
            required = Duration.ofMinutes(45),
        )
        val map = computeFreeTimeMap(TEST_DATE, staticBlocks = listOf(gym))
        assertEquals(listOf("22:00-22:45"), map.occupied.render())
    }

    @Test
    fun `an overnight partial window anchored LATEST lands after midnight`() {
        val gym = staticBlock(
            "gym",
            from = at(22),
            to = at(2),
            required = Duration.ofMinutes(45),
            anchor = WindowAnchor.LATEST,
        )
        val map = computeFreeTimeMap(TEST_DATE, staticBlocks = listOf(gym))
        // Last night's occurrence ended at 02:00 this morning.
        assertEquals(listOf("01:15-02:00"), map.occupied.render())
    }

    @Test
    fun `a required duration longer than a wrapped window is still rejected`() {
        assertFailsWith<IllegalArgumentException> {
            staticBlock("shift", from = at(23), to = at(5), required = Duration.ofHours(8))
        }
    }

    @Test
    fun `an overnight block coexists with an ordinary one`() {
        val map = computeFreeTimeMap(
            TEST_DATE,
            staticBlocks = listOf(
                nightShift(),
                staticBlock("college", from = at(9), to = at(16)),
            ),
        )
        assertEquals(
            listOf("00:00-05:15", "09:00-16:00", "23:00-24:00"),
            map.occupied.render(),
        )
        assertEquals(listOf("05:15-09:00", "16:00-23:00"), map.free.render())
    }

    @Test
    fun `the placement says it runs overnight`() {
        val map = computeFreeTimeMap(TEST_DATE, staticBlocks = listOf(nightShift()))
        assertTrue(map.placements.all { it.reason.contains("overnight") })
    }

    @Test
    fun `occupied and free still tile the day exactly once`() {
        val map = computeFreeTimeMap(
            TEST_DATE,
            staticBlocks = listOf(nightShift(), staticBlock("college", from = at(9), to = at(16))),
        )
        assertEquals(1440, map.totalFreeMinutes + map.totalOccupiedMinutes)
    }
}
