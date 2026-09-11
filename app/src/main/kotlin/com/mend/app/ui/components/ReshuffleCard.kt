package com.mend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mend.engine.reshuffle.ReshuffleResponse

/**
 * A proposed change to tomorrow, with exactly three answers.
 *
 * Three, and only three, is the whole point: PROJECT_PLAN.md makes every
 * schedule change a conversation rather than a silent mutation. There is no
 * dismiss affordance and no way to swipe this away, because "I ignored it" is
 * not one of the three answers — the plan either changes or it does not, and
 * the user is the one who decides which.
 */
@Composable
fun ReshuffleProposalCard(
    summary: String,
    changes: List<String>,
    effectiveFrom: String,
    onRespond: (ReshuffleResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showProposeDifferent by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "A different plan for $effectiveFrom",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = summary, style = MaterialTheme.typography.bodyMedium)

            if (changes.isEmpty()) {
                Text(
                    text = "Nothing actually moves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    changes.forEach { change ->
                        Text(
                            text = "• $change",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Text(
                text = "Today is unchanged either way.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRespond(ReshuffleResponse.Accept) }) {
                    Text("Accept")
                }
                TextButton(onClick = { onRespond(ReshuffleResponse.Decline) }) {
                    Text("Decline")
                }
                OutlinedButton(onClick = { showProposeDifferent = true }) {
                    Text("Propose different")
                }
            }
        }
    }

    if (showProposeDifferent) {
        ProposeDifferentDialog(
            onDismiss = { showProposeDifferent = false },
            onSubmit = {
                onRespond(ReshuffleResponse.ProposeDifferent(it))
                showProposeDifferent = false
            },
        )
    }
}

/**
 * Free text, kept verbatim.
 *
 * Phase 7 routes this through NLP parsing. Until then it is stored exactly as
 * written rather than being forced into a structured form — losing what the
 * user actually said would be worse than not acting on it yet.
 */
@Composable
private fun ProposeDifferentDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What would you rather?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Keep mornings free — I think better after lunch") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "This is saved as written. Mend cannot act on it yet, " +
                        "so nothing changes until you say so.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSubmit(text.trim()) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
