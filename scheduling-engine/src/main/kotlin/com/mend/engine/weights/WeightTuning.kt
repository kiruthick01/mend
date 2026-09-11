package com.mend.engine.weights

import com.mend.engine.model.Weights
import com.mend.engine.tuning.Tuning
import java.time.LocalDateTime

/**
 * Adaptive weight tuning.
 *
 * This is the one part of Mend that changes its own behaviour over time, which
 * makes it the part most able to become inexplicable. So it is deliberately the
 * least clever thing in the codebase: count consecutive identical complaints,
 * and when there are enough of them, move one named weight by one fixed step.
 *
 * No model, no learning rate, no decay. PROJECT_PLAN.md asks that six months
 * from now a person can look at the adjustment log and understand exactly why a
 * weight changed — and that is only true if the rule is small enough to hold in
 * your head. Every adjustment records the feedback that caused it, the streak
 * that triggered it, the old value and the new one.
 */

/** What the user thought of the schedule they were given. */
enum class ScheduleFeedback {
    /** Packed too hard. Back off. */
    TOO_AGGRESSIVE,

    /** Not pushing enough. Lean in. */
    TOO_LAX,

    /** No change wanted. Resets any streak. */
    JUST_RIGHT,
}

/** Which weight an adjustment moved. */
enum class WeightKind {
    DEADLINE,
    IMPORTANCE,
    ADHERENCE_TREND,
}

/** Consecutive identical complaints before anything moves. */
const val FEEDBACK_STREAK_THRESHOLD: Int = Tuning.FEEDBACK_STREAK_THRESHOLD

/** How far a weight moves when it does. */
const val WEIGHT_STEP: Double = Tuning.WEIGHT_STEP

/** A weight never goes below this, so no term is ever switched off entirely. */
const val WEIGHT_MIN: Double = Tuning.WEIGHT_MIN

/** Nor above this, so no single term can drown out the other two. */
const val WEIGHT_MAX: Double = Tuning.WEIGHT_MAX

/** One recorded change to the weights, and the case for it. */
data class WeightAdjustment(
    val id: String,
    val at: LocalDateTime,
    val kind: WeightKind,
    val from: Double,
    val to: Double,
    /** The feedback that triggered it, or null for a manual override. */
    val feedback: ScheduleFeedback?,
    /** How many consecutive identical responses it took. Zero when manual. */
    val streak: Int,
    val weightsAfter: Weights,
    val reason: String,
) {
    val isManual: Boolean get() = feedback == null

    val delta: Double get() = to - from

    /** One line that fully explains the change without any other context. */
    fun describe(): String = reason
}

/**
 * Decides whether [recentFeedback] has earned an adjustment, and what it should
 * be.
 *
 * [recentFeedback] must be **newest first**. Returns null far more often than
 * not — three consecutive identical complaints is a deliberately high bar,
 * because weights that twitch after every schedule would make the app's
 * behaviour impossible to predict or to reason about after the fact.
 *
 * Which weight moves:
 *  - "Too aggressive" lowers `w_deadline`, the term that drives urgency. If it
 *    is already at the floor, `w_adherence_trend` is lowered instead.
 *  - "Too lax" does the reverse.
 *  - `w_importance` is **never** moved automatically. It encodes the user's own
 *    stated judgement of what matters, and an app quietly deciding that its
 *    owner cares less about their exams than they said would be overstepping.
 *    It remains available to set by hand.
 */
fun evaluateFeedback(
    current: Weights,
    recentFeedback: List<ScheduleFeedback>,
    now: LocalDateTime,
    id: String,
): WeightAdjustment? {
    val streak = leadingStreak(recentFeedback) ?: return null
    if (streak.length < FEEDBACK_STREAK_THRESHOLD) return null

    val direction = when (streak.feedback) {
        ScheduleFeedback.TOO_AGGRESSIVE -> -WEIGHT_STEP
        ScheduleFeedback.TOO_LAX -> WEIGHT_STEP
        ScheduleFeedback.JUST_RIGHT -> return null
    }

    val kind = chooseWeight(current, direction) ?: return null
    val from = current.valueOf(kind)
    val to = (from + direction).coerceIn(WEIGHT_MIN, WEIGHT_MAX)
    if (to == from) return null

    val after = current.withWeight(kind, to).renormalised(kind)

    return WeightAdjustment(
        id = id,
        at = now,
        kind = kind,
        from = from,
        to = to,
        feedback = streak.feedback,
        streak = streak.length,
        weightsAfter = after,
        reason = buildString {
            append("${streak.length} consecutive ")
            append(streak.feedback.label())
            append(" responses, so ")
            append(kind.label())
            append(if (direction < 0) " was lowered" else " was raised")
            append(" from %.2f to %.2f".format(from, to))
            append("; the other weights were rescaled to keep the total at 1.00")
        },
    )
}

