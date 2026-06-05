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

Historical import should write into the same normalized daily tables that Health Connect now uses:

- `daily_activity_summaries`
- `daily_heart_summaries`
- `daily_sleep_summaries`
- `daily_workout_summaries`
- `daily_body_summaries`

This keeps dashboard, desktop sync, and future AI summaries independent from the original source format.

## Import Rules

- Do not commit raw historical exports.
- Parse with a real CSV parser, not ad hoc string splitting.
- Import into a staging model first.
- Validate date ranges, units, row counts, and duplicates before writing normalized summaries.
- Keep source provenance: `MI_FITNESS_EXPORT`, file name, and import timestamp.
- Prefer daily aggregates for dashboard and AI. Keep raw detail local only if a clear feature needs it.
- Do not mix basal/total calories into activity calories. Map historical activity calories conservatively and mark uncertain fields.

## Next Implementation Step

Implementation should happen in stages:

1. Create sanitized sample fixtures with a few fake rows for each supported file shape.
2. Build JVM parser tests for Mi Fitness CSV and workout metadata.
3. Add staging tables or staging models that preserve source file, source key, raw timestamp, unit, and parsed value.
4. Normalize staging rows into the existing daily summary tables.
5. Run the importer on the private export locally only.
6. Compare imported aggregates against Mi Fitness visible totals and the existing export profile.

After tests pass, run the importer on the private export and compare:

- imported date range
- total active days
- steps by day
- heart samples by day
- sleep sessions by day
- workout sessions and GPX count

## Initial Mapping Targets

`hlth_center_fitness_data.csv`:

- `steps` -> `daily_activity_summaries.steps`
- activity/distance keys, when confidently identified -> `daily_activity_summaries.distanceMeters`
- active movement calories only -> `daily_activity_summaries.activeCaloriesKcal`
- `heart_rate` and resting heart keys -> `daily_heart_summaries`
- `watch_night_sleep` and related sleep keys -> `daily_sleep_summaries`
- `weight` -> `daily_body_summaries.latestWeightKg`
- `vo2_max` -> `daily_body_summaries.latestVo2Max`
- `single_spo2` -> `daily_body_summaries.latestSpo2Percent`

Workout files:

- session date, duration, distance, calories, average heart rate, cadence -> `daily_workout_summaries`
- GPX paths remain local route detail and should not be sent to AI.

Unclear fields must be imported with uncertainty flags or skipped until verified.
