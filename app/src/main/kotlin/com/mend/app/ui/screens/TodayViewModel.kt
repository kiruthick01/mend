package com.mend.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mend.app.repository.GoalRepository
import com.mend.app.repository.ScheduleBuilder
import com.mend.app.repository.StaticLayerRepository
import com.mend.engine.allocation.DaySchedule
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * The whole of today: fixed blocks, habits, and the goal work the engine
 * placed into what was left.
 *
 * Every recompute dumps the engine's decision log to logcat under [LOG_TAG].
 * Phase 3 has no user-facing explanation yet, so this is the only way to
 * answer "why is that there?" — and it needs to be answerable now, not once
 * the schedule has already surprised someone.
 */
class TodayViewModel(
    private val staticLayer: StaticLayerRepository,
    private val goals: GoalRepository,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    val state: StateFlow<TodayUiState> =
        combine(
            staticLayer.observeStaticBlocks(),
            staticLayer.observeHabits(),
            goals.observeGoals(),
        ) { blocks, habits, goalList ->
            val date = today()
            TodayUiState(
                date = date,
                schedule = ScheduleBuilder.buildDaySchedule(
                    date = date,
                    staticBlocks = blocks,
                    habits = habits,
                    goals = goalList,
                ),
                hasAnyInput = blocks.isNotEmpty() || habits.isNotEmpty() || goalList.isNotEmpty(),
            )
        }
            .onEach { it.schedule?.let(::dumpDecisionLog) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = TodayUiState(date = today()),
            )

    private fun dumpDecisionLog(schedule: DaySchedule) {
        Log.d(LOG_TAG, "--- schedule for ${schedule.date} ---")
        schedule.log.entries.forEach { Log.d(LOG_TAG, it.render()) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val LOG_TAG = "MendScheduler"
    }
}

data class TodayUiState(
    val date: LocalDate,
    val schedule: DaySchedule? = null,
    val hasAnyInput: Boolean = false,
)
