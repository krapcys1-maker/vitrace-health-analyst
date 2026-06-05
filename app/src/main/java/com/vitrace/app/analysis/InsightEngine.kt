package com.vitrace.app.analysis

import com.vitrace.app.data.AnalysisResultEntity
import com.vitrace.app.data.SleepActivityFeatureRow
import com.vitrace.app.data.SleepNextDayActivityRow
import com.vitrace.app.data.TrainingSleepAggregate
import com.vitrace.app.data.UserProfileEntity
import com.vitrace.app.data.VitaTraceDatabase
import com.vitrace.app.data.WalkingDistanceBandAggregate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

private const val TESTED_INSIGHT_ANALYSIS_TYPE = "tested_insight"
private const val TESTED_INSIGHT_ENGINE_VERSION = "tested_insight_v1"
private const val TESTED_INSIGHT_RETENTION = 10

data class TestedInsight(
    val id: String,
    val domain: String,
    val title: String,
    val answer: String,
    val evidence: List<String>,
    val dateRange: String,
    val sampleSize: Int,
    val confidence: AnalysisConfidence,
    val limitations: List<String>,
    val nextStep: String,
)

object InsightEngine {
    suspend fun buildAndPersistCurrent(
        database: VitaTraceDatabase,
        profile: UserProfileEntity,
        now: Instant = Instant.now(),
    ): List<TestedInsight> {
        val insights = build(database, profile, now)
        val dao = database.analysisResultDao()
        val timestamp = now.toEpochMilli()
        insights.forEach { insight ->
            dao.supersedeCurrent(
                analysisType = TESTED_INSIGHT_ANALYSIS_TYPE,
                scope = insight.id,
                supersededAtEpochMs = timestamp,
            )
            dao.insert(insight.toAnalysisResult(now))
            dao.deleteOldSupersededCurrentSnapshots(
                analysisType = TESTED_INSIGHT_ANALYSIS_TYPE,
                scope = insight.id,
                keepCount = TESTED_INSIGHT_RETENTION,
            )
        }
        return insights
    }

    suspend fun build(
        database: VitaTraceDatabase,
        profile: UserProfileEntity,
        now: Instant = Instant.now(),
    ): List<TestedInsight> {
        val dao = database.dailySummaryDao()
        val today = LocalDate.ofInstant(now, ZoneId.systemDefault())
        val currentEnd = today.minusDays(1)
        val currentStart = currentEnd.minusDays(89)
        val sleepActivityRows = dao.sleepActivityFeatureRows()
            .filter { row -> row.date < today.toString() }
            .sortedBy { row -> row.date }
        val nextDayRows = dao.sleepNextDayActivityRows()
            .filter { row -> row.date < today.toString() }
            .sortedBy { row -> row.date }
        val trainingSleepRows = dao.trainingSleepAggregates()
        val workoutTypes = dao.workoutTypeSessionAggregates()
        val currentWalkingBands = dao.walkingBandsBetween(
            startDate = currentStart.toString(),
            endDate = currentEnd.toString(),
        )
        val baselineWalkingBands = dao.walkingBandsBefore(currentStart.toString())

        return listOf(
            buildCoverageInsight(
                activityDays = dao.activityDays(),
                sleepRows = sleepActivityRows,
                workoutTypes = workoutTypes,
                profile = profile,
            ),
            buildSameNightSleepInsight(sleepActivityRows),
            buildNextDayActivityInsight(nextDayRows),
            buildTrainingSleepInsight(trainingSleepRows),
            buildWalkingEfficiencyInsight(
                currentStart = currentStart,
                currentEnd = currentEnd,
                currentBands = currentWalkingBands,
                baselineBands = baselineWalkingBands,
            ),
        )
    }

