package com.mend.app.repository

import com.mend.app.MendApplication
import com.mend.engine.allocation.TimelineEntry
import com.mend.engine.time.TimeInterval
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * What you are meant to be doing, and what comes after it.
 *
 * Both surfaces that live outside the app — the home screen widget and the
 * ongoing lock-screen note — answer exactly one question, *now what?*, and they
 * must never disagree with each other or with Today. So they read from here,
 * and here rebuilds the schedule from the engine the same way every other
 * surface does. There is no separate cache to fall out of step.
 */
data class NowNext(
    val currentTitle: String? = null,
    val currentEndsAt: String? = null,
    val minutesLeft: Long? = null,
    /** Set only when the current entry is goal work, which is the only kind that can be ticked off. */
    val currentTaskId: String? = null,
    val currentSlot: TimeInterval? = null,
    val nextTitle: String? = null,
    val nextStartsAt: String? = null,
    /** Connected-app status for the widget, e.g. "Duolingo · 12-day streak". */
    val progressLine: String? = null,
) {
    val isEmpty: Boolean get() = currentTitle == null && nextTitle == null

    /** Whether there is something here a "done" button could act on. */
    val isCompletable: Boolean get() = currentTaskId != null

    /** The one-line "how long is left" string, or null when nothing is running. */
    fun remainingLine(): String? {
        if (currentTitle == null) return null
        return buildString {
            minutesLeft?.let { append("${it}m left") }
            currentEndsAt?.let {
                if (isNotEmpty()) append("  ·  ")
                append("until $it")
            }
        }.takeIf { it.isNotEmpty() }
    }

    /** The "what comes after" line, or null when nothing does. */
    fun nextLine(): String? = nextTitle?.let { title ->
        "Next: $title" + (nextStartsAt?.let { " at $it" } ?: "")
    }

    companion object {
        val Empty = NowNext()

        /**
         * Reads the current state of the day.
         *
         * Any failure resolves to [Empty] rather than propagating. Both callers
         * are surfaces Android will stop updating if they throw, and a blank
         * widget is recoverable where a crashed one is not.
         */
        suspend fun read(
            app: MendApplication,
            now: LocalDateTime = LocalDateTime.now(),
        ): NowNext = try {
            val schedule = ScheduleBuilder.buildDaySchedule(
                date = now.toLocalDate(),
                staticBlocks = app.staticLayerRepository.observeStaticBlocks().first(),
                habits = app.staticLayerRepository.observeHabits().first(),
                goals = app.goalRepository.observeGoalsForScheduling().first(),
                protectedRecovery = listOfNotNull(
                    app.recoveryRepository.observeSleepBlock().first(),
                ),
                weights = app.weightsRepository.current(),
            )

            val timeline = schedule.timeline
            val current = timeline.firstOrNull { it.interval.contains(now) }
            val next = timeline.firstOrNull { it.interval.start.isAfter(now) }

            // Only goal work is completable. A fixed block or a habit is not
            // something you finish early, and protected sleep certainly is not,
            // so those get the same "now what" line without a done button.
            val work = current as? TimelineEntry.Work

            NowNext(
                currentTitle = current?.title(),
                currentEndsAt = current?.interval?.end?.format(TIME),
                minutesLeft = current?.let {
                    ChronoUnit.MINUTES.between(now, it.interval.end).coerceAtLeast(0)
                },
                currentTaskId = work?.task?.id,
                currentSlot = work?.interval,
                nextTitle = next?.title(),
                nextStartsAt = next?.interval?.start?.format(TIME),
                progressLine = com.mend.app.progress.progressLineFor(app, now.toLocalDate()),
            )
        } catch (e: Exception) {
            Empty
        }

        /**
         * The slot a task currently occupies, for recording adherence against.
         *
         * Looked up afresh rather than carried around, because a widget or a
         * notification may have been sitting on screen for hours and the slot
         * it was drawn with is not necessarily the slot the task ended up in.
         */
        suspend fun slotFor(
            app: MendApplication,
            taskId: String,
            now: LocalDateTime = LocalDateTime.now(),
        ): TimeInterval? = try {
            ScheduleBuilder.buildDaySchedule(
                date = now.toLocalDate(),
                staticBlocks = app.staticLayerRepository.observeStaticBlocks().first(),
                habits = app.staticLayerRepository.observeHabits().first(),
                goals = app.goalRepository.observeGoalsForScheduling().first(),
                protectedRecovery = listOfNotNull(
                    app.recoveryRepository.observeSleepBlock().first(),
                ),
                weights = app.weightsRepository.current(),
            ).placements
                .firstOrNull { it.task.id == taskId }
                ?.let { placement ->
                    val start = placement.slices.minOfOrNull { it.start }
                    val end = placement.slices.maxOfOrNull { it.end }
                    if (start != null && end != null) TimeInterval(start, end) else null
                }
        } catch (e: Exception) {
            null
        }
    }
}

private fun TimelineEntry.title(): String = when (this) {
    is TimelineEntry.Fixed -> entry.title
    is TimelineEntry.Work -> task.title
}
