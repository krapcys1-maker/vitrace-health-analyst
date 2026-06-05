"""Audit persisted VitaTrace insight snapshots against a local SQLite DB copy.

This is intentionally deterministic. It checks whether the app has saved the
expected tested insights and prints the raw data coverage that those insights
should be based on.
"""

from __future__ import annotations

import argparse
import json
import math
import sqlite3
import sys
from pathlib import Path
from typing import Iterable


DEFAULT_DB = Path("build/phone-db-check/vitrace-after-am-start.db")
CORE_SCOPES = {
    "data_coverage_reality",
    "activity_sleep_same_night",
    "sleep_next_day_activity",
    "training_day_sleep",
    "walking_efficiency_by_distance_band",
}
KNOWN_SCOPES = CORE_SCOPES | {
    "long_term_steps_km",
    "sleep_monthly_baseline",
    "heart_outlier_context",
}
REQUIRED_RESULT_KEYS = {
    "id",
    "domain",
    "title",
    "answer",
    "evidence",
    "dateRange",
    "sampleSize",
    "confidence",
    "limitations",
    "nextStep",
}
VALID_CONFIDENCE = {"High", "Medium", "Low", "Insufficient"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--db",
        default=str(DEFAULT_DB),
        help="Path to a pulled VitaTrace SQLite database copy.",
    )
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"ERROR: DB not found: {db_path}", file=sys.stderr)
        return 2

    errors: list[str] = []
    with sqlite3.connect(db_path) as con:
        con.row_factory = sqlite3.Row
        print(f"DB: {db_path}")
        print_data_coverage(con)
        audit_current_insights(con, errors)
        print_sleep_activity_correlations(con)

    if errors:
        print("\nFAILED")
        for error in errors:
            print(f"- {error}")
        return 1

    print("\nOK: insight snapshots are structurally valid.")
    return 0


def print_data_coverage(con: sqlite3.Connection) -> None:
    activity_days = scalar(
        con,
        """
        SELECT count(*)
        FROM daily_activity_summaries
        WHERE steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0
        """,
    )
    sleep_nights = scalar(
        con,
        "SELECT count(*) FROM sleep_details WHERE totalSleepMinutes > 0",
    )
    workouts = scalar(con, "SELECT count(*) FROM workout_sessions")

    print("\nRaw coverage")
    print(f"- activity days: {activity_days}")
    print(f"- sleep detail nights: {sleep_nights}")
    print(f"- workout sessions: {workouts}")
    print("- workout types:")
    for row in con.execute(
        """
        SELECT workoutType, count(*) AS sessions, round(sum(distanceMeters) / 1000.0, 1) AS km
        FROM workout_sessions
        GROUP BY workoutType
        ORDER BY sessions DESC, workoutType ASC
        """
    ):
        print(f"  {row['workoutType']}: {row['sessions']} sessions, {row['km']} km")


def audit_current_insights(con: sqlite3.Connection, errors: list[str]) -> None:
    rows = list(
        con.execute(
            """
            SELECT *
            FROM analysis_results
            WHERE analysisType = 'tested_insight' AND isCurrent = 1
            ORDER BY scope
            """
        )
    )
    scopes = {row["scope"] for row in rows}
    missing = CORE_SCOPES - scopes
    extra = scopes - KNOWN_SCOPES
    if missing:
        errors.append(f"missing current tested insights: {sorted(missing)}")
    if extra:
        errors.append(f"unexpected current tested insights: {sorted(extra)}")

    print("\nCurrent tested insights")
    for row in rows:
        print(
            f"- {row['scope']}: {row['confidence']}, sample={row['sampleSize']}, "
            f"title={row['summaryTitle']}"
        )
        validate_analysis_row(row, errors)


