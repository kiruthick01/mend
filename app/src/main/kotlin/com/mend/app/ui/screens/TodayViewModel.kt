package com.mend.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mend.app.data.entity.ReshuffleProposalEntity
import com.mend.app.data.mapper.changeLines
import com.mend.app.repository.AdherenceRepository
import com.mend.app.repository.GoalRepository
import com.mend.app.repository.GoalWithLessons
import com.mend.app.repository.RecoveryRepository
import com.mend.app.repository.ReshuffleRepository
import com.mend.app.repository.ScheduleBuilder
import com.mend.app.repository.StaticLayerRepository
import com.mend.app.repository.detectInterrupt
import com.mend.engine.adherence.detectElapsedMisses
import com.mend.engine.allocation.DaySchedule
import com.mend.engine.guilt.GuiltTrip
import com.mend.engine.guilt.evaluateAllGuiltTrips
import com.mend.engine.interrupt.InterruptPlan
import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.Habit
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.SubTask
import com.mend.engine.recovery.SleepStopDecision
import com.mend.engine.recovery.evaluateSleepStop
import com.mend.engine.reshuffle.ReshuffleResponse
import com.mend.engine.reshuffle.ScheduleInvalidatingEvent
import com.mend.engine.time.TimeInterval
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The whole of today: the fixed grid, the goal work packed into what is left,
 * the bedtime boundary, any outstanding negotiation, and any warning that has
 * earned the right to be shown.
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
    private val adherence: AdherenceRepository,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    /** Warnings waved away for this session. Not persisted, and not a resolution. */
    private val dismissedGuiltTrips = MutableStateFlow<Set<String>>(emptySet())

    /** The stored inputs the day is built from. */
    private data class Inputs(
        val blocks: List<StaticBlock>,
        val habits: List<Habit>,
        val goals: List<GoalWithLessons>,
        val sleepBlock: ProtectedRecovery?,
        val proposal: ReshuffleProposalEntity?,
    )

    private val inputs = combine(
        staticLayer.observeStaticBlocks(),
        staticLayer.observeHabits(),
        goals.observeGoals(),
        recovery.observeSleepBlock(),
        reshuffle.observeOutstanding(),
    ) { blocks, habits, goalList, sleepBlock, proposal ->
        Inputs(blocks, habits, goalList, sleepBlock, proposal)
    }

    val state: StateFlow<TodayUiState> =
        combine(
            inputs,
            adherence.observeLog(),
            dismissedGuiltTrips,
        ) { input, log, dismissed ->
            buildState(input, log, dismissed)
        }
            .onEach { it.schedule?.let(::dumpDecisionLog) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = TodayUiState(date = now().toLocalDate()),
            )

    private fun buildState(
        input: Inputs,
        log: List<AdherenceLogEntry>,
        dismissed: Set<String>,
    ): TodayUiState {
        val instant = now()
        val date = instant.toLocalDate()
        val recoveryBlocks = listOfNotNull(input.sleepBlock)

        val schedule = ScheduleBuilder.buildDaySchedule(
            date = date,
            staticBlocks = input.blocks,
            habits = input.habits,
            goals = input.goals,
            protectedRecovery = recoveryBlocks,
        )

        return TodayUiState(
            date = date,
            schedule = schedule,
            sleepBlock = input.sleepBlock,
            sleepStop = evaluateSleepStop(instant, schedule, input.sleepBlock),
            interrupt = detectInterrupt(
                now = instant,
                schedule = schedule,
                goals = input.goals,
                staticBlocks = input.blocks,
                habits = input.habits,
                protectedRecovery = recoveryBlocks,
            ),
            proposal = input.proposal?.let {
                ProposalUiState(
                    id = it.id,
                    effectiveFrom = it.effectiveFrom,
                    summary = it.summary,
                    changes = it.changeLines(),
                )
            },
            guiltTrips = evaluateAllGuiltTrips(
                goals = input.goals.map { it.goal to it.lessons },
                log = log,
                today = date,
            ).filterNot { it.goalId in dismissed },
            // Scheduled work keyed back to the sub-task behind it, so Today can
            // tick something off without first going and finding it in the goal
            // hierarchy.
            subTasksById = input.goals
                .flatMap { it.lessons }
                .flatMap { lesson -> lesson.subTasks.map { it.id to it } }
                .toMap(),
            hasAnyInput = input.blocks.isNotEmpty() || input.habits.isNotEmpty() ||
                input.goals.isNotEmpty() || input.sleepBlock != null,
        )
    }

    init {
        watchForReshuffleTriggers()
        watchForElapsedMisses()
    }

    /**
     * Writes down scheduled work whose time has gone.
     *
     * A miss has to become an event or none of the behavioural features work:
     * left implicit, adherence never degrades, the projection never sees a
     * problem, and the warning that should fire never does. Silence here turns
     * into false reassurance downstream.
     *
     * Completed work needs no exclusion — it stops being pending, so it is not
     * in the schedule to begin with. `alreadyLogged` is what prevents a second
     * entry for the same task on the same day, which matters because the stream
     * is append-only and a duplicate could never be tidied away.
     *
     * Only today is swept. Catching up on days the app was not open needs
     * either stored schedules or a background job, and is Phase 8 work.
     */
    private fun watchForElapsedMisses() {
        viewModelScope.launch {
            state.collect { current ->
                val schedule = current.schedule ?: return@collect
                val instant = now()

                detectElapsedMisses(
                    schedule = schedule,
                    now = instant,
                    alreadyLogged = adherence.loggedOn(current.date),
                ).forEach { miss ->
                    adherence.recordMiss(
                        taskId = miss.taskId,
                        taskTitle = miss.placement.task.title,
                        assignedSlot = miss.slot,
                        now = instant,
                    )
                }
            }
        }
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
            combine(reshuffle.observePendingEvents(), inputs) { pending, input ->
                pending.isNotEmpty() to input
            }.collect { (hasPending, input) ->
                if (!hasPending) return@collect

                val instant = now()
                reshuffle.proposeIfNeeded(
                    now = instant,
                    proposedSchedule = ScheduleBuilder.buildDaySchedule(
                        date = reshuffle.earliestEffectiveDate(instant),
                        staticBlocks = input.blocks,
                        habits = input.habits,
                        goals = input.goals,
                        protectedRecovery = listOfNotNull(input.sleepBlock),
                    ),
                )
            }
        }
    }

    /**
     * Marks scheduled work done, against the slot it was actually given.
     *
     * The slot is passed through rather than inferred: ticking something off at
     * 21:00 that was scheduled for 09:00 is a materially different fact from
     * doing it on time, and that difference is what adherence is made of.
     */
    fun markDone(subTask: SubTask, assignedSlot: TimeInterval?) {
        viewModelScope.launch {
            goals.setSubTaskDone(
                subTask = subTask,
                done = true,
                orderIndex = 0,
                now = now(),
                assignedSlot = assignedSlot,
            )
        }
    }

    /** Records that scheduled work did not happen, and offers to replan. */
    fun markMissed(taskId: String, taskTitle: String, assignedSlot: TimeInterval) {
        viewModelScope.launch {
            adherence.recordMiss(taskId, taskTitle, assignedSlot, now())
        }
    }

    /**
     * Accepts a warning and asks for a different plan.
     *
     * This only raises the event. The proposal that follows still arrives with
     * Accept / Decline / Propose different, so agreeing to be replanned is not
     * the same as agreeing to the replan.
     */
    fun requestReshuffleFor(trip: GuiltTrip) {
        viewModelScope.launch {
            reshuffle.record(
                ScheduleInvalidatingEvent.ManualEdit(
                    occurredAt = now(),
                    summary = "you asked for a different plan for ${trip.goalTitle}",
                ),
            )
            dismissGuiltTrip(trip.goalId)
        }
    }

    /** Hides a warning for this session without pretending it was resolved. */
    fun dismissGuiltTrip(goalId: String) {
        dismissedGuiltTrips.value = dismissedGuiltTrips.value + goalId
    }

    /** Answers an outstanding proposal. Decline is a real answer, not an absence. */
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
    val guiltTrips: List<GuiltTrip> = emptyList(),
    val subTasksById: Map<String, SubTask> = emptyMap(),
    val hasAnyInput: Boolean = false,
)
