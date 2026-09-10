package com.mend.app

import android.app.Application
import com.mend.app.data.MendDatabase
import com.mend.app.repository.GoalRepository
import com.mend.app.repository.StaticLayerRepository

class MendApplication : Application() {

    val database: MendDatabase by lazy { MendDatabase.getInstance(this) }

    val staticLayerRepository: StaticLayerRepository by lazy {
        StaticLayerRepository(database.taskDao())
    }

    val goalRepository: GoalRepository by lazy {
        GoalRepository(
            goalDao = database.goalDao(),
            lessonDao = database.lessonDao(),
            subTaskDao = database.subTaskDao(),
        )
    }
}
