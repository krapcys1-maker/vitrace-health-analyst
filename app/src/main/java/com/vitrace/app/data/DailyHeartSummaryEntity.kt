package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_heart_summaries")
data class DailyHeartSummaryEntity(
    @PrimaryKey
    val date: String,
    val sampleCount: Int,
    val minBpm: Long?,
    val maxBpm: Long?,
    val avgBpm: Double?,
    val source: String,
    val lastRecordAtEpochMs: Long?,
    val syncedAtEpochMs: Long,
)
