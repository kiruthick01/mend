package com.mend.app.alarms

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.mend.app.repository.GoalRepository
import com.mend.app.repository.RecoveryRepository
import com.mend.app.repository.ScheduleBuilder
import com.mend.app.repository.StaticLayerRepository
import com.mend.app.repository.WeightsRepository
import com.mend.app.widget.MendWidget
import com.mend.engine.alarms.ScheduledAlarm
import com.mend.engine.alarms.diffAlarms
import com.mend.engine.alarms.planAlarms
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeping the armed alarms in step with the schedule.
 *
 * The day is rebuilt constantly — every edit, every completion — and naively
 * re-arming everything each time would cancel and re-create a dozen identical
 * alarms a minute. So the plan is diffed against what is already armed and only
 * the difference is touched. See `diffAlarms` for what counts as a difference.
 *
 * Two rules make that safe, and both were learned the hard way on a real
 * device, where two callers replanning at once spent a second cancelling each
 * other's alarms in turn and left the stored set describing neither:
 *
 *  1. **One writer.** Nothing else computes an alarm plan. Callers say "the day
 *     changed, work it out again"; they do not hand in a plan of their own,
 *     because a plan computed a moment ago is a plan that can undo a newer one.
 *  2. **One at a time.** Reading what is armed, diffing, arming and storing the
 *     result is a single critical section. It is short, and it is the only
 *     thing standing between a concurrent edit and an alarm that was cancelled
 *     but never replaced.
 */
class AlarmCoordinator(
    private val context: Context,
    private val staticLayer: StaticLayerRepository,
    private val goals: GoalRepository,
    private val recovery: RecoveryRepository,
    private val weights: WeightsRepository,
    private val state: AlarmState,
) {

    private val scheduler = AlarmScheduler(context)

    /** Serialises read-diff-arm-store. See rule 2 above. */
    private val lock = Mutex()

    /**
     * An emergency interrupt, which is not part of the planned day.
     *
     * Held here rather than armed separately so that it survives the next
     * replan: an interrupt that quietly disappeared because something else
     * edited the day would be the worst possible thing for it to do.
     */
    @Volatile
    private var interrupt: ScheduledAlarm? = null

    /**
     * Rebuilds today's plan and arms the difference.
     *
     * Returns how many alarms are now armed, for the log — "it did not fire" is
     * a question that needs an answer afterwards.
     */
    suspend fun replanToday(now: LocalDateTime): Int {
        val armed = lock.withLock { planAndApply(now) }

        // The widget and the lock-screen note read the same schedule, so they
        // are stale the moment it is rebuilt. Refreshing them here means every
        // path that replans — a boot, the daily worker, a completion — keeps
        // all three surfaces saying the same thing. Outside the lock: neither
        // touches the armed set, and both can be slow.
        MendWidget().updateAll(context)
        OngoingNote.refresh(context)

        return armed
    }

    /**
     * Sets or clears the emergency interrupt. Takes effect at the next replan.
     *
     * Passing null clears it, which is what happens once the interrupt has been
     * dealt with or the condition that raised it has gone. Deliberately does
     * not replan by itself: the caller is about to, and replanning twice for
     * one change is how a diff ends up racing its own previous result.
     */
    fun setInterrupt(alarm: ScheduledAlarm?) {
        interrupt = alarm
    }

    /**
     * Re-arms everything after a reboot.
     *
     * Alarms do not survive one. Whatever was armed before the phone went down
     * is simply gone, so the stored set is cleared first and the whole day is
     * planned again from scratch — otherwise the diff would decide that
     * everything was already armed and do nothing at all.
     */
    suspend fun rearmAfterBoot(now: LocalDateTime) {
        lock.withLock {
            state.armed = emptyList()
            planAndApply(now)
        }
        MendWidget().updateAll(context)
        OngoingNote.refresh(context)
        Log.d(AlarmScheduler.LOG_TAG, "re-armed after boot")
    }

    /** Whether the system will honour exact timing, for the UI to report. */
    fun canScheduleExact(): Boolean = scheduler.canScheduleExact()

    fun exactAlarmSettingsIntent() = scheduler.exactAlarmSettingsIntent()

    /**
     * Computes the plan and arms the difference. Call holding [lock].
     *
     * Computing inside the lock is the point: a plan built outside it is
     * already potentially out of date by the time it is applied.
     */
    private suspend fun planAndApply(now: LocalDateTime): Int {
        val sleepBlock = recovery.observeSleepBlock().first()

        val schedule = ScheduleBuilder.buildDaySchedule(
            date = now.toLocalDate(),
            staticBlocks = staticLayer.observeStaticBlocks().first(),
            habits = staticLayer.observeHabits().first(),
            goals = goals.observeGoals().first(),
            protectedRecovery = listOfNotNull(sleepBlock),
            weights = weights.current(),
        )

        val desired = planAlarms(schedule = schedule, now = now, sleepBlock = sleepBlock) +
            listOfNotNull(interrupt?.takeIf { it.at.isAfter(now) })

        val diff = diffAlarms(current = state.armed, desired = desired)

        if (diff.isEmpty) {
            Log.d(AlarmScheduler.LOG_TAG, "plan unchanged; ${diff.unchanged.size} still armed")
            return desired.size
        }

        scheduler.cancelAll(diff.toCancel)
        scheduler.armAll(diff.toArm)
        state.armed = desired

        Log.d(
            AlarmScheduler.LOG_TAG,
            "armed ${diff.toArm.size}, cancelled ${diff.toCancel.size}, " +
                "${diff.unchanged.size} unchanged",
        )
        return desired.size
    }
}
