package com.mend.app.progress

import java.time.LocalDate
import org.json.JSONObject

/**
 * Pure rules for turning raw readings from outside apps into "did the work".
 * Kept free of Android so they are unit-testable.
 */
object ProgressEvidence {

    /** Minutes in the app that count as having practised, when no account is linked. */
    const val USAGE_THRESHOLD_MINUTES = 10

    /** What a public Duolingo profile says. */
    data class DuolingoProfile(val practicedToday: Boolean, val streak: Int, val totalXp: Int?)

    /**
     * Reads the public profile payload. Null when it cannot be read, so a
     * changed endpoint shows as unknown rather than as a missed day.
     */
    fun duolingoProfile(profileJson: String, today: LocalDate): DuolingoProfile? = runCatching {
        val users = JSONObject(profileJson).optJSONArray("users") ?: return null
        val user = users.optJSONObject(0) ?: return null
        val end = user.optJSONObject("streakData")
            ?.optJSONObject("currentStreak")
            ?.optString("endDate")
            ?.takeIf { it.isNotBlank() }
        DuolingoProfile(
            practicedToday = end != null && LocalDate.parse(end) == today,
            streak = user.optInt("streak", 0),
            totalXp = if (user.has("totalXp")) user.optInt("totalXp") else null,
        )
    }.getOrNull()

    /** True when the streak was extended on [today]; null when unreadable. */
    fun duolingoPracticedOn(profileJson: String, today: LocalDate): Boolean? =
        duolingoProfile(profileJson, today)?.practicedToday

    /**
     * Anki is finished for the day when cards were due earlier today and none are now.
     * A deck that was never due proves nothing, so it is not counted.
     */
    fun ankiFinished(baselineDue: Int, dueNow: Int): Boolean = baselineDue > 0 && dueNow == 0

    /** The baseline only ever grows within a day; reviewing lowers [dueNow], not it. */
    fun nextBaseline(baselineDue: Int, dueNow: Int): Int = maxOf(baselineDue, dueNow)

    fun usageCounts(minutesToday: Int): Boolean = minutesToday >= USAGE_THRESHOLD_MINUTES

    /** AnkiDroid reports `[learning, review, new]`; learning plus review is what is due. */
    fun ankiDue(countsJson: String): Int? = runCatching {
        val a = org.json.JSONArray(countsJson)
        a.getInt(0) + a.getInt(1)
    }.getOrNull()
}
