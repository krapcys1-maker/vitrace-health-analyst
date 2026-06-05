package com.vitrace.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.vitrace.app.data.HealthConnectQualitySnapshotEntity
import com.vitrace.app.data.VitaTraceDatabase
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

object HealthConnectDiagnosticsRepository {
    private val metricSpecs = listOf(
        MetricSpec("steps", "Steps", StepsRecord::class),
        MetricSpec("distance", "Distance", DistanceRecord::class),
        MetricSpec("active_calories", "Active calories", ActiveCaloriesBurnedRecord::class),
        MetricSpec("heart_rate", "Heart rate", HeartRateRecord::class),
        MetricSpec("sleep", "Sleep", SleepSessionRecord::class),
        MetricSpec("exercise", "Exercise", ExerciseSessionRecord::class),
        MetricSpec("vo2_max", "VO2 max", Vo2MaxRecord::class),
        MetricSpec("spo2", "SpO2", OxygenSaturationRecord::class),
        MetricSpec("weight", "Weight", WeightRecord::class),
    )

    val requiredPermissions: Set<String> = metricSpecs
        .map { metric -> metric.permission }
        .toSet()

    fun getSdkStatus(context: Context): HealthConnectSdkStatus {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectSdkStatus.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectSdkStatus.NotInstalled
            HealthConnectClient.SDK_UNAVAILABLE -> HealthConnectSdkStatus.NotSupported
            else -> HealthConnectSdkStatus.Unknown
        }
    }

    suspend fun load(context: Context): HealthConnectDiagnostics {
        val database = VitaTraceDatabase.get(context)
        val sdkStatus = getSdkStatus(context)
        if (sdkStatus != HealthConnectSdkStatus.Available) {
            return HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                requiredPermissionCount = requiredPermissions.size,
                savedSnapshotCount = database.healthConnectQualitySnapshotDao().count(),
            )
        }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val grantedRequired = granted.intersect(requiredPermissions)
        val end = Instant.now()

        if (!granted.containsAll(requiredPermissions)) {
            return HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                grantedPermissionCount = grantedRequired.size,
                requiredPermissionCount = requiredPermissions.size,
                rows = listOf(
                    DiagnosticRow(
                        label = "Permissions",
                        value = "${grantedRequired.size}/${requiredPermissions.size}",
                        quality = DiagnosticQuality.Warning,
                    )
                ),
                dataQualityItems = metricSpecs.map { metric ->
                    metric.emptyQualityItem(hasPermission = granted.contains(metric.permission))
                },
                savedSnapshotCount = database.healthConnectQualitySnapshotDao().count(),
            )
        }

        return try {
            val qualityItems = metricSpecs.map { metric ->
                metric.loadQualityItem(client, end)
            }
            val snapshots = qualityItems.map { item ->
                item.toSnapshot(capturedAt = end)
            }
            database.healthConnectQualitySnapshotDao().insertAll(snapshots)

            val rows = buildList {
                addRangeSummary(client, end, days = 1)
                addRangeSummary(client, end, days = 7)
                addRangeSummary(client, end, days = 30)
                add(
                    DiagnosticRow(
                        label = "Quality snapshots",
                        value = database.healthConnectQualitySnapshotDao().count().toString(),
                        quality = DiagnosticQuality.Neutral,
                    )
                )
            }

            HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                grantedPermissionCount = requiredPermissions.size,
                requiredPermissionCount = requiredPermissions.size,
                rows = rows,
                dataQualityItems = qualityItems,
                savedSnapshotCount = database.healthConnectQualitySnapshotDao().count(),
            )
        } catch (error: Exception) {
            HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                grantedPermissionCount = requiredPermissions.size,
                requiredPermissionCount = requiredPermissions.size,
                savedSnapshotCount = database.healthConnectQualitySnapshotDao().count(),
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun MutableList<DiagnosticRow>.addRangeSummary(
        client: HealthConnectClient,
        end: Instant,
        days: Long,
    ) {
        val start = end.minus(Duration.ofDays(days))
        val aggregate = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )
        val suffix = "${days}d"

        add(
            DiagnosticRow(
                label = "Steps, $suffix",
                value = (aggregate[StepsRecord.COUNT_TOTAL] ?: 0L).toString(),
                quality = DiagnosticQuality.Good,
            )
        )
        add(
            DiagnosticRow(
                label = "Distance, $suffix",
                value = "${((aggregate[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0) / 1000.0).format1()} km",
                quality = DiagnosticQuality.Good,
            )
        )
        add(
            DiagnosticRow(
                label = "Active calories, $suffix",
                value = "${(aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0).format0()} kcal",
                quality = DiagnosticQuality.Good,
            )
        )
    }

    private suspend fun MetricSpec<out Record>.loadQualityItem(
        client: HealthConnectClient,
        end: Instant,
    ): DataQualityItem {
        val count1d = countRecords(client, type, end.minus(Duration.ofDays(1)), end)
        val count7d = countRecords(client, type, end.minus(Duration.ofDays(7)), end)
        val summary30d = readRecordSummary(client, type, end.minus(Duration.ofDays(30)), end)
        val quality = when {
            summary30d.count > 0 -> DiagnosticQuality.Good
            else -> DiagnosticQuality.Warning
        }

        return DataQualityItem(
            key = key,
            label = label,
            hasPermission = true,
            count1d = count1d,
            count7d = count7d,
            count30d = summary30d.count,
            origins = summary30d.origins,
            lastRecordAt = summary30d.lastRecordAt?.formatLocal(),
            lastRecordAtEpochMs = summary30d.lastRecordAt?.toEpochMilli(),
            quality = quality,
        )
    }

    private fun MetricSpec<out Record>.emptyQualityItem(hasPermission: Boolean): DataQualityItem {
        return DataQualityItem(
            key = key,
            label = label,
            hasPermission = hasPermission,
            count1d = 0,
            count7d = 0,
            count30d = 0,
            origins = emptySet(),
            lastRecordAt = null,
            lastRecordAtEpochMs = null,
            quality = if (hasPermission) DiagnosticQuality.Neutral else DiagnosticQuality.Warning,
        )
    }

    private suspend fun countRecords(
        client: HealthConnectClient,
        type: KClass<out Record>,
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

    private suspend fun readRecordSummary(
        client: HealthConnectClient,
        type: KClass<out Record>,
        start: Instant,
        end: Instant,
    ): RecordSummary {
        var count = 0
        val origins = mutableSetOf<String>()
        var lastRecordAt: Instant? = null
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
            response.records.forEach { record ->
                record.metadata.dataOrigin.packageName.let(origins::add)
                val candidate = record.latestMeasurementTime()
                if (candidate != null && (lastRecordAt == null || candidate.isAfter(lastRecordAt))) {
                    lastRecordAt = candidate
                }
            }
            pageToken = response.pageToken
        } while (pageToken != null)

        return RecordSummary(
            count = count,
            origins = origins,
            lastRecordAt = lastRecordAt,
        )
    }

    private fun DataQualityItem.toSnapshot(capturedAt: Instant): HealthConnectQualitySnapshotEntity {
        return HealthConnectQualitySnapshotEntity(
            capturedAtEpochMs = capturedAt.toEpochMilli(),
            metricKey = key,
            label = label,
            hasPermission = hasPermission,
            count1d = count1d,
            count7d = count7d,
            count30d = count30d,
            origins = origins.joinToString(),
            lastRecordAtEpochMs = lastRecordAtEpochMs,
            status = quality.name,
        )
    }

    private fun Record.latestMeasurementTime(): Instant? {
        return when (this) {
            is ActiveCaloriesBurnedRecord -> endTime
            is DistanceRecord -> endTime
            is ExerciseSessionRecord -> endTime
            is HeartRateRecord -> samples.maxOfOrNull { sample -> sample.time }
            is OxygenSaturationRecord -> time
            is SleepSessionRecord -> endTime
            is StepsRecord -> endTime
            is Vo2MaxRecord -> time
            is WeightRecord -> time
            else -> null
        }
    }

    private fun Instant.formatLocal(): String {
        return DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(this)
    }

    private fun Double.format0(): String = "%,.0f".format(this)

    private fun Double.format1(): String = "%,.1f".format(this)
}

private data class MetricSpec<T : Record>(
    val key: String,
    val label: String,
    val type: KClass<T>,
) {
    val permission: String = HealthPermission.getReadPermission(type)
}

private data class RecordSummary(
    val count: Int,
    val origins: Set<String>,
    val lastRecordAt: Instant?,
)