    private fun buildCoverageInsight(
        activityDays: Int,
        sleepRows: List<SleepActivityFeatureRow>,
        workoutTypes: List<com.vitrace.app.data.WorkoutTypeSessionAggregate>,
        profile: UserProfileEntity,
    ): TestedInsight {
        val walking = workoutTypes.firstOrNull { row -> row.workoutType == "walking" }
        val running = workoutTypes.firstOrNull { row -> row.workoutType == "running" }
        val evidence = listOf(
            "aktywnosc dzienna: $activityDays dni",
            "sen + aktywnosc: ${sleepRows.size} wspolnych dni",
            "chodzenie: ${walking?.sessionCount ?: 0} sesji, ${(walking?.distanceKm ?: 0.0).format1()} km",
            "bieganie: ${running?.sessionCount ?: 0} sesji",
            "profil: ${profile.ageYears} lat, ${profile.heightCm} cm, ${profile.stepsPerKm} krokow/km",
        )
        return TestedInsight(
            id = "data_coverage_reality",
            domain = "Dane",
            title = "Co mozemy analizowac teraz",
            answer = "Najmocniejsze dane to dlugoterminowe kroki/km oraz chodzenie. Sen ma szczegoly, ale mniejsza probke. Bieganie jest jeszcze tylko lista sesji.",
            evidence = evidence,
            dateRange = dateRangeForSleepActivity(sleepRows),
            sampleSize = activityDays,
            confidence = AnalysisConfidence.High,
            limitations = listOf(
                "Health Connect live jest ubozszy niz import Mi Fitness",
                "nie budujemy teraz modulow bez realnego sygnalu danych",
                "kalorie z zegarka sa sygnalem pomocniczym",
            ),
            nextStep = "pierwsze wnioski liczyc z importu historycznego i dopiero potem laczyc z live",
        )
    }

    private fun buildSameNightSleepInsight(
        rows: List<SleepActivityFeatureRow>,
    ): TestedInsight {
        val usableRows = rows.filter { row -> row.steps > 0 && row.totalSleepMinutes > 0 }
        val steps = usableRows.map { row -> row.steps.toDouble() }
        val totalSleep = usableRows.map { row -> row.totalSleepMinutes.toDouble() }
        val rem = usableRows.map { row -> row.remSleepMinutes?.toDouble() }
        val deep = usableRows.map { row -> row.deepSleepMinutes?.toDouble() }
        val score = usableRows.map { row -> row.sleepScore?.toDouble() }
        val buckets = stepBuckets(usableRows)
        val totalCorr = correlation(steps, totalSleep)
        val remCorr = correlationNullable(steps, rem)
        val deepCorr = correlationNullable(steps, deep)
        val scoreCorr = correlationNullable(steps, score)
        val simpleRelationshipIsWeak = listOf(totalCorr, remCorr, deepCorr, scoreCorr)
            .all { value -> value == null || abs(value) < 0.20 }

        return TestedInsight(
            id = "activity_sleep_same_night",
            domain = "Sen",
            title = "Czy wiecej krokow poprawia sen tej nocy?",
            answer = if (simpleRelationshipIsWeak) {
                "W obecnych danych nie widac mocnej prostej zaleznosci: wiecej krokow tego samego dnia nie oznacza wyraznie lepszego snu."
            } else {
                "Jest sygnal zaleznosci miedzy krokami i snem, ale trzeba go rozbic na typ aktywnosci, czas treningu i obciazenie."
            },
            evidence = listOf(
                "kroki vs caly sen: r=${totalCorr.formatCorrelation()}",
                "kroki vs REM: r=${remCorr.formatCorrelation()}",
                "kroki vs gleboki sen: r=${deepCorr.formatCorrelation()}",
                "kroki vs wynik snu: r=${scoreCorr.formatCorrelation()}",
                "bucket 8-12k: ${buckets["8-12k"] ?: "brak"}",
                "bucket 18k+: ${buckets["18k+"] ?: "brak"}",
            ),
            dateRange = dateRangeForSleepActivity(usableRows),
            sampleSize = usableRows.size,
            confidence = confidenceForSample(usableRows.size),
            limitations = listOf(
                "to jest prosta korelacja, nie przyczyna",
                "nie kontroluje godziny treningu, stresu, choroby ani alkoholu",
                "fazy snu z zegarka traktujemy jako trend",
            ),
            nextStep = "dodac test: trening dzienny/wieczorny kontra REM, gleboki sen i score",
        )
    }

