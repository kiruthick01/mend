package com.mend.engine.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A body of work with a deadline: "finish Japanese N5", "clear the syllabus".
 *
 * A goal is not scheduled directly. It owns [Lesson]s, which own [SubTask]s,
 * and it is the sub-tasks (or bare lessons) that the allocator places.
 */
data class Goal(
    val id: String,
    val title: String,
    val deadline: LocalDate,
    /** 1-5, set by hand. Feeds `w_importance` in the priority score. */
    val manualImportance: Int,
    val consequenceType: ConsequenceType = ConsequenceType.SOFT_GOAL,
    val isOneShot: Boolean = false,
    val totalLessons: Int = 0,
    val lessonsCompleted: Int = 0,
    /** Set when the goal is fragile: sequential work already slipping. Phase 4. */
    val pileupRisk: Boolean = false,
    /**
     * True when lessons must be done in order.
     *
     * Half of the delay-negotiation test: postponing sequential work genuinely
     * blocks everything behind it, whereas parallelisable work just gets
     * reordered and so never needs to negotiate.
     */
    val isSequential: Boolean = false,
    /** Last-resort fallback, granted only to fragile work. Phase 4. */
    val negotiatedDelayLimit: Duration? = null,
) {
    init {
        require(manualImportance in IMPORTANCE_RANGE) {
            "Goal '$title': manualImportance must be in $IMPORTANCE_RANGE, was $manualImportance"
        }
        require(totalLessons >= 0) { "Goal '$title': totalLessons must not be negative" }
        require(lessonsCompleted in 0..maxOf(totalLessons, 0)) {
            "Goal '$title': lessonsCompleted ($lessonsCompleted) must be within 0..$totalLessons"
        }
    }

    /** 0.0-1.0 rollup of lesson completion. Zero-lesson goals report 0. */
    val completionFraction: Double
        get() = if (totalLessons == 0) 0.0 else lessonsCompleted.toDouble() / totalLessons

    /**
     * Derived, per Section 3: a goal only becomes eligible to negotiate a delay
     * once it is genuinely fragile. This is a last resort, not a default.
     */
    val delayNegotiationEligible: Boolean
        get() = pileupRisk && isSequential

    companion object {
        val IMPORTANCE_RANGE = 1..5
    }
}

/**
 * One unit of a [Goal]'s work.
 *
 * A lesson defines its own sub-tasks independently — zero, one, or several,
 * freely named. There is deliberately no cross-lesson template: lessons in the
 * same goal need not share a structure. A lesson with no sub-tasks is complete
 * when the lesson itself is marked done.
 */
data class Lesson(
    val id: String,
    val goalId: String,
    val orderIndex: Int,
    val title: String,
    val status: ProgressStatus = ProgressStatus.NOT_STARTED,
    /** When this lesson was marked done. Only meaningful for a bare lesson. */
    val completedAt: LocalDateTime? = null,
    val subTasks: List<SubTask> = emptyList(),
) {
    /** True when this lesson is completed by marking the lesson itself done. */
    val hasNoSubTasks: Boolean get() = subTasks.isEmpty()
}

/**
 * A named piece of a [Lesson]. The type is a free-text label the user chooses
 * ("reading", "writing", "past paper") — deliberately not a fixed enum.
 */
data class SubTask(
    val id: String,
    val lessonId: String,
    val type: String,
    val title: String,
    val estimatedDuration: Duration,
    /** Pace the user says is comfortable for this kind of sub-task. */
    val paceComfortable: Duration? = null,
    /**
     * How the allocator may bend this work to fit the day.
     *
     * Not in Section 3, which puts flexibility on `TimeSpec`, but a sub-task
     * is the unit that actually gets placed and different sub-tasks of the
     * same lesson bend differently: reading splits across gaps, a mock exam
     * does not. Splittable is the default because most study work is.
     */
    val flexibility: Flexibility = Flexibility.SPLITTABLE,
    val status: ProgressStatus = ProgressStatus.NOT_STARTED,
    /**
     * When this sub-task was marked done.
     *
     * Not in PROJECT_PLAN.md Section 3, but "actual velocity" cannot be
     * measured without knowing when work landed, and Phase 2 asks for a pace
     * view. Null means done before tracking began, or not done at all.
     */
    val completedAt: LocalDateTime? = null,
) {
    init {
        require(!estimatedDuration.isNegative && !estimatedDuration.isZero) {
            "SubTask '$title': estimatedDuration must be positive"
        }
    }
}
