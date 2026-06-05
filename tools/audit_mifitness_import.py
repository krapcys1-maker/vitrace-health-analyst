#!/usr/bin/env python3
"""Audit a VitaTrace database against a private Mi Fitness export.

This tool is local-only. It reads private Mi Fitness CSV files and a copied
VitaTrace SQLite database, then verifies that normalized daily summaries match
the canonical Mi Fitness daily reports and workout records.
"""

from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import json
import sqlite3
import sys
from pathlib import Path
from typing import Any


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--export-dir", required=True, type=Path)
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--timezone-offset-hours", default=3, type=int)
    parser.add_argument("--show-months", action="store_true")
    args = parser.parse_args()

    zone = dt.timezone(dt.timedelta(hours=args.timezone_offset_hours))
    export_dir: Path = args.export_dir
    db_path: Path = args.db

    aggregated_file = find_one(export_dir, "*hlth_center_aggregated_fitness_data.csv")
    sport_file = find_one(export_dir, "*hlth_center_sport_record.csv")

    expected = read_expected(aggregated_file, sport_file, zone)
    actual = read_actual(db_path)
    report = build_report(expected, actual)

    print_report(db_path, report, args.show_months)
    sys.exit(1 if report.has_critical_mismatches else 0)


def find_one(directory: Path, pattern: str) -> Path:
    matches = sorted(directory.glob(pattern))
    if not matches:
        raise FileNotFoundError(f"Missing {pattern} in {directory}")
    return matches[0]


def read_expected(
    aggregated_file: Path,
    sport_file: Path,
    zone: dt.tzinfo,
) -> dict[str, dict[str, Any]]:
    expected: dict[str, dict[str, Any]] = {
        "activity": {},
        "heart": {},
        "sleep": {},
        "spo2": {},
        "workout": collections.defaultdict(lambda: [0, 0]),
    }

    with aggregated_file.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            if row["Tag"] != "daily_report":
                continue

            value = parse_json(row.get("Value"))
            date = date_from_epoch(row["Time"], zone)
            key = row["Key"]

            if key == "steps":
                expected["activity"][date] = (
                    optional_int(value.get("steps")) or 0,
                    optional_float(value.get("distance")) or 0.0,
                    optional_float(value.get("calories")) or 0.0,
                )
            elif key == "heart_rate":
                expected["heart"][date] = (
                    optional_float(value.get("avg_hr")),
                    optional_int(value.get("min_hr")),
                    optional_int(value.get("max_hr")),
                )
            elif key == "sleep":
                expected["sleep"][date] = optional_int(value.get("total_duration")) or 0
            elif key == "spo2":
                expected["spo2"][date] = optional_float(value.get("avg_spo2"))

    with sport_file.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            value = parse_json(row.get("Value"))
            timestamp = int(value.get("start_time") or value.get("time") or row.get("Time") or 0)
            if timestamp <= 0:
                continue
            date = date_from_epoch(timestamp, zone)
            duration_seconds = int(float(value.get("duration") or 0))
            expected["workout"][date][0] += 1
            expected["workout"][date][1] += duration_seconds // 60

    expected["workout"] = dict(expected["workout"])
    return expected


def read_actual(db_path: Path) -> dict[str, dict[str, Any]]:
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        return {
            "activity": {
                row["date"]: (
                    row["steps"],
                    row["distanceMeters"],
                    row["activeCaloriesKcal"],
                )
                for row in con.execute(
                    "SELECT date, steps, distanceMeters, activeCaloriesKcal "
                    "FROM daily_activity_summaries"
                )
            },
            "heart": {
                row["date"]: (
                    row["avgBpm"],
                    row["minBpm"],
                    row["maxBpm"],
                    row["sampleCount"],
                )
                for row in con.execute(
                    "SELECT date, avgBpm, minBpm, maxBpm, sampleCount "
                    "FROM daily_heart_summaries"
                )
            },
            "sleep": {
                row["date"]: (
                    row["totalSleepMinutes"],
                    row["sessionCount"],
                )
                for row in con.execute(
                    "SELECT date, totalSleepMinutes, sessionCount "
                    "FROM daily_sleep_summaries"
                )
            },
            "spo2": {
                row["date"]: (
                    row["latestSpo2Percent"],
                    row["spo2RecordCount"],
                )
                for row in con.execute(
                    "SELECT date, latestSpo2Percent, spo2RecordCount "
                    "FROM daily_body_summaries"
                )
            },
            "body_count": {
                "nonzero": con.execute(
                    "SELECT COUNT(*) FROM daily_body_summaries "
                    "WHERE weightRecordCount > 0 OR vo2MaxRecordCount > 0 OR spo2RecordCount > 0"
                ).fetchone()[0]
            },
            "workout": {
                row["date"]: (
                    row["sessionCount"],
                    row["totalDurationMinutes"],
                )
                for row in con.execute(
                    "SELECT date, sessionCount, totalDurationMinutes "
                    "FROM daily_workout_summaries"
                )
            },
            "profile": dict(con.execute("SELECT * FROM user_profile").fetchone()),
        }
    finally:
        con.close()