    private fun buildNextDayActivityInsight(
        rows: List<SleepNextDayActivityRow>,
    ): TestedInsight {
        val usableRows = rows.filter { row -> row.totalSleepMinutes > 0 }
        val poorSleepRows = usableRows.filter { row ->
            (row.sleepScore != null && row.sleepScore < 70) || row.totalSleepMinutes < 360
        }
        val otherRows = usableRows - poorSleepRows.toSet()
        val poorSteps = poorSleepRows.map { row -> row.nextDaySteps }.averageOrNull()
        val otherSteps = otherRows.map { row -> row.nextDaySteps }.averageOrNull()
        val poorWorkout = poorSleepRows.map { row -> row.nextDayWorkoutMinutes }.averageOrNull()
        val otherWorkout = otherRows.map { row -> row.nextDayWorkoutMinutes }.averageOrNull()
        val poorHeart = poorSleepRows.mapNotNull { row -> row.nextDayAvgHeartRateBpm }.averageOrNull()
        val otherHeart = otherRows.mapNotNull { row -> row.nextDayAvgHeartRateBpm }.averageOrNull()
        val stepDelta = poorSteps.minusNullable(otherSteps)
        val workoutDelta = poorWorkout.minusNullable(otherWorkout)
        val heartDelta = poorHeart.minusNullable(otherHeart)

        return TestedInsight(
            id = "sleep_next_day_activity",
            domain = "Regeneracja",
            title = "Czy slabszy sen zmienia nastepny dzien?",
            answer = when {
                usableRows.size < 30 || poorSleepRows.size < 10 -> "Probka jest jeszcze za mala, zeby mocno ocenic dzien po slabym snie."
                stepDelta != null && stepDelta <= -1000.0 -> "Po slabszym snie nastepnego dnia aktywnosc wyglada nizsza. To jest bardziej obiecujacy test niz proste kroki -> sen."
                stepDelta != null && abs(stepDelta) < 1000.0 -> "Na razie slabszy sen nie pokazuje duzej roznicy w krokach nastepnego dnia."
                else -> "Dane sa mieszane; trzeba zebrane dni rozbic na score, dlugosc snu i treningi."
            },
            evidence = listOf(
                "slabszy sen: ${poorSleepRows.size} dni; pozostale: ${otherRows.size}",
                "nastepne kroki: ${poorSteps.format0()} vs ${otherSteps.format0()} (${stepDelta.formatSigned0()})",
                "nastepny trening: ${poorWorkout.format0()} min vs ${otherWorkout.format0()} min (${workoutDelta.formatSigned0()} min)",
                "nastepny puls: ${poorHeart.format0()} bpm vs ${otherHeart.format0()} bpm (${heartDelta.formatSigned0()} bpm)",
            ),
            dateRange = dateRangeForNextDayRows(usableRows),
            sampleSize = usableRows.size,
            confidence = confidenceForGroups(poorSleepRows.size, otherRows.size),
            limitations = listOf(
                "slabszy sen jest teraz definiowany prosto: score < 70 albo mniej niz 6h",
                "nie rozdziela dni pracy, weekendow i choroby",
                "puls nastepnego dnia ma nierowne pokrycie",
            ),
            nextStep = "dodac osobne progi: krotki sen, niski score, malo REM, malo glebokiego snu",
        )
    }

