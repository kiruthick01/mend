package com.mend.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.mend.app.R

/**
 * The top-level destinations of the app. Phase 0 wires the shell only; the
 * screens behind these routes are intentionally empty placeholders.
 */
enum class MendDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    TODAY("today", R.string.nav_today, Icons.Filled.CalendarToday),
    TASKS("tasks", R.string.nav_tasks, Icons.Filled.Checklist),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}
