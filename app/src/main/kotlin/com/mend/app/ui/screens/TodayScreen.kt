package com.mend.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mend.app.ui.mendViewModel
import com.mend.engine.freetime.EntryPlacement
import com.mend.engine.freetime.FreeTimeMap
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.TaskType
import com.mend.engine.time.TimeInterval
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The static skeleton of today.
 *
 * Phase 1 renders exactly two kinds of row: the immovable entries the user has
 * declared, and the open stretches between them. No goal work is placed here
 * yet — the free rows are literally the space Phase 3 will fill.
 */
@Composable
fun TodayScreen() {
    val viewModel = mendViewModel { app -> StaticLayerViewModel(app.staticLayerRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val map = state.freeTimeMap

    Column(modifier = Modifier.fillMaxSize()) {
        TodayHeader(state = state)
        HorizontalDivider()

        if (map == null || state.isEmpty) {
            EmptyDay()
        } else {
            DayTimeline(map = map)
        }
    }
}

@Composable
private fun TodayHeader(state: StaticLayerUiState) {
    val map = state.freeTimeMap
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
            text = state.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = state.date.format(DateTimeFormatter.ofPattern("d MMMM yyyy")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (map != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${formatSpan(map.totalOccupiedMinutes)} committed  ·  " +
                    "${formatSpan(map.totalFreeMinutes)} free",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun EmptyDay() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("The day has no shape yet.", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Add your fixed blocks and habits in Tasks, and they will " +
                    "appear here as the skeleton of the day.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Interleaves occupied placements and free gaps into one ascending timeline.
 *
 * Both lists come from the engine already normalized and non-overlapping, so
 * this is a merge, not a reconciliation.
 */
@Composable
private fun DayTimeline(map: FreeTimeMap) {
    val rows = buildTimelineRows(map)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is TimelineRow.Occupied -> OccupiedRow(row.placement)
                is TimelineRow.Free -> FreeRow(row.interval)
            }
        }
    }
}

@Composable
private fun OccupiedRow(placement: EntryPlacement) {
    val accent = when (placement.entry.type) {
        TaskType.HABIT -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        TimeGutter(placement.interval)
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = placement.entry.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitleFor(placement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FreeRow(interval: TimeInterval) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        TimeGutter(interval)
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${formatSpan(interval.minutes)} free",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TimeGutter(interval: TimeInterval) {
    Column(modifier = Modifier.width(58.dp)) {
        Text(
            text = interval.start.format(TIME_FORMAT),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = formatEnd(interval.end),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private sealed interface TimelineRow {
    val start: LocalDateTime
    val key: String

    data class Occupied(val placement: EntryPlacement) : TimelineRow {
        override val start: LocalDateTime get() = placement.interval.start
        override val key: String get() = "busy-${placement.entry.id}-$start"
    }

    data class Free(val interval: TimeInterval) : TimelineRow {
        override val start: LocalDateTime get() = interval.start
        override val key: String get() = "free-$start"
    }
}

private fun buildTimelineRows(map: FreeTimeMap): List<TimelineRow> =
    (map.placements.map(TimelineRow::Occupied) + map.free.map(TimelineRow::Free))
        .sortedBy { it.start }

private fun subtitleFor(placement: EntryPlacement): String {
    val length = formatSpan(placement.interval.minutes)
    val entry = placement.entry
    return when {
        entry is StaticBlock && entry.isPartialWindow ->
            "$length within ${entry.windowStart.format(TIME_FORMAT)}" +
                "-${entry.windowEnd.format(TIME_FORMAT)} window"
        entry.type == TaskType.HABIT -> "$length  ·  habit"
        else -> "$length  ·  fixed block"
    }
}

/** Renders the exclusive end instant, showing end-of-day as 24:00 rather than 00:00. */
private fun formatEnd(end: LocalDateTime): String =
    if (end.toLocalTime() == java.time.LocalTime.MIDNIGHT) "24:00" else end.format(TIME_FORMAT)

private fun formatSpan(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours == 0L -> "${mins}m"
        mins == 0L -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}
