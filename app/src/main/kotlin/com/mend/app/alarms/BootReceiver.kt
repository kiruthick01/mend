package com.mend.app.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mend.app.MendApplication
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Putting the day's alarms back after a reboot.
 *
 * Alarms do not survive one — everything armed before the phone went down is
 * simply gone, silently. An app that only works until the next restart is not a
 * scheduler, so this re-plans the whole day from scratch.
 *
 * Also handles the package-replaced broadcast, because an app update clears
 * alarms the same way a reboot does.
 *
 * `LOCKED_BOOT_COMPLETED` is deliberately **not** handled. It arrives before the
 * user has unlocked the phone, and the database lives in credential-encrypted
 * storage, which is not readable until they do — so there would be nothing to
 * plan from. Receiving it at all would also require the receiver to be
 * direct-boot aware, which in turn would mean moving storage, and that is a
 * much larger decision than this phase should be making. The consequence is
 * that alarms come back at first unlock rather than at power-on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action in setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
        if (!relevant) return

        val app = context.applicationContext as? MendApplication ?: return
        Log.d(AlarmScheduler.LOG_TAG, "re-arming after ${intent.action}")

        // goAsync keeps the receiver alive past onReceive; re-planning reads the
        // database, which is not something to do on the main thread.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MendNotifications.ensureChannels(context)
                app.alarmCoordinator.rearmAfterBoot(LocalDateTime.now())
                DailyReplanWorker.schedule(context)
            } catch (e: Exception) {
                Log.e(AlarmScheduler.LOG_TAG, "failed to re-arm after boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}
