package com.mend.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mend.app.MendApplication
import com.mend.app.progress.AnkiReader
import com.mend.app.progress.LinkStatus
import com.mend.app.progress.ProgressInsights
import com.mend.app.progress.ProgressKind
import com.mend.app.progress.ProgressLink
import com.mend.app.progress.ProgressSync
import com.mend.app.progress.UsageReader
import kotlinx.coroutines.launch

/**
 * Connect a goal to Duolingo or AnkiDroid so finished practice there ticks off
 * the goal's next item here. Entirely optional; with no links nothing runs.
 */
@Composable
fun ProgressSection() {
    val context = LocalContext.current
    val app = context.applicationContext as MendApplication
    val scope = rememberCoroutineScope()
    val goals by app.goalRepository.observeGoals().collectAsStateWithLifecycle(emptyList())

    var links by remember { mutableStateOf(app.progressLinks.all()) }
    var statuses by remember { mutableStateOf<Map<String, LinkStatus>>(emptyMap()) }
    var adding by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            syncing = true
            statuses = ProgressSync(app).run().associateBy { it.link.key }
            links = app.progressLinks.all()
            syncing = false
        }
    }

    LaunchedEffect(Unit) { if (links.isNotEmpty()) refresh() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Connected apps",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Link a goal to Duolingo or AnkiDroid. When you have practised there, " +
                "Mend ticks off that goal's next item and records it in your adherence.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        links.forEach { link ->
            val title = goals.firstOrNull { it.goal.id == link.goalId }?.goal?.title ?: "Deleted goal"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("$title  ·  ${link.kind.label}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = (if (link.target.isBlank()) "Time in app" else link.target) +
                            "  ·  " + (statuses[link.key]?.message ?: "Not checked yet"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    app.progressLinks.remove(link)
                    links = app.progressLinks.all()
                }) { Text("Remove") }
            }
            val goal = goals.firstOrNull { it.goal.id == link.goalId }?.goal
            val peak = ProgressInsights.peakWindow(
                app.progressLinks.records(link.key),
                java.time.LocalDate.now(),
            )
            if (goal != null && peak != null && !goal.hasWorkWindow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "You mostly use ${link.kind.label} ${peak.first} to ${peak.second}. " +
                            "Schedule \"${goal.title}\" then?",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            app.goalRepository.saveGoal(
                                goal.copy(workWindowStart = peak.first, workWindowEnd = peak.second),
                            )
                        }
                    }) { Text("Use") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { adding = true }, enabled = goals.isNotEmpty()) { Text("Add link") }
            if (links.isNotEmpty()) {
                TextButton(onClick = ::refresh, enabled = !syncing) {
                    Text(if (syncing) "Checking…" else "Check now")
                }
            }
        }

        Text(
            text = "Duolingo has no official API. A username reads your public profile " +
                "(streak), which Duolingo can change at any time. Leave it blank to count " +
                "time spent in the app instead. Time-in-app needs Usage access.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (adding) {
        AddLinkDialog(
            goals = goals.map { it.goal.id to it.goal.title },
            onDismiss = { adding = false },
            onAdd = { link ->
                app.progressLinks.save(link)
                links = app.progressLinks.all()
                adding = false
                refresh()
            },
        )
    }
}

@Composable
private fun AddLinkDialog(
    goals: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onAdd: (ProgressLink) -> Unit,
) {
    val context = LocalContext.current
    var goalId by remember { mutableStateOf(goals.first().first) }
    var kind by remember { mutableStateOf(ProgressKind.DUOLINGO) }
    var target by remember { mutableStateOf("") }

    val anki = remember { AnkiReader(context) }
    val usage = remember { UsageReader(context) }
    var decks by remember { mutableStateOf<List<String>>(emptyList()) }
    var ankiGranted by remember { mutableStateOf(anki.hasPermission()) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ankiGranted = it
    }
    LaunchedEffect(kind, ankiGranted) {
        decks = if (kind == ProgressKind.ANKI) anki.deckIds()?.keys?.toList().orEmpty() else emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link a goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Goal", style = MaterialTheme.typography.labelMedium)
                goals.forEach { (id, name) ->
                    FilterChip(selected = id == goalId, onClick = { goalId = id }, label = { Text(name) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressKind.entries.forEach {
                        FilterChip(
                            selected = it == kind,
                            onClick = { kind = it; target = "" },
                            label = { Text(it.label) },
                        )
                    }
                }
                if (kind == ProgressKind.DUOLINGO) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("Duolingo username (optional)") },
                        singleLine = true,
                    )
                } else {
                    if (!anki.isInstalled()) {
                        Text("AnkiDroid is not installed. Leave the deck empty to use time in app.")
                    } else if (!ankiGranted) {
                        TextButton(onClick = { permission.launch(AnkiReader.PERMISSION) }) {
                            Text("Allow AnkiDroid access")
                        }
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        decks.forEach { deck ->
                            FilterChip(
                                selected = deck == target,
                                onClick = { target = if (deck == target) "" else deck },
                                label = { Text(deck) },
                            )
                        }
                    }
                }
                if (target.isBlank() && !usage.hasAccess()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(usage.settingsIntent()) }
                    }) { Text("Grant Usage access") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(ProgressLink(goalId, kind, target.trim())) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Today's connected-app status, one line per link. Renders nothing when
 * nothing is linked, so the screen is unchanged for anyone not using it.
 */
@Composable
fun ProgressStrip(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as MendApplication
    val revision by app.progressLinks.revision.collectAsStateWithLifecycle()
    val goals by app.goalRepository.observeGoals().collectAsStateWithLifecycle(emptyList())
    val today = java.time.LocalDate.now()
    val lines = remember(revision, goals) {
        app.progressLinks.all().mapNotNull { link ->
            val title = goals.firstOrNull { it.goal.id == link.goalId }?.goal?.title
                ?: return@mapNotNull null
            title to ProgressInsights.summaryLine(link.kind, app.progressLinks.records(link.key), today)
        }
    }
    if (lines.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        lines.forEach { (goal, line) ->
            Text(
                text = "$goal  ·  $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A goal's practice over the last week, and XP gained where Duolingo reports it. */
@Composable
fun GoalProgressSection(goalId: String) {
    val app = LocalContext.current.applicationContext as MendApplication
    val revision by app.progressLinks.revision.collectAsStateWithLifecycle()
    val today = java.time.LocalDate.now()
    val rows = remember(revision, goalId) {
        app.progressLinks.all().filter { it.goalId == goalId }.map { link ->
            val records = app.progressLinks.records(link.key)
            Triple(link, ProgressInsights.lastDays(records, today), ProgressInsights.xpGained(records, today))
        }
    }
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Practice, last 7 days", style = MaterialTheme.typography.titleSmall)
        rows.forEach { (link, days, xp) ->
            Text(
                text = link.kind.label + "  " + days.joinToString(" ") { if (it) "●" else "○" } +
                    (xp?.let { "   +$it XP" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
