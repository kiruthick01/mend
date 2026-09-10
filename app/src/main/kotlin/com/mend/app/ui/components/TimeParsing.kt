package com.mend.app.ui.components

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Plain formatting and parsing helpers for the static-layer editors.
 *
 * Kept free of Compose imports so they can be unit tested on the JVM without
 * an Android runtime — these are exactly the bits where an off-by-one is easy
 * and silent.
 */

internal fun LocalTime.toDisplay(): String = "%02d:%02d".format(hour, minute)

/**
 * Parses "9:05", "09:05" or "0905" into a time, returning null for anything
 * else. Deliberately permissive about the separator and strict about the
 * result, so typing stays forgiving but a bad value never reaches the engine.
 */
internal fun String.toLocalTimeOrNull(): LocalTime? {
    val digits = filter(Char::isDigit)
    if (digits.length !in 3..4) return null
    val padded = digits.padStart(4, '0')
    val hour = padded.substring(0, 2).toIntOrNull() ?: return null
    val minute = padded.substring(2, 4).toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

/** "Every day", "Weekdays", or an explicit list, for a list row subtitle. */
fun describeDays(days: Set<DayOfWeek>): String = when {
    days.size == 7 -> "Every day"
    days.isEmpty() -> "No days selected"
    days == WEEKDAYS -> "Weekdays"
    days == WEEKEND -> "Weekends"
    else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { it.shortLabel() }
}

internal val WEEKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

internal val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

internal fun DayOfWeek.shortLabel(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())
