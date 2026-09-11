package com.mend.engine.reshuffle

import com.mend.engine.TEST_DATE
import com.mend.engine.allocation.allocate
import com.mend.engine.at
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.goalTask
import com.mend.engine.model.Flexibility
import com.mend.engine.staticBlock
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReshuffleTest {

    private val tomorrow: LocalDate = TEST_DATE.plusDays(1)
    private val now = TEST_DATE.atTime(14, 30)

    private fun dayOf(
        date: LocalDate = tomorrow,
        tasks: List<com.mend.engine.model.GoalTask> = emptyList(),
        from: Int = 9,
        to: Int = 17,
    ) = allocate(
        computeFreeTimeMap(date = date, dayStart = at(from), dayEnd = at(to)),
        tasks,
        today = date,
    )

    private fun missed(taskId: String = "reading") = ScheduleInvalidatingEvent.TaskMissed(
        occurredAt = now,
        taskId = taskId,
        taskTitle = taskId,
        date = TEST_DATE,
    )

    // --- the tomorrow rule ---------------------------------------------------

    @Test
    fun `a proposal always takes effect tomorrow`() {
        val proposal = buildProposal("p1", now, listOf(missed()), null, dayOf())
        assertEquals(tomorrow, proposal.effectiveFrom)
    }

    @Test
    fun `effectiveDateFor is always the day after`() {
        assertEquals(tomorrow, effectiveDateFor(TEST_DATE))
        assertEquals(LocalDate.of(2027, 1, 1), effectiveDateFor(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `a proposal that would rewrite today is rejected outright`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            buildProposal("p1", now, listOf(missed()), null, dayOf(date = TEST_DATE))
        }
        assertTrue(ex.message!!.contains("retroactively"))
    }

    @Test
    fun `a proposal for a later day is allowed`() {
        val proposal = buildProposal(
            "p1",
            now,
            listOf(missed()),
            null,
            dayOf(date = TEST_DATE.plusDays(5)),
        )
        assertEquals(tomorrow, proposal.effectiveFrom)
    }

    @Test
    fun `a proposal needs at least one trigger`() {
        assertFailsWith<IllegalArgumentException> {
            buildProposal("p1", now, emptyList(), null, dayOf())
        }
    }

    // --- batching, so prompts do not become noise ---------------------------

    @Test
    fun `several events on one day become a single proposal`() {
        val batches = batchEvents(
            listOf(
                missed("reading"),
                ScheduleInvalidatingEvent.ManualEdit(now.plusMinutes(5), "gym moved"),
                ScheduleInvalidatingEvent.DeadlineChanged(
                    now.plusMinutes(20),
                    "g1",
                    "Japanese",
                    TEST_DATE.plusDays(20),
                    TEST_DATE.plusDays(5),
                ),
            ),
        )
        assertEquals(1, batches.size)
        assertEquals(3, batches.single().size)
    }

    @Test
    fun `events on different days become separate proposals`() {
        val batches = batchEvents(
            listOf(
                missed("a"),
                ScheduleInvalidatingEvent.ManualEdit(
                    TEST_DATE.plusDays(1).atTime(8, 0),
                    "added a block",
                ),
            ),
        )
        assertEquals(2, batches.size)
    }

    @Test
    fun `batches come out in chronological order`() {
        val batches = batchEvents(
            listOf(
                ScheduleInvalidatingEvent.ManualEdit(
                    TEST_DATE.plusDays(2).atTime(8, 0),
                    "later",
                ),
                ScheduleInvalidatingEvent.ManualEdit(TEST_DATE.atTime(8, 0), "earlier"),
            ),
        )
        assertEquals(
            listOf(TEST_DATE, TEST_DATE.plusDays(2)),
            batches.map { it.first().occurredAt.toLocalDate() },
        )
    }

    @Test
    fun `no events produce no batches`() {
        assertEquals(emptyList(), batchEvents(emptyList()))
    }

    @Test
    fun `a batched proposal keeps every trigger that caused it`() {
        val triggers = listOf(
            missed("reading"),
            ScheduleInvalidatingEvent.ManualEdit(now, "gym window widened"),
        )
        val proposal = buildProposal("p1", now, triggers, null, dayOf())
        assertEquals(2, proposal.triggers.size)
        assertTrue(proposal.summarise().contains("reading was missed"))
        assertTrue(proposal.summarise().contains("gym window widened"))
    }

    // --- event descriptions --------------------------------------------------

    @Test
    fun `a deadline moving earlier reads differently from one moving later`() {
        val earlier = ScheduleInvalidatingEvent.DeadlineChanged(
            now, "g", "Japanese", TEST_DATE.plusDays(20), TEST_DATE.plusDays(5),
        )
        val later = ScheduleInvalidatingEvent.DeadlineChanged(
            now, "g", "Japanese", TEST_DATE.plusDays(5), TEST_DATE.plusDays(20),
        )
        assertTrue(earlier.describe().contains("moved earlier"))
        assertTrue(later.describe().contains("moved later"))
    }

    @Test
    fun `a new deadline describes itself`() {
        val event = ScheduleInvalidatingEvent.DeadlineAdded(
            now, "g", "Japanese", TEST_DATE.plusDays(9),
        )
        assertTrue(event.describe().contains("now has a deadline"))
    }

    // --- diffing -------------------------------------------------------------

    @Test
    fun `with no previous schedule everything reads as added`() {
        val proposed = dayOf(
            tasks = listOf(goalTask("a", deadlineIn = 2, minutes = 60, date = tomorrow)),
        )
        val changes = diffSchedules(null, proposed)
        assertEquals(1, changes.size)
        assertTrue(changes.single() is ScheduleChange.Added)
    }

    @Test
    fun `an unchanged schedule produces no changes at all`() {
        val tasks = listOf(goalTask("a", deadlineIn = 2, minutes = 60, date = tomorrow))
        assertEquals(emptyList(), diffSchedules(dayOf(tasks = tasks), dayOf(tasks = tasks)))
    }

    @Test
    fun `a no-op proposal says so rather than pretending to change something`() {
        val tasks = listOf(goalTask("a", deadlineIn = 2, minutes = 60, date = tomorrow))
        val proposal = buildProposal(
            "p1", now, listOf(missed()), dayOf(tasks = tasks), dayOf(tasks = tasks),
        )
        assertTrue(proposal.isNoOp)
        assertTrue(proposal.summarise().contains("nothing needs to move"))
    }

    @Test
    fun `work that moves is reported as moved`() {
        val before = dayOf(
            tasks = listOf(
                goalTask("a", deadlineIn = 2, minutes = 60, date = tomorrow),
                goalTask("b", deadlineIn = 3, minutes = 60, date = tomorrow),
            ),
        )
        // Making b the more urgent one swaps their order.
        val after = dayOf(
            tasks = listOf(
                goalTask("a", deadlineIn = 5, minutes = 60, date = tomorrow),
                goalTask("b", deadlineIn = 0, minutes = 60, date = tomorrow),
            ),
        )
        val moves = diffSchedules(before, after).filterIsInstance<ScheduleChange.Moved>()
        assertEquals(setOf("a", "b"), moves.map { it.taskId }.toSet())
        assertTrue(moves.first().describe().contains("moves from"))
    }

    @Test
    fun `work that no longer fits is reported as removed, with the reason`() {
        val before = dayOf(
            tasks = listOf(goalTask("a", deadlineIn = 2, minutes = 60, date = tomorrow)),
        )
        val after = allocate(
            computeFreeTimeMap(
                date = tomorrow,
                staticBlocks = listOf(staticBlock("all-day", from = at(9), to = at(17))),
                dayStart = at(9),
                dayEnd = at(17),
            ),
            listOf(goalTask("a", deadlineIn = 2, minutes = 60, date = tomorrow)),
            today = tomorrow,
        )
        val removed = diffSchedules(before, after).filterIsInstance<ScheduleChange.Removed>()
        assertEquals("a", removed.single().taskId)
        assertTrue(removed.single().reason.isNotBlank())
        assertTrue(removed.single().describe().contains("no longer fits"))
    }

    @Test
    fun `work that gets more or less time is reported as resized`() {
        val before = dayOf(
            tasks = listOf(
                goalTask(
                    "a",
                    deadlineIn = 2,
                    minutes = 600,
                    flexibility = Flexibility.SPLITTABLE,
                    date = tomorrow,
                ),
            ),
            from = 9,
            to = 12,
        )
        val after = dayOf(
            tasks = listOf(
                goalTask(
                    "a",
                    deadlineIn = 2,
                    minutes = 600,
                    flexibility = Flexibility.SPLITTABLE,
                    date = tomorrow,
                ),
            ),
            from = 9,
            to = 17,
        )
        val resized = diffSchedules(before, after).filterIsInstance<ScheduleChange.Resized>()
        assertEquals(180, resized.single().fromMinutes)
        assertEquals(480, resized.single().toMinutes)
        assertTrue(resized.single().describe().contains("more"))
    }

    @Test
    fun `changes are listed in a stable order`() {
        val before = dayOf(
            tasks = listOf(
                goalTask("zebra", deadlineIn = 2, minutes = 60, date = tomorrow),
                goalTask("apple", deadlineIn = 3, minutes = 60, date = tomorrow),
            ),
        )
        val after = dayOf(
            tasks = listOf(
                goalTask("zebra", deadlineIn = 5, minutes = 60, date = tomorrow),
                goalTask("apple", deadlineIn = 0, minutes = 60, date = tomorrow),
            ),
        )
        val titles = diffSchedules(before, after).map { it.taskTitle }
        assertEquals(titles.sorted(), titles)
    }

    // --- the three responses -------------------------------------------------

    @Test
    fun `a fresh proposal is unanswered`() {
        val proposal = buildProposal("p1", now, listOf(missed()), null, dayOf())
        assertFalse(proposal.isAnswered)
    }

    @Test
    fun `accepting records the response without the engine applying anything`() {
        val proposal = buildProposal("p1", now, listOf(missed()), null, dayOf())
            .copy(response = ReshuffleResponse.Accept)
        assertTrue(proposal.isAnswered)
        assertEquals(ReshuffleResponse.Accept, proposal.response)
    }

    @Test
    fun `declining is a first-class answer, not an absence of one`() {
        val proposal = buildProposal("p1", now, listOf(missed()), null, dayOf())
            .copy(response = ReshuffleResponse.Decline)
        assertTrue(proposal.isAnswered)
    }

    @Test
    fun `proposing something different keeps the words verbatim`() {
        val text = "keep mornings free, I think better after lunch"
        val proposal = buildProposal("p1", now, listOf(missed()), null, dayOf())
            .copy(response = ReshuffleResponse.ProposeDifferent(text))
        val response = proposal.response as ReshuffleResponse.ProposeDifferent
        assertEquals(text, response.text)
    }
}
