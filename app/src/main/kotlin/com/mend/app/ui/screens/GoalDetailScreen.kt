package com.mend.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mend.app.ui.components.LessonEditorDialog
import com.mend.app.ui.components.SubTaskEditorDialog
import com.mend.app.ui.mendViewModel
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import com.mend.engine.progress.PaceSnapshot
import com.mend.engine.progress.completionFraction
import com.mend.engine.progress.derivedStatus
import com.mend.engine.progress.isComplete
import kotlin.math.roundToInt

/**
 * One goal, its lessons, and each lesson's own sub-tasks.
 *
 * Lessons are shown expanded rather than behind another level of navigation,
 * because the whole point of the hierarchy is seeing at a glance which lesson
 * is actually blocking the goal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailScreen(goalId: String, onBack: () -> Unit) {
    val viewModel = mendViewModel { app -> GoalsViewModel(app.goalRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.goals.firstOrNull { it.goal.id == goalId }

    var editingLesson by remember { mutableStateOf<Lesson?>(null) }
    var showLessonEditor by remember { mutableStateOf(false) }
    var subTaskTarget by remember { mutableStateOf<Pair<Lesson, SubTask?>?>(null) }

    Scaffold(
        // The top-level Scaffold in MendApp has already inset this screen for
        // the status and navigation bars. Modifier.padding does not consume
        // insets, so without this the inner Scaffold applies them a second time
        // and the content sits a whole status bar too low.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // TopAppBar insets itself for the status bar by default, which
                // would be a third application of the same inset on top of the
                // outer Scaffold. Everything above this point has already
                // accounted for it.
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(item?.goal?.title ?: "Goal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (item != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingLesson = null
                        showLessonEditor = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add lesson") },
                )
            }
        },
    ) { padding ->
        if (item == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("This goal no longer exists.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
        ) {
            item { GoalSummary(item) }
            if (item.pace.isNotEmpty()) {
                item { PaceSection(item.pace) }
            }
            item { HorizontalDivider() }

            item.lessons.forEach { lesson ->
                item(key = lesson.id) {
                    LessonBlock(
                        lesson = lesson,
                        onToggleLesson = { done -> viewModel.setLessonDone(lesson, done) },
                        onToggleSubTask = { subTask, done ->
                            viewModel.setSubTaskDone(
                                subTask,
                                done,
                                lesson.subTasks.indexOfFirst { it.id == subTask.id },
                            )
                        },
                        onEditLesson = {
                            editingLesson = lesson
                            showLessonEditor = true
                        },
                        onDeleteLesson = { viewModel.deleteLesson(lesson) },
                        onAddSubTask = { subTaskTarget = lesson to null },
                        onEditSubTask = { subTaskTarget = lesson to it },
                        onDeleteSubTask = { viewModel.deleteSubTask(it) },
                    )
                    HorizontalDivider()
                }
            }

            if (item.lessons.isEmpty()) {
                item {
                    Text(
                        text = "No lessons yet. Add the first one to start breaking " +
                            "this goal down.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }

    if (showLessonEditor && item != null) {
        LessonEditorDialog(
            existing = editingLesson,
            goalId = goalId,
            nextOrderIndex = item.lessons.size,
            onDismiss = { showLessonEditor = false },
            onSave = {
                viewModel.saveLesson(it)
                showLessonEditor = false
            },
        )
    }

    subTaskTarget?.let { (lesson, existing) ->
        SubTaskEditorDialog(
            existing = existing,
            lessonId = lesson.id,
            knownTypes = item?.lessons.orEmpty()
                .flatMap { it.subTasks }
                .map { it.type }
                .distinct()
                .sorted(),
            onDismiss = { subTaskTarget = null },
            onSave = {
                viewModel.saveSubTask(
                    it,
                    orderIndex = existing?.let { e ->
                        lesson.subTasks.indexOfFirst { s -> s.id == e.id }
                    } ?: lesson.subTasks.size,
                )
                subTaskTarget = null
            },
        )
    }
}

@Composable
private fun GoalSummary(item: GoalItem) {
    val progress = item.progress
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${progress.lessonsCompleted} of ${progress.totalLessons} lessons",
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(
            progress = { progress.fraction.toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Text(
            text = buildString {
                append("${progress.subTasksCompleted}/${progress.totalSubTasks} sub-tasks")
                append("  ·  ")
                append(
                    when {
                        progress.isComplete -> "complete"
                        progress.isOverdue -> "overdue by ${-progress.daysRemaining}d"
                        else -> "${progress.daysRemaining}d to deadline"
                    },
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Declared comfortable pace against what has actually been happening, per
 * sub-task type. A blunt sustained average, not a forecast.
 */
