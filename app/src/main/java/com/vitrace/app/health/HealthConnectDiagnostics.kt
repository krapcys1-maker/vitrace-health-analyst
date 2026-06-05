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
)

data class ActivityPeriodSummary(
    val period: String,
    val steps: Long,
    val estimatedKm: Double,
    val recordedKm: Double,
    val activeDays: Int,
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
