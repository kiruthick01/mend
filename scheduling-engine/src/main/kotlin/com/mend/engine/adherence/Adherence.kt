package com.mend.engine.adherence

import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.AdherenceOutcome
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Turning the append-only adherence stream into behavioural signal.
 *
 * Everything here is descriptive statistics over recorded history. There is no
 * inference, no smoothing and no model: if a number says you are twenty
 * minutes late on average, twenty minutes is the arithmetic mean of the delays
 * actually written down. That is the whole point — PROJECT_PLAN.md Phase 5
 * says precision is what keeps this from becoming noise, and a figure nobody
 * can reconstruct is not precise, it is just confident.
 */

/** Days of history a rolling window covers unless told otherwise. */
const val DEFAULT_ROLLING_WINDOW_DAYS: Long = 14

/** Below this many observations, a rate is reported but not treated as signal. */
const val MINIMUM_OBSERVATIONS_FOR_SIGNAL: Int = 3

/** How delays are moving, compared across the two halves of the window. */
enum class DelayTrend {
    /** Getting later. */
    WORSENING,

    /** No meaningful change. */
    STABLE,

    /** Getting closer to on time. */
    IMPROVING,

    /** Not enough history to say, which is different from "stable". */
    UNKNOWN,
}

/** A delay shift smaller than this is noise, not a trend. */
const val DELAY_TREND_SENSITIVITY_MINUTES: Double = 5.0

/** What the history says about one task, or one kind of task. */
data class AdherenceStats(
    /** Task id, or a task-type key when these are grouped by type. */
    val key: String,
    val onTime: Int,
    val late: Int,
    val missed: Int,
    val negotiated: Int,
    /**
     * Mean delay across every session that happened, on-time zeros included.
     *
     * This is "how late is this work typically", which is the honest headline
     * number: a task done punctually four times out of five is not a
     * thirty-minutes-late task.
     */
    val averageDelayMinutes: Double,
    /**
     * Mean delay across only the sessions that actually ran late.
     *
     * A different question — "when it slips, by how much?" — and the one the
     * summary sentence is about. Zero when nothing has ever been late.
     */
    val averageDelayWhenLateMinutes: Double,
    /** The middle delay, which survives one disastrous day better than the mean. */
    val medianDelayMinutes: Double,
    val worstDelayMinutes: Long,
    val delayTrend: DelayTrend,
    val windowDays: Long,
    val firstObserved: LocalDate?,
    val lastObserved: LocalDate?,
) {
    /** Every entry considered, whatever the outcome. */
    val total: Int get() = onTime + late + missed + negotiated

    /** Entries where the work actually happened. */
    val completed: Int get() = onTime + late

    /**
     * Share of assigned slots that were honoured at all, 0.0-1.0.
     *
     * Late counts as done. Being twenty minutes late is a punctuality problem;
     * not turning up is a different one, and collapsing them would hide which
     * of the two is actually happening.
     *
     * A task with no history reports 1.0: assuming the worst about work that
     * has never been scheduled would poison the priority score with a
     * behavioural claim nothing supports.
     */
    val adherenceRate: Double
        get() = if (total == 0) 1.0 else completed.toDouble() / total

    /** Share of honoured slots that were also on time. */
    val punctualityRate: Double
        get() = if (completed == 0) 1.0 else onTime.toDouble() / completed

    /** True when there is enough history to draw a conclusion from. */
    val hasSignal: Boolean get() = total >= MINIMUM_OBSERVATIONS_FOR_SIGNAL

    /** One line a human can check against the raw entries. */
    fun describe(): String = buildString {
        append("$key: $completed/$total slots honoured")
        append(" (${(adherenceRate * 100).toInt()}%)")
        if (missed > 0) append(", $missed missed")
        if (late > 0) {
            append(", $late late by ${averageDelayWhenLateMinutes.toInt()}m on average")
        }
        if (!hasSignal) append(" — too few observations to read into")
    }
}

/**
 * Computes stats for one task over a rolling window ending at [asOf].
 *
 * The window is inclusive of both ends and defaults to
 * [DEFAULT_ROLLING_WINDOW_DAYS]. Entries outside it are ignored entirely
 * rather than weighted down: a weighted average is harder to check by hand,
 * and being able to check it by hand is the requirement.
 */
fun computeAdherence(
    log: List<AdherenceLogEntry>,
    taskId: String,
    asOf: LocalDate,
    windowDays: Long = DEFAULT_ROLLING_WINDOW_DAYS,
): AdherenceStats = statsFor(
    key = taskId,
    entries = log.filter { it.taskId == taskId }.withinWindow(asOf, windowDays),
    windowDays = windowDays,
)

/**
 * Groups the log by task type and computes stats for each.
 *
 * [typeOf] maps a task id to its type key — "reading", "past paper", or
 * whatever the user called it. Ids it does not recognise are skipped rather
 * than bucketed under a catch-all, which would quietly mix unrelated work.
 */
