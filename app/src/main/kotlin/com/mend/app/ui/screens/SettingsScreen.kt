package com.mend.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mend.app.MendApplication
import com.mend.app.alarms.AlarmScheduler
import com.mend.app.alarms.ReliabilityStatus
import com.mend.app.alarms.batteryOptimisationIntent
import com.mend.app.alarms.notificationSettingsIntent
import com.mend.app.ui.components.SleepEditorDialog
import com.mend.app.ui.mendViewModel
import kotlin.math.roundToInt

/**
 * Settings: currently the sleep block, and how well it is actually holding.
 *
 * The trend is shown alongside the setting on purpose. A bedtime you have
 * missed by an hour every night for a fortnight is information you should have
 * to look at while you are editing it.
 */
@Composable
fun SettingsScreen() {
    val viewModel = mendViewModel { app ->
        RecoveryViewModel(app.recoveryRepository, app.weightsRepository)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val app = context.applicationContext as MendApplication
    // Read directly rather than through a view model: these are two device-local
    // preferences with no derived state, and a flow for them would be ceremony.
    var hasKey by remember { mutableStateOf(app.aiSettings.hasApiKey) }
    var maskedKey by remember { mutableStateOf(app.aiSettings.maskedKey()) }
    var aiEnabled by remember { mutableStateOf(app.aiSettings.enabled) }

    // Re-read whenever Settings is shown: every one of these can be changed
    // outside the app, and a stale "all good" is worse than no check at all.
    val lifecycleOwner = LocalLifecycleOwner.current
    var reliability by remember {
        mutableStateOf(ReliabilityStatus.of(app, AlarmScheduler(app)))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reliability = ReliabilityStatus.of(app, AlarmScheduler(app))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun launch(intent: Intent?) {
        intent ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(20.dp),
        )
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showEditor = true }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Protected sleep",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = state.sleepBlock?.let { block ->
                    buildString {
                        append("${block.bedtime} to ${block.wakeTime}")
                        append("  ·  ${block.windDown.toMinutes()}m wind-down")
                        append(if (block.hardStop) "  ·  hard stop" else "  ·  nudge only")
                    }
                } ?: "Not set. Tap to protect a bedtime.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        val trend = state.sleepTrend
        if (trend != null && trend.nightsRecorded > 0) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "How it is holding",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${trend.nightsRecorded} night(s) recorded  ·  " +
                        "${(trend.onTargetRate * 100).roundToInt()}% on target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Average ${trend.averageBedtimeDriftMinutes.roundToInt()}m late to " +
                        "bed, ${trend.averageShortfallMinutes.roundToInt()}m short of target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }

        ReliabilitySection(
            status = reliability,
            onFixNotifications = { launch(notificationSettingsIntent(app)) },
            onFixExactAlarms = { launch(app.alarmCoordinator.exactAlarmSettingsIntent()) },
            onFixBattery = { launch(batteryOptimisationIntent(app)) },
            onOpenOemSettings = { launch(it) },
        )
        HorizontalDivider()

        AiSection(
            hasKey = hasKey,
            maskedKey = maskedKey,
            enabled = aiEnabled,
            onSetKey = { key ->
                app.aiSettings.apiKey = key
                hasKey = app.aiSettings.hasApiKey
                maskedKey = app.aiSettings.maskedKey()
            },
            onSetEnabled = {
                app.aiSettings.enabled = it
                aiEnabled = it
            },
        )
        HorizontalDivider()

        ProgressSection()
        HorizontalDivider()

        WeightsSection(
            weights = state.weights,
            history = state.adjustments,
            onOverride = viewModel::overrideWeight,
        )
        HorizontalDivider()

        Text(
            text = "Add the Mend widget from your home screen's widget picker for " +
                "what is happening now and what is next.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }

    if (showEditor) {
        SleepEditorDialog(
            existing = state.sleepBlock,
            onDismiss = { showEditor = false },
            onSave = {
                viewModel.saveSleepBlock(it)
                showEditor = false
            },
            onDelete = state.sleepBlock?.let { block ->
                {
                    viewModel.deleteSleepBlock(block.id)
                    showEditor = false
                }
            },
        )
    }
}
