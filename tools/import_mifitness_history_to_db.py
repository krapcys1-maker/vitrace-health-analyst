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
    aggregated_file = find_one(export_dir, "*hlth_center_aggregated_fitness_data.csv")
    sport_file = find_one(export_dir, "*hlth_center_sport_record.csv")
    sport_track_file = find_optional(export_dir, "*hlth_center_sport_track_data.csv")

    aggregates = build_daily_aggregates(fitness_file, aggregated_file, sport_file, zone)
    sleep_details = build_sleep_details(aggregated_file, zone)
    workout_sessions = build_workout_sessions(sport_file, sport_track_file, zone)
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
        upsert_detail_tables(con, sleep_details, workout_sessions, now_ms)
        upsert_daily_tables(con, aggregates, now_ms)
        con.commit()

    print_summary(aggregates, args.steps_per_km)
    print_detail_summary(sleep_details, workout_sessions)


def find_one(directory: Path, pattern: str) -> Path:
    matches = sorted(directory.glob(pattern))
    if not matches:
        raise FileNotFoundError(f"Missing {pattern} in {directory}")
    return matches[0]


def find_optional(directory: Path, pattern: str) -> Path | None:
    matches = sorted(directory.glob(pattern))
    return matches[0] if matches else None


def build_daily_aggregates(
    fitness_file: Path,
    aggregated_file: Path,
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
                continue
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

    apply_aggregated_daily_reports(daily, aggregated_file, zone)

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
            workout_type = map_workout_type(value.get("sport_type"))
            if workout_type is not None:
                typed = day["workoutTypes"][workout_type]
                typed["sessions"] += 1
                typed["minutes"] += duration_seconds // 60
                typed["distanceMeters"] += optional_float(value.get("distance")) or 0.0
                typed["activeCaloriesKcal"] += optional_float(value.get("calories")) or 0.0
                typed["steps"] += optional_int(value.get("steps")) or 0
                typed["last"] = max(typed["last"] or 0, end_time)
                avg_hrm = optional_float(value.get("avg_hrm"))
                if avg_hrm is not None and avg_hrm > 0:
                    weight = max(duration_seconds, 1)
                    typed["heartWeightedSum"] += avg_hrm * weight
                    typed["heartWeight"] += weight

            vo2 = value.get("vo2_max")
            if vo2 not in (None, "", 0, "0"):
                day["vo2"] = float(vo2)
                day["vo2Count"] += 1
                day["bodyLast"] = max(day["bodyLast"] or 0, timestamp)

    return daily


def build_sleep_details(aggregated_file: Path, zone: dt.tzinfo) -> list[dict[str, Any]]:
    details: list[dict[str, Any]] = []
    with aggregated_file.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            if row["Tag"] != "daily_report" or row["Key"] != "sleep":
                continue

            raw_payload = row.get("Value") or "{}"
            value = parse_json(raw_payload)
            timestamp = optional_int(row.get("Time"))
            if not timestamp:
                continue
            date = dt.datetime.fromtimestamp(timestamp, zone).date().isoformat()
            segments = value.get("segment_details")
            segment_list = [segment for segment in segments if isinstance(segment, dict)] if isinstance(segments, list) else []
            bedtimes = [optional_int(segment.get("bedtime")) for segment in segment_list]
            wake_times = [optional_int(segment.get("wake_up_time")) for segment in segment_list]
            bedtimes = [item for item in bedtimes if item is not None]
            wake_times = [item for item in wake_times if item is not None]

            details.append(
                {
                    "date": date,
                    "bedtimeEpochMs": epoch_ms(min(bedtimes) if bedtimes else None),
                    "wakeUpEpochMs": epoch_ms(max(wake_times) if wake_times else None),
                    "totalSleepMinutes": optional_int(value.get("total_duration")) or 0,
                    "deepSleepMinutes": optional_int(value.get("sleep_deep_duration")),
                    "lightSleepMinutes": optional_int(value.get("sleep_light_duration")),
                    "remSleepMinutes": optional_int(value.get("sleep_rem_duration")),
                    "awakeMinutes": optional_int(value.get("sleep_awake_duration")),
                    "awakeCount": optional_int(value.get("awake_count")),
                    "sleepScore": optional_int(value.get("sleep_score")),
                    "segmentCount": len(segment_list),
                    "source": SOURCE,
                    "rawSourceFile": aggregated_file.name,
                    "rawSourceKey": "daily_report/sleep",
                    "rawTimestampEpochMs": epoch_ms(timestamp),
                    "rawPayloadJson": raw_payload,
                }
            )
    return details


def build_workout_sessions(
    sport_file: Path,
    sport_track_file: Path | None,
    zone: dt.tzinfo,
) -> list[dict[str, Any]]:
    gpx_by_key = read_gpx_index(sport_track_file) if sport_track_file else {}
    sessions: list[dict[str, Any]] = []
    with sport_file.open("r", encoding="utf-8-sig", newline="") as handle:
        for index, row in enumerate(csv.DictReader(handle), start=1):
            raw_payload = row.get("Value") or "{}"
            value = parse_json(raw_payload)
            start_time = optional_int(value.get("start_time")) or optional_int(value.get("time")) or optional_int(row.get("Time"))
            if not start_time:
                continue
            duration_seconds = optional_int(value.get("duration")) or 0
            end_time = optional_int(value.get("end_time"))
            if not end_time or end_time <= 0:
                end_time = start_time + duration_seconds if duration_seconds > 0 else None
            date = dt.datetime.fromtimestamp(start_time, zone).date().isoformat()
            raw_sport_type = optional_int(value.get("sport_type"))
            workout_type = normalize_workout_type(raw_sport_type, row.get("Category"), row.get("Key"))
            distance_meters = optional_float(value.get("distance")) or 0.0
            avg_pace = calculate_avg_pace(duration_seconds, distance_meters)
            track_key = (row.get("Key") or "", start_time)

            sessions.append(
                {
                    "sessionId": f"{SOURCE}:sport_record:{index}",
                    "date": date,
                    "workoutType": workout_type,
                    "sportName": row.get("Key") or row.get("Category") or workout_type,
                    "rawSportType": raw_sport_type,
                    "startAtEpochMs": epoch_ms(start_time),
                    "endAtEpochMs": epoch_ms(end_time),
                    "durationSeconds": duration_seconds,
                    "distanceMeters": distance_meters,
                    "activeCaloriesKcal": optional_float(value.get("calories")) or 0.0,
                    "totalCaloriesKcal": optional_float(value.get("total_cal")),
                    "steps": optional_int(value.get("steps")) or 0,
                    "avgHeartRateBpm": optional_float(value.get("avg_hrm")),
                    "minHeartRateBpm": optional_int(value.get("min_hrm")),
                    "maxHeartRateBpm": optional_int(value.get("max_hrm")),
                    "avgPaceSecondsPerKm": avg_pace,
                    "minPaceSecondsPerKm": optional_int(value.get("min_pace")),
                    "maxPaceSecondsPerKm": optional_int(value.get("max_pace")),
                    "avgCadence": optional_float(value.get("avg_cadence")),
                    "maxCadence": optional_int(value.get("max_cadence")),
                    "trainingEffect": optional_float(value.get("train_effect")),
                    "recoveryTime": optional_int(value.get("recover_time")),
                    "vo2Max": optional_float(value.get("vo2_max")),
                    "gpxUrl": gpx_by_key.get(track_key),
                    "source": SOURCE,
                    "rawSourceFile": sport_file.name,
                    "rawTimestampEpochMs": epoch_ms(start_time),
                    "rawPayloadJson": raw_payload,
                }
            )
    return sessions


def read_gpx_index(sport_track_file: Path) -> dict[tuple[str, int], str]:
    result: dict[tuple[str, int], str] = {}
    with sport_track_file.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            key = row.get("Key") or ""
            timestamp = optional_int(row.get("Time"))
            gpx = row.get("GPX")
            if key and timestamp and gpx:
                result[(key, timestamp)] = gpx
    return result


def new_day() -> dict[str, Any]:
    return {
        "steps": 0,
        "distanceMeters": 0.0,
        "activeCaloriesKcal": 0.0,
        "heart": [],
        "heartAvg": None,
        "heartMin": None,
        "heartMax": None,
        "heartReportCount": 0,
        "sleepSessions": 0,
        "sleepMinutes": 0,
        "sleepLast": None,
        "workoutSessions": 0,
        "workoutMinutes": 0,
        "workoutLast": None,
        "workoutTypes": collections.defaultdict(new_workout_type),
        "weight": None,
        "vo2": None,
        "spo2": None,
        "weightCount": 0,
        "vo2Count": 0,
        "spo2Count": 0,
        "bodyLast": None,
    }


def new_workout_type() -> dict[str, Any]:
    return {
        "sessions": 0,
        "minutes": 0,
        "distanceMeters": 0.0,
        "activeCaloriesKcal": 0.0,
        "steps": 0,
        "heartWeightedSum": 0.0,
        "heartWeight": 0,
        "last": None,
    }


def map_workout_type(raw_type: Any) -> str | None:
    try:
        sport_type = int(float(raw_type))
    except (TypeError, ValueError):
        return None
    if sport_type == 1:
        return "running"
    if sport_type == 2:
        return "walking"
    return None


def normalize_workout_type(raw_type: Any, category: str | None, key: str | None) -> str:
    mapped = map_workout_type(raw_type)
    if mapped is not None:
        return mapped
    source = (category or key or "unknown").strip().lower()
    return "".join(char if char.isalnum() else "_" for char in source).strip("_") or "unknown"


def calculate_avg_pace(duration_seconds: int, distance_meters: float) -> int | None:
    if duration_seconds <= 0 or distance_meters <= 0:
        return None
    return round(duration_seconds / (distance_meters / 1000.0))


def apply_aggregated_daily_reports(
    daily: dict[str, dict[str, Any]],
    aggregated_file: Path,
    zone: dt.tzinfo,
) -> None:
    """Prefer Mi Fitness daily_report values; they match the app's deduped totals."""
    with aggregated_file.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            if row["Tag"] != "daily_report":
                continue

            value = parse_json(row.get("Value"))
            timestamp = int(row["Time"] or 0)
            if timestamp <= 0:
                continue

            date = dt.datetime.fromtimestamp(timestamp, zone).date().isoformat()
            day = daily[date]
            key = row["Key"]

            if key == "steps":
                day["steps"] = int(float(value.get("steps") or 0))
                day["distanceMeters"] = float(value.get("distance") or 0)
                day["activeCaloriesKcal"] = float(value.get("calories") or 0)
            elif key == "heart_rate":
                day["heartAvg"] = optional_float(value.get("avg_hr"))
                day["heartMin"] = optional_int(value.get("min_hr"))
                day["heartMax"] = optional_int(value.get("max_hr"))
                day["heartReportCount"] = 1
            elif key == "sleep":
                day["sleepSessions"] = 1 if optional_int(value.get("total_duration")) else 0
                day["sleepMinutes"] = optional_int(value.get("total_duration")) or 0
                day["sleepLast"] = latest_sleep_segment_end(value) or timestamp
            elif key == "spo2":
                day["spo2"] = optional_float(value.get("avg_spo2"))
                day["spo2Count"] = 1 if day["spo2"] is not None else 0
                day["bodyLast"] = max(day["bodyLast"] or 0, timestamp)


def parse_json(raw: str | None) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return value if isinstance(value, dict) else {}


def optional_int(value: Any) -> int | None:
    if value in (None, ""):
        return None
    return int(float(value))


def optional_float(value: Any) -> float | None:
    if value in (None, ""):
        return None
    return float(value)


def latest_sleep_segment_end(value: dict[str, Any]) -> int | None:
    segments = value.get("segment_details")
    if not isinstance(segments, list):
        return None
    ends = [
        optional_int(segment.get("wake_up_time"))
        for segment in segments
        if isinstance(segment, dict)
    ]
    ends = [end for end in ends if end is not None]
    return max(ends) if ends else None


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
    ensure_workout_type_table(con)
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
        heart_count = len(heart_values) if heart_values else day["heartReportCount"]
        heart_min = day["heartMin"] if day["heartMin"] is not None else (min(heart_values) if heart_values else None)
        heart_max = day["heartMax"] if day["heartMax"] is not None else (max(heart_values) if heart_values else None)
        heart_avg = day["heartAvg"] if day["heartAvg"] is not None else (
            (sum(heart_values) / len(heart_values)) if heart_values else None
        )
        con.execute(
            """
            INSERT OR REPLACE INTO daily_heart_summaries (
                date, sampleCount, minBpm, maxBpm, avgBpm, source, lastRecordAtEpochMs, syncedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                date,
                heart_count,
                heart_min,
                heart_max,
                heart_avg,
                SOURCE if heart_count > 0 else "none",
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

        for workout_type, typed in sorted(day["workoutTypes"].items()):
            heart_avg = (
                typed["heartWeightedSum"] / typed["heartWeight"]
                if typed["heartWeight"] > 0
                else None
            )
            con.execute(
                """
                INSERT OR REPLACE INTO daily_workout_type_summaries (
                    date, workoutType, sessionCount, totalDurationMinutes,
                    distanceMeters, activeCaloriesKcal, steps, avgHeartRateBpm,
                    source, lastRecordAtEpochMs, syncedAtEpochMs
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    date,
                    workout_type,
                    typed["sessions"],
                    typed["minutes"],
                    typed["distanceMeters"],
                    typed["activeCaloriesKcal"],
                    typed["steps"],
                    heart_avg,
                    SOURCE if typed["sessions"] > 0 else "none",
                    epoch_ms(typed["last"]),
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


def upsert_detail_tables(
    con: sqlite3.Connection,
    sleep_details: list[dict[str, Any]],
    workout_sessions: list[dict[str, Any]],
    synced_at_ms: int,
) -> None:
    ensure_detail_tables(con)
    for detail in sleep_details:
        con.execute(
            """
            INSERT OR REPLACE INTO sleep_details (
                date, bedtimeEpochMs, wakeUpEpochMs, totalSleepMinutes,
                deepSleepMinutes, lightSleepMinutes, remSleepMinutes, awakeMinutes,
                awakeCount, sleepScore, segmentCount, source, rawSourceFile,
                rawSourceKey, rawTimestampEpochMs, rawPayloadJson, syncedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                detail["date"],
                detail["bedtimeEpochMs"],
                detail["wakeUpEpochMs"],
                detail["totalSleepMinutes"],
                detail["deepSleepMinutes"],
                detail["lightSleepMinutes"],
                detail["remSleepMinutes"],
                detail["awakeMinutes"],
                detail["awakeCount"],
                detail["sleepScore"],
                detail["segmentCount"],
                detail["source"],
                detail["rawSourceFile"],
                detail["rawSourceKey"],
                detail["rawTimestampEpochMs"],
                detail["rawPayloadJson"],
                synced_at_ms,
            ),
        )

    for session in workout_sessions:
        con.execute(
            """
            INSERT OR REPLACE INTO workout_sessions (
                sessionId, date, workoutType, sportName, rawSportType,
                startAtEpochMs, endAtEpochMs, durationSeconds, distanceMeters,
                activeCaloriesKcal, totalCaloriesKcal, steps, avgHeartRateBpm,
                minHeartRateBpm, maxHeartRateBpm, avgPaceSecondsPerKm,
                minPaceSecondsPerKm, maxPaceSecondsPerKm, avgCadence, maxCadence,
                trainingEffect, recoveryTime, vo2Max, gpxUrl, source,
                rawSourceFile, rawTimestampEpochMs, rawPayloadJson, syncedAtEpochMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                session["sessionId"],
                session["date"],
                session["workoutType"],
                session["sportName"],
                session["rawSportType"],
                session["startAtEpochMs"],
                session["endAtEpochMs"],
                session["durationSeconds"],
                session["distanceMeters"],
                session["activeCaloriesKcal"],
                session["totalCaloriesKcal"],
                session["steps"],
                session["avgHeartRateBpm"],
                session["minHeartRateBpm"],
                session["maxHeartRateBpm"],
                session["avgPaceSecondsPerKm"],
                session["minPaceSecondsPerKm"],
                session["maxPaceSecondsPerKm"],
                session["avgCadence"],
                session["maxCadence"],
                session["trainingEffect"],
                session["recoveryTime"],
                session["vo2Max"],
                session["gpxUrl"],
                session["source"],
                session["rawSourceFile"],
                session["rawTimestampEpochMs"],
                session["rawPayloadJson"],
                synced_at_ms,
            ),
        )


def ensure_detail_tables(con: sqlite3.Connection) -> None:
    con.execute(
        """
        CREATE TABLE IF NOT EXISTS sleep_details (
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
        )
        """
    )
    con.execute(
        """
        CREATE TABLE IF NOT EXISTS workout_sessions (
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
        )
        """
    )
    con.execute("CREATE INDEX IF NOT EXISTS index_sleep_details_date ON sleep_details(date)")
    con.execute("CREATE INDEX IF NOT EXISTS index_workout_sessions_date ON workout_sessions(date)")
    con.execute("CREATE INDEX IF NOT EXISTS index_workout_sessions_workoutType_date ON workout_sessions(workoutType, date)")


def has_activity(day: dict[str, Any]) -> bool:
    return day["steps"] > 0 or day["distanceMeters"] > 0 or day["activeCaloriesKcal"] > 0


def ensure_workout_type_table(con: sqlite3.Connection) -> None:
    con.execute(
        """
        CREATE TABLE IF NOT EXISTS daily_workout_type_summaries (
            date TEXT NOT NULL,
            workoutType TEXT NOT NULL,
            sessionCount INTEGER NOT NULL,
            totalDurationMinutes INTEGER NOT NULL,
            distanceMeters REAL NOT NULL,
            activeCaloriesKcal REAL NOT NULL,
            steps INTEGER NOT NULL,
            avgHeartRateBpm REAL,
            source TEXT NOT NULL,
            lastRecordAtEpochMs INTEGER,
            syncedAtEpochMs INTEGER NOT NULL,
            PRIMARY KEY(date, workoutType)
        )
        """
    )


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


def print_detail_summary(
    sleep_details: list[dict[str, Any]],
    workout_sessions: list[dict[str, Any]],
) -> None:
    print("Detailed sleep rows:", len(sleep_details))
    print(
        "Sleep detail coverage:",
        {
            "stages": sum(1 for row in sleep_details if row["deepSleepMinutes"] is not None or row["remSleepMinutes"] is not None),
            "score": sum(1 for row in sleep_details if row["sleepScore"] is not None),
            "bed_wake": sum(1 for row in sleep_details if row["bedtimeEpochMs"] is not None and row["wakeUpEpochMs"] is not None),
        },
    )
    print("Workout sessions:", len(workout_sessions))
    print(
        "Workout detail coverage:",
        {
            "distance": sum(1 for row in workout_sessions if row["distanceMeters"] > 0),
            "active_calories": sum(1 for row in workout_sessions if row["activeCaloriesKcal"] > 0),
            "avg_hr": sum(1 for row in workout_sessions if row["avgHeartRateBpm"] not in (None, 0)),
            "max_hr": sum(1 for row in workout_sessions if row["maxHeartRateBpm"] not in (None, 0)),
            "pace": sum(1 for row in workout_sessions if row["avgPaceSecondsPerKm"] is not None),
            "cadence": sum(1 for row in workout_sessions if row["avgCadence"] is not None or row["maxCadence"] not in (None, 0)),
            "vo2": sum(1 for row in workout_sessions if row["vo2Max"] not in (None, 0)),
            "gpx": sum(1 for row in workout_sessions if row["gpxUrl"]),
        },
    )


if __name__ == "__main__":
    main()
