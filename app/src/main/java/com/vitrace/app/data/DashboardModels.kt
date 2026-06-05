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

data class BodySignalAggregate(
    val bodyDays: Int,
    val weightRecords: Int,
    val vo2Records: Int,
    val spo2Records: Int,
)
