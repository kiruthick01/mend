package com.mend.engine.guilt

import com.mend.engine.adherence.activeDays
import com.mend.engine.adherence.completedMinutesPerDay
import com.mend.engine.adherence.computeAdherence
import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.progress.GoalProgress
import com.mend.engine.progress.computeGoalProgress
import com.mend.engine.progress.remainingEffort
import com.mend.engine.tuning.Tuning
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * The guilt trip: told once, with numbers, about something that actually
 * matters.
 *
 * PROJECT_PLAN.md is unusually specific about this feature, and the
 * specificity is the feature. It fires only for work with a real-world
 * consequence, only when recorded history projects an actual miss, and only
 * when there is enough history to project from. Anything looser produces "you
 * haven't done this in a while", which is noise, and noise gets dismissed —
 * taking the genuine warnings with it.
 *
 * Three gates, all of which must open:
 *  1. `consequence_type == IRL_BINDING`. Habits and soft goals never qualify,
 *     and habits cannot even reach this code — they have no consequence type
 *     and are not part of the goal hierarchy.
 *  2. Enough logged history to project from.
 *  3. The projection, at the pace actually being sustained, misses.
 */

/** Days of recorded activity before a projection is trustworthy. */
const val MINIMUM_ACTIVE_DAYS_FOR_PROJECTION: Int = Tuning.MINIMUM_ACTIVE_DAYS_FOR_PROJECTION

/** Completed slots before a projection is trustworthy. */
const val MINIMUM_COMPLETIONS_FOR_PROJECTION: Int = Tuning.MINIMUM_COMPLETIONS_FOR_PROJECTION

/** Why a projection could not be made. */
enum class ProjectionBlocker {
    /** Not enough recorded history yet. */
    INSUFFICIENT_HISTORY,

    /** Nothing left to do. */
    NO_WORK_REMAINING,

    /** The deadline has already gone; that is a different conversation. */
    DEADLINE_PASSED,
}

/**
 * What the recorded pace implies about a deadline.
 *
 * Every field is an input to the message, so the user can check the reasoning
 * rather than take the conclusion on trust.
 */
data class PaceProjection(
    val goalId: String,
    val remainingMinutes: Long,
    /** Minutes of work per day actually completed over the window. */
    val observedMinutesPerDay: Double,
    val daysRemaining: Long,
    /** What the observed pace delivers before the deadline. */
    val minutesAvailableAtThisPace: Double,
    /** How far short that falls. Zero or less means the deadline is reachable. */
    val shortfallMinutes: Double,
    /** Days the remaining work needs at this pace, or null if the pace is zero. */
    val daysNeededAtThisPace: Long?,
    val projectedFinish: LocalDate?,
    val willMissDeadline: Boolean,
    val blocker: ProjectionBlocker?,
) {
    val isUsable: Boolean get() = blocker == null

    /** The arithmetic, spelled out so it can be argued with. */
    fun explain(): String = when (blocker) {
        ProjectionBlocker.INSUFFICIENT_HISTORY ->
            "Not enough recorded history yet to project a pace."
        ProjectionBlocker.NO_WORK_REMAINING -> "Nothing left to do."
        ProjectionBlocker.DEADLINE_PASSED -> "The deadline has already passed."
        null -> buildString {
            append("${remainingMinutes}m of work left, ")
            append("${daysRemaining}d until the deadline. ")
            append("At the ${observedMinutesPerDay.toInt()}m/day you have actually been ")
            append("doing, that is ${minutesAvailableAtThisPace.toInt()}m of capacity")
            if (willMissDeadline) {
                append(" — ${shortfallMinutes.toInt()}m short.")
            } else {
                append(", which is enough.")
            }
        }
    }
}

/**
 * Projects whether [goal] is reachable at the pace actually being sustained.
 *
 * Pure, and driven by the adherence log rather than by intentions. Pace comes
 * from slots that were genuinely honoured; remaining work comes from sub-tasks
 * not yet done.
 */
fun projectPace(
    goal: Goal,
    lessons: List<Lesson>,
    log: List<AdherenceLogEntry>,
    today: LocalDate,
): PaceProjection {
    val remaining = remainingEffort(lessons).toMinutes()
    val daysRemaining = ChronoUnit.DAYS.between(today, goal.deadline)

    val taskIds = lessons.flatMap { it.subTasks }.map { it.id }.toSet() +
        lessons.map { it.id }.toSet()
    val relevant = log.filter { it.taskId in taskIds }

    val velocity = completedMinutesPerDay(relevant, today)
    val days = activeDays(relevant, today)
    val completions = relevant.count {
        it.outcome == com.mend.engine.model.AdherenceOutcome.ON_TIME ||
            it.outcome == com.mend.engine.model.AdherenceOutcome.LATE
    }

    fun blocked(blocker: ProjectionBlocker) = PaceProjection(
        goalId = goal.id,
        remainingMinutes = remaining,
        observedMinutesPerDay = velocity,
        daysRemaining = daysRemaining,
        minutesAvailableAtThisPace = 0.0,
        shortfallMinutes = 0.0,
        daysNeededAtThisPace = null,
        projectedFinish = null,
        willMissDeadline = false,
        blocker = blocker,
    )

    if (remaining <= 0) return blocked(ProjectionBlocker.NO_WORK_REMAINING)
    if (daysRemaining < 0) return blocked(ProjectionBlocker.DEADLINE_PASSED)
    if (days < MINIMUM_ACTIVE_DAYS_FOR_PROJECTION ||
        completions < MINIMUM_COMPLETIONS_FOR_PROJECTION
    ) {
        return blocked(ProjectionBlocker.INSUFFICIENT_HISTORY)
    }

    val available = velocity * daysRemaining
    val shortfall = remaining - available

    val daysNeeded = if (velocity > 0) {
        ceil(remaining / velocity).toLong()
    } else {
        null
    }

    return PaceProjection(
        goalId = goal.id,
        remainingMinutes = remaining,
        observedMinutesPerDay = velocity,
        daysRemaining = daysRemaining,
        minutesAvailableAtThisPace = available,
        shortfallMinutes = shortfall,
        daysNeededAtThisPace = daysNeeded,
        projectedFinish = daysNeeded?.let { today.plusDays(it) },
        willMissDeadline = shortfall > 0,
        blocker = null,
    )
}

