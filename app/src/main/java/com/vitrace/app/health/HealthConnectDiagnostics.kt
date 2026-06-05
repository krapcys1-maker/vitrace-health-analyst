package com.vitrace.app.health

data class HealthConnectDiagnostics(
    val sdkStatus: HealthConnectSdkStatus,
    val grantedPermissionCount: Int = 0,
    val requiredPermissionCount: Int = 0,
    val rows: List<DiagnosticRow> = emptyList(),
    val error: String? = null,
) {
    val hasAllPermissions: Boolean
        get() = requiredPermissionCount > 0 && grantedPermissionCount == requiredPermissionCount
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

enum class DiagnosticQuality {
    Good,
    Warning,
    Neutral,
}

