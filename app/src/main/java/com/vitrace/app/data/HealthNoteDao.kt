package com.vitrace.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HealthNoteDao {
    @Insert
    suspend fun insert(note: HealthNoteEntity): Long

    @Query("SELECT * FROM health_notes ORDER BY date DESC, createdAtEpochMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<HealthNoteEntity>

    @Query("SELECT COUNT(*) FROM health_notes")
    suspend fun count(): Int
}
