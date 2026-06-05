package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_body_summaries")
data class DailyBodySummaryEntity(
    @PrimaryKey
    val date: String,
    val latestWeightKg: Double?,
    val latestVo2Max: Double?,
    val latestSpo2Percent: Double?,
    val weightRecordCount: Int,
    val vo2MaxRecordCount: Int,
    val spo2RecordCount: Int,
    val source: String,
    val lastRecordAtEpochMs: Long?,
    val syncedAtEpochMs: Long,
)
