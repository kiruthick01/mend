package com.mend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mend.app.repository.ProposalInterpreter
import com.mend.engine.nlp.StructuredConstraint

/**
 * "I would rather you did something else."
 *
 * Write it, see what Mend made of it, then go and make the change.
 *
 * That last part is deliberate and it is a step back from what this screen used
 * to claim. Reading prose and *applying* it are two different jobs, and only
 * the first is built: a parsed constraint is never written anywhere, so a
 * button that said "Use this" produced a confirmation, a replan against
 * unchanged data, and no actual change. A screen that says it understood you
 * and then quietly does nothing is worse than one that admits the limit.
 *
 * So the interpretation stays — it is genuinely useful, it is validated by the
 * engine, and it tells you in Mend's own vocabulary what you would need to set
 * up — but the only thing this dialog commits is your words, kept against the
 * proposal. The change itself is made in Tasks, where it actually takes effect.
 *
 * If there is no AI available the text box behaves identically, minus the
 * translation. Losing the user's own words would be worse than not reading
 * them.
 */
@Composable
fun ProposeDifferentDialog(
    state: ProposeDifferentState,
    onTextChange: (String) -> Unit,
    onInterpret: () -> Unit,
    onSaveVerbatim: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What would you rather?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    placeholder = { Text("Exams from next week for five days") },
                    minLines = 3,
                    enabled = !state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                )

                when (val outcome = state.outcome) {
                    null -> if (state.isWorking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            Text(
                                text = "Reading that…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    is ProposalInterpreter.Outcome.Understood -> Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Mend understood:",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = outcome.summary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        outcome.warnings.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            text = "Mend cannot make this change for you yet. To do " +
                                "it: " + howToApply(outcome.constraint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Saving keeps your words against this proposal so " +
                                "the reason is on record either way.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is ProposalInterpreter.Outcome.Refused -> Message(
                        title = "Mend will not do that",
                        body = outcome.reason,
                        isError = true,
                    )

                    is ProposalInterpreter.Outcome.NotUnderstood -> Message(
                        title = "Could not turn that into a change",
                        body = "${outcome.reason}. Try naming a date range, a time of " +
                            "day to keep clear, or a deadline that has moved.",
                        isError = false,
                    )

                    is ProposalInterpreter.Outcome.NeedsManualEntry -> Message(
                        title = "No language features right now",
                        body = buildString {
                            append(
                                when (outcome.availability) {
                                    com.mend.app.ai.AiAvailability.NO_API_KEY ->
                                        "No API key is set, so Mend cannot read prose."
                                    com.mend.app.ai.AiAvailability.DISABLED ->
                                        "Language features are switched off in Settings."
                                    else ->
                                        "Could not reach the API."
                                },
                            )
                            append(" What you wrote will be saved as-is against this ")
                            append("proposal, and you can make the change by hand in Tasks.")
                            outcome.detail?.let { append("\n\n($it)") }
                        },
                        isError = false,
                    )
                }
            }
        },
        confirmButton = {
            when (state.outcome) {
                // Understood and NeedsManualEntry now do the same thing, because
                // the same thing is all either of them can honestly do.
                is ProposalInterpreter.Outcome.Understood,
                is ProposalInterpreter.Outcome.NeedsManualEntry,
                -> TextButton(
                    onClick = onSaveVerbatim,
                    enabled = state.text.isNotBlank(),
                ) { Text("Save what I wrote") }

                else -> TextButton(
                    onClick = onInterpret,
                    enabled = state.text.isNotBlank() && !state.isWorking,
                ) { Text("Read this") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What the user has to do by hand, in the vocabulary of the screen that does it.
 *
 * This is the honest replacement for the button that used to claim it would
 * apply the change itself. Naming the exact thing to create is the difference
 * between a translation that helps and one that only proves the parser ran.
 */
private fun howToApply(constraint: StructuredConstraint): String = when (constraint) {
    is StructuredConstraint.BusyPeriod ->
        "add a fixed block called \"${constraint.label}\" in Tasks, covering " +
            "${constraint.from} to ${constraint.to}."

    is StructuredConstraint.AvoidWindow ->
        "add a fixed block called \"${constraint.label}\" in Tasks on " +
            constraint.daysOfWeek.joinToString(", ") {
                it.name.lowercase().replaceFirstChar(Char::uppercase)
            } +
            ", from ${constraint.from} to ${constraint.to}."

    is StructuredConstraint.DeadlineChange ->
        "open \"${constraint.goalTitleHint}\" in Tasks and set its deadline to " +
            "${constraint.newDeadline}."

    // Filtered out before this screen is reached; handled so the when is total.
    is StructuredConstraint.Unrecognised -> "describe it again, more specifically."
}

@Composable
private fun Message(title: String, body: String, isError: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(text = body, style = MaterialTheme.typography.bodySmall)
    }
}

/** What the dialog is currently showing. */
data class ProposeDifferentState(
    val proposalId: String,
    val text: String = "",
    val isWorking: Boolean = false,
    val outcome: ProposalInterpreter.Outcome? = null,
)
