package com.vitrace.app.analysis

import com.vitrace.app.data.AnalysisResultEntity
import com.vitrace.app.data.MonthlySleepPhaseAggregate
import com.vitrace.app.data.MonthlyWorkoutSessionAggregate
import com.vitrace.app.data.SleepActivityFeatureRow
import com.vitrace.app.data.UserProfileEntity
import com.vitrace.app.data.VitaTraceDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

private const val SLEEP_ACTIVITY_ENGINE_VERSION = "sleep_activity_v1"
private const val CURRENT_SCOPE = "current_snapshot"

data class PersonalAnalysisContext(
    val profile: AnalysisProfile,
    val timeContext: AnalysisTimeContext,
    val monthlySleepPhases: List<MonthlySleepPhaseAnalysis>,
    val sleepActivityComparison: SleepActivityComparison,
    val monthlySportTrends: List<MonthlySportTrend>,
    val currentSleepActivityResult: AnalysisResultEntity?,
)

data class AnalysisProfile(
    val sex: String,
    val ageYears: Int,
    val heightCm: Int,
    val weightKg: Double,
    val stepsPerKm: Int,
)

data class MonthlySleepPhaseAnalysis(
    val period: String,
    val sleepDays: Int,
    val avgTotalSleepMinutes: Double,
    val avgDeepSleepMinutes: Double?,
    val avgLightSleepMinutes: Double?,
    val avgRemSleepMinutes: Double?,
    val avgAwakeMinutes: Double?,
    val avgSleepScore: Double?,
) {
    val deepPercent: Double?
        get() = avgDeepSleepMinutes.percentOf(avgTotalSleepMinutes)

    val lightPercent: Double?
        get() = avgLightSleepMinutes.percentOf(avgTotalSleepMinutes)

    val remPercent: Double?
        get() = avgRemSleepMinutes.percentOf(avgTotalSleepMinutes)
}

data class AnalysisTimeContext(
    val today: String,
    val currentStartDate: String,
    val currentEndDate: String,
    val baselineStartDate: String?,
    val baselineEndDate: String?,
    val recentMaybeIncompleteDays: List<String>,
    val currentPartialDay: String,
) {
    fun stateFor(date: String): DayAnalysisState {
        return when {
            date == currentPartialDay -> DayAnalysisState.CurrentPartial
            recentMaybeIncompleteDays.contains(date) -> DayAnalysisState.RecentMaybeIncomplete
            date < currentStartDate -> DayAnalysisState.HistoricalComplete
            date <= currentEndDate -> DayAnalysisState.CurrentClosed
            else -> DayAnalysisState.FutureOrUnknown
        }
    }
}

enum class DayAnalysisState {
    HistoricalComplete,
    CurrentClosed,
    CurrentPartial,
    RecentMaybeIncomplete,
    MissingData,
    MixedSource,
    FutureOrUnknown,
}

data class SleepActivityComparison(
    val totalSampleDays: Int,
    val highActivityDays: Int,
    val lowerActivityDays: Int,
    val stepThreshold: Long?,
    val highActivity: SleepAverages?,
    val lowerActivity: SleepAverages?,
    val delta: SleepDelta?,
    val confidence: AnalysisConfidence,
    val interpretation: String,
)

data class SleepAverages(
    val avgSteps: Double,
    val avgTotalSleepMinutes: Double,
    val avgDeepSleepMinutes: Double?,
    val avgLightSleepMinutes: Double?,
    val avgRemSleepMinutes: Double?,
    val avgAwakeMinutes: Double?,
    val avgSleepScore: Double?,
)

data class SleepDelta(
    val totalSleepMinutes: Double,
    val deepSleepPercent: Double?,
    val lightSleepPercent: Double?,
    val remSleepPercent: Double?,
    val awakeMinutes: Double?,
    val sleepScore: Double?,
)

