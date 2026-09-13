package com.mend.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mend.app.alarms.OemGuidance
import com.mend.app.alarms.ReliabilityStatus

/**
 * Whether this phone will actually let Mend arrive on time.
 *
 * Shown plainly rather than buried, because every one of these failures looks
 * the same from outside — nothing happens — and an app whose premise is being
 * worth obeying cannot afford the user to discover a week later that its alarms
 * were being dropped the whole time.
 */
@Composable
fun ReliabilitySection(
    status: ReliabilityStatus,
    onFixNotifications: () -> Unit,
    onFixExactAlarms: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenOemSettings: (Intent) -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Will alarms actually fire?",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )

        if (status.isFullyReliable) {
            Text(
                text = "Everything Android controls is set correctly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            status.problems.forEach {
                Text(
                    text = "• $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!status.notificationsEnabled) {
                OutlinedButton(onClick = onFixNotifications) { Text("Notifications") }
            }
            if (!status.exactAlarmsAllowed) {
                OutlinedButton(onClick = onFixExactAlarms) { Text("Exact alarms") }
            }
        }

        if (!status.ignoringBatteryOptimisation) {
            OutlinedButton(onClick = onFixBattery) { Text("Allow background work") }
        }

        status.oemGuidance?.let { guidance ->
            OemSection(
                guidance = guidance,
                canOpen = guidance.settingsIntent?.let { intent ->
                    intent.resolveActivity(context.packageManager) != null
                } ?: false,
                onOpen = { guidance.settingsIntent?.let(onOpenOemSettings) },
            )
        }
    }
}

/**
 * The manufacturer-specific part.
 *
 * Separated and worded carefully because it is the one thing the app cannot
 * detect, cannot request, and cannot verify — it can only say that this
 * particular phone has a setting of its own, and where to find it. Being
 * straight about that is better than implying the standard Android exemption
 * was enough when on these devices it is not.
 */
@Composable
private fun OemSection(
    guidance: OemGuidance,
    canOpen: Boolean,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${guidance.vendor} needs one more thing",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = guidance.whatHappens,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = guidance.whatToDo,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Mend cannot check or change this itself — there is no API for " +
                "it. If alarms go quiet after a few days, this is almost always why.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canOpen) {
            OutlinedButton(onClick = onOpen) { Text("Open ${guidance.vendor} settings") }
        }
    }
}
