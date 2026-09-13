package com.mend.app.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mend.engine.alarms.ScheduledAlarm
import java.time.ZoneId

/**
 * Arming alarms with the platform, and being honest when it will not let us.
 *
 * Android has spent a decade making it harder to run at a specific minute, for
 * good reasons that are nonetheless exactly at odds with an app whose entire
 * value is arriving when it said it would. Three different mechanisms are used
 * here depending on how much the alarm matters:
 *
 *  - **`setAlarmClock`** for anything critical. It is the only API that is
 *    genuinely exempt from Doze, and the only one the system will not defer.
 *    The cost is a visible alarm icon in the status bar, which is a fair
 *    trade for the one alarm that must not be missed.
 *  - **`setExactAndAllowWhileIdle`** for ordinary task starts. Exact, wakes
 *    from Doze, but rate-limited by the system to roughly once every nine
 *    minutes per app — fine for something that fires a handful of times a day.
 *  - **`setWindow`** when exact alarms are not permitted at all, so the app
 *    degrades to approximately-right rather than silent.
 *
 * Which one was used is recorded, because "the alarm did not fire" is a
 * question that has to be answerable afterwards.
 */
class AlarmScheduler(private val context: Context) {

    private val manager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /**
     * Whether the system will honour an exact alarm request.
     *
     * From Android 12 this is a user-grantable permission that can also be
     * revoked at any time, so it is checked at the point of use rather than
     * cached.
     */
    fun canScheduleExact(): Boolean = when {
        manager == null -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> manager.canScheduleExactAlarms()
        else -> true
    }

    /** The Settings screen where exact alarms are granted, if one exists. */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

    /** Arms one alarm, replacing any previous one with the same id. */
    fun arm(alarm: ScheduledAlarm) {
        val manager = manager ?: return
        val triggerAt = alarm.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // FLAG_UPDATE_CURRENT always creates, so this is never null here;
        // FLAG_NO_CREATE in cancel() is the case that legitimately returns one.
        val operation = pendingIntent(alarm, PendingIntent.FLAG_UPDATE_CURRENT) ?: return

        val method = when {
            // The one that must not be missed gets the one API that cannot be
            // deferred. The status bar icon is the price of that guarantee.
            alarm.isCritical && canScheduleExact() -> {
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showIntent()),
                    operation,
                )
                "setAlarmClock"
            }

            canScheduleExact() -> {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation,
                )
                "setExactAndAllowWhileIdle"
            }

            // Exact alarms refused. Approximately right beats silent, and the
            // UI tells the user their alarms are inexact.
            else -> {
                manager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    INEXACT_WINDOW_MS,
                    operation,
                )
                "setWindow (inexact — exact alarms not permitted)"
            }
        }

        Log.d(LOG_TAG, "armed ${alarm.id} for ${alarm.at} via $method")
    }

    fun cancel(alarm: ScheduledAlarm) {
        val operation = pendingIntent(alarm, PendingIntent.FLAG_NO_CREATE) ?: return
        manager?.cancel(operation)
        operation.cancel()
        Log.d(LOG_TAG, "cancelled ${alarm.id}")
    }

    fun armAll(alarms: List<ScheduledAlarm>) = alarms.forEach(::arm)

    fun cancelAll(alarms: List<ScheduledAlarm>) = alarms.forEach(::cancel)

    private fun pendingIntent(alarm: ScheduledAlarm, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            AlarmReceiver.intentFor(context, alarm),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Where tapping the status-bar alarm icon takes the user. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, com.mend.app.MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val LOG_TAG = "MendAlarms"

        /** How far an inexact alarm may drift when exact ones are refused. */
        private const val INEXACT_WINDOW_MS = 5 * 60 * 1000L
    }
}