class AuditReport:
    def __init__(self) -> None:
        self.activity_bad: list[Any] = []
        self.activity_extra: dict[str, Any] = {}
        self.month_bad: list[Any] = []
        self.year_bad: list[Any] = []
        self.years: list[tuple[str, int]] = []
        self.months: list[tuple[str, int]] = []
        self.heart_bad: list[Any] = []
        self.heart_extra: dict[str, Any] = {}
        self.sleep_bad: list[Any] = []
        self.sleep_extra: dict[str, Any] = {}
        self.spo2_bad: list[Any] = []
        self.spo2_extra: dict[str, Any] = {}
        self.workout_bad: list[Any] = []
        self.workout_extra: dict[str, Any] = {}
        self.counts: dict[str, int] = {}
        self.profile: dict[str, Any] = {}

    @property
    def has_critical_mismatches(self) -> bool:
        return any(
            [
                self.activity_bad,
                self.activity_extra,
                self.month_bad,
                self.year_bad,
                self.heart_bad,
                self.sleep_bad,
                self.spo2_bad,
                self.workout_bad,
                self.workout_extra,
            ]
        )


def build_report(expected: dict[str, dict[str, Any]], actual: dict[str, dict[str, Any]]) -> AuditReport:
    report = AuditReport()

    expected_steps = {date: value[0] for date, value in expected["activity"].items()}
    actual_steps = {
        date: value[0]
        for date, value in actual["activity"].items()
        if value[0] or value[1] or value[2]
    }

    for date, expected_value in expected["activity"].items():
        actual_value = actual["activity"].get(date)
        if (
            actual_value is None
            or actual_value[0] != expected_value[0]
            or not close(actual_value[1], expected_value[1], 0.01)
            or not close(actual_value[2], expected_value[2], 0.01)
        ):
            report.activity_bad.append((date, expected_value, actual_value))

    report.activity_extra = {
        date: value
        for date, value in actual["activity"].items()
        if (value[0] or value[1] or value[2]) and date not in expected["activity"]
    }
    expected_months = period(expected_steps, 7)
    actual_months = period(actual_steps, 7)
    expected_years = period(expected_steps, 4)
    actual_years = period(actual_steps, 4)
    report.month_bad = diff_periods(expected_months, actual_months)
    report.year_bad = diff_periods(expected_years, actual_years)
    report.years = sorted(expected_years.items())
    report.months = sorted(expected_months.items())

    for date, expected_value in expected["heart"].items():
        actual_value = actual["heart"].get(date)
        if (
            actual_value is None
            or not close(actual_value[0], expected_value[0], 0.001)
            or actual_value[1] != expected_value[1]
            or actual_value[2] != expected_value[2]
        ):
            report.heart_bad.append((date, expected_value, actual_value))
    report.heart_extra = {
        date: value for date, value in actual["heart"].items() if value[3] and date not in expected["heart"]
    }

    for date, expected_value in expected["sleep"].items():
        actual_value = actual["sleep"].get(date)
        if actual_value is None or actual_value[0] != expected_value or (expected_value > 0 and actual_value[1] < 1):
            report.sleep_bad.append((date, expected_value, actual_value))
    report.sleep_extra = {
        date: value for date, value in actual["sleep"].items() if value[0] and date not in expected["sleep"]
    }

    for date, expected_value in expected["spo2"].items():
        actual_value = actual["spo2"].get(date)
        if actual_value is None or not close(actual_value[0], expected_value, 0.001):
            report.spo2_bad.append((date, expected_value, actual_value))
    report.spo2_extra = {
        date: value for date, value in actual["spo2"].items() if value[1] and date not in expected["spo2"]
    }

    for date, expected_value in expected["workout"].items():
        actual_value = actual["workout"].get(date)
        if actual_value is None or actual_value[0] != expected_value[0] or actual_value[1] != expected_value[1]:
            report.workout_bad.append((date, tuple(expected_value), actual_value))
    report.workout_extra = {
        date: value
        for date, value in actual["workout"].items()
        if (value[0] or value[1]) and date not in expected["workout"]
    }

    report.counts = {
        "activity_days": len(expected["activity"]),
        "heart_report_days": len(expected["heart"]),
        "sleep_report_days": len(expected["sleep"]),
        "spo2_report_days": len(expected["spo2"]),
        "workout_days": len(expected["workout"]),
        "workout_sessions": sum(value[0] for value in expected["workout"].values()),
        "body_nonzero_rows": actual["body_count"]["nonzero"],
    }
    report.profile = actual["profile"]
    return report


