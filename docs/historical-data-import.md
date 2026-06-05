# Historical Data Import Plan

VitaTrace has two data tracks:

- Health Connect for new/live phone data.
- Private Mi Fitness export files for historical data.

The historical files are useful because Health Connect should not be treated as the source for older history. Health Connect reads may be limited by permission timing and by whatever Mi Fitness decides to write after connection.

## Current Historical Source

Private local source:

- `dane historyczne z zegarka/`
- `dokumentacja/`

Known Mi Fitness export structure from earlier inspection:

- `hlth_center_fitness_data.csv`
- sport session files
- GPX tracks
- daily report files

These files must stay private unless a small fake/sanitized fixture is created explicitly for tests.

## Target Import Shape

Historical import must preserve rich source detail first, then generate daily summaries for fast dashboard screens.

Detailed local tables should include:

- raw/staging records with source file, source key, timestamp, payload JSON, parser version, and import time
- sleep details with date, bedtime, wake time, total minutes, deep/light/REM/awake minutes, awake count, sleep score, source, and raw record reference
- workout sessions with date, sport type, start/end, duration, distance, active calories, total calories, min/avg/max heart rate, pace, cadence, training effect, recovery time, VO2 max, GPX reference, source, and raw record reference
- daily activity records with canonical steps, distance, active calories, and estimated kilometers
- daily heart summaries, with sample-level or compressed heart series added later only if needed for an actual analysis feature

Daily summary tables remain useful for quick screens:

- `daily_activity_summaries`
- `daily_heart_summaries`
- `daily_sleep_summaries`
- `daily_workout_summaries`
- `daily_body_summaries`

This keeps dashboard, desktop sync, and future AI summaries independent from the original source format without throwing away analytical detail.

## Import Rules

- Do not commit raw historical exports.
- Parse with a real CSV parser, not ad hoc string splitting.
- Import into a staging model first.
- Validate date ranges, units, row counts, and duplicates before writing normalized summaries.
- Keep source provenance: `MI_FITNESS_EXPORT`, file name, and import timestamp.
- Prefer daily aggregates for dashboard speed only. AI and deterministic analytics must be built from detailed analytical tables and then summarized into an `AiHealthSummary`.
- Do not discard rich fields just because the first UI does not show them.
- Do not mix basal/total calories into activity calories. Map historical activity calories conservatively and mark uncertain fields.

## Next Implementation Step

Implementation should happen in stages:

1. Create sanitized sample fixtures with a few fake rows for each supported file shape.
2. Build parser tests for Mi Fitness CSV sleep, workout, activity, and profile metadata.
3. Add staging tables or staging models that preserve source file, source key, raw timestamp, unit, parsed value, and raw payload JSON.
4. Add detailed sleep and workout session tables.
5. Normalize staging/detail rows into the existing daily summary tables.
6. Run the importer on the private export locally only.
7. Compare imported aggregates and detailed counts against Mi Fitness visible totals and the existing export profile.

Current local development importer:

- `tools/import_mifitness_history_to_db.py`
- Reads the private Mi Fitness export directory.
- Writes normalized daily aggregates into a copied VitaTrace SQLite database.
- Does not commit raw CSV data or generated databases.
- Current mapped aggregates: activity, heart, sleep, workouts, weight, VO2 max, SpO2, and user profile settings.
- `tools/audit_mifitness_import.py`
- Verifies the copied database against private Mi Fitness daily reports and workout records before the import is trusted.

After tests pass, run the importer on the private export and compare:

- imported date range
- total active days
- steps by day
- steps by month and year
- estimated kilometers by month and year using `stepsPerKm`
- heart samples by day
- sleep sessions by day
- workout sessions and GPX count
- VO2 max records if present in historical export

## Initial Mapping Targets

`hlth_center_fitness_data.csv`:

