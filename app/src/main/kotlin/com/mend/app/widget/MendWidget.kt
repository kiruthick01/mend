package com.mend.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.mend.app.MainActivity
import com.mend.app.MendApplication
import com.mend.app.repository.NowNext
import com.mend.app.repository.TaskCompletion
import java.time.LocalDateTime

/**
 * What you are meant to be doing, on the home screen.
 *
 * Deliberately answers one question — *now what?* — rather than showing the
 * day. A widget that reproduces the schedule is a worse version of opening the
 * app; a widget that says "Grammar 7, 40 minutes, until 10:20" is something you
 * can act on without opening anything, and tick off without opening anything.
 *
 * It reads through [NowNext], which rebuilds the schedule from the engine the
 * same way every other surface does. There is no separate widget cache to fall
 * out of step.
 */
class MendWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = (context.applicationContext as? MendApplication)
            ?.let { NowNext.read(it) }
            ?: NowNext.Empty

        provideContent {
            GlanceTheme {
                WidgetBody(snapshot)
            }
        }
    }
}

/**
 * The widget's done button.
 *
 * Runs the same completion path as every other surface, then redraws both
 * outside surfaces. A done button that leaves the task sitting there reads as a
 * button that did not work, so the redraw is part of the action rather than
 * something left to the next update.
 */
class MarkDoneAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TASK_ID] ?: return
        val app = context.applicationContext as? MendApplication ?: return

        TaskCompletion.complete(app, taskId, LocalDateTime.now())

        // Replanning redraws this widget and the lock-screen note as part of
        // its own work, so both agree with the schedule it just rebuilt.
        app.alarmCoordinator.replanToday(LocalDateTime.now())
    }

    companion object {
        val TASK_ID = ActionParameters.Key<String>("taskId")
    }
}

@Composable
private fun WidgetBody(snapshot: NowNext) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (snapshot.isEmpty) {
            Text(
                text = "Nothing scheduled",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 14.sp(),
                ),
            )
            return@Column
        }

        snapshot.currentTitle?.let { title ->
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = title,
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 16.sp(),
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    snapshot.remainingLine()?.let { line ->
                        Text(
                            text = line,
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 13.sp(),
                            ),
                        )
                    }
                }

                // Only goal work can be ticked off. A fixed block or protected
                // sleep is not something you finish early, so offering a button
                // for it would be offering something that does nothing.
                snapshot.currentTaskId?.let { taskId ->
                    Text(
                        text = "Done",
                        modifier = GlanceModifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable(
                                actionRunCallback<MarkDoneAction>(
                                    actionParametersOf(MarkDoneAction.TASK_ID to taskId),
                                ),
                            ),
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 14.sp(),
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }

        snapshot.nextLine()?.let { line ->
            if (snapshot.currentTitle != null) {
                Spacer(modifier = GlanceModifier.height(6.dp))
            }
            Text(
                text = line,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp(),
                ),
            )
        }

        snapshot.progressLine?.let { line ->
            Spacer(modifier = GlanceModifier.height(6.dp))
            Text(
                text = line,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 12.sp(),
                ),
            )
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(
    toFloat(),
    androidx.compose.ui.unit.TextUnitType.Sp,
)

/** Registers the widget with the system. */
class MendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MendWidget()
}