data class MonthlySportTrend(
    val period: String,
    val workoutType: String,
    val sessionCount: Int,
    val totalDurationMinutes: Double,
    val distanceKm: Double,
    val activeCaloriesKcal: Double,
    val totalCaloriesKcal: Double?,
    val avgHeartRateBpm: Double?,
    val maxHeartRateBpm: Long?,
    val avgPaceSecondsPerKm: Double?,
    val avgCadence: Double?,
    val avgVo2Max: Double?,
) {
    val activeCaloriesPerKm: Double?
        get() = activeCaloriesKcal.takeIf { distanceKm > 0 }?.div(distanceKm)

    val activeCaloriesPerMinute: Double?
        get() = activeCaloriesKcal.takeIf { totalDurationMinutes > 0 }?.div(totalDurationMinutes)
}

enum class AnalysisConfidence {
    Insufficient,
    Low,
    Medium,
    High,
}

object AnalysisContextBuilder {
    suspend fun build(
        database: VitaTraceDatabase,
        profile: UserProfileEntity,
        now: Instant = Instant.now(),
    ): PersonalAnalysisContext {
        val dao = database.dailySummaryDao()
        val timeContext = buildTimeContext(now)
        val sleepActivityRows = dao.sleepActivityFeatureRows()
            .filter { row -> timeContext.stateFor(row.date) != DayAnalysisState.CurrentPartial }
            .filter { row -> timeContext.stateFor(row.date) != DayAnalysisState.FutureOrUnknown }
        val comparison = buildSleepActivityComparison(sleepActivityRows)
        return PersonalAnalysisContext(
            profile = profile.toAnalysisProfile(),
            timeContext = timeContext,
            monthlySleepPhases = dao.monthlySleepPhases(limit = 12).map { aggregate ->
                aggregate.toAnalysis()
            },
            sleepActivityComparison = comparison,
            monthlySportTrends = dao.monthlyWorkoutSessions(
                workoutTypes = listOf("walking", "running"),
                limit = 24,
            ).map { aggregate -> aggregate.toTrend() },
            currentSleepActivityResult = database.analysisResultDao().current("sleep_activity", CURRENT_SCOPE),
        )
    }

    suspend fun buildAndPersistCurrent(
        database: VitaTraceDatabase,
        profile: UserProfileEntity,
        now: Instant = Instant.now(),
    ): PersonalAnalysisContext {
        val context = build(database, profile, now)
        val result = context.toSleepActivityResult(now)
        val dao = database.analysisResultDao()
        dao.supersedeCurrent(result.analysisType, result.scope, now.toEpochMilli())
        dao.insert(result)
        return context.copy(
            currentSleepActivityResult = dao.current("sleep_activity", CURRENT_SCOPE),
        )
    }

    private fun buildSleepActivityComparison(
        rows: List<SleepActivityFeatureRow>,
    ): SleepActivityComparison {
        val usableRows = rows
            .filter { row -> row.steps > 0 && row.totalSleepMinutes > 0 }
            .sortedBy { row -> row.steps }

        if (usableRows.size < 14) {
            return SleepActivityComparison(
                totalSampleDays = usableRows.size,
                highActivityDays = 0,
                lowerActivityDays = 0,
                stepThreshold = null,
                highActivity = null,
                lowerActivity = null,
                delta = null,
                confidence = AnalysisConfidence.Insufficient,
                interpretation = "za malo wspolnych dni snu i aktywnosci do porownania",
            )
        }

        val threshold = medianSteps(usableRows)
        val lower = usableRows.filter { row -> row.steps < threshold }
        val high = usableRows.filter { row -> row.steps >= threshold }
        val confidence = confidenceForGroups(high.size, lower.size)
        val highAverages = high.sleepAverages()
        val lowerAverages = lower.sleepAverages()
        val delta = highAverages.deltaAgainst(lowerAverages)

        return SleepActivityComparison(
            totalSampleDays = usableRows.size,
            highActivityDays = high.size,
            lowerActivityDays = lower.size,
            stepThreshold = threshold,
            highActivity = highAverages,
            lowerActivity = lowerAverages,
            delta = delta,
            confidence = confidence,
            interpretation = interpretationFor(delta, confidence),
        )
    }