def validate_analysis_row(row: sqlite3.Row, errors: list[str]) -> None:
    scope = row["scope"]
    if not row["summaryTitle"].strip():
        errors.append(f"{scope}: empty summaryTitle")
    if not row["summaryText"].strip():
        errors.append(f"{scope}: empty summaryText")
    if row["confidence"] not in VALID_CONFIDENCE:
        errors.append(f"{scope}: invalid confidence {row['confidence']!r}")
    if row["sampleSize"] <= 0:
        errors.append(f"{scope}: sampleSize must be positive")

    result = parse_json(row["resultJson"], f"{scope}.resultJson", errors)
    parse_json(row["sourceCoverageJson"], f"{scope}.sourceCoverageJson", errors)
    parse_json(row["timeContextJson"], f"{scope}.timeContextJson", errors)
    if not isinstance(result, dict):
        return

    missing_keys = REQUIRED_RESULT_KEYS - result.keys()
    if missing_keys:
        errors.append(f"{scope}: resultJson missing keys {sorted(missing_keys)}")
    if result.get("id") != scope:
        errors.append(f"{scope}: resultJson id does not match scope")
    if result.get("sampleSize") != row["sampleSize"]:
        errors.append(f"{scope}: resultJson sampleSize does not match DB row")
    if result.get("confidence") != row["confidence"]:
        errors.append(f"{scope}: resultJson confidence does not match DB row")
    if not isinstance(result.get("evidence"), list) or not result.get("evidence"):
        errors.append(f"{scope}: resultJson evidence must be a non-empty list")
    if not isinstance(result.get("limitations"), list):
        errors.append(f"{scope}: resultJson limitations must be a list")


def print_sleep_activity_correlations(con: sqlite3.Connection) -> None:
    cutoff_date = con.execute(
        """
        SELECT max(generatedForDate)
        FROM analysis_results
        WHERE analysisType = 'tested_insight' AND isCurrent = 1
        """
    ).fetchone()[0]
    rows = list(
        con.execute(
            """
            SELECT
                a.steps AS steps,
                s.totalSleepMinutes AS totalSleepMinutes,
                s.remSleepMinutes AS remSleepMinutes,
                s.deepSleepMinutes AS deepSleepMinutes,
                s.sleepScore AS sleepScore
            FROM sleep_details s
            JOIN daily_activity_summaries a ON a.date = s.date
            WHERE a.steps > 0 AND s.totalSleepMinutes > 0 AND s.date < ?
            ORDER BY s.date
            """,
            (cutoff_date,),
        )
    )
    print("\nSame-day steps/sleep checks")
    print(f"- closed-day cutoff: {cutoff_date}")
    print(f"- paired closed days: {len(rows)}")
    print(f"- steps vs total sleep: r={fmt_corr(correlation(rows, 'steps', 'totalSleepMinutes'))}")
    print(f"- steps vs REM: r={fmt_corr(correlation(rows, 'steps', 'remSleepMinutes'))}")
    print(f"- steps vs deep sleep: r={fmt_corr(correlation(rows, 'steps', 'deepSleepMinutes'))}")
    print(f"- steps vs sleep score: r={fmt_corr(correlation(rows, 'steps', 'sleepScore'))}")


def parse_json(raw: str, label: str, errors: list[str]) -> object | None:
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        errors.append(f"{label}: invalid JSON: {exc}")
        return None


def scalar(con: sqlite3.Connection, query: str) -> int:
    value = con.execute(query).fetchone()[0]
    return int(value or 0)


def correlation(rows: Iterable[sqlite3.Row], x_key: str, y_key: str) -> float | None:
    pairs = [
        (float(row[x_key]), float(row[y_key]))
        for row in rows
        if row[x_key] is not None and row[y_key] is not None
    ]
    if len(pairs) < 3:
        return None
    xs = [pair[0] for pair in pairs]
    ys = [pair[1] for pair in pairs]
    mean_x = sum(xs) / len(xs)
    mean_y = sum(ys) / len(ys)
    var_x = sum((value - mean_x) ** 2 for value in xs)
    var_y = sum((value - mean_y) ** 2 for value in ys)
    if var_x == 0.0 or var_y == 0.0:
        return None
    cov = sum((x - mean_x) * (y - mean_y) for x, y in pairs)
    return cov / math.sqrt(var_x * var_y)


def fmt_corr(value: float | None) -> str:
    return "brak" if value is None else f"{value:+.2f}"


if __name__ == "__main__":
    raise SystemExit(main())
