"""Build a privacy-safe AI context bundle from deterministic VitaTrace results."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sqlite3
import sys
from pathlib import Path
from typing import Any

from analysis_rules import (
    ActivitySleepGroup,
    ActivitySleepThresholdResult,
    ActivityWorkoutSleepGroup,
    ActivityWorkoutSleepLoadResult,
    CREDIBLE_DAILY_HEART_FILTER_SQL,
    CREDIBLE_WALKING_FILTER_SQL,
    FITNESS_WALKING_FILTER_SQL,
    HEART_MIN_DAILY_SAMPLES,
    LONG_WALK_SLEEP_MIN_DISTANCE_KM,
    ActivityMonth,
    SleepWindow,
    SleepWindowComparison,
    WalkingBandTrend,
    classify_activity_months,
    compare_activity_sleep_thresholds,
    compare_activity_workout_sleep_load,
    compare_sleep_window_to_baseline,
    compare_latest_walking_band_years,
)


DEFAULT_DB = Path("build/phone-db-check/phone-current-vitrace.db")
DEFAULT_JSON_OUTPUT = Path("build/ai-context-bundle.json")
DEFAULT_PROMPT_OUTPUT = Path("build/ai-context-prompt.md")
SCHEMA_VERSION = "ai_context_bundle_v1"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default=str(DEFAULT_DB), help="Path to a VitaTrace SQLite DB copy.")
    parser.add_argument("--output", type=Path, default=DEFAULT_JSON_OUTPUT, help="JSON bundle output path.")
    parser.add_argument("--prompt-output", type=Path, default=DEFAULT_PROMPT_OUTPUT, help="Prompt markdown output path.")
    parser.add_argument("--generated-for-date", help="Current partial day. Defaults to DB analysis date or today.")
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"ERROR: DB not found: {db_path}", file=sys.stderr)
        return 2

    with sqlite3.connect(db_path) as con:
        con.row_factory = sqlite3.Row
        generated_for_date = args.generated_for_date or latest_generated_for_date(con) or dt.date.today().isoformat()
        bundle = build_bundle(con, db_path, generated_for_date)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bundle, ensure_ascii=True, indent=2), encoding="utf-8")

    args.prompt_output.parent.mkdir(parents=True, exist_ok=True)
    args.prompt_output.write_text(build_prompt(bundle), encoding="utf-8")

    print(f"Wrote {args.output}")
    print(f"Wrote {args.prompt_output}")
    return 0


def build_bundle(con: sqlite3.Connection, db_path: Path, generated_for_date: str) -> dict[str, Any]:
    profile = user_profile(con)
    insights = current_tested_insights(con)
    coverage = data_coverage(con, generated_for_date)
    engine_facts = deterministic_engine_facts(con, generated_for_date, profile)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedForDate": generated_for_date,
        "source": {
            "kind": "local deterministic analysis bundle",
            "closedDayRule": f"Use rows before {generated_for_date} for trends; treat {generated_for_date} as partial/live context.",
            "rawDataIncluded": False,
            "routeDataIncluded": False,
            "photosOrScansIncluded": False,
            "secretsIncluded": False,
        },
        "profile": profile,
        "dataCoverage": coverage,
        "researchRules": research_rules(),
        "engineFacts": engine_facts,
        "deterministicInsights": insights,
        "aiRole": ai_role(),
        "forbiddenConclusions": forbidden_conclusions(),
        "expectedOutput": expected_output(),
    }


def user_profile(con: sqlite3.Connection) -> dict[str, Any]:
    try:
        row = con.execute(
            """
            select sex, ageYears, heightCm, weightKg, stepsPerKm
            from user_profile
            order by id
            limit 1
            """
        ).fetchone()
    except sqlite3.OperationalError:
        row = None
    if not row:
        return {
            "available": False,
            "note": "No profile row available.",
        }
    return {
        "available": True,
        "sex": row["sex"],
        "ageYears": int(row["ageYears"]),
        "heightCm": int(row["heightCm"]),
        "weightKg": float(row["weightKg"]),
        "stepsPerKm": int(row["stepsPerKm"]),
    }


def data_coverage(con: sqlite3.Connection, generated_for_date: str) -> dict[str, Any]:
    return {
        "activityDays": scalar(con, "select count(*) from daily_activity_summaries where date < ? and (steps > 0 or distanceMeters > 0 or activeCaloriesKcal > 0)", generated_for_date),
        "sleepDetailNights": scalar(con, "select count(*) from sleep_details where date < ? and totalSleepMinutes > 0", generated_for_date),
        "heartDays": scalar(con, f"select count(*) from daily_heart_summaries where date < ? and {CREDIBLE_DAILY_HEART_FILTER_SQL}", generated_for_date),
        "walkingSessions": scalar(con, "select count(*) from workout_sessions where date < ? and workoutType = 'walking'", generated_for_date),
        "runningSessions": scalar(con, "select count(*) from workout_sessions where date < ? and workoutType = 'running'", generated_for_date),
        "bodyWeightDays": scalar(con, "select count(*) from daily_body_summaries where date < ? and weightRecordCount > 0", generated_for_date),
        "coverageInterpretation": [
            "Daily activity and walking workouts are currently the strongest signals.",
            "Sleep is useful for personal baseline and cautious trend analysis.",
            "Daily average heart rate is context for load/recovery, not resting heart rate.",
            "Running, body composition, SpO2, and lab analysis are not strong enough for claims yet.",
        ],
    }


def current_tested_insights(con: sqlite3.Connection) -> list[dict[str, Any]]:
    try:
        rows = list(
            con.execute(
                """
                select scope, generatedForDate, summaryTitle, summaryText, confidence,
                       sampleSize, resultJson, sourceCoverageJson, timeContextJson
                from analysis_results
                where analysisType = 'tested_insight' and isCurrent = 1
                  and scope != 'data_coverage_reality'
                order by
                    case confidence
                        when 'High' then 0
                        when 'Medium' then 1
                        when 'Low' then 2
                        else 3
                    end,
                    scope
                """
            )
        )
    except sqlite3.OperationalError:
        return []

    insights: list[dict[str, Any]] = []
    for row in rows:
        result = parse_json_object(row["resultJson"])
        insights.append(
            {
                "id": result.get("id", row["scope"]),
                "domain": result.get("domain", "Unknown"),
                "title": result.get("title", row["summaryTitle"]),
                "answer": sanitize_ai_text(result.get("answer", row["summaryText"])),
                "confidence": row["confidence"],
                "sampleSize": int(row["sampleSize"]),
                "dateRange": result.get("dateRange"),
                "evidence": [sanitize_ai_text(item) for item in keep_string_list(result.get("evidence"))],
                "limitations": [sanitize_ai_text(item) for item in keep_string_list(result.get("limitations"))],
                "nextStep": sanitize_ai_text(result.get("nextStep")),
            }
        )
    return insights


def deterministic_engine_facts(
    con: sqlite3.Connection,
    generated_for_date: str,
    profile: dict[str, Any],
) -> dict[str, Any]:
    steps_per_km = int(profile.get("stepsPerKm") or 1250) if profile.get("available") else 1250
    return {
        "activityOverTime": activity_over_time_facts(con, generated_for_date, steps_per_km),
        "walkingFitness": walking_fitness_facts(con, generated_for_date),
        "sleepBaseline": sleep_baseline_facts(con, generated_for_date),
        "activitySleepThresholds": activity_sleep_threshold_facts(con, generated_for_date),
        "activityWorkoutSleepLoad": activity_workout_sleep_load_facts(con, generated_for_date),
        "sleepAfterLongWalks": sleep_after_long_walks_facts(con, generated_for_date),
        "heartLoad": heart_load_facts(con, generated_for_date),
    }


def activity_over_time_facts(
    con: sqlite3.Connection,
    generated_for_date: str,
    steps_per_km: int,
) -> dict[str, Any]:
    try:
        month_rows = list(
            con.execute(
                """
                select substr(date, 1, 7) as period,
                       count(*) as days,
                       sum(steps) as steps,
                       avg(steps) as avgSteps
                from daily_activity_summaries
                where date < ? and (steps > 0 or distanceMeters > 0 or activeCaloriesKcal > 0)
                group by period
                order by period
                """,
                (generated_for_date,),
            )
        )
        year_rows = list(
            con.execute(
                """
                select substr(date, 1, 4) as period,
                       count(*) as days,
                       sum(steps) as steps,
                       avg(steps) as avgSteps
                from daily_activity_summaries
                where date < ? and (steps > 0 or distanceMeters > 0 or activeCaloriesKcal > 0)
                group by period
                order by period
                """,
                (generated_for_date,),
            )
        )
    except sqlite3.OperationalError:
        return {"available": False, "reason": "activity tables unavailable"}

    months = [
        ActivityMonth(
            period=row["period"],
            days=int(row["days"] or 0),
            steps=int(row["steps"] or 0),
        )
        for row in month_rows
    ]
    signals = classify_activity_months(months)
    monthly_signals = []
    for row in month_rows[-18:]:
        signal = signals.get(row["period"])
        monthly_signals.append(
            {
                "period": row["period"],
                "days": int(row["days"] or 0),
                "steps": int(row["steps"] or 0),
                "avgSteps": round(float(row["avgSteps"] or 0), 1),
                "estimatedKm": round(float(row["steps"] or 0) / steps_per_km, 1),
                "signal": signal.label if signal else "unknown",
                "ratioToBaseline": round(signal.ratio_to_baseline, 2) if signal and signal.ratio_to_baseline is not None else None,
                "reason": signal.reason if signal else "no signal",
            }
        )

    notable = [
        item
        for item in monthly_signals
        if item["signal"] in {"peak", "slump", "above_baseline", "below_baseline"}
    ]
    best_month = max(monthly_signals, key=lambda item: item["steps"], default=None)
    years = [
        {
            "period": row["period"],
            "days": int(row["days"] or 0),
            "steps": int(row["steps"] or 0),
            "avgSteps": round(float(row["avgSteps"] or 0), 1),
            "estimatedKm": round(float(row["steps"] or 0) / steps_per_km, 1),
        }
        for row in year_rows
    ]

    return {
        "available": bool(month_rows),
        "closedDayRule": f"uses dates before {generated_for_date}",
        "stepsPerKm": steps_per_km,
        "years": years,
        "monthlySignals": monthly_signals,
        "notableRecentMonths": notable,
        "bestRecentMonth": best_month,
    }


def walking_fitness_facts(con: sqlite3.Connection, generated_for_date: str) -> dict[str, Any]:
    try:
        rows = list(
            con.execute(
                f"""
                select substr(date, 1, 4) as period,
                       count(*) as sessions,
                       sum(distanceMeters) / 1000.0 as km,
                       avg(avgPaceSecondsPerKm) as paceSecondsPerKm,
                       avg(avgHeartRateBpm) as avgHeartRateBpm,
                       avg(activeCaloriesKcal / nullif(distanceMeters / 1000.0, 0)) as kcalPerKm,
                       avg(vo2Max) as vo2
                from workout_sessions
                where date < ? and {FITNESS_WALKING_FILTER_SQL}
                group by period
                order by period
                """,
                (generated_for_date,),
            )
        )
        band_rows = list(
            con.execute(
                f"""
                select substr(date, 1, 4) as period,
                       case
                         when distanceMeters < 3000 then '1-3 km'
                         when distanceMeters < 6000 then '3-6 km'
                         when distanceMeters < 10000 then '6-10 km'
                         when distanceMeters < 15000 then '10-15 km'
                         else '15+ km'
                       end as distance_band,
                       count(*) as sessions,
                       sum(distanceMeters) / 1000.0 as km,
                       avg(avgPaceSecondsPerKm) as pace_seconds_per_km,
                       avg(avgHeartRateBpm) as avg_heart_rate_bpm,
                       avg(activeCaloriesKcal / nullif(distanceMeters / 1000.0, 0)) as kcal_per_km,
                       avg(vo2Max) as vo2
                from workout_sessions
                where date < ? and {CREDIBLE_WALKING_FILTER_SQL}
                group by period, distance_band
                order by period, distance_band
                """,
                (generated_for_date,),
            )
        )
    except sqlite3.OperationalError:
        return {"available": False, "reason": "workout tables unavailable"}

    years = [
        {
            "period": row["period"],
            "sessions": int(row["sessions"] or 0),
            "km": round(float(row["km"] or 0), 1),
            "paceSecondsPerKm": round(float(row["paceSecondsPerKm"]), 1) if row["paceSecondsPerKm"] is not None else None,
            "avgHeartRateBpm": round(float(row["avgHeartRateBpm"]), 1) if row["avgHeartRateBpm"] is not None else None,
            "kcalPerKm": round(float(row["kcalPerKm"]), 1) if row["kcalPerKm"] is not None else None,
            "vo2": round(float(row["vo2"]), 1) if row["vo2"] is not None else None,
        }
        for row in rows
    ]
    latest = years[-1] if years else None
    previous = years[-2] if len(years) >= 2 else None
    return {
        "available": bool(years),
        "closedDayRule": f"uses dates before {generated_for_date}",
        "filter": "walking 3-15 km, duration 10 min - 4 h, pace 8-25 min/km",
        "years": years,
        "latestVsPrevious": walking_delta(latest, previous),
        "distanceBandTrends": [
            walking_band_trend_to_dict(trend)
            for trend in compare_latest_walking_band_years(band_rows)
        ],
    }


def walking_band_trend_to_dict(trend: WalkingBandTrend) -> dict[str, Any]:
    return {
        "distanceBand": trend.distance_band,
        "current": walking_band_year_to_dict(trend.current),
        "previous": walking_band_year_to_dict(trend.previous) if trend.previous else None,
        "confidence": trend.confidence,
        "deltas": {
            "paceSecondsPerKm": trend.pace_seconds_per_km_delta,
            "avgHeartRateBpm": trend.avg_heart_rate_bpm_delta,
            "kcalPerKm": trend.kcal_per_km_delta,
            "vo2": trend.vo2_delta,
        },
        "interpretation": trend.interpretation,
    }


def walking_band_year_to_dict(year: object) -> dict[str, Any]:
    return {
        "period": year.period,
        "sessions": year.sessions,
        "km": round(float(year.km), 1) if year.km is not None else None,
        "paceSecondsPerKm": round(float(year.pace_seconds_per_km), 1) if year.pace_seconds_per_km is not None else None,
        "avgHeartRateBpm": round(float(year.avg_heart_rate_bpm), 1) if year.avg_heart_rate_bpm is not None else None,
        "kcalPerKm": round(float(year.kcal_per_km), 1) if year.kcal_per_km is not None else None,
        "vo2": round(float(year.vo2), 1) if year.vo2 is not None else None,
    }


def walking_delta(current: dict[str, Any] | None, previous: dict[str, Any] | None) -> dict[str, Any] | None:
    if not current or not previous:
        return None
    return {
        "currentPeriod": current["period"],
        "previousPeriod": previous["period"],
        "paceSecondsPerKmDelta": numeric_delta(current.get("paceSecondsPerKm"), previous.get("paceSecondsPerKm")),
        "avgHeartRateBpmDelta": numeric_delta(current.get("avgHeartRateBpm"), previous.get("avgHeartRateBpm")),
        "kcalPerKmDelta": numeric_delta(current.get("kcalPerKm"), previous.get("kcalPerKm")),
        "vo2Delta": numeric_delta(current.get("vo2"), previous.get("vo2")),
    }


def numeric_delta(current: object, previous: object) -> float | None:
    if current is None or previous is None:
        return None
    return round(float(current) - float(previous), 1)


def sleep_baseline_facts(con: sqlite3.Connection, generated_for_date: str) -> dict[str, Any]:
    try:
        rows = list(
            con.execute(
                """
                select date,
                       totalSleepMinutes as totalSleepMinutes,
                       remSleepMinutes as remSleepMinutes,
                       deepSleepMinutes as deepSleepMinutes,
                       lightSleepMinutes as lightSleepMinutes,
                       awakeMinutes as awakeMinutes,
                       sleepScore as sleepScore
                from sleep_details
                where date < ? and totalSleepMinutes > 0
                order by date desc
                """,
                (generated_for_date,),
            )
        )
    except sqlite3.OperationalError:
        return {"available": False, "reason": "sleep table unavailable"}

    windows = {
        "baseline": sleep_window("baseline", rows[30:120]),
        "last7": sleep_window("last7", rows[:7]),
        "last14": sleep_window("last14", rows[:14]),
        "last30": sleep_window("last30", rows[:30]),
    }
    baseline = windows["baseline"]
    comparisons = [
        compare_sleep_window_to_baseline(windows[label], baseline)
        for label in ("last7", "last14", "last30")
    ]
    return {
        "available": bool(rows),
        "closedDayRule": f"uses sleep dates before {generated_for_date}",
        "baselineWindow": sleep_window_to_dict(baseline),
        "comparisons": [sleep_comparison_to_dict(item) for item in comparisons],
        "interpretationGuard": "wearable sleep stages are estimates; compare against personal baseline first",
    }


def sleep_window(label: str, rows: list[sqlite3.Row]) -> SleepWindow:
    return SleepWindow(
        label=label,
        nights=len(rows),
        total_minutes=rounded_average(rows, "totalSleepMinutes"),
        rem_minutes=rounded_average(rows, "remSleepMinutes"),
        deep_minutes=rounded_average(rows, "deepSleepMinutes"),
        light_minutes=rounded_average(rows, "lightSleepMinutes"),
        awake_minutes=rounded_average(rows, "awakeMinutes"),
        score=rounded_average(rows, "sleepScore"),
    )


def sleep_comparison_to_dict(comparison: SleepWindowComparison) -> dict[str, Any]:
    return {
        "window": sleep_window_to_dict(comparison.window),
        "baseline": sleep_window_to_dict(comparison.baseline),
        "confidence": comparison.confidence,
        "deltas": {
            "totalSleepMinutes": comparison.total_minutes_delta,
            "remSleepMinutes": comparison.rem_minutes_delta,
            "deepSleepMinutes": comparison.deep_minutes_delta,
            "lightSleepMinutes": comparison.light_minutes_delta,
            "awakeMinutes": comparison.awake_minutes_delta,
            "sleepScore": comparison.score_delta,
        },
        "interpretation": comparison.interpretation,
    }


def sleep_window_to_dict(window: SleepWindow) -> dict[str, Any]:
    return {
        "label": window.label,
        "nights": window.nights,
        "totalSleepMinutes": window.total_minutes,
        "remSleepMinutes": window.rem_minutes,
        "deepSleepMinutes": window.deep_minutes,
        "lightSleepMinutes": window.light_minutes,
        "awakeMinutes": window.awake_minutes,
        "sleepScore": window.score,
    }


def activity_sleep_threshold_facts(con: sqlite3.Connection, generated_for_date: str) -> dict[str, Any]:
    try:
        rows = list(
            con.execute(
                """
                select a.steps as steps,
                       s.totalSleepMinutes as totalSleepMinutes,
                       s.remSleepMinutes as remSleepMinutes,
                       s.deepSleepMinutes as deepSleepMinutes,
                       s.sleepScore as sleepScore
                from daily_activity_summaries a
                join sleep_details s on date(a.date, '+1 day') = s.date
                where a.date < ? and s.date < ? and a.steps > 0 and s.totalSleepMinutes > 0
                """,
                (generated_for_date, generated_for_date),
            )
        )
    except sqlite3.OperationalError:
        return {"available": False, "reason": "activity or sleep table unavailable"}

    if len(rows) < 30:
        return {
            "available": False,
            "reason": "too few activity-to-next-sleep pairs",
            "pairs": len(rows),
        }

    step_values = sorted(int(row["steps"]) for row in rows)
    low_threshold = step_values[len(step_values) // 4]
    high_threshold = step_values[(len(step_values) * 3) // 4]
    low_rows = [row for row in rows if int(row["steps"]) <= low_threshold]
    high_rows = [row for row in rows if int(row["steps"]) >= high_threshold]
    typical_rows = [
        row for row in rows
        if low_threshold < int(row["steps"]) < high_threshold
    ]
    result = compare_activity_sleep_thresholds(
        activity_sleep_group("lowActivity", low_rows),
        activity_sleep_group("typicalActivity", typical_rows),
        activity_sleep_group("highActivity", high_rows),
    )
    return {
        "available": True,
        "closedDayRule": f"uses activity and sleep dates before {generated_for_date}",
        "direction": "previous-day activity -> next measured sleep",
        "pairs": len(rows),
        "lowThresholdSteps": low_threshold,
        "highThresholdSteps": high_threshold,
        "result": activity_sleep_threshold_to_dict(result),
        "interpretationGuard": "threshold comparison is observational; it does not prove causality",
    }


def activity_sleep_group(label: str, rows: list[sqlite3.Row]) -> ActivitySleepGroup:
    steps = [int(row["steps"]) for row in rows if row["steps"] is not None]
    return ActivitySleepGroup(
        label=label,
        days=len(rows),
        min_steps=min(steps) if steps else None,
        max_steps=max(steps) if steps else None,
        avg_steps=round(sum(steps) / len(steps), 1) if steps else None,
        total_minutes=rounded_average(rows, "totalSleepMinutes"),
        rem_minutes=rounded_average(rows, "remSleepMinutes"),
        deep_minutes=rounded_average(rows, "deepSleepMinutes"),
        score=rounded_average(rows, "sleepScore"),
    )


def activity_sleep_threshold_to_dict(result: ActivitySleepThresholdResult) -> dict[str, Any]:
    return {
        "low": activity_sleep_group_to_dict(result.low),
        "typical": activity_sleep_group_to_dict(result.typical),
        "high": activity_sleep_group_to_dict(result.high),
        "confidence": result.confidence,
        "highVsTypicalDeltas": {
            "totalSleepMinutes": result.high_total_minutes_delta,
            "remSleepMinutes": result.high_rem_minutes_delta,
            "deepSleepMinutes": result.high_deep_minutes_delta,
            "sleepScore": result.high_score_delta,
        },
        "lowVsTypicalDeltas": {
            "totalSleepMinutes": result.low_total_minutes_delta,
            "remSleepMinutes": result.low_rem_minutes_delta,
            "deepSleepMinutes": result.low_deep_minutes_delta,
            "sleepScore": result.low_score_delta,
        },
        "interpretation": result.interpretation,
    }


def activity_sleep_group_to_dict(group: ActivitySleepGroup) -> dict[str, Any]:
    return {
        "label": group.label,
        "days": group.days,
        "minSteps": group.min_steps,
        "maxSteps": group.max_steps,
        "avgSteps": group.avg_steps,
        "totalSleepMinutes": group.total_minutes,
        "remSleepMinutes": group.rem_minutes,
        "deepSleepMinutes": group.deep_minutes,
        "sleepScore": group.score,
    }


def activity_workout_sleep_load_facts(con: sqlite3.Connection, generated_for_date: str) -> dict[str, Any]:
    try:
        rows = list(
            con.execute(
                f"""
                with walking_days as (
                  select date as activityDate,
                         sum(distanceMeters) / 1000.0 as walkingKm,
                         count(*) as walkingSessions
                  from workout_sessions
                  where date < ? and {CREDIBLE_WALKING_FILTER_SQL}
                  group by date
                )
                select a.steps as steps,
                       coalesce(w.walkingKm, 0.0) as walkingKm,
                       coalesce(w.walkingSessions, 0) as walkingSessions,
                       s.totalSleepMinutes as totalSleepMinutes,
                       s.remSleepMinutes as remSleepMinutes,
                       s.deepSleepMinutes as deepSleepMinutes,
                       s.sleepScore as sleepScore
                from daily_activity_summaries a
                join sleep_details s on date(a.date, '+1 day') = s.date
                left join walking_days w on w.activityDate = a.date
                where a.date < ? and s.date < ? and a.steps > 0 and s.totalSleepMinutes > 0
                """,
                (generated_for_date, generated_for_date, generated_for_date),
            )
        )
    except sqlite3.OperationalError:
        return {"available": False, "reason": "activity, workout, or sleep table unavailable"}

    if len(rows) < 30:
        return {
            "available": False,
            "reason": "too few activity/workout-to-next-sleep pairs",
            "pairs": len(rows),
        }

    step_values = sorted(int(row["steps"]) for row in rows)
    low_threshold = step_values[len(step_values) // 4]
    high_threshold = step_values[(len(step_values) * 3) // 4]
    typical_no_long_rows = [
        row for row in rows
        if low_threshold < int(row["steps"]) < high_threshold
        and float(row["walkingKm"] or 0.0) < LONG_WALK_SLEEP_MIN_DISTANCE_KM
    ]
    high_no_long_rows = [
        row for row in rows
        if int(row["steps"]) >= high_threshold
        and float(row["walkingKm"] or 0.0) < LONG_WALK_SLEEP_MIN_DISTANCE_KM
    ]
    long_walk_rows = [
        row for row in rows
        if float(row["walkingKm"] or 0.0) >= LONG_WALK_SLEEP_MIN_DISTANCE_KM
    ]
    result = compare_activity_workout_sleep_load(
        activity_workout_sleep_group("typicalNoLongWalk", typical_no_long_rows),
        activity_workout_sleep_group("highNoLongWalk", high_no_long_rows),
        activity_workout_sleep_group("longWalk", long_walk_rows),
    )
    return {
        "available": True,
        "closedDayRule": f"uses activity, workout, and sleep dates before {generated_for_date}",
        "direction": "previous-day step load / long walk -> next measured sleep",
        "pairs": len(rows),
        "longWalkThresholdKm": LONG_WALK_SLEEP_MIN_DISTANCE_KM,
        "lowThresholdSteps": low_threshold,
        "highThresholdSteps": high_threshold,
        "result": activity_workout_sleep_load_to_dict(result),
        "interpretationGuard": "observational comparison; long walk and high steps may include route, weather, stress, and schedule effects",
    }


def activity_workout_sleep_group(label: str, rows: list[sqlite3.Row]) -> ActivityWorkoutSleepGroup:
    return ActivityWorkoutSleepGroup(
        label=label,
        days=len(rows),
        avg_steps=rounded_average(rows, "steps"),
        avg_walking_km=rounded_average(rows, "walkingKm"),
        total_minutes=rounded_average(rows, "totalSleepMinutes"),
        rem_minutes=rounded_average(rows, "remSleepMinutes"),
        deep_minutes=rounded_average(rows, "deepSleepMinutes"),
        score=rounded_average(rows, "sleepScore"),
    )


def activity_workout_sleep_load_to_dict(result: ActivityWorkoutSleepLoadResult) -> dict[str, Any]:
    return {
        "typicalNoLongWalk": activity_workout_sleep_group_to_dict(result.typical_no_long_walk),
        "highNoLongWalk": activity_workout_sleep_group_to_dict(result.high_no_long_walk),
        "longWalk": activity_workout_sleep_group_to_dict(result.long_walk),
        "confidence": result.confidence,
        "highNoLongWalkVsTypicalDeltas": {
            "totalSleepMinutes": result.high_no_long_total_minutes_delta,
            "remSleepMinutes": result.high_no_long_rem_minutes_delta,
            "deepSleepMinutes": result.high_no_long_deep_minutes_delta,
            "sleepScore": result.high_no_long_score_delta,
        },
        "longWalkVsTypicalDeltas": {
            "totalSleepMinutes": result.long_walk_total_minutes_delta,
            "remSleepMinutes": result.long_walk_rem_minutes_delta,
            "deepSleepMinutes": result.long_walk_deep_minutes_delta,
            "sleepScore": result.long_walk_score_delta,
        },
        "interpretation": result.interpretation,
    }


def activity_workout_sleep_group_to_dict(group: ActivityWorkoutSleepGroup) -> dict[str, Any]:
    return {
        "label": group.label,
        "days": group.days,
        "avgSteps": group.avg_steps,
        "avgWalkingKm": group.avg_walking_km,
        "totalSleepMinutes": group.total_minutes,
        "remSleepMinutes": group.rem_minutes,
        "deepSleepMinutes": group.deep_minutes,
        "sleepScore": group.score,
    }


def sleep_after_long_walks_facts(con: sqlite3.Connection, generated_for_date: str) -> dict[str, Any]:
    try:
        rows = list(
            con.execute(
                f"""
                with walking_days as (
                  select date as activityDate,
                         sum(distanceMeters) / 1000.0 as walkingKm,
                         count(*) as walkingSessions
                  from workout_sessions
                  where date < ? and {CREDIBLE_WALKING_FILTER_SQL}
                  group by date
                )
                select s.date as sleepDate,
                       coalesce(w.walkingKm, 0.0) as prevWalkingKm,
                       coalesce(w.walkingSessions, 0) as prevWalkingSessions,
                       s.totalSleepMinutes as totalSleepMinutes,
                       s.remSleepMinutes as remSleepMinutes,
                       s.deepSleepMinutes as deepSleepMinutes,
                       s.lightSleepMinutes as lightSleepMinutes,
                       s.sleepScore as sleepScore
                from sleep_details s
                left join walking_days w on s.date = date(w.activityDate, '+1 day')
                where s.date < ? and s.totalSleepMinutes > 0
                """,
                (generated_for_date, generated_for_date),
            )
        )
    except sqlite3.OperationalError:
        return {"available": False, "reason": "sleep or workout tables unavailable"}

    long_walk_rows = [
        row for row in rows
        if float(row["prevWalkingKm"] or 0) >= LONG_WALK_SLEEP_MIN_DISTANCE_KM
    ]
    normal_rows = [
        row for row in rows
        if float(row["prevWalkingKm"] or 0) < LONG_WALK_SLEEP_MIN_DISTANCE_KM
    ]
    long_walk = sleep_walk_group(long_walk_rows)
    normal = sleep_walk_group(normal_rows)
    return {
        "available": bool(rows),
        "closedDayRule": f"uses sleep dates before {generated_for_date}",
        "thresholdKm": LONG_WALK_SLEEP_MIN_DISTANCE_KM,
        "interpretationGuard": "hypothesis only; next-night comparison does not prove causality",
        "longWalkSleep": long_walk,
        "normalSleep": normal,
        "delta": {
            "totalSleepMinutes": numeric_delta(long_walk.get("totalSleepMinutes"), normal.get("totalSleepMinutes")),
            "remSleepMinutes": numeric_delta(long_walk.get("remSleepMinutes"), normal.get("remSleepMinutes")),
            "deepSleepMinutes": numeric_delta(long_walk.get("deepSleepMinutes"), normal.get("deepSleepMinutes")),
            "sleepScore": numeric_delta(long_walk.get("sleepScore"), normal.get("sleepScore")),
        },
    }


def sleep_walk_group(rows: list[sqlite3.Row]) -> dict[str, Any]:
    return {
        "days": len(rows),
        "avgPreviousWalkingKm": rounded_average(rows, "prevWalkingKm"),
        "totalSleepMinutes": rounded_average(rows, "totalSleepMinutes"),
        "remSleepMinutes": rounded_average(rows, "remSleepMinutes"),
        "deepSleepMinutes": rounded_average(rows, "deepSleepMinutes"),
        "lightSleepMinutes": rounded_average(rows, "lightSleepMinutes"),
        "sleepScore": rounded_average(rows, "sleepScore"),
    }


def rounded_average(rows: list[sqlite3.Row], key: str) -> float | None:
    values = [float(row[key]) for row in rows if row[key] is not None]
    if not values:
        return None
    return round(sum(values) / len(values), 1)


def heart_load_facts(con: sqlite3.Connection, generated_for_date: str) -> dict[str, Any]:
    try:
        total_days = scalar(
            con,
            "select count(*) from daily_heart_summaries where date < ? and sampleCount > 0 and avgBpm is not null",
            generated_for_date,
        )
        rows = list(
            con.execute(
                f"""
                select h.date as date,
                       h.avgBpm as avgBpm,
                       h.sampleCount as sampleCount,
                       coalesce(a.steps, 0) as steps,
                       s.totalSleepMinutes as totalSleepMinutes,
                       coalesce(w.totalDurationMinutes, 0) as workoutMinutes
                from daily_heart_summaries h
                left join daily_activity_summaries a on a.date = h.date
                left join sleep_details s on s.date = h.date
                left join daily_workout_summaries w on w.date = h.date
                where h.date < ? and {CREDIBLE_DAILY_HEART_FILTER_SQL}
                """,
                (generated_for_date,),
            )
        )
    except sqlite3.OperationalError:
        return {"available": False, "reason": "heart table unavailable"}

    values = [float(row["avgBpm"]) for row in rows if row["avgBpm"] is not None]
    baseline = round(sum(values) / len(values), 1) if values else None
    std_dev = standard_deviation(values)
    threshold = round(baseline + std_dev, 1) if baseline is not None and std_dev is not None else None
    high_rows = [row for row in rows if threshold is not None and float(row["avgBpm"]) >= threshold]
    normal_rows = [row for row in rows if threshold is not None and float(row["avgBpm"]) < threshold]
    high = heart_group(high_rows)
    normal = heart_group(normal_rows)

    return {
        "available": bool(rows),
        "closedDayRule": f"uses dates before {generated_for_date}",
        "filter": f"sampleCount >= {HEART_MIN_DAILY_SAMPLES}, avgBpm 35-220",
        "totalHeartDays": total_days,
        "credibleHeartDays": len(rows),
        "excludedLowCoverageDays": max(total_days - len(rows), 0),
        "baselineAvgBpm": baseline,
        "highDayThresholdAvgBpm": threshold,
        "highAvgHeartDays": high,
        "otherHeartDays": normal,
        "delta": {
            "sleepMinutes": numeric_delta(high.get("totalSleepMinutes"), normal.get("totalSleepMinutes")),
            "steps": numeric_delta(high.get("steps"), normal.get("steps")),
            "workoutMinutes": numeric_delta(high.get("workoutMinutes"), normal.get("workoutMinutes")),
        },
        "interpretationGuard": "daily average HR only; not resting HR and not diagnosis",
    }


def heart_group(rows: list[sqlite3.Row]) -> dict[str, Any]:
    return {
        "days": len(rows),
        "avgBpm": rounded_average(rows, "avgBpm"),
        "avgSamples": rounded_average(rows, "sampleCount"),
        "totalSleepMinutes": rounded_average(rows, "totalSleepMinutes"),
        "steps": rounded_average(rows, "steps"),
        "workoutMinutes": rounded_average(rows, "workoutMinutes"),
    }


def standard_deviation(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / len(values)
    return variance ** 0.5


def research_rules() -> dict[str, list[str]]:
    return {
        "activityAndFitness": [
            "Compare pace, heart rate, kcal/km, and VO2 only within comparable workout types and distance bands.",
            "Filter impossible workout duration, pace, and heart-rate records before interpreting fitness.",
            "Heart-rate recovery is useful only when post-exercise HR data exists; do not infer it now.",
            "For age 40, an estimated max HR is about 180 bpm; use general intensity zones cautiously.",
        ],
        "sleep": [
            "Use total sleep duration, consistency, 7/14/30 measured-night windows, and personal baseline first.",
            "Treat REM/deep/light as wearable estimates, not clinical truth.",
            "Test activity-to-sleep and sleep-to-next-day directions separately.",
            "Do not claim movement improves sleep unless the user's own data supports it.",
        ],
        "heart": [
            "Daily average HR is not resting HR.",
            "Use high daily HR only as load/recovery context paired with sleep and activity.",
            "Do not diagnose disease, arrhythmia, overtraining, or metabolic issues.",
        ],
    }


def ai_role() -> dict[str, Any]:
    return {
        "placement": "after deterministic analytics, before final human-facing explanation",
        "allowed": [
            "prioritize deterministic findings",
            "translate evidence into plain language",
            "state what is weak or not proven",
            "suggest next measurements or tests",
            "propose which insight belongs on the main app screen",
        ],
        "notAllowed": [
            "calculate metrics from raw files",
            "invent missing data",
            "hide low confidence",
            "make medical diagnosis",
            "show technical provider/source details to the user",
        ],
    }


def forbidden_conclusions() -> list[str]:
    return [
        "More steps clearly improve sleep.",
        "Calories prove metabolism improved.",
        "Daily average HR is resting HR.",
        "Running fitness trend is reliable with the current small sample.",
        "Weight or body composition explains sleep, pulse, or performance.",
        "Any medical diagnosis or treatment recommendation.",
    ]


def expected_output() -> dict[str, Any]:
    return {
        "language": "Polish, plain, direct, non-technical",
        "format": [
            "topFindings: 3-5 short findings with confidence",
            "whatChanged: trend changes worth showing in the app",
            "whatIsWeak: unsupported or low-confidence claims",
            "nextTests: concrete data tests to run next",
            "mainScreenCopy: app-ready text without technical source wording",
        ],
    }


def build_prompt(bundle: dict[str, Any]) -> str:
    compact_json = json.dumps(bundle, ensure_ascii=True, indent=2)
    return "\n".join(
        [
            "# VitaTrace AI Prompt",
            "",
            "You are the explanation layer for VitaTrace, a personal health data analyst.",
            "Use only the deterministic facts in the JSON bundle below.",
            "Write in Polish for the user. Be direct and practical.",
            "",
            "Rules:",
            "- Do not diagnose.",
            "- Do not invent missing data.",
            "- Do not mention sync, API, Health Connect, database, quota, SDK, or provider internals.",
            "- Do not claim causality from correlation.",
            "- Keep weak evidence visibly weak.",
            "- Produce app-ready wording, not developer diagnostics.",
            "",
            "Return:",
            "1. topFindings",
            "2. whatChanged",
            "3. whatIsWeak",
            "4. nextTests",
            "5. mainScreenCopy",
            "",
            "JSON bundle:",
            "",
            "```json",
            compact_json,
            "```",
            "",
        ]
    )


def latest_generated_for_date(con: sqlite3.Connection) -> str | None:
    try:
        row = con.execute(
            """
            select max(generatedForDate)
            from analysis_results
            where isCurrent = 1
            """
        ).fetchone()
    except sqlite3.OperationalError:
        return None
    return row[0] if row and row[0] else None


def scalar(con: sqlite3.Connection, query: str, *params: object) -> int:
    try:
        row = con.execute(query, params).fetchone()
    except sqlite3.OperationalError:
        return 0
    return int(row[0] or 0) if row else 0


def parse_json_object(text: str) -> dict[str, Any]:
    try:
        value = json.loads(text)
    except (TypeError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def keep_string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]


def sanitize_ai_text(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    replacements = {
        "GPX": "podobne trasy",
        "gpx": "podobne trasy",
        "rawPayloadJson": "usuniete dane surowe",
    }
    text = value
    for old, new in replacements.items():
        text = text.replace(old, new)
    return text


if __name__ == "__main__":
    raise SystemExit(main())