    private fun buildTrainingSleepInsight(
        rows: List<TrainingSleepAggregate>,
    ): TestedInsight {
        val training = rows.firstOrNull { row -> row.groupName == "training" }
        val rest = rows.firstOrNull { row -> row.groupName == "non_training" }
        val sleepDelta = training?.avgTotalSleepMinutes.minusNullable(rest?.avgTotalSleepMinutes)
        val deepDelta = training?.avgDeepSleepMinutes.minusNullable(rest?.avgDeepSleepMinutes)
        val remDelta = training?.avgRemSleepMinutes.minusNullable(rest?.avgRemSleepMinutes)
        val scoreDelta = training?.avgSleepScore.minusNullable(rest?.avgSleepScore)

        return TestedInsight(
            id = "training_day_sleep",
            domain = "Sen",
            title = "Czy dni treningowe poprawiaja sen?",
            answer = when {
                training == null || rest == null -> "Brakuje jednej z grup, wiec nie mozna porownac snu po treningu i bez treningu."
                abs(sleepDelta ?: 0.0) < 20.0 && abs(scoreDelta ?: 0.0) < 3.0 ->
                    "Dni treningowe i nietreningowe wygladaja podobnie. Nie ma tu prostego wniosku, ze sam trening automatycznie poprawia sen."
                (sleepDelta ?: 0.0) > 20.0 && (scoreDelta ?: 0.0) >= 0.0 ->
                    "Po dniach treningowych sen jest dluzszy, ale trzeba sprawdzic typ, godzine i intensywnosc treningu."
                else -> "Sygnal jest mieszany; trening trzeba rozbic na chodzenie, bieganie, czas dnia i obciazenie."
            },
            evidence = listOf(
                "trening: ${training?.nights ?: 0} nocy; bez treningu: ${rest?.nights ?: 0} nocy",
                "sen caly: ${training?.avgTotalSleepMinutes.formatMinutes()} vs ${rest?.avgTotalSleepMinutes.formatMinutes()} (${sleepDelta.formatSigned0()} min)",
                "REM: ${training?.avgRemSleepMinutes.format0()} vs ${rest?.avgRemSleepMinutes.format0()} (${remDelta.formatSigned0()} min)",
                "gleboki: ${training?.avgDeepSleepMinutes.format0()} vs ${rest?.avgDeepSleepMinutes.format0()} (${deepDelta.formatSigned0()} min)",
                "score: ${training?.avgSleepScore.format0()} vs ${rest?.avgSleepScore.format0()} (${scoreDelta.formatSigned0()})",
            ),
            dateRange = "wszystkie noce z detalami snu",
            sampleSize = (training?.nights ?: 0) + (rest?.nights ?: 0),
            confidence = confidenceForGroups(training?.nights ?: 0, rest?.nights ?: 0),
            limitations = listOf(
                "trening jest tu dowolnym treningiem, bez typu i godziny",
                "nie uwzglednia obciazenia, stresu i dnia tygodnia",
            ),
            nextStep = "rozbic trening na chodzenie/bieganie oraz poranne/wieczorne sesje",
        )
    }

    private fun buildWalkingEfficiencyInsight(
        currentStart: LocalDate,
        currentEnd: LocalDate,
        currentBands: List<WalkingDistanceBandAggregate>,
        baselineBands: List<WalkingDistanceBandAggregate>,
    ): TestedInsight {
        val pairs = currentBands.mapNotNull { current ->
            baselineBands.firstOrNull { baseline -> baseline.distanceBand == current.distanceBand }
                ?.let { baseline -> current to baseline }
        }
        val bestPair = pairs
            .filter { (current, baseline) -> current.sessionCount >= 2 && baseline.sessionCount >= 10 }
            .maxByOrNull { (current, _) -> current.sessionCount }
            ?: pairs.maxByOrNull { (current, baseline) -> minOf(current.sessionCount, baseline.sessionCount) }
        val current = bestPair?.first
        val baseline = bestPair?.second
        val hrDelta = current?.avgHeartRateBpm.minusNullable(baseline?.avgHeartRateBpm)
        val paceDelta = current?.avgPaceSecondsPerKm.minusNullable(baseline?.avgPaceSecondsPerKm)
        val costDelta = current?.activeCaloriesPerKm.minusNullable(baseline?.activeCaloriesPerKm)
        val vo2Delta = current?.avgVo2Max.minusNullable(baseline?.avgVo2Max)

        return TestedInsight(
            id = "walking_efficiency_by_distance_band",
            domain = "Trening",
            title = "Czy chodzenie wyglada wydolnosciowo lepiej?",
            answer = when {
                current == null || baseline == null -> "Nie ma jeszcze porownywalnego pasma dystansu dla chodzenia."
                current.sessionCount < 2 -> "W ostatnim oknie jest za malo podobnych marszow, zeby ocenic wydolnosc."
                baseline.sessionCount < 10 -> "Brakuje mocnego baseline dla tego pasma dystansu."
                hrDelta != null && paceDelta != null && hrDelta <= -3.0 && paceDelta <= 10.0 ->
                    "W podobnym pasmie dystansu puls jest nizszy bez wyraznego spowolnienia. To moze byc sygnal lepszej wydolnosci."
                hrDelta != null && paceDelta != null && hrDelta >= 3.0 && paceDelta >= 10.0 ->
                    "W podobnym pasmie dystansu puls jest wyzszy i tempo wolniejsze. To moze oznaczac wieksze zmeczenie albo inne warunki."
                else -> "Sygnal chodzenia jest mieszany. Warto pokazac go jako porownanie pasm dystansu, nie jako jeden wykres miesieczny."
            },
            evidence = listOf(
                "pasmo: ${current?.distanceBand ?: "brak"}",
                "ostatnie 90 dni: ${current?.sessionCount ?: 0} sesji, ${current?.distanceKm.format1()} km",
                "baseline: ${baseline?.sessionCount ?: 0} sesji, ${baseline?.distanceKm.format1()} km",
                "puls: ${current?.avgHeartRateBpm.format0()} vs ${baseline?.avgHeartRateBpm.format0()} (${hrDelta.formatSigned0()} bpm)",
                "tempo: ${current?.avgPaceSecondsPerKm.formatPace()} vs ${baseline?.avgPaceSecondsPerKm.formatPace()} (${paceDelta.formatSignedSeconds()} /km)",
                "kcal/km: ${current?.activeCaloriesPerKm.format0()} vs ${baseline?.activeCaloriesPerKm.format0()} (${costDelta.formatSigned0()})",
                "VO2: ${current?.avgVo2Max.format1()} vs ${baseline?.avgVo2Max.format1()} (${vo2Delta.formatSigned1()})",
            ),
            dateRange = "${currentStart} - ${currentEnd}",
            sampleSize = (current?.sessionCount ?: 0) + (baseline?.sessionCount ?: 0),
            confidence = confidenceForGroups(current?.sessionCount ?: 0, baseline?.sessionCount ?: 0),
            limitations = listOf(
                "nie kontroluje pogody, trasy, przewyzszen i przerw",
                "kalorie sa tylko pomocnicze",
                "nastepny krok to porownanie konkretnych podobnych tras z GPX",
            ),
            nextStep = "dodac porownanie podobnych sesji po dystansie, tempie i pozniej po GPX",
        )
    }
}

