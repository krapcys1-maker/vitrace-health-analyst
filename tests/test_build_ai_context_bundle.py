from __future__ import annotations

import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = REPO_ROOT / "tools" / "build_ai_context_bundle.py"


class BuildAiContextBundleCliTest(unittest.TestCase):
    def test_bundle_contains_only_deterministic_context(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmpdir:
            db_path = Path(tmpdir) / "fixture.db"
            output_path = Path(tmpdir) / "ai-context.json"
            prompt_path = Path(tmpdir) / "prompt.md"
            create_fixture_db(db_path)

            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--db",
                    str(db_path),
                    "--output",
                    str(output_path),
                    "--prompt-output",
                    str(prompt_path),
                    "--generated-for-date",
                    "2026-06-05",
                ],
                cwd=REPO_ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

            bundle = json.loads(output_path.read_text(encoding="utf-8"))
            prompt = prompt_path.read_text(encoding="utf-8")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(bundle["schemaVersion"], "ai_context_bundle_v1")
        self.assertFalse(bundle["source"]["rawDataIncluded"])
        self.assertFalse(bundle["source"]["routeDataIncluded"])
        self.assertEqual(bundle["profile"]["ageYears"], 40)
        self.assertEqual(bundle["dataCoverage"]["activityDays"], 102)
        self.assertEqual(bundle["dataCoverage"]["sleepDetailNights"], 2)
        self.assertEqual(bundle["deterministicInsights"][0]["id"], "long_term_steps_km")
        self.assertEqual(bundle["engineFacts"]["activityOverTime"]["stepsPerKm"], 1250)
        self.assertEqual(bundle["engineFacts"]["activityOverTime"]["bestRecentMonth"]["period"], "2026-04")
        self.assertEqual(bundle["engineFacts"]["activityOverTime"]["bestRecentMonth"]["signal"], "peak")
        self.assertNotIn("999999", json.dumps(bundle["engineFacts"], ensure_ascii=True))
        self.assertEqual(bundle["engineFacts"]["walkingFitness"]["years"][-1]["period"], "2026")
        self.assertEqual(bundle["engineFacts"]["walkingFitness"]["years"][-1]["sessions"], 1)
        walking_band_trends = bundle["engineFacts"]["walkingFitness"]["distanceBandTrends"]
        self.assertEqual(walking_band_trends[0]["distanceBand"], "6-10 km")
        self.assertEqual(walking_band_trends[0]["current"]["period"], "2026")
        self.assertEqual(walking_band_trends[0]["previous"]["period"], "2025")
        self.assertEqual(walking_band_trends[0]["confidence"], "Insufficient")
        sleep_baseline = bundle["engineFacts"]["sleepBaseline"]
        self.assertTrue(sleep_baseline["available"])
        self.assertEqual(sleep_baseline["baselineWindow"]["nights"], 0)
        self.assertEqual(sleep_baseline["comparisons"][0]["window"]["label"], "last7")
        self.assertEqual(sleep_baseline["comparisons"][0]["confidence"], "Insufficient")
        self.assertIn("wearable sleep stages", sleep_baseline["interpretationGuard"])
        activity_sleep = bundle["engineFacts"]["activitySleepThresholds"]
        self.assertFalse(activity_sleep["available"])
        self.assertEqual(activity_sleep["pairs"], 1)
        workout_sleep = bundle["engineFacts"]["activityWorkoutSleepLoad"]
        self.assertFalse(workout_sleep["available"])
        self.assertEqual(workout_sleep["pairs"], 1)
        sleep_after_walks = bundle["engineFacts"]["sleepAfterLongWalks"]
        self.assertEqual(sleep_after_walks["thresholdKm"], 8.0)
        self.assertEqual(sleep_after_walks["longWalkSleep"]["days"], 1)
        self.assertEqual(sleep_after_walks["normalSleep"]["days"], 1)
        self.assertEqual(sleep_after_walks["delta"]["totalSleepMinutes"], -30.0)
        heart_load = bundle["engineFacts"]["heartLoad"]
        self.assertEqual(heart_load["totalHeartDays"], 2)
        self.assertEqual(heart_load["credibleHeartDays"], 1)
        self.assertEqual(heart_load["excludedLowCoverageDays"], 1)
        self.assertIn("activitySplit", heart_load)
        self.assertEqual(heart_load["activitySplit"]["confidence"], "Insufficient")
        self.assertNotIn("190.0", json.dumps(heart_load, ensure_ascii=True))
        self.assertIn("2026-06-05 as partial", bundle["source"]["closedDayRule"])
        self.assertIn("Do not diagnose", prompt)
        self.assertIn("Do not mention sync", prompt)
        self.assertIn("mainScreenCopy", prompt)
        self.assertNotIn("rawPayloadJson", prompt)
        self.assertNotIn("GPX", prompt)
        self.assertNotIn("private-route.gpx", prompt)


