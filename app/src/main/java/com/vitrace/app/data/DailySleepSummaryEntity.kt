package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_sleep_summaries")
data class DailySleepSummaryEntity(
    @PrimaryKey
    val date: String,
    val sessionCount: Int,
    val totalSleepMinutes: Long,
    val source: String,
    val lastRecordAtEpochMs: Long?,
    val syncedAtEpochMs: Long,
)
