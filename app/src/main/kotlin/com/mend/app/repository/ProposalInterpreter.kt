package com.mend.app.repository

import com.mend.app.ai.AiAvailability
import com.mend.app.ai.AiBridge
import com.mend.app.ai.AiResult
import com.mend.app.ai.ParseContext
import com.mend.engine.model.Habit
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.StaticBlock
import com.mend.engine.nlp.StructuredConstraint
import com.mend.engine.nlp.ValidationResult
import com.mend.engine.nlp.validateConstraint
import java.time.LocalDate

/**
 * Turning "propose different" prose into something the user can accept.
 *
 * The order here is the whole point, and it is the order PROJECT_PLAN.md
 * specifies: **parse, then validate, then ask.** The AI never gets to apply
 * anything; it gets to suggest a shape, the deterministic engine decides
 * whether that shape is coherent against the real day, and only what survives
 * both is put in front of the user — who still has to say yes.
 *
 * Every failure mode lands somewhere sensible rather than as an error: no key,
 * no network, nothing understood, and nothing viable are four different
 * outcomes and the UI says something different for each.
 */
class ProposalInterpreter(
    private val aiBridge: AiBridge,
) {

    /** What came of trying to interpret what the user wrote. */
    sealed interface Outcome {

        /** Parsed, validated, and worth offering. */
        data class Understood(
            val constraint: StructuredConstraint,
            val summary: String,
            val warnings: List<String>,
        ) : Outcome

        /** Parsed, but the engine will not offer it, and why. */
        data class Refused(val reason: String) : Outcome

        /** The model could not turn it into anything. */
        data class NotUnderstood(val reason: String) : Outcome

        /** No AI available. The manual form is the way through. */
        data class NeedsManualEntry(
            val availability: AiAvailability,
            val detail: String?,
        ) : Outcome
    }

    /**
     * Interprets [text] against the real shape of the user's days.
     *
     * The static layer is passed in so validation checks the change against the
     * day it would actually affect rather than an imagined one — the difference
     * between "keep mornings free" being harmless and it emptying the schedule.
     */
    suspend fun interpret(
        text: String,
        today: LocalDate,
        goalTitles: List<String>,
        staticBlocks: List<StaticBlock>,
        habits: List<Habit>,
        protectedRecovery: List<ProtectedRecovery>,
    ): Outcome {
        val parsed = aiBridge.parseNaturalLanguageInput(
            text = text,
            context = ParseContext(today = today, knownGoalTitles = goalTitles),
        )

        val constraint = when (parsed) {
            is AiResult.Success -> parsed.value
            is AiResult.Unavailable -> return Outcome.NeedsManualEntry(parsed.reason, parsed.detail)
            is AiResult.Failed -> return Outcome.NeedsManualEntry(
                AiAvailability.OFFLINE,
                parsed.message,
            )
        }

        if (constraint is StructuredConstraint.Unrecognised) {
            return Outcome.NotUnderstood(constraint.reason)
        }

        // The engine has the final say, and it gets it before the user is shown
        // anything at all.
        return when (
            val validation = validateConstraint(
                constraint = constraint,
                today = today,
                staticBlocks = staticBlocks,
                habits = habits,
                protectedRecovery = protectedRecovery,
            )
        ) {
            is ValidationResult.Accepted -> Outcome.Understood(
                constraint = validation.constraint,
                summary = validation.constraint.describe(),
                warnings = validation.warnings,
            )

            is ValidationResult.Rejected -> Outcome.Refused(validation.reason)
        }
    }
}
