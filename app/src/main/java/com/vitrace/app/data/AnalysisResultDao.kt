package com.vitrace.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AnalysisResultDao {
    @Query(
        """
        UPDATE analysis_results
        SET isCurrent = 0, supersededAtEpochMs = :supersededAtEpochMs, updatedAtEpochMs = :supersededAtEpochMs
        WHERE analysisType = :analysisType AND scope = :scope AND isCurrent = 1 AND pinned = 0
        """
    )
    suspend fun supersedeCurrent(
        analysisType: String,
        scope: String,
        supersededAtEpochMs: Long,
    )

    @Insert
    suspend fun insert(result: AnalysisResultEntity): Long

    @Query(
        """
        DELETE FROM analysis_results
        WHERE analysisType = :analysisType
          AND scope = :scope
          AND isCurrent = 0
          AND pinned = 0
          AND id NOT IN (
              SELECT id FROM analysis_results
              WHERE analysisType = :analysisType
                AND scope = :scope
                AND isCurrent = 0
                AND pinned = 0
              ORDER BY updatedAtEpochMs DESC
              LIMIT :keepCount
          )
        """
    )
    suspend fun deleteOldSupersededCurrentSnapshots(
        analysisType: String,
        scope: String,
        keepCount: Int,
    )

    @Query(
        """
        SELECT * FROM analysis_results
        WHERE analysisType = :analysisType AND scope = :scope AND isCurrent = 1
        ORDER BY updatedAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun current(
        analysisType: String,
        scope: String,
    ): AnalysisResultEntity?

    @Query(
        """
        SELECT * FROM analysis_results
        WHERE pinned = 1 OR scope = 'saved_report'
        ORDER BY createdAtEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun saved(limit: Int): List<AnalysisResultEntity>
}
