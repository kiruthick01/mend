package com.mend.app.repository

import com.mend.app.data.dao.GoalDao
import com.mend.app.data.dao.LessonDao
import com.mend.app.data.dao.SubTaskDao
import com.mend.app.data.mapper.assembleGoals
import com.mend.app.data.mapper.toEntity
import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import com.mend.engine.reshuffle.ScheduleInvalidatingEvent
import com.mend.engine.time.TimeInterval
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** A goal together with its assembled lesson hierarchy. */
data class GoalWithLessons(
    val goal: Goal,
    val lessons: List<Lesson>,
    /**
     * The day this goal was created, where known.
     *
     * Needed by the catch-up sweep: recomputing a past day must not include
     * goals that did not exist then, or adding a goal today would back-date a
     * fortnight of failures nobody had committed to.
     */
    val createdOn: LocalDate? = null,
)

/**
 * The goal hierarchy: goals, their lessons, and each lesson's own sub-tasks.
 *
 * Reads assemble the whole tree in one pass from three flat queries, so the UI
 * gets engine domain types and never has to stitch rows together itself.
 */
class GoalRepository(
    private val goalDao: GoalDao,
    private val lessonDao: LessonDao,
    private val subTaskDao: SubTaskDao,
    /**
     * Optional so the repository stays testable on its own. When present,
     * deadline changes are recorded as schedule-invalidating events and feed
     * the next reshuffle proposal.
     */
    private val reshuffle: ReshuffleRepository? = null,
    /**
     * Optional for testability. When present, every completion is written to
     * the append-only adherence stream, which is what every behavioural
     * feature from Phase 5 onward reads.
     */
    private val adherence: AdherenceRepository? = null,
) {

    fun observeGoals(): Flow<List<GoalWithLessons>> =
        combine(
            goalDao.observeAll(),
            lessonDao.observeAll(),
            subTaskDao.observeAll(),
        ) { goals, lessons, subTasks ->
            val createdOn = goals.associate { it.id to it.createdAt.toLocalDateOrNull() }
            assembleGoals(goals, lessons, subTasks).map { (goal, goalLessons) ->
                GoalWithLessons(goal, goalLessons, createdOn[goal.id])
            }
        }

    fun observeGoal(goalId: String): Flow<GoalWithLessons?> =
        observeGoals().map { all -> all.firstOrNull { it.goal.id == goalId } }

    suspend fun saveGoal(goal: Goal, now: LocalDateTime = LocalDateTime.now()) {
        val previous = goalDao.findById(goal.id)
        goalDao.upsert(goal.toEntity(createdAt = System.currentTimeMillis()))

        // Only a deadline that genuinely moved invalidates the plan. Renaming a
        // goal or nudging its importance does not, and prompting for those
        // would turn the negotiation into noise.
        val event = when {
            previous == null -> ScheduleInvalidatingEvent.DeadlineAdded(
                occurredAt = now,
                goalId = goal.id,
                goalTitle = goal.title,
                deadline = goal.deadline,
            )

            previous.deadline != goal.deadline -> ScheduleInvalidatingEvent.DeadlineChanged(
                occurredAt = now,
                goalId = goal.id,
                goalTitle = goal.title,
                from = previous.deadline,
                to = goal.deadline,
            )

            else -> null
        }
        event?.let { reshuffle?.record(it) }
    }

    suspend fun deleteGoal(goal: Goal) {
        // Lessons and sub-tasks cascade; the hierarchy is an ownership tree.
        goalDao.delete(goal.toEntity())
    }

    suspend fun saveLesson(lesson: Lesson) {
        lessonDao.upsert(lesson.toEntity())
    }

    suspend fun deleteLesson(lesson: Lesson) {
        lessonDao.delete(lesson.toEntity())
    }

    suspend fun saveSubTask(subTask: SubTask, orderIndex: Int) {
        subTaskDao.upsert(subTask.toEntity(orderIndex))
    }

    suspend fun deleteSubTask(subTask: SubTask) {
        subTaskDao.delete(subTask.toEntity())
    }

    /**
     * Marks a sub-task done or not done.
     *
     * Completing stamps [now] so velocity can be measured later; un-completing
     * clears it, because a sub-task that is not done has no completion time.
     */
    suspend fun setSubTaskDone(
        subTask: SubTask,
        done: Boolean,
        orderIndex: Int,
        now: LocalDateTime = LocalDateTime.now(),
        assignedSlot: TimeInterval? = null,
    ) {
        subTaskDao.upsert(
            subTask.copy(
                status = if (done) ProgressStatus.DONE else ProgressStatus.NOT_STARTED,
                completedAt = if (done) now else null,
            ).toEntity(orderIndex),
        )

        // Only completion is logged. Un-ticking is a correction to the current
        // state, not a new behavioural fact, and the stream is append-only, so
        // writing a row for it would leave a permanent record of an action the
        // user was in the middle of undoing.
        if (done) {
            adherence?.recordCompletion(
                taskId = subTask.id,
                assignedSlot = assignedSlot,
                completedAt = now,
                estimatedDuration = subTask.estimatedDuration,
            )
        }
    }

    /**
     * Marks a bare lesson done or not done.
     *
     * Only meaningful for a lesson with no sub-tasks: once a lesson has
     * sub-tasks, its completion is derived from them and this flag is ignored
     * by the rollup.
     */
    suspend fun setLessonDone(
        lesson: Lesson,
        done: Boolean,
        now: LocalDateTime = LocalDateTime.now(),
        assignedSlot: TimeInterval? = null,
        estimatedDuration: Duration = ScheduleBuilder.BARE_LESSON_ESTIMATE,
    ) {
        lessonDao.upsert(
            lesson.copy(
                status = if (done) ProgressStatus.DONE else ProgressStatus.NOT_STARTED,
                completedAt = if (done) now else null,
            ).toEntity(),
        )

        if (done) {
            adherence?.recordCompletion(
                taskId = lesson.id,
                assignedSlot = assignedSlot,
                completedAt = now,
                estimatedDuration = estimatedDuration,
            )
        }
    }
}

/**
 * Epoch millis as a local date, or null when it was never recorded.
 *
 * Rows written before creation timestamps existed carry zero, and treating
 * that as 1970 would make every such goal look older than any sweep window,
 * which is the safe direction but worth naming rather than leaving implicit.
 */
private fun Long.toLocalDateOrNull(): LocalDate? =
    if (this <= 0L) null else Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
