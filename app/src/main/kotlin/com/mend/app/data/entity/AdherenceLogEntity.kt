package com.mend.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mend.engine.model.AdherenceOutcome
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Persisted form of `AdherenceLogEntry`.
 *
 * Append-only by contract: the DAO exposes insert and read, and deliberately
 * exposes no update or delete. Behavioural pattern detection is only worth
 * trusting if the history behind it cannot be quietly rewritten.
 *
 * There is intentionally no foreign key to `tasks` — a log entry must survive
 * the deletion of the task it describes, or the record of a missed deadline
 * would vanish along with the task.
 */
@Entity(
    tableName = "adherence_log",
    indices = [Index("task_id"), Index("date")],
)
data class AdherenceLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val date: LocalDate,
    @ColumnInfo(name = "assigned_slot_start") val assignedSlotStart: LocalDateTime,
    @ColumnInfo(name = "assigned_slot_end") val assignedSlotEnd: LocalDateTime,
    @ColumnInfo(name = "completed_at") val completedAt: LocalDateTime? = null,
    @ColumnInfo(name = "delay_minutes") val delayMinutes: Long = 0,
    val outcome: AdherenceOutcome,
)
