package com.mend.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mend.app.ui.components.HabitEditorDialog
import com.mend.app.ui.components.StaticBlockEditorDialog
import com.mend.app.ui.components.describeDays
import com.mend.app.ui.mendViewModel
import com.mend.engine.model.Habit
import com.mend.engine.model.StaticBlock
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private enum class StaticLayerTab(val label: String) {
    BLOCKS("Fixed blocks"),
    HABITS("Habits"),
}

/**
 * Entry point for the static layer: the fixed blocks and habits that define
 * the shape of every day.
 *
 * The two are kept on separate tabs rather than in one merged list because
 * they behave differently in kind, not degree — a block carves the grid, a
 * habit sits on the timeline and is silently skipped when the day is full.
 * Presenting them as one list would suggest they are interchangeable.
 */
@Composable
fun TasksScreen() {
    val viewModel = mendViewModel { app -> StaticLayerViewModel(app.staticLayerRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(StaticLayerTab.BLOCKS) }
    var editingBlock by remember { mutableStateOf<StaticBlock?>(null) }
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var showBlockEditor by remember { mutableStateOf(false) }
    var showHabitEditor by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    when (tab) {
                        StaticLayerTab.BLOCKS -> {
                            editingBlock = null
                            showBlockEditor = true
                        }
                        StaticLayerTab.HABITS -> {
                            editingHabit = null
                            showHabitEditor = true
                        }
                    }
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = {
                    Text(
                        when (tab) {
                            StaticLayerTab.BLOCKS -> "Add block"
                            StaticLayerTab.HABITS -> "Add habit"
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab.ordinal) {
                StaticLayerTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        text = { Text(entry.label) },
                    )
                }
            }

            when (tab) {
                StaticLayerTab.BLOCKS -> StaticBlockList(
                    blocks = state.staticBlocks,
                    onEdit = {
                        editingBlock = it
                        showBlockEditor = true
                    },
                    onDelete = { viewModel.delete(it.id) },
                )

                StaticLayerTab.HABITS -> HabitList(
                    habits = state.habits,
                    onEdit = {
                        editingHabit = it
                        showHabitEditor = true
                    },
                    onDelete = { viewModel.delete(it.id) },
                )
            }
        }
    }

    if (showBlockEditor) {
        StaticBlockEditorDialog(
            existing = editingBlock,
            onDismiss = { showBlockEditor = false },
            onSave = {
                viewModel.saveStaticBlock(it)
                showBlockEditor = false
            },
        )
    }

    if (showHabitEditor) {
        HabitEditorDialog(
            existing = editingHabit,
            onDismiss = { showHabitEditor = false },
            onSave = {
                viewModel.saveHabit(it)
                showHabitEditor = false
            },
        )
    }
}

@Composable
private fun StaticBlockList(
    blocks: List<StaticBlock>,
    onEdit: (StaticBlock) -> Unit,
    onDelete: (StaticBlock) -> Unit,
) {
    if (blocks.isEmpty()) {
        EmptyList(
            title = "No fixed blocks yet.",
            body = "College, mess, gym. These are constraints on the day, not " +
                "tasks competing for it.",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(blocks, key = { it.id }) { block ->
            EntryRow(
                title = block.title,
                subtitle = describeBlock(block),
                days = describeDays(block.daysOfWeek),
                onEdit = { onEdit(block) },
                onDelete = { onDelete(block) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun HabitList(
    habits: List<Habit>,
    onEdit: (Habit) -> Unit,
    onDelete: (Habit) -> Unit,
) {
    if (habits.isEmpty()) {
        EmptyList(
            title = "No habits yet.",
            body = "Brushing, water, wake time. Habits carry no priority and no " +
                "deadline; if the day is packed they are simply skipped.",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(habits, key = { it.id }) { habit ->
            EntryRow(
                title = habit.title,
                subtitle = "${habit.timeOfDay.format(TIME_FORMAT)}  ·  " +
                    "${habit.duration.toMinutes()}m",
                days = describeDays(habit.daysOfWeek),
                onEdit = { onEdit(habit) },
                onDelete = { onDelete(habit) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    days: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = days,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit $title")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete $title")
        }
    }
}

@Composable
private fun EmptyList(title: String, body: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun describeBlock(block: StaticBlock): String {
    val window = "${block.windowStart.format(TIME_FORMAT)}-${block.windowEnd.format(TIME_FORMAT)}"
    return if (block.isPartialWindow) {
        "$window window  ·  ${block.occupiedDuration.toMinutes()}m needed"
    } else {
        "$window  ·  full window"
    }
}
