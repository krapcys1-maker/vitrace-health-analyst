"""Build an offline markdown signal report from a VitaTrace SQLite DB copy."""

from __future__ import annotations

import argparse
import datetime as dt
import math
import sqlite3
import sys
from pathlib import Path
from typing import Iterable


DEFAULT_DB = Path("build/phone-db-check/vitrace-after-am-start.db")
DEFAULT_STEPS_PER_KM = 1250


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default=str(DEFAULT_DB), help="Path to a VitaTrace SQLite DB copy.")
    parser.add_argument("--output", type=Path, help="Optional markdown output path.")
    parser.add_argument(
        "--generated-for-date",
        help="Date treated as current partial day. Defaults to DB analysis date or today.",
    )
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"ERROR: DB not found: {db_path}", file=sys.stderr)
        return 2

    with sqlite3.connect(db_path) as con:
        con.row_factory = sqlite3.Row
        report = build_report(con, db_path, args.generated_for_date)

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
        print(f"Wrote {args.output}")
    else:
        print(report)
    return 0


def build_report(
    con: sqlite3.Connection,
    db_path: Path,
    generated_for_date: str | None,
) -> str:
    cutoff_date = generated_for_date or analysis_generated_for_date(con) or dt.date.today().isoformat()
    steps_per_km = user_steps_per_km(con)
    lines: list[str] = [
        "# VitaTrace Offline Signal Report",
        "",
        f"- DB: `{db_path}`",
        f"- Generated for date: `{cutoff_date}`",
        f"- Closed-day rule: rows before `{cutoff_date}` are trend inputs",
        f"- Steps per km: `{steps_per_km}`",
        "",
    ]

    lines.extend(data_coverage_section(con, cutoff_date))
    lines.extend(activity_section(con, cutoff_date, steps_per_km))
    lines.extend(sleep_section(con, cutoff_date))
    lines.extend(sleep_debt_section(con, cutoff_date))
    lines.extend(sleep_activity_section(con, cutoff_date))
    lines.extend(heart_context_section(con, cutoff_date))
    lines.extend(walking_section(con, cutoff_date))
    lines.extend(next_candidates_section())
    return "\n".join(lines).rstrip() + "\n"


def data_coverage_section(con: sqlite3.Connection, cutoff_date: str) -> list[str]:
    activity_days = scalar(
        con,
        """
        SELECT count(*)
        FROM daily_activity_summaries
        WHERE date < ? AND (steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0)
        """,
        (cutoff_date,),
    )
    sleep_nights = scalar(
        con,
        "SELECT count(*) FROM sleep_details WHERE date < ? AND totalSleepMinutes > 0",
        (cutoff_date,),
    )
    workouts = scalar(
        con,
        "SELECT count(*) FROM workout_sessions WHERE date < ?",
        (cutoff_date,),
    )
    walking = scalar(
        con,
        "SELECT count(*) FROM workout_sessions WHERE date < ? AND workoutType = 'walking'",
        (cutoff_date,),
    )
    running = scalar(
        con,
        "SELECT count(*) FROM workout_sessions WHERE date < ? AND workoutType = 'running'",
        (cutoff_date,),
    )

    return [
        "## Data Coverage",
        "",
        f"- Activity days: {activity_days}",
        f"- Sleep detail nights: {sleep_nights}",
        f"- Workout sessions: {workouts}",
        f"- Walking sessions: {walking}",
        f"- Running sessions: {running}",
        "",
        "Interpretation: activity and walking are the strongest current signals; sleep is useful but smaller; running is still too small for strong trend claims.",
        "",
    ]


def activity_section(
    con: sqlite3.Connection,
    cutoff_date: str,
    steps_per_km: int,
) -> list[str]:
    yearly = list(
        con.execute(
            """
            SELECT
                substr(date, 1, 4) AS year,
                sum(steps) AS steps,
                sum(distanceMeters) / 1000.0 AS sourceKm,
                count(*) AS days
            FROM daily_activity_summaries
            WHERE date < ? AND (steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0)
            GROUP BY substr(date, 1, 4)
            ORDER BY year
            """,
            (cutoff_date,),
        )
    )
    best_months = list(
        con.execute(
            """
            SELECT
                substr(date, 1, 7) AS month,
                sum(steps) AS steps,
                sum(distanceMeters) / 1000.0 AS sourceKm,
                count(*) AS days
            FROM daily_activity_summaries
            WHERE date < ? AND (steps > 0 OR distanceMeters > 0 OR activeCaloriesKcal > 0)
            GROUP BY substr(date, 1, 7)
            ORDER BY steps DESC
            LIMIT 6
            """,
            (cutoff_date,),
        )
    )

    lines = [
        "## Long-Term Steps And Kilometers",
        "",
        "| Year | Days | Steps | Estimated km | Source km |",
        "|---|---:|---:|---:|---:|",
    ]
    for row in yearly:
        lines.append(
            f"| {row['year']} | {row['days']} | {int(row['steps']):,} | "
            f"{steps_to_km(row['steps'], steps_per_km):.1f} | {float(row['sourceKm'] or 0):.1f} |"
        )
    lines.extend(["", "Top months by steps:", ""])
    for index, row in enumerate(best_months, start=1):
        lines.append(
            f"{index}. {row['month']}: {int(row['steps']):,} steps, "
            f"{steps_to_km(row['steps'], steps_per_km):.1f} estimated km, "
            f"{float(row['sourceKm'] or 0):.1f} source km"
        )
    lines.extend(["", "Best use: yearly/monthly totals are a strong feature because Mi Fitness does not make this easy to inspect.", ""])
    return lines


