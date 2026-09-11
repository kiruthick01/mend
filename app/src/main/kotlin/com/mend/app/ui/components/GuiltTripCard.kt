package com.mend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/**
 * A warning about work with a real-world consequence.
 *
 * It ends in a question, not an instruction. PROJECT_PLAN.md is explicit that
 * the message requests permission to reshuffle rather than reshuffling — and
 * saying yes only routes into the Phase 4 flow, where there are still three
 * answers. Nothing changes without a second, separate consent.
 *
 * "Not now" is a real button for the same reason: a warning that cannot be
 * declined is not a warning, it is nagging, and nagging gets tuned out along
 * with everything else.
 */
@Composable
fun GuiltTripCard(
    message: String,
    reshuffleRequest: String,
    onRequestReshuffle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "This one has a deadline that bites",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = reshuffleRequest,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRequestReshuffle) { Text("Yes, replan") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
