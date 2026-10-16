package com.mend.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mend.app.alarms.AlarmCoordinator
import com.mend.app.data.entity.ReshuffleProposalEntity
import com.mend.app.data.mapper.changeLines
import com.mend.app.repository.AdherenceRepository
import com.mend.app.repository.GoalRepository
import com.mend.app.repository.GoalWithLessons
import com.mend.app.repository.MissSweeper
import com.mend.app.repository.ProposalInterpreter
import com.mend.app.repository.RecoveryRepository
import com.mend.app.repository.ReshuffleRepository
import com.mend.app.repository.ScheduleBuilder
import com.mend.app.repository.StaticLayerRepository
import com.mend.app.repository.SweepState
import com.mend.app.repository.WeightsRepository
import com.mend.app.repository.detectInterrupt
import com.mend.app.ui.components.ProposeDifferentState
import com.mend.engine.adherence.detectElapsedMisses
import com.mend.engine.alarms.interruptAlarm
import com.mend.engine.alarms.planAlarms
import com.mend.engine.allocation.DaySchedule
import com.mend.engine.guilt.GuiltTrip
import com.mend.engine.guilt.evaluateAllGuiltTrips
import com.mend.engine.interrupt.InterruptPlan
import com.mend.engine.model.AdherenceLogEntry
import com.mend.engine.model.Habit
import com.mend.engine.model.Lesson
import com.mend.engine.model.ProtectedRecovery
import com.mend.engine.model.StaticBlock
import com.mend.engine.model.SubTask
import com.mend.engine.model.Weights
import com.mend.engine.recovery.SleepStopDecision
import com.mend.engine.recovery.evaluateSleepStop
import com.mend.engine.reshuffle.ReshuffleResponse
import com.mend.engine.reshuffle.ScheduleInvalidatingEvent
import com.mend.engine.time.TimeInterval
import com.mend.engine.weights.ScheduleFeedback
import com.mend.engine.weights.WeightAdjustment
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val weights: WeightsRepository,
    private val interpreter: ProposalInterpreter,
    private val alarms: AlarmCoordinator? = null,
    private val sweepState: SweepState? = null,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    /** Warnings waved away for this session. Not persisted, and not a resolution. */
    private val dismissedGuiltTrips = MutableStateFlow<Set<String>>(emptySet())

    /** The proposal whose feedback prompt is currently open, if any. */
    private val pendingFeedbackFor = MutableStateFlow<String?>(null)

    /** The most recent weight change, held until the user has seen it. */
    private val lastAdjustment = MutableStateFlow<WeightAdjustment?>(null)

    /** The open "propose different" dialog, if any. */
    private val proposeDifferent = MutableStateFlow<ProposeDifferentState?>(null)

    /** The stored inputs the day is built from. */
    private data class Inputs(
        val blocks: List<StaticBlock>,
        val habits: List<Habit>,
        val goals: List<GoalWithLessons>,
        /** Goals as the scheduler sees them. Never written back: durations may be adjusted. */
        val scheduled: List<GoalWithLessons>,
        val sleepBlock: ProtectedRecovery?,
        val proposal: ReshuffleProposalEntity?,
    )

    private val inputs = combine(
        staticLayer.observeStaticBlocks(),
        staticLayer.observeHabits(),
        combine(goals.observeGoals(), goals.observeGoalsForScheduling()) { stored, scheduled ->
            stored to scheduled
        },
        recovery.observeSleepBlock(),
        reshuffle.observeOutstanding(),
    ) { blocks, habits, (goalList, scheduled), sleepBlock, proposal ->
        Inputs(blocks, habits, goalList, scheduled, sleepBlock, proposal)
    }

    val state: StateFlow<TodayUiState> =
        combine(
            combine(
                inputs,
                adherence.observeLog(),
                dismissedGuiltTrips,
                weights.observeWeights(),
            ) { input, log, dismissed, currentWeights ->
                buildState(input, log, dismissed, currentWeights)
            },
            pendingFeedbackFor,
            lastAdjustment,
            proposeDifferent,
        ) { base, askingFor, adjustment, propose ->
            base.copy(
                feedbackPromptFor = askingFor,
                lastAdjustmentReason = adjustment?.describe(),
                proposeDifferent = propose,
            )
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
        weights: Weights,
    ): TodayUiState {
        val instant = now()
        val date = instant.toLocalDate()
        val recoveryBlocks = listOfNotNull(input.sleepBlock)

        val schedule = ScheduleBuilder.buildDaySchedule(
            date = date,
            staticBlocks = input.blocks,
            habits = input.habits,
            goals = input.scheduled,
            protectedRecovery = recoveryBlocks,
            weights = weights,
            adherenceLog = log,
        )

        return TodayUiState(
            date = date,
            schedule = schedule,
            sleepBlock = input.sleepBlock,
            sleepStop = evaluateSleepStop(instant, schedule, input.sleepBlock),
            interrupt = detectInterrupt(
                now = instant,
                schedule = schedule,
                goals = input.scheduled,
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
            // A lesson with no sub-tasks is scheduled as itself, so it needs to
            // be completable from Today too. Without this it could only ever be
            // marked missed, which is a strange thing to offer on its own.
            bareLessonsById = input.goals
                .flatMap { it.lessons }
                .filter { it.hasNoSubTasks }
                .associateBy { it.id },
            hasAnyInput = input.blocks.isNotEmpty() || input.habits.isNotEmpty() ||
                input.goals.isNotEmpty() || input.sleepBlock != null,
        )
    }

    init {
        watchForScheduleChanges()
        watchForReshuffleTriggers()
        watchForElapsedMisses()
        sweepMissedDays()
    }

    /**
     * Back-fills days the app was never opened.
     *
     * Runs once, against the first snapshot of the inputs. A past day has no
     * stored schedule, but a schedule is a pure function of its inputs, so it
     * can be recomputed — and work since completed drops out on its own,
     * because it is no longer pending.
     */
    /**
     * Records what the user thought of a schedule, and says so if it moved a
     * weight.
     *
     * Surfacing the change is the point. An app that quietly retunes itself
     * becomes inexplicable to its own owner, which is exactly what the plan's
     * debuggability constraint is guarding against.
     */
    fun recordFeedback(response: ScheduleFeedback, proposalId: String? = null) {
        viewModelScope.launch {
            val adjustment = weights.recordFeedback(response, proposalId, now())
            lastAdjustment.value = adjustment
            pendingFeedbackFor.value = null
        }
    }

    /** Opens the feedback prompt for a proposal that has just been answered. */
    fun askForFeedback(proposalId: String) {
        pendingFeedbackFor.value = proposalId
    }

    fun dismissFeedbackPrompt() {
        pendingFeedbackFor.value = null
    }

    fun dismissAdjustmentNotice() {
        lastAdjustment.value = null
    }

    private fun sweepMissedDays() {
        val state = sweepState ?: return
        viewModelScope.launch {
            val input = inputs.first()
            val instant = now()

            MissSweeper.sweep(
                lastSwept = state.lastSweptDate,
                now = instant,
                staticBlocks = input.blocks,
                habits = input.habits,
                protectedRecovery = listOfNotNull(input.sleepBlock),
                goals = input.goals,
                alreadyLoggedOn = { adherence.loggedOn(it) },
            ).forEach { recovered ->
                adherence.recordMiss(
                    taskId = recovered.taskId,
                    taskTitle = recovered.taskTitle,
                    assignedSlot = recovered.slot,
                    now = instant,
                )
            }

            state.lastSweptDate = instant.toLocalDate().minusDays(1)
        }
    }

    /**
     * Keeps the armed alarms matching the day on screen.
     *
     * It deliberately does **not** work out the alarms itself. The coordinator
     * is the only thing that builds an alarm plan, because two components each
     * applying their own plan spend their time cancelling one another's alarms
     * — which is exactly what happened on a real device before this was moved.
     * Here we only say that the shape of the day changed.
     *
     * The signature is what makes that affordable: the state flow re-emits for
     * things alarms do not care about, such as a dismissed warning or an open
     * dialog, and replanning on each of those would rebuild the day dozens of
     * times an hour for no reason.
     */
    private fun watchForScheduleChanges() {
        val coordinator = alarms ?: return
        viewModelScope.launch {
            state
                .map { current -> current.alarmSignature() to current.interrupt }
                .distinctUntilChanged()
                .collectLatest { (_, interrupt) ->
                    val instant = now()

                    // An interrupt only qualifies for an alarm once it has been
                    // given a time; one that could not claim a slot has nothing
                    // to fire at.
                    coordinator.setInterrupt(
                        interrupt
                            ?.takeIf { it.slot != null }
                            ?.let { plan ->
                                interruptAlarm(
                                    taskId = plan.task.id,
                                    taskTitle = plan.task.title,
                                    at = maxOf(plan.slot!!.start, instant.plusMinutes(1)),
                                    message = plan.message,
                                )
                            },
                    )

                    coordinator.replanToday(instant)
                }
        }
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
                        goals = input.scheduled,
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
                now = now(),
                assignedSlot = assignedSlot,
            )
        }
    }

    /** Marks a bare lesson done against the slot it was given. */
    fun markLessonDone(lesson: Lesson, assignedSlot: TimeInterval?) {
        viewModelScope.launch {
            goals.setLessonDone(
                lesson = lesson,
                done = true,
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

    // --- propose different ---------------------------------------------------

    fun openProposeDifferent(proposalId: String) {
        proposeDifferent.value = ProposeDifferentState(proposalId = proposalId)
    }

    fun updateProposeText(text: String) {
        proposeDifferent.value = proposeDifferent.value?.copy(text = text, outcome = null)
    }

    fun dismissProposeDifferent() {
        proposeDifferent.value = null
    }

    /**
     * Reads what the user wrote, then stops.
     *
     * Deliberately does not apply anything. The result is shown back so a
     * misreading becomes a corrected sentence rather than a changed schedule,
     * and the engine has already refused anything incoherent before this
     * returns.
     */
    fun interpretProposeDifferent() {
        val current = proposeDifferent.value ?: return
        proposeDifferent.value = current.copy(isWorking = true, outcome = null)

        viewModelScope.launch {
            val input = inputs.first()
            val outcome = interpreter.interpret(
                text = current.text,
                today = now().toLocalDate(),
                goalTitles = input.goals.map { it.goal.title },
                staticBlocks = input.blocks,
                habits = input.habits,
                protectedRecovery = listOfNotNull(input.sleepBlock),
            )
            proposeDifferent.value = proposeDifferent.value?.copy(
                isWorking = false,
                outcome = outcome,
            )
        }
    }

    /**
     * Stores the words verbatim, which is the only thing this flow commits.
     *
     * There used to be a second path that "accepted" an understood constraint.
     * It recorded a [ScheduleInvalidatingEvent.ManualEdit] carrying a summary
     * string and nothing else — no repository ever saw the parsed constraint —
     * so the replan it triggered read unchanged data and the described change
     * simply did not happen. It was removed rather than left looking capable.
     *
     * Reading the prose still earns its place: the interpretation is shown so
     * the user can see it was understood, and the dialog names the change to
     * make in Tasks. Applying it is the piece that is not built.
     */
    fun saveProposeVerbatim() {
        val current = proposeDifferent.value ?: return
        viewModelScope.launch {
            reshuffle.respond(
                current.proposalId,
                ReshuffleResponse.ProposeDifferent(current.text),
                now(),
            )
            proposeDifferent.value = null
            askForFeedback(current.proposalId)
        }
    }

    /** Answers an outstanding proposal. Decline is a real answer, not an absence. */
    fun respond(proposalId: String, response: ReshuffleResponse) {
        viewModelScope.launch {
            reshuffle.respond(proposalId, response, now())
            // The plan asks for feedback after a prioritisation decision, which
            // is exactly this moment: a plan was just accepted or turned down.
            askForFeedback(proposalId)
        }
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
    /** Proposal id whose feedback prompt is open, or null. */
    val feedbackPromptFor: String? = null,
    /** The most recent weight change, until acknowledged. */
    val lastAdjustmentReason: String? = null,
    val proposeDifferent: ProposeDifferentState? = null,
    val subTasksById: Map<String, SubTask> = emptyMap(),
    val bareLessonsById: Map<String, Lesson> = emptyMap(),
    val hasAnyInput: Boolean = false,
) {
    /**
     * Everything about this state that could change which alarms are wanted.
     *
     * The bedtime boundary and every placement's task and start time, and
     * nothing else. Two states with the same signature want the same alarms, so
     * the second one is not worth replanning for.
     */
    fun alarmSignature(): String = buildString {
        append(sleepBlock?.let { "${it.bedtime}/${it.windDown}/${it.hardStop}" } ?: "-")
        schedule?.placements?.forEach { placement ->
            append('|')
            append(placement.task.id)
            append('@')
            append(placement.slices.minByOrNull { it.start }?.start)
        }
    }
}
