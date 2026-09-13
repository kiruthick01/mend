package com.mend.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mend.app.repository.GoalRepository
import com.mend.app.repository.GoalWithLessons
import com.mend.engine.model.Goal
import com.mend.engine.model.Lesson
import com.mend.engine.model.SubTask
import com.mend.engine.progress.GoalProgress
import com.mend.engine.progress.PaceSnapshot
import com.mend.engine.progress.computeGoalProgress
import com.mend.engine.progress.TrackSpec
import com.mend.engine.progress.computePace
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Goals, their lesson hierarchies, and the derived progress and pace views.
 *
 * As with the static layer, every number shown here is computed by the engine
 * from the stored rows. The ViewModel holds no counters of its own, so there
 * is nothing here that can drift out of step with the data.
 */
class GoalsViewModel(
    private val repository: GoalRepository,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    val state: StateFlow<GoalsUiState> =
        repository.observeGoals()
            .map { goals ->
                val date = today()
                GoalsUiState(
                    goals = goals.map { it.toItem(date) },
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = GoalsUiState(),
            )

    fun saveGoal(goal: Goal) {
        viewModelScope.launch { repository.saveGoal(goal) }
    }

    /** Creates a goal together with its whole track breakdown. */
    fun createWithTracks(goal: Goal, tracks: List<TrackSpec>) {
        viewModelScope.launch { repository.createTracks(goal, tracks) }
    }

    fun deleteGoal(goal: Goal) {
        viewModelScope.launch { repository.deleteGoal(goal) }
    }

    fun saveLesson(lesson: Lesson) {
        viewModelScope.launch { repository.saveLesson(lesson) }
    }

    fun deleteLesson(lesson: Lesson) {
        viewModelScope.launch { repository.deleteLesson(lesson) }
    }

    fun saveSubTask(subTask: SubTask, orderIndex: Int) {
        viewModelScope.launch { repository.saveSubTask(subTask, orderIndex) }
    }

    fun deleteSubTask(subTask: SubTask) {
        viewModelScope.launch { repository.deleteSubTask(subTask) }
    }

    fun setSubTaskDone(subTask: SubTask, done: Boolean, orderIndex: Int? = null) {
        viewModelScope.launch { repository.setSubTaskDone(subTask, done, orderIndex) }
    }

    fun setLessonDone(lesson: Lesson, done: Boolean) {
        viewModelScope.launch { repository.setLessonDone(lesson, done) }
    }

    private fun GoalWithLessons.toItem(date: LocalDate) = GoalItem(
        goal = goal,
        lessons = lessons,
        progress = computeGoalProgress(goal, lessons, date),
        pace = computePace(lessons, date),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class GoalItem(
    val goal: Goal,
    val lessons: List<Lesson>,
    val progress: GoalProgress,
    val pace: List<PaceSnapshot>,
)

data class GoalsUiState(
    val goals: List<GoalItem> = emptyList(),
) {
    val isEmpty: Boolean get() = goals.isEmpty()
}
