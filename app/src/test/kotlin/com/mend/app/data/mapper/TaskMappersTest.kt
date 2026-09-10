package com.mend.app.data.mapper

import com.mend.app.data.entity.TaskEntity
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Habit
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.TaskType
import com.mend.engine.model.WindowAnchor
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The flat `tasks` table has to survive a round trip back into the engine's
 * sealed types without losing or inventing anything. These tests guard that
 * boundary, since it is the one place nullable columns are handled by hand.
 */
class TaskMappersTest {

    private val college = StaticBlock(
        id = "college",
        title = "College",
        daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        windowStart = LocalTime.of(9, 0),
        windowEnd = LocalTime.of(16, 0),
    )

    private val gym = StaticBlock(
        id = "gym",
        title = "Gym",
        windowStart = LocalTime.of(6, 0),
        windowEnd = LocalTime.of(8, 0),
        requiredDuration = Duration.ofMinutes(45),
        anchor = WindowAnchor.LATEST,
    )

    private val brush = Habit(
        id = "brush",
        title = "Brush teeth",
        timeOfDay = LocalTime.of(7, 15),
        duration = Duration.ofMinutes(3),
    )

    @Test
    fun `a full-window static block round-trips unchanged`() {
        assertEquals(college, college.toEntity().toStaticBlockOrNull())
    }

    @Test
    fun `a partial-window static block round-trips with its anchor intact`() {
        val restored = gym.toEntity().toStaticBlockOrNull()
        assertEquals(gym, restored)
        assertEquals(WindowAnchor.LATEST, restored?.anchor)
        assertEquals(Duration.ofMinutes(45), restored?.requiredDuration)
    }

    @Test
    fun `a habit round-trips unchanged`() {
        assertEquals(brush, brush.toEntity().toHabitOrNull())
    }

    @Test
    fun `static blocks and habits are stored with no consequence type`() {
        assertEquals(ConsequenceType.NONE, college.toEntity().consequenceType)
        assertEquals(ConsequenceType.NONE, brush.toEntity().consequenceType)
    }

    @Test
    fun `a habit row is never read back as a static block`() {
        assertNull(brush.toEntity().toStaticBlockOrNull())
    }

    @Test
    fun `a static block row is never read back as a habit`() {
        assertNull(college.toEntity().toHabitOrNull())
    }

    @Test
    fun `a static block row missing its window is rejected rather than guessed`() {
        val broken = college.toEntity().copy(windowEnd = null)
        assertNull(broken.toStaticBlockOrNull())
    }

    @Test
    fun `a static block row with an inverted window is rejected`() {
        val broken = college.toEntity().copy(windowEnd = LocalTime.of(8, 0))
        assertNull(broken.toStaticBlockOrNull())
    }

    @Test
    fun `a habit row with a non-positive duration is rejected`() {
        assertNull(brush.toEntity().copy(durationMinutes = 0).toHabitOrNull())
    }

    @Test
    fun `a static block row with no explicit anchor defaults to EARLIEST`() {
        val legacy = gym.toEntity().copy(windowAnchor = null)
        assertEquals(WindowAnchor.EARLIEST, legacy.toStaticBlockOrNull()?.anchor)
    }

    @Test
    fun `list mappers keep only the rows of the matching type`() {
        val rows = listOf(college.toEntity(), gym.toEntity(), brush.toEntity())
        assertEquals(listOf("college", "gym"), rows.toStaticBlocks().map { it.id })
        assertEquals(listOf("brush"), rows.toHabits().map { it.id })
    }

    @Test
    fun `an unrelated task type maps to neither`() {
        val goalRow = TaskEntity(
            id = "goal-task",
            title = "Revise chapter 3",
            type = TaskType.GOAL_TASK,
            consequenceType = ConsequenceType.SOFT_GOAL,
            daysOfWeek = StaticBlock.ALL_DAYS,
        )
        assertNull(goalRow.toStaticBlockOrNull())
        assertNull(goalRow.toHabitOrNull())
    }
}
