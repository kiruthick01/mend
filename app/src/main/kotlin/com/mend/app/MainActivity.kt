package com.mend.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mend.app.ui.MendApp

class MainActivity : ComponentActivity() {

    /**
     * Asked for once, on first launch.
     *
     * Notifications are how the schedule reaches you when the app is closed,
     * which is most of the time — without them Mend is reduced to something you
     * have to remember to open, which is the problem it exists to solve. The
     * result is not acted on here: Settings reports the real state and offers a
     * way back, so a refusal is recoverable rather than permanent.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askForNotificationsIfNeeded()
        setContent {
            MendApp()
        }
    }

    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
