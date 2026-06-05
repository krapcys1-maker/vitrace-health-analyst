package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "health_connect_quality_snapshots")
data class HealthConnectQualitySnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val capturedAtEpochMs: Long,
    val metricKey: String,
    val label: String,
    val hasPermission: Boolean,
    val count1d: Int,
    val count7d: Int,
    val count30d: Int,
    val origins: String,
    val lastRecordAtEpochMs: Long?,
    val status: String,
)
