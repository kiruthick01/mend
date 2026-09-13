package com.mend.app.alarms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mend.app.MainActivity
import com.mend.app.MendApplication
import com.mend.app.R
import com.mend.app.repository.NowNext

/**
 * The schedule, on the lock screen.
 *
 * Android has had no real lock screen widget since API 21 — `widgetCategory`
 * still accepts `keyguard`, but nothing honours it, and the phones this is
 * built for certainly do not. A persistent notification is what a lock screen
 * widget became, so that is what this is: the same current/next content the
 * home screen widget shows, on a silent low-importance channel, marked
 * public so it is readable without unlocking.
 *
 * It is deliberately not a foreground service. Holding one all day to draw a
 * line of text would be exactly the battery liability this phase is supposed
 * to avoid, and would give every OEM battery manager a reason to kill the app
 * that matters far more than the note does.
 */
object OngoingNote {

    private const val NOTE_ID = 424242

    /**
     * Redraws the note, or removes it when there is nothing to say.
     *
     * Called after anything that can change the day: a replan, an alarm
     * firing, a completion. Cheap enough to call speculatively.
     */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext as? MendApplication ?: return
        show(context, NowNext.read(app))
    }

    fun show(context: Context, state: NowNext) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        // Nothing scheduled is not worth a permanent line on the lock screen.
        if (state.isEmpty) {
            manager.cancel(NOTE_ID)
            return
        }

        val title = state.currentTitle ?: "Nothing running"
        val body = listOfNotNull(state.remainingLine(), state.nextLine())
            .joinToString("\n")

        val builder = NotificationCompat.Builder(context, MendNotifications.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            // Readable on the lock screen without unlocking. The content is a
            // task title and a time, which is the whole point of it being there.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        state.currentTaskId?.let { taskId ->
            builder.addAction(
                R.drawable.ic_notification,
                "Done",
                DoneReceiver.pendingIntent(context, taskId, NOTE_ID),
            )
        }

        try {
            manager.notify(NOTE_ID, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTE_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        NOTE_ID,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