/**
 * Records a weight set by hand.
 *
 * Manual overrides go into the same history as automatic ones, so the log reads
 * as one story rather than two. They carry no feedback and no streak, which is
 * exactly how you tell them apart.
 */
fun manualAdjustment(
    current: Weights,
    kind: WeightKind,
    value: Double,
    now: LocalDateTime,
    id: String,
): WeightAdjustment? {
    val from = current.valueOf(kind)
    val to = value.coerceIn(WEIGHT_MIN, WEIGHT_MAX)
    if (to == from) return null

    val after = current.withWeight(kind, to).renormalised(kind)

    return WeightAdjustment(
        id = id,
        at = now,
        kind = kind,
        from = from,
        to = to,
        feedback = null,
        streak = 0,
        weightsAfter = after,
        reason = "set by hand: ${kind.label()} changed from %.2f to %.2f".format(from, to) +
            "; the other weights were rescaled to keep the total at 1.00",
    )
}

/** How many identical responses lead the list, and which they were. */
private data class Streak(val feedback: ScheduleFeedback, val length: Int)

private fun leadingStreak(recent: List<ScheduleFeedback>): Streak? {
    val first = recent.firstOrNull() ?: return null
    return Streak(first, recent.takeWhile { it == first }.size)
}

/**
 * Picks the weight to move: deadline first, adherence trend as the fallback
 * when deadline has already hit the relevant bound.
 */
private fun chooseWeight(current: Weights, direction: Double): WeightKind? {
    val deadlineAtBound = if (direction < 0) {
        current.deadline <= WEIGHT_MIN
    } else {
        current.deadline >= WEIGHT_MAX
    }
    if (!deadlineAtBound) return WeightKind.DEADLINE

    val trendAtBound = if (direction < 0) {
        current.adherenceTrend <= WEIGHT_MIN
    } else {
        current.adherenceTrend >= WEIGHT_MAX
    }
    // Both pressure terms are pinned; there is nowhere left to go, and saying
    // so is better than logging an adjustment that changed nothing.
    return if (trendAtBound) null else WeightKind.ADHERENCE_TREND
}

fun Weights.valueOf(kind: WeightKind): Double = when (kind) {
    WeightKind.DEADLINE -> deadline
    WeightKind.IMPORTANCE -> importance
    WeightKind.ADHERENCE_TREND -> adherenceTrend
}

fun Weights.withWeight(kind: WeightKind, value: Double): Weights = when (kind) {
    WeightKind.DEADLINE -> copy(deadline = value)
    WeightKind.IMPORTANCE -> copy(importance = value)
    WeightKind.ADHERENCE_TREND -> copy(adherenceTrend = value)
}

/**
 * Rescales the two weights that were not touched so the three still sum to 1.
 *
 * Without this the score would drift out of its 0.0-1.0 range and stop being
 * comparable with scores recorded before the change — which would quietly break
 * every explanation in the decision log.
 *
 * [fixed] keeps the value it was just given; the remainder is shared between
 * the others in the proportion they already had.
 */
fun Weights.renormalised(fixed: WeightKind): Weights {
    val others = WeightKind.entries.filter { it != fixed }
    val remaining = 1.0 - valueOf(fixed)
    val othersTotal = others.sumOf { valueOf(it) }

    // If the others are somehow both zero there is no proportion to preserve,
    // so split the remainder evenly rather than dividing by zero.
    val shares = if (othersTotal <= 0.0) {
        others.associateWith { remaining / others.size }
    } else {
        others.associateWith { remaining * (valueOf(it) / othersTotal) }
    }

    var result = this
    shares.forEach { (kind, value) ->
        result = result.withWeight(kind, value.coerceIn(0.0, 1.0))
    }
    return result
}

private fun ScheduleFeedback.label(): String = when (this) {
    ScheduleFeedback.TOO_AGGRESSIVE -> "\"too aggressive\""
    ScheduleFeedback.TOO_LAX -> "\"too lax\""
    ScheduleFeedback.JUST_RIGHT -> "\"just right\""
}

fun WeightKind.label(): String = when (this) {
    WeightKind.DEADLINE -> "deadline pressure"
    WeightKind.IMPORTANCE -> "importance"
    WeightKind.ADHERENCE_TREND -> "adherence pressure"
}
