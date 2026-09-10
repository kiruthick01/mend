package com.mend.app.ui.components

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeParsingTest {

    @Test
    fun `parses a padded time`() {
        assertEquals(LocalTime.of(9, 5), "09:05".toLocalTimeOrNull())
    }

    @Test
    fun `parses an unpadded time`() {
        assertEquals(LocalTime.of(9, 5), "9:05".toLocalTimeOrNull())
    }

    @Test
    fun `parses bare digits`() {
        assertEquals(LocalTime.of(21, 30), "2130".toLocalTimeOrNull())
    }

    @Test
    fun `parses midnight and the last minute of the day`() {
        assertEquals(LocalTime.of(0, 0), "0000".toLocalTimeOrNull())
        assertEquals(LocalTime.of(23, 59), "23:59".toLocalTimeOrNull())
    }

    @Test
    fun `rejects an out-of-range hour`() {
        assertNull("24:00".toLocalTimeOrNull())
        assertNull("99:00".toLocalTimeOrNull())
    }

    @Test
    fun `rejects an out-of-range minute`() {
        assertNull("09:99".toLocalTimeOrNull())
    }

    @Test
    fun `rejects input that is too short or too long`() {
        assertNull("9".toLocalTimeOrNull())
        assertNull("".toLocalTimeOrNull())
        assertNull("091500".toLocalTimeOrNull())
    }

    @Test
    fun `rejects text with no digits`() {
        assertNull("morning".toLocalTimeOrNull())
    }

    @Test
    fun `formats a time with leading zeroes`() {
        assertEquals("09:05", LocalTime.of(9, 5).toDisplay())
        assertEquals("00:00", LocalTime.of(0, 0).toDisplay())
    }

    @Test
    fun `describes a full week as every day`() {
        assertEquals("Every day", describeDays(DayOfWeek.entries.toSet()))
    }

    @Test
    fun `describes the weekday and weekend sets by name`() {
        assertEquals("Weekdays", describeDays(WEEKDAYS))
        assertEquals("Weekends", describeDays(WEEKEND))
    }

    @Test
    fun `describes an arbitrary set by listing it in week order`() {
        val result = describeDays(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY))
        assertEquals(
            listOf(DayOfWeek.MONDAY.shortLabel(), DayOfWeek.FRIDAY.shortLabel()).joinToString(", "),
            result,
        )
    }

    @Test
    fun `describes an empty set explicitly`() {
        assertEquals("No days selected", describeDays(emptySet()))
    }
}