fun computeAdherenceByType(
    log: List<AdherenceLogEntry>,
    asOf: LocalDate,
    windowDays: Long = DEFAULT_ROLLING_WINDOW_DAYS,
    typeOf: (String) -> String?,
): Map<String, AdherenceStats> =
    log.withinWindow(asOf, windowDays)
        .groupBy { typeOf(it.taskId) }
        .filterKeys { it != null }
        .map { (type, entries) -> type!! to statsFor(type, entries, windowDays) }
        .toMap()

/** Stats across everything in the window, whatever the task. */
fun computeOverallAdherence(
    log: List<AdherenceLogEntry>,
    asOf: LocalDate,
    windowDays: Long = DEFAULT_ROLLING_WINDOW_DAYS,
): AdherenceStats = statsFor("overall", log.withinWindow(asOf, windowDays), windowDays)

/**
 * Minutes of work actually completed per day over the window.
 *
 * Measured from the assigned slots of entries that were honoured, which is the
 * only record of how much work a day really absorbed. Used by the Phase 5
 * projection to decide whether a deadline is reachable.
 */
fun completedMinutesPerDay(
    log: List<AdherenceLogEntry>,
    asOf: LocalDate,
    windowDays: Long = DEFAULT_ROLLING_WINDOW_DAYS,
): Double {
    val entries = log.withinWindow(asOf, windowDays)
        .filter { it.outcome == AdherenceOutcome.ON_TIME || it.outcome == AdherenceOutcome.LATE }
    if (entries.isEmpty()) return 0.0

    val minutes = entries.sumOf {
        Duration.between(it.assignedSlotStart, it.assignedSlotEnd).toMinutes()
    }
    // Spread over the observed span rather than the nominal window: two weeks
    // of history and two days of it both deserve an honest denominator.
    val first = entries.minOf { it.date }
    val days = (ChronoUnit.DAYS.between(first, asOf) + 1).coerceAtLeast(1)
    return minutes.toDouble() / days
}

/** How many distinct days in the window have any recorded activity. */
fun activeDays(
    log: List<AdherenceLogEntry>,
    asOf: LocalDate,
    windowDays: Long = DEFAULT_ROLLING_WINDOW_DAYS,
): Int = log.withinWindow(asOf, windowDays).map { it.date }.distinct().size

private fun List<AdherenceLogEntry>.withinWindow(
    asOf: LocalDate,
    windowDays: Long,
): List<AdherenceLogEntry> {
    val from = asOf.minusDays(windowDays - 1)
    return filter { !it.date.isBefore(from) && !it.date.isAfter(asOf) }
}

private fun statsFor(
    key: String,
    entries: List<AdherenceLogEntry>,
    windowDays: Long,
): AdherenceStats {
    val delays = entries
        .filter { it.outcome == AdherenceOutcome.ON_TIME || it.outcome == AdherenceOutcome.LATE }
        .map { it.delayMinutes }
    val lateDelays = entries
        .filter { it.outcome == AdherenceOutcome.LATE }
        .map { it.delayMinutes }

    return AdherenceStats(
        key = key,
        onTime = entries.count { it.outcome == AdherenceOutcome.ON_TIME },
        late = entries.count { it.outcome == AdherenceOutcome.LATE },
        missed = entries.count { it.outcome == AdherenceOutcome.MISSED },
        negotiated = entries.count { it.outcome == AdherenceOutcome.NEGOTIATED },
        averageDelayMinutes = if (delays.isEmpty()) 0.0 else delays.average(),
        averageDelayWhenLateMinutes = if (lateDelays.isEmpty()) 0.0 else lateDelays.average(),
        medianDelayMinutes = delays.median(),
        worstDelayMinutes = delays.maxOrNull() ?: 0L,
        delayTrend = trendOf(entries),
        windowDays = windowDays,
        firstObserved = entries.minOfOrNull { it.date },
        lastObserved = entries.maxOfOrNull { it.date },
    )
}

private fun List<Long>.median(): Double {
    if (isEmpty()) return 0.0
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid].toDouble()
    } else {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}

/**
 * Compares the average delay of the older half of the window against the newer
 * half.
 *
 * Deliberately crude. A regression line would look more rigorous and would be
 * far harder to explain when it disagreed with what the user could see in
 * their own history.
 */
private fun trendOf(entries: List<AdherenceLogEntry>): DelayTrend {
    val completed = entries
        .filter { it.outcome == AdherenceOutcome.ON_TIME || it.outcome == AdherenceOutcome.LATE }
        .sortedBy { it.date }

    if (completed.size < 4) return DelayTrend.UNKNOWN

    val half = completed.size / 2
    val older = completed.take(half).map { it.delayMinutes }.average()
    val newer = completed.drop(half).map { it.delayMinutes }.average()
    val shift = newer - older

    return when {
        abs(shift) < DELAY_TREND_SENSITIVITY_MINUTES -> DelayTrend.STABLE
        shift > 0 -> DelayTrend.WORSENING
        else -> DelayTrend.IMPROVING
    }
}
