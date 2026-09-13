package com.mend.engine.alarms

import com.mend.engine.TEST_DATE
import com.mend.engine.allocation.allocate
import com.mend.engine.at
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.goalTask
import com.mend.engine.model.Flexibility
import com.mend.engine.sleep
import com.mend.engine.staticBlock
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmPlanTest {

    private val morning = TEST_DATE.atTime(7, 0)

    /** A day free 09:00-22:00 with two hour-long tasks in it. */
    private fun workingDay() = allocate(
        computeFreeTimeMap(TEST_DATE, dayStart = at(9), dayEnd = at(22)),
        listOf(
            goalTask("reading", deadlineIn = 1, minutes = 60, flexibility = Flexibility.MOVABLE),
            goalTask("problems", deadlineIn = 2, minutes = 60, flexibility = Flexibility.MOVABLE),
        ),
    )

    // --- task starts ---------------------------------------------------------

    @Test
    fun `each scheduled task gets one alarm at its start`() {
        val alarms = planAlarms(workingDay(), now = morning)
            .filter { it.kind == AlarmKind.TASK_START }

        assertEquals(2, alarms.size)
        assertEquals(TEST_DATE.atTime(9, 0), alarms.first().at)
        assertEquals(TEST_DATE.atTime(10, 0), alarms[1].at)
    }

    @Test
    fun `a task start is not critical enough to wake the device`() {
        val alarm = planAlarms(workingDay(), now = morning).first()
        assertEquals(AlarmCriticality.NORMAL, alarm.criticality)
        assertFalse(alarm.isCritical)
    }

    @Test
    fun `split work gets one alarm, not one per piece`() {
        // 07:00-09:00 and 16:00-20:00, with three hours of splittable work.
        val schedule = allocate(
            computeFreeTimeMap(
                TEST_DATE,
                staticBlocks = listOf(staticBlock("college", from = at(9), to = at(16))),
                dayStart = at(7),
                dayEnd = at(20),
            ),
            listOf(
                goalTask("revision", deadlineIn = 0, minutes = 180, flexibility = Flexibility.SPLITTABLE),
            ),
        )
        val alarms = planAlarms(schedule, now = TEST_DATE.atTime(6, 0))
            .filter { it.kind == AlarmKind.TASK_START }

        assertEquals(1, alarms.size, "three buzzes for one task is how an app gets silenced")
        assertEquals(TEST_DATE.atTime(7, 0), alarms.single().at)
        assertTrue(alarms.single().body.contains("first of 2 blocks"))
    }

    @Test
    fun `a partial placement says how much was actually wanted`() {
        val schedule = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(9), dayEnd = at(11)),
            listOf(goalTask("marathon", deadlineIn = 0, minutes = 300)),
        )
        val alarm = planAlarms(schedule, now = morning).single { it.kind == AlarmKind.TASK_START }
        assertTrue(alarm.body.contains("300m wanted in total"))
    }

    @Test
    fun `an alarm names the task it is about`() {
        val alarm = planAlarms(workingDay(), now = morning).first()
        assertEquals("reading", alarm.taskId)
        assertEquals("reading", alarm.title)
    }

    // --- the past ------------------------------------------------------------

    @Test
    fun `alarms already in the past are dropped`() {
        // Midday: the 09:00 start has gone, the 10:00 one too.
        val alarms = planAlarms(workingDay(), now = TEST_DATE.atTime(12, 0))
        assertTrue(alarms.none { it.kind == AlarmKind.TASK_START })
    }

    @Test
    fun `an alarm too close to now is not worth setting`() {
        // 08:59 against a 09:00 start: it would arrive while the user is still
        // looking at the screen that scheduled it.
        val alarms = planAlarms(workingDay(), now = TEST_DATE.atTime(8, 59))
        assertTrue(alarms.none { it.at == TEST_DATE.atTime(9, 0) })
    }

    @Test
    fun `an alarm comfortably ahead is kept`() {
        val alarms = planAlarms(workingDay(), now = TEST_DATE.atTime(8, 50))
        assertTrue(alarms.any { it.at == TEST_DATE.atTime(9, 0) })
    }

    @Test
    fun `an empty day needs no alarms`() {
        val empty = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(9), dayEnd = at(17)),
            emptyList(),
        )
        assertEquals(emptyList(), planAlarms(empty, now = morning))
    }

    // --- sleep ---------------------------------------------------------------

    private fun nightBlock(bedtime: LocalTime = at(22, 30)) =
        sleep(bedtime = bedtime, wake = at(6, 30), windDownMinutes = 30)

    @Test
    fun `sleep produces a wind-down warning and a hard stop`() {
        val alarms = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())

        val windDown = alarms.single { it.kind == AlarmKind.SLEEP_WIND_DOWN }
        val stop = alarms.single { it.kind == AlarmKind.SLEEP_HARD_STOP }

        // Boundary is 22:00 (bedtime minus wind-down); the warning is 15m before.
        assertEquals(TEST_DATE.atTime(21, 45), windDown.at)
        assertEquals(TEST_DATE.atTime(22, 0), stop.at)
    }

    @Test
    fun `the hard stop is the one alarm that must survive everything`() {
        val stop = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
            .single { it.kind == AlarmKind.SLEEP_HARD_STOP }
        assertEquals(AlarmCriticality.CRITICAL, stop.criticality)
        assertTrue(stop.isCritical)
    }

    @Test
    fun `the hard stop names what it is costing you`() {
        // A long task running past the 22:00 boundary.
        val schedule = allocate(
            computeFreeTimeMap(TEST_DATE, dayStart = at(9), dayEnd = at(23, 59)),
            listOf(goalTask("essay", deadlineIn = 0, minutes = 800)),
        )
        val stop = planAlarms(schedule, now = morning, sleepBlock = nightBlock())
            .single { it.kind == AlarmKind.SLEEP_HARD_STOP }

        assertTrue(stop.body.contains("essay"))
        assertTrue(stop.body.contains("moves to tomorrow"))
    }

    @Test
    fun `a clear evening says so rather than inventing a cost`() {
        val stop = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
            .single { it.kind == AlarmKind.SLEEP_HARD_STOP }
        assertEquals("Nothing is still scheduled.", stop.body)
    }

    @Test
    fun `a nudge-only block is worded as a nudge`() {
        val nudge = sleep(bedtime = at(22, 30), wake = at(6, 30), hardStop = false)
        val stop = planAlarms(workingDay(), now = morning, sleepBlock = nudge)
            .single { it.kind == AlarmKind.SLEEP_HARD_STOP }
        assertEquals("Past your wind-down", stop.title)
    }

    @Test
    fun `no sleep block means no sleep alarms`() {
        val alarms = planAlarms(workingDay(), now = morning, sleepBlock = null)
        assertTrue(alarms.none { it.kind == AlarmKind.SLEEP_HARD_STOP })
    }

    @Test
    fun `a sleep block that does not apply tonight produces nothing`() {
        val sundayOnly = sleep(
            bedtime = at(22, 30),
            wake = at(6, 30),
            days = setOf(java.time.DayOfWeek.SUNDAY),
        )
        val alarms = planAlarms(workingDay(), now = morning, sleepBlock = sundayOnly)
        assertTrue(alarms.none { it.kind == AlarmKind.SLEEP_HARD_STOP })
    }

    // --- ordering and identity ----------------------------------------------

    @Test
    fun `alarms come back in time order`() {
        val alarms = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
        assertEquals(alarms.map { it.at }.sorted(), alarms.map { it.at })
    }

    @Test
    fun `ids are stable across recomputes of the same day`() {
        val first = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
        val second = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `ids are distinct within a plan`() {
        val alarms = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
        assertEquals(alarms.size, alarms.map { it.id }.distinct().size)
    }

    // --- interrupts ----------------------------------------------------------

    @Test
    fun `an interrupt alarm is critical and says what to do`() {
        val alarm = interruptAlarm(
            taskId = "visa",
            taskTitle = "Visa form",
            at = TEST_DATE.atTime(14, 0),
            message = "45m, starting now.",
        )
        assertEquals(AlarmCriticality.CRITICAL, alarm.criticality)
        assertEquals(AlarmKind.INTERRUPT, alarm.kind)
        assertTrue(alarm.title.contains("Do this next"))
        assertTrue(alarm.title.contains("Visa form"))
    }

    @Test
    fun `an interrupt alarm replaces itself rather than stacking`() {
        val first = interruptAlarm("visa", "Visa form", TEST_DATE.atTime(14, 0), "a")
        val later = interruptAlarm("visa", "Visa form", TEST_DATE.atTime(16, 0), "b")
        assertEquals(first.id, later.id)
    }

    // --- diffing -------------------------------------------------------------

    @Test
    fun `an unchanged plan arms and cancels nothing`() {
        val plan = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
        val diff = diffAlarms(current = plan, desired = plan)
        assertTrue(diff.isEmpty)
        assertEquals(plan.size, diff.unchanged.size)
    }

    @Test
    fun `a first run arms everything`() {
        val plan = planAlarms(workingDay(), now = morning)
        val diff = diffAlarms(current = emptyList(), desired = plan)
        assertEquals(plan.size, diff.toArm.size)
        assertTrue(diff.toCancel.isEmpty())
    }

    @Test
    fun `work that disappears has its alarm cancelled`() {
        val before = planAlarms(workingDay(), now = morning)
        val after = planAlarms(
            allocate(
                computeFreeTimeMap(TEST_DATE, dayStart = at(9), dayEnd = at(22)),
                listOf(goalTask("reading", deadlineIn = 1, minutes = 60, flexibility = Flexibility.MOVABLE)),
            ),
            now = morning,
        )
        val diff = diffAlarms(current = before, desired = after)
        assertEquals(1, diff.toCancel.size)
        assertEquals("problems", diff.toCancel.single().taskId)
    }

    @Test
    fun `work that moves is cancelled and re-armed`() {
        val before = planAlarms(workingDay(), now = morning)
        // Same tasks, a day that starts later, so both move.
        val after = planAlarms(
            allocate(
                computeFreeTimeMap(TEST_DATE, dayStart = at(14), dayEnd = at(22)),
                listOf(
                    goalTask("reading", deadlineIn = 1, minutes = 60, flexibility = Flexibility.MOVABLE),
                    goalTask("problems", deadlineIn = 2, minutes = 60, flexibility = Flexibility.MOVABLE),
                ),
            ),
            now = morning,
        )
        val diff = diffAlarms(current = before, desired = after)
        assertEquals(2, diff.toCancel.size)
        assertEquals(2, diff.toArm.size)
        assertTrue(diff.unchanged.isEmpty())
    }

    @Test
    fun `an edited bedtime moves the alarm rather than being mistaken for unchanged`() {
        // The bedtime alarms are identified by date alone, so an edit keeps the
        // id and changes the time. Diffing on ids alone called this "already
        // armed" and left the hard stop firing at the old time — seen on a real
        // phone after changing 23:20 to 23:10.
        val before = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
        val after = planAlarms(
            workingDay(),
            now = morning,
            sleepBlock = nightBlock(bedtime = at(21, 30)),
        )

        val diff = diffAlarms(current = before, desired = after)

        assertFalse(diff.isEmpty, "an earlier bedtime is a change")
        assertTrue(
            diff.unchanged.none { it.kind in SLEEP_KINDS },
            "neither bedtime alarm is where it was",
        )
        assertEquals(
            after.filter { it.kind in SLEEP_KINDS }.toSet(),
            diff.toArm.filter { it.kind in SLEEP_KINDS }.toSet(),
            "both moved bedtime alarms are re-armed",
        )
        // The day's work did not move, so its alarms are left alone.
        assertTrue(diff.toArm.none { it.kind == AlarmKind.TASK_START })
    }

    private val SLEEP_KINDS =
        setOf(AlarmKind.SLEEP_WIND_DOWN, AlarmKind.SLEEP_HARD_STOP)

    @Test
    fun `an alarm that only moved is re-armed, not cancelled first`() {
        // Arming replaces an alarm with the same id outright, so re-arming is
        // atomic where cancel-then-arm leaves a window with nothing armed.
        val before = planAlarms(workingDay(), now = morning, sleepBlock = nightBlock())
        val after = planAlarms(
            workingDay(),
            now = morning,
            sleepBlock = nightBlock(bedtime = at(21, 30)),
        )

        val diff = diffAlarms(current = before, desired = after)

        val movedIds = after.map { it.id }.toSet()
        assertTrue(
            diff.toCancel.none { it.id in movedIds },
            "an id that still exists must not be cancelled",
        )
    }
}
