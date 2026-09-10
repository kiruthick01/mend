package com.mend.engine.priority

import com.mend.engine.model.Goal
import com.mend.engine.model.GoalTask
import com.mend.engine.model.Schedulable
import com.mend.engine.model.Weights
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Deterministic priority scoring.
 *
 * Three terms, each normalised to 0.0-1.0, combined by the user's weights.
 * There is no model here and no call to anything: the same task on the same
 * day always scores the same number, and every number can be explained in one
 * sentence. That is the whole design goal — a schedule you cannot interrogate
 * is a schedule you will not trust.
 */

/** How far ahead a deadline stops adding urgency, in days. */
const val DEADLINE_HORIZON_DAYS: Long = 30

/**
 * The breakdown behind a score, kept so a placement can be explained without
 * recomputing it.
 */
data class PriorityBreakdown(
    val taskId: String,
    val deadlineProximity: Double,
    val importance: Double,
    val adherencePressure: Double,
    val weights: Weights,
    val score: Double,
) {
    /** One line saying exactly where the number came from. */
    fun explain(): String =
        "score %.3f = deadline %.2f x %.2f + importance %.2f x %.2f + adherence %.2f x %.2f"
            .format(
                score,
                deadlineProximity,
                weights.deadline,
                importance,
                weights.importance,
                adherencePressure,
                weights.adherenceTrend,
            )
}

/**
 * Scores one task, higher meaning "do this sooner".
 *
 * Accepts [Schedulable] and nothing else. Habits and static blocks do not
 * implement it, so they cannot be scored — the constraint is enforced by the
 * type system rather than by a runtime check that could be forgotten.
 */
fun computePriorityScore(
    task: Schedulable,
    weights: Weights = Weights.DEFAULT,
    today: LocalDate,
): Double = computePriorityBreakdown(task, weights, today).score

/** As [computePriorityScore], but keeping the three terms for logging. */
fun computePriorityBreakdown(
    task: Schedulable,
    weights: Weights = Weights.DEFAULT,
    today: LocalDate,
): PriorityBreakdown {
    val goalTask = task as? GoalTask

    val proximity = goalTask?.let { deadlineProximity(it.deadline, today) } ?: 0.0
    val importance = goalTask?.let { normalisedImportance(it.manualImportance) } ?: 0.0
    val adherence = goalTask?.let { adherencePressure(it.adherenceTrend) }
        ?: adherencePressure(GoalTask.NEUTRAL_ADHERENCE)

    val score = weights.deadline * proximity +
        weights.importance * importance +
        weights.adherenceTrend * adherence

    return PriorityBreakdown(
        taskId = task.id,
        deadlineProximity = proximity,
        importance = importance,
        adherencePressure = adherence,
        weights = weights,
        score = score,
    )
}

/**
 * 1.0 when the deadline is today or already past, falling linearly to 0.0 at
 * [DEADLINE_HORIZON_DAYS] away and staying there.
 *
 * Linear rather than exponential on purpose: an exponential curve produces
 * more plausible-looking urgency, and is far harder to reason about when a
 * task turns up somewhere surprising.
 */
fun deadlineProximity(
    deadline: LocalDate,
    today: LocalDate,
    horizonDays: Long = DEADLINE_HORIZON_DAYS,
): Double {
    val daysRemaining = ChronoUnit.DAYS.between(today, deadline)
    return when {
        daysRemaining <= 0 -> 1.0
        daysRemaining >= horizonDays -> 0.0
        else -> 1.0 - (daysRemaining.toDouble() / horizonDays)
    }
}

/** Maps the 1-5 manual importance scale onto 0.0-1.0. */
fun normalisedImportance(manualImportance: Int): Double {
    val range = Goal.IMPORTANCE_RANGE
    return (manualImportance.coerceIn(range) - range.first).toDouble() /
        (range.last - range.first)
}

/**
 * Turns an adherence trend into upward pressure on the score.
 *
 * A trend of 1.0 means this work always gets done, so it needs no help and
 * contributes nothing. A trend of 0.0 means it keeps slipping, so it is pushed
 * up the ranking. Neutral sits in the middle and moves nothing.
 */
fun adherencePressure(adherenceTrend: Double): Double =
    1.0 - adherenceTrend.coerceIn(0.0, 1.0)

/**
 * Ranks tasks highest-priority first, with fully deterministic tie-breaking.
 *
 * Ties are broken in this order, and no other:
 *  1. higher score
 *  2. earlier deadline
 *  3. higher manual importance
 *  4. lexicographic id
 *
 * The last rule exists so that two genuinely identical tasks still come out in
 * a stable, repeatable order. Without it the same inputs could produce two
 * different schedules, which would make every bug report unreproducible.
 */
fun rankByPriority(
    tasks: List<GoalTask>,
    weights: Weights = Weights.DEFAULT,
    today: LocalDate,
): List<RankedTask> =
    tasks
        .map { RankedTask(it, computePriorityBreakdown(it, weights, today)) }
        .sortedWith(
            compareByDescending<RankedTask> { it.breakdown.score }
                .thenBy { it.task.deadline }
                .thenByDescending { it.task.manualImportance }
                .thenBy { it.task.id },
        )

/** A task with its computed score and the breakdown behind it. */
data class RankedTask(
    val task: GoalTask,
    val breakdown: PriorityBreakdown,
) {
    val score: Double get() = breakdown.score
}
