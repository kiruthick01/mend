package com.mend.engine.nlp

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The only shapes an AI parse is allowed to produce.
 *
 * This is the narrow gate PROJECT_PLAN.md Section 1 requires: the AI translates
 * language *in*, and that is all it does. It cannot express "reschedule this",
 * "lower that priority", or anything else that would amount to a scheduling
 * decision — because none of those are constructible here. Whatever the model
 * returns, it comes back as one of these or as [Unrecognised].
 *
 * Living in the engine rather than the app layer is deliberate. The engine has
 * to validate these before anything is shown to the user, and a type the
 * validator cannot see is a type the validator cannot check.
 */
sealed interface StructuredConstraint {

    /** A short human summary, for the confirmation screen. */
    fun describe(): String

    /**
     * A stretch of days that is largely spoken for.
     *
     * "Exams from next week for five days" — the plan's own worked example.
     */
    data class BusyPeriod(
        val from: LocalDate,
        val to: LocalDate,
        val label: String,
    ) : StructuredConstraint {
        override fun describe(): String =
            "Treat $from to $to as mostly taken up by $label"
    }

    /**
     * A recurring part of the day to keep clear.
     *
     * "Keep mornings free, I think better after lunch."
     */
    data class AvoidWindow(
        val daysOfWeek: Set<DayOfWeek>,
        val from: LocalTime,
        val to: LocalTime,
        val label: String,
    ) : StructuredConstraint {
        override fun describe(): String =
            "Keep $from to $to clear of scheduled work ($label)"
    }

    /**
     * A deadline the user says has moved.
     *
     * Carries a *hint* rather than an id: the model is describing a goal in
     * words and has no access to the database, so resolving which goal it meant
     * is the app's job, and confirming it is the user's.
     */
    data class DeadlineChange(
        val goalTitleHint: String,
        val newDeadline: LocalDate,
    ) : StructuredConstraint {
        override fun describe(): String =
            "Move the deadline for \"$goalTitleHint\" to $newDeadline"
    }

    /**
     * Nothing usable came back.
     *
     * A first-class outcome, not an error. The model failing to understand
     * something is ordinary, and the honest response is to say so and fall back
     * to the manual form.
     */
    data class Unrecognised(val reason: String) : StructuredConstraint {
        override fun describe(): String = "Could not turn that into a change: $reason"
    }
}

/** Everything the explanation generator is allowed to know about. */
sealed interface ExplanationContext {

    /** A deadline projection that misses, for the Phase 5 warning. */
    data class GuiltTrip(
        val goalTitle: String,
        val lessonsCompleted: Int,
        val totalLessons: Int,
        val daysRemaining: Long,
        val remainingMinutes: Long,
        val observedMinutesPerDay: Int,
        val shortfallMinutes: Int,
    ) : ExplanationContext

    /** A proposed change to tomorrow, for the Phase 4 flow. */
    data class Reshuffle(
        val effectiveFrom: LocalDate,
        val triggers: List<String>,
        val changes: List<String>,
    ) : ExplanationContext
}
