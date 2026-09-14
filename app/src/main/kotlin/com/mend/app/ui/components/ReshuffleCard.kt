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
import com.mend.engine.reshuffle.ReshuffleResponse

/**
 * How many individual moves the card will spell out before summarising.
 *
 * It has to be capped, and not only for readability. Today lays its banners out
 * in a plain column that does not scroll, so an uncapped list grows the card
 * until it pushes its own Accept / Decline / Propose different buttons off the
 * bottom of the screen — at which point the proposal cannot be answered at all.
 * Seen with a goal of ~100 sub-tasks, where the card listed 66 moves.
 *
 * Six is enough to show what kind of change this is. The rest of the answer is
 * the schedule itself, which is on the same screen, just below.
 */
private const val MAX_LISTED_CHANGES = 6

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
                    changes.take(MAX_LISTED_CHANGES).forEach { change ->
                        Text(
                            text = "• $change",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val hidden = changes.size - MAX_LISTED_CHANGES
                    if (hidden > 0) {
                        Text(
                            text = "…and $hidden more. The whole day is below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                OutlinedButton(
                    // Hands straight off to the Today screen, which owns the
                    // read-it-back flow. The text is collected there.
                    onClick = { onRespond(ReshuffleResponse.ProposeDifferent("")) },
                ) {
                    Text("Propose different")
                }
            }
        }
    }
}
