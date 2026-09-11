package com.mend.app.data.mapper

import com.mend.app.data.entity.ReshuffleProposalEntity
import com.mend.app.data.entity.TaskEntity
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Habit
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.TaskType
import com.mend.engine.recovery.SleepLogEntry
import com.mend.engine.reshuffle.ReshuffleResponse
import com.mend.engine.reshuffle.ScheduleInvalidatingEvent
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase4MappersTest {

    private val date = LocalDate.of(2026, 9, 16)

    private val night = ProtectedRecovery(
        id = "sleep",
        title = "Sleep",
        daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        bedtime = LocalTime.of(22, 30),
        wakeTime = LocalTime.of(6, 30),
        windDown = Duration.ofMinutes(45),
        hardStop = true,
    )

    // --- protected recovery --------------------------------------------------

    @Test
    fun `a sleep block round-trips unchanged`() {
        assertEquals(night, night.toEntity().toProtectedRecoveryOrNull())
    }

    @Test
    fun `a nudge-only block keeps its setting through persistence`() {
        val nudge = night.copy(hardStop = false)
        assertEquals(false, nudge.toEntity().toProtectedRecoveryOrNull()?.hardStop)
    }

    @Test
    fun `a zero wind-down survives the round trip`() {
        val instant = night.copy(windDown = Duration.ZERO)
        assertEquals(Duration.ZERO, instant.toEntity().toProtectedRecoveryOrNull()?.windDown)
    }

    @Test
    fun `recovery is stored with no consequence type`() {
        assertEquals(ConsequenceType.NONE, night.toEntity().consequenceType)
        assertEquals(TaskType.PROTECTED_RECOVERY, night.toEntity().type)
    }

    @Test
    fun `a static block row is never read back as a sleep block`() {
        val block = StaticBlock(
            id = "college",
            title = "College",
            windowStart = LocalTime.of(9, 0),
            windowEnd = LocalTime.of(16, 0),
        )
        assertNull(block.toEntity().toProtectedRecoveryOrNull())
    }

    @Test
    fun `a habit row is never read back as a sleep block`() {
        val habit = Habit(id = "brush", title = "Brush", timeOfDay = LocalTime.of(7, 0))
        assertNull(habit.toEntity().toProtectedRecoveryOrNull())
    }

    @Test
    fun `a sleep row is never read back as a static block or habit`() {
        assertNull(night.toEntity().toStaticBlockOrNull())
        assertNull(night.toEntity().toHabitOrNull())
    }

    @Test
    fun `a sleep row with identical bedtime and wake is rejected rather than guessed`() {
        val broken = night.toEntity().copy(windowEnd = night.bedtime)
        assertNull(broken.toProtectedRecoveryOrNull())
    }

    @Test
    fun `a legacy row with no hard-stop flag defaults to protecting sleep`() {
        val legacy = night.toEntity().copy(hardStop = null)
        assertEquals(true, legacy.toProtectedRecoveryOrNull()?.hardStop)
    }

    @Test
    fun `list mapping keeps only recovery rows`() {
        val rows = listOf(
            night.toEntity(),
            Habit(id = "brush", title = "Brush", timeOfDay = LocalTime.of(7, 0)).toEntity(),
        )
        assertEquals(listOf("sleep"), rows.toProtectedRecovery().map { it.id })
    }

    // --- sleep log -----------------------------------------------------------

    @Test
    fun `a completed night round-trips`() {
        val entry = SleepLogEntry(
            id = "n1",
            date = date,
            targetBedtime = date.atTime(22, 30),
            actualBedtime = date.atTime(23, 45),
            targetWake = date.plusDays(1).atTime(6, 30),
            actualWake = date.plusDays(1).atTime(7, 10),
        )
        assertEquals(entry, entry.toEntity().toDomain())
    }

    @Test
    fun `a night still in progress keeps its nulls`() {
        val entry = SleepLogEntry(
            id = "n1",
            date = date,
            targetBedtime = date.atTime(22, 30),
            actualBedtime = date.atTime(23, 0),
            targetWake = date.plusDays(1).atTime(6, 30),
            actualWake = null,
        )
        val restored = entry.toEntity().toDomain()
        assertNull(restored.actualWake)
        assertEquals(entry, restored)
    }

    // --- schedule events -----------------------------------------------------

    @Test
    fun `each event kind is stored with its own discriminator`() {
        val now = date.atTime(9, 0)
        val kinds = listOf(
            ScheduleInvalidatingEvent.DeadlineAdded(now, "g", "Japanese", date),
            ScheduleInvalidatingEvent.DeadlineChanged(now, "g", "Japanese", date, date.plusDays(3)),
            ScheduleInvalidatingEvent.TaskMissed(now, "t", "Reading", date),
            ScheduleInvalidatingEvent.ManualEdit(now, "gym moved"),
        ).map { it.toEntity("id").kind }

        assertEquals(4, kinds.distinct().size)
    }

    @Test
    fun `an event stores the rendered summary so it reads the same later`() {
        val event = ScheduleInvalidatingEvent.TaskMissed(
            date.atTime(9, 0), "t", "Reading", date,
        )
        val row = event.toEntity("id")
        assertEquals(event.describe(), row.summary)
        assertEquals(event.describe(), row.toDomain().describe())
    }

    @Test
    fun `an event records what it was about`() {
        val row = ScheduleInvalidatingEvent.DeadlineChanged(
            date.atTime(9, 0), "goal-1", "Japanese", date, date.plusDays(3),
        ).toEntity("id")
        assertEquals("goal-1", row.subjectId)
    }

    @Test
    fun `a manual edit has no subject`() {
        val row = ScheduleInvalidatingEvent.ManualEdit(date.atTime(9, 0), "gym moved")
            .toEntity("id")
        assertNull(row.subjectId)
    }

    // --- proposals -----------------------------------------------------------

    private fun proposalRow(kind: String? = null, text: String? = null) =
        ReshuffleProposalEntity(
            id = "p1",
            effectiveFrom = date.plusDays(1),
            createdAt = date.atTime(14, 0),
            summary = "Because Reading was missed, here is a different plan.",
            changes = "Reading moves from 09:00 to 14:00\nProblems loses 30m",
            responseKind = kind,
            responseText = text,
        )

    @Test
    fun `an unanswered proposal has no response`() {
        assertNull(proposalRow().responseOrNull())
    }

    @Test
    fun `each response round-trips through its stored kind`() {
        assertEquals(
            ReshuffleResponse.Accept,
            proposalRow(ReshuffleResponse.Accept.kind()).responseOrNull(),
        )
        assertEquals(
            ReshuffleResponse.Decline,
            proposalRow(ReshuffleResponse.Decline.kind()).responseOrNull(),
        )
    }

    @Test
    fun `proposing something different keeps the words verbatim`() {
        val words = "keep mornings free, I think better after lunch"
        val response = ReshuffleResponse.ProposeDifferent(words)
        val restored = proposalRow(response.kind(), response.text()).responseOrNull()
        assertEquals(words, (restored as ReshuffleResponse.ProposeDifferent).text)
    }

    @Test
    fun `only propose-different carries text`() {
        assertNull(ReshuffleResponse.Accept.text())
        assertNull(ReshuffleResponse.Decline.text())
        assertEquals("x", ReshuffleResponse.ProposeDifferent("x").text())
    }

    @Test
    fun `an unrecognised response kind reads as unanswered rather than crashing`() {
        assertNull(proposalRow("SOMETHING_ELSE").responseOrNull())
    }

    @Test
    fun `change lines split back out for display`() {
        val lines = proposalRow().changeLines()
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("moves from"))
    }

    @Test
    fun `a proposal with no changes yields no change lines`() {
        assertEquals(emptyList(), proposalRow().copy(changes = "").changeLines())
    }

    @Test
    fun `an unrelated task row maps to no Phase 4 type`() {
        val goalRow = TaskEntity(
            id = "g",
            title = "Revise",
            type = TaskType.GOAL_TASK,
            consequenceType = ConsequenceType.SOFT_GOAL,
            daysOfWeek = StaticBlock.ALL_DAYS,
        )
        assertNull(goalRow.toProtectedRecoveryOrNull())
    }
}
