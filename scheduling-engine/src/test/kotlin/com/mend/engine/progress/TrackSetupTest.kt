package com.mend.engine.progress

import com.mend.engine.model.Flexibility
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackSetupTest {

    private var counter = 0
    private fun ids(seed: String): String = "${seed}-${counter++}"

    private fun japanese() = listOf(
        TrackSpec("grammar", count = 30, totalTime = Duration.ofHours(20)),
        TrackSpec("vocab", count = 40, totalTime = Duration.ofHours(10)),
        TrackSpec("reading", count = 15, totalTime = Duration.ofHours(8)),
        TrackSpec("listening", count = 15, totalTime = Duration.ofHours(7)),
    )

    // --- per-item arithmetic -------------------------------------------------

    @Test
    fun `per-item duration is the total divided by the count`() {
        assertEquals(
            Duration.ofMinutes(40),
            TrackSpec("grammar", 30, Duration.ofHours(20)).perItem,
        )
        assertEquals(
            Duration.ofMinutes(15),
            TrackSpec("vocab", 40, Duration.ofHours(10)).perItem,
        )
    }

    @Test
    fun `an uneven division rounds to the nearest minute`() {
        // 7 hours over 15 items is 28 minutes each.
        assertEquals(
            Duration.ofMinutes(28),
            TrackSpec("listening", 15, Duration.ofHours(7)).perItem,
        )
    }

    @Test
    fun `a huge count never produces zero-length work`() {
        // One hour across 500 items rounds to zero; floored at a minute so the
        // allocator has something it can actually place.
        assertEquals(
            Duration.ofMinutes(1),
            TrackSpec("flashcards", 500, Duration.ofHours(1)).perItem,
        )
    }

    @Test
    fun `nonsense specs are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { TrackSpec("", 5, Duration.ofHours(1)) }
        assertFailsWith<IllegalArgumentException> { TrackSpec("x", 0, Duration.ofHours(1)) }
        assertFailsWith<IllegalArgumentException> { TrackSpec("x", 5, Duration.ZERO) }
    }

    // --- what it generates ---------------------------------------------------

    @Test
    fun `each track becomes one lesson`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        assertEquals(4, lessons.size)
        assertEquals(
            listOf("Grammar", "Vocab", "Reading", "Listening"),
            lessons.map { it.title },
        )
    }

    @Test
    fun `each lesson holds its own count of sub-tasks`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        assertEquals(listOf(30, 40, 15, 15), lessons.map { it.subTasks.size })
    }

    @Test
    fun `sub-task type is the track name, so pace groups by strand`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        assertEquals(
            listOf("grammar", "vocab", "reading", "listening"),
            lessons.map { it.subTasks.first().type },
        )
        assertTrue(lessons.all { lesson -> lesson.subTasks.all { it.type == lesson.subTasks.first().type } })
    }

    @Test
    fun `sub-tasks are numbered readably`() {
        val grammar = generateTracks("g1", japanese(), ::ids).first()
        assertEquals("Grammar 1", grammar.subTasks.first().title)
        assertEquals("Grammar 30", grammar.subTasks.last().title)
    }

    @Test
    fun `every sub-task carries the track's per-item estimate`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        assertTrue(lessons[0].subTasks.all { it.estimatedDuration == Duration.ofMinutes(40) })
        assertTrue(lessons[1].subTasks.all { it.estimatedDuration == Duration.ofMinutes(15) })
    }

    @Test
    fun `lessons are ordered as the tracks were given`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        assertEquals(listOf(0, 1, 2, 3), lessons.map { it.orderIndex })
    }

    @Test
    fun `sub-tasks belong to their own lesson and the lessons to the goal`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        assertTrue(lessons.all { it.goalId == "g1" })
        assertTrue(lessons.all { lesson -> lesson.subTasks.all { it.lessonId == lesson.id } })
    }

    @Test
    fun `every generated id is distinct`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        val all = lessons.map { it.id } + lessons.flatMap { l -> l.subTasks.map { it.id } }
        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun `a per-track comfortable pace is carried onto its sub-tasks`() {
        val lessons = generateTracks(
            "g1",
            listOf(
                TrackSpec(
                    "reading",
                    count = 10,
                    totalTime = Duration.ofHours(5),
                    comfortablePerDay = Duration.ofMinutes(30),
                ),
            ),
            ::ids,
        )
        assertTrue(lessons.single().subTasks.all { it.paceComfortable == Duration.ofMinutes(30) })
    }

    @Test
    fun `flexibility is per track`() {
        val lessons = generateTracks(
            "g1",
            listOf(
                TrackSpec("mock exam", 3, Duration.ofHours(6), flexibility = Flexibility.FIXED),
                TrackSpec("vocab", 20, Duration.ofHours(5)),
            ),
            ::ids,
        )
        assertTrue(lessons[0].subTasks.all { it.flexibility == Flexibility.FIXED })
        assertTrue(lessons[1].subTasks.all { it.flexibility == Flexibility.SPLITTABLE })
    }

    @Test
    fun `nothing is generated from an empty setup`() {
        assertFailsWith<IllegalArgumentException> { generateTracks("g1", emptyList(), ::ids) }
    }

    @Test
    fun `an implausibly large setup is refused rather than creating it`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            generateTracks("g1", listOf(TrackSpec("vocab", 5000, Duration.ofHours(50))), ::ids)
        }
        assertTrue(ex.message!!.contains("split it into separate goals"))
    }

    // --- what it generates is ordinary, not a template ----------------------

    @Test
    fun `generated lessons are independent and freely editable afterwards`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        // Renaming one sub-task, re-estimating another and deleting a third
        // leaves everything else untouched: there is no template to propagate.
        val edited = lessons[0].let { grammar ->
            grammar.copy(
                subTasks = grammar.subTasks
                    .drop(1)
                    .mapIndexed { i, sub ->
                        if (i == 0) sub.copy(title = "Renamed", estimatedDuration = Duration.ofHours(2))
                        else sub
                    },
            )
        }
        assertEquals(29, edited.subTasks.size)
        assertEquals("Renamed", edited.subTasks.first().title)
        assertEquals(40, lessons[1].subTasks.size, "the other tracks are unaffected")
    }

    // --- the preview ---------------------------------------------------------

    @Test
    fun `the preview totals the work before anything is created`() {
        val preview = TrackPreview(japanese())
        assertEquals(100, preview.totalItems)
        assertEquals(Duration.ofHours(45), preview.totalTime)
        assertEquals("100 pieces of work, 45h in total", preview.summary())
        assertFalse(preview.isTooLarge)
    }

    @Test
    fun `the preview shows the arithmetic per track`() {
        val lines = TrackPreview(japanese()).lines()
        assertEquals("Grammar — 30 × 40m (20h)", lines[0])
        assertEquals("Vocab — 40 × 15m (10h)", lines[1])
    }

    @Test
    fun `the preview flags a setup that is too large`() {
        assertTrue(TrackPreview(listOf(TrackSpec("x", 900, Duration.ofHours(90)))).isTooLarge)
    }

    // --- it rolls up the way the goal screen expects -------------------------

    @Test
    fun `generated work rolls up through the ordinary progress functions`() {
        val lessons = generateTracks("g1", japanese(), ::ids)
        assertEquals(Duration.ofHours(45), remainingEffort(lessons))
        assertTrue(lessons.none { it.isComplete() })
        assertEquals(100, lessons.sumOf { it.subTasks.size })
    }
}
