package com.mend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mend.engine.weights.ScheduleFeedback

/**
 * The one question the app asks about its own judgement.
 *
 * Three answers, mirroring PROJECT_PLAN.md exactly, and "just right" is a real
 * answer rather than a way of dismissing the prompt — it resets the streak, so
 * saying it actively prevents a change.
 *
 * Nothing moves on a single response. It takes three consecutive identical
 * answers, which is worth saying out loud here: a prompt that looks like it
 * might silently retune the app is a prompt people learn to avoid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackPrompt(
    onRespond: (ScheduleFeedback) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "How was that plan?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Nothing changes on one answer. It takes three of the same " +
                    "in a row, and you will be told exactly what moved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onRespond(ScheduleFeedback.TOO_AGGRESSIVE) }) {
                    Text("Too aggressive")
                }
                OutlinedButton(onClick = { onRespond(ScheduleFeedback.JUST_RIGHT) }) {
                    Text("Just right")
                }
                OutlinedButton(onClick = { onRespond(ScheduleFeedback.TOO_LAX) }) {
                    Text("Too lax")
                }
            }

            TextButton(onClick = onDismiss) { Text("Skip") }
        }
    }
}

/**
 * What changed, said plainly, immediately after it changed.
 *
 * The whole debuggability constraint rests on this not being silent. A weight
 * that moves without anyone noticing is a weight nobody can later explain.
 */
@Composable
fun WeightChangeNotice(
    reason: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Priorities adjusted",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = reason, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "You can see and change this in Settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    }
}
