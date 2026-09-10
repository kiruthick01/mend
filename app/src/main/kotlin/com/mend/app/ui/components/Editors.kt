package com.mend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mend.engine.model.Habit
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.WindowAnchor
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.util.UUID

/**
 * Editors for the static layer.
 *
 * Validation is done here rather than left to the domain constructors, because
 * a half-typed form is a normal state for a user to be in and must not throw.
 * The domain types keep their `require` checks as the last line of defence for
 * everything that is not a text field.
 */

@Composable
fun StaticBlockEditorDialog(
    existing: StaticBlock?,
    onDismiss: () -> Unit,
    onSave: (StaticBlock) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var start by remember { mutableStateOf(existing?.windowStart?.toDisplay() ?: "09:00") }
    var end by remember { mutableStateOf(existing?.windowEnd?.toDisplay() ?: "16:00") }
    var partialWindow by remember { mutableStateOf(existing?.isPartialWindow ?: false) }
    var requiredMinutes by remember {
        mutableStateOf(existing?.requiredDuration?.toMinutes()?.toString() ?: "45")
    }
    var anchor by remember { mutableStateOf(existing?.anchor ?: WindowAnchor.EARLIEST) }
    var days by remember { mutableStateOf(existing?.daysOfWeek ?: StaticBlock.ALL_DAYS) }

    val parsedStart = start.toLocalTimeOrNull()
    val parsedEnd = end.toLocalTimeOrNull()
    val parsedRequired = requiredMinutes.toLongOrNull()

    val error: String? = when {
        title.isBlank() -> "Give the block a name."
        parsedStart == null -> "Start time must look like 09:00."
        parsedEnd == null -> "End time must look like 16:00."
        parsedEnd <= parsedStart -> "End time must be after the start time."
        days.isEmpty() -> "Pick at least one day."
        partialWindow && (parsedRequired == null || parsedRequired <= 0) ->
            "Required minutes must be a positive number."
        partialWindow && parsedRequired!! > Duration.between(parsedStart, parsedEnd).toMinutes() ->
            "Required minutes cannot exceed the window itself."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New fixed block" else "Edit fixed block") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    placeholder = { Text("College") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField(
                        value = start,
                        onValueChange = { start = it },
                        label = "Window opens",
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        value = end,
                        onValueChange = { end = it },
                        label = "Window closes",
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Only part of the window", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "For things like a 2-hour gym window you only need " +
                                "45 minutes of.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = partialWindow, onCheckedChange = { partialWindow = it })
                }

                if (partialWindow) {
                    OutlinedTextField(
                        value = requiredMinutes,
                        onValueChange = { requiredMinutes = it.filter(Char::isDigit) },
                        label = { Text("Minutes needed") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AnchorPicker(selected = anchor, onSelect = { anchor = it })
                }

                DayPicker(selected = days, onChange = { days = it })

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
                        StaticBlock(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(),
                            daysOfWeek = days,
                            windowStart = parsedStart!!,
                            windowEnd = parsedEnd!!,
                            requiredDuration = if (partialWindow) {
                                Duration.ofMinutes(parsedRequired!!)
                            } else {
                                null
                            },
                            anchor = anchor,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun HabitEditorDialog(
    existing: Habit?,
    onDismiss: () -> Unit,
    onSave: (Habit) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var time by remember { mutableStateOf(existing?.timeOfDay?.toDisplay() ?: "07:00") }
    var minutes by remember {
        mutableStateOf(existing?.duration?.toMinutes()?.toString() ?: "5")
    }
    var days by remember { mutableStateOf(existing?.daysOfWeek ?: StaticBlock.ALL_DAYS) }

    val parsedTime = time.toLocalTimeOrNull()
    val parsedMinutes = minutes.toLongOrNull()

    val error: String? = when {
        title.isBlank() -> "Give the habit a name."
        parsedTime == null -> "Time must look like 07:00."
        parsedMinutes == null || parsedMinutes <= 0 -> "Minutes must be a positive number."
        days.isEmpty() -> "Pick at least one day."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New habit" else "Edit habit") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Habits have no priority and no deadline. If the day is " +
                        "packed they are skipped, never reshuffled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    placeholder = { Text("Brush teeth") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField(
                        value = time,
                        onValueChange = { time = it },
                        label = "Time",
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit) },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                DayPicker(selected = days, onChange = { days = it })

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
                        Habit(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(),
                            daysOfWeek = days,
                            timeOfDay = parsedTime!!,
                            duration = Duration.ofMinutes(parsedMinutes!!),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("HH:mm") },
        singleLine = true,
        isError = value.toLocalTimeOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnchorPicker(selected: WindowAnchor, onSelect: (WindowAnchor) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Where in the window", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == WindowAnchor.EARLIEST,
                onClick = { onSelect(WindowAnchor.EARLIEST) },
                label = { Text("As early as possible") },
            )
            FilterChip(
                selected = selected == WindowAnchor.LATEST,
                onClick = { onSelect(WindowAnchor.LATEST) },
                label = { Text("As late as possible") },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Days", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { day ->
                FilterChip(
                    selected = day in selected,
                    onClick = {
                        onChange(
                            if (day in selected) selected - day else selected + day,
                        )
                    },
                    label = { Text(day.shortLabel()) },
                )
            }
        }
    }
}
