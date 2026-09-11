package com.mend.engine.fragility

import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.AdherenceOutcome
import com.mend.engine.tuning.Tuning
import java.time.Duration

/**
 * The delay-negotiation tier.
 *
 * Negotiating a delay is a last resort, not an option on every task. If
 * anything can be postponed by asking, then everything gets postponed by
 * asking, and the schedule stops being a commitment.
 *
 * So eligibility is narrow and both halves are required, per PROJECT_PLAN.md
 * Phase 4:
 *  - the work has **already been skipped** at least [FRAGILE_SKIP_THRESHOLD]
 *    times, so it is demonstrably piling up rather than merely inconvenient; and
 *  - it is **sequential** — nothing else can proceed past it — so postponing it
 *    genuinely blocks progress rather than just reordering it.
 *
 * Work that is parallelisable is never eligible however often it is skipped:
 * there is something else to do instead, which is precisely why it does not
 * need a negotiated delay.
 */

/** Skips before work counts as piling up. Two, per "skipped once/twice". */
const val FRAGILE_SKIP_THRESHOLD: Int = Tuning.FRAGILE_SKIP_THRESHOLD

/** The most delay that can be negotiated at once. */
val MAX_NEGOTIATED_DELAY: Duration = Tuning.MAX_NEGOTIATED_DELAY

/** What the adherence history says about one piece of work. */
data class TaskFragility(
    val taskId: String,
    val skipCount: Int,
    val lateCount: Int,
    val completedCount: Int,
    val isSequential: Boolean,
    /** Demonstrably piling up: skipped at least [FRAGILE_SKIP_THRESHOLD] times. */
    val pileupRisk: Boolean,
    /** Derived, per Section 3. Both halves of the test must hold. */
    val delayNegotiationEligible: Boolean,
    val reason: String,
) {
    /** 0.0-1.0. Feeds `adherenceTrend` on a GoalTask from Phase 6 onward. */
    val adherenceRate: Double
        get() {
            val total = skipCount + lateCount + completedCount
            return if (total == 0) 1.0 else completedCount.toDouble() / total
        }
}

/**
 * Reads [log] for what happened to [taskId] and decides whether it has earned
 * the right to negotiate a delay.
 *
 * Pure, and driven entirely by the append-only adherence stream rather than by
 * a mutable flag on the task. A flag can be set by anything; a history cannot
 * be argued with.
 */
fun assessFragility(
    taskId: String,
    log: List<AdherenceLogEntry>,
    isSequential: Boolean,
): TaskFragility {
    val forTask = log.filter { it.taskId == taskId }

    val skips = forTask.count { it.outcome == AdherenceOutcome.MISSED }
    val late = forTask.count { it.outcome == AdherenceOutcome.LATE }
    val done = forTask.count { it.outcome == AdherenceOutcome.ON_TIME }

    val pileup = skips >= FRAGILE_SKIP_THRESHOLD
    val eligible = pileup && isSequential

    val reason = when {
        eligible ->
            "skipped $skips times and sequential: nothing downstream can start " +
                "until it does, so a negotiated delay is the honest option"

        pileup && !isSequential ->
            "skipped $skips times, but this work is parallelisable — there is " +
                "other work to do instead, so it does not need a negotiated delay"

        !pileup && isSequential ->
            "sequential, but only skipped $skips time(s); the threshold is " +
                "$FRAGILE_SKIP_THRESHOLD before delay becomes negotiable"

        else ->
            "skipped $skips time(s) and parallelisable: not fragile"
    }

    return TaskFragility(
        taskId = taskId,
        skipCount = skips,
        lateCount = late,
        completedCount = done,
        isSequential = isSequential,
        pileupRisk = pileup,
        delayNegotiationEligible = eligible,
        reason = reason,
    )
}

/** A delay that was asked for, and whether it was granted. */
data class DelayNegotiation(
    val taskId: String,
    val requested: Duration,
    val granted: Duration?,
    val reason: String,
) {
    val wasGranted: Boolean get() = granted != null
}

/**
 * Grants a delay, but only to work that has earned the right to ask.
 *
 * Refusal is the default and the common case. A grant is capped at
 * [MAX_NEGOTIATED_DELAY]: an unbounded delay is a cancellation with better
 * manners.
 */
fun negotiateDelay(
    fragility: TaskFragility,
    requested: Duration,
    maximum: Duration = MAX_NEGOTIATED_DELAY,
): DelayNegotiation {
    if (!fragility.delayNegotiationEligible) {
        return DelayNegotiation(
            taskId = fragility.taskId,
            requested = requested,
            granted = null,
            reason = "not eligible to negotiate a delay — ${fragility.reason}",
        )
    }

    if (requested.isNegative || requested.isZero) {
        return DelayNegotiation(
            taskId = fragility.taskId,
            requested = requested,
            granted = null,
            reason = "a delay must be a positive amount of time",
        )
    }

    val granted = minOf(requested, maximum)
    return DelayNegotiation(
        taskId = fragility.taskId,
        requested = requested,
        granted = granted,
        reason = if (granted < requested) {
            "granted ${granted.toHours()}h of the ${requested.toHours()}h asked for; " +
                "${maximum.toHours()}h is the cap — ${fragility.reason}"
        } else {
            "granted ${granted.toHours()}h — ${fragility.reason}"
        },
    )
}
