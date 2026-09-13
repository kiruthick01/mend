package com.mend.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mend.app.ui.components.FeedbackPrompt
import com.mend.app.ui.components.GuiltTripCard
import com.mend.app.ui.components.ProposeDifferentDialog
import com.mend.app.ui.components.WeightChangeNotice
import com.mend.app.ui.components.ReshuffleProposalCard
import com.mend.app.ui.mendViewModel
import com.mend.engine.allocation.DaySchedule
import com.mend.engine.interrupt.InterruptPlan
import com.mend.engine.allocation.TaskPlacement
import com.mend.engine.allocation.TimelineEntry
import com.mend.engine.allocation.UnscheduledTask
import com.mend.engine.model.Lesson
import com.mend.engine.reshuffle.ReshuffleResponse
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.SubTask
import com.mend.engine.recovery.SleepStopAction
import com.mend.engine.recovery.SleepStopDecision
import com.mend.engine.model.TaskType
import com.mend.engine.time.TimeInterval
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Today, in full: the static skeleton with goal work packed into what was
 * left of it, plus anything the day could not fit.
 */
@Composable
fun TodayScreen() {
    val viewModel = mendViewModel { app ->
        TodayViewModel(
            staticLayer = app.staticLayerRepository,
            goals = app.goalRepository,
            recovery = app.recoveryRepository,
            reshuffle = app.reshuffleRepository,
            adherence = app.adherenceRepository,
            weights = app.weightsRepository,
            interpreter = app.proposalInterpreter,
            alarms = app.alarmCoordinator,
            sweepState = app.sweepState,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val schedule = state.schedule

    Column(modifier = Modifier.fillMaxSize()) {
        TodayHeader(state = state)

        state.interrupt?.let { InterruptBanner(it) }

        state.sleepStop?.let { SleepBanner(it) }

        state.lastAdjustmentReason?.let { reason ->
            WeightChangeNotice(
                reason = reason,
                onDismiss = viewModel::dismissAdjustmentNotice,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        state.feedbackPromptFor?.let { proposalId ->
            FeedbackPrompt(
                onRespond = { viewModel.recordFeedback(it, proposalId) },
                onDismiss = viewModel::dismissFeedbackPrompt,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        state.guiltTrips.forEach { trip ->
            GuiltTripCard(
                message = trip.message,
                reshuffleRequest = trip.reshuffleRequest,
                onRequestReshuffle = { viewModel.requestReshuffleFor(trip) },
                onDismiss = { viewModel.dismissGuiltTrip(trip.goalId) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        state.proposal?.let { proposal ->
            ReshuffleProposalCard(
                summary = proposal.summary,
                changes = proposal.changes,
                effectiveFrom = proposal.effectiveFrom.format(
                    DateTimeFormatter.ofPattern("EEEE d MMMM"),
                ),
                onRespond = { response ->
                    // "Propose different" opens its own flow rather than being
                    // recorded immediately: the words have to be read and shown
                    // back before anything is answered.
                    if (response is ReshuffleResponse.ProposeDifferent) {
                        viewModel.openProposeDifferent(proposal.id)
                    } else {
                        viewModel.respond(proposal.id, response)
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        HorizontalDivider()

        state.proposeDifferent?.let { propose ->
            ProposeDifferentDialog(
                state = propose,
                onTextChange = viewModel::updateProposeText,
                onInterpret = viewModel::interpretProposeDifferent,
                onAccept = viewModel::acceptInterpretedConstraint,
                onSaveVerbatim = viewModel::saveProposeVerbatim,
                onDismiss = viewModel::dismissProposeDifferent,
            )
        }

        if (schedule == null || !state.hasAnyInput) {
            EmptyDay()
        } else {
            DayTimeline(
                schedule = schedule,
                subTasksById = state.subTasksById,
                bareLessonsById = state.bareLessonsById,
                onMarkDone = viewModel::markDone,
                onMarkLessonDone = viewModel::markLessonDone,
                onMarkMissed = viewModel::markMissed,
            )
        }
    }
}

/**
 * A genuine emergency, and what it costs.
 *
 * Sits above everything else because that is what "mandatory next action"
 * means. The cost is stated in the same breath as the demand: an interrupt
 * that does not say what it is taking from you is just an alarm.
 */
@Composable
private fun InterruptBanner(plan: InterruptPlan) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
    ) {
        Text(
            text = "Do this next",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = plan.message, style = MaterialTheme.typography.bodySmall)
        Text(
            text = "Why: ${plan.qualification.reason}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The bedtime boundary, when it is close enough to matter.
 *
 * Silent until it is relevant. A protected block that talks all day is a
 * protected block that gets ignored by evening, which is exactly when it needs
 * to be heard.
 */
@Composable
private fun SleepBanner(decision: SleepStopDecision) {
    if (decision.action == SleepStopAction.NONE) return

    val container = when (decision.action) {
        SleepStopAction.STOP -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(12.dp),
    ) {
        Text(
            text = when (decision.action) {
                SleepStopAction.STOP -> "Hard stop"
                SleepStopAction.NUDGE -> "Past your wind-down"
                else -> "Wind-down"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = decision.message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TodayHeader(state: TodayUiState) {
    val schedule = state.schedule
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
        if (schedule != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = buildString {
                    append("${formatSpan(schedule.freeTimeMap.totalOccupiedMinutes)} committed")
                    append("  ·  ")
                    append("${formatSpan(schedule.totalScheduledMinutes)} scheduled")
                    append("  ·  ")
                    append("${formatSpan(schedule.remainingFreeMinutes)} free")
                },
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
                text = "Add your fixed blocks, habits and goals in Tasks, and the " +
                    "day will be built here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Interleaves fixed entries, scheduled work and the gaps still open.
 *
 * The engine hands over a normalized, non-overlapping timeline, so this is a
 * merge of three already-consistent lists rather than a reconciliation.
 */
@Composable
private fun DayTimeline(
    schedule: DaySchedule,
    subTasksById: Map<String, SubTask>,
    bareLessonsById: Map<String, Lesson>,
    onMarkDone: (SubTask, TimeInterval?) -> Unit,
    onMarkLessonDone: (Lesson, TimeInterval?) -> Unit,
    onMarkMissed: (String, String, TimeInterval) -> Unit,
) {
    val rows = buildRows(schedule)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is Row.Fixed -> FixedRow(row.entry)
                is Row.Work -> WorkRow(
                    entry = row.entry,
                    subTask = subTasksById[row.entry.task.id],
                    bareLesson = bareLessonsById[row.entry.task.id],
                    onMarkDone = onMarkDone,
                    onMarkLessonDone = onMarkLessonDone,
                    onMarkMissed = onMarkMissed,
                )
                is Row.Free -> FreeRow(row.interval)
            }
        }

        if (schedule.unscheduled.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Did not fit today",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(schedule.unscheduled, key = { "un-${it.task.id}" }) { UnscheduledRow(it) }
        }
    }
}

@Composable
private fun FixedRow(entry: TimelineEntry.Fixed) {
    val accent = when (entry.entry.type) {
        TaskType.HABIT -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    TimelineRow(
        interval = entry.interval,
        accent = accent,
        barHeight = 44.dp,
        title = entry.entry.title,
        subtitle = fixedSubtitle(entry),
    )
}

/**
 * One slice of scheduled goal work, with the two actions that produce
 * adherence signal.
 *
 * Both are offered, and neither is assumed. Marking something missed is as
 * much a real answer as marking it done — without it the stream only ever
 * records success, adherence never degrades, and every behavioural feature
 * downstream quietly reports that everything is fine.
 */
@Composable
private fun WorkRow(
    entry: TimelineEntry.Work,
    subTask: SubTask?,
    bareLesson: Lesson?,
    onMarkDone: (SubTask, TimeInterval?) -> Unit,
    onMarkLessonDone: (Lesson, TimeInterval?) -> Unit,
    onMarkMissed: (String, String, TimeInterval) -> Unit,
) {
    Column {
        TimelineRow(
            interval = entry.interval,
            accent = MaterialTheme.colorScheme.tertiary,
            barHeight = 44.dp,
            title = entry.task.title,
            subtitle = workSubtitle(entry),
        )
        Row(
            modifier = Modifier.padding(start = 74.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Scheduled work is either a sub-task or a whole bare lesson;
            // both are completable, and exactly one of them applies here.
            if (subTask != null) {
                TextButton(onClick = { onMarkDone(subTask, entry.interval) }) {
                    Text("Done")
                }
            } else if (bareLesson != null) {
                TextButton(onClick = { onMarkLessonDone(bareLesson, entry.interval) }) {
                    Text("Done")
                }
            }
            TextButton(
                onClick = {
                    onMarkMissed(entry.task.id, entry.task.title, entry.interval)
                },
            ) {
                Text("Missed")
            }
        }
    }
}

@Composable
private fun FreeRow(interval: TimeInterval) {
    TimelineRow(
        interval = interval,
        accent = MaterialTheme.colorScheme.outlineVariant,
        barHeight = 28.dp,
        title = "${formatSpan(interval.minutes)} free",
        subtitle = null,
        titleStyle = TitleStyle.MUTED,
    )
}

@Composable
private fun UnscheduledRow(unscheduled: UnscheduledTask) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = unscheduled.task.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = unscheduled.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class TitleStyle { NORMAL, MUTED }

@Composable
private fun TimelineRow(
    interval: TimeInterval,
    accent: Color,
    barHeight: androidx.compose.ui.unit.Dp,
    title: String,
    subtitle: String?,
    titleStyle: TitleStyle = TitleStyle.NORMAL,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(barHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (titleStyle == TitleStyle.MUTED) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                fontWeight = if (titleStyle == TitleStyle.MUTED) null else FontWeight.Medium,
                color = if (titleStyle == TitleStyle.MUTED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private sealed interface Row {
    val start: LocalDateTime
    val key: String

    data class Fixed(val entry: TimelineEntry.Fixed) : Row {
        override val start: LocalDateTime get() = entry.interval.start
        override val key: String get() = "fixed-${entry.entry.id}-$start"
    }

    data class Work(val entry: TimelineEntry.Work) : Row {
        override val start: LocalDateTime get() = entry.interval.start
        override val key: String get() = "work-${entry.task.id}-$start"
    }

    data class Free(val interval: TimeInterval) : Row {
        override val start: LocalDateTime get() = interval.start
        override val key: String get() = "free-$start"
    }
}

private fun buildRows(schedule: DaySchedule): List<Row> =
    (
        schedule.timeline.map { entry ->
            when (entry) {
                is TimelineEntry.Fixed -> Row.Fixed(entry)
                is TimelineEntry.Work -> Row.Work(entry)
            }
        } + schedule.remainingFree.map(Row::Free)
        ).sortedBy { it.start }

private fun fixedSubtitle(entry: TimelineEntry.Fixed): String {
    val length = formatSpan(entry.interval.minutes)
    val block = entry.entry
    return when {
        block is StaticBlock && block.isPartialWindow ->
            "$length within ${block.windowStart.format(TIME_FORMAT)}" +
                "-${block.windowEnd.format(TIME_FORMAT)} window"
        block.type == TaskType.HABIT -> "$length  ·  habit"
        block.type == TaskType.PROTECTED_RECOVERY -> "$length  ·  protected"
        else -> "$length  ·  fixed block"
    }
}

private fun workSubtitle(entry: TimelineEntry.Work): String {
    val placement: TaskPlacement = entry.placement
    val length = formatSpan(entry.interval.minutes)
    val sliceIndex = placement.slices.indexOf(entry.interval)
    return buildString {
        append(length)
        if (placement.isSplit) {
            append("  ·  part ${sliceIndex + 1} of ${placement.slices.size}")
        }
        if (placement.isPartial) {
            append("  ·  ${formatSpan(placement.requestedDuration.toMinutes())} wanted")
        }
    }
}

/** Renders the exclusive end instant, showing end-of-day as 24:00 rather than 00:00. */
private fun formatEnd(end: LocalDateTime): String =
    if (end.toLocalTime() == LocalTime.MIDNIGHT) "24:00" else end.format(TIME_FORMAT)

private fun formatSpan(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours == 0L -> "${mins}m"
        mins == 0L -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}
