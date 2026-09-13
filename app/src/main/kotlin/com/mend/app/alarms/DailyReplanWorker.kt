package com.mend.app.alarms

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mend.app.MendApplication
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Re-arming the day, every day, without the app being opened.
 *
 * Alarms are set for one day at a time. Without something that runs on its own,
 * a phone left alone overnight wakes up with yesterday's alarms spent and
 * today's never set — the app would work perfectly right up until you stopped
 * looking at it.
 *
 * This is deliberately a [CoroutineWorker] rather than more alarms: the daily
 * replan is not time-critical to the minute, and WorkManager survives reboots,
 * app upgrades and process death in a way a chain of self-scheduling alarms
 * does not. Exact alarms are spent on the moments that need a minute; this only
 * needs a morning.
 */
class DailyReplanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MendApplication ?: return Result.failure()

        return try {
            val armed = app.alarmCoordinator.replanToday(LocalDateTime.now())
            Log.d(AlarmScheduler.LOG_TAG, "daily replan armed $armed alarm(s)")
            Result.success()
        } catch (e: Exception) {
            // Retrying costs nothing and the next attempt is backed off; failing
            // outright would leave the day with no alarms at all.
            Log.w(AlarmScheduler.LOG_TAG, "daily replan failed, will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "mend-daily-replan"

        /**
         * Early enough to have armed the day before it starts, late enough that
         * the phone is likely awake and charging overnight has finished.
         */
        private val REPLAN_AT: LocalTime = LocalTime.of(4, 0)

        /**
         * Schedules the daily replan, keeping any existing one.
         *
         * `KEEP` rather than `REPLACE` so opening the app does not reset the
         * schedule each time — repeatedly replacing periodic work is a common
         * way to ensure it never actually runs.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyReplanWorker>(
                Duration.ofDays(1),
                // A generous flex window: this is the opposite of time-critical,
                // and a wide window lets the system batch it with other work
                // instead of waking the device on its own.
                Duration.ofHours(3),
            )
                .setInitialDelay(untilNextReplan(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Milliseconds until the next [REPLAN_AT]. */
        private fun untilNextReplan(now: LocalDateTime = LocalDateTime.now()): Long {
            val today = now.toLocalDate().atTime(REPLAN_AT)
            val next = if (today.isAfter(now)) today else today.plusDays(1)
            return ChronoUnit.MILLIS.between(now, next)
        }
    }
}