def print_report(db_path: Path, report: AuditReport, show_months: bool) -> None:
    print(f"Audit DB: {db_path}")
    print(
        "Activity: "
        f"days={report.counts['activity_days']} "
        f"daily_bad={len(report.activity_bad)} "
        f"month_bad={len(report.month_bad)} "
        f"year_bad={len(report.year_bad)} "
        f"extra_positive_days={len(report.activity_extra)}"
    )
    print("Activity years:")
    for year, steps in report.years:
        print(f"  {year}: {steps} steps, {steps / report.profile['stepsPerKm']:.1f} km")

    top_months = sorted(report.months, key=lambda item: item[1], reverse=True)[:12]
    print("Top months:")
    for month, steps in top_months:
        print(f"  {month}: {steps} steps, {steps / report.profile['stepsPerKm']:.1f} km")

    if show_months:
        print("All months:")
        for month, steps in report.months:
            print(f"  {month}: {steps} steps, {steps / report.profile['stepsPerKm']:.1f} km")

    print(
        "Heart: "
        f"report_days={report.counts['heart_report_days']} "
        f"bad={len(report.heart_bad)} "
        f"extra_raw_days={len(report.heart_extra)}"
    )
    print(
        "Sleep: "
        f"report_days={report.counts['sleep_report_days']} "
        f"bad={len(report.sleep_bad)} "
        f"extra_raw_days={len(report.sleep_extra)}"
    )
    print(
        "SpO2: "
        f"report_days={report.counts['spo2_report_days']} "
        f"bad={len(report.spo2_bad)} "
        f"extra_raw_days={len(report.spo2_extra)}"
    )
    print(
        "Workouts: "
        f"days={report.counts['workout_days']} "
        f"sessions={report.counts['workout_sessions']} "
        f"bad={len(report.workout_bad)} "
        f"extra_days={len(report.workout_extra)}"
    )
    print(f"Body rows with data: {report.counts['body_nonzero_rows']}")
    print(f"Profile: {report.profile}")

    if report.has_critical_mismatches:
        print("Mismatch samples:")
        print(
            {
                "activity_bad": report.activity_bad[:5],
                "month_bad": report.month_bad[:10],
                "year_bad": report.year_bad,
                "activity_extra": list(sorted(report.activity_extra.items()))[:5],
                "heart_bad": report.heart_bad[:5],
                "sleep_bad": report.sleep_bad[:5],
                "spo2_bad": report.spo2_bad[:5],
                "workout_bad": report.workout_bad[:5],
            }
        )


def parse_json(raw: str | None) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return value if isinstance(value, dict) else {}


def date_from_epoch(epoch_seconds: int | str, zone: dt.tzinfo) -> str:
    return dt.datetime.fromtimestamp(int(epoch_seconds), zone).date().isoformat()


def optional_int(value: Any) -> int | None:
    if value in (None, ""):
        return None
    return int(float(value))


def optional_float(value: Any) -> float | None:
    if value in (None, ""):
        return None
    return float(value)


def close(left: float | None, right: float | None, tolerance: float) -> bool:
    if left is None and right is None:
        return True
    if left is None or right is None:
        return False
    return abs(float(left) - float(right)) <= tolerance


def period(values: dict[str, int], prefix_length: int) -> collections.Counter[str]:
    result: collections.Counter[str] = collections.Counter()
    for date, value in values.items():
        result[date[:prefix_length]] += value
    return result


def diff_periods(
    expected: collections.Counter[str],
    actual: collections.Counter[str],
) -> list[tuple[str, int, int, int]]:
    keys = sorted(set(expected) | set(actual))
    return [
        (key, expected.get(key, 0), actual.get(key, 0), actual.get(key, 0) - expected.get(key, 0))
        for key in keys
        if expected.get(key, 0) != actual.get(key, 0)
    ]


if __name__ == "__main__":
    main()
