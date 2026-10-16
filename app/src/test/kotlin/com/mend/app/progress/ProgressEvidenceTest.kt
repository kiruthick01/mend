package com.mend.app.progress

import com.mend.engine.model.Lesson
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.SubTask
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressEvidenceTest {

    private val today = LocalDate.of(2026, 10, 16)

    private fun profile(end: String) =
        """{"users":[{"streakData":{"currentStreak":{"length":3,"startDate":"2026-10-14","endDate":"$end"}}}]}"""

    @Test fun `duolingo streak ending today counts`() =
        assertEquals(true, ProgressEvidence.duolingoPracticedOn(profile("2026-10-16"), today))

    @Test fun `duolingo streak ending yesterday does not`() =
        assertEquals(false, ProgressEvidence.duolingoPracticedOn(profile("2026-10-15"), today))

    @Test fun `duolingo user with no streak is false`() =
        assertEquals(false, ProgressEvidence.duolingoPracticedOn("""{"users":[{}]}""", today))

    @Test fun `unreadable duolingo payload is unknown not missed`() {
        assertNull(ProgressEvidence.duolingoPracticedOn("{}", today))
        assertNull(ProgressEvidence.duolingoPracticedOn("not json", today))
    }

    @Test fun `anki finished only after cards were due`() {
        assertTrue(ProgressEvidence.ankiFinished(baselineDue = 40, dueNow = 0))
        assertFalse(ProgressEvidence.ankiFinished(baselineDue = 40, dueNow = 3))
        assertFalse(ProgressEvidence.ankiFinished(baselineDue = 0, dueNow = 0))
    }

    @Test fun `baseline never shrinks within a day`() {
        assertEquals(40, ProgressEvidence.nextBaseline(40, 12))
        assertEquals(50, ProgressEvidence.nextBaseline(40, 50))
    }

    @Test fun `anki due sums learning and review but not new`() {
        assertEquals(15, ProgressEvidence.ankiDue("[5,10,20]"))
        assertNull(ProgressEvidence.ankiDue("oops"))
    }

    @Test fun `usage threshold`() {
        assertFalse(ProgressEvidence.usageCounts(9))
        assertTrue(ProgressEvidence.usageCounts(10))
    }

    @Test fun `next pending work follows lesson order and skips done items`() {
        fun sub(id: String, lesson: String, done: Boolean) = SubTask(
            id, lesson, "t", id, Duration.ofMinutes(15),
            status = if (done) ProgressStatus.DONE else ProgressStatus.NOT_STARTED,
        )
        val l2 = Lesson("l2", "g", 1, "two", subTasks = listOf(sub("s3", "l2", false)))
        val l1 = Lesson("l1", "g", 0, "one", subTasks = listOf(sub("s1", "l1", true), sub("s2", "l1", false)))
        val bare = Lesson("l3", "g", 2, "three")

        val work = nextPendingWork(listOf(l2, bare, l1))
        assertEquals("s2", (work as com.mend.app.repository.PendingWork.Sub).subTask.id)
        assertNull(nextPendingWork(listOf(l1.copy(subTasks = listOf(sub("s1", "l1", true))), bare.copy(status = ProgressStatus.DONE))))
    }

    @Test fun `links survive encode and decode`() {
        val links = listOf(
            ProgressLink("g1", ProgressKind.ANKI, "Japanese::N5", today),
            ProgressLink("g2", ProgressKind.DUOLINGO),
        )
        assertEquals(links, ProgressLinkStore.decode(ProgressLinkStore.encode(links)))
    }
}
