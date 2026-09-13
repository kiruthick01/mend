package com.mend.app.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.mend.app.widget.MendWidget
import com.mend.engine.alarms.AlarmCriticality
import com.mend.engine.alarms.AlarmKind
import com.mend.engine.alarms.ScheduledAlarm
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Where an alarm actually lands.
 *
 * Everything needed to show the notification is carried in the intent rather
 * than looked up, because this runs in a process that may have just been
 * started from nothing — the app was killed hours ago, the database is cold,
 * and a broadcast receiver has seconds to finish. Reading the alarm's own
 * payload is the difference between firing reliably and firing usually.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarm = fromIntent(intent) ?: run {
            Log.w(AlarmScheduler.LOG_TAG, "received an alarm with no readable payload")
            return
        }

        Log.d(AlarmScheduler.LOG_TAG, "fired ${alarm.id} at ${LocalDateTime.now()}")

        // Channels are created on app start, but this may be the first thing to
        // run after a reboot, before anything else has.
        MendNotifications.ensureChannels(context)

        val shown = MendNotifications.show(context, alarm)
        if (!shown) {
            Log.w(
                AlarmScheduler.LOG_TAG,
                "alarm ${alarm.id} fired but notifications are not permitted",
            )
        }

        // An alarm firing is precisely the moment "now" changed, so the two
        // surfaces that show what is happening now are redrawn. Held open with
        // goAsync because both read the database, which this thread may not do.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MendWidget().updateAll(context)
                OngoingNote.refresh(context)
            } catch (e: Exception) {
                Log.w(AlarmScheduler.LOG_TAG, "could not refresh after ${alarm.id}", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_AT = "at"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_CRITICALITY = "criticality"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_TASK_ID = "taskId"

        fun intentFor(context: Context, alarm: ScheduledAlarm): Intent =
            Intent(context, AlarmReceiver::class.java).apply {
                // A distinct action per alarm, so PendingIntent.getBroadcast
                // treats them as different operations. Without it every alarm
                // would overwrite the last one.
                action = "com.mend.app.ALARM.${alarm.id}"
                putExtra(EXTRA_ID, alarm.id)
                putExtra(EXTRA_AT, alarm.at.toString())
                putExtra(EXTRA_KIND, alarm.kind.name)
                putExtra(EXTRA_CRITICALITY, alarm.criticality.name)
                putExtra(EXTRA_TITLE, alarm.title)
                putExtra(EXTRA_BODY, alarm.body)
                putExtra(EXTRA_TASK_ID, alarm.taskId)
            }

        fun fromIntent(intent: Intent): ScheduledAlarm? {
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            val at = intent.getStringExtra(EXTRA_AT) ?: return null

            return runCatching {
                ScheduledAlarm(
                    id = id,
                    at = LocalDateTime.parse(at),
                    kind = AlarmKind.valueOf(
                        intent.getStringExtra(EXTRA_KIND) ?: AlarmKind.TASK_START.name,
                    ),
                    criticality = AlarmCriticality.valueOf(
                        intent.getStringExtra(EXTRA_CRITICALITY)
                            ?: AlarmCriticality.NORMAL.name,
                    ),
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
                    taskId = intent.getStringExtra(EXTRA_TASK_ID),
                )
            }.getOrNull()
        }
    }
}
