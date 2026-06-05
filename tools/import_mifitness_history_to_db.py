#!/usr/bin/env python3
"""Import private Mi Fitness export aggregates into a VitaTrace SQLite database.

This tool is intentionally local-only. It reads a private Mi Fitness export
directory and writes normalized daily aggregates into the app database copied
from an Android device. Do not commit the export files or generated databases.
"""

from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import json
import sqlite3
from pathlib import Path
from typing import Any


SOURCE = "MI_FITNESS_EXPORT"
PROFILE_SOURCE = "USER_PROVIDED"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--export-dir", required=True, type=Path)
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--age-years", default=40, type=int)
    parser.add_argument("--height-cm", default=186, type=int)
    parser.add_argument("--weight-kg", default=88.0, type=float)
    parser.add_argument("--steps-per-km", default=1250, type=int)
    parser.add_argument("--timezone-offset-hours", default=3, type=int)
    args = parser.parse_args()

    zone = dt.timezone(dt.timedelta(hours=args.timezone_offset_hours))
    export_dir: Path = args.export_dir
    db_path: Path = args.db

    fitness_file = find_one(export_dir, "*hlth_center_fitness_data.csv")
    sport_file = find_one(export_dir, "*hlth_center_sport_record.csv")

    aggregates = build_daily_aggregates(fitness_file, sport_file, zone)
    now_ms = int(dt.datetime.now(tz=dt.timezone.utc).timestamp() * 1000)

    with sqlite3.connect(db_path) as con:
        con.execute("PRAGMA journal_mode=DELETE")
        upsert_profile(
            con=con,
            age_years=args.age_years,
            height_cm=args.height_cm,
            weight_kg=args.weight_kg,
            steps_per_km=args.steps_per_km,
            updated_at_ms=now_ms,
        )
        upsert_daily_tables(con, aggregates, now_ms)
        con.commit()

    print_summary(aggregates, args.steps_per_km)


def find_one(directory: Path, pattern: str) -> Path:
    matches = sorted(directory.glob(pattern))
    if not matches:
        raise FileNotFoundError(f"Missing {pattern} in {directory}")
    return matches[0]


def build_daily_aggregates(
    fitness_file: Path,
    sport_file: Path,
    zone: dt.tzinfo,
) -> dict[str, dict[str, Any]]:
    daily: dict[str, dict[str, Any]] = collections.defaultdict(new_day)

    with fitness_file.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            key = row["Key"]
            value = parse_json(row.get("Value"))
            timestamp = int(value.get("time") or row["Time"] or 0)
            if timestamp <= 0:
                continue
            date = dt.datetime.fromtimestamp(timestamp, zone).date().isoformat()
            day = daily[date]

            if key == "steps":
                day["steps"] += int(float(value.get("steps") or 0))
                day["distanceMeters"] += float(value.get("distance") or 0)
                day["activeCaloriesKcal"] += float(value.get("calories") or 0)
            elif key == "heart_rate":
                bpm = value.get("bpm")
                if bpm not in (None, ""):
                    day["heart"].append(int(float(bpm)))
            elif key == "watch_night_sleep":
                duration = value.get("duration")
                if duration not in (None, ""):
                    day["sleepSessions"] += 1
                    day["sleepMinutes"] += int(float(duration))
                    day["sleepLast"] = max(day["sleepLast"] or 0, timestamp)
            elif key == "weight":
                weight = value.get("weight")
                if weight not in (None, ""):
                    day["weight"] = float(weight)
                    day["weightCount"] += 1
                    day["bodyLast"] = max(day["bodyLast"] or 0, timestamp)
            elif key == "vo2_max":
                vo2 = value.get("vo2_max")
                if vo2 not in (None, ""):
                    day["vo2"] = float(vo2)
                    day["vo2Count"] += 1
                    day["bodyLast"] = max(day["bodyLast"] or 0, timestamp)
            elif key == "single_spo2":
                spo2 = value.get("spo2")
                if spo2 not in (None, ""):
                    day["spo2"] = float(spo2)
                    day["spo2Count"] += 1
                    day["bodyLast"] = max(day["bodyLast"] or 0, timestamp)

    with sport_file.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            value = parse_json(row.get("Value"))
            timestamp = int(value.get("start_time") or value.get("time") or row.get("Time") or 0)
            if timestamp <= 0:
                continue
            date = dt.datetime.fromtimestamp(timestamp, zone).date().isoformat()
            day = daily[date]
            duration_seconds = int(float(value.get("duration") or 0))
            day["workoutSessions"] += 1
            day["workoutMinutes"] += duration_seconds // 60
            end_time = int(value.get("end_time") or timestamp + duration_seconds)
            day["workoutLast"] = max(day["workoutLast"] or 0, end_time)

            vo2 = value.get("vo2_max")
            if vo2 not in (None, "", 0, "0"):
                day["vo2"] = float(vo2)
                day["vo2Count"] += 1
                day["bodyLast"] = max(day["bodyLast"] or 0, timestamp)

    return daily


