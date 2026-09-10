package com.mend.engine.freetime

import com.mend.engine.TEST_DATE
import com.mend.engine.at
import com.mend.engine.habit
import com.mend.engine.model.WindowAnchor
import com.mend.engine.render
import com.mend.engine.staticBlock
import java.time.DayOfWeek
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FreeTimeMapTest {

    @Test
    fun `an empty day is entirely free`() {
        val map = computeFreeTimeMap(TEST_DATE)
        assertEquals(listOf("00:00-24:00"), map.free.render())
        assertEquals(1440, map.totalFreeMinutes)
        assertEquals(0, map.totalOccupiedMinutes)
    }

    @Test
    fun `a single static block splits the day in two`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("college", from = at(9), to = at(16))),
        )
        assertEquals(listOf("00:00-09:00", "16:00-24:00"), map.free.render())
        assertEquals(420, map.totalOccupiedMinutes)
    }

    @Test
    fun `back-to-back static blocks leave no sliver between them`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock("college", from = at(9), to = at(13)),
                staticBlock("mess", from = at(13), to = at(14)),
            ),
        )
        assertEquals(listOf("00:00-09:00", "14:00-24:00"), map.free.render())
        assertEquals(listOf("09:00-14:00"), map.occupied.render())
    }

    @Test
    fun `overlapping static blocks are merged rather than double-counted`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock("college", from = at(9), to = at(13)),
                staticBlock("lab", from = at(12), to = at(15)),
            ),
        )
        assertEquals(listOf("09:00-15:00"), map.occupied.render())
        assertEquals(listOf("00:00-09:00", "15:00-24:00"), map.free.render())
        assertEquals(360, map.totalOccupiedMinutes)
    }

    @Test
    fun `a static block fully inside another is absorbed`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock("college", from = at(9), to = at(17)),
                staticBlock("tutorial", from = at(11), to = at(12)),
            ),
        )
        assertEquals(listOf("09:00-17:00"), map.occupied.render())
    }

    @Test
    fun `a partial window occupies only the required duration and frees the rest`() {
        // The worked example from the plan: only 45 minutes needed inside a
        // 2-hour gym window.
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock(
                    "gym",
                    from = at(6),
                    to = at(8),
                    required = Duration.ofMinutes(45),
                ),
            ),
        )
        assertEquals(listOf("06:00-06:45"), map.occupied.render())
        assertEquals(listOf("00:00-06:00", "06:45-24:00"), map.free.render())
        assertEquals(45, map.totalOccupiedMinutes)
        // The 75 minutes left inside the window stay genuinely usable.
        assertEquals(1395, map.totalFreeMinutes)
    }

    @Test
    fun `a partial window anchored LATEST sits at the end of the window`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock(
                    "gym",
                    from = at(6),
                    to = at(8),
                    required = Duration.ofMinutes(45),
                    anchor = WindowAnchor.LATEST,
                ),
            ),
        )
        assertEquals(listOf("07:15-08:00"), map.occupied.render())
        assertEquals(listOf("00:00-07:15", "08:00-24:00"), map.free.render())
    }

    @Test
    fun `a required duration equal to its window occupies the whole window`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock("gym", from = at(6), to = at(8), required = Duration.ofHours(2)),
            ),
        )
        assertEquals(listOf("06:00-08:00"), map.occupied.render())
    }

    @Test
    fun `a required duration longer than its window is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            staticBlock("gym", from = at(6), to = at(8), required = Duration.ofHours(3))
        }
    }

    @Test
    fun `habits occupy their fixed point on the timeline`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            habits = listOf(
                habit("wake", at = at(6, 30), minutes = 10),
                habit("brush", at = at(6, 40), minutes = 5),
            ),
        )
        // Back-to-back habits merge into one occupied span.
        assertEquals(listOf("06:30-06:45"), map.occupied.render())
        assertEquals(listOf("00:00-06:30", "06:45-24:00"), map.free.render())
    }

    @Test
    fun `a habit falling inside a static block does not consume extra time`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("college", from = at(9), to = at(16))),
            habits = listOf(habit("water", at = at(11), minutes = 5)),
        )
        assertEquals(listOf("09:00-16:00"), map.occupied.render())
        assertEquals(420, map.totalOccupiedMinutes)
    }

    @Test
    fun `static blocks and habits combine into a realistic day`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock("college", from = at(9), to = at(16)),
                staticBlock("mess-dinner", from = at(19, 30), to = at(20, 15)),
                staticBlock("gym", from = at(6), to = at(8), required = Duration.ofMinutes(45)),
            ),
            habits = listOf(
                habit("wake", at = at(5, 45), minutes = 15),
                habit("brush-night", at = at(22, 30), minutes = 5),
            ),
        )
        // Wake (05:45-06:00) runs straight into gym (06:00-06:45), so the two
        // merge into a single occupied span rather than leaving a seam.
        assertEquals(
            listOf(
                "00:00-05:45",
                "06:45-09:00",
                "16:00-19:30",
                "20:15-22:30",
                "22:35-24:00",
            ),
            map.free.render(),
        )
    }

    @Test
    fun `entries not scheduled for this weekday are ignored`() {
        // TEST_DATE is a Wednesday.
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock(
                    "sunday-class",
                    from = at(9),
                    to = at(12),
                    days = setOf(DayOfWeek.SUNDAY),
                ),
            ),
            habits = listOf(
                habit("weekend-lie-in", at = at(10), days = setOf(DayOfWeek.SATURDAY)),
            ),
        )
        assertEquals(listOf("00:00-24:00"), map.free.render())
    }

    @Test
    fun `entries scheduled for this weekday are included`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock(
                    "wednesday-lab",
                    from = at(14),
                    to = at(17),
                    days = setOf(DayOfWeek.WEDNESDAY),
                ),
            ),
        )
        assertEquals(listOf("14:00-17:00"), map.occupied.render())
    }

    @Test
    fun `a fully booked day reports no free time`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("all-day", from = at(0), to = at(23, 59))),
            dayEnd = at(23, 59),
        )
        assertEquals(emptyList(), map.free)
        assertEquals(0, map.totalFreeMinutes)
    }

    @Test
    fun `custom day bounds clip entries that fall outside them`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("early", from = at(5), to = at(9))),
            dayStart = at(7),
            dayEnd = at(22),
        )
        assertEquals(listOf("07:00-09:00"), map.occupied.render())
        assertEquals(listOf("09:00-22:00"), map.free.render())
    }

    @Test
    fun `an entry entirely outside the day bounds is dropped`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("dawn", from = at(3), to = at(5))),
            dayStart = at(7),
            dayEnd = at(22),
        )
        assertTrue(map.placements.isEmpty())
        assertEquals(listOf("07:00-22:00"), map.free.render())
    }

    @Test
    fun `inverted day bounds are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            computeFreeTimeMap(TEST_DATE, dayStart = at(20), dayEnd = at(8))
        }
    }

    @Test
    fun `occupied and free tile the day exactly once`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock("college", from = at(9), to = at(16)),
                staticBlock("gym", from = at(18), to = at(20), required = Duration.ofMinutes(50)),
            ),
            habits = listOf(habit("wake", at = at(6), minutes = 20)),
        )
        assertEquals(1440, map.totalFreeMinutes + map.totalOccupiedMinutes)
    }

    @Test
    fun `longest free interval is reported`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("college", from = at(9), to = at(16))),
            dayStart = at(6),
            dayEnd = at(23),
        )
        // 16:00-23:00 is seven hours, longer than 06:00-09:00.
        assertEquals(listOf("16:00-23:00"), listOfNotNull(map.longestFreeInterval()).render())
    }

    @Test
    fun `every placement carries a reason for debuggability`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock("gym", from = at(6), to = at(8), required = Duration.ofMinutes(45)),
            ),
            habits = listOf(habit("wake", at = at(5, 30))),
        )
        assertEquals(2, map.placements.size)
        assertTrue(map.placements.all { it.reason.isNotBlank() })
        assertTrue(
            map.placements.first { it.entry.id == "gym" }.reason.contains("anchored to the start"),
        )
    }

    @Test
    fun `placements are returned in time order`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(
                staticBlock("evening", from = at(19), to = at(20)),
                staticBlock("morning", from = at(9), to = at(10)),
            ),
            habits = listOf(habit("noon-water", at = at(12))),
        )
        assertEquals(
            listOf("morning", "noon-water", "evening"),
            map.placements.map { it.entry.id },
        )
    }

    @Test
    fun `the function is pure and repeatable`() {
        val blocks = listOf(staticBlock("college", from = at(9), to = at(16)))
        val habits = listOf(habit("wake", at = at(6)))
        assertEquals(
            computeFreeTimeMap(TEST_DATE, blocks, habits),
            computeFreeTimeMap(TEST_DATE, blocks, habits),
        )
    }
}
