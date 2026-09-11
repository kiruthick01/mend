package com.mend.app.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

        WeightsSection(
            weights = state.weights,
            history = state.adjustments,
            onOverride = viewModel::overrideWeight,
        )
        HorizontalDivider()

        Text(
            text = "Notifications, alarms and widgets arrive in a later phase.",
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
