package com.mend.engine.alarms

import com.mend.engine.allocation.DaySchedule
import com.mend.engine.allocation.TaskPlacement
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.tuning.Tuning
import java.time.Duration
import java.time.LocalDateTime

/**
 * What the day needs to interrupt you about, and when.
 *
 * Deciding *which* moments deserve an alarm is a scheduling question, so it
 * lives here and is tested like every other one. Actually arming them is
 * Android's business and stays in the app layer.
 *
 * The bar is deliberately high. Every alarm that does not earn its place trains
 * the user to ignore the next one, and the alarms that matter — a hard stop at
 * bedtime, an emergency interrupt — only work if the earlier ones were worth
 * looking at. A quiet app is a trusted app.
 */

/** How much an alarm is allowed to intrude, and therefore which channel it uses. */
enum class AlarmCriticality {
    /**
     * Must arrive. A genuine emergency interrupt, or the bedtime hard stop.
     *
     * Scheduled as an exact alarm-clock so it survives Doze, and shown on a
     * high-importance channel so OEM battery management cannot quietly drop it.
     */
    CRITICAL,

    /** Work is starting. Worth a notification, not worth waking the device for. */
    NORMAL,

    /** Ambient. Fine to arrive late or be batched. */
    LOW,
}

/** What the alarm is about, which decides its copy and its action. */
enum class AlarmKind {
    /** A scheduled piece of work is starting now. */
    TASK_START,

    /** Work should already have stopped; bedtime is imminent. */
    SLEEP_HARD_STOP,

    /** The wind-down before the hard stop. */
    SLEEP_WIND_DOWN,

    /** A genuine emergency has become the next action. */
    INTERRUPT,
}

/** One thing to fire, at one moment. */
data class ScheduledAlarm(
    /**
     * Stable across recomputes of the same day, so re-planning replaces an
     * alarm rather than adding a second copy of it.
     */
    val id: String,
    val at: LocalDateTime,
    val kind: AlarmKind,
    val criticality: AlarmCriticality,
    val title: String,
    val body: String,
    /** The task this is about, where there is one. */
    val taskId: String? = null,
) {
    val isCritical: Boolean get() = criticality == AlarmCriticality.CRITICAL
}

/**
 * Works out every alarm [schedule] deserves.
 *
 * Pure: [now] is a parameter, never a clock read. Anything already in the past
 * is dropped, because an alarm for a moment that has gone is either noise or a
 * notification that fires the instant it is set.
 */
fun planAlarms(
    schedule: DaySchedule,
    now: LocalDateTime,
    sleepBlock: ProtectedRecovery? = null,
    minimumLead: Duration = MINIMUM_ALARM_LEAD,
): List<ScheduledAlarm> = buildList {
    schedule.placements.forEach { placement ->
        taskStartAlarm(placement)?.let(::add)
    }

    sleepBlock?.let { addAll(sleepAlarms(it, schedule, now)) }
}
    .filter { it.at.isAfter(now.plus(minimumLead)) }
    .sortedBy { it.at }

/**
 * The lead time below which an alarm is not worth setting.
 *
 * An alarm two minutes out arrives while you are still looking at the screen
 * that scheduled it.
 */
val MINIMUM_ALARM_LEAD: Duration = Duration.ofMinutes(2)

/**
 * One alarm per placement, at the start of its first slice.
 *
 * Split work deliberately gets a single alarm rather than one per slice: three
 * buzzes for one task is how an app teaches you to silence it.
 */
private fun taskStartAlarm(placement: TaskPlacement): ScheduledAlarm? {
    val first = placement.slices.minByOrNull { it.start } ?: return null

    return ScheduledAlarm(
        id = "task-${placement.task.id}-${first.start}",
        at = first.start,
        kind = AlarmKind.TASK_START,
        criticality = AlarmCriticality.NORMAL,
        title = placement.task.title,
        body = buildString {
            append("${first.minutes}m now")
            if (placement.isSplit) {
                append(", first of ${placement.slices.size} blocks")
            }
            if (placement.isPartial) {
                append(" — ${placement.requestedDuration.toMinutes()}m wanted in total")
            }
        },
        taskId = placement.task.id,
    )
}

