package com.mend.app.alarms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mend.app.MainActivity
import com.mend.app.R
import com.mend.engine.alarms.AlarmCriticality
import com.mend.engine.alarms.AlarmKind
import com.mend.engine.alarms.ScheduledAlarm

/**
 * Notification channels, split by how much each kind of alarm may intrude.
 *
 * The split exists because the user can silence one without silencing the
 * others, and because Android and OEM battery layers treat a high-importance
 * channel differently from a low one. Putting the bedtime hard stop on the same
 * channel as a routine task start would mean muting the second inevitably mutes
 * the first — and the first is the one this project exists for.
 *
 * Channel importance is fixed at creation and cannot be raised later, so these
 * are deliberately set high enough on day one. A user who finds them intrusive
 * can lower them; an app that starts too quiet can never recover.
 */
object MendNotifications {

    /** The bedtime hard stop and genuine emergencies. Intrudes on purpose. */
    const val CHANNEL_CRITICAL = "mend_critical"

    /** Work is starting. Visible, audible, not insistent. */
    const val CHANNEL_TASK = "mend_task"

    /** Ambient: the day's shape, wind-down warnings. Silent. */
    const val CHANNEL_AMBIENT = "mend_ambient"

    /** The ongoing "what now" notification, which is the lock-screen surface. */
    const val CHANNEL_ONGOING = "mend_ongoing"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CRITICAL,
                "Bedtime and emergencies",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "The bedtime hard stop, and work that has genuinely " +
                    "become urgent. These are the ones worth being interrupted by."
                enableVibration(true)
                setBypassDnd(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TASK,
                "Scheduled work",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "When a piece of scheduled work is due to start."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AMBIENT,
                "Reminders",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Wind-down warnings and other quiet nudges."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Current task",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "A persistent note showing what is happening now and " +
                    "what is next. This is what appears on your lock screen."
                setShowBadge(false)
            },
        )
    }

    /** Which channel an alarm belongs on. */
    fun channelFor(alarm: ScheduledAlarm): String = when (alarm.criticality) {
        AlarmCriticality.CRITICAL -> CHANNEL_CRITICAL
        AlarmCriticality.NORMAL ->
            if (alarm.kind == AlarmKind.TASK_START) CHANNEL_TASK else CHANNEL_AMBIENT
        AlarmCriticality.LOW -> CHANNEL_AMBIENT
    }

    /**
     * Shows one alarm.
     *
     * Returns false when notifications are not permitted, so the caller can say
     * so rather than silently doing nothing — an app that appears to be working
     * while its notifications are blocked is worse than one that admits it.
     */
    fun show(context: Context, alarm: ScheduledAlarm): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        val open = PendingIntent.getActivity(
            context,
            alarm.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, channelFor(alarm))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alarm.title)
            .setContentText(alarm.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alarm.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(
                if (alarm.isCritical) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_REMINDER
                },
            )
            .setPriority(
                if (alarm.isCritical) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )

        // Work that is starting can be ticked off from the notification itself.
        // The bedtime hard stop deliberately gets no such button: it is not a
        // task, and there is nothing about it to complete.
        if (alarm.kind == AlarmKind.TASK_START || alarm.kind == AlarmKind.INTERRUPT) {
            alarm.taskId?.let { taskId ->
                builder.addAction(
                    R.drawable.ic_notification,
                    "Done",
                    DoneReceiver.pendingIntent(context, taskId, alarm.id.hashCode()),
                )
            }
        }

        return try {
            manager.notify(alarm.id.hashCode(), builder.build())
            true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
            false
        }
    }

    /** Whether the user has allowed notifications at all. */
    fun areEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