/**
 * A warning that has earned the right to be shown, and the case for it.
 *
 * Carries a [reshuffleRequested] flag rather than a boolean "dismissed",
 * because per PROJECT_PLAN.md the message ends by *asking* to reshuffle. The
 * answer routes into the Phase 4 flow, where the user still gets Accept /
 * Decline / Propose different.
 */
data class GuiltTrip(
    val goalId: String,
    val goalTitle: String,
    val progress: GoalProgress,
    val projection: PaceProjection,
    val adherenceSummary: String,
    val message: String,
) {
    /** The question the message ends on. The UI turns this into the prompt. */
    val reshuffleRequest: String
        get() = "Shall I work out a different plan for $goalTitle, starting tomorrow?"
}

/**
 * Decides whether [goal] has earned a warning, and writes it if so.
 *
 * Returns null — meaning "say nothing" — far more often than not. That is the
 * intended behaviour, not a gap.
 *
 * Habits cannot reach this function: they are not part of the goal hierarchy
 * and carry no consequence type. Soft goals reach it and are turned away at
 * the first gate.
 */
fun evaluateGuiltTrip(
    goal: Goal,
    lessons: List<Lesson>,
    log: List<AdherenceLogEntry>,
    today: LocalDate,
): GuiltTrip? {
    // Gate 1: only work with a real-world consequence.
    if (goal.consequenceType != ConsequenceType.IRL_BINDING) return null

    val projection = projectPace(goal, lessons, log, today)

    // Gates 2 and 3: a usable projection that actually misses.
    if (!projection.isUsable) return null
    if (!projection.willMissDeadline) return null

    val progress = computeGoalProgress(goal, lessons, today)
    val adherence = computeAdherence(
        log = log,
        taskId = goal.id,
        asOf = today,
    )

    val taskIds = lessons.flatMap { it.subTasks }.map { it.id }.toSet()
    val relevant = log.filter { it.taskId in taskIds }
    val missed = relevant.count { it.outcome == com.mend.engine.model.AdherenceOutcome.MISSED }

    val adherenceSummary = if (missed > 0) {
        "$missed scheduled session(s) missed in the last ${adherence.windowDays} days."
    } else {
        "Sessions are being honoured, but there are not enough of them."
    }

    return GuiltTrip(
        goalId = goal.id,
        goalTitle = goal.title,
        progress = progress,
        projection = projection,
        adherenceSummary = adherenceSummary,
        message = buildMessage(goal, progress, projection, adherenceSummary),
    )
}

/**
 * The message, in the order PROJECT_PLAN.md asks for: progress so far, days
 * remaining, why the projection misses, then the request.
 *
 * Written as arithmetic rather than admonishment. "You are 62m/day short" is
 * something a person can act on; "you have been slacking" is not.
 */
private fun buildMessage(
    goal: Goal,
    progress: GoalProgress,
    projection: PaceProjection,
    adherenceSummary: String,
): String = buildString {
    append("${goal.title}: ")
    append("${progress.lessonsCompleted} of ${progress.totalLessons} lessons done")
    if (progress.totalSubTasks > 0) {
        append(", ${progress.subTasksCompleted}/${progress.totalSubTasks} sub-tasks")
    }
    append(". ")

    append(
        when (projection.daysRemaining) {
            0L -> "The deadline is today. "
            1L -> "One day left. "
            else -> "${projection.daysRemaining} days left. "
        },
    )

    append(projection.explain())
    append(" ")

    projection.projectedFinish?.let {
        append("At this rate you finish on $it, ")
        append("${ChronoUnit.DAYS.between(goal.deadline, it)}d after the deadline. ")
    } ?: append("At this rate you do not finish at all. ")

    append(adherenceSummary)
    append(" This one has a real consequence, so it is worth saying plainly.")
}

/**
 * Evaluates every goal and returns the warnings that qualify, most urgent
 * first.
 *
 * Urgency is the deadline, not the size of the shortfall: a small gap on
 * something due Friday matters more than a large one on something due next
 * month.
 */
fun evaluateAllGuiltTrips(
    goals: List<Pair<Goal, List<Lesson>>>,
    log: List<AdherenceLogEntry>,
    today: LocalDate,
): List<GuiltTrip> =
    goals
        .mapNotNull { (goal, lessons) -> evaluateGuiltTrip(goal, lessons, log, today) }
        .sortedBy { it.projection.daysRemaining }
