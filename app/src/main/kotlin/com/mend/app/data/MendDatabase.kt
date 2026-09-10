package com.mend.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mend.app.data.dao.AdherenceLogDao
import com.mend.app.data.dao.GoalDao
import com.mend.app.data.dao.LessonDao
import com.mend.app.data.dao.SubTaskDao
import com.mend.app.data.dao.TaskDao
import com.mend.app.data.entity.AdherenceLogEntity
import com.mend.app.data.entity.GoalEntity
import com.mend.app.data.entity.LessonEntity
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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MendDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun goalDao(): GoalDao
    abstract fun lessonDao(): LessonDao
    abstract fun subTaskDao(): SubTaskDao
    abstract fun adherenceLogDao(): AdherenceLogDao

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
                .build()
    }
}
