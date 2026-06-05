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
- AI is not the first milestone.
- Future AI may use DeepSeek only with aggregated summaries and explicit user consent.
- Future photo/scan imports need an architectural opening now, but OCR is not part of MVP.
- Body composition and lab results must be treated as trend/supporting data, not diagnosis.

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

1. Sanitize and migrate useful content from `dokumentacja/` into public `docs/` files.
2. Create Android project scaffold: Kotlin, Compose, Room.
3. Implement import architecture before UI polish.
4. Add fake/sample CSV fixtures that contain no private health data.
5. Build parser and importer tests before importing the full private export.
6. Add Health Connect probe screen only after baseline app structure exists.

## Work Log

| Date | Branch | Summary | Tests |
|---|---|---|---|
| 2026-06-05 | `vitrace/mvp-foundation` | Created repo, branch, starter README, gitignore, env example. | Not applicable; no app code yet. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added agent guide, memory file, and clean project context. | Not applicable; documentation only. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added Health Connect live data checklist and implementation order. | Not applicable; documentation only. |
| 2026-06-05 | `vitrace/mvp-foundation` | Started Android Compose project and Health Connect diagnostics screen. | `gradlew.bat tasks` passed; `:app:assembleDebug` blocked by missing Android SDK path. |

## Update Protocol

When you finish a session, append or update:

- What changed.
- Which files matter.
- Which tests/checks were run.
- What remains next.
- Any new decision or assumption.

Keep this file short enough to read quickly. Move long details into separate docs and link them here.
