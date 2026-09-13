package com.mend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mend.engine.progress.TrackPreview
import com.mend.engine.progress.TrackSpec
import java.time.Duration

/**
 * Setting up a goal made of several parallel strands.
 *
 * For a goal like Japanese, where grammar, vocabulary, reading and listening
 * run alongside each other in different volumes. Entering a hundred sub-tasks
 * one dialog at a time is not a realistic thing to ask of anyone; entering four
 * counts and four totals is.
 *
 * The arithmetic is shown before anything is created — "Grammar — 30 × 40m
 * (20h)" — because a mistyped total becomes a hundred wrongly-estimated
 * sub-tasks, and that is much easier to prevent than to undo.
 *
 * What it produces is ordinary lessons and sub-tasks. Nothing links back here,
 * so every one can be renamed, re-estimated or deleted afterwards.
 */
@Composable
fun TrackSetupDialog(
    goalTitle: String,
    onDismiss: () -> Unit,
    onCreate: (List<TrackSpec>) -> Unit,
) {
    val rows = remember {
        mutableStateListOf(
            TrackRow("grammar", "30", "20"),
            TrackRow("vocab", "40", "10"),
            TrackRow("reading", "15", "8"),
            TrackRow("listening", "15", "7"),
        )
    }

    val specs = rows.mapNotNull(TrackRow::toSpecOrNull)
    val preview = TrackPreview(specs)

    val error: String? = when {
        rows.any { it.name.isBlank() } -> "Every track needs a name."
        specs.size != rows.size -> "Counts and hours must be positive numbers."
        specs.isEmpty() -> "Add at least one track."
        preview.isTooLarge -> "That is ${preview.totalItems} pieces of work — too many to " +
            "create at once. Reduce the counts, or split this into separate goals."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Break down $goalTitle") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Each track becomes one lesson holding its own work, and " +
                        "pace is tracked separately for each. Roughly is fine — you " +
                        "can rename, re-estimate or delete any of it afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                rows.forEachIndexed { index, row ->
                    TrackRowEditor(
                        row = row,
                        canRemove = rows.size > 1,
                        onChange = { rows[index] = it },
                        onRemove = { rows.removeAt(index) },
                    )
                }

                TextButton(onClick = { rows.add(TrackRow("", "10", "5")) }) {
                    Text("Add a track")
                }

                if (specs.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "This will create:",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        preview.lines().forEach {
                            Text(text = it, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = preview.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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
                onClick = { onCreate(specs) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Skip") } },
    )
}

@Composable
private fun TrackRowEditor(
    row: TrackRow,
    canRemove: Boolean,
    onChange: (TrackRow) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = row.name,
            onValueChange = { onChange(row.copy(name = it)) },
            label = { Text("Track") },
            singleLine = true,
            modifier = Modifier.weight(1.4f),
        )
        OutlinedTextField(
            value = row.count,
            onValueChange = { onChange(row.copy(count = it.filter(Char::isDigit))) },
            label = { Text("Count") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = row.hours,
            onValueChange = { onChange(row.copy(hours = it.filter(Char::isDigit))) },
            label = { Text("Hrs") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(0.7f),
        )
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${row.name}")
            }
        }
    }
}

/** One row of the form, before it is known to be valid. */
data class TrackRow(
    val name: String,
    val count: String,
    val hours: String,
) {
    fun toSpecOrNull(): TrackSpec? {
        val n = count.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val h = hours.toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (name.isBlank()) return null
        return TrackSpec(
            name = name.trim(),
            count = n,
            totalTime = Duration.ofHours(h.toLong()),
        )
    }
}
