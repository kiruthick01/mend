package com.mend.app

import android.app.Application
import com.mend.app.data.MendDatabase
import com.mend.app.repository.AdherenceRepository
import com.mend.app.repository.GoalRepository
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
