package com.vitrace.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateRequest
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

object HealthConnectDiagnosticsRepository {
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    fun getSdkStatus(context: Context): HealthConnectSdkStatus {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectSdkStatus.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectSdkStatus.NotInstalled
            HealthConnectClient.SDK_UNAVAILABLE -> HealthConnectSdkStatus.NotSupported
            else -> HealthConnectSdkStatus.Unknown
        }
    }

    suspend fun load(context: Context): HealthConnectDiagnostics {
        val sdkStatus = getSdkStatus(context)
        if (sdkStatus != HealthConnectSdkStatus.Available) {
            return HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                requiredPermissionCount = requiredPermissions.size,
            )
        }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(7))

        if (!granted.containsAll(requiredPermissions)) {
            return HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                grantedPermissionCount = granted.intersect(requiredPermissions).size,
                requiredPermissionCount = requiredPermissions.size,
                rows = listOf(
                    DiagnosticRow(
                        label = "Permissions",
                        value = "${granted.intersect(requiredPermissions).size}/${requiredPermissions.size}",
                        quality = DiagnosticQuality.Warning,
                    )
                ),
            )
        }

        return try {
            val rows = buildList {
                val aggregate = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(
                            StepsRecord.COUNT_TOTAL,
                            DistanceRecord.DISTANCE_TOTAL,
                            TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        ),
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                    )
                )

                add(
                    DiagnosticRow(
                        label = "Steps, 7 days",
                        value = (aggregate[StepsRecord.COUNT_TOTAL] ?: 0L).toString(),
                        quality = DiagnosticQuality.Good,
                    )
                )
                add(
                    DiagnosticRow(
                        label = "Distance, 7 days",
                        value = "${((aggregate[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0) / 1000.0).format1()} km",
                        quality = DiagnosticQuality.Good,
                    )
                )
                add(
                    DiagnosticRow(
                        label = "Calories, 7 days",
                        value = "${(aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0).format0()} kcal",
                        quality = DiagnosticQuality.Good,
                    )
                )
                add(
                    DiagnosticRow(
                        label = "Heart records, 7 days",
                        value = countRecords(client, HeartRateRecord::class, start, end).toString(),
                        quality = DiagnosticQuality.Neutral,
                    )
                )
                add(
                    DiagnosticRow(
                        label = "Sleep sessions, 7 days",
                        value = countRecords(client, SleepSessionRecord::class, start, end).toString(),
                        quality = DiagnosticQuality.Neutral,
                    )
                )
                add(
                    DiagnosticRow(
                        label = "Exercise sessions, 7 days",
                        value = countRecords(client, ExerciseSessionRecord::class, start, end).toString(),
                        quality = DiagnosticQuality.Neutral,
                    )
                )
                add(
                    DiagnosticRow(
                        label = "Data origins",
                        value = readDataOrigins(client, start, end).joinToString().ifBlank { "none" },
                        quality = DiagnosticQuality.Neutral,
                    )
                )
            }

            HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                grantedPermissionCount = requiredPermissions.size,
                requiredPermissionCount = requiredPermissions.size,
                rows = rows,
            )
        } catch (error: Exception) {
            HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                grantedPermissionCount = requiredPermissions.size,
                requiredPermissionCount = requiredPermissions.size,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> countRecords(
        client: HealthConnectClient,
        type: KClass<T>,
        start: Instant,
        end: Instant,
    ): Int {
        var count = 0
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken,
                )
            )
            count += response.records.size
            pageToken = response.pageToken
        } while (pageToken != null)
        return count
    }

    private suspend fun readDataOrigins(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): Set<String> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )
        return response.records
            .mapNotNull { record -> record.metadata.dataOrigin.packageName }
            .toSet()
    }

    private fun Double.format0(): String = "%,.0f".format(this)

    private fun Double.format1(): String = "%,.1f".format(this)
}

