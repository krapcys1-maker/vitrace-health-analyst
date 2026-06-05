package com.vitrace.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "analysis_results",
    indices = [
        Index(value = ["analysisType", "scope", "isCurrent"]),
        Index("createdAtEpochMs"),
    ],
)
data class AnalysisResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val analysisType: String,
    val scope: String,
    val engineVersion: String,
    val baselineStartDate: String?,
    val baselineEndDate: String?,
    val currentStartDate: String?,
    val currentEndDate: String?,
    val generatedForDate: String,
    val summaryTitle: String,
    val summaryText: String,
    val confidence: String,
    val sampleSize: Int,
    val resultJson: String,
    val sourceCoverageJson: String,
    val timeContextJson: String,
    val isCurrent: Boolean,
    val pinned: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val supersededAtEpochMs: Long?,
)
