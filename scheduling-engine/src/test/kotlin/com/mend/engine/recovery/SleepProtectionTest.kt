package com.mend.engine.recovery

import com.mend.engine.TEST_DATE
import com.mend.engine.allocation.allocate
import com.mend.engine.at
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.goalTask
import com.mend.engine.model.Flexibility
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.render
import com.mend.engine.sleep
import com.mend.engine.staticBlock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepProtectionTest {

    // --- the block itself ----------------------------------------------------

    @Test
    fun `a normal night crosses midnight`() {
        val night = sleep(bedtime = at(22, 30), wake = at(6, 30))
        assertTrue(night.crossesMidnight)
        assertEquals(Duration.ofHours(8), night.duration)
    }

    @Test
    fun `a night that does not cross midnight is still handled`() {
        val night = sleep(bedtime = at(1), wake = at(9))
        assertFalse(night.crossesMidnight)
        assertEquals(Duration.ofHours(8), night.duration)
    }

    @Test
    fun `bedtime equal to wake time is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ProtectedRecovery(
                id = "s",
                title = "Sleep",
                bedtime = LocalTime.of(22, 0),
                wakeTime = LocalTime.of(22, 0),
            )
        }
    }

    @Test
    fun `the hard stop lands a wind-down before bedtime`() {
        val night = sleep(bedtime = at(22, 30), wake = at(6, 30), windDownMinutes = 30)
        assertEquals(TEST_DATE.atTime(22, 0), night.hardStopAt(TEST_DATE))
    }

    @Test
    fun `the wind-down is configurable`() {
        val night = sleep(bedtime = at(22, 30), wake = at(6, 30), windDownMinutes = 90)
        assertEquals(TEST_DATE.atTime(21, 0), night.hardStopAt(TEST_DATE))
    }

    @Test
    fun `a night that does not apply today has no hard stop`() {
        val night = sleep(
            bedtime = at(22, 30),
            wake = at(6, 30),
            days = setOf(DayOfWeek.SUNDAY),
        )
        assertEquals(null, night.hardStopAt(TEST_DATE))
    }

    // --- how it shapes the day ----------------------------------------------

    @Test
    fun `sleep occupies both ends of the day it crosses`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            protectedRecovery = listOf(sleep(bedtime = at(22, 30), wake = at(6, 30))),
        )
        // Wind-down pulls the evening block back to 22:00.
        assertEquals(listOf("00:00-06:30", "22:00-24:00"), map.occupied.render())
        assertEquals(listOf("06:30-22:00"), map.free.render())
    }

    @Test
    fun `the wind-down is taken out of usable time, not just marked`() {
        val withWindDown = computeFreeTimeMap(
            date = TEST_DATE,
            protectedRecovery = listOf(
                sleep(bedtime = at(23), wake = at(7), windDownMinutes = 60),
            ),
        )
        assertEquals(listOf("07:00-22:00"), withWindDown.free.render())
    }

    @Test
    fun `a zero wind-down puts the boundary at bedtime itself`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            protectedRecovery = listOf(
                sleep(bedtime = at(23), wake = at(7), windDownMinutes = 0),
            ),
        )
        assertEquals(listOf("07:00-23:00"), map.free.render())
    }

    @Test
    fun `goal work is never scheduled past the bedtime boundary`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            staticBlocks = listOf(staticBlock("college", from = at(9), to = at(17))),
            protectedRecovery = listOf(sleep(bedtime = at(22), wake = at(6))),
        )
        val schedule = allocate(
            map,
            listOf(
                goalTask(
                    "revision",
                    deadlineIn = 0,
                    minutes = 600,
                    flexibility = Flexibility.SPLITTABLE,
                ),
            ),
        )
        val boundary = TEST_DATE.atTime(21, 30)
        schedule.placements.flatMap { it.slices }.forEach { slice ->
            assertTrue(
                !slice.end.isAfter(boundary),
                "$slice runs past the bedtime boundary at $boundary",
            )
        }
    }

    @Test
    fun `sleep beats outstanding work for the late evening`() {
        // Nine hours of work wanted, but the evening ends at 22:00.
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            protectedRecovery = listOf(sleep(bedtime = at(22, 30), wake = at(6, 30))),
        )
        val schedule = allocate(
            map,
            listOf(goalTask("cram", deadlineIn = 0, minutes = 1200)),
        )
        val placement = schedule.placements.single()
        assertTrue(placement.isPartial, "an overfull day must not borrow from sleep")
        assertEquals(930, placement.scheduledMinutes) // 06:30 to 22:00
    }

    @Test
    fun `the placement records that sleep is protected and why`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            protectedRecovery = listOf(sleep(bedtime = at(22, 30), wake = at(6, 30))),
        )
        val evening = map.placements.first { it.interval.start.hour >= 20 }
        assertTrue(evening.reason.contains("wind-down"))
        assertTrue(evening.reason.contains("hard stop"))
    }

    @Test
    fun `a nudge-only block says so in its reasoning`() {
        val map = computeFreeTimeMap(
            date = TEST_DATE,
            protectedRecovery = listOf(
                sleep(bedtime = at(22, 30), wake = at(6, 30), hardStop = false),
            ),
        )
        assertTrue(map.placements.any { it.reason.contains("nudge only") })
    }

    // --- the real-time boundary ---------------------------------------------

    private fun eveningSchedule() = allocate(
        computeFreeTimeMap(
            date = TEST_DATE,
            protectedRecovery = listOf(sleep(bedtime = at(22, 30), wake = at(6, 30))),
        ),
        listOf(goalTask("essay", deadlineIn = 0, minutes = 900)),
    )

    @Test
    fun `nothing is said when the boundary is far away`() {
        val decision = evaluateSleepStop(
            now = TEST_DATE.atTime(14, 0),
            schedule = eveningSchedule(),
            recovery = sleep(bedtime = at(22, 30), wake = at(6, 30)),
        )
        assertEquals(SleepStopAction.NONE, decision.action)
        assertFalse(decision.isEnforced)
    }

    @Test
    fun `a warning comes shortly before the boundary`() {
        val decision = evaluateSleepStop(
            now = TEST_DATE.atTime(21, 50),
            schedule = eveningSchedule(),
            recovery = sleep(bedtime = at(22, 30), wake = at(6, 30)),
        )
        assertEquals(SleepStopAction.WARN, decision.action)
        assertEquals(10, decision.minutesUntilHardStop)
        assertTrue(decision.message.contains("Winding down"))
    }

    @Test
    fun `the hard stop is enforced once the boundary passes`() {
        val decision = evaluateSleepStop(
            now = TEST_DATE.atTime(22, 15),
            schedule = eveningSchedule(),
            recovery = sleep(bedtime = at(22, 30), wake = at(6, 30)),
        )
        assertEquals(SleepStopAction.STOP, decision.action)
        assertTrue(decision.isEnforced)
        assertTrue(decision.message.contains("Stopping for the night"))
    }

    @Test
    fun `a nudge block does not force a stop`() {
        val decision = evaluateSleepStop(
            now = TEST_DATE.atTime(22, 15),
            schedule = eveningSchedule(),
            recovery = sleep(bedtime = at(22, 30), wake = at(6, 30), hardStop = false),
        )
        assertEquals(SleepStopAction.NUDGE, decision.action)
        assertFalse(decision.isEnforced)
        assertTrue(decision.message.contains("Your call"))
    }

    @Test
    fun `with no sleep block configured nothing is enforced`() {
        val decision = evaluateSleepStop(
            now = TEST_DATE.atTime(23, 0),
            schedule = eveningSchedule(),
            recovery = null,
        )
        assertEquals(SleepStopAction.NONE, decision.action)
        assertFalse(decision.isEnforced)
    }

    // --- the sleep adherence stream -----------------------------------------

    private fun night(
        bedDrift: Long,
        wakeDrift: Long,
        date: java.time.LocalDate = TEST_DATE,
    ) = SleepLogEntry(
        id = "n-$date-$bedDrift",
        date = date,
        targetBedtime = date.atTime(22, 30),
        actualBedtime = date.atTime(22, 30).plusMinutes(bedDrift),
        targetWake = date.plusDays(1).atTime(6, 30),
        actualWake = date.plusDays(1).atTime(6, 30).plusMinutes(wakeDrift),
    )

    @Test
    fun `a night records how far bedtime drifted`() {
        assertEquals(75, night(bedDrift = 75, wakeDrift = 0).bedtimeDriftMinutes)
    }

    @Test
    fun `going to bed early reports a negative drift`() {
        assertEquals(-20, night(bedDrift = -20, wakeDrift = 0).bedtimeDriftMinutes)
    }

    @Test
    fun `shortfall is target sleep minus what was actually had`() {
        // An hour late to bed, up at the usual time: an hour lost.
        assertEquals(60, night(bedDrift = 60, wakeDrift = 0).shortfallMinutes)
    }

    @Test
    fun `a lie-in offsets a late night`() {
        assertEquals(0, night(bedDrift = 60, wakeDrift = 60).shortfallMinutes)
    }

    @Test
    fun `a night still in progress has no shortfall yet`() {
        val inProgress = SleepLogEntry(
            id = "n1",
            date = TEST_DATE,
            targetBedtime = TEST_DATE.atTime(22, 30),
            actualBedtime = TEST_DATE.atTime(23, 15),
            targetWake = TEST_DATE.plusDays(1).atTime(6, 30),
            actualWake = null,
        )
        assertFalse(inProgress.isComplete)
        assertEquals(null, inProgress.shortfallMinutes)
    }

    @Test
    fun `the trend averages only complete nights`() {
        val trend = computeSleepTrend(
            listOf(
                night(bedDrift = 60, wakeDrift = 0, date = TEST_DATE),
                night(bedDrift = 0, wakeDrift = 0, date = TEST_DATE.plusDays(1)),
                SleepLogEntry(
                    id = "open",
                    date = TEST_DATE.plusDays(2),
                    targetBedtime = TEST_DATE.plusDays(2).atTime(22, 30),
                    actualBedtime = TEST_DATE.plusDays(2).atTime(23, 0),
                    targetWake = TEST_DATE.plusDays(3).atTime(6, 30),
                    actualWake = null,
                ),
            ),
        )
        assertEquals(2, trend.nightsRecorded)
        assertEquals(30.0, trend.averageBedtimeDriftMinutes)
        assertEquals(30.0, trend.averageShortfallMinutes)
    }

    @Test
    fun `a night within the slack counts as on target`() {
        val trend = computeSleepTrend(
            listOf(
                night(bedDrift = 10, wakeDrift = 0, date = TEST_DATE),
                night(bedDrift = 90, wakeDrift = 0, date = TEST_DATE.plusDays(1)),
            ),
        )
        assertEquals(1, trend.nightsOnTarget)
        assertEquals(0.5, trend.onTargetRate)
    }

    @Test
    fun `an empty sleep log reports nothing rather than dividing by zero`() {
        val trend = computeSleepTrend(emptyList())
        assertEquals(0, trend.nightsRecorded)
        assertEquals(0.0, trend.onTargetRate)
        assertEquals(0.0, trend.averageShortfallMinutes)
    }

    @Test
    fun `the sleep stream is kept separate from task adherence`() {
        // A compile-level guarantee as much as a behavioural one: SleepLogEntry
        // has no taskId, so sleep can never be filed as a missed task.
        val entry = night(bedDrift = 0, wakeDrift = 0)
        assertEquals(TEST_DATE, entry.date)
        assertEquals(Duration.ofHours(8), entry.targetDuration)
    }
}
