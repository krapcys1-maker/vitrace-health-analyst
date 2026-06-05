package com.vitrace.app.data

import androidx.room.Entity

@Entity(
    tableName = "daily_workout_type_summaries",
    primaryKeys = ["date", "workoutType"],
)
data class DailyWorkoutTypeSummaryEntity(
    val date: String,
    val workoutType: String,
    val sessionCount: Int,
    val totalDurationMinutes: Long,
    val distanceMeters: Double,
    val activeCaloriesKcal: Double,
    val steps: Long,
    val avgHeartRateBpm: Double?,
    val source: String,
    val lastRecordAtEpochMs: Long?,
    val syncedAtEpochMs: Long,
)
