package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_workout_summaries")
data class DailyWorkoutSummaryEntity(
    @PrimaryKey
    val date: String,
    val sessionCount: Int,
    val totalDurationMinutes: Long,
    val source: String,
    val lastRecordAtEpochMs: Long?,
    val syncedAtEpochMs: Long,
)
