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
- Room now includes normalized daily summary tables for activity, heart, sleep, workouts, and body metrics.
- The first Android dashboard reads from local daily summary tables, not directly from Health Connect.
- App launch loads the local dashboard cache first. The `Refresh` button performs the heavier Health Connect sync to avoid hitting Health Connect API quotas on every app open.
- Health Connect sync failures must stay non-blocking: keep showing the local dashboard and show a short user-facing sync notice instead of raw exception text.
- AI is not the first milestone.
- Future AI may use DeepSeek only with aggregated summaries and explicit user consent.
- Future photo/scan imports need an architectural opening now, but OCR is not part of MVP.
- Body composition and lab results must be treated as trend/supporting data, not diagnosis.
- Health Connect `TotalCaloriesBurnedRecord` must not be shown as activity calories. It can include basal/resting energy and produced misleading 7/30-day values. Use `ActiveCaloriesBurnedRecord` for movement/workout calories.
- VitaTrace must not copy Mi Fitness 1:1. Mi Fitness is a source/collector; VitaTrace is the trend, baseline, reliability, and analysis layer above it.
- Deterministic analysis logic should live outside Compose UI, currently under `app/src/main/java/com/vitrace/app/analysis/`.

## Important Local Paths

- Private raw data: `dane historyczne z zegarka/`
- Private historical docs: `dokumentacja/`
- Public agent instructions: `AGENTS.md`
- Public clean project context: `docs/project-context.md`
- Public Health Connect checklist: `docs/health-connect-live-data.md`
- Public historical import plan: `docs/historical-data-import.md`
- Public Android setup: `docs/android-setup.md`
- Public desktop roadmap: `docs/desktop-roadmap.md`
- Public analytics product plan: `docs/analytics-product-plan.md`
- Public living memory: `docs/agent-memory.md`

## Next Recommended Steps

1. Add sample/sanitized CSV fixtures that contain no private health data.
2. Build parser and importer tests before importing the full private export.
3. Implement Mi Fitness historical import into the normalized daily summary tables.
4. Improve dashboard layout after it has real historical data.
5. Turn Health Connect quality snapshots into a fuller source reliability screen.
6. Add background sync after foreground sync remains stable.

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
| 2026-06-05 | `vitrace/mvp-foundation` | Added normalized daily summary tables and foreground Health Connect sync for the last 30 days; documented historical import rules. | `:app:assembleDebug` passed; APK installed with ADB; UI verified on device with local daily summary counts. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added first local-database dashboard with Today, 7-day, 30-day activity windows and signal coverage. | `:app:assembleDebug` passed; APK installed with ADB; dashboard screenshot/UI dump verified on device. |
| 2026-06-05 | `vitrace/mvp-foundation` | Redesigned the dashboard into readable cards and changed startup to load cached local data before manual Health Connect refresh. | `:app:assembleDebug` passed; APK installed with ADB; screenshot/UI dump verified on device. |
| 2026-06-05 | `vitrace/mvp-foundation` | Replaced raw Health Connect exception display with a compact sync notice and kept cached dashboard visible on sync failures. | `:app:assembleDebug` passed; APK installed with ADB; UI dump verified no raw error text on startup. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added analytics product plan, first tabbed app structure, and separated deterministic analysis rules from the Compose screen. | `:app:assembleDebug` passed; APK installed with ADB; Start, Analysis, and Data tabs verified with screenshots. |

## Update Protocol

When you finish a session, append or update:

- What changed.
- Which files matter.
- Which tests/checks were run.
- What remains next.
- Any new decision or assumption.

Keep this file short enough to read quickly. Move long details into separate docs and link them here.
