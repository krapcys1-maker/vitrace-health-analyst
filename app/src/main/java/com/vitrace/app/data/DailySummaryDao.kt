package com.vitrace.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DailySummaryDao {
    @Upsert
    suspend fun upsertActivity(summaries: List<DailyActivitySummaryEntity>)

    @Upsert
    suspend fun upsertHeart(summaries: List<DailyHeartSummaryEntity>)

    @Upsert
    suspend fun upsertSleep(summaries: List<DailySleepSummaryEntity>)

    @Upsert
    suspend fun upsertWorkouts(summaries: List<DailyWorkoutSummaryEntity>)

    @Upsert
    suspend fun upsertBody(summaries: List<DailyBodySummaryEntity>)

    @Query(
        """
        SELECT COUNT(*) FROM daily_activity_summaries
        WHERE steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0
        """
    )
    suspend fun activityDays(): Int

    @Query("SELECT COUNT(*) FROM daily_heart_summaries WHERE sampleCount > 0")
    suspend fun heartDays(): Int

    @Query("SELECT COUNT(*) FROM daily_sleep_summaries WHERE sessionCount > 0 OR totalSleepMinutes > 0")
    suspend fun sleepDays(): Int

    @Query("SELECT COUNT(*) FROM daily_workout_summaries WHERE sessionCount > 0 OR totalDurationMinutes > 0")
    suspend fun workoutDays(): Int

    @Query(
        """
        SELECT COUNT(*) FROM daily_body_summaries
        WHERE weightRecordCount > 0 OR vo2MaxRecordCount > 0 OR spo2RecordCount > 0
        """
    )
    suspend fun bodyDays(): Int

    @Query(
        """
        SELECT MAX(syncedAtEpochMs) FROM (
            SELECT syncedAtEpochMs FROM daily_activity_summaries
            UNION ALL SELECT syncedAtEpochMs FROM daily_heart_summaries
            UNION ALL SELECT syncedAtEpochMs FROM daily_sleep_summaries
            UNION ALL SELECT syncedAtEpochMs FROM daily_workout_summaries
            UNION ALL SELECT syncedAtEpochMs FROM daily_body_summaries
        )
        """
    )
    suspend fun lastSyncedAtEpochMs(): Long?

    @Query(
        """
        SELECT
            COALESCE(SUM(steps), 0) AS steps,
            COALESCE(SUM(distanceMeters), 0) AS distanceMeters,
            COALESCE(SUM(activeCaloriesKcal), 0) AS activeCaloriesKcal,
            COUNT(CASE WHEN steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0 THEN 1 END) AS daysWithActivity
        FROM daily_activity_summaries
        WHERE date = :date
        """
    )
    suspend fun activityForDate(date: String): DashboardActivityAggregate

    @Query(
        """
        SELECT
            COALESCE(SUM(steps), 0) AS steps,
            COALESCE(SUM(distanceMeters), 0) AS distanceMeters,
            COALESCE(SUM(activeCaloriesKcal), 0) AS activeCaloriesKcal,
            COUNT(CASE WHEN steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0 THEN 1 END) AS daysWithActivity
        FROM daily_activity_summaries
        WHERE date >= :startDate
        """
    )
    suspend fun activitySince(startDate: String): DashboardActivityAggregate

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM daily_heart_summaries WHERE sampleCount > 0 AND date >= :startDate) AS heartDays,
            (SELECT COUNT(*) FROM daily_sleep_summaries WHERE (sessionCount > 0 OR totalSleepMinutes > 0) AND date >= :startDate) AS sleepDays,
            (SELECT COUNT(*) FROM daily_workout_summaries WHERE (sessionCount > 0 OR totalDurationMinutes > 0) AND date >= :startDate) AS workoutDays,
            (SELECT COUNT(*) FROM daily_body_summaries WHERE (weightRecordCount > 0 OR vo2MaxRecordCount > 0 OR spo2RecordCount > 0) AND date >= :startDate) AS bodyDays
        """
    )
    suspend fun signalCountsSince(startDate: String): DashboardSignalCounts
}
