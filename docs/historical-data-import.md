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

Create a private local importer command or Android import screen that can read a small sanitized fixture first. After tests pass, run the importer on the private export and compare:

- imported date range
- total active days
- steps by day
- heart samples by day
- sleep sessions by day
- workout sessions and GPX count