@Composable
private fun PaceSection(pace: List<PaceSnapshot>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Pace", style = MaterialTheme.typography.titleSmall)
        pace.forEach { snapshot ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(snapshot.subTaskType, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${snapshot.completedCount}/${snapshot.totalCount} done " +
                            "over ${snapshot.observationDays}d",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = paceLabel(snapshot),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (snapshot.isBehindComfortablePace) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun paceLabel(snapshot: PaceSnapshot): String {
    val actual = "${snapshot.actualMinutesPerDay.roundToInt()}m/day"
    val declared = snapshot.comfortableMinutesPerDay
    return if (declared == null) {
        "$actual  ·  no pace set"
    } else {
        "$actual of ${declared}m/day"
    }
}

@Composable
private fun LessonBlock(
    lesson: Lesson,
    onToggleLesson: (Boolean) -> Unit,
    onToggleSubTask: (SubTask, Boolean) -> Unit,
    onEditLesson: () -> Unit,
    onDeleteLesson: () -> Unit,
    onAddSubTask: () -> Unit,
    onEditSubTask: (SubTask) -> Unit,
    onDeleteSubTask: (SubTask) -> Unit,
) {
    val complete = lesson.isComplete()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only a lesson with no sub-tasks is ticked directly; otherwise its
            // state is derived from the sub-tasks and the box is read-only.
            Checkbox(
                checked = complete,
                enabled = lesson.hasNoSubTasks,
                onCheckedChange = { onToggleLesson(it) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = lesson.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (complete) TextDecoration.LineThrough else null,
                )
                Text(
                    text = lessonSubtitle(lesson),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEditLesson) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${lesson.title}")
            }
            IconButton(onClick = onDeleteLesson) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${lesson.title}")
            }
        }

        lesson.subTasks.forEach { subTask ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = subTask.status == ProgressStatus.DONE,
                    onCheckedChange = { onToggleSubTask(subTask, it) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subTask.title,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (subTask.status == ProgressStatus.DONE) {
                            TextDecoration.LineThrough
                        } else {
                            null
                        },
                    )
                    Text(
                        text = "${subTask.type}  ·  ${subTask.estimatedDuration.toMinutes()}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onEditSubTask(subTask) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${subTask.title}")
                }
                IconButton(onClick = { onDeleteSubTask(subTask) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${subTask.title}")
                }
            }
        }

        Row(modifier = Modifier.padding(start = 52.dp)) {
            androidx.compose.material3.TextButton(onClick = onAddSubTask) {
                Text("Add sub-task")
            }
        }
    }
}

private fun lessonSubtitle(lesson: Lesson): String = when {
    lesson.hasNoSubTasks -> "no sub-tasks  ·  tick to complete"
    else -> {
        val done = lesson.subTasks.count { it.status == ProgressStatus.DONE }
        val percent = (lesson.completionFraction() * 100).roundToInt()
        "$done/${lesson.subTasks.size} sub-tasks  ·  $percent%  ·  " +
            lesson.derivedStatus().name.lowercase().replace('_', ' ')
    }
}
