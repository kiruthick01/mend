package com.mend.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mend.app.repository.StaticLayerRepository
import com.mend.engine.freetime.FreeTimeMap
import com.mend.engine.freetime.computeFreeTimeMap
import com.mend.engine.model.Habit
import com.mend.engine.model.StaticBlock
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The static layer of the day: the fixed blocks and habits the user has
 * entered, and the free time map the engine derives from them.
 *
 * The ViewModel does no scheduling arithmetic of its own. It collects the two
 * lists, hands them to [computeFreeTimeMap], and exposes the result. Any bug
 * in the shape of the day is therefore a bug in the engine, where it is unit
 * tested, rather than in an Android class that is not.
 */
class StaticLayerViewModel(
    private val repository: StaticLayerRepository,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    val state: StateFlow<StaticLayerUiState> =
        combine(
            repository.observeStaticBlocks(),
            repository.observeHabits(),
        ) { blocks, habits ->
            val date = today()
            StaticLayerUiState(
                date = date,
                staticBlocks = blocks,
                habits = habits,
                freeTimeMap = computeFreeTimeMap(
                    date = date,
                    staticBlocks = blocks,
                    habits = habits,
                ),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = StaticLayerUiState(date = today()),
        )

    fun saveStaticBlock(block: StaticBlock) {
        viewModelScope.launch { repository.save(block) }
    }

    fun saveHabit(habit: Habit) {
        viewModelScope.launch { repository.save(habit) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class StaticLayerUiState(
    val date: LocalDate,
    val staticBlocks: List<StaticBlock> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val freeTimeMap: FreeTimeMap? = null,
) {
    val isEmpty: Boolean get() = staticBlocks.isEmpty() && habits.isEmpty()
}