def create_fixture_db(db_path: Path) -> None:
    with sqlite3.connect(db_path) as con:
        con.executescript(
            """
            CREATE TABLE user_profile (
                id INTEGER PRIMARY KEY NOT NULL,
                sex TEXT NOT NULL,
                ageYears INTEGER NOT NULL,
                heightCm INTEGER NOT NULL,
                weightKg REAL NOT NULL,
                stepsPerKm INTEGER NOT NULL,
                source TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            );

            CREATE TABLE analysis_results (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                analysisType TEXT NOT NULL,
                scope TEXT NOT NULL,
                engineVersion TEXT NOT NULL,
                baselineStartDate TEXT,
                baselineEndDate TEXT,
                currentStartDate TEXT,
                currentEndDate TEXT,
                generatedForDate TEXT NOT NULL,
                summaryTitle TEXT NOT NULL,
                summaryText TEXT NOT NULL,
                confidence TEXT NOT NULL,
                sampleSize INTEGER NOT NULL,
                resultJson TEXT NOT NULL,
                sourceCoverageJson TEXT NOT NULL,
                timeContextJson TEXT NOT NULL,
                isCurrent INTEGER NOT NULL,
                pinned INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                supersededAtEpochMs INTEGER
            );

            CREATE TABLE daily_activity_summaries (
                date TEXT NOT NULL PRIMARY KEY,
                steps INTEGER NOT NULL,
                distanceMeters REAL NOT NULL,
                activeCaloriesKcal REAL NOT NULL,
                source TEXT NOT NULL,
                syncedAtEpochMs INTEGER NOT NULL
            );

            CREATE TABLE sleep_details (
                date TEXT NOT NULL PRIMARY KEY,
                bedtimeEpochMs INTEGER,
                wakeUpEpochMs INTEGER,
                totalSleepMinutes INTEGER NOT NULL,
                deepSleepMinutes INTEGER,
                lightSleepMinutes INTEGER,
                remSleepMinutes INTEGER,
                awakeMinutes INTEGER,
                awakeCount INTEGER,
                sleepScore INTEGER,
                segmentCount INTEGER NOT NULL,
                source TEXT NOT NULL,
                rawSourceFile TEXT NOT NULL,
                rawSourceKey TEXT NOT NULL,
                rawTimestampEpochMs INTEGER,
                rawPayloadJson TEXT NOT NULL,
                syncedAtEpochMs INTEGER NOT NULL
            );

            CREATE TABLE daily_heart_summaries (
                date TEXT NOT NULL PRIMARY KEY,
                sampleCount INTEGER NOT NULL,
                minBpm INTEGER,
                maxBpm INTEGER,
                avgBpm REAL,
                source TEXT NOT NULL,
                lastRecordAtEpochMs INTEGER,
                syncedAtEpochMs INTEGER NOT NULL
            );

            CREATE TABLE daily_workout_summaries (
                date TEXT NOT NULL PRIMARY KEY,
                sessionCount INTEGER NOT NULL,
                totalDurationMinutes INTEGER NOT NULL,
                source TEXT NOT NULL,
                lastRecordAtEpochMs INTEGER,
                syncedAtEpochMs INTEGER NOT NULL
            );

            CREATE TABLE daily_body_summaries (
                date TEXT NOT NULL PRIMARY KEY,
                latestWeightKg REAL,
                latestVo2Max REAL,
                latestSpo2Percent REAL,
                weightRecordCount INTEGER NOT NULL,
                vo2MaxRecordCount INTEGER NOT NULL,
                spo2RecordCount INTEGER NOT NULL,
                source TEXT NOT NULL,
                lastRecordAtEpochMs INTEGER,
                syncedAtEpochMs INTEGER NOT NULL
            );

            CREATE TABLE workout_sessions (
                sessionId TEXT NOT NULL PRIMARY KEY,
                date TEXT NOT NULL,
                workoutType TEXT NOT NULL,
                sportName TEXT NOT NULL,
                rawSportType INTEGER,
                startAtEpochMs INTEGER,
                endAtEpochMs INTEGER,
                durationSeconds INTEGER NOT NULL,
                distanceMeters REAL NOT NULL,
                activeCaloriesKcal REAL NOT NULL,
                totalCaloriesKcal REAL,
                steps INTEGER NOT NULL,
                avgHeartRateBpm REAL,
                minHeartRateBpm INTEGER,
                maxHeartRateBpm INTEGER,
                avgPaceSecondsPerKm INTEGER,
                minPaceSecondsPerKm INTEGER,
                maxPaceSecondsPerKm INTEGER,
                avgCadence REAL,
                maxCadence INTEGER,
                trainingEffect REAL,
                recoveryTime INTEGER,
                vo2Max REAL,
                gpxUrl TEXT,
                source TEXT NOT NULL,
                rawSourceFile TEXT NOT NULL,
                rawTimestampEpochMs INTEGER,
                rawPayloadJson TEXT NOT NULL,
                syncedAtEpochMs INTEGER NOT NULL
            );
            """
        )
        con.execute(
            """
            INSERT INTO user_profile
            (id, sex, ageYears, heightCm, weightKg, stepsPerKm, source, updatedAtEpochMs)
            VALUES (1, 'male', 40, 186, 88.0, 1250, 'fixture', 1)
            """
        )
        activity_rows = []
        for year_month, daily_steps in [
            ("2025-12", 10_000),
            ("2026-01", 10_000),
            ("2026-02", 10_000),
            ("2026-03", 10_000),
            ("2026-04", 20_000),
        ]:
            for day in range(1, 21):
                activity_rows.append(
                    (f"{year_month}-{day:02d}", daily_steps, float(daily_steps) * 0.8, 250.0)
                )
        activity_rows.extend(
            [
                ("2026-06-01", 8000, 6400.0, 250.0),
                ("2026-06-02", 10000, 8000.0, 320.0),
                ("2026-06-05", 999999, 999999.0, 999.0),
            ]
        )
        con.executemany(
            """
            INSERT INTO daily_activity_summaries
            (date, steps, distanceMeters, activeCaloriesKcal, source, syncedAtEpochMs)
            VALUES (?, ?, ?, ?, 'fixture', 1)
            """,
            activity_rows,
        )
        con.executemany(
            """
            INSERT INTO sleep_details
            (
                date, bedtimeEpochMs, wakeUpEpochMs, totalSleepMinutes, deepSleepMinutes,
                lightSleepMinutes, remSleepMinutes, awakeMinutes, awakeCount, sleepScore,
                segmentCount, source, rawSourceFile, rawSourceKey, rawTimestampEpochMs,
                rawPayloadJson, syncedAtEpochMs
            )
            VALUES (?, NULL, NULL, ?, ?, ?, ?, 10, 1, ?,
                    1, 'fixture', 'fixture.csv', 'sleep', NULL, '{"private":true}', 1)
            """,
            [
                ("2026-06-01", 430, 60, 280, 80, 75),
                ("2026-06-02", 400, 50, 270, 70, 68),
            ],
        )
        con.executemany(
            """
            INSERT INTO daily_heart_summaries
            (date, sampleCount, minBpm, maxBpm, avgBpm, source, lastRecordAtEpochMs, syncedAtEpochMs)
            VALUES (?, ?, ?, ?, ?, 'fixture', NULL, 1)
            """,
            [
                ("2026-06-01", 100, 60, 140, 85.0),
                ("2026-06-02", 1, 180, 200, 190.0),
            ],
        )
        con.execute(
            """
            INSERT INTO daily_body_summaries
            (date, latestWeightKg, latestVo2Max, latestSpo2Percent, weightRecordCount,
             vo2MaxRecordCount, spo2RecordCount, source, lastRecordAtEpochMs, syncedAtEpochMs)
            VALUES ('2026-06-01', 88.0, NULL, NULL, 1, 0, 0, 'fixture', NULL, 1)
            """
        )
        con.execute(
            """
            INSERT INTO workout_sessions
            (
                sessionId, date, workoutType, sportName, rawSportType, startAtEpochMs,
                endAtEpochMs, durationSeconds, distanceMeters, activeCaloriesKcal,
                totalCaloriesKcal, steps, avgHeartRateBpm, minHeartRateBpm,
                maxHeartRateBpm, avgPaceSecondsPerKm, minPaceSecondsPerKm,
                maxPaceSecondsPerKm, avgCadence, maxCadence, trainingEffect,
                recoveryTime, vo2Max, gpxUrl, source, rawSourceFile,
                rawTimestampEpochMs, rawPayloadJson, syncedAtEpochMs
            )
            VALUES
            ('walk-2025', '2025-12-10', 'walking', 'Walking', NULL, NULL,
             NULL, 7200, 9000.0, 650.0, NULL, 11200, 125.0, 92,
             154, 800, NULL, NULL, 108.0, NULL, NULL,
             NULL, 38.0, 'private-route.gpx', 'fixture', 'fixture.csv', NULL, '{"private":true}', 1),
            ('walk-1', '2026-06-01', 'walking', 'Walking', NULL, NULL,
             NULL, 5400, 9000.0, 420.0, NULL, 11000, 120.0, 90,
             150, 720, NULL, NULL, 110.0, NULL, NULL,
             NULL, 38.5, 'private-route.gpx', 'fixture', 'fixture.csv', NULL, '{"private":true}', 1)
            """
        )
        con.execute(
            """
            INSERT INTO analysis_results
            (
                analysisType, scope, engineVersion, generatedForDate, summaryTitle,
                summaryText, confidence, sampleSize, resultJson, sourceCoverageJson,
                timeContextJson, isCurrent, pinned, createdAtEpochMs, updatedAtEpochMs
            )
            VALUES
            ('tested_insight', 'long_term_steps_km', 'test', '2026-06-05',
             'Ile chodzenia widac w skali lat?',
             'Roczne i miesieczne kroki sa najmocniejszym sygnalem.',
             'High', 2, ?, '{"source":"fixture"}',
             '{"generatedForDate":"2026-06-05"}', 1, 0, 1, 1)
            """,
            (
                json.dumps(
                    {
                        "id": "long_term_steps_km",
                        "domain": "Aktywnosc",
                        "title": "Ile chodzenia widac w skali lat?",
                        "answer": "Roczne i miesieczne kroki sa najmocniejszym sygnalem.",
                        "evidence": ["2026: 18000 krokow"],
                        "dateRange": "2026-06-01 - 2026-06-02",
                        "sampleSize": 2,
                        "confidence": "High",
                        "limitations": ["fixture limitation"],
                        "nextStep": "fixture next step",
                    }
                ),
            ),
        )


if __name__ == "__main__":
    unittest.main()
