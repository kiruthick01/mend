package com.mend.app.progress

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Time spent in a foreground app, from Android's usage history. */
class UsageReader(private val context: Context) {

    @Suppress("DEPRECATION")
    fun hasAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** Whole minutes [packageName] was in the foreground since midnight, or null without access. */
    fun minutesToday(packageName: String, today: LocalDate = LocalDate.now()): Int? {
        if (!hasAccess()) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val millis = manager
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
            .filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }
        return (millis / 60_000).toInt()
    }

    @Suppress("DEPRECATION") // the non-deprecated events need API 29; minSdk is 26
    /** Minutes in the foreground per hour of day since midnight (24 entries), or null without access. */
    fun minutesByHourToday(packageName: String, today: LocalDate = LocalDate.now()): List<Int>? {
        if (!hasAccess()) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(start, now)
        val millis = LongArray(24)
        val event = android.app.usage.UsageEvents.Event()
        var openedAt: Long? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> openedAt = event.timeStamp
                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    openedAt?.let { addSpan(millis, it, event.timeStamp, zone) }
                    openedAt = null
                }
            }
        }
        openedAt?.let { addSpan(millis, it, now, zone) }
        return millis.map { (it / 60_000).toInt() }
    }

    private fun addSpan(millis: LongArray, from: Long, to: Long, zone: ZoneId) {
        var cursor = from
        while (cursor < to) {
            val hour = java.time.Instant.ofEpochMilli(cursor).atZone(zone).hour
            val nextHour = java.time.Instant.ofEpochMilli(cursor).atZone(zone)
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val end = minOf(to, nextHour)
            millis[hour] += end - cursor
            cursor = end
        }
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Reads AnkiDroid through its public ContentProvider API. No library needed.
 * Needs AnkiDroid installed and its READ_WRITE_DATABASE permission granted.
 */
class AnkiReader(private val context: Context) {

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(ProgressKind.ANKI.packageName, 0)
    }.isSuccess

    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Deck name to cards due now (learning + review), or null when unreadable. */
    fun deckDue(): Map<String, Int>? {
        if (!isInstalled() || !hasPermission()) return null
        return runCatching {
            val result = linkedMapOf<String, Int>()
            context.contentResolver.query(
                Uri.parse("content://$AUTHORITY/decks"),
                arrayOf("deck_name", "deck_counts"),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val due = ProgressEvidence.ankiDue(c.getString(1)) ?: continue
                    result[c.getString(0)] = due
                }
            }
            result
        }.getOrNull()
    }

    companion object {
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val AUTHORITY = "com.ichi2.anki.flashcards"
    }
}

/**
 * Reads a public Duolingo profile by username. There is no official API: this
 * is the endpoint the website itself uses, it may change without notice, and
 * every failure comes back as null (unknown) rather than as a missed day.
 */
class DuolingoReader {

    suspend fun profile(username: String, today: LocalDate = LocalDate.now()): ProgressEvidence.DuolingoProfile? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(
                    "https://www.duolingo.com/2017-06-30/users?username=" +
                        URLEncoder.encode(username.trim(), "UTF-8"),
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        null
                    } else {
                        ProgressEvidence.duolingoProfile(
                            connection.inputStream.bufferedReader().use { it.readText() },
                            today,
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
}
