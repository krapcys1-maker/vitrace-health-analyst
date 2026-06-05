"""Build a privacy-safe AI context bundle from deterministic VitaTrace results."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sqlite3
import sys
from pathlib import Path
from typing import Any


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
        "heartDays": scalar(con, "select count(*) from daily_heart_summaries where date < ? and sampleCount > 0 and avgBpm is not null", generated_for_date),
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
                "answer": result.get("answer", row["summaryText"]),
                "confidence": row["confidence"],
                "sampleSize": int(row["sampleSize"]),
                "dateRange": result.get("dateRange"),
                "evidence": keep_string_list(result.get("evidence")),
                "limitations": keep_string_list(result.get("limitations")),
                "nextStep": result.get("nextStep"),
            }
        )
    return insights


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


if __name__ == "__main__":
    raise SystemExit(main())
