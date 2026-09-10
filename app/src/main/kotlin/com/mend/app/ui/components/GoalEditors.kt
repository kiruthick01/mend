package com.mend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Flexibility
import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.model.SubTask
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Editors for the goal hierarchy.
 *
 * There is deliberately no sub-task template here. Each lesson's sub-tasks are
 * entered from scratch, with a free-text type, so two lessons in the same goal
 * can look nothing alike — which is the point.
 */

@Composable
fun GoalEditorDialog(
    existing: Goal?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Goal) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var deadline by remember {
        mutableStateOf((existing?.deadline ?: today.plusWeeks(2)).toString())
    }
    var importance by remember { mutableStateOf(existing?.manualImportance ?: 3) }
    var totalLessons by remember {
        mutableStateOf((existing?.totalLessons ?: 0).toString())
    }
    var consequence by remember {
        mutableStateOf(existing?.consequenceType ?: ConsequenceType.SOFT_GOAL)
    }

    val parsedDeadline = deadline.toLocalDateOrNull()
    val parsedTotal = totalLessons.toIntOrNull()

    val error: String? = when {
        title.isBlank() -> "Give the goal a name."
        parsedDeadline == null -> "Deadline must look like 2026-09-30."
        parsedTotal == null || parsedTotal < 0 -> "Lesson count must be zero or more."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New goal" else "Edit goal") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Goal") },
                    placeholder = { Text("Japanese N5") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deadline,
                    onValueChange = { deadline = it },
                    label = { Text("Deadline") },
                    placeholder = { Text("YYYY-MM-DD") },
                    isError = parsedDeadline == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = totalLessons,
                    onValueChange = { totalLessons = it.filter(Char::isDigit) },
                    label = { Text("Total lessons") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ImportancePicker(selected = importance, onSelect = { importance = it })
                ConsequencePicker(selected = consequence, onSelect = { consequence = it })

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
                        Goal(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(),
                            deadline = parsedDeadline!!,
                            manualImportance = importance,
                            consequenceType = consequence,
                            isOneShot = existing?.isOneShot ?: false,
                            totalLessons = parsedTotal!!,
                            lessonsCompleted = 0,
                            pileupRisk = existing?.pileupRisk ?: false,
                            negotiatedDelayLimit = existing?.negotiatedDelayLimit,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun LessonEditorDialog(
    existing: Lesson?,
    goalId: String,
    nextOrderIndex: Int,
    onDismiss: () -> Unit,
    onSave: (Lesson) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    val error = if (title.isBlank()) "Give the lesson a name." else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New lesson" else "Edit lesson") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Lesson") },
                    placeholder = { Text("Chapter 3 — te-form") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Add sub-tasks afterwards if this lesson needs them. " +
                        "A lesson with none is finished by ticking the lesson itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        (existing ?: Lesson(
                            id = UUID.randomUUID().toString(),
                            goalId = goalId,
                            orderIndex = nextOrderIndex,
                            title = "",
                        )).copy(title = title.trim()),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SubTaskEditorDialog(
    existing: SubTask?,
    lessonId: String,
    knownTypes: List<String>,
    onDismiss: () -> Unit,
    onSave: (SubTask) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type.orEmpty()) }
    var minutes by remember {
        mutableStateOf((existing?.estimatedDuration?.toMinutes() ?: 30).toString())
    }
    var comfortable by remember {
        mutableStateOf(existing?.paceComfortable?.toMinutes()?.toString().orEmpty())
    }
    var flexibility by remember {
        mutableStateOf(existing?.flexibility ?: Flexibility.SPLITTABLE)
    }

    val parsedMinutes = minutes.toLongOrNull()
    val parsedComfortable = comfortable.takeIf { it.isNotBlank() }?.toLongOrNull()

    val error: String? = when {
        title.isBlank() -> "Give the sub-task a name."
        type.isBlank() -> "Give the sub-task a type."
        parsedMinutes == null || parsedMinutes <= 0 -> "Estimate must be a positive number."
        comfortable.isNotBlank() && (parsedComfortable == null || parsedComfortable <= 0) ->
            "Comfortable pace must be a positive number of minutes per day."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New sub-task" else "Edit sub-task") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Sub-task") },
                    placeholder = { Text("Read pages 40-52") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = type,
                    onValueChange = { type = it },
                    label = { Text("Type") },
                    placeholder = { Text("reading") },
                    supportingText = {
                        Text("Anything you like. Pace is tracked per type.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (knownTypes.isNotEmpty()) {
                    TypeSuggestions(types = knownTypes, onPick = { type = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit) },
                        label = { Text("Estimate (min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = comfortable,
                        onValueChange = { comfortable = it.filter(Char::isDigit) },
                        label = { Text("Pace (min/day)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "Pace is optional: how many minutes of this kind of work " +
                        "you are comfortable doing in a day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlexibilityPicker(selected = flexibility, onSelect = { flexibility = it })
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
                        (existing ?: SubTask(
                            id = UUID.randomUUID().toString(),
                            lessonId = lessonId,
                            type = "",
                            title = "",
                            estimatedDuration = Duration.ofMinutes(1),
                        )).copy(
                            title = title.trim(),
                            type = type.trim(),
                            estimatedDuration = Duration.ofMinutes(parsedMinutes!!),
                            paceComfortable = parsedComfortable?.let(Duration::ofMinutes),
                            flexibility = flexibility,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * How the allocator may bend this sub-task to fit the day.
 *
 * Worded as behaviour rather than as the enum names, because "splittable" is
 * a scheduler word and "can be broken up" is what the user is actually
 * deciding.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlexibilityPicker(selected: Flexibility, onSelect: (Flexibility) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("How it can be scheduled", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FLEXIBILITY_LABELS.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

private val FLEXIBILITY_LABELS: List<Pair<Flexibility, String>> = listOf(
    Flexibility.SPLITTABLE to "Can be broken up",
    Flexibility.MOVABLE to "One sitting, any time",
    Flexibility.SHRINKABLE to "Can be cut short",
    Flexibility.FIXED to "One sitting, all of it",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeSuggestions(types: List<String>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Used before", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { known ->
                FilterChip(
                    selected = false,
                    onClick = { onPick(known) },
                    label = { Text(known) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportancePicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Importance", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Goal.IMPORTANCE_RANGE.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text("$value") },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsequencePicker(
    selected: ConsequenceType,
    onSelect: (ConsequenceType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Consequence", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selected == ConsequenceType.SOFT_GOAL,
                onClick = { onSelect(ConsequenceType.SOFT_GOAL) },
                label = { Text("Self-imposed") },
            )
            FilterChip(
                selected = selected == ConsequenceType.IRL_BINDING,
                onClick = { onSelect(ConsequenceType.IRL_BINDING) },
                label = { Text("Real consequence") },
            )
        }
    }
}

internal fun String.toLocalDateOrNull(): LocalDate? = try {
    LocalDate.parse(trim())
} catch (_: DateTimeParseException) {
    null
}
