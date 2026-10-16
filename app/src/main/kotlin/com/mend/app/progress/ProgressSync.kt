package com.mend.app.progress

import android.util.Log
import com.mend.app.MendApplication
import com.mend.app.repository.NowNext
import com.mend.app.repository.PendingWork
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first

/** The outcome of checking one link, shown in Settings. */
data class LinkStatus(val link: ProgressLink, val message: String, val practiced: Boolean?)

/**
 * The next unfinished unit of work in a goal: the first sub-task, or bare
 * lesson, in lesson order. Null when everything is done.
 */
fun nextPendingWork(lessons: List<Lesson>): PendingWork? {
    for (lesson in lessons.sortedBy { it.orderIndex }) {
        if (lesson.hasNoSubTasks) {
            if (lesson.status != ProgressStatus.DONE) return PendingWork.Bare(lesson)
        } else {
            lesson.subTasks.firstOrNull { it.status != ProgressStatus.DONE }
                ?.let { return PendingWork.Sub(it) }
        }
    }
    return null
}

/** One reading of an outside app, before it is judged. */
private data class Reading(
    val practiced: Boolean?,
    val message: String,
    val due: Int? = null,
    val streak: Int? = null,
    val xp: Int? = null,
)

/**
 * Checks every link, records what it saw, and when the outside app shows the
 * work was done today ticks off the goal's next pending item through the normal
 * completion path - against the slot that item was given, so the adherence log
 * sees real lateness, exactly as if it had been tapped by hand.
 * One item per link per day, however often this runs.
 */
class ProgressSync(private val app: MendApplication) {

    private val store get() = app.progressLinks
    private val usage = UsageReader(app)
    private val anki = AnkiReader(app)
    private val duolingo = DuolingoReader()

    suspend fun run(
        today: LocalDate = LocalDate.now(),
        now: LocalDateTime = LocalDateTime.now(),
    ): List<LinkStatus> {
        val goals = app.goalRepository.observeGoals().first()
        // A link to a deleted goal has nothing to tick off and would linger forever.
        val goalIds = goals.map { it.goal.id }.toSet()
        store.all().filter { it.goalId !in goalIds }.forEach(store::remove)
        val links = store.all()
        val ankiDecks = links.filter { it.kind == ProgressKind.ANKI && it.target.isNotBlank() }
            .map { it.target }
        val ankiDue = if (ankiDecks.isNotEmpty()) anki.dueFor(ankiDecks) else null

        val statuses = links.map { link ->
            val reading = read(link, today, ankiDue)
            store.upsert(
                DayRecord(
                    date = today,
                    key = link.key,
                    practiced = reading.practiced,
                    minutes = usage.minutesToday(link.kind.packageName, today),
                    due = reading.due,
                    streak = reading.streak,
                    xp = reading.xp,
                    hours = usage.minutesByHourToday(link.kind.packageName, today),
                ),
            )

            if (reading.practiced == true && link.lastAppliedOn != today) {
                val lessons = goals.firstOrNull { it.goal.id == link.goalId }?.lessons.orEmpty()
                val work = nextPendingWork(lessons)
                if (work != null) {
                    complete(work, now)
                    store.save(link.copy(lastAppliedOn = today))
                    Log.d(LOG_TAG, "${link.kind} link completed work for goal ${link.goalId}")
                    LinkStatus(link, "${reading.message} - ticked off today's work", true)
                } else {
                    LinkStatus(link, "${reading.message} - nothing left to tick off", true)
                }
            } else {
                LinkStatus(link, reading.message, reading.practiced)
            }
        }

        // Durations, priorities and alarms all depend on what was just read.
        runCatching { app.alarmCoordinator.replanToday(now) }
        return statuses
    }

    private suspend fun read(
        link: ProgressLink,
        today: LocalDate,
        ankiDue: Map<String, Int>?,
    ): Reading {
        if (link.target.isBlank()) return readUsage(link, today)

        return when (link.kind) {
            ProgressKind.DUOLINGO -> {
                val profile = duolingo.profile(link.target, today)
                    ?: return Reading(null, "Could not read profile (offline, or Duolingo changed)")
                Reading(
                    practiced = profile.practicedToday,
                    message = if (profile.practicedToday) "Streak extended today" else "No practice yet today",
                    streak = profile.streak,
                    xp = profile.totalXp,
                )
            }

            ProgressKind.ANKI -> {
                val due = ankiDue?.get(link.target)
                    ?: return Reading(null, "Deck not readable - check AnkiDroid access")
                val baseline = ProgressEvidence.nextBaseline(store.ankiBaseline(link.target, today), due)
                store.setAnkiBaseline(link.target, today, baseline)
                if (ProgressEvidence.ankiFinished(baseline, due)) {
                    Reading(true, "All $baseline due cards reviewed", due = due)
                } else {
                    Reading(false, "$due card(s) due", due = due)
                }
            }
        }
    }

    private fun readUsage(link: ProgressLink, today: LocalDate): Reading {
        val minutes = usage.minutesToday(link.kind.packageName, today)
            ?: return Reading(null, "Usage access not granted")
        return Reading(
            practiced = ProgressEvidence.usageCounts(minutes),
            message = "$minutes min in ${link.kind.label} today (needs ${ProgressEvidence.USAGE_THRESHOLD_MINUTES})",
        )
    }

    private suspend fun complete(work: PendingWork, now: LocalDateTime) {
        val id = when (work) {
            is PendingWork.Sub -> work.subTask.id
            is PendingWork.Bare -> work.lesson.id
        }
        val slot = NowNext.slotFor(app, id, now)
        when (work) {
            is PendingWork.Sub ->
                app.goalRepository.setSubTaskDone(work.subTask, done = true, now = now, assignedSlot = slot)
            is PendingWork.Bare ->
                app.goalRepository.setLessonDone(work.lesson, done = true, now = now, assignedSlot = slot)
        }
    }

    companion object {
        const val LOG_TAG = "MendProgress"
    }
}