    private fun buildTimeContext(now: Instant): AnalysisTimeContext {
        val today = LocalDate.ofInstant(now, ZoneId.systemDefault())
        val currentEnd = today.minusDays(1)
        val currentStart = currentEnd.minusDays(29)
        val baselineEnd = currentStart.minusDays(1)
        return AnalysisTimeContext(
            today = today.toString(),
            currentStartDate = currentStart.toString(),
            currentEndDate = currentEnd.toString(),
            baselineStartDate = null,
            baselineEndDate = baselineEnd.toString(),
            recentMaybeIncompleteDays = listOf(currentEnd.toString()),
            currentPartialDay = today.toString(),
        )
    }
}

private fun PersonalAnalysisContext.toSleepActivityResult(now: Instant): AnalysisResultEntity {
    val comparison = sleepActivityComparison
    val delta = comparison.delta
    val summary = if (delta == null) {
        comparison.interpretation
    } else {
        "prog ${comparison.stepThreshold ?: 0} krokow; sen ${delta.totalSleepMinutes.formatSigned0()} min; " +
            "REM ${delta.remSleepPercent.formatSignedPercentJson()}; " +
            "gleboki ${delta.deepSleepPercent.formatSignedPercentJson()}; " +
            "plytki ${delta.lightSleepPercent.formatSignedPercentJson()}"
    }
    return AnalysisResultEntity(
        analysisType = "sleep_activity",
        scope = CURRENT_SCOPE,
        engineVersion = SLEEP_ACTIVITY_ENGINE_VERSION,
        baselineStartDate = timeContext.baselineStartDate,
        baselineEndDate = timeContext.baselineEndDate,
        currentStartDate = timeContext.currentStartDate,
        currentEndDate = timeContext.currentEndDate,
        generatedForDate = timeContext.today,
        summaryTitle = "Ruch a sen",
        summaryText = summary,
        confidence = comparison.confidence.name,
        sampleSize = comparison.totalSampleDays,
        resultJson = comparison.toJson(),
        sourceCoverageJson = sourceCoverageJson(),
        timeContextJson = timeContext.toJson(),
        isCurrent = true,
        pinned = false,
        createdAtEpochMs = now.toEpochMilli(),
        updatedAtEpochMs = now.toEpochMilli(),
        supersededAtEpochMs = null,
    )
}

private fun UserProfileEntity.toAnalysisProfile(): AnalysisProfile {
    return AnalysisProfile(
        sex = sex,
        ageYears = ageYears,
        heightCm = heightCm,
        weightKg = weightKg,
        stepsPerKm = stepsPerKm,
    )
}

private fun MonthlySleepPhaseAggregate.toAnalysis(): MonthlySleepPhaseAnalysis {
    return MonthlySleepPhaseAnalysis(
        period = period,
        sleepDays = sleepDays,
        avgTotalSleepMinutes = avgTotalSleepMinutes,
        avgDeepSleepMinutes = avgDeepSleepMinutes,
        avgLightSleepMinutes = avgLightSleepMinutes,
        avgRemSleepMinutes = avgRemSleepMinutes,
        avgAwakeMinutes = avgAwakeMinutes,
        avgSleepScore = avgSleepScore,
    )
}

private fun MonthlyWorkoutSessionAggregate.toTrend(): MonthlySportTrend {
    return MonthlySportTrend(
        period = period,
        workoutType = workoutType,
        sessionCount = sessionCount,
        totalDurationMinutes = totalDurationSeconds.toDouble() / 60.0,
        distanceKm = distanceMeters / 1000.0,
        activeCaloriesKcal = activeCaloriesKcal,
        totalCaloriesKcal = totalCaloriesKcal,
        avgHeartRateBpm = avgHeartRateBpm,
        maxHeartRateBpm = maxHeartRateBpm,
        avgPaceSecondsPerKm = avgPaceSecondsPerKm,
        avgCadence = avgCadence,
        avgVo2Max = avgVo2Max,
    )
}