private fun TestedInsight.toAnalysisResult(now: Instant): AnalysisResultEntity {
    val generatedDate = LocalDate.ofInstant(now, ZoneId.systemDefault()).toString()
    return AnalysisResultEntity(
        analysisType = TESTED_INSIGHT_ANALYSIS_TYPE,
        scope = id,
        engineVersion = TESTED_INSIGHT_ENGINE_VERSION,
        baselineStartDate = null,
        baselineEndDate = null,
        currentStartDate = dateRange.dateRangeStartOrNull(),
        currentEndDate = dateRange.dateRangeEndOrNull(),
        generatedForDate = generatedDate,
        summaryTitle = title,
        summaryText = answer,
        confidence = confidence.name,
        sampleSize = sampleSize,
        resultJson = toJson(),
        sourceCoverageJson = """
            {
              "source": "local_room_database",
              "domain": "${domain.escapeJson()}",
              "note": "Insight generated from normalized local summaries and detailed imported records."
            }
        """.trimIndent(),
        timeContextJson = """
            {
              "generatedAtEpochMs": ${now.toEpochMilli()},
              "generatedForDate": "$generatedDate"
            }
        """.trimIndent(),
        isCurrent = true,
        pinned = false,
        createdAtEpochMs = now.toEpochMilli(),
        updatedAtEpochMs = now.toEpochMilli(),
        supersededAtEpochMs = null,
    )
}

private fun TestedInsight.toJson(): String {
    return """
        {
          "id": "${id.escapeJson()}",
          "domain": "${domain.escapeJson()}",
          "title": "${title.escapeJson()}",
          "answer": "${answer.escapeJson()}",
          "evidence": ${evidence.toJsonArray()},
          "dateRange": "${dateRange.escapeJson()}",
          "sampleSize": $sampleSize,
          "confidence": "${confidence.name}",
          "limitations": ${limitations.toJsonArray()},
          "nextStep": "${nextStep.escapeJson()}"
        }
    """.trimIndent()
}

private fun stepBuckets(rows: List<SleepActivityFeatureRow>): Map<String, String> {
    val buckets = listOf(
        "lt8k" to (0L..7_999L),
        "8-12k" to (8_000L..11_999L),
        "12-18k" to (12_000L..17_999L),
        "18k+" to (18_000L..Long.MAX_VALUE),
    )
    return buckets.associate { (label, range) ->
        val bucketRows = rows.filter { row -> row.steps in range }
        val summary = if (bucketRows.isEmpty()) {
            "brak"
        } else {
            val sleepHours = bucketRows.map { row -> row.totalSleepMinutes }.average() / 60.0
            val score = bucketRows.mapNotNull { row -> row.sleepScore }.averageOrNull()
            "${bucketRows.size} dni, ${sleepHours.format1()} h, score ${score.format0()}"
        }
        label to summary
    }
}