/**
 * The two moments sleep needs: the wind-down warning and the boundary itself.
 *
 * The hard stop is CRITICAL because it is the one this project exists for. If
 * exactly one alarm in this app survives an aggressive OEM, it should be this.
 */
private fun sleepAlarms(
    block: ProtectedRecovery,
    schedule: DaySchedule,
    now: LocalDateTime,
): List<ScheduledAlarm> {
    val boundary = block.hardStopAt(now.toLocalDate()) ?: return emptyList()

    val stillRunning = schedule.placements.filter { placement ->
        placement.slices.any { it.end > boundary }
    }

    val cost = if (stillRunning.isEmpty()) {
        "Nothing is still scheduled."
    } else {
        stillRunning.joinToString(", ") { it.task.title } + " moves to tomorrow."
    }

    return listOf(
        ScheduledAlarm(
            id = "sleep-winddown-${boundary.toLocalDate()}",
            at = boundary.minus(Tuning.SLEEP_WARNING_LEAD),
            kind = AlarmKind.SLEEP_WIND_DOWN,
            criticality = AlarmCriticality.NORMAL,
            title = "Winding down",
            body = "Work stops at ${boundary.toLocalTime()} for a ${block.bedtime} bedtime.",
        ),
        ScheduledAlarm(
            id = "sleep-stop-${boundary.toLocalDate()}",
            at = boundary,
            kind = AlarmKind.SLEEP_HARD_STOP,
            criticality = AlarmCriticality.CRITICAL,
            title = if (block.hardStop) "Stopping for the night" else "Past your wind-down",
            body = cost,
        ),
    )
}

/**
 * The alarm for an emergency interrupt.
 *
 * Separate from [planAlarms] because an interrupt is not part of the planned
 * day — it arrives mid-day and claims the next slot, so it is armed on its own
 * when one is detected.
 */
fun interruptAlarm(
    taskId: String,
    taskTitle: String,
    at: LocalDateTime,
    message: String,
): ScheduledAlarm = ScheduledAlarm(
    id = "interrupt-$taskId",
    at = at,
    kind = AlarmKind.INTERRUPT,
    criticality = AlarmCriticality.CRITICAL,
    title = "Do this next: $taskTitle",
    body = message,
    taskId = taskId,
)

/**
 * Which alarms changed between two plans.
 *
 * Re-planning happens constantly — every edit rebuilds the day — and cancelling
 * and re-arming a dozen identical alarms each time is both wasteful and a good
 * way to make one fire twice. So only genuine differences are acted on.
 *
 * "Genuine" means the **whole alarm**, not its id. A task alarm's id carries
 * its start time, so moving the task changes the id; but the bedtime alarms are
 * identified by date alone, and their time moves whenever the bedtime does.
 * Comparing ids only, an edited bedtime looks like an alarm that is already
 * armed, and the hard stop goes on firing at the old time — observed on a real
 * device, where changing 23:20 to 23:10 left the alarm sitting at 22:50.
 *
 * An alarm whose id is unchanged but whose content is not appears in [toArm]
 * and **not** in [toCancel]: arming replaces an existing alarm with the same id
 * outright, so re-arming is a single atomic step where cancel-then-arm is a
 * window in which nothing is armed at all.
 */
data class AlarmDiff(
    val toCancel: List<ScheduledAlarm>,
    val toArm: List<ScheduledAlarm>,
    val unchanged: List<ScheduledAlarm>,
) {
    val isEmpty: Boolean get() = toCancel.isEmpty() && toArm.isEmpty()
}

fun diffAlarms(current: List<ScheduledAlarm>, desired: List<ScheduledAlarm>): AlarmDiff {
    val currentById = current.associateBy { it.id }
    val desiredById = desired.associateBy { it.id }

    return AlarmDiff(
        // Only alarms that are wanted no longer. One whose id survives but
        // whose time moved is replaced by re-arming, not cancelled first.
        toCancel = current.filterNot { it.id in desiredById },
        toArm = desired.filterNot { currentById[it.id] == it },
        unchanged = desired.filter { currentById[it.id] == it },
    )
}
