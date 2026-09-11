package com.mend.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mend.app.data.mapper.changeLines
import com.mend.app.repository.GoalRepository
import com.mend.app.repository.RecoveryRepository
import com.mend.app.repository.ReshuffleRepository
import com.mend.app.repository.ScheduleBuilder
import com.mend.app.repository.detectInterrupt
import com.mend.app.repository.StaticLayerRepository
import com.mend.engine.allocation.DaySchedule
import com.mend.engine.interrupt.InterruptPlan
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.recovery.SleepStopDecision
import com.mend.engine.recovery.evaluateSleepStop
import com.mend.engine.reshuffle.ReshuffleResponse
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The whole of today: the fixed grid, the goal work packed into what is left,
 * the bedtime boundary, and any outstanding negotiation.
 *
 * Every recompute dumps the engine's decision log to logcat under [LOG_TAG].
 * There is still no user-facing explanation, so this remains the only way to
 * answer "why is that there?" — and it needs to be answerable now, not once
 * the schedule has already surprised someone.
 */
class TodayViewModel(
    private val staticLayer: StaticLayerRepository,
    private val goals: GoalRepository,
    private val recovery: RecoveryRepository,
    private val reshuffle: ReshuffleRepository,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    val state: StateFlow<TodayUiState> =
        combine(
            staticLayer.observeStaticBlocks(),
            staticLayer.observeHabits(),
            goals.observeGoals(),
            recovery.observeSleepBlock(),
            reshuffle.observeOutstanding(),
        ) { blocks, habits, goalList, sleepBlock, proposal ->
            val instant = now()
            val date = instant.toLocalDate()
            val schedule = ScheduleBuilder.buildDaySchedule(
                date = date,
                staticBlocks = blocks,
                habits = habits,
                goals = goalList,
                protectedRecovery = listOfNotNull(sleepBlock),
            )

            TodayUiState(
                date = date,
                schedule = schedule,
                sleepBlock = sleepBlock,
                sleepStop = evaluateSleepStop(instant, schedule, sleepBlock),
                interrupt = detectInterrupt(
                    now = instant,
                    schedule = schedule,
                    goals = goalList,
                    staticBlocks = blocks,
                    habits = habits,
                    protectedRecovery = listOfNotNull(sleepBlock),
                ),
                proposal = proposal?.let {
                    ProposalUiState(
                        id = it.id,
                        effectiveFrom = it.effectiveFrom,
                        summary = it.summary,
                        changes = it.changeLines(),
                    )
                },
                hasAnyInput = blocks.isNotEmpty() || habits.isNotEmpty() ||
                    goalList.isNotEmpty() || sleepBlock != null,
            )
        }
            .onEach { it.schedule?.let(::dumpDecisionLog) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = TodayUiState(date = now().toLocalDate()),
            )

    init {
        watchForReshuffleTriggers()
    }

    /**
     * Builds a proposal whenever something has invalidated the plan.
     *
     * Batching lives in the repository, so a morning of edits produces one
     * proposal rather than six, and nothing new is asked while an earlier
     * question is still unanswered.
     *
     * The proposal is always for tomorrow. Today is never rewritten.
     */
    private fun watchForReshuffleTriggers() {
        viewModelScope.launch {
            combine(
                reshuffle.observePendingEvents(),
                staticLayer.observeStaticBlocks(),
                staticLayer.observeHabits(),
                goals.observeGoals(),
                recovery.observeSleepBlock(),
            ) { pending, blocks, habits, goalList, sleepBlock ->
                ReshuffleInputs(pending.isNotEmpty(), blocks, habits, goalList, sleepBlock)
            }.collect { inputs ->
                if (!inputs.hasPendingEvents) return@collect

                val instant = now()
                val tomorrow = reshuffle.earliestEffectiveDate(instant)
                reshuffle.proposeIfNeeded(
                    now = instant,
                    proposedSchedule = ScheduleBuilder.buildDaySchedule(
                        date = tomorrow,
                        staticBlocks = inputs.blocks,
                        habits = inputs.habits,
                        goals = inputs.goals,
                        protectedRecovery = listOfNotNull(inputs.sleepBlock),
                    ),
                )
            }
        }
    }

    private data class ReshuffleInputs(
        val hasPendingEvents: Boolean,
        val blocks: List<com.mend.engine.model.StaticBlock>,
        val habits: List<com.mend.engine.model.Habit>,
        val goals: List<com.mend.app.repository.GoalWithLessons>,
        val sleepBlock: ProtectedRecovery?,
    )

    /**
     * Answers an outstanding proposal.
     *
     * All three responses go through here, including Decline: "no" is a real
     * answer that closes the question, not an absence of one.
     */
    fun respond(proposalId: String, response: ReshuffleResponse) {
        viewModelScope.launch { reshuffle.respond(proposalId, response, now()) }
    }

    private fun dumpDecisionLog(schedule: DaySchedule) {
        Log.d(LOG_TAG, "--- schedule for ${schedule.date} ---")
        schedule.log.entries.forEach { Log.d(LOG_TAG, it.render()) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val LOG_TAG = "MendScheduler"
    }
}

/** An outstanding proposal, flattened for display. */
data class ProposalUiState(
    val id: String,
    val effectiveFrom: LocalDate,
    val summary: String,
    val changes: List<String>,
)

data class TodayUiState(
    val date: LocalDate,
    val schedule: DaySchedule? = null,
    val sleepBlock: ProtectedRecovery? = null,
    val sleepStop: SleepStopDecision? = null,
    val interrupt: InterruptPlan? = null,
    val proposal: ProposalUiState? = null,
    val hasAnyInput: Boolean = false,
)
