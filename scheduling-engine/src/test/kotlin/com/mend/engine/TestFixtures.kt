package com.mend.engine

import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Flexibility
import com.mend.engine.model.GoalTask
import com.mend.engine.model.Habit
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.TimeSpec
import com.mend.engine.model.UnitDuration
import com.mend.engine.model.WindowAnchor
import com.mend.engine.time.TimeInterval
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** A Wednesday, used as the reference date across the engine test suite. */
val TEST_DATE: LocalDate = LocalDate.of(2026, 9, 16)

fun at(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

/** Builds an interval on [TEST_DATE] from "HH:mm" style hour/minute pairs. */
fun interval(
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    date: LocalDate = TEST_DATE,
): TimeInterval = TimeInterval(
    date.atTime(startHour, startMinute),
    if (endHour == 24) date.plusDays(1).atStartOfDay() else date.atTime(endHour, endMinute),
)

fun staticBlock(
    id: String,
    title: String = id,
    from: LocalTime,
    to: LocalTime,
    required: Duration? = null,
    anchor: WindowAnchor = WindowAnchor.EARLIEST,
    days: Set<DayOfWeek> = StaticBlock.ALL_DAYS,
): StaticBlock = StaticBlock(
    id = id,
    title = title,
    daysOfWeek = days,
    windowStart = from,
    windowEnd = to,
    requiredDuration = required,
    anchor = anchor,
)

fun habit(
    id: String,
    title: String = id,
    at: LocalTime,
    minutes: Long = 5,
    days: Set<DayOfWeek> = StaticBlock.ALL_DAYS,
): Habit = Habit(
    id = id,
    title = title,
    daysOfWeek = days,
    timeOfDay = at,
    duration = Duration.ofMinutes(minutes),
)

/** Renders intervals as "HH:mm-HH:mm" so assertion failures are readable. */
fun List<TimeInterval>.render(): List<String> = map {
    "%02d:%02d-%02d:%02d".format(
        it.start.hour,
        it.start.minute,
        if (it.end.toLocalDate() > it.start.toLocalDate()) 24 else it.end.hour,
        it.end.minute,
    )
}

fun goalTask(
    id: String,
    title: String = id,
    deadlineIn: Long,
    importance: Int = 3,
    minutes: Long = 60,
    flexibility: Flexibility = Flexibility.SPLITTABLE,
    windowFrom: LocalTime? = null,
    windowTo: LocalTime? = null,
    adherenceTrend: Double = GoalTask.NEUTRAL_ADHERENCE,
    days: Set<DayOfWeek> = StaticBlock.ALL_DAYS,
    date: LocalDate = TEST_DATE,
): GoalTask = GoalTask(
    id = id,
    title = title,
    consequenceType = ConsequenceType.SOFT_GOAL,
    timeSpec = TimeSpec(
        unitDuration = UnitDuration.coveringAtLeast(minutes.toInt()),
        flexibility = flexibility,
        windowStart = windowFrom,
        windowEnd = windowTo,
        requiredDurationWithinWindow = Duration.ofMinutes(minutes),
    ),
    daysOfWeek = days,
    goalId = "goal",
    lessonId = "lesson",
    subTaskId = id,
    deadline = date.plusDays(deadlineIn),
    manualImportance = importance,
    adherenceTrend = adherenceTrend,
)
