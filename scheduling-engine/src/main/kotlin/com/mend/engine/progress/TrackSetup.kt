package com.mend.engine.progress

import com.mend.engine.model.Flexibility
import com.mend.engine.model.Lesson
import com.mend.engine.model.SubTask
import java.time.Duration
import kotlin.math.roundToLong

/**
 * Setting up a goal that has several parallel strands of work.
 *
 * Some goals are a sequence of chapters. Others are four different kinds of
 * work running alongside each other in different volumes — thirty grammar
 * points, forty vocabulary sets, some reading, some listening — where the
 * counts do not line up and pairing them into shared units would be a lie.
 *
 * A track becomes one lesson holding its own sub-tasks, so the pace view (which
 * already groups by sub-task type) reports each strand separately: "reading:
 * 18m/day against your 30m target".
 *
 * This is a **generator, not a template**. What it produces is ordinary
 * lessons and sub-tasks with no link back here, so every one can be renamed,
 * re-estimated or deleted afterwards. PROJECT_PLAN.md Phase 2 rules out a
 * template system that imposes one structure across a goal; nothing here
 * persists to impose anything.
 */

/** One strand of work: how much of it, and roughly how long in total. */
data class TrackSpec(
    /** What the user calls it — "grammar", "vocab", "past papers". */
    val name: String,
    /** Roughly how many pieces of work. */
    val count: Int,
    /** Roughly how long the whole strand takes. */
    val totalTime: Duration,
    val flexibility: Flexibility = Flexibility.SPLITTABLE,
    /** Optional comfortable pace for this kind of work, in minutes per day. */
    val comfortablePerDay: Duration? = null,
) {
    init {
        require(name.isNotBlank()) { "a track needs a name" }
        require(count > 0) { "track '$name': count must be positive" }
        require(!totalTime.isNegative && !totalTime.isZero) {
            "track '$name': total time must be positive"
        }
    }

    /**
     * How long one piece of this work takes.
     *
     * Rounded to the nearest minute and floored at one, so a large count and a
     * small total never produce zero-length work the allocator would refuse to
     * place.
     */
    val perItem: Duration
        get() = Duration.ofMinutes(
            (totalTime.toMinutes().toDouble() / count).roundToLong().coerceAtLeast(1),
        )
}

/** How many sub-tasks a single setup may create, to catch a typo before it lands. */
const val MAX_GENERATED_SUBTASKS: Int = 500

/**
 * Turns [tracks] into lessons and sub-tasks for [goalId].
 *
 * [idFor] supplies identifiers so this stays pure and its output is
 * deterministic in tests; the app passes a UUID generator.
 */
fun generateTracks(
    goalId: String,
    tracks: List<TrackSpec>,
    idFor: (String) -> String,
): List<Lesson> {
    require(tracks.isNotEmpty()) { "at least one track is needed" }

    val total = tracks.sumOf { it.count }
    require(total <= MAX_GENERATED_SUBTASKS) {
        "$total pieces of work is more than the $MAX_GENERATED_SUBTASKS this can " +
            "create at once; split it into separate goals or raise the counts later"
    }

    return tracks.mapIndexed { index, track ->
        val lessonId = idFor("lesson-$goalId-${track.name}-$index")

        Lesson(
            id = lessonId,
            goalId = goalId,
            orderIndex = index,
            title = track.name.replaceFirstChar { it.uppercase() },
            subTasks = (1..track.count).map { n ->
                SubTask(
                    id = idFor("sub-$lessonId-$n"),
                    lessonId = lessonId,
                    // The type is what the pace view groups by, so it is the
                    // track name rather than the individual title.
                    type = track.name.lowercase(),
                    title = "${track.name.replaceFirstChar { c -> c.uppercase() }} $n",
                    estimatedDuration = track.perItem,
                    paceComfortable = track.comfortablePerDay,
                    flexibility = track.flexibility,
                )
            },
        )
    }
}

/** What a setup would produce, for showing before it is committed to. */
data class TrackPreview(
    val tracks: List<TrackSpec>,
) {
    val totalItems: Int get() = tracks.sumOf { it.count }

    val totalTime: Duration
        get() = tracks.fold(Duration.ZERO) { acc, track -> acc.plus(track.totalTime) }

    /** True when this would exceed what one setup may create. */
    val isTooLarge: Boolean get() = totalItems > MAX_GENERATED_SUBTASKS

    /** One line per track: "Grammar — 30 × 40m (20h)". */
    fun lines(): List<String> = tracks.map { track ->
        "${track.name.replaceFirstChar { it.uppercase() }} — " +
            "${track.count} × ${track.perItem.toMinutes()}m " +
            "(${formatHours(track.totalTime)})"
    }

    fun summary(): String =
        "$totalItems pieces of work, ${formatHours(totalTime)} in total"
}

private fun formatHours(duration: Duration): String {
    val hours = duration.toMinutes() / 60
    val minutes = duration.toMinutes() % 60
    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}