private fun List<SleepActivityFeatureRow>.sleepAverages(): SleepAverages {
    return SleepAverages(
        avgSteps = map { row -> row.steps }.average(),
        avgTotalSleepMinutes = map { row -> row.totalSleepMinutes }.average(),
        avgDeepSleepMinutes = mapNotNull { row -> row.deepSleepMinutes }.averageOrNull(),
        avgLightSleepMinutes = mapNotNull { row -> row.lightSleepMinutes }.averageOrNull(),
        avgRemSleepMinutes = mapNotNull { row -> row.remSleepMinutes }.averageOrNull(),
        avgAwakeMinutes = mapNotNull { row -> row.awakeMinutes }.averageOrNull(),
        avgSleepScore = mapNotNull { row -> row.sleepScore }.averageOrNull(),
    )
}

private fun SleepAverages.deltaAgainst(baseline: SleepAverages): SleepDelta {
    return SleepDelta(
        totalSleepMinutes = avgTotalSleepMinutes - baseline.avgTotalSleepMinutes,
        deepSleepPercent = avgDeepSleepMinutes.percentDeltaAgainst(baseline.avgDeepSleepMinutes),
        lightSleepPercent = avgLightSleepMinutes.percentDeltaAgainst(baseline.avgLightSleepMinutes),
        remSleepPercent = avgRemSleepMinutes.percentDeltaAgainst(baseline.avgRemSleepMinutes),
        awakeMinutes = avgAwakeMinutes.minusNullable(baseline.avgAwakeMinutes),
        sleepScore = avgSleepScore.minusNullable(baseline.avgSleepScore),
    )
}

private fun medianSteps(rows: List<SleepActivityFeatureRow>): Long {
    val middle = rows.size / 2
    return if (rows.size % 2 == 0) {
        ((rows[middle - 1].steps + rows[middle].steps) / 2.0).toLong()
    } else {
        rows[middle].steps
    }
}

private fun confidenceForGroups(
    highCount: Int,
    lowerCount: Int,
): AnalysisConfidence {
    val smallest = minOf(highCount, lowerCount)
    val total = highCount + lowerCount
    return when {
        smallest < 7 || total < 14 -> AnalysisConfidence.Insufficient
        smallest >= 45 && total >= 90 -> AnalysisConfidence.High
        smallest >= 15 && total >= 30 -> AnalysisConfidence.Medium
        else -> AnalysisConfidence.Low
    }
}

private fun interpretationFor(
    delta: SleepDelta,
    confidence: AnalysisConfidence,
): String {
    if (confidence == AnalysisConfidence.Insufficient) {
        return "za mala probka, nie pokazujemy zaleznosci jako wniosku"
    }

    val meaningfulSignals = listOfNotNull(
        delta.totalSleepMinutes.takeIf { minutes -> abs(minutes) >= 15.0 },
        delta.deepSleepPercent?.takeIf { percent -> abs(percent) >= 5.0 },
        delta.remSleepPercent?.takeIf { percent -> abs(percent) >= 5.0 },
        delta.sleepScore?.takeIf { score -> abs(score) >= 3.0 },
    )

    return if (meaningfulSignals.isEmpty()) {
        "aktywnosc i sen wygladaja podobnie w obu grupach; trend jest slaby"
    } else {
        "w wyzszej aktywnosci widac powtarzalna roznice snu; interpretuj z poziomem pewnosci"
    }
}

private fun SleepActivityComparison.toJson(): String {
    val delta = delta
    return """
        {
          "analysisType": "sleep_activity",
          "stepThreshold": ${stepThreshold.jsonNumber()},
          "sample": {
            "totalDays": $totalSampleDays,
            "highActivityDays": $highActivityDays,
            "lowerActivityDays": $lowerActivityDays
          },
          "confidence": "${confidence.name}",
          "highActivity": ${highActivity.toJson()},
          "lowerActivity": ${lowerActivity.toJson()},
          "delta": ${delta.toJson()},
          "interpretation": "${interpretation.escapeJson()}"
        }
    """.trimIndent()
}

