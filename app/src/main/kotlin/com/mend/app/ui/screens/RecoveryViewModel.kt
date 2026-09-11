package com.mend.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mend.app.repository.RecoveryRepository
import com.mend.app.repository.WeightsRepository
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.Weights
import com.mend.engine.recovery.SleepTrend
import com.mend.engine.weights.WeightAdjustment
import com.mend.engine.weights.WeightKind
import com.mend.engine.recovery.computeSleepTrend
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The sleep block, and what the sleep stream says about it. */
class RecoveryViewModel(
    private val repository: RecoveryRepository,
    private val weights: WeightsRepository,
) : ViewModel() {

    val state: StateFlow<RecoveryUiState> =
        combine(
            repository.observeSleepBlock(),
            repository.observeSleepLog(),
            weights.observeWeights(),
            weights.observeHistory(),
        ) { block, log, currentWeights, history ->
            RecoveryUiState(
                sleepBlock = block,
                sleepTrend = computeSleepTrend(log),
                weights = currentWeights,
                adjustments = history,
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

    /** Sets one weight by hand. Recorded in the same history as automatic changes. */
    fun overrideWeight(kind: WeightKind, value: Double) {
        viewModelScope.launch { weights.overrideWeight(kind, value) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class RecoveryUiState(
    val sleepBlock: ProtectedRecovery? = null,
    val sleepTrend: SleepTrend? = null,
    val weights: Weights = Weights.DEFAULT,
    val adjustments: List<WeightAdjustment> = emptyList(),
)
