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
private const val SPORT_EFFICIENCY_ENGINE_VERSION = "sport_efficiency_v1"
private const val CURRENT_SCOPE = "current_snapshot"
private const val CURRENT_SNAPSHOT_RETENTION = 5

data class PersonalAnalysisContext(
    val profile: AnalysisProfile,
    val timeContext: AnalysisTimeContext,
    val monthlySleepPhases: List<MonthlySleepPhaseAnalysis>,
    val sleepActivityComparison: SleepActivityComparison,
    val monthlySportTrends: List<MonthlySportTrend>,
    val sportEfficiencyComparisons: List<SportEfficiencyComparison>,
    val currentSleepActivityResult: AnalysisResultEntity?,
    val currentSportEfficiencyResult: AnalysisResultEntity?,
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

data class SportEfficiencyComparison(
    val workoutType: String,
    val current: MonthlySportTrend?,
    val previous: MonthlySportTrend?,
    val delta: SportEfficiencyDelta?,
    val confidence: AnalysisConfidence,
    val interpretation: String,
)

data class SportEfficiencyDelta(
    val sessionCount: Int,
    val totalDurationMinutes: Double,
    val distanceKm: Double,
    val activeCaloriesKcal: Double,
    val avgHeartRateBpm: Double?,
    val avgPaceSecondsPerKm: Double?,
    val activeCaloriesPerKm: Double?,
    val activeCaloriesPerMinute: Double?,
    val avgCadence: Double?,
    val avgVo2Max: Double?,
)

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
        val monthlySportTrends = dao.monthlyWorkoutSessions(
            workoutTypes = listOf("walking", "running"),
            limit = 24,
        ).map { aggregate -> aggregate.toTrend() }
        val sportEfficiencyComparisons = buildSportEfficiencyComparisons(monthlySportTrends, timeContext)
        return PersonalAnalysisContext(
            profile = profile.toAnalysisProfile(),
            timeContext = timeContext,
            monthlySleepPhases = dao.monthlySleepPhases(limit = 12).map { aggregate ->
                aggregate.toAnalysis()
            },
            sleepActivityComparison = comparison,
            monthlySportTrends = monthlySportTrends,
            sportEfficiencyComparisons = sportEfficiencyComparisons,
            currentSleepActivityResult = database.analysisResultDao().current("sleep_activity", CURRENT_SCOPE),
            currentSportEfficiencyResult = database.analysisResultDao().current("sport_efficiency", CURRENT_SCOPE),
        )
    }

    suspend fun buildAndPersistCurrent(
        database: VitaTraceDatabase,
        profile: UserProfileEntity,
        now: Instant = Instant.now(),
    ): PersonalAnalysisContext {
        val context = build(database, profile, now)
        val sleepActivityResult = context.toSleepActivityResult(now)
        val sportEfficiencyResult = context.toSportEfficiencyResult(now)
        val dao = database.analysisResultDao()
        listOf(sleepActivityResult, sportEfficiencyResult).forEach { result ->
            dao.supersedeCurrent(result.analysisType, result.scope, now.toEpochMilli())
            dao.insert(result)
            dao.deleteOldSupersededCurrentSnapshots(
                analysisType = result.analysisType,
                scope = result.scope,
                keepCount = CURRENT_SNAPSHOT_RETENTION,
            )
        }
        return context.copy(
            currentSleepActivityResult = dao.current("sleep_activity", CURRENT_SCOPE),
            currentSportEfficiencyResult = dao.current("sport_efficiency", CURRENT_SCOPE),
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

    private fun buildSportEfficiencyComparisons(
        trends: List<MonthlySportTrend>,
        timeContext: AnalysisTimeContext,
    ): List<SportEfficiencyComparison> {
        val partialMonth = timeContext.currentPartialDay.take(7)
        val workoutOrder = listOf("walking", "running")
        return workoutOrder.map { workoutType ->
            val sorted = trends
                .filter { trend -> trend.workoutType == workoutType }
                .filter { trend -> trend.period != partialMonth }
                .sortedByDescending { trend -> trend.period }
            val current = sorted.getOrNull(0)
            val previous = sorted.getOrNull(1)
            val delta = if (current != null && previous != null) {
                current.deltaAgainst(previous)
            } else {
                null
            }
            val confidence = sportConfidenceFor(current, previous)
            SportEfficiencyComparison(
                workoutType = workoutType,
                current = current,
                previous = previous,
                delta = delta,
                confidence = confidence,
                interpretation = sportInterpretationFor(delta, confidence),
            )
        }
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

private fun PersonalAnalysisContext.toSportEfficiencyResult(now: Instant): AnalysisResultEntity {
    val comparisonsWithData = sportEfficiencyComparisons.filter { comparison -> comparison.delta != null }
    val bestSummary = comparisonsWithData.firstOrNull { comparison ->
        comparison.confidence != AnalysisConfidence.Insufficient
    } ?: comparisonsWithData.firstOrNull()
    val summary = bestSummary?.summaryText()
        ?: "za malo miesiecy chodzenia/biegania do porownania wydolnosci"
    val sampleSize = comparisonsWithData.sumOf { comparison ->
        (comparison.current?.sessionCount ?: 0) + (comparison.previous?.sessionCount ?: 0)
    }
    return AnalysisResultEntity(
        analysisType = "sport_efficiency",
        scope = CURRENT_SCOPE,
        engineVersion = SPORT_EFFICIENCY_ENGINE_VERSION,
        baselineStartDate = null,
        baselineEndDate = sportEfficiencyComparisons.mapNotNull { comparison -> comparison.previous?.period }.minOrNull(),
        currentStartDate = sportEfficiencyComparisons.mapNotNull { comparison -> comparison.current?.period }.minOrNull(),
        currentEndDate = sportEfficiencyComparisons.mapNotNull { comparison -> comparison.current?.period }.maxOrNull(),
        generatedForDate = timeContext.today,
        summaryTitle = "Wydolnosc sportowa",
        summaryText = summary,
        confidence = bestSummary?.confidence?.name ?: AnalysisConfidence.Insufficient.name,
        sampleSize = sampleSize,
        resultJson = sportEfficiencyComparisons.toJson(),
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

private fun MonthlySportTrend.deltaAgainst(previous: MonthlySportTrend): SportEfficiencyDelta {
    return SportEfficiencyDelta(
        sessionCount = sessionCount - previous.sessionCount,
        totalDurationMinutes = totalDurationMinutes - previous.totalDurationMinutes,
        distanceKm = distanceKm - previous.distanceKm,
        activeCaloriesKcal = activeCaloriesKcal - previous.activeCaloriesKcal,
        avgHeartRateBpm = avgHeartRateBpm.minusNullable(previous.avgHeartRateBpm),
        avgPaceSecondsPerKm = avgPaceSecondsPerKm.minusNullable(previous.avgPaceSecondsPerKm),
        activeCaloriesPerKm = activeCaloriesPerKm.minusNullable(previous.activeCaloriesPerKm),
        activeCaloriesPerMinute = activeCaloriesPerMinute.minusNullable(previous.activeCaloriesPerMinute),
        avgCadence = avgCadence.minusNullable(previous.avgCadence),
        avgVo2Max = avgVo2Max.minusNullable(previous.avgVo2Max),
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

private fun sportConfidenceFor(
    current: MonthlySportTrend?,
    previous: MonthlySportTrend?,
): AnalysisConfidence {
    if (current == null || previous == null) {
        return AnalysisConfidence.Insufficient
    }
    val smallestSessionGroup = minOf(current.sessionCount, previous.sessionCount)
    val hasHeartRate = current.avgHeartRateBpm != null && previous.avgHeartRateBpm != null
    val hasPace = current.avgPaceSecondsPerKm != null && previous.avgPaceSecondsPerKm != null
    val hasCost = current.activeCaloriesPerKm != null && previous.activeCaloriesPerKm != null
    val volumeRatio = if (previous.distanceKm > 0.0) current.distanceKm / previous.distanceKm else 1.0

    val baseConfidence = when {
        smallestSessionGroup < 2 -> AnalysisConfidence.Insufficient
        smallestSessionGroup >= 8 && hasHeartRate && hasPace && hasCost -> AnalysisConfidence.High
        smallestSessionGroup >= 4 && hasHeartRate && (hasPace || hasCost) -> AnalysisConfidence.Medium
        hasHeartRate || hasPace || hasCost -> AnalysisConfidence.Low
        else -> AnalysisConfidence.Insufficient
    }
    return if (baseConfidence != AnalysisConfidence.Insufficient && (volumeRatio < 0.5 || volumeRatio > 2.0)) {
        AnalysisConfidence.Low
    } else {
        baseConfidence
    }
}

private fun sportInterpretationFor(
    delta: SportEfficiencyDelta?,
    confidence: AnalysisConfidence,
): String {
    if (delta == null || confidence == AnalysisConfidence.Insufficient) {
        return "za malo porownywalnych miesiecy lub sesji; nie wyciagamy wniosku"
    }

    val heartRateLower = delta.avgHeartRateBpm?.let { value -> value <= -3.0 } == true
    val heartRateHigher = delta.avgHeartRateBpm?.let { value -> value >= 3.0 } == true
    val paceFaster = delta.avgPaceSecondsPerKm?.let { value -> value <= -10.0 } == true
    val paceSlower = delta.avgPaceSecondsPerKm?.let { value -> value >= 10.0 } == true
    val costLower = delta.activeCaloriesPerKm?.let { value -> value <= -5.0 } == true
    val costHigher = delta.activeCaloriesPerKm?.let { value -> value >= 5.0 } == true
    val muchLowerVolume = delta.distanceKm <= -50.0 || delta.sessionCount <= -5
    val muchHigherVolume = delta.distanceKm >= 50.0 || delta.sessionCount >= 5

    return when {
        muchLowerVolume && (heartRateLower || paceFaster || costLower) ->
            "metryki wygladaja lepiej, ale miesiac mial duzo mniejsza objetosc; traktuj jako niski sygnal"
        muchHigherVolume && !heartRateHigher && !paceSlower ->
            "objetosc wzrosla bez pogorszenia pulsu i tempa; to obiecujacy sygnal wytrzymalosci"
        heartRateLower && !paceSlower ->
            "puls spadl przy podobnym albo lepszym tempie; to pierwszy sygnal lepszej wydolnosci"
        paceFaster && !heartRateHigher ->
            "tempo jest lepsze bez wyraznego wzrostu pulsu; to wyglada jak poprawa formy"
        heartRateHigher && paceSlower ->
            "miesiac wyglada ciezszy: wolniejsze tempo i wyzszy puls"
        costLower && !paceSlower ->
            "koszt kcal/km spadl przy podobnym albo lepszym tempie; wydajnosc mogla sie poprawic"
        costHigher && !paceFaster ->
            "koszt kcal/km wzrosl; sprawdzimy to pozniej z masa ciala i intensywnoscia"
        else ->
            "sygnal jest mieszany; potrzebny wykres i porownanie podobnych sesji"
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

private fun List<SportEfficiencyComparison>.toJson(): String {
    return """
        {
          "analysisType": "sport_efficiency",
          "comparisons": [
            ${joinToString(",\n") { comparison -> comparison.toJson() }}
          ],
          "note": "Month-to-month aggregate comparison; later engine should compare similar sessions by workout type, distance, duration, pace, and intensity."
        }
    """.trimIndent()
}

private fun SportEfficiencyComparison.toJson(): String {
    return """
        {
          "workoutType": "$workoutType",
          "confidence": "${confidence.name}",
          "current": ${current.toJson()},
          "previous": ${previous.toJson()},
          "delta": ${delta.toJson()},
          "interpretation": "${interpretation.escapeJson()}"
        }
    """.trimIndent()
}

private fun MonthlySportTrend?.toJson(): String {
    if (this == null) {
        return "null"
    }
    return """
        {
          "period": "$period",
          "workoutType": "$workoutType",
          "sessionCount": $sessionCount,
          "totalDurationMinutes": ${totalDurationMinutes.jsonNumber()},
          "distanceKm": ${distanceKm.jsonNumber()},
          "activeCaloriesKcal": ${activeCaloriesKcal.jsonNumber()},
          "totalCaloriesKcal": ${totalCaloriesKcal.jsonNumber()},
          "avgHeartRateBpm": ${avgHeartRateBpm.jsonNumber()},
          "maxHeartRateBpm": ${maxHeartRateBpm.jsonNumber()},
          "avgPaceSecondsPerKm": ${avgPaceSecondsPerKm.jsonNumber()},
          "avgCadence": ${avgCadence.jsonNumber()},
          "avgVo2Max": ${avgVo2Max.jsonNumber()},
          "activeCaloriesPerKm": ${activeCaloriesPerKm.jsonNumber()},
          "activeCaloriesPerMinute": ${activeCaloriesPerMinute.jsonNumber()}
        }
    """.trimIndent()
}

private fun SportEfficiencyDelta?.toJson(): String {
    if (this == null) {
        return "null"
    }
    return """
        {
          "sessionCount": $sessionCount,
          "totalDurationMinutes": ${totalDurationMinutes.jsonNumber()},
          "distanceKm": ${distanceKm.jsonNumber()},
          "activeCaloriesKcal": ${activeCaloriesKcal.jsonNumber()},
          "avgHeartRateBpm": ${avgHeartRateBpm.jsonNumber()},
          "avgPaceSecondsPerKm": ${avgPaceSecondsPerKm.jsonNumber()},
          "activeCaloriesPerKm": ${activeCaloriesPerKm.jsonNumber()},
          "activeCaloriesPerMinute": ${activeCaloriesPerMinute.jsonNumber()},
          "avgCadence": ${avgCadence.jsonNumber()},
          "avgVo2Max": ${avgVo2Max.jsonNumber()}
        }
    """.trimIndent()
}

private fun SportEfficiencyComparison.summaryText(): String {
    val delta = delta ?: return interpretation
    return "${workoutType.labelForAnalysis()}: puls ${delta.avgHeartRateBpm.formatSigned0Json()} bpm; " +
        "tempo ${delta.avgPaceSecondsPerKm.formatSignedSecondsJson()} s/km; " +
        "kcal/km ${delta.activeCaloriesPerKm.formatSigned0Json()}; $interpretation"
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
          "sportEfficiencyComparisons": ${sportEfficiencyComparisons.size},
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

private fun Double?.formatSigned0Json(): String {
    return this?.let { value -> "%+.0f".format(value) } ?: "brak"
}

private fun Double?.formatSignedSecondsJson(): String {
    return this?.let { value -> "%+.0f".format(value) } ?: "brak"
}

private fun String.labelForAnalysis(): String {
    return when (this) {
        "walking" -> "chodzenie"
        "running" -> "bieganie"
        else -> this
    }
}
