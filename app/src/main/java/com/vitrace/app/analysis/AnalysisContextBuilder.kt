package com.vitrace.app.analysis

import com.vitrace.app.data.MonthlySleepPhaseAggregate
import com.vitrace.app.data.MonthlyWorkoutSessionAggregate
import com.vitrace.app.data.SleepActivityFeatureRow
import com.vitrace.app.data.UserProfileEntity
import com.vitrace.app.data.VitaTraceDatabase
import kotlin.math.abs

data class PersonalAnalysisContext(
    val profile: AnalysisProfile,
    val monthlySleepPhases: List<MonthlySleepPhaseAnalysis>,
    val sleepActivityComparison: SleepActivityComparison,
    val monthlySportTrends: List<MonthlySportTrend>,
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
    ): PersonalAnalysisContext {
        val dao = database.dailySummaryDao()
        val sleepActivityRows = dao.sleepActivityFeatureRows()
        return PersonalAnalysisContext(
            profile = profile.toAnalysisProfile(),
            monthlySleepPhases = dao.monthlySleepPhases(limit = 12).map { aggregate ->
                aggregate.toAnalysis()
            },
            sleepActivityComparison = buildSleepActivityComparison(sleepActivityRows),
            monthlySportTrends = dao.monthlyWorkoutSessions(
                workoutTypes = listOf("walking", "running"),
                limit = 24,
            ).map { aggregate -> aggregate.toTrend() },
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
