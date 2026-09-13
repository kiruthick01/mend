package com.mend.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Flexibility
import com.mend.engine.model.ProgressStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Persisted form of `Goal` (PROJECT_PLAN.md Section 3). */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val deadline: LocalDate,
    @ColumnInfo(name = "manual_importance") val manualImportance: Int,
    @ColumnInfo(name = "consequence_type") val consequenceType: ConsequenceType,
    @ColumnInfo(name = "is_one_shot") val isOneShot: Boolean = false,
    @ColumnInfo(name = "total_lessons") val totalLessons: Int = 0,
    @ColumnInfo(name = "pileup_risk") val pileupRisk: Boolean = false,
    /**
     * True when lessons must be done in order, so nothing downstream can start
     * until the current one does. Only sequential work can ever negotiate a
     * delay; parallelisable work has something else to do instead.
     */
    @ColumnInfo(name = "is_sequential") val isSequential: Boolean = false,
    @ColumnInfo(name = "negotiated_delay_limit_minutes") val negotiatedDelayLimitMinutes: Long? = null,
    /** Hours this goal's work is confined to, if any. */
    @ColumnInfo(name = "work_window_start") val workWindowStart: LocalTime? = null,
    @ColumnInfo(name = "work_window_end") val workWindowEnd: LocalTime? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
)

/**
 * Persisted form of `Lesson`.
 *
 * Deleting a goal cascades to its lessons, and deleting a lesson cascades to
 * its sub-tasks: the hierarchy is a genuine ownership tree, so orphaned
 * children are never a valid state.
 */
@Entity(
    tableName = "lessons",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goal_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("goal_id")],
)
data class LessonEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "goal_id") val goalId: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    val title: String,
    val status: ProgressStatus = ProgressStatus.NOT_STARTED,
    @ColumnInfo(name = "completed_at") val completedAt: LocalDateTime? = null,
)

/**
 * Persisted form of `SubTask`.
 *
 * [type] is free text on purpose. Sub-task structure is defined per lesson,
 * with no template shared across a goal, so there is no fixed category set to
 * constrain it to.
 */
@Entity(
    tableName = "sub_tasks",
    foreignKeys = [
        ForeignKey(
            entity = LessonEntity::class,
            parentColumns = ["id"],
            childColumns = ["lesson_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("lesson_id")],
)
data class SubTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lesson_id") val lessonId: String,
    val type: String,
    val title: String,
    @ColumnInfo(name = "estimated_duration_minutes") val estimatedDurationMinutes: Long,
    @ColumnInfo(name = "pace_comfortable_minutes") val paceComfortableMinutes: Long? = null,
    val flexibility: Flexibility = Flexibility.SPLITTABLE,
    val status: ProgressStatus = ProgressStatus.NOT_STARTED,
    @ColumnInfo(name = "completed_at") val completedAt: LocalDateTime? = null,
    @ColumnInfo(name = "order_index") val orderIndex: Int = 0,
)
