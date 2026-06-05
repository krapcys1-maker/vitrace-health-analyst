package com.vitrace.app.analysis

import com.vitrace.app.data.AnalysisResultEntity
import com.vitrace.app.data.ActivityPeriodAggregate
import com.vitrace.app.data.ActivityWorkoutSleepLoadRow
import com.vitrace.app.data.HeartContextRow
import com.vitrace.app.data.MonthlySleepPhaseAggregate
import com.vitrace.app.data.SleepActivityFeatureRow
import com.vitrace.app.data.SleepNextDayActivityRow
import com.vitrace.app.data.SleepWindowRow
import com.vitrace.app.data.TrainingSleepAggregate
import com.vitrace.app.data.UserProfileEntity
import com.vitrace.app.data.VitaTraceDatabase
import com.vitrace.app.data.WalkingDistanceBandAggregate
import com.vitrace.app.data.WorkoutTypeSessionAggregate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

private const val TESTED_INSIGHT_ANALYSIS_TYPE = "tested_insight"
private const val TESTED_INSIGHT_ENGINE_VERSION = "tested_insight_v1"
private const val TESTED_INSIGHT_RETENTION = 10
private const val LONG_WALK_SLEEP_MIN_DISTANCE_KM = 8.0

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
        val todayText = today.toString()
        val currentEnd = today.minusDays(1)
        val currentStart = currentEnd.minusDays(89)
        val sleepActivityRows = dao.sleepActivityFeatureRows()
            .filter { row -> row.date < todayText }
            .sortedBy { row -> row.date }
        val nextDayRows = dao.sleepNextDayActivityRows()
            .filter { row -> row.date < todayText }
            .sortedBy { row -> row.date }
        val trainingSleepRows = dao.trainingSleepAggregatesBefore(todayText)
        val workoutTypes = dao.workoutTypeSessionAggregatesBefore(todayText)
        val yearlyActivity = dao.yearlyActivityBefore(todayText)
        val bestActivityMonth = dao.bestActivityMonthBefore(todayText)
        val monthlySleep = dao.monthlySleepPhasesBefore(beforeDate = todayText, limit = 8)
        val sleepWindowRows = dao.sleepWindowRowsBefore(todayText)
        val workoutSleepLoadRows = dao.activityWorkoutSleepLoadRowsBefore(todayText)
        val heartContextRows = dao.heartContextRowsBefore(todayText)
        val currentWalkingBands = dao.walkingBandsBetween(
            startDate = currentStart.toString(),
            endDate = currentEnd.toString(),
        )
        val baselineWalkingBands = dao.walkingBandsBefore(currentStart.toString())

        return listOf(
            buildCoverageInsight(
                activityDays = dao.activityDaysBefore(todayText),
                sleepRows = sleepActivityRows,
                workoutTypes = workoutTypes,
                profile = profile,
            ),
            buildLongTermActivityInsight(
                yearlyActivity = yearlyActivity,
                bestActivityMonth = bestActivityMonth,
                profile = profile,
            ),
            buildMonthlySleepBaselineInsight(monthlySleep),
            buildSleepDebtInsight(sleepWindowRows),
            buildActivityWorkoutSleepLoadInsight(workoutSleepLoadRows),
            buildSameNightSleepInsight(sleepActivityRows),
            buildNextDayActivityInsight(nextDayRows),
            buildTrainingSleepInsight(trainingSleepRows),
            buildHeartContextInsight(heartContextRows),
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
        workoutTypes: List<WorkoutTypeSessionAggregate>,
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

    private fun buildLongTermActivityInsight(
        yearlyActivity: List<ActivityPeriodAggregate>,
        bestActivityMonth: ActivityPeriodAggregate?,
        profile: UserProfileEntity,
    ): TestedInsight {
        val chronologicalYears = yearlyActivity.sortedBy { row -> row.period }
        val totalDays = yearlyActivity.sumOf { row -> row.daysWithActivity }
        val bestYear = yearlyActivity.maxByOrNull { row -> row.steps }
        val latestYear = chronologicalYears.lastOrNull()
        val evidence = chronologicalYears.takeLast(6).map { year ->
            "${year.period}: ${year.steps.formatSteps()} krokow, ${year.steps.estimatedKm(profile).format1()} km est., ${(year.distanceMeters / 1000.0).format1()} km z danych"
        } + listOfNotNull(
            bestYear?.let { year -> "najlepszy rok: ${year.period}, ${year.steps.formatSteps()} krokow" },
            bestActivityMonth?.let { month ->
                "najlepszy miesiac: ${month.period}, ${month.steps.formatSteps()} krokow, ${month.steps.estimatedKm(profile).format1()} km est."
            },
        )

        return TestedInsight(
            id = "long_term_steps_km",
            domain = "Aktywnosc",
            title = "Ile chodzenia widac w skali lat?",
            answer = when {
                yearlyActivity.isEmpty() -> "Nie ma jeszcze zamknietych dni aktywnosci do rocznych podsumowan."
                latestYear != null && bestYear != null && latestYear.period == bestYear.period ->
                    "Najmocniejsza wartosc aplikacji teraz to roczne i miesieczne kroki/km. Najnowszy zamkniety rok w danych jest jednoczesnie najlepszy pod wzgledem liczby krokow."
                else -> "Najmocniejsza wartosc aplikacji teraz to roczne i miesieczne kroki/km, bo tego brakuje w prostym widoku Mi Fitness."
            },
            evidence = evidence,
            dateRange = if (chronologicalYears.isEmpty()) "brak danych" else "${chronologicalYears.first().period} - ${chronologicalYears.last().period}",
            sampleSize = totalDays,
            confidence = confidenceForSample(totalDays),
            limitations = listOf(
                "km est. liczymy z profilu ${profile.stepsPerKm} krokow/km",
                "km z danych pochodzi z importu i moze roznic sie od przelicznika krokow",
                "dzisiejszy czesciowy dzien nie jest uzyty w trendzie",
            ),
            nextStep = "dodac trend miesiac do miesiaca i pokazac osobno km est. oraz km z danych",
        )
    }

    private fun buildMonthlySleepBaselineInsight(
        monthlySleep: List<MonthlySleepPhaseAggregate>,
    ): TestedInsight {
        val newest = monthlySleep.firstOrNull()
        val baseline = monthlySleep.drop(1)
        val baselineTotal = baseline.map { row -> row.avgTotalSleepMinutes }.averageOrNull()
        val baselineRem = baseline.mapNotNull { row -> row.avgRemSleepMinutes }.averageOrNull()
        val baselineDeep = baseline.mapNotNull { row -> row.avgDeepSleepMinutes }.averageOrNull()
        val totalDelta = newest?.avgTotalSleepMinutes.minusNullable(baselineTotal)
        val remDelta = newest?.avgRemSleepMinutes.minusNullable(baselineRem)
        val deepDelta = newest?.avgDeepSleepMinutes.minusNullable(baselineDeep)
        val totalNights = monthlySleep.sumOf { row -> row.sleepDays }

        val evidence = monthlySleep.take(6).map { month ->
            "${month.period}: ${month.sleepDays} nocy, sen ${month.avgTotalSleepMinutes.formatMinutes()}, REM ${month.avgRemSleepMinutes.format0()} min, gleboki ${month.avgDeepSleepMinutes.format0()} min, score ${month.avgSleepScore.format0()}"
        } + listOfNotNull(
            newest?.let { "najnowszy miesiac vs baseline: sen ${totalDelta.formatSigned0()} min, REM ${remDelta.formatSigned0()} min, gleboki ${deepDelta.formatSigned0()} min" },
        )

        return TestedInsight(
            id = "sleep_monthly_baseline",
            domain = "Sen",
            title = "Jak wyglada miesieczny baseline snu?",
            answer = when {
                newest == null -> "Brakuje miesiecy z detalami snu, wiec nie ma jeszcze baseline."
                newest.sleepDays < 7 -> "Mamy fazy snu miesiacami, ale najnowszy miesiac ma jeszcze mala probke. Traktujemy go jako wczesny sygnal, nie trend."
                baselineTotal != null && abs(totalDelta ?: 0.0) < 20.0 ->
                    "Najnowszy miesiac wyglada podobnie do Twojego ostatniego baseline snu. Warto sledzic REM i sen gleboki miesiac do miesiaca."
                else -> "Miesieczny baseline snu jest gotowy do porownan: caly sen, REM, gleboki, lekki, czuwanie i score."
            },
            evidence = evidence,
            dateRange = if (monthlySleep.isEmpty()) "brak danych" else "${monthlySleep.last().period} - ${monthlySleep.first().period}",
            sampleSize = totalNights,
            confidence = confidenceForSample(totalNights),
            limitations = listOf(
                "fazy snu z zegarka sa estymacja i lepiej nadaja sie do trendow niz do diagnozy",
                "miesiac z mala liczba nocy nie powinien byc traktowany jak pelny miesiac",
                "dzisiejszy czesciowy sen nie jest uzyty w trendzie",
            ),
            nextStep = "dodac wykres stacked bar miesiacami i test sleep debt 7/14/30 dni",
        )
    }

    private fun buildSleepDebtInsight(
        rowsDescending: List<SleepWindowRow>,
    ): TestedInsight {
        val newest30 = rowsDescending.take(30)
        val newest14 = rowsDescending.take(14)
        val newest7 = rowsDescending.take(7)
        val baseline = rowsDescending.drop(30).take(90)
        val baselineTotal = baseline.map { row -> row.totalSleepMinutes }.averageOrNull()
        val baselineRem = baseline.mapNotNull { row -> row.remSleepMinutes }.averageOrNull()
        val baselineDeep = baseline.mapNotNull { row -> row.deepSleepMinutes }.averageOrNull()
        val delta7 = newest7.map { row -> row.totalSleepMinutes }.averageOrNull().minusNullable(baselineTotal)
        val delta14 = newest14.map { row -> row.totalSleepMinutes }.averageOrNull().minusNullable(baselineTotal)
        val delta30 = newest30.map { row -> row.totalSleepMinutes }.averageOrNull().minusNullable(baselineTotal)
        val remDelta30 = newest30.mapNotNull { row -> row.remSleepMinutes }.averageOrNull().minusNullable(baselineRem)
        val deepDelta30 = newest30.mapNotNull { row -> row.deepSleepMinutes }.averageOrNull().minusNullable(baselineDeep)

        return TestedInsight(
            id = "sleep_debt_window",
            domain = "Sen",
            title = "Czy ostatnie noce sa ponizej Twojej normy?",
            answer = when {
                newest30.size < 14 || baseline.size < 30 -> "Mamy za malo zarejestrowanych nocy, zeby policzyc sensowny sleep debt wzgledem Twojej normy."
                delta7 != null && delta7 <= -45.0 ->
                    "Ostatnie 7 zarejestrowanych nocy jest wyraznie krotsze niz Twoj wczesniejszy baseline. To moze byc sygnal dlugu snu."
                delta30 != null && delta30 <= -30.0 ->
                    "Ostatnie 30 zarejestrowanych nocy jest krotsze niz Twoj baseline. Warto obserwowac, czy to trend, czy tylko okres z gorszym snem."
                abs(delta30 ?: 0.0) < 20.0 ->
                    "Ostatnie 30 zarejestrowanych nocy wyglada podobnie do Twojej wczesniejszej normy dlugosci snu."
                else -> "Sleep debt window jest policzony, ale sygnal nie jest jednoznaczny bez regularniejszego pokrycia nocy."
            },
            evidence = listOf(
                "ostatnie 7 nocy: ${newest7.map { row -> row.totalSleepMinutes }.averageOrNull().formatMinutes()} (${delta7.formatSigned0()} min vs baseline)",
                "ostatnie 14 nocy: ${newest14.map { row -> row.totalSleepMinutes }.averageOrNull().formatMinutes()} (${delta14.formatSigned0()} min vs baseline)",
                "ostatnie 30 nocy: ${newest30.map { row -> row.totalSleepMinutes }.averageOrNull().formatMinutes()} (${delta30.formatSigned0()} min vs baseline)",
                "baseline: ${baseline.size} nocy, ${baselineTotal.formatMinutes()}",
                "REM 30 nocy vs baseline: ${remDelta30.formatSigned0()} min",
                "gleboki 30 nocy vs baseline: ${deepDelta30.formatSigned0()} min",
            ),
            dateRange = dateRangeForSleepWindowRows(newest30),
            sampleSize = newest30.size + baseline.size,
            confidence = confidenceForGroups(newest30.size, baseline.size),
            limitations = listOf(
                "to sa ostatnie zarejestrowane noce, nie zawsze ciagly kalendarz",
                "fazy snu z zegarka sa estymacja",
                "dzisiejszy czesciowy sen nie jest uzyty w trendzie",
            ),
            nextStep = "dodac widok 7/14/30 nocy z kreska Twojego baseline i oznaczeniem brakujacych nocy",
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

    private fun buildActivityWorkoutSleepLoadInsight(
        rows: List<ActivityWorkoutSleepLoadRow>,
    ): TestedInsight {
        if (rows.size < 30) {
            return TestedInsight(
                id = "activity_workout_sleep_load",
                domain = "Regeneracja",
                title = "Czy rodzaj obciazenia zmienia nastepny sen?",
                answer = "Mamy za malo par aktywnosc -> nastepny sen, zeby rozdzielic same wysokie kroki od dlugich marszow.",
                evidence = listOf("pary aktywnosc -> sen: ${rows.size}"),
                dateRange = dateRangeForWorkoutSleepLoadRows(rows),
                sampleSize = rows.size,
                confidence = AnalysisConfidence.Insufficient,
                limitations = listOf("minimum do tego testu to 30 par aktywnosc -> nastepny sen"),
                nextStep = "zbierac kolejne noce po aktywnych dniach i dlugich marszach",
            )
        }

        val sortedSteps = rows.map { row -> row.steps }.sorted()
        val lowThreshold = sortedSteps[sortedSteps.size / 4]
        val highThreshold = sortedSteps[(sortedSteps.size * 3) / 4]
        val typicalRows = rows.filter { row ->
            row.steps > lowThreshold &&
                row.steps < highThreshold &&
                row.walkingKm < LONG_WALK_SLEEP_MIN_DISTANCE_KM
        }
        val highNoLongRows = rows.filter { row ->
            row.steps >= highThreshold &&
                row.walkingKm < LONG_WALK_SLEEP_MIN_DISTANCE_KM
        }
        val longWalkRows = rows.filter { row ->
            row.walkingKm >= LONG_WALK_SLEEP_MIN_DISTANCE_KM
        }

        val typical = SleepLoadGroup.from("typowy dzien", typicalRows)
        val highNoLong = SleepLoadGroup.from("wysokie kroki bez dlugiego marszu", highNoLongRows)
        val longWalk = SleepLoadGroup.from("dlugi marsz 8+ km", longWalkRows)
        val highRemDelta = highNoLong.avgRemMinutes.minusNullable(typical.avgRemMinutes)
        val highScoreDelta = highNoLong.avgSleepScore.minusNullable(typical.avgSleepScore)
        val longTotalDelta = longWalk.avgTotalSleepMinutes.minusNullable(typical.avgTotalSleepMinutes)
        val longScoreDelta = longWalk.avgSleepScore.minusNullable(typical.avgSleepScore)
        val confidence = confidenceForSleepLoadGroups(
            typical.days,
            highNoLong.days,
            longWalk.days,
        )
        val interpretation = when {
            confidence == AnalysisConfidence.Insufficient ->
                "Probka jest jeszcze za mala, zeby mocno rozdzielic same wysokie kroki od dlugich marszow."
            (highRemDelta ?: 0.0) <= -10.0 && (longTotalDelta ?: 0.0) <= -20.0 ->
                "Wysokie kroki bez dlugiego marszu obnizaja REM, a dlugi marsz dodatkowo skraca sen."
            (longTotalDelta ?: 0.0) <= -20.0 && (longScoreDelta ?: 0.0) <= -3.0 ->
                "Dlugi marsz wyglada jak wieksze obciazenie regeneracji niz same wysokie kroki."
            (highRemDelta ?: 0.0) <= -10.0 || (highScoreDelta ?: 0.0) <= -2.0 ->
                "Same wysokie kroki wygladaja na koszt dla regeneracji, nawet bez dlugiego marszu."
            (longTotalDelta ?: 0.0) <= -20.0 || (longScoreDelta ?: 0.0) <= -3.0 ->
                "Dlugi marsz wyglada na osobny koszt regeneracyjny."
            else -> "Po rozdzieleniu obciazenia nie ma jednej wyraznej roznicy w nastepnym snie."
        }

        return TestedInsight(
            id = "activity_workout_sleep_load",
            domain = "Regeneracja",
            title = "Czy rodzaj obciazenia zmienia nastepny sen?",
            answer = interpretation,
            evidence = listOf(
                "pary aktywnosc -> sen: ${rows.size}",
                "typowy dzien: ${typical.days} dni, ${typical.avgSteps.format0()} krokow, sen ${typical.avgTotalSleepMinutes.formatMinutes()}, REM ${typical.avgRemMinutes.format0()}, score ${typical.avgSleepScore.format0()}",
                "wysokie kroki bez dlugiego marszu: ${highNoLong.days} dni, ${highNoLong.avgSteps.format0()} krokow, REM ${highRemDelta.formatSigned0()} min, score ${highScoreDelta.formatSigned0()}",
                "dlugi marsz 8+ km: ${longWalk.days} dni, ${longWalk.avgWalkingKm.format1()} km, sen ${longTotalDelta.formatSigned0()} min, score ${longScoreDelta.formatSigned0()}",
            ),
            dateRange = dateRangeForWorkoutSleepLoadRows(rows),
            sampleSize = rows.size,
            confidence = confidence,
            limitations = listOf(
                "to jest porownanie obserwacyjne, nie dowod przyczyny",
                "nie kontroluje pory dnia, pogody, pracy, stresu i trasy",
                "fazy snu z zegarka traktujemy jako trend",
            ),
            nextStep = "dodac godzine treningu i porownanie podobnych tras, zeby sprawdzic koszt regeneracyjny",
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

    private fun buildHeartContextInsight(
        rows: List<HeartContextRow>,
    ): TestedInsight {
        val avgBpmValues = rows.map { row -> row.avgBpm }
        val baselineAvg = avgBpmValues.averageOrNull()
        val baselineStdDev = avgBpmValues.standardDeviationOrNull()
        val highThreshold = baselineAvg?.let { avg -> avg + (baselineStdDev ?: 0.0) }
        val highRows = if (highThreshold == null) {
            emptyList()
        } else {
            rows.filter { row -> row.avgBpm >= highThreshold }
        }
        val normalRows = if (highThreshold == null) {
            emptyList()
        } else {
            rows.filter { row -> row.avgBpm < highThreshold }
        }
        val highSleep = highRows.mapNotNull { row -> row.totalSleepMinutes }.averageOrNull()
        val normalSleep = normalRows.mapNotNull { row -> row.totalSleepMinutes }.averageOrNull()
        val highSteps = highRows.map { row -> row.steps }.averageOrNull()
        val normalSteps = normalRows.map { row -> row.steps }.averageOrNull()
        val highWorkout = highRows.map { row -> row.workoutMinutes }.averageOrNull()
        val normalWorkout = normalRows.map { row -> row.workoutMinutes }.averageOrNull()
        val sleepDelta = highSleep.minusNullable(normalSleep)
        val stepsDelta = highSteps.minusNullable(normalSteps)
        val workoutDelta = highWorkout.minusNullable(normalWorkout)

        return TestedInsight(
            id = "heart_outlier_context",
            domain = "Puls",
            title = "Co widac w dniach z wyzszym srednim pulsem?",
            answer = when {
                rows.size < 45 -> "Dni z pulsem jest jeszcze za malo, zeby sensownie szukac odchylen od Twojego baseline."
                highRows.size < 5 -> "Wyzszy sredni puls pojawia sie rzadko, wiec na razie traktujemy go jako liste dni do sprawdzenia, nie trend."
                sleepDelta != null && sleepDelta <= -30.0 ->
                    "Dni z wyzszym srednim pulsem wygladaja w dostepnych parach jak dni z krotszym snem. To jest sygnal do dalszego testu, nie dowod przyczyny."
                stepsDelta != null && stepsDelta >= 1500.0 ->
                    "Dni z wyzszym srednim pulsem czesciej wygladaja jak dni z wiekszym obciazeniem ruchem. Trzeba rozdzielic trening, stres i jakosc pomiaru."
                else -> "Sa dni z wyzszym srednim pulsem, ale obecny kontekst snu i aktywnosci nie daje jednego mocnego wyjasnienia."
            },
            evidence = listOf(
                "pokrycie pulsu: ${rows.size} zamknietych dni",
                "baseline sredniego pulsu: ${baselineAvg.format0()} bpm, prog wysokiego dnia: ${highThreshold.format0()} bpm",
                "dni wysokiego pulsu: ${highRows.size}",
                "sredni puls: ${highRows.map { row -> row.avgBpm }.averageOrNull().format0()} vs ${normalRows.map { row -> row.avgBpm }.averageOrNull().format0()} bpm",
                "sen: ${highSleep.formatMinutes()} vs ${normalSleep.formatMinutes()} (${sleepDelta.formatSigned0()} min)",
                "kroki: ${highSteps.format0()} vs ${normalSteps.format0()} (${stepsDelta.formatSigned0()})",
                "trening: ${highWorkout.format0()} min vs ${normalWorkout.format0()} min (${workoutDelta.formatSigned0()} min)",
            ),
            dateRange = dateRangeForHeartRows(rows),
            sampleSize = rows.size,
            confidence = confidenceForSample(rows.size),
            limitations = listOf(
                "to jest sredni dzienny puls, nie puls spoczynkowy",
                "pokrycie historyczne pulsu jest nierowne",
                "wysoki puls moze oznaczac trening, stres, chorobe, kofeine albo blad pomiaru",
                "dzisiejszy czesciowy dzien nie jest uzyty w trendzie",
            ),
            nextStep = "dodac osobny test dni z pulsem powyzej baseline plus sen poprzedniej nocy i trening tego dnia",
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

private fun confidenceForSleepLoadGroups(
    typicalDays: Int,
    highNoLongDays: Int,
    longWalkDays: Int,
): AnalysisConfidence {
    return when {
        typicalDays < 20 || highNoLongDays < 10 || longWalkDays < 8 -> AnalysisConfidence.Insufficient
        typicalDays >= 80 && highNoLongDays >= 30 && longWalkDays >= 25 -> AnalysisConfidence.High
        typicalDays >= 30 && highNoLongDays >= 15 && longWalkDays >= 10 -> AnalysisConfidence.Medium
        else -> AnalysisConfidence.Low
    }
}

private fun dateRangeForSleepActivity(rows: List<SleepActivityFeatureRow>): String {
    if (rows.isEmpty()) {
        return "brak danych"
    }
    return "${rows.first().date} - ${rows.last().date}"
}

private fun dateRangeForWorkoutSleepLoadRows(rows: List<ActivityWorkoutSleepLoadRow>): String {
    if (rows.isEmpty()) {
        return "brak danych"
    }
    return "${rows.first().activityDate} - ${rows.last().sleepDate}"
}

private fun dateRangeForNextDayRows(rows: List<SleepNextDayActivityRow>): String {
    if (rows.isEmpty()) {
        return "brak danych"
    }
    return "${rows.first().date} - ${rows.last().date}"
}

private fun dateRangeForHeartRows(rows: List<HeartContextRow>): String {
    if (rows.isEmpty()) {
        return "brak danych"
    }
    return "${rows.first().date} - ${rows.last().date}"
}

private fun dateRangeForSleepWindowRows(rowsDescending: List<SleepWindowRow>): String {
    if (rowsDescending.isEmpty()) {
        return "brak danych"
    }
    return "${rowsDescending.last().date} - ${rowsDescending.first().date}"
}

private data class SleepLoadGroup(
    val label: String,
    val days: Int,
    val avgSteps: Double?,
    val avgWalkingKm: Double?,
    val avgTotalSleepMinutes: Double?,
    val avgRemMinutes: Double?,
    val avgDeepMinutes: Double?,
    val avgSleepScore: Double?,
) {
    companion object {
        fun from(
            label: String,
            rows: List<ActivityWorkoutSleepLoadRow>,
        ): SleepLoadGroup {
            return SleepLoadGroup(
                label = label,
                days = rows.size,
                avgSteps = rows.map { row -> row.steps }.averageOrNull(),
                avgWalkingKm = rows.map { row -> row.walkingKm }.averageOrNull(),
                avgTotalSleepMinutes = rows.map { row -> row.totalSleepMinutes }.averageOrNull(),
                avgRemMinutes = rows.mapNotNull { row -> row.remSleepMinutes }.averageOrNull(),
                avgDeepMinutes = rows.mapNotNull { row -> row.deepSleepMinutes }.averageOrNull(),
                avgSleepScore = rows.mapNotNull { row -> row.sleepScore }.averageOrNull(),
            )
        }
    }
}

private fun <T : Number> List<T>.averageOrNull(): Double? {
    if (isEmpty()) {
        return null
    }
    return map { number -> number.toDouble() }.average()
}

private fun List<Double>.standardDeviationOrNull(): Double? {
    if (size < 2) {
        return null
    }
    val mean = average()
    val variance = sumOf { value -> (value - mean) * (value - mean) } / size
    return sqrt(variance)
}

private fun Long.formatSteps(): String {
    return "%,d".format(this)
}

private fun Long.estimatedKm(profile: UserProfileEntity): Double {
    return if (profile.stepsPerKm > 0) {
        toDouble() / profile.stepsPerKm
    } else {
        0.0
    }
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
