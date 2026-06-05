package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "health_notes")
data class HealthNoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String,
    val noteText: String,
    val tags: String,
    val moodScore: Int?,
    val physicalScore: Int?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
