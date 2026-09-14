package com.mend.app.ai

import com.mend.engine.nlp.ExplanationContext
import com.mend.engine.nlp.StructuredConstraint
import com.mend.engine.nlp.TemplatedMessages

/**
 * The app with no AI at all.
 *
 * Not a stub and not a test double — this is a supported way to run Mend, and
 * the one it falls back to whenever the network is unreachable or no key is
 * configured. Every core function works here: the schedule is still built,
 * warnings still fire, proposals still arrive. What is lost is phrasing and the
 * ability to type a change in prose, and the UI offers a structured form for
 * the latter.
 *
 * Messages come from [TemplatedMessages], which lives in the engine, so this
 * path has no network dependency to fail with.
 */
class OfflineAiBridge(
    private val reason: AiAvailability = AiAvailability.NO_API_KEY,
) : AiBridge {

    override suspend fun availability(): AiAvailability = reason

    override suspend fun parseNaturalLanguageInput(
        text: String,
        context: ParseContext,
    ): AiResult<StructuredConstraint> = AiResult.Unavailable(
        reason = reason,
        // Deliberately no detail. The dialog already says where to make the
        // change, and this used to add "type the change in the form instead",
        // which contradicted it and pointed at a form that does not exist —
        // editing is done in Tasks, on the same screens that created the thing.
        detail = null,
    )

    /**
     * Succeeds rather than reporting unavailable.
     *
     * A templated explanation is a real answer, not a degraded one — the
     * numbers in it are the same numbers a generated version would have been
     * given. Only the prose is plainer, and the caller has nothing useful to do
     * with the knowledge that no model was involved.
     */
    override suspend fun generateExplanationMessage(
        context: ExplanationContext,
    ): AiResult<String> = AiResult.Success(TemplatedMessages.forContext(context))
}