private fun correlation(
    xs: List<Double>,
    ys: List<Double>,
): Double? {
    if (xs.size != ys.size || xs.size < 3) {
        return null
    }
    val meanX = xs.average()
    val meanY = ys.average()
    val sumX = xs.sumOf { value -> (value - meanX) * (value - meanX) }
    val sumY = ys.sumOf { value -> (value - meanY) * (value - meanY) }
    if (sumX == 0.0 || sumY == 0.0) {
        return null
    }
    val covariance = xs.zip(ys).sumOf { (x, y) -> (x - meanX) * (y - meanY) }
    return covariance / sqrt(sumX * sumY)
}

private fun correlationNullable(
    xs: List<Double>,
    ys: List<Double?>,
): Double? {
    val pairs = xs.zip(ys).mapNotNull { (x, y) -> y?.let { value -> x to value } }
    return correlation(
        xs = pairs.map { pair -> pair.first },
        ys = pairs.map { pair -> pair.second },
    )
}

private fun confidenceForSample(sampleSize: Int): AnalysisConfidence {
    return when {
        sampleSize >= 90 -> AnalysisConfidence.High
        sampleSize >= 45 -> AnalysisConfidence.Medium
        sampleSize >= 14 -> AnalysisConfidence.Low
        else -> AnalysisConfidence.Insufficient
    }
}

private fun confidenceForGroups(
    first: Int,
    second: Int,
): AnalysisConfidence {
    val smallest = minOf(first, second)
    val total = first + second
    return when {
        smallest < 7 || total < 14 -> AnalysisConfidence.Insufficient
        smallest >= 45 && total >= 90 -> AnalysisConfidence.High
        smallest >= 15 && total >= 30 -> AnalysisConfidence.Medium
        else -> AnalysisConfidence.Low
    }
}

private fun dateRangeForSleepActivity(rows: List<SleepActivityFeatureRow>): String {
    if (rows.isEmpty()) {
        return "brak danych"
    }
    return "${rows.first().date} - ${rows.last().date}"
}

private fun dateRangeForNextDayRows(rows: List<SleepNextDayActivityRow>): String {
    if (rows.isEmpty()) {
        return "brak danych"
    }
    return "${rows.first().date} - ${rows.last().date}"
}

private fun <T : Number> List<T>.averageOrNull(): Double? {
    if (isEmpty()) {
        return null
    }
    return map { number -> number.toDouble() }.average()
}

private fun Double?.minusNullable(other: Double?): Double? {
    if (this == null || other == null) {
        return null
    }
    return this - other
}

private fun Double?.formatCorrelation(): String {
    return this?.let { value -> "%+.2f".format(value) } ?: "brak"
}

private fun Double?.format0(): String {
    return this?.let { value -> "%.0f".format(value) } ?: "brak"
}

private fun Double?.format1(): String {
    return this?.let { value -> "%.1f".format(value) } ?: "brak"
}

private fun Double?.formatSigned0(): String {
    return this?.let { value -> "%+.0f".format(value) } ?: "brak"
}

private fun Double?.formatSigned1(): String {
    return this?.let { value -> "%+.1f".format(value) } ?: "brak"
}

private fun Double?.formatMinutes(): String {
    return this?.let { value -> "%.1f h".format(value / 60.0) } ?: "brak"
}

private fun Double?.formatPace(): String {
    if (this == null || this <= 0.0) {
        return "brak"
    }
    val totalSeconds = toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun Double?.formatSignedSeconds(): String {
    return this?.let { value -> "%+.0f s".format(value) } ?: "brak"
}

private fun String.dateRangeStartOrNull(): String? {
    return split(" - ").getOrNull(0)?.takeIf { value -> value.length == 10 }
}

private fun String.dateRangeEndOrNull(): String? {
    return split(" - ").getOrNull(1)?.takeIf { value -> value.length == 10 }
}

private fun List<String>.toJsonArray(): String {
    return joinToString(
        prefix = "[",
        postfix = "]",
        separator = ",",
    ) { value -> "\"${value.escapeJson()}\"" }
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
