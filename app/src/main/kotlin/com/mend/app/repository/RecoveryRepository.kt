package com.mend.app.repository

import com.mend.app.data.dao.SleepLogDao
import com.mend.app.data.dao.TaskDao
import com.mend.app.data.mapper.toDomain
import com.mend.app.data.mapper.toEntity
import com.mend.app.data.mapper.toProtectedRecovery
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.TaskType
import com.mend.engine.recovery.SleepLogEntry
import com.mend.engine.time.TimeInterval
import com.mend.engine.tuning.Tuning
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Protected recovery, and the sleep stream it produces.
 *
 * Kept separate from [StaticLayerRepository] even though both read the `tasks`
 * table: sleep is the thing the day yields to, not another entry competing for
 * it, and mixing the two would blur exactly the distinction Phase 4 exists to
 * draw.
 */
class RecoveryRepository(
    private val taskDao: TaskDao,
    private val sleepLogDao: SleepLogDao,
    private val adherence: AdherenceRepository? = null,
) {

    fun observeProtectedRecovery(): Flow<List<ProtectedRecovery>> =
        taskDao.observeByType(TaskType.PROTECTED_RECOVERY).map { it.toProtectedRecovery() }

    /** The sleep block in force, if one is set. At most one is expected. */
    fun observeSleepBlock(): Flow<ProtectedRecovery?> =
        observeProtectedRecovery().map { it.firstOrNull() }

    suspend fun save(recovery: ProtectedRecovery) =
        taskDao.upsert(recovery.toEntity(createdAt = System.currentTimeMillis()))

    suspend fun delete(id: String) = taskDao.deleteById(id)

    fun observeSleepLog(): Flow<List<SleepLogEntry>> =
        sleepLogDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Records when you actually went to bed on [date].
     *
     * Opens the night's row if it does not exist yet. The wake time completes
     * it the next morning, so a night in progress is a real, visible state
     * rather than a gap.
     */
    suspend fun recordBedtime(
        date: LocalDate,
        block: ProtectedRecovery,
        actual: LocalDateTime,
    ) {
        val existing = sleepLogDao.forDate(date)?.toDomain()
        sleepLogDao.upsert(
            (existing ?: newNight(date, block)).copy(actualBedtime = actual).toEntity(),
        )
    }

    /**
     * Records when you actually got up, completing the night begun on [date].
     *
     * A completed night also writes one row to the app-wide adherence stream.
     * The two are not redundant: SleepLogEntry carries the detail specific to
     * sleep - bedtime drift, shortfall against target - while the adherence
     * entry carries the uniform honoured/missed signal, so protected recovery
     * shows up in app-wide statistics alongside everything else, which is what
     * PROJECT_PLAN.md Phase 5 asks for.
     */
    suspend fun recordWake(
        date: LocalDate,
        block: ProtectedRecovery,
        actual: LocalDateTime,
    ) {
        val night = (sleepLogDao.forDate(date)?.toDomain() ?: newNight(date, block))
            .copy(actualWake = actual)
        sleepLogDao.upsert(night.toEntity())

        if (!night.isComplete) return

        val slot = TimeInterval(night.targetBedtime, night.targetWake)
        val drift = night.bedtimeDriftMinutes ?: 0

        if (drift >= MISSED_BEDTIME_THRESHOLD_MINUTES) {
            adherence?.recordMiss(
                taskId = block.id,
                taskTitle = block.title,
                assignedSlot = slot,
                now = actual,
            )
        } else {
            adherence?.recordCompletion(
                taskId = block.id,
                assignedSlot = slot,
                completedAt = night.actualBedtime ?: night.targetBedtime,
                estimatedDuration = night.targetDuration,
            )
        }
    }

    companion object {
        /**
         * Past this much later than target, bedtime was not merely missed by a
         * margin - it was not observed at all.
         */
        const val MISSED_BEDTIME_THRESHOLD_MINUTES: Long = Tuning.MISSED_BEDTIME_THRESHOLD_MINUTES
    }

    private fun newNight(date: LocalDate, block: ProtectedRecovery) = SleepLogEntry(
        id = UUID.randomUUID().toString(),
        date = date,
        targetBedtime = date.atTime(block.bedtime),
        actualBedtime = null,
        // A night that crosses midnight wakes on the following calendar day.
        targetWake = if (block.crossesMidnight) {
            date.plusDays(1).atTime(block.wakeTime)
        } else {
            date.atTime(block.wakeTime)
        },
        actualWake = null,
    )
}
