package com.vitrace.app.data

data class DashboardActivityAggregate(
    val steps: Long,
    val distanceMeters: Double,
    val activeCaloriesKcal: Double,
    val daysWithActivity: Int,
)

data class DashboardSignalCounts(
    val heartDays: Int,
    val sleepDays: Int,
    val workoutDays: Int,
    val bodyDays: Int,
)

data class ActivityPeriodAggregate(
    val period: String,
    val steps: Long,
    val distanceMeters: Double,
    val activeCaloriesKcal: Double,
    val daysWithActivity: Int,
)

data class SleepPeriodAggregate(
    val period: String,
    val totalSleepMinutes: Long,
    val sleepDays: Int,
)

data class WorkoutTotalsAggregate(
    val daysWithWorkouts: Int,
    val sessionCount: Int,
    val totalDurationMinutes: Long,
)

data class WorkoutTypeAggregate(
    val workoutType: String,
    val daysWithWorkouts: Int,
    val sessionCount: Int,
    val totalDurationMinutes: Long,
    val distanceMeters: Double,
    val activeCaloriesKcal: Double,
    val steps: Long,
    val avgHeartRateBpm: Double?,
)

data class BodySignalAggregate(
    val bodyDays: Int,
    val weightRecords: Int,
    val vo2Records: Int,
    val spo2Records: Int,
)

data class MonthlySleepPhaseAggregate(
    val period: String,
    val sleepDays: Int,
    val avgTotalSleepMinutes: Double,
    val avgDeepSleepMinutes: Double?,
    val avgLightSleepMinutes: Double?,
    val avgRemSleepMinutes: Double?,
    val avgAwakeMinutes: Double?,
    val avgSleepScore: Double?,
)

data class SleepActivityFeatureRow(
    val date: String,
    val steps: Long,
    val distanceMeters: Double,
    val activeCaloriesKcal: Double,
    val totalSleepMinutes: Long,
    val deepSleepMinutes: Long?,
    val lightSleepMinutes: Long?,
    val remSleepMinutes: Long?,
    val awakeMinutes: Long?,
    val sleepScore: Int?,
)

data class MonthlyWorkoutSessionAggregate(
    val period: String,
    val workoutType: String,
    val sessionCount: Int,
    val totalDurationSeconds: Long,
    val distanceMeters: Double,
    val activeCaloriesKcal: Double,
    val totalCaloriesKcal: Double?,
    val avgHeartRateBpm: Double?,
    val maxHeartRateBpm: Long?,
    val avgPaceSecondsPerKm: Double?,
    val avgCadence: Double?,
    val avgVo2Max: Double?,
)
