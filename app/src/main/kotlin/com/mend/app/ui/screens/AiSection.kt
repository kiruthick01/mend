package com.mend.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The optional AI layer, and what it is for.
 *
 * Worded so that switching it off does not read like losing a feature, because
 * it mostly is not one: the schedule, the warnings and the proposals are all
 * produced without it. What the key buys is exactly one thing — the ability to
 * type a change in prose instead of filling in a form.
 *
 * It used to claim it also wrote better explanations. It does not: the generator
 * exists and is tested, but nothing calls it, and the cards still render the
 * engine's templated text. Describing a feature the app does not have is worse
 * than not having it, so the claim is gone until the wiring is.
 */
@Composable
fun AiSection(
    hasKey: Boolean,
    maskedKey: String?,
    enabled: Boolean,
    onSetKey: (String?) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    var showKeyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Language features",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Lets you describe a change in your own words instead of " +
                        "filling in a form. Everything else works exactly the same " +
                        "without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onSetEnabled)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (hasKey) "API key set  ·  $maskedKey" else "No API key set",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showKeyDialog = true }) {
                Text(if (hasKey) "Change" else "Add key")
            }
        }

        Text(
            text = "Your key is stored on this device only and is never sent " +
                "anywhere except to Anthropic. Mend never decides anything with " +
                "it — scheduling is ordinary arithmetic either way.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            hasExistingKey = hasKey,
            onDismiss = { showKeyDialog = false },
            onSave = {
                onSetKey(it)
                showKeyDialog = false
            },
        )
    }
}

@Composable
private fun ApiKeyDialog(
    hasExistingKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anthropic API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key") },
                    placeholder = { Text("sk-ant-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Kept on this device, in this app only. A key on a phone " +
                        "can be read by anyone who can unlock the phone, so use one " +
                        "you are willing to rotate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank(),
                onClick = { onSave(key.trim()) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (hasExistingKey) {
                    TextButton(onClick = { onSave(null) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
