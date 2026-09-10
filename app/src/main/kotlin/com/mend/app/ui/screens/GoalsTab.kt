package com.mend.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mend.engine.progress.GoalProgress
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * The goal list: what you are working towards, and how far along each is.
 *
 * Nothing here is scheduled. Placing this work into free time is Phase 3.
 */
@Composable
fun GoalsTab(
    goals: List<GoalItem>,
    onOpen: (GoalItem) -> Unit,
    onEdit: (GoalItem) -> Unit,
    onDelete: (GoalItem) -> Unit,
) {
    if (goals.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No goals yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "A goal is a body of work with a deadline. Break it into " +
                        "lessons, and give each lesson whatever sub-tasks it needs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(goals, key = { it.goal.id }) { item ->
            GoalRow(
                item = item,
                onClick = { onOpen(item) },
                onEdit = { onEdit(item) },
                onDelete = { onDelete(item) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun GoalRow(
    item: GoalItem,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val progress = item.progress
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.goal.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${progress.lessonsCompleted}/${progress.totalLessons}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${item.goal.title}")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${item.goal.title}")
            }
        }

        LinearProgressIndicator(
            progress = { progress.fraction.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )

        Spacer(Modifier.height(2.dp))
        Text(
            text = deadlineLine(item, progress),
            style = MaterialTheme.typography.bodySmall,
            color = if (progress.isOverdue) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun deadlineLine(item: GoalItem, progress: GoalProgress): String {
    val date = item.goal.deadline.format(DATE_FORMAT)
    val importance = "importance ${item.goal.manualImportance}/5"
    return when {
        progress.isComplete -> "Complete  ·  was due $date"
        progress.isOverdue -> "Overdue by ${-progress.daysRemaining}d  ·  was due $date"
        progress.daysRemaining == 0L -> "Due today  ·  $importance"
        else -> "${progress.daysRemaining}d left  ·  due $date  ·  $importance"
    }
}
