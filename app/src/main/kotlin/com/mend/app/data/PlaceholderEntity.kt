package com.mend.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "placeholder")
data class PlaceholderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)

@Dao
interface PlaceholderDao {

    @Query("SELECT COUNT(*) FROM placeholder")
    suspend fun count(): Int
}
