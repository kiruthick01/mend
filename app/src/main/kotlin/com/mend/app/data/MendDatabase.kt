package com.mend.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Offline-first local store. Phase 0 stands the database up with a single
 * placeholder entity so the Room toolchain is wired and verified; the real
 * entities from PROJECT_PLAN.md Section 3 arrive in Phase 1.
 */
@Database(
    entities = [PlaceholderEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MendDatabase : RoomDatabase() {

    abstract fun placeholderDao(): PlaceholderDao

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
            ).build()
    }
}
