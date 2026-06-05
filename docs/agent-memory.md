# VitaTrace Agent Memory

This is the living project memory. Update it after every meaningful work session.

## Current State

- App name: VitaTrace.
- GitHub repo: https://github.com/krapcys1-maker/vitrace-health-analyst
- Working branch: `vitrace/mvp-foundation`.
- Default branch: `main`.
- Repository visibility: public.
- Tracked safe starter files exist: `README.md`, `.gitignore`, `.env.example`, `AGENTS.md`, `docs/`.
- Private local files exist and must stay private:
  - `.env`
  - `dane historyczne z zegarka/`
  - `dokumentacja/` unless explicitly sanitized before committing.

## What Has Been Done

- Reviewed local planning docs and Mi Fitness export structure.
- Chose app name: VitaTrace.
- Created public GitHub repository `krapcys1-maker/vitrace-health-analyst`.
- Created and pushed branch `vitrace/mvp-foundation`.
- Added privacy-first `.gitignore`.
- Added `.env.example` without secrets.
- Added starter `README.md`.
- Confirmed `.env` contains DeepSeek-related config names but did not expose values.

## Data Findings

Local Mi Fitness export summary:

- Main health file: `hlth_center_fitness_data.csv`
- Rows: 522,004
- Time span: 2021-11-09 to 2026-06-05
- Important keys: `steps`, `calories`, `energy`, `heart_rate`, `stress`, `valid_stand`, `vo2_max`, `resting_heart_rate`, `watch_night_sleep`, `single_spo2`, `weight`, `abnormal_heart_rate`.
- Sport records: 303 sessions, mostly outdoor walking.
- GPX tracks: 281 tracks.
- Aggregated daily reports exist and should be used for sanity checks, not as the source of truth.
- Xiaomi PDF guide mentions `hlth_center_medical_data.csv`, which is not present now but should be considered for future medical/lab import architecture.

## Decisions

- MVP starts with Mi Fitness CSV import, local database, normalized metrics, daily aggregates, dashboard, and Data Quality.
- Health Connect starts as availability/probe/diagnostics, then full sync after real phone data appears.
- Android app comes first. Desktop companion is planned later and should consume Android-exported aggregates.
- Local database is the source of truth.
- Android app now has an initial Room database (`vitrace.db`) with Health Connect quality snapshots.
- AI is not the first milestone.
- Future AI may use DeepSeek only with aggregated summaries and explicit user consent.
- Future photo/scan imports need an architectural opening now, but OCR is not part of MVP.
- Body composition and lab results must be treated as trend/supporting data, not diagnosis.
- Health Connect `TotalCaloriesBurnedRecord` must not be shown as activity calories. It can include basal/resting energy and produced misleading 7/30-day values. Use `ActiveCaloriesBurnedRecord` for movement/workout calories.

## Important Local Paths

- Private raw data: `dane historyczne z zegarka/`
- Private historical docs: `dokumentacja/`
- Public agent instructions: `AGENTS.md`
- Public clean project context: `docs/project-context.md`
- Public Health Connect checklist: `docs/health-connect-live-data.md`
- Public Android setup: `docs/android-setup.md`
- Public desktop roadmap: `docs/desktop-roadmap.md`
- Public living memory: `docs/agent-memory.md`

## Next Recommended Steps

1. Turn Health Connect quality snapshots into a proper sync state screen with last successful sync and source reliability.
2. Add normalized daily summary tables for activity, heart, sleep, workouts, and body metrics.
3. Implement foreground Health Connect sync into those normalized tables.
4. Sanitize and migrate useful content from `dokumentacja/` into public `docs/` files.
5. Add fake/sample CSV fixtures that contain no private health data.
6. Build parser and importer tests before importing the full private export.

## Work Log

| Date | Branch | Summary | Tests |
|---|---|---|---|
| 2026-06-05 | `vitrace/mvp-foundation` | Created repo, branch, starter README, gitignore, env example. | Not applicable; no app code yet. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added agent guide, memory file, and clean project context. | Not applicable; documentation only. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added Health Connect live data checklist and implementation order. | Not applicable; documentation only. |
| 2026-06-05 | `vitrace/mvp-foundation` | Started Android Compose project and Health Connect diagnostics screen. | `gradlew.bat tasks` passed; `:app:assembleDebug` later passed after installing Android SDK tools. |
| 2026-06-05 | `vitrace/mvp-foundation` | Installed Android command-line tools, SDK Platform 36, Build-Tools, Platform-Tools/ADB; built debug APK. | APK generated at `app/build/outputs/apk/debug/app-debug.apk`. |
| 2026-06-05 | `vitrace/mvp-foundation` | Fixed Health Connect diagnostics: removed total/basal calories from the activity view and kept active calories only. | `:app:assembleDebug` passed; APK installed with ADB and screenshot verified. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added Room and a Health Connect Data Quality screen that records per-metric 1/7/30-day counts, origins, and last record timestamps. | `:app:assembleDebug` passed; APK installed with ADB; screenshots/UI dump verified on device. |

## Update Protocol

When you finish a session, append or update:

- What changed.
- Which files matter.
- Which tests/checks were run.
- What remains next.
- Any new decision or assumption.

Keep this file short enough to read quickly. Move long details into separate docs and link them here.
