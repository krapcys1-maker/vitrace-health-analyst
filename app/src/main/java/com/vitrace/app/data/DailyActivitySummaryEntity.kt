package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_activity_summaries")
data class DailyActivitySummaryEntity(
    @PrimaryKey
    val date: String,
    val steps: Long,
    val distanceMeters: Double,
    val activeCaloriesKcal: Double,
    val source: String,
    val syncedAtEpochMs: Long,
)
