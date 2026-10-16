package com.mend.app.progress

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject

/** What one link looked like on one day. Written by every sync, newest wins per field. */
data class DayRecord(
    val date: LocalDate,
    val key: String,
    val practiced: Boolean? = null,
    val minutes: Int? = null,
    val due: Int? = null,
    val streak: Int? = null,
    val xp: Int? = null,
    /** Minutes in the app per hour of day (24 entries), from usage history. */
    val hours: List<Int>? = null,
) {
    /** Combines a newer reading into this one without losing what the newer one lacks. */
    fun merge(newer: DayRecord): DayRecord = DayRecord(
        date = date,
        key = key,
        // Practice never un-happens within a day.
        practiced = if (practiced == true || newer.practiced == true) true else newer.practiced ?: practiced,
        minutes = newer.minutes ?: minutes,
        due = newer.due ?: due,
        streak = newer.streak ?: streak,
        xp = newer.xp ?: xp,
        hours = newer.hours ?: hours,
    )

    internal fun toJson(): JSONObject = JSONObject()
        .put("date", date.toString())
        .put("key", key)
        .put("practiced", practiced)
        .put("minutes", minutes)
        .put("due", due)
        .put("streak", streak)
        .put("xp", xp)
        .put("hours", hours?.let { JSONArray(it) })

    internal companion object {
        fun fromJson(o: JSONObject) = DayRecord(
            date = LocalDate.parse(o.getString("date")),
            key = o.getString("key"),
            practiced = if (o.isNull("practiced")) null else o.getBoolean("practiced"),
            minutes = if (o.isNull("minutes")) null else o.getInt("minutes"),
            due = if (o.isNull("due")) null else o.getInt("due"),
            streak = if (o.isNull("streak")) null else o.getInt("streak"),
            xp = if (o.isNull("xp")) null else o.getInt("xp"),
            hours = o.optJSONArray("hours")?.let { a -> (0 until a.length()).map { a.getInt(it) } },
        )
    }
}

/**
 * Pure rules that turn recorded days into scheduling decisions and
 * suggestions. No Android here, so all of it is unit-tested.
 */
object ProgressInsights {

    /** Seconds a single due card is assumed to take. */
    const val SECONDS_PER_CARD = 8
    private const val MIN_REVIEW_MINUTES = 5L
    private const val MAX_REVIEW_MINUTES = 60L

    /** Days of practice needed before Mend trusts a learned duration or a peak hour. */
    const val MIN_SAMPLES = 3

    /** A Duolingo or Anki day is "at risk" from this hour if nothing has been done yet. */
    val GUARD_TIME: LocalTime = LocalTime.of(20, 0)

    /** Review time for [due] cards: 8s each, rounded up to 5 minutes, within 5..60. */
    fun ankiReviewDuration(due: Int): Duration {
        val minutes = Math.ceil(due * SECONDS_PER_CARD / 60.0).toLong()
        val rounded = (minutes + 4) / 5 * 5
        return Duration.ofMinutes(rounded.coerceIn(MIN_REVIEW_MINUTES, MAX_REVIEW_MINUTES))
    }

    /**
     * Median minutes on days practice actually happened, rounded to 5, or null
     * when there is too little history to say. Uses the median so one long
     * binge does not inflate every future estimate.
     */
    fun typicalDuration(records: List<DayRecord>): Duration? {
        val samples = records.filter { it.practiced == true }.mapNotNull { it.minutes }
            .filter { it > 0 }.sorted()
        if (samples.size < MIN_SAMPLES) return null
        val median = samples[samples.size / 2]
        val rounded = (median + 2) / 5 * 5
        return Duration.ofMinutes(rounded.coerceIn(5, 120).toLong())
    }

    /**
     * True when a run of practice is in danger: it happened yesterday (or a
     * streak is live) and has not happened yet today.
     */
    fun streakAtRisk(records: List<DayRecord>, today: LocalDate): Boolean {
        val todayRecord = records.firstOrNull { it.date == today }
        if (todayRecord?.practiced == true) return false
        val yesterdayDone = records.any { it.date == today.minusDays(1) && it.practiced == true }
        val liveStreak = (todayRecord?.streak ?: 0) > 0
        return yesterdayDone || liveStreak
    }

    /**
     * True from the start of wind-down until wake time. The block usually spans
     * midnight (22:30 to 06:30), so "inside" is either side of it.
     */
    fun inProtectedSleep(now: LocalTime, bedtime: LocalTime, windDown: Duration, wake: LocalTime): Boolean {
        val start = bedtime.minus(windDown)
        return if (start <= wake) now >= start && now < wake else now >= start || now < wake
    }

    /** One short status line for a link today, e.g. "Duolingo · 12-day streak · 18 min". */
    fun summaryLine(kind: ProgressKind, records: List<DayRecord>, today: LocalDate): String {
        val todayRecord = records.firstOrNull { it.date == today }
        val streak = todayRecord?.streak?.takeIf { it > 0 }
            ?: recordedStreak(records, today).takeIf { it > 0 }
        return buildList {
            add(kind.label)
            streak?.let { add("$it-day streak") }
            todayRecord?.due?.let { add(if (todayRecord.practiced == true) "cleared" else "$it due") }
            todayRecord?.minutes?.takeIf { it > 0 }?.let { add("$it min") }
            if (todayRecord?.practiced == true && todayRecord.due == null) add("done today")
        }.joinToString(" · ")
    }

    /** The last [days] days oldest-first, true where practice happened. */
    fun lastDays(records: List<DayRecord>, today: LocalDate, days: Int = 7): List<Boolean> =
        (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            records.any { it.date == date && it.practiced == true }
        }

    /** Consecutive practised days ending today or yesterday, from recorded days. */
    fun recordedStreak(records: List<DayRecord>, today: LocalDate): Int {
        val done = records.filter { it.practiced == true }.map { it.date }.toSet()
        var day = if (today in done) today else today.minusDays(1)
        var count = 0
        while (day in done) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /** XP gained over the recorded days in the window, or null without two readings. */
    fun xpGained(records: List<DayRecord>, today: LocalDate, days: Int = 7): Int? {
        val readings = records.filter { it.xp != null && it.date > today.minusDays(days.toLong()) }
            .sortedBy { it.date }
        if (readings.size < 2) return null
        return readings.last().xp!! - readings.first().xp!!
    }

    /**
     * The two-hour window in which the app is used most, from per-hour minutes
     * over recent days. Null until there are enough days and enough minutes
     * for the pattern to mean something.
     */
    fun peakWindow(records: List<DayRecord>, today: LocalDate, lookbackDays: Int = 14): Pair<LocalTime, LocalTime>? {
        val recent = records.filter { it.hours != null && it.date > today.minusDays(lookbackDays.toLong()) }
        if (recent.size < MIN_SAMPLES) return null
        val totals = IntArray(24) { hour -> recent.sumOf { it.hours!!.getOrElse(hour) { 0 } } }
        if (totals.sum() < 30) return null
        // Windows are kept inside one day (start 0..22), matching the goal work-window rule.
        val start = (0..22).maxBy { totals[it] + totals[it + 1] }
        val end = if (start + 2 >= 24) LocalTime.of(23, 59) else LocalTime.of(start + 2, 0)
        return LocalTime.of(start, 0) to end
    }
}
