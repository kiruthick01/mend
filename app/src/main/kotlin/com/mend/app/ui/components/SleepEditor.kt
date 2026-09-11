package com.mend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mend.engine.model.ProtectedRecovery
import java.time.Duration
import java.util.UUID

/**
 * The bedtime block.
 *
 * Deliberately worded as a boundary rather than a reminder. Fixing chronic
 * late-night work is one of this project's stated goals, so the copy here
 * commits to the block winning arguments with outstanding work — because that
 * is what the code actually does.
 */
@Composable
fun SleepEditorDialog(
    existing: ProtectedRecovery?,
    onDismiss: () -> Unit,
    onSave: (ProtectedRecovery) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var bedtime by remember { mutableStateOf(existing?.bedtime?.toDisplay() ?: "22:30") }
    var wake by remember { mutableStateOf(existing?.wakeTime?.toDisplay() ?: "06:30") }
    var windDown by remember {
        mutableStateOf((existing?.windDown?.toMinutes() ?: 30).toString())
    }
    var hardStop by remember { mutableStateOf(existing?.hardStop ?: true) }

    val parsedBedtime = bedtime.toLocalTimeOrNull()
    val parsedWake = wake.toLocalTimeOrNull()
    val parsedWindDown = windDown.toLongOrNull()

    val error: String? = when {
        parsedBedtime == null -> "Bedtime must look like 22:30."
        parsedWake == null -> "Wake time must look like 06:30."
        parsedBedtime == parsedWake -> "Bedtime and wake time cannot be the same."
        parsedWindDown == null || parsedWindDown < 0 -> "Wind-down must be zero or more minutes."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Protect your sleep" else "Edit sleep block") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Sleep is not a habit that gets skipped when the day is " +
                        "full. It is the boundary the day gets cut down for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = bedtime,
                        onValueChange = { bedtime = it },
                        label = { Text("Bedtime") },
                        isError = parsedBedtime == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = wake,
                        onValueChange = { wake = it },
                        label = { Text("Wake") },
                        isError = parsedWake == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = windDown,
                    onValueChange = { windDown = it.filter(Char::isDigit) },
                    label = { Text("Wind-down (min)") },
                    supportingText = {
                        Text(
                            "Work stops this long before bedtime. Work still running " +
                                "when the block opens has already cost you sleep.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hard stop", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (hardStop) {
                                "Overrunning work is deferred to tomorrow."
                            } else {
                                "You get told, and the decision stays yours."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = hardStop, onCheckedChange = { hardStop = it })
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null,
                onClick = {
                    onSave(
                        ProtectedRecovery(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            title = existing?.title ?: "Sleep",
                            bedtime = parsedBedtime!!,
                            wakeTime = parsedWake!!,
                            windDown = Duration.ofMinutes(parsedWindDown!!),
                            hardStop = hardStop,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