def sleep_section(con: sqlite3.Connection, cutoff_date: str) -> list[str]:
    months = list(
        con.execute(
            """
            SELECT
                substr(date, 1, 7) AS month,
                count(*) AS nights,
                avg(totalSleepMinutes) AS total,
                avg(remSleepMinutes) AS rem,
                avg(deepSleepMinutes) AS deep,
                avg(lightSleepMinutes) AS light,
                avg(awakeMinutes) AS awake,
                avg(sleepScore) AS score
            FROM sleep_details
            WHERE date < ? AND totalSleepMinutes > 0
            GROUP BY substr(date, 1, 7)
            HAVING nights >= 3
            ORDER BY month DESC
            LIMIT 8
            """,
            (cutoff_date,),
        )
    )
    lines = [
        "## Sleep Monthly Baseline",
        "",
        "| Month | Nights | Total h | REM min | Deep min | Light min | Awake min | Score |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in months:
        lines.append(
            f"| {row['month']} | {row['nights']} | {minutes_to_hours(row['total']):.2f} | "
            f"{fmt0(row['rem'])} | {fmt0(row['deep'])} | {fmt0(row['light'])} | "
            f"{fmt0(row['awake'])} | {fmt0(row['score'])} |"
        )
    lines.extend(["", "Best use: monthly sleep phases can support trends, but stage values should be treated as wearable estimates.", ""])
    return lines


def sleep_activity_section(con: sqlite3.Connection, cutoff_date: str) -> list[str]:
    rows = list(
        con.execute(
            """
            SELECT
                a.steps AS steps,
                s.totalSleepMinutes AS totalSleepMinutes,
                s.remSleepMinutes AS remSleepMinutes,
                s.deepSleepMinutes AS deepSleepMinutes,
                s.lightSleepMinutes AS lightSleepMinutes,
                s.sleepScore AS sleepScore
            FROM sleep_details s
            JOIN daily_activity_summaries a ON a.date = s.date
            WHERE s.date < ? AND a.steps > 0 AND s.totalSleepMinutes > 0
            ORDER BY s.date
            """,
            (cutoff_date,),
        )
    )
    high = [row for row in rows if row["steps"] >= 10_000]
    low = [row for row in rows if row["steps"] < 10_000]
    lines = [
        "## Activity And Same-Night Sleep",
        "",
        f"- Paired closed days: {len(rows)}",
        f"- Steps vs total sleep: r={fmt_corr(correlation(rows, 'steps', 'totalSleepMinutes'))}",
        f"- Steps vs REM: r={fmt_corr(correlation(rows, 'steps', 'remSleepMinutes'))}",
        f"- Steps vs deep sleep: r={fmt_corr(correlation(rows, 'steps', 'deepSleepMinutes'))}",
        f"- Steps vs sleep score: r={fmt_corr(correlation(rows, 'steps', 'sleepScore'))}",
        "",
        "| Group | Days | Total h | REM min | Deep min | Score |",
        "|---|---:|---:|---:|---:|---:|",
        sleep_group_row(">=10k steps", high),
        sleep_group_row("<10k steps", low),
        "",
        "Interpretation: if correlations stay near zero, the app should not claim that more steps directly improve same-night sleep.",
        "",
    ]
    return lines


def sleep_debt_section(con: sqlite3.Connection, cutoff_date: str) -> list[str]:
    rows = list(
        con.execute(
            """
            SELECT
                date,
                totalSleepMinutes,
                remSleepMinutes,
                deepSleepMinutes,
                lightSleepMinutes,
                awakeMinutes,
                sleepScore
            FROM sleep_details
            WHERE date < ? AND totalSleepMinutes > 0
            ORDER BY date DESC
            """,
            (cutoff_date,),
        )
    )
    newest7 = rows[:7]
    newest14 = rows[:14]
    newest30 = rows[:30]
    baseline = rows[30:120]
    baseline_total = average(baseline, "totalSleepMinutes")
    baseline_rem = average(baseline, "remSleepMinutes")
    baseline_deep = average(baseline, "deepSleepMinutes")
    total_7 = average(newest7, "totalSleepMinutes")
    total_14 = average(newest14, "totalSleepMinutes")
    total_30 = average(newest30, "totalSleepMinutes")
    rem_30 = average(newest30, "remSleepMinutes")
    deep_30 = average(newest30, "deepSleepMinutes")

    lines = [
        "## Sleep Debt Window",
        "",
        f"- Latest measured night: {rows[0]['date'] if rows else 'brak'}",
        f"- Baseline nights: {len(baseline)}",
        "",
        "| Window | Nights | Total h | Delta vs baseline | REM delta | Deep delta |",
        "|---|---:|---:|---:|---:|---:|",
        sleep_window_row("last 7 measured", newest7, baseline_total, baseline_rem, baseline_deep),
        sleep_window_row("last 14 measured", newest14, baseline_total, baseline_rem, baseline_deep),
        sleep_window_row("last 30 measured", newest30, baseline_total, baseline_rem, baseline_deep),
        "",
        "Interpretation: this uses the latest measured nights, not a guaranteed continuous calendar window.",
        "",
    ]
    return lines


def walking_section(con: sqlite3.Connection, cutoff_date: str) -> list[str]:
    rows = list(
        con.execute(
            """
            SELECT
                CASE
                    WHEN distanceMeters < 3000 THEN '<3 km'
                    WHEN distanceMeters < 6000 THEN '3-6 km'
                    WHEN distanceMeters < 10000 THEN '6-10 km'
                    WHEN distanceMeters < 15000 THEN '10-15 km'
                    ELSE '15+ km'
                END AS band,
                count(*) AS sessions,
                sum(distanceMeters) / 1000.0 AS km,
                avg(avgHeartRateBpm) AS avgHr,
                avg(avgPaceSecondsPerKm) AS pace,
                avg(activeCaloriesKcal / NULLIF(distanceMeters / 1000.0, 0)) AS kcalKm,
                avg(vo2Max) AS vo2
            FROM workout_sessions
            WHERE date < ? AND workoutType = 'walking' AND distanceMeters > 0
            GROUP BY band
            ORDER BY min(distanceMeters)
            """,
            (cutoff_date,),
        )
    )
    lines = [
        "## Walking Workout Baselines",
        "",
        "| Distance band | Sessions | Km | Avg HR | Pace | kcal/km | VO2 |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for row in rows:
        lines.append(
            f"| {row['band']} | {row['sessions']} | {float(row['km'] or 0):.1f} | "
            f"{fmt0(row['avgHr'])} | {format_pace(row['pace'])} | {fmt0(row['kcalKm'])} | {fmt1(row['vo2'])} |"
        )
    lines.extend(["", "Best use: compare recent walks against the same distance band instead of random monthly totals.", ""])
    return lines


def heart_context_section(con: sqlite3.Connection, cutoff_date: str) -> list[str]:
    rows = list(
        con.execute(
            """
            SELECT
                h.date AS date,
                h.avgBpm AS avgBpm,
                h.minBpm AS minBpm,
                h.maxBpm AS maxBpm,
                h.sampleCount AS sampleCount,
                COALESCE(a.steps, 0) AS steps,
                s.totalSleepMinutes AS totalSleepMinutes,
                s.sleepScore AS sleepScore,
                COALESCE(w.totalDurationMinutes, 0) AS workoutMinutes
            FROM daily_heart_summaries h
            LEFT JOIN daily_activity_summaries a ON a.date = h.date
            LEFT JOIN sleep_details s ON s.date = h.date
            LEFT JOIN daily_workout_summaries w ON w.date = h.date
            WHERE h.date < ? AND h.sampleCount > 0 AND h.avgBpm IS NOT NULL
            ORDER BY h.date
            """,
            (cutoff_date,),
        )
    )
    values = [float(row["avgBpm"]) for row in rows]
    baseline = average_values(values)
    std_dev = standard_deviation(values)
    threshold = baseline + std_dev if baseline is not None and std_dev is not None else None
    high = [row for row in rows if threshold is not None and row["avgBpm"] >= threshold]
    normal = [row for row in rows if threshold is not None and row["avgBpm"] < threshold]

    lines = [
        "## Heart Context",
        "",
        f"- Closed heart days: {len(rows)}",
        f"- Average daily HR baseline: {fmt0(baseline)} bpm",
        f"- High-day threshold: {fmt0(threshold)} bpm",
        "",
        "| Group | Days | Avg HR | Sleep h | Steps | Workout min |",
        "|---|---:|---:|---:|---:|---:|",
        heart_group_row("high avg HR", high),
        heart_group_row("other HR days", normal),
        "",
        "Interpretation: this uses daily average heart rate, not resting heart rate. Treat it as context for load/recovery, not a medical signal.",
        "",
    ]
    return lines


def next_candidates_section() -> list[str]:
    return [
        "## Next Insight Candidates",
        "",
        "1. `long_term_steps_km`: yearly/monthly steps and estimated kilometers.",
        "2. `sleep_monthly_baseline`: monthly total, REM, deep, light, awake, and score.",
        "3. `walking_band_baseline`: walking pace/HR/kcal/km/VO2 by distance band.",
        "4. `heart_outlier_context`: high-heart days versus sleep/activity context, only where coverage is enough.",
        "5. `sleep_debt_window`: last 7/14/30 closed days versus personal sleep baseline.",
        "",
    ]


def analysis_generated_for_date(con: sqlite3.Connection) -> str | None:
    try:
        row = con.execute(
            """
            SELECT max(generatedForDate)
            FROM analysis_results
            WHERE analysisType = 'tested_insight' AND isCurrent = 1
            """
        ).fetchone()
    except sqlite3.OperationalError:
        return None
    return row[0] if row and row[0] else None


def user_steps_per_km(con: sqlite3.Connection) -> int:
    try:
        row = con.execute("SELECT stepsPerKm FROM user_profile ORDER BY id LIMIT 1").fetchone()
    except sqlite3.OperationalError:
        return DEFAULT_STEPS_PER_KM
    value = int(row[0]) if row and row[0] else DEFAULT_STEPS_PER_KM
    return value if value > 0 else DEFAULT_STEPS_PER_KM


def scalar(con: sqlite3.Connection, query: str, params: tuple[object, ...] = ()) -> int:
    value = con.execute(query, params).fetchone()[0]
    return int(value or 0)


def steps_to_km(steps: int | float | None, steps_per_km: int) -> float:
    return float(steps or 0) / steps_per_km


def minutes_to_hours(minutes: int | float | None) -> float:
    return float(minutes or 0) / 60.0


def sleep_group_row(label: str, rows: list[sqlite3.Row]) -> str:
    return (
        f"| {label} | {len(rows)} | {minutes_to_hours(average(rows, 'totalSleepMinutes')):.2f} | "
        f"{fmt0(average(rows, 'remSleepMinutes'))} | {fmt0(average(rows, 'deepSleepMinutes'))} | "
        f"{fmt0(average(rows, 'sleepScore'))} |"
    )


def sleep_window_row(
    label: str,
    rows: list[sqlite3.Row],
    baseline_total: float | None,
    baseline_rem: float | None,
    baseline_deep: float | None,
) -> str:
    total = average(rows, "totalSleepMinutes")
    rem = average(rows, "remSleepMinutes")
    deep = average(rows, "deepSleepMinutes")
    return (
        f"| {label} | {len(rows)} | {minutes_to_hours(total):.2f} | "
        f"{fmt_signed_minutes(delta(total, baseline_total))} | "
        f"{fmt_signed_minutes(delta(rem, baseline_rem))} | "
        f"{fmt_signed_minutes(delta(deep, baseline_deep))} |"
    )


def average(rows: list[sqlite3.Row], key: str) -> float | None:
    values = [float(row[key]) for row in rows if row[key] is not None]
    return sum(values) / len(values) if values else None


def delta(current: float | None, baseline: float | None) -> float | None:
    if current is None or baseline is None:
        return None
    return current - baseline


def average_values(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def standard_deviation(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / len(values)
    return math.sqrt(variance)


def heart_group_row(label: str, rows: list[sqlite3.Row]) -> str:
    return (
        f"| {label} | {len(rows)} | {fmt0(average(rows, 'avgBpm'))} | "
        f"{minutes_to_hours(average(rows, 'totalSleepMinutes')):.2f} | "
        f"{fmt0(average(rows, 'steps'))} | {fmt0(average(rows, 'workoutMinutes'))} |"
    )


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


def fmt_signed_minutes(value: float | None) -> str:
    return "brak" if value is None else f"{value:+.0f} min"


def fmt0(value: int | float | None) -> str:
    return "brak" if value is None else f"{float(value):.0f}"


def fmt1(value: int | float | None) -> str:
    return "brak" if value is None else f"{float(value):.1f}"


def format_pace(seconds_per_km: int | float | None) -> str:
    if seconds_per_km is None:
        return "brak"
    total_seconds = int(round(float(seconds_per_km)))
    minutes = total_seconds // 60
    seconds = total_seconds % 60
    return f"{minutes}:{seconds:02d}/km"


if __name__ == "__main__":
    raise SystemExit(main())
