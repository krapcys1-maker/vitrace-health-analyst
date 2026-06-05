package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_sessions",
    indices = [
        Index("date"),
        Index(value = ["workoutType", "date"]),
    ],
)
data class WorkoutSessionEntity(
    @PrimaryKey
    val sessionId: String,
    val date: String,
    val workoutType: String,
    val sportName: String,
    val rawSportType: Int?,
    val startAtEpochMs: Long?,
    val endAtEpochMs: Long?,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val activeCaloriesKcal: Double,
    val totalCaloriesKcal: Double?,
    val steps: Long,
    val avgHeartRateBpm: Double?,
    val minHeartRateBpm: Long?,
    val maxHeartRateBpm: Long?,
    val avgPaceSecondsPerKm: Long?,
    val minPaceSecondsPerKm: Long?,
    val maxPaceSecondsPerKm: Long?,
    val avgCadence: Double?,
    val maxCadence: Long?,
    val trainingEffect: Double?,
    val recoveryTime: Long?,
    val vo2Max: Double?,
    val gpxUrl: String?,
    val source: String,
    val rawSourceFile: String,
    val rawTimestampEpochMs: Long?,
    val rawPayloadJson: String,
    val syncedAtEpochMs: Long,
)
