package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sleep_details",
    indices = [Index("date")],
)
data class SleepDetailEntity(
    @PrimaryKey
    val date: String,
    val bedtimeEpochMs: Long?,
    val wakeUpEpochMs: Long?,
    val totalSleepMinutes: Long,
    val deepSleepMinutes: Long?,
    val lightSleepMinutes: Long?,
    val remSleepMinutes: Long?,
    val awakeMinutes: Long?,
    val awakeCount: Int?,
    val sleepScore: Int?,
    val segmentCount: Int,
    val source: String,
    val rawSourceFile: String,
    val rawSourceKey: String,
    val rawTimestampEpochMs: Long?,
    val rawPayloadJson: String,
    val syncedAtEpochMs: Long,
)
