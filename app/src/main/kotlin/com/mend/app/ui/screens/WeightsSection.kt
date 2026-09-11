package com.mend.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mend.engine.model.Weights
import com.mend.engine.weights.WEIGHT_MAX
import com.mend.engine.weights.WEIGHT_MIN
import com.mend.engine.weights.WeightAdjustment
import com.mend.engine.weights.WeightKind
import com.mend.engine.weights.label
import com.mend.engine.weights.valueOf
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/**
 * What the scheduler currently cares about, and why.
 *
 * Both halves matter equally. Showing the values without the history would
 * leave the user with three numbers they cannot account for; showing the
 * history without the ability to override would leave them watching the app
 * change its mind with no way to disagree.
 */
@Composable
fun WeightsSection(
    weights: Weights,
    history: List<WeightAdjustment>,
    onOverride: (WeightKind, Double) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "How work is prioritised",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Every pending task is scored on these three, and the highest " +
                "score gets first pick of your free time. They always add up to 1.00.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        WeightKind.entries.forEach { kind ->
            WeightSlider(
                kind = kind,
                value = weights.valueOf(kind),
                onChange = { onOverride(kind, it) },
            )
        }

        Text(
            text = "Importance is never adjusted automatically — it is your own " +
                "judgement of what matters, so only you change it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AdjustmentHistory(history)
}

/**
 * One weight, with its current share of the score.
 *
 * The slider commits on release rather than on every pixel: each change is an
 * append-only history entry, and a drag would otherwise write fifty of them.
 */
@Composable
private fun WeightSlider(
    kind: WeightKind,
    value: Double,
    onChange: (Double) -> Unit,
) {
    var dragged by remember(value) { mutableStateOf(value.toFloat()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = kind.label().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "%.2f".format(dragged),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onChange(dragged.toDouble()) },
            valueRange = WEIGHT_MIN.toFloat()..WEIGHT_MAX.toFloat(),
            // One notch per 0.05, matching the step the automatic rule uses, so
            // a hand-set value and a learned one are always on the same grid.
            steps = (((WEIGHT_MAX - WEIGHT_MIN) / 0.05) - 1).roundToInt(),
        )
    }
}

/**
 * Every change, newest first, each with the reason recorded at the time.
 *
 * PROJECT_PLAN.md asks that someone can look at this months later and
 * understand exactly why a weight changed. The reason is stored as it was
 * written rather than regenerated, so a later change to the rule cannot
 * retroactively alter the explanation for an older adjustment.
 */
@Composable
private fun AdjustmentHistory(history: List<WeightAdjustment>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "What has changed, and why",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )

        if (history.isEmpty()) {
            Text(
                text = "Nothing yet. These are the starting values; they move only " +
                    "after three consecutive identical answers to the feedback " +
                    "prompt, or when you change them here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        history.forEach { adjustment ->
            Column {
                Text(
                    text = "${adjustment.at.format(STAMP)}  ·  " +
                        if (adjustment.isManual) "by hand" else "from your feedback",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = adjustment.describe(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