private fun SleepAverages?.toJson(): String {
    if (this == null) {
        return "null"
    }
    return """
        {
          "avgSteps": ${avgSteps.jsonNumber()},
          "avgTotalSleepMinutes": ${avgTotalSleepMinutes.jsonNumber()},
          "avgDeepSleepMinutes": ${avgDeepSleepMinutes.jsonNumber()},
          "avgLightSleepMinutes": ${avgLightSleepMinutes.jsonNumber()},
          "avgRemSleepMinutes": ${avgRemSleepMinutes.jsonNumber()},
          "avgAwakeMinutes": ${avgAwakeMinutes.jsonNumber()},
          "avgSleepScore": ${avgSleepScore.jsonNumber()}
        }
    """.trimIndent()
}

private fun SleepDelta?.toJson(): String {
    if (this == null) {
        return "null"
    }
    return """
        {
          "totalSleepMinutes": ${totalSleepMinutes.jsonNumber()},
          "deepSleepPercent": ${deepSleepPercent.jsonNumber()},
          "lightSleepPercent": ${lightSleepPercent.jsonNumber()},
          "remSleepPercent": ${remSleepPercent.jsonNumber()},
          "awakeMinutes": ${awakeMinutes.jsonNumber()},
          "sleepScore": ${sleepScore.jsonNumber()}
        }
    """.trimIndent()
}

private fun PersonalAnalysisContext.sourceCoverageJson(): String {
    return """
        {
          "historicalSource": "MI_FITNESS_EXPORT",
          "liveSource": "HEALTH_CONNECT",
          "sleepPhaseMonths": ${monthlySleepPhases.size},
          "sleepActivityDays": ${sleepActivityComparison.totalSampleDays},
          "sportTrendRows": ${monthlySportTrends.size},
          "note": "History is richer than current live Health Connect coverage."
        }
    """.trimIndent()
}

private fun AnalysisTimeContext.toJson(): String {
    return """
        {
          "today": "$today",
          "currentPartialDay": "$currentPartialDay",
          "currentStartDate": "$currentStartDate",
          "currentEndDate": "$currentEndDate",
          "baselineStartDate": ${baselineStartDate.jsonString()},
          "baselineEndDate": ${baselineEndDate.jsonString()},
          "recentMaybeIncompleteDays": [${recentMaybeIncompleteDays.joinToString(",") { day -> day.jsonString() }}]
        }
    """.trimIndent()
}

private fun Double?.percentOf(total: Double): Double? {
    if (this == null || total <= 0.0) {
        return null
    }
    return this / total * 100.0
}

private fun Double?.percentDeltaAgainst(baseline: Double?): Double? {
    if (this == null || baseline == null || baseline == 0.0) {
        return null
    }
    return (this - baseline) / baseline * 100.0
}

private fun Double?.minusNullable(baseline: Double?): Double? {
    if (this == null || baseline == null) {
        return null
    }
    return this - baseline
}

private fun <T : Number> List<T>.averageOrNull(): Double? {
    if (isEmpty()) {
        return null
    }
    return map { number -> number.toDouble() }.average()
}

private fun Number?.jsonNumber(): String {
    return this?.toString() ?: "null"
}

private fun String?.jsonString(): String {
    return this?.let { value -> "\"${value.escapeJson()}\"" } ?: "null"
}

private fun String.escapeJson(): String {
    return buildString {
        this@escapeJson.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

private fun Double.formatSigned0(): String {
    return "%+.0f".format(this)
}

private fun Double?.formatSignedPercentJson(): String {
    return this?.let { value -> "%+.0f%%".format(value) } ?: "brak"
}
