package com.mend.app.progress

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mend.app.MainActivity
import com.mend.app.MendApplication
import com.mend.app.R
import com.mend.app.alarms.MendNotifications
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * The evening check: if a run of practice is about to break, say so once.
 *
 * Runs daily at [ProgressInsights.GUARD_TIME] (the system may delay it a little;
 * this is a nudge, not an alarm). It never speaks inside protected sleep or its
 * wind-down, since a streak is not worth the bedtime this app exists to defend.
 */
class StreakGuardWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MendApplication ?: return Result.failure()
        val links = app.progressLinks.all()
        if (links.isEmpty()) return Result.success()

        val now = LocalDateTime.now()
        runCatching { ProgressSync(app).run(now.toLocalDate(), now) }
        // Re-rank now that the guard hour has arrived, even if the readings did not change.
        app.progressLinks.touch()

        val sleep = app.recoveryRepository.observeSleepBlock().first()
        if (sleep != null &&
            ProgressInsights.inProtectedSleep(now.toLocalTime(), sleep.bedtime, sleep.windDown, sleep.wakeTime)
        ) {
            return Result.success()
        }

        val goals = app.goalRepository.observeGoals().first()
        links.filter { ProgressInsights.streakAtRisk(app.progressLinks.records(it.key), now.toLocalDate()) }
            .forEach { link ->
                val title = goals.firstOrNull { it.goal.id == link.goalId }?.goal?.title ?: return@forEach
                val streak = app.progressLinks.records(link.key)
                    .firstOrNull { it.date == now.toLocalDate() }?.streak
                notify(app, link, title, streak)
            }
        return Result.success()
    }

    private fun notify(app: MendApplication, link: ProgressLink, goalTitle: String, streak: Int?) {
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            app,
            link.key.hashCode(),
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val run = if (streak != null && streak > 0) "$streak-day streak" else "streak"
        val notification = NotificationCompat.Builder(app, MendNotifications.CHANNEL_TASK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${link.kind.label} $run at risk")
            .setContentText("Nothing yet today for \"$goalTitle\". A short session keeps it going.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify("streak-${link.key}".hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    companion object {
        private const val WORK_NAME = "mend-streak-guard"

        fun schedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
            val today = now.toLocalDate().atTime(ProgressInsights.GUARD_TIME)
            val next = if (today.isAfter(now)) today else today.plusDays(1)
            val request = PeriodicWorkRequestBuilder<StreakGuardWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.of(ChronoUnit.MILLIS.between(now, next), ChronoUnit.MILLIS))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
