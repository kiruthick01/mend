package com.mend.engine.nlp

import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.model.Habit
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.StaticBlock
import com.mend.engine.time.Intervals
import com.mend.engine.time.TimeInterval
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The gate every AI-parsed constraint has to pass.
 *
 * PROJECT_PLAN.md is unambiguous: AI-parsed output must be validated against
 * hard constraints by the deterministic engine *before ever being presented to
 * the user*. Not before being applied — before being shown. So this runs
 * between the parse and the confirmation screen, and a constraint that fails
 * here is never offered as something to accept.
 *
 * The checks are about coherence and consequence, not plausibility. The engine
 * cannot know whether the user really has exams next week; it can know that a
 * date range runs backwards, that a window would leave no usable time in the
 * day, or that a change lands in the past.
 */

/** How far ahead a constraint may reach before it is more likely a parse error. */
const val MAX_CONSTRAINT_HORIZON_DAYS: Long = 365

/** A day with less than this much free time left is effectively unusable. */
const val MINIMUM_VIABLE_FREE_MINUTES: Long = 30

/** The verdict, with the reasoning either way. */
sealed interface ValidationResult {

    /** Safe to offer. [warnings] are worth showing but do not block. */
    data class Accepted(
        val constraint: StructuredConstraint,
        val warnings: List<String> = emptyList(),
    ) : ValidationResult {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()
    }

    /** Not safe to offer, and precisely why. */
    data class Rejected(val reason: String) : ValidationResult
}

/**
 * Validates [constraint] against the day's hard structure.
 *
 * [today] is passed rather than read so this stays pure and every branch is
 * testable. The static layer is supplied so a window can be checked against
 * what the day actually looks like rather than against an assumption.
 */
fun validateConstraint(
    constraint: StructuredConstraint,
    today: LocalDate,
    staticBlocks: List<StaticBlock> = emptyList(),
    habits: List<Habit> = emptyList(),
    protectedRecovery: List<ProtectedRecovery> = emptyList(),
): ValidationResult = when (constraint) {
    is StructuredConstraint.Unrecognised ->
        ValidationResult.Rejected(constraint.reason)

    is StructuredConstraint.BusyPeriod ->
        validateBusyPeriod(constraint, today)

    is StructuredConstraint.DeadlineChange ->
        validateDeadlineChange(constraint, today)

    is StructuredConstraint.AvoidWindow ->
        validateAvoidWindow(constraint, today, staticBlocks, habits, protectedRecovery)
}

private fun validateBusyPeriod(
    constraint: StructuredConstraint.BusyPeriod,
    today: LocalDate,
): ValidationResult {
    if (constraint.to.isBefore(constraint.from)) {
        return ValidationResult.Rejected(
            "the period runs backwards: ${constraint.from} to ${constraint.to}",
        )
    }
    if (constraint.to.isBefore(today)) {
        return ValidationResult.Rejected(
            "that period has already passed; Mend never rewrites days that are over",
        )
    }
    if (ChronoUnit.DAYS.between(today, constraint.to) > MAX_CONSTRAINT_HORIZON_DAYS) {
        return ValidationResult.Rejected(
            "it reaches more than a year ahead, which is more likely a " +
                "misreading than a plan",
        )
    }

    val warnings = buildList {
        if (constraint.from.isBefore(today)) {
            // Worth saying rather than rejecting: "exams this week" said on
            // Wednesday is a reasonable thing to type.
            add("It starts on ${constraint.from}, which is in the past — only the remaining days can change.")
        }
        val length = ChronoUnit.DAYS.between(constraint.from, constraint.to) + 1
        if (length > 30) {
            add("That is $length days, which is a long time to write off.")
        }
    }

    return ValidationResult.Accepted(constraint, warnings)
}

private fun validateDeadlineChange(
    constraint: StructuredConstraint.DeadlineChange,
    today: LocalDate,
): ValidationResult {
    if (constraint.goalTitleHint.isBlank()) {
        return ValidationResult.Rejected("it does not say which goal the deadline belongs to")
    }
    if (constraint.newDeadline.isBefore(today)) {
        return ValidationResult.Rejected(
            "the new deadline ${constraint.newDeadline} is in the past",
        )
    }
    if (ChronoUnit.DAYS.between(today, constraint.newDeadline) > MAX_CONSTRAINT_HORIZON_DAYS) {
        return ValidationResult.Rejected("the new deadline is more than a year away")
    }

    val warnings = if (constraint.newDeadline == today) {
        listOf("That makes it due today, which may turn it into an interrupt.")
    } else {
        emptyList()
    }

    return ValidationResult.Accepted(constraint, warnings)
}

/**
 * Checks a recurring window against the day it would actually carve into.
 *
 * This is the check that matters most. "Keep mornings free" sounds harmless
 * until it is applied to a day that is already mostly college, at which point
 * it leaves nowhere for anything to go — and the user would have accepted a
 * change whose consequence nobody had worked out.
 */
private fun validateAvoidWindow(
    constraint: StructuredConstraint.AvoidWindow,
    today: LocalDate,
    staticBlocks: List<StaticBlock>,
    habits: List<Habit>,
    protectedRecovery: List<ProtectedRecovery>,
): ValidationResult {
    if (constraint.to <= constraint.from) {
        return ValidationResult.Rejected(
            "the window runs backwards: ${constraint.from} to ${constraint.to}",
        )
    }
    if (constraint.daysOfWeek.isEmpty()) {
        return ValidationResult.Rejected("it does not say which days it applies to")
    }

    // What each affected day would have left once this window is also removed.
    val squeezed = constraint.daysOfWeek.mapNotNull { day ->
        val date = nextDateFor(today, day)
        val map = computeFreeTimeMap(date, staticBlocks, habits, protectedRecovery)
        val window = TimeInterval(
            date.atTime(constraint.from),
            date.atTime(constraint.to),
        )
        val left = Intervals.totalMinutes(Intervals.subtract(map.free, listOf(window)))
        if (left < MINIMUM_VIABLE_FREE_MINUTES) day to left else null
    }

    if (squeezed.size == constraint.daysOfWeek.size) {
        return ValidationResult.Rejected(
            "keeping ${constraint.from}-${constraint.to} clear would leave no usable " +
                "time on any affected day, once your fixed blocks and sleep are " +
                "accounted for",
        )
    }

    val warnings = if (squeezed.isNotEmpty()) {
        listOf(
            "On " + squeezed.joinToString(", ") { (day, left) ->
                "${day.name.lowercase().replaceFirstChar { c -> c.uppercase() }} (${left}m left)"
            } + " this leaves almost nothing schedulable.",
        )
    } else {
        emptyList()
    }

    return ValidationResult.Accepted(constraint, warnings)
}

/** The next occurrence of [day] on or after [from]. */
private fun nextDateFor(from: LocalDate, day: java.time.DayOfWeek): LocalDate {
    var date = from
    repeat(7) {
        if (date.dayOfWeek == day) return date
        date = date.plusDays(1)
    }
    return from
}
