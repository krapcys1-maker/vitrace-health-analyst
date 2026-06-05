from __future__ import annotations

import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = REPO_ROOT / "tools" / "offline_signal_report.py"


class OfflineSignalReportCliTest(unittest.TestCase):
    def test_report_excludes_current_partial_day_from_trends(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmpdir:
            db_path = Path(tmpdir) / "fixture.db"
            create_fixture_db(db_path)

            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--db",
                    str(db_path),
                    "--generated-for-date",
                    "2026-06-05",
                ],
                cwd=REPO_ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Closed-day rule: rows before `2026-06-05`", result.stdout)
        self.assertIn("| 2026 | 2 | 20,000 | 16.0 | 15.0 |", result.stdout)
        self.assertNotIn("1,019,999", result.stdout)
        self.assertIn("Walking Workout Baselines", result.stdout)

    def test_report_can_be_written_to_file(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmpdir:
            db_path = Path(tmpdir) / "fixture.db"
            output_path = Path(tmpdir) / "report.md"
            create_fixture_db(db_path)

            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--db",
                    str(db_path),
                    "--output",
                    str(output_path),
                    "--generated-for-date",
                    "2026-06-05",
                ],
                cwd=REPO_ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

            report = output_path.read_text(encoding="utf-8")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Wrote", result.stdout)
        self.assertIn("# VitaTrace Offline Signal Report", report)
        self.assertIn("Next Insight Candidates", report)


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
        con.execute(
            """
            INSERT INTO analysis_results
            (
                analysisType, scope, engineVersion, generatedForDate, summaryTitle,
                summaryText, confidence, sampleSize, resultJson, sourceCoverageJson,
                timeContextJson, isCurrent, pinned, createdAtEpochMs, updatedAtEpochMs
            )
            VALUES
            ('tested_insight', 'fixture', 'test', '2026-06-05', 'title',
             'text', 'High', 1, '{}', '{}', '{}', 1, 0, 1, 1)
            """
        )
        con.executemany(
            """
            INSERT INTO daily_activity_summaries
            (date, steps, distanceMeters, activeCaloriesKcal, source, syncedAtEpochMs)
            VALUES (?, ?, ?, ?, 'fixture', 1)
            """,
            [
                ("2026-06-01", 8000, 6000.0, 250.0),
                ("2026-06-02", 12000, 9000.0, 300.0),
                ("2026-06-05", 999999, 999999.0, 1.0),
            ],
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
            VALUES (?, NULL, NULL, ?, ?, ?, ?, 10, 1, ?, 1, 'fixture', 'fixture.csv', 'sleep', NULL, '{}', 1)
            """,
            [
                ("2026-06-01", 420, 50, 280, 70, 72),
                ("2026-06-02", 460, 70, 300, 80, 80),
                ("2026-06-05", 30, 1, 20, 1, 10),
            ],
        )
        con.execute(
            """
            INSERT INTO workout_sessions
            (
                sessionId, date, workoutType, sportName, durationSeconds,
                distanceMeters, activeCaloriesKcal, steps, avgHeartRateBpm,
                minHeartRateBpm, maxHeartRateBpm, avgPaceSecondsPerKm,
                avgCadence, vo2Max, source, rawSourceFile, rawPayloadJson,
                syncedAtEpochMs
            )
            VALUES
            ('walk-1', '2026-06-01', 'walking', 'Walking', 3600,
             5000.0, 220.0, 6500, 120.0, 90, 150, 720,
             110.0, 38.5, 'fixture', 'fixture.csv', '{}', 1)
            """
        )


if __name__ == "__main__":
    unittest.main()
