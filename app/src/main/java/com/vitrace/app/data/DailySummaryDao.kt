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
    suspend fun upsertWorkoutTypes(summaries: List<DailyWorkoutTypeSummaryEntity>)

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

    @Query("SELECT * FROM daily_activity_summaries WHERE date IN (:dates)")
    suspend fun activityRowsForDates(dates: List<String>): List<DailyActivitySummaryEntity>

    @Query("SELECT * FROM daily_heart_summaries WHERE date IN (:dates)")
    suspend fun heartRowsForDates(dates: List<String>): List<DailyHeartSummaryEntity>

    @Query("SELECT * FROM daily_sleep_summaries WHERE date IN (:dates)")
    suspend fun sleepRowsForDates(dates: List<String>): List<DailySleepSummaryEntity>

    @Query("SELECT * FROM daily_workout_summaries WHERE date IN (:dates)")
    suspend fun workoutRowsForDates(dates: List<String>): List<DailyWorkoutSummaryEntity>

    @Query("SELECT * FROM daily_body_summaries WHERE date IN (:dates)")
    suspend fun bodyRowsForDates(dates: List<String>): List<DailyBodySummaryEntity>

    @Query(
        """
        SELECT
            substr(date, 1, 4) AS period,
            COALESCE(SUM(steps), 0) AS steps,
            COALESCE(SUM(distanceMeters), 0) AS distanceMeters,
            COALESCE(SUM(activeCaloriesKcal), 0) AS activeCaloriesKcal,
            COUNT(CASE WHEN steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0 THEN 1 END) AS daysWithActivity
        FROM daily_activity_summaries
        WHERE steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0
        GROUP BY substr(date, 1, 4)
        ORDER BY period DESC
        """
    )
    suspend fun yearlyActivity(): List<ActivityPeriodAggregate>

    @Query(
        """
        SELECT
            substr(date, 1, 7) AS period,
            COALESCE(SUM(steps), 0) AS steps,
            COALESCE(SUM(distanceMeters), 0) AS distanceMeters,
            COALESCE(SUM(activeCaloriesKcal), 0) AS activeCaloriesKcal,
            COUNT(CASE WHEN steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0 THEN 1 END) AS daysWithActivity
        FROM daily_activity_summaries
        WHERE steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0
        GROUP BY substr(date, 1, 7)
        ORDER BY steps DESC
        LIMIT 1
        """
    )
    suspend fun bestActivityMonth(): ActivityPeriodAggregate?

    @Query(
        """
        SELECT
            substr(date, 1, 7) AS period,
            COALESCE(SUM(steps), 0) AS steps,
            COALESCE(SUM(distanceMeters), 0) AS distanceMeters,
            COALESCE(SUM(activeCaloriesKcal), 0) AS activeCaloriesKcal,
            COUNT(CASE WHEN steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0 THEN 1 END) AS daysWithActivity
        FROM daily_activity_summaries
        WHERE steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0
        GROUP BY substr(date, 1, 7)
        ORDER BY period DESC
        LIMIT :limit
        """
    )
    suspend fun recentActivityMonths(limit: Int): List<ActivityPeriodAggregate>

    @Query(
        """
        SELECT * FROM daily_sleep_summaries
        WHERE sessionCount > 0 OR totalSleepMinutes > 0
        ORDER BY date DESC
        LIMIT 1
        """
    )
    suspend fun latestSleep(): DailySleepSummaryEntity?

    @Query(
        """
        SELECT * FROM daily_sleep_summaries
        WHERE sessionCount > 0 OR totalSleepMinutes > 0
        ORDER BY date DESC
        LIMIT :limit
        """
    )
    suspend fun recentSleepDays(limit: Int): List<DailySleepSummaryEntity>

    @Query(
        """
        SELECT
            substr(date, 1, 7) AS period,
            COALESCE(SUM(totalSleepMinutes), 0) AS totalSleepMinutes,
            COUNT(CASE WHEN sessionCount > 0 OR totalSleepMinutes > 0 THEN 1 END) AS sleepDays
        FROM daily_sleep_summaries
        WHERE sessionCount > 0 OR totalSleepMinutes > 0
        GROUP BY substr(date, 1, 7)
        ORDER BY period DESC
        LIMIT :limit
        """
    )
    suspend fun recentSleepMonths(limit: Int): List<SleepPeriodAggregate>

    @Query(
        """
        SELECT
            COUNT(CASE WHEN sessionCount > 0 OR totalDurationMinutes > 0 THEN 1 END) AS daysWithWorkouts,
            COALESCE(SUM(sessionCount), 0) AS sessionCount,
            COALESCE(SUM(totalDurationMinutes), 0) AS totalDurationMinutes
        FROM daily_workout_summaries
        WHERE date >= :startDate
        """
    )
    suspend fun workoutsSince(startDate: String): WorkoutTotalsAggregate

    @Query(
        """
        SELECT
            workoutType AS workoutType,
            COUNT(CASE WHEN sessionCount > 0 OR totalDurationMinutes > 0 THEN 1 END) AS daysWithWorkouts,
            COALESCE(SUM(sessionCount), 0) AS sessionCount,
            COALESCE(SUM(totalDurationMinutes), 0) AS totalDurationMinutes,
            COALESCE(SUM(distanceMeters), 0) AS distanceMeters,
            COALESCE(SUM(activeCaloriesKcal), 0) AS activeCaloriesKcal,
            COALESCE(SUM(steps), 0) AS steps,
            AVG(avgHeartRateBpm) AS avgHeartRateBpm
        FROM daily_workout_type_summaries
        WHERE date >= :startDate AND workoutType = :workoutType
        GROUP BY workoutType
        """
    )
    suspend fun workoutTypeSince(startDate: String, workoutType: String): WorkoutTypeAggregate?

    @Query(
        """
        SELECT * FROM daily_workout_type_summaries
        WHERE workoutType = :workoutType
        ORDER BY date DESC
        LIMIT :limit
        """
    )
    suspend fun recentWorkoutTypeDays(workoutType: String, limit: Int): List<DailyWorkoutTypeSummaryEntity>

    @Query(
        """
        SELECT
            COUNT(CASE WHEN weightRecordCount > 0 THEN 1 END) AS bodyDays,
            COALESCE(SUM(weightRecordCount), 0) AS weightRecords,
            COALESCE(SUM(vo2MaxRecordCount), 0) AS vo2Records,
            COALESCE(SUM(spo2RecordCount), 0) AS spo2Records
        FROM daily_body_summaries
        """
    )
    suspend fun bodySignalCounts(): BodySignalAggregate

    @Query(
        """
        SELECT * FROM daily_body_summaries
        WHERE weightRecordCount > 0
        ORDER BY date DESC
        LIMIT 1
        """
    )
    suspend fun latestWeight(): DailyBodySummaryEntity?
}
