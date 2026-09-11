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

/**
 * "I would rather you did something else."
 *
 * The flow is deliberately three steps and not two: write it, **see what Mend
 * understood**, then decide. The middle step is not politeness — it is where a
 * misread turns into a corrected sentence instead of a changed schedule.
 *
 * If there is no AI available the text box is still there and still saves what
 * was written, because losing the user's own words would be worse than not
 * acting on them yet.
 */
@Composable
fun ProposeDifferentDialog(
    state: ProposeDifferentState,
    onTextChange: (String) -> Unit,
    onInterpret: () -> Unit,
    onAccept: () -> Unit,
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
                            text = "Accepting this only asks for a new plan for " +
                                "tomorrow. You will still get to accept or decline it.",
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
                is ProposalInterpreter.Outcome.Understood -> TextButton(onClick = onAccept) {
                    Text("Use this")
                }

                is ProposalInterpreter.Outcome.NeedsManualEntry -> TextButton(
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
