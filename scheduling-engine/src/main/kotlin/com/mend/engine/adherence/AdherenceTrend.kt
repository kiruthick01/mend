package com.mend.engine.adherence

import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.GoalTask
import java.time.LocalDate

/**
 * Choosing the adherence figure a task should actually be scored on.
 *
 * The priority score has always weighted an adherence term; until now every
 * task carried the neutral value, so the term was present but inert. Feeding
 * the recorded rate in is what makes work you keep skipping climb the ranking
 * instead of quietly slipping again.
 *
 * The difficulty is that most individual sub-tasks are scheduled once or twice
 * ever, so their personal history is far too thin to rank on — one missed
 * session would make a brand-new task look chronically neglected. So the
 * figure is resolved in three steps, widest evidence first:
 *
 *  1. **This task's own record**, if it has enough observations to mean
 *     something.
 *  2. **This kind of work's record** otherwise — if reading is honoured 40% of
 *     the time across the whole goal, a new reading sub-task inherits that,
 *     which is a fair prior and the thing the user can actually recognise.
 *  3. **Neutral**, when neither has enough behind it. Silence, not suspicion.
 */

/**
 * The adherence value [taskId] should be scored on, 0.0-1.0.
 *
 * [typeOf] maps a task id to its user-named type, so step 2 can find the
 * right cohort. Returning null from it simply skips that step.
 */
fun resolveAdherenceTrend(
    log: List<AdherenceLogEntry>,
    taskId: String,
    asOf: LocalDate,
    windowDays: Long = DEFAULT_ROLLING_WINDOW_DAYS,
    typeOf: (String) -> String?,
): Double {
    val own = computeAdherence(log, taskId, asOf, windowDays)
    if (own.hasSignal) return own.adherenceRate

    val type = typeOf(taskId) ?: return GoalTask.NEUTRAL_ADHERENCE
    val cohort = computeAdherenceByType(log, asOf, windowDays, typeOf)[type]
    if (cohort != null && cohort.hasSignal) return cohort.adherenceRate

    return GoalTask.NEUTRAL_ADHERENCE
}

/** Which of the three sources a resolved figure came from. */
enum class AdherenceSource {
    /** The task's own recorded history. */
    OWN_HISTORY,

    /** The record of this kind of work generally. */
    TYPE_HISTORY,

    /** Nothing with enough behind it; the neutral default. */
    NEUTRAL,
}

/**
 * As [resolveAdherenceTrend], but saying where the number came from.
 *
 * The allocator logs its reasoning, and "this ranked higher because you keep
 * skipping it" is only a defensible claim if it can name the evidence.
 */
fun resolveAdherenceTrendWithSource(
    log: List<AdherenceLogEntry>,
    taskId: String,
    asOf: LocalDate,
    windowDays: Long = DEFAULT_ROLLING_WINDOW_DAYS,
    typeOf: (String) -> String?,
): Pair<Double, AdherenceSource> {
    val own = computeAdherence(log, taskId, asOf, windowDays)
    if (own.hasSignal) return own.adherenceRate to AdherenceSource.OWN_HISTORY

    val type = typeOf(taskId)
    if (type != null) {
        val cohort = computeAdherenceByType(log, asOf, windowDays, typeOf)[type]
        if (cohort != null && cohort.hasSignal) {
            return cohort.adherenceRate to AdherenceSource.TYPE_HISTORY
        }
    }

    return GoalTask.NEUTRAL_ADHERENCE to AdherenceSource.NEUTRAL
}
