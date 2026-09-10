package com.mend.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Flexibility
import com.mend.engine.model.TaskType
import com.mend.engine.model.UnitDuration
import com.mend.engine.model.WindowAnchor
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The persisted form of `Task (base)` from PROJECT_PLAN.md Section 3.
 *
 * Storage is deliberately flat — one table, a [type] discriminator, and
 * nullable columns for the fields only some types use. The type-level
 * guarantees (habits have no priority, static blocks are never ranked) live in
 * the engine's sealed `ScheduleEntry` hierarchy, which this table is mapped
 * into on read. SQLite has no sum types; the domain model does, and that is
 * the layer the scheduler actually reasons about.
 */
@Entity(
    tableName = "tasks",
    indices = [Index("type"), Index("goal_id")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: TaskType,
    @ColumnInfo(name = "consequence_type") val consequenceType: ConsequenceType,
    @ColumnInfo(name = "days_of_week") val daysOfWeek: Set<DayOfWeek>,

    // --- STATIC_BLOCK columns -------------------------------------------------
    @ColumnInfo(name = "window_start") val windowStart: LocalTime? = null,
    @ColumnInfo(name = "window_end") val windowEnd: LocalTime? = null,
    /** Required time within the window; null means the whole window is occupied. */
    @ColumnInfo(name = "required_duration_minutes") val requiredDurationMinutes: Long? = null,
    @ColumnInfo(name = "window_anchor") val windowAnchor: WindowAnchor? = null,

    // --- HABIT columns --------------------------------------------------------
    @ColumnInfo(name = "time_of_day") val timeOfDay: LocalTime? = null,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Long? = null,

    // --- Schedulable columns (goal tasks, interrupts, recovery) ---------------
    @ColumnInfo(name = "unit_duration") val unitDuration: UnitDuration? = null,
    val flexibility: Flexibility? = null,
    @ColumnInfo(name = "goal_id") val goalId: String? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
)
