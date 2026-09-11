package com.mend.app

import android.app.Application
import com.mend.app.ai.AiBridge
import com.mend.app.ai.AiSettings
import com.mend.app.ai.AnthropicAiBridge
import com.mend.app.ai.AnthropicClient
import com.mend.app.ai.OfflineAiBridge
import com.mend.app.data.MendDatabase
import com.mend.app.repository.AdherenceRepository
import com.mend.app.repository.GoalRepository
import com.mend.app.repository.ProposalInterpreter
import com.mend.app.repository.RecoveryRepository
import com.mend.app.repository.ReshuffleRepository
import com.mend.app.repository.StaticLayerRepository
import com.mend.app.repository.SweepState
import com.mend.app.repository.WeightsRepository

class MendApplication : Application() {

    val database: MendDatabase by lazy { MendDatabase.getInstance(this) }

    val staticLayerRepository: StaticLayerRepository by lazy {
        StaticLayerRepository(database.taskDao(), reshuffleRepository)
    }

    val weightsRepository: WeightsRepository by lazy {
        WeightsRepository(
            weightsDao = database.weightsDao(),
            adjustmentDao = database.weightAdjustmentDao(),
            feedbackDao = database.scheduleFeedbackDao(),
        )
    }

    val aiSettings: AiSettings by lazy { AiSettings(this) }

    /**
     * The AI layer, or the honest absence of one.
     *
     * Resolved per access rather than cached, so adding a key in Settings takes
     * effect immediately instead of at next launch. Everything downstream is
     * written against the interface, so the offline branch is not a degraded
     * mode - it is simply the app without a key.
     */
    val aiBridge: AiBridge
        get() = if (aiSettings.hasApiKey && aiSettings.enabled) {
            AnthropicAiBridge(
                client = AnthropicClient(apiKeyProvider = { aiSettings.apiKey }),
                isEnabled = { aiSettings.enabled },
            )
        } else {
            OfflineAiBridge(
                reason = if (!aiSettings.enabled) {
                    com.mend.app.ai.AiAvailability.DISABLED
                } else {
                    com.mend.app.ai.AiAvailability.NO_API_KEY
                },
            )
        }

    val proposalInterpreter: ProposalInterpreter
        get() = ProposalInterpreter(aiBridge)

    val sweepState: SweepState by lazy { SweepState(this) }

    val adherenceRepository: AdherenceRepository by lazy {
        AdherenceRepository(database.adherenceLogDao(), reshuffleRepository)
    }

    val recoveryRepository: RecoveryRepository by lazy {
        RecoveryRepository(
            taskDao = database.taskDao(),
            sleepLogDao = database.sleepLogDao(),
            adherence = adherenceRepository,
        )
    }

    val reshuffleRepository: ReshuffleRepository by lazy {
        ReshuffleRepository(
            eventDao = database.scheduleEventDao(),
            proposalDao = database.reshuffleProposalDao(),
        )
    }

    val goalRepository: GoalRepository by lazy {
        GoalRepository(
            goalDao = database.goalDao(),
            lessonDao = database.lessonDao(),
            subTaskDao = database.subTaskDao(),
            reshuffle = reshuffleRepository,
            adherence = adherenceRepository,
        )
    }
}