def new_day() -> dict[str, Any]:
    return {
        "steps": 0,
        "distanceMeters": 0.0,
        "activeCaloriesKcal": 0.0,
        "heart": [],
        "sleepSessions": 0,
        "sleepMinutes": 0,
        "sleepLast": None,
        "workoutSessions": 0,
        "workoutMinutes": 0,
        "workoutLast": None,
        "weight": None,
        "vo2": None,
        "spo2": None,
        "weightCount": 0,
        "vo2Count": 0,
        "spo2Count": 0,
        "bodyLast": None,
    }


def parse_json(raw: str | None) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return value if isinstance(value, dict) else {}


def upsert_profile(
    con: sqlite3.Connection,
    age_years: int,
    height_cm: int,
    weight_kg: float,
    steps_per_km: int,
    updated_at_ms: int,
) -> None:
    con.execute(
        """
        INSERT OR REPLACE INTO user_profile (
            id, sex, ageYears, heightCm, weightKg, stepsPerKm, source, updatedAtEpochMs
        ) VALUES (1, 'male', ?, ?, ?, ?, ?, ?)
        """,
        (age_years, height_cm, weight_kg, steps_per_km, PROFILE_SOURCE, updated_at_ms),
    )


def upsert_daily_tables(
    con: sqlite3.Connection,
    daily: dict[str, dict[str, Any]],
    synced_at_ms: int,
) -> None:
    for date, day in sorted(daily.items()):
        con.execute(
            """
            INSERT OR REPLACE INTO daily_activity_summaries (
                date, steps, distanceMeters, activeCaloriesKcal, source, syncedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                date,
                day["steps"],
                day["distanceMeters"],
                day["activeCaloriesKcal"],
                SOURCE if has_activity(day) else "none",
                synced_at_ms,
            ),
        )

        heart_values = day["heart"]
        con.execute(
            """
            INSERT OR REPLACE INTO daily_heart_summaries (
                date, sampleCount, minBpm, maxBpm, avgBpm, source, lastRecordAtEpochMs, syncedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                date,
                len(heart_values),
                min(heart_values) if heart_values else None,
                max(heart_values) if heart_values else None,
                (sum(heart_values) / len(heart_values)) if heart_values else None,
                SOURCE if heart_values else "none",
                None,
                synced_at_ms,
            ),
        )

        con.execute(
            """
            INSERT OR REPLACE INTO daily_sleep_summaries (
                date, sessionCount, totalSleepMinutes, source, lastRecordAtEpochMs, syncedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                date,
                day["sleepSessions"],
                day["sleepMinutes"],
                SOURCE if day["sleepSessions"] > 0 else "none",
                epoch_ms(day["sleepLast"]),
                synced_at_ms,
            ),
        )

        con.execute(
            """
            INSERT OR REPLACE INTO daily_workout_summaries (
                date, sessionCount, totalDurationMinutes, source, lastRecordAtEpochMs, syncedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                date,
                day["workoutSessions"],
                day["workoutMinutes"],
                SOURCE if day["workoutSessions"] > 0 else "none",
                epoch_ms(day["workoutLast"]),
                synced_at_ms,
            ),
        )

        con.execute(
            """
            INSERT OR REPLACE INTO daily_body_summaries (
                date, latestWeightKg, latestVo2Max, latestSpo2Percent,
                weightRecordCount, vo2MaxRecordCount, spo2RecordCount,
                source, lastRecordAtEpochMs, syncedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                date,
                day["weight"],
                day["vo2"],
                day["spo2"],
                day["weightCount"],
                day["vo2Count"],
                day["spo2Count"],
                SOURCE if has_body(day) else "none",
                epoch_ms(day["bodyLast"]),
                synced_at_ms,
            ),
        )


def has_activity(day: dict[str, Any]) -> bool:
    return day["steps"] > 0 or day["distanceMeters"] > 0 or day["activeCaloriesKcal"] > 0


def has_body(day: dict[str, Any]) -> bool:
    return day["weightCount"] > 0 or day["vo2Count"] > 0 or day["spo2Count"] > 0


def epoch_ms(epoch_seconds: int | None) -> int | None:
    return None if not epoch_seconds else int(epoch_seconds) * 1000


def print_summary(daily: dict[str, dict[str, Any]], steps_per_km: int) -> None:
    years: collections.Counter[str] = collections.Counter()
    months: collections.Counter[str] = collections.Counter()
    for date, day in daily.items():
        years[date[:4]] += day["steps"]
        months[date[:7]] += day["steps"]

    print("Imported daily rows:", len(daily))
    print("Years:")
    for year in sorted(years):
        steps = years[year]
        print(f"  {year}: {steps} steps, {steps / steps_per_km:.1f} estimated km")
    if months:
        best_month, best_steps = max(months.items(), key=lambda item: item[1])
        print(f"Best month: {best_month}: {best_steps} steps, {best_steps / steps_per_km:.1f} estimated km")


if __name__ == "__main__":
    main()
