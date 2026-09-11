package com.mend.engine.nlp

/**
 * The messages Mend writes for itself, with no model involved.
 *
 * These are the offline fallback PROJECT_PLAN.md requires, and they live in the
 * engine on purpose: the app must stay *fully usable* with the network off, and
 * a fallback that lives behind the network layer is a fallback that can fail
 * with it.
 *
 * They are also the reference the generated versions are judged against. The AI
 * is allowed to phrase these better; it is not allowed to say anything these do
 * not already say, because everything here is derived from the same numbers the
 * deterministic engine computed.
 */
object TemplatedMessages {

    /** The Phase 5 warning, written plainly. */
    fun guiltTrip(context: ExplanationContext.GuiltTrip): String = buildString {
        append("${context.goalTitle}: ")
        append("${context.lessonsCompleted} of ${context.totalLessons} lessons done. ")

        append(
            when (context.daysRemaining) {
                0L -> "The deadline is today. "
                1L -> "One day left. "
                else -> "${context.daysRemaining} days left. "
            },
        )

        append("${context.remainingMinutes}m of work remains, and you have been ")
        append("averaging ${context.observedMinutesPerDay}m a day, which leaves you ")
        append("${context.shortfallMinutes}m short. ")
        append("This one has a real consequence, so it is worth saying plainly.")
    }

    /** The Phase 4 proposal summary, written plainly. */
    fun reshuffle(context: ExplanationContext.Reshuffle): String = buildString {
        append("Because ")
        append(context.triggers.joinToString("; "))
        append(", here is a different plan for ${context.effectiveFrom} onward.")

        if (context.changes.isEmpty()) {
            append(" As it turns out, nothing needs to move.")
        } else {
            append(" ")
            append(context.changes.joinToString("; "))
            append(".")
        }
    }

    /** Dispatch for either context. */
    fun forContext(context: ExplanationContext): String = when (context) {
        is ExplanationContext.GuiltTrip -> guiltTrip(context)
        is ExplanationContext.Reshuffle -> reshuffle(context)
    }
}
