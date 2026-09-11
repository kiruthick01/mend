package com.mend.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mend.app.repository.RecoveryRepository
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.recovery.SleepTrend
import com.mend.engine.recovery.computeSleepTrend
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The sleep block, and what the sleep stream says about it. */
class RecoveryViewModel(
    private val repository: RecoveryRepository,
) : ViewModel() {

    val state: StateFlow<RecoveryUiState> =
        combine(
            repository.observeSleepBlock(),
            repository.observeSleepLog(),
        ) { block, log ->
            RecoveryUiState(
                sleepBlock = block,
                sleepTrend = computeSleepTrend(log),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = RecoveryUiState(),
        )

    fun saveSleepBlock(block: ProtectedRecovery) {
        viewModelScope.launch { repository.save(block) }
    }

    fun deleteSleepBlock(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class RecoveryUiState(
    val sleepBlock: ProtectedRecovery? = null,
    val sleepTrend: SleepTrend? = null,
)
