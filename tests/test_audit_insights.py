from __future__ import annotations

import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = REPO_ROOT / "tools" / "audit_insights.py"
EXPECTED_SCOPES = [
    "activity_sleep_same_night",
    "data_coverage_reality",
    "sleep_next_day_activity",
    "training_day_sleep",
    "walking_efficiency_by_distance_band",
]


class AuditInsightsCliTest(unittest.TestCase):
    def test_valid_database_passes(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmpdir:
            db_path = Path(tmpdir) / "vitrace-test.db"
            create_fixture_db(db_path)

            result = run_audit(db_path)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("OK: insight snapshots are structurally valid.", result.stdout)
        self.assertIn("paired closed days: 3", result.stdout)

    def test_new_known_engine_scopes_pass(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmpdir:
            db_path = Path(tmpdir) / "vitrace-test.db"
            create_fixture_db(
                db_path,
                extra_scopes=["long_term_steps_km", "sleep_monthly_baseline"],
            )

            result = run_audit(db_path)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("long_term_steps_km", result.stdout)
        self.assertIn("sleep_monthly_baseline", result.stdout)

    def test_unknown_engine_scope_fails(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmpdir:
            db_path = Path(tmpdir) / "vitrace-test.db"
            create_fixture_db(db_path, extra_scopes=["made_up_scope"])

            result = run_audit(db_path)

        self.assertEqual(result.returncode, 1)
        self.assertIn("unexpected current tested insights", result.stdout)
        self.assertIn("made_up_scope", result.stdout)

    def test_missing_required_insight_fails(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmpdir:
            db_path = Path(tmpdir) / "vitrace-test.db"
            create_fixture_db(db_path, omitted_scope="training_day_sleep")

            result = run_audit(db_path)

        self.assertEqual(result.returncode, 1)
        self.assertIn("missing current tested insights", result.stdout)
        self.assertIn("training_day_sleep", result.stdout)

    def test_invalid_result_json_fails(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmpdir:
            db_path = Path(tmpdir) / "vitrace-test.db"
            create_fixture_db(db_path, broken_scope="data_coverage_reality")

            result = run_audit(db_path)

        self.assertEqual(result.returncode, 1)
        self.assertIn("data_coverage_reality.resultJson: invalid JSON", result.stdout)


def run_audit(db_path: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--db", str(db_path)],
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def create_fixture_db(
    db_path: Path,
    omitted_scope: str | None = None,
    broken_scope: str | None = None,
    extra_scopes: list[str] | None = None,
) -> None:
    with sqlite3.connect(db_path) as con:
        create_schema(con)
        insert_metric_rows(con)
        scopes = EXPECTED_SCOPES + (extra_scopes or [])
        for index, scope in enumerate(scopes, start=1):
            if scope == omitted_scope:
                continue
            insert_insight_row(
                con=con,
                row_id=index,
                scope=scope,
                result_json="not-json" if scope == broken_scope else valid_result_json(scope),
            )


def create_schema(con: sqlite3.Connection) -> None:
    con.executescript(
        """
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


def insert_metric_rows(con: sqlite3.Connection) -> None:
    activity_rows = [
        ("2026-06-01", 8000, 6400.0, 250.0),
        ("2026-06-02", 10000, 8000.0, 320.0),
        ("2026-06-03", 12000, 9600.0, 410.0),
        ("2026-06-05", 500, 400.0, 20.0),
    ]
    con.executemany(
        """
        INSERT INTO daily_activity_summaries
        (date, steps, distanceMeters, activeCaloriesKcal, source, syncedAtEpochMs)
        VALUES (?, ?, ?, ?, 'fixture', 1)
        """,
        activity_rows,
    )

    sleep_rows = [
        ("2026-06-01", 430, 60, 280, 80, 75),
        ("2026-06-02", 450, 70, 290, 90, 80),
        ("2026-06-03", 470, 75, 300, 95, 85),
        ("2026-06-05", 60, 5, 40, 5, 50),
    ]
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
        sleep_rows,
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
        ('walk-1', '2026-06-01', 'walking', 'Walking', NULL, NULL,
         NULL, 3600, 5000.0, 220.0, NULL, 6500, 120.0, 90,
         150, 720, NULL, NULL, 110.0, NULL, NULL,
         NULL, 38.5, NULL, 'fixture', 'fixture.csv', NULL, '{}', 1)
        """
    )


def insert_insight_row(con: sqlite3.Connection, row_id: int, scope: str, result_json: str) -> None:
    con.execute(
        """
        INSERT INTO analysis_results
        (
            id, analysisType, scope, engineVersion, baselineStartDate, baselineEndDate,
            currentStartDate, currentEndDate, generatedForDate, summaryTitle, summaryText,
            confidence, sampleSize, resultJson, sourceCoverageJson, timeContextJson,
            isCurrent, pinned, createdAtEpochMs, updatedAtEpochMs, supersededAtEpochMs
        )
        VALUES
        (?, 'tested_insight', ?, 'tested_insight_test', NULL, NULL,
         '2026-06-01', '2026-06-03', '2026-06-05', 'Fixture title',
         'Fixture answer', 'High', 3, ?, '{"source":"fixture"}',
         '{"generatedForDate":"2026-06-05"}', 1, 0, 1, 1, NULL)
        """,
        (row_id, scope, result_json),
    )


def valid_result_json(scope: str) -> str:
    return json.dumps(
        {
            "id": scope,
            "domain": "Test",
            "title": "Fixture title",
            "answer": "Fixture answer",
            "evidence": ["sample evidence"],
            "dateRange": "2026-06-01 - 2026-06-03",
            "sampleSize": 3,
            "confidence": "High",
            "limitations": ["fixture limitation"],
            "nextStep": "fixture next step",
        }
    )


if __name__ == "__main__":
    unittest.main()
