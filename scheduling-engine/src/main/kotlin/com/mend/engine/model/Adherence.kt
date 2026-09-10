package com.mend.engine.model

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One immutable record of how an assigned slot actually went.
 *
 * This stream is **append-only**. Entries are never edited or deleted; a
 * correction is a new entry. It is the single source of truth for behavioural
 * pattern detection in Phase 5, and that is only trustworthy if history cannot
 * be quietly rewritten.
 */
data class AdherenceLogEntry(
    val id: String,
    val taskId: String,
    val date: LocalDate,
    val assignedSlotStart: LocalDateTime,
    val assignedSlotEnd: LocalDateTime,
    val completedAt: LocalDateTime? = null,
    val delayMinutes: Long = 0,
    val outcome: AdherenceOutcome,
)

/**
 * Per-user priority weights.
 *
 * These start fixed and are nudged by the bounded, rule-based feedback loop in
 * Phase 6 — never by a learned model. Every field is inspectable and every
 * change is logged with its cause.
 */
data class Weights(
    val deadline: Double = DEFAULT_DEADLINE,
    val importance: Double = DEFAULT_IMPORTANCE,
    val adherenceTrend: Double = DEFAULT_ADHERENCE_TREND,
) {
    init {
        require(deadline in BOUNDS) { "w_deadline must be in $BOUNDS, was $deadline" }
        require(importance in BOUNDS) { "w_importance must be in $BOUNDS, was $importance" }
        require(adherenceTrend in BOUNDS) {
            "w_adherence_trend must be in $BOUNDS, was $adherenceTrend"
        }
    }

    companion object {
        const val DEFAULT_DEADLINE = 0.5
        const val DEFAULT_IMPORTANCE = 0.35
        const val DEFAULT_ADHERENCE_TREND = 0.15

        val BOUNDS = 0.0..1.0

        val DEFAULT = Weights()
    }
}
