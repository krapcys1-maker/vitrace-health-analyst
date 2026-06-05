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
import com.vitrace.app.data.DailyActivitySummaryEntity
import com.vitrace.app.data.DailyBodySummaryEntity
import com.vitrace.app.data.DailyHeartSummaryEntity
import com.vitrace.app.data.DailySleepSummaryEntity
import com.vitrace.app.data.DailyWorkoutSummaryEntity
import com.vitrace.app.data.DashboardActivityAggregate
import com.vitrace.app.data.HealthConnectQualitySnapshotEntity
import com.vitrace.app.data.VitaTraceDatabase
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

    suspend fun load(
        context: Context,
        syncFromHealthConnect: Boolean,
    ): HealthConnectDiagnostics {
        val database = VitaTraceDatabase.get(context)
        val sdkStatus = getSdkStatus(context)
        val end = Instant.now()

        if (!syncFromHealthConnect) {
            return HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                requiredPermissionCount = requiredPermissions.size,
                dashboard = database.loadDashboard(end),
                savedSnapshotCount = database.healthConnectQualitySnapshotDao().count(),
                dailySyncSummary = database.loadDailySyncSummary(),
            )
        }

        if (sdkStatus != HealthConnectSdkStatus.Available) {
            return HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                requiredPermissionCount = requiredPermissions.size,
                savedSnapshotCount = database.healthConnectQualitySnapshotDao().count(),
                dailySyncSummary = database.loadDailySyncSummary(),
                dashboard = database.loadDashboard(end),
            )
        }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val grantedRequired = granted.intersect(requiredPermissions)

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
                dailySyncSummary = database.loadDailySyncSummary(),
                dashboard = database.loadDashboard(end),
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
            syncDailySummaries(client, database, end)

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
                dashboard = database.loadDashboard(end),
                savedSnapshotCount = database.healthConnectQualitySnapshotDao().count(),
                dailySyncSummary = database.loadDailySyncSummary(),
            )
        } catch (error: Exception) {
            HealthConnectDiagnostics(
                sdkStatus = sdkStatus,
                grantedPermissionCount = requiredPermissions.size,
                requiredPermissionCount = requiredPermissions.size,
                savedSnapshotCount = database.healthConnectQualitySnapshotDao().count(),
                dailySyncSummary = database.loadDailySyncSummary(),
                dashboard = database.loadDashboard(end),
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

    private suspend fun syncDailySummaries(
        client: HealthConnectClient,
        database: VitaTraceDatabase,
        end: Instant,
    ) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dates = (29L downTo 0L).map { offset -> today.minusDays(offset) }
        val syncedAt = end.toEpochMilli()

        val activity = mutableListOf<DailyActivitySummaryEntity>()
        val heart = mutableListOf<DailyHeartSummaryEntity>()
        val sleep = mutableListOf<DailySleepSummaryEntity>()
        val workouts = mutableListOf<DailyWorkoutSummaryEntity>()
        val body = mutableListOf<DailyBodySummaryEntity>()

        dates.forEach { date ->
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            activity += loadDailyActivity(client, date, dayStart, dayEnd, syncedAt)
            heart += loadDailyHeart(client, date, dayStart, dayEnd, syncedAt)
            sleep += loadDailySleep(client, date, dayStart, dayEnd, syncedAt)
            workouts += loadDailyWorkouts(client, date, dayStart, dayEnd, syncedAt)
            body += loadDailyBody(client, date, dayStart, dayEnd, syncedAt)
        }

        val dao = database.dailySummaryDao()
        dao.upsertActivity(activity)
        dao.upsertHeart(heart)
        dao.upsertSleep(sleep)
        dao.upsertWorkouts(workouts)
        dao.upsertBody(body)
    }

    private suspend fun loadDailyActivity(
        client: HealthConnectClient,
        date: LocalDate,
        start: Instant,
        end: Instant,
        syncedAt: Long,
    ): DailyActivitySummaryEntity {
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
        val stepOrigins = readRecordSummary(client, StepsRecord::class, start, end).origins
        val calorieOrigins = readRecordSummary(client, ActiveCaloriesBurnedRecord::class, start, end).origins
        val origins = stepOrigins + calorieOrigins

        return DailyActivitySummaryEntity(
            date = date.toString(),
            steps = aggregate[StepsRecord.COUNT_TOTAL] ?: 0L,
            distanceMeters = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0,
            activeCaloriesKcal = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            source = origins.sourceLabel(),
            syncedAtEpochMs = syncedAt,
        )
    }

    private suspend fun loadDailyHeart(
        client: HealthConnectClient,
        date: LocalDate,
        start: Instant,
        end: Instant,
        syncedAt: Long,
    ): DailyHeartSummaryEntity {
        val records = readAllRecords(client, HeartRateRecord::class, start, end)
        val samples = records.flatMap { record -> record.samples }
        val bpms = samples.map { sample -> sample.beatsPerMinute }
        val lastRecordAt = samples.maxOfOrNull { sample -> sample.time }

        return DailyHeartSummaryEntity(
            date = date.toString(),
            sampleCount = samples.size,
            minBpm = bpms.minOrNull(),
            maxBpm = bpms.maxOrNull(),
            avgBpm = bpms.takeIf { it.isNotEmpty() }?.average(),
            source = records.origins().sourceLabel(),
            lastRecordAtEpochMs = lastRecordAt?.toEpochMilli(),
            syncedAtEpochMs = syncedAt,
        )
    }

    private suspend fun loadDailySleep(
        client: HealthConnectClient,
        date: LocalDate,
        start: Instant,
        end: Instant,
        syncedAt: Long,
    ): DailySleepSummaryEntity {
        val records = readAllRecords(client, SleepSessionRecord::class, start, end)
        val totalMinutes = records.sumOf { record -> overlapMinutes(record.startTime, record.endTime, start, end) }
        val lastRecordAt = records.maxOfOrNull { record -> record.endTime }

        return DailySleepSummaryEntity(
            date = date.toString(),
            sessionCount = records.size,
            totalSleepMinutes = totalMinutes,
            source = records.origins().sourceLabel(),
            lastRecordAtEpochMs = lastRecordAt?.toEpochMilli(),
            syncedAtEpochMs = syncedAt,
        )
    }

    private suspend fun loadDailyWorkouts(
        client: HealthConnectClient,
        date: LocalDate,
        start: Instant,
        end: Instant,
        syncedAt: Long,
    ): DailyWorkoutSummaryEntity {
        val records = readAllRecords(client, ExerciseSessionRecord::class, start, end)
        val totalMinutes = records.sumOf { record -> overlapMinutes(record.startTime, record.endTime, start, end) }
        val lastRecordAt = records.maxOfOrNull { record -> record.endTime }

        return DailyWorkoutSummaryEntity(
            date = date.toString(),
            sessionCount = records.size,
            totalDurationMinutes = totalMinutes,
            source = records.origins().sourceLabel(),
            lastRecordAtEpochMs = lastRecordAt?.toEpochMilli(),
            syncedAtEpochMs = syncedAt,
        )
    }

    private suspend fun loadDailyBody(
        client: HealthConnectClient,
        date: LocalDate,
        start: Instant,
        end: Instant,
        syncedAt: Long,
    ): DailyBodySummaryEntity {
        val weights = readAllRecords(client, WeightRecord::class, start, end)
        val vo2Max = readAllRecords(client, Vo2MaxRecord::class, start, end)
        val spo2 = readAllRecords(client, OxygenSaturationRecord::class, start, end)
        val latestWeight = weights.maxByOrNull { record -> record.time }
        val latestVo2 = vo2Max.maxByOrNull { record -> record.time }
        val latestSpo2 = spo2.maxByOrNull { record -> record.time }
        val lastRecordAt = listOfNotNull(
            latestWeight?.time,
            latestVo2?.time,
            latestSpo2?.time,
        ).maxOrNull()
        val origins = weights.origins() + vo2Max.origins() + spo2.origins()

        return DailyBodySummaryEntity(
            date = date.toString(),
            latestWeightKg = latestWeight?.weight?.inKilograms,
            latestVo2Max = latestVo2?.vo2MillilitersPerMinuteKilogram,
            latestSpo2Percent = latestSpo2?.percentage?.value,
            weightRecordCount = weights.size,
            vo2MaxRecordCount = vo2Max.size,
            spo2RecordCount = spo2.size,
            source = origins.sourceLabel(),
            lastRecordAtEpochMs = lastRecordAt?.toEpochMilli(),
            syncedAtEpochMs = syncedAt,
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

    private suspend fun <T : Record> readAllRecords(
        client: HealthConnectClient,
        type: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken,
                )
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
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

    private suspend fun VitaTraceDatabase.loadDailySyncSummary(): DailySyncSummary {
        val dao = dailySummaryDao()
        val lastSyncedAt = dao.lastSyncedAtEpochMs()
            ?.let { epochMs -> Instant.ofEpochMilli(epochMs).formatLocal() }

        return DailySyncSummary(
            activityDays = dao.activityDays(),
            heartDays = dao.heartDays(),
            sleepDays = dao.sleepDays(),
            workoutDays = dao.workoutDays(),
            bodyDays = dao.bodyDays(),
            lastSyncedAt = lastSyncedAt,
        )
    }

    private suspend fun VitaTraceDatabase.loadDashboard(now: Instant): HealthDashboard {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dao = dailySummaryDao()
        val todayActivity = dao.activityForDate(today.toString())
        val last7Days = dao.activitySince(today.minusDays(6).toString())
        val last30Days = dao.activitySince(today.minusDays(29).toString())
        val signals = dao.signalCountsSince(today.minusDays(29).toString())
        val lastSyncedAt = dao.lastSyncedAtEpochMs()
            ?.let { epochMs -> Instant.ofEpochMilli(epochMs).formatLocal() }

        return HealthDashboard(
            today = todayActivity.toActivityWindow(),
            last7Days = last7Days.toActivityWindow(),
            last30Days = last30Days.toActivityWindow(),
            signalCounts = DashboardSignalCounts(
                heartDays = signals.heartDays,
                sleepDays = signals.sleepDays,
                workoutDays = signals.workoutDays,
                bodyDays = signals.bodyDays,
            ),
            lastSyncedAt = lastSyncedAt ?: now.formatLocal(),
        )
    }

    private fun DashboardActivityAggregate.toActivityWindow(): ActivityWindow {
        return ActivityWindow(
            steps = steps,
            distanceKm = distanceMeters / 1000.0,
            activeCaloriesKcal = activeCaloriesKcal,
            daysWithActivity = daysWithActivity,
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

    private fun List<Record>.origins(): Set<String> {
        return map { record -> record.metadata.dataOrigin.packageName }.toSet()
    }

    private fun Set<String>.sourceLabel(): String {
        return if (isEmpty()) "none" else joinToString()
    }

    private fun overlapMinutes(
        recordStart: Instant,
        recordEnd: Instant,
        dayStart: Instant,
        dayEnd: Instant,
    ): Long {
        val start = maxOf(recordStart, dayStart)
        val end = minOf(recordEnd, dayEnd)
        return if (end.isAfter(start)) ChronoUnit.MINUTES.between(start, end) else 0L
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
