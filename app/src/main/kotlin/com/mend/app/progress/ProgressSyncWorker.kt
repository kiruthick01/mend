package com.mend.app.progress

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mend.app.MendApplication
import java.util.concurrent.TimeUnit

/** Checks linked outside apps a few times a day, with the app closed. */
class ProgressSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MendApplication ?: return Result.failure()
        if (app.progressLinks.all().isEmpty()) return Result.success()
        return runCatching { ProgressSync(app).run() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val WORK_NAME = "mend-progress-sync"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProgressSyncWorker>(2, TimeUnit.HOURS).build(),
            )
        }
    }
}
