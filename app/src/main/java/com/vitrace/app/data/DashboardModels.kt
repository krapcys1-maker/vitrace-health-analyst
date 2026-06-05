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
