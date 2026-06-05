package com.vitrace.app.health

import com.vitrace.app.data.UserProfileEntity

data class HealthConnectDiagnostics(
    val sdkStatus: HealthConnectSdkStatus,
    val grantedPermissionCount: Int = 0,
    val requiredPermissionCount: Int = 0,
    val permissionsChecked: Boolean = false,
    val rows: List<DiagnosticRow> = emptyList(),
    val dataQualityItems: List<DataQualityItem> = emptyList(),
    val dashboard: HealthDashboard? = null,
    val profile: UserProfileEntity? = null,
    val longTermActivity: LongTermActivitySummary? = null,
    val sleepSummary: SleepDomainSummary? = null,
    val sportSummary: SportDomainSummary? = null,
    val bodySummary: BodyDomainSummary? = null,
    val healthJournal: HealthJournalSummary? = null,
    val savedSnapshotCount: Int = 0,
    val dailySyncSummary: DailySyncSummary? = null,
    val error: String? = null,
) {
    val hasAllPermissions: Boolean
        get() = permissionsChecked && requiredPermissionCount > 0 && grantedPermissionCount == requiredPermissionCount
}

enum class HealthConnectSdkStatus {
    Available,
    NotInstalled,
    NotSupported,
    Unknown,
}

data class DiagnosticRow(
    val label: String,
    val value: String,
    val quality: DiagnosticQuality,
)

data class DataQualityItem(
    val key: String,
    val label: String,
    val hasPermission: Boolean,
    val count1d: Int,
    val count7d: Int,
    val count30d: Int,
    val origins: Set<String>,
    val lastRecordAt: String?,
    val lastRecordAtEpochMs: Long?,
    val quality: DiagnosticQuality,
)

data class DailySyncSummary(
    val activityDays: Int,
    val heartDays: Int,
    val sleepDays: Int,
    val workoutDays: Int,
    val bodyDays: Int,
    val lastSyncedAt: String?,
)

data class HealthDashboard(
    val today: ActivityWindow,
    val last7Days: ActivityWindow,
    val last30Days: ActivityWindow,
    val signalCounts: DashboardSignalCounts,
    val lastSyncedAt: String?,
)

data class LongTermActivitySummary(
    val stepsPerKm: Int,
    val yearly: List<ActivityPeriodSummary>,
    val bestMonth: ActivityPeriodSummary?,
    val recentMonths: List<ActivityPeriodSummary> = emptyList(),
)

data class ActivityPeriodSummary(
    val period: String,
    val steps: Long,
    val estimatedKm: Double,
    val recordedKm: Double,
    val activeDays: Int,
)

data class SleepDomainSummary(
    val latest: SleepDaySummary?,
    val recentDays: List<SleepDaySummary>,
    val recentMonths: List<SleepPeriodSummary>,
    val last30SleepDays: Int,
    val last30AverageMinutes: Double,
)

data class SleepDaySummary(
    val date: String,
    val totalSleepMinutes: Long,
    val sessionCount: Int,
    val source: String,
)

data class SleepPeriodSummary(
    val period: String,
    val totalSleepMinutes: Long,
    val sleepDays: Int,
) {
    val averageMinutes: Double
        get() = if (sleepDays > 0) totalSleepMinutes.toDouble() / sleepDays.toDouble() else 0.0
}

data class SportDomainSummary(
    val stepsPerKm: Int,
    val today: ActivityWindow,
    val last7Days: ActivityWindow,
    val last30Days: ActivityWindow,
    val yearly: List<ActivityPeriodSummary>,
    val recentMonths: List<ActivityPeriodSummary>,
    val bestMonth: ActivityPeriodSummary?,
    val workoutLast30: WorkoutSummary,
    val walkingLast30: WorkoutTypeSummary?,
    val runningLast30: WorkoutTypeSummary?,
    val recentWalkingDays: List<WorkoutTypeDaySummary>,
    val recentRunningDays: List<WorkoutTypeDaySummary>,
)

data class WorkoutSummary(
    val daysWithWorkouts: Int,
    val sessionCount: Int,
    val totalDurationMinutes: Long,
)

data class WorkoutTypeSummary(
    val workoutType: String,
    val daysWithWorkouts: Int,
    val sessionCount: Int,
    val totalDurationMinutes: Long,
    val distanceKm: Double,
    val activeCaloriesKcal: Double,
    val steps: Long,
    val avgHeartRateBpm: Double?,
)

data class WorkoutTypeDaySummary(
    val date: String,
    val workoutType: String,
    val sessionCount: Int,
    val totalDurationMinutes: Long,
    val distanceKm: Double,
    val activeCaloriesKcal: Double,
    val steps: Long,
    val avgHeartRateBpm: Double?,
)

data class BodyDomainSummary(
    val bodyDays: Int,
    val weightRecords: Int,
    val vo2Records: Int,
    val spo2Records: Int,
    val latestDate: String?,
    val latestWeightKg: Double?,
    val latestVo2Max: Double?,
    val latestSpo2Percent: Double?,
)

data class HealthJournalSummary(
    val noteCount: Int,
    val latestNotes: List<HealthNoteEntry>,
)

data class HealthNoteEntry(
    val id: Long,
    val date: String,
    val noteText: String,
    val tags: String,
    val moodScore: Int?,
    val physicalScore: Int?,
    val createdAtEpochMs: Long,
)

data class ActivityWindow(
    val steps: Long,
    val distanceKm: Double,
    val activeCaloriesKcal: Double,
    val daysWithActivity: Int,
)

data class DashboardSignalCounts(
    val heartDays: Int,
    val sleepDays: Int,
    val workoutDays: Int,
    val bodyDays: Int,
)

enum class DiagnosticQuality {
    Good,
    Warning,
    Neutral,
}
