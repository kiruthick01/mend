package com.mend.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mend.app.data.dao.AdherenceLogDao
import com.mend.app.data.dao.GoalDao
import com.mend.app.data.dao.LessonDao
import com.mend.app.data.dao.ReshuffleProposalDao
import com.mend.app.data.dao.ScheduleEventDao
import com.mend.app.data.dao.ScheduleFeedbackDao
import com.mend.app.data.dao.SleepLogDao
import com.mend.app.data.dao.WeightAdjustmentDao
import com.mend.app.data.dao.WeightsDao
import com.mend.app.data.dao.SubTaskDao
import com.mend.app.data.dao.TaskDao
import com.mend.app.data.entity.AdherenceLogEntity
import com.mend.app.data.entity.GoalEntity
import com.mend.app.data.entity.LessonEntity
import com.mend.app.data.entity.ReshuffleProposalEntity
import com.mend.app.data.entity.ScheduleEventEntity
import com.mend.app.data.entity.ScheduleFeedbackEntity
import com.mend.app.data.entity.SleepLogEntity
import com.mend.app.data.entity.WeightAdjustmentEntity
import com.mend.app.data.entity.WeightsEntity
import com.mend.app.data.entity.SubTaskEntity
import com.mend.app.data.entity.TaskEntity

/**
 * The offline-first local store. There is no remote database and no sync; the
 * schedule has to be fully usable with the network switched off.
 */
@Database(
    entities = [
        TaskEntity::class,
        GoalEntity::class,
        LessonEntity::class,
        SubTaskEntity::class,
        AdherenceLogEntity::class,
        SleepLogEntity::class,
        ScheduleEventEntity::class,
        ReshuffleProposalEntity::class,
        WeightsEntity::class,
        WeightAdjustmentEntity::class,
        ScheduleFeedbackEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MendDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun goalDao(): GoalDao
    abstract fun lessonDao(): LessonDao
    abstract fun subTaskDao(): SubTaskDao
    abstract fun adherenceLogDao(): AdherenceLogDao
    abstract fun sleepLogDao(): SleepLogDao
    abstract fun scheduleEventDao(): ScheduleEventDao
    abstract fun reshuffleProposalDao(): ReshuffleProposalDao
    abstract fun weightsDao(): WeightsDao
    abstract fun weightAdjustmentDao(): WeightAdjustmentDao
    abstract fun scheduleFeedbackDao(): ScheduleFeedbackDao

    companion object {
        private const val DATABASE_NAME = "mend.db"

        @Volatile
        private var instance: MendDatabase? = null

        fun getInstance(context: Context): MendDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): MendDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MendDatabase::class.java,
                DATABASE_NAME,
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Pre-release only. The app has never shipped, so there is no
                // real data to migrate and writing migrations for a schema
                // still in motion is wasted work. This MUST be replaced with
                // real migrations before the first release — losing a user's
                // adherence history on an update would destroy the one thing
                // the behavioural features are built on.
                .fallbackToDestructiveMigration()
                .build()
    }
}
