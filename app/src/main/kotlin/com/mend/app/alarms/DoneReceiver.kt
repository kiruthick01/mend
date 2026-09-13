package com.mend.app.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.mend.app.MendApplication
import com.mend.app.repository.TaskCompletion
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ticking something off without opening the app.
 *
 * Both outside surfaces — the widget button and the notification action — land
 * here. Marking work done is a database write, which a broadcast receiver
 * cannot do on its main thread, so the result is held open with `goAsync` while
 * a coroutine does the work. Everything downstream of the write is refreshed
 * afterwards, because a done button that leaves the same task on screen reads
 * as a button that did not work.
 */
class DoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val app = context.applicationContext as? MendApplication ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val done = TaskCompletion.complete(app, taskId, LocalDateTime.now())
                Log.d(
                    AlarmScheduler.LOG_TAG,
                    if (done) "marked $taskId done" else "nothing pending for $taskId",
                )

                // The tap came from a notification, so it has served its purpose
                // either way — including when the task was already gone.
                if (notificationId >= 0) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }

                // Replanning refreshes the widget and the lock-screen note too,
                // so the day and both surfaces move together.
                app.alarmCoordinator.replanToday(LocalDateTime.now())
            } catch (e: Exception) {
                Log.w(AlarmScheduler.LOG_TAG, "failed to mark $taskId done", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_NOTIFICATION_ID = "notificationId"

        /**
         * A done button for one task.
         *
         * The action string carries the task id for the same reason
         * [AlarmReceiver] does: without it, PendingIntent would treat two done
         * buttons for two different tasks as the same operation and the second
         * would silently complete the first.
         */
        fun pendingIntent(
            context: Context,
            taskId: String,
            notificationId: Int,
        ): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            Intent(context, DoneReceiver::class.java).apply {
                action = "com.mend.app.DONE.$taskId"
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            },
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
