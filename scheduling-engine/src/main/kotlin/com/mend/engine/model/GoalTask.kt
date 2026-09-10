package com.mend.engine.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/**
 * A unit of goal work, ready to be ranked and placed.
 *
 * This is the bridge between the goal hierarchy and the scheduler. A
 * [SubTask], or a whole bare [Lesson], is flattened into one of these together
 * with the context the priority score needs — its goal's deadline and
 * importance — so scoring never has to walk back up the tree.
 *
 * It is the only [Schedulable] in the model so far, which is the point:
 * `computePriorityScore` accepts [Schedulable] and nothing else, so a habit or
 * a static block cannot be ranked even by accident.
 */
data class GoalTask(
    override val id: String,
    override val title: String,
    override val consequenceType: ConsequenceType,
    override val timeSpec: TimeSpec,
    override val daysOfWeek: Set<DayOfWeek> = StaticBlock.ALL_DAYS,
    val goalId: String,
    val lessonId: String,
    /** Null when the unit of work is a whole lesson with no sub-tasks. */
    val subTaskId: String? = null,
    val deadline: LocalDate,
    /** 1-5, inherited from the parent goal. */
    val manualImportance: Int,
    /**
     * How reliably this kind of work has actually been done, 0.0 to 1.0.
     *
     * Phase 6 derives this from the adherence log. Until then every task
     * carries [NEUTRAL_ADHERENCE], so the term is present in the score and
     * weighted, but pushes nothing in either direction.
     */
    val adherenceTrend: Double = NEUTRAL_ADHERENCE,
) : Schedulable {

    override val type: TaskType = TaskType.GOAL_TASK

    init {
        require(manualImportance in Goal.IMPORTANCE_RANGE) {
            "GoalTask '$title': manualImportance must be in ${Goal.IMPORTANCE_RANGE}"
        }
        require(adherenceTrend in 0.0..1.0) {
            "GoalTask '$title': adherenceTrend must be in 0.0..1.0, was $adherenceTrend"
        }
    }

    /** How much time this work is asking for. */
    val requestedDuration: Duration get() = timeSpec.requestedDuration

    val flexibility: Flexibility get() = timeSpec.flexibility

    companion object {
        /**
         * The adherence value that pushes the score neither up nor down.
         *
         * Real history arrives in Phase 6; anything other than the midpoint
         * here would be inventing a behavioural signal that does not exist yet.
         */
        const val NEUTRAL_ADHERENCE = 0.5

        /**
         * Flattens a sub-task into a schedulable unit under [goal].
         */
        fun fromSubTask(
            goal: Goal,
            subTask: SubTask,
            adherenceTrend: Double = NEUTRAL_ADHERENCE,
        ): GoalTask = GoalTask(
            id = subTask.id,
            title = subTask.title,
            consequenceType = goal.consequenceType,
            timeSpec = TimeSpec(
                unitDuration = UnitDuration.coveringAtLeast(
                    subTask.estimatedDuration.toMinutes().toInt(),
                ),
                flexibility = subTask.flexibility,
                requiredDurationWithinWindow = subTask.estimatedDuration,
            ),
            goalId = goal.id,
            lessonId = subTask.lessonId,
            subTaskId = subTask.id,
            deadline = goal.deadline,
            manualImportance = goal.manualImportance,
            adherenceTrend = adherenceTrend,
        )

        /**
         * Flattens a bare lesson — one with no sub-tasks — into a schedulable
         * unit. Its duration has to be supplied, since a bare lesson carries no
         * estimate of its own.
         */
        fun fromBareLesson(
            goal: Goal,
            lesson: Lesson,
            estimatedDuration: Duration,
            flexibility: Flexibility = Flexibility.SPLITTABLE,
            adherenceTrend: Double = NEUTRAL_ADHERENCE,
        ): GoalTask = GoalTask(
            id = lesson.id,
            title = lesson.title,
            consequenceType = goal.consequenceType,
            timeSpec = TimeSpec(
                unitDuration = UnitDuration.coveringAtLeast(
                    estimatedDuration.toMinutes().toInt(),
                ),
                flexibility = flexibility,
                requiredDurationWithinWindow = estimatedDuration,
            ),
            goalId = goal.id,
            lessonId = lesson.id,
            subTaskId = null,
            deadline = goal.deadline,
            manualImportance = goal.manualImportance,
            adherenceTrend = adherenceTrend,
        )
    }
}