- raw `steps` records are not imported into dashboard summaries; they can double-count phone and wearable sources and can also contain raw-only days that do not match Mi Fitness monthly/yearly totals
- raw `heart_rate` can fill extra sample-only heart days when no `daily_report/heart_rate` exists
- raw `watch_night_sleep` can act as fallback, but `daily_report/sleep` is preferred when present
- `weight` -> `daily_body_summaries.latestWeightKg`
- `vo2_max` -> `daily_body_summaries.latestVo2Max`
- `single_spo2` can act as fallback, but `daily_report/spo2.avg_spo2` is preferred when present

Workout files:

- session date, start/end, sport type, duration, distance, active calories, total calories, average/min/max heart rate, cadence, pace, training effect, recovery time, VO2 max, GPX reference -> detailed `workout_sessions`
- daily totals generated from `workout_sessions` -> `daily_workout_summaries`
- `sport_type = 1` -> running training summaries.
- `sport_type = 2` -> walking training summaries.
- Running and walking training summaries are stored separately from daily step totals.
- GPX paths remain local route detail and should not be sent to AI.

Unclear fields must be imported with uncertainty flags or skipped until verified.

`hlth_center_aggregated_fitness_data.csv`:

- `daily_report` + `steps` is the canonical source for daily steps, distance, and activity calories because it matches Mi Fitness app totals after source deduplication.
- `daily_report` + `heart_rate` is the canonical source for daily average, min, and max heart rate when present.
- `daily_report` + `sleep` is the canonical source for daily sleep detail when present: total duration, deep sleep, light sleep, REM, awake duration, awake count, sleep score, bedtime, and wake time.
- `daily_report` + `spo2` is the canonical source for daily average SpO2 when present.
- Do not calculate monthly/yearly steps by summing raw `steps` rows from multiple `Sid` values.
- Example audit: September 2024 raw `Sid` sum was 611,046 steps, but canonical daily reports sum to 325,587 steps, matching Mi Fitness.

Current verified audit from 2026-06-05:

- Activity: 1,031 canonical days, 0 bad days, 0 bad months, 0 bad years, 0 extra positive DB days.
- Years: 2021 = 173,975; 2022 = 455,416; 2023 = 1,150,305; 2024 = 2,799,620; 2025 = 3,191,192; 2026 = 1,366,486 steps.
- Best month in the export: 2026-04 = 559,104 steps.
- September 2024: 325,587 steps.
- Heart: 196 canonical daily reports, 0 bad canonical days, plus 2 extra raw-only heart days.
- Sleep: 133 canonical daily reports, 0 bad days.
- SpO2: 5 canonical daily reports, 0 bad days.
- Workouts: 303 sessions across 230 days, 0 bad days.
- Sleep details: 133 expected, 133 actual, 0 bad; all 133 have stages, sleep score, bedtime, and wake time.
- Workout details: 303 expected, 303 actual, 0 bad; 286 have distance/pace, 303 active calories, 299 average heart rate, 300 max heart rate, 282 cadence, 280 VO2 max, and 281 GPX references.

Before saying the history import is correct, run:

```powershell
python tools\import_mifitness_history_to_db.py --export-dir "dane historyczne z zegarka" --db "$env:TEMP\vitrace_full_history_audit\vitrace.db" --age-years 40 --height-cm 186 --weight-kg 88 --steps-per-km 1250 --timezone-offset-hours 3
python tools\audit_mifitness_import.py --export-dir "dane historyczne z zegarka" --db "$env:TEMP\vitrace_full_history_audit\vitrace.db" --timezone-offset-hours 3
```

## Analytics Outputs From History

Historical import should make these deterministic calculations possible before any AI summary:

- yearly step totals
- monthly step totals
- weekly step totals
- estimated kilometers with configurable `stepsPerKm`, default `1250`
- best year, month, and week
- trend versus previous year/month
- sleep/activity correlation candidates
- cardio efficiency candidates from workouts, heart rate, pace, and VO2 max when available
- body composition correlations when weight/body fat/muscle data exists
