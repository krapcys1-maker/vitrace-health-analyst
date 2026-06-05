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
- Aggregated Mi Fitness `daily_report` rows are the canonical source for imported daily steps/distance/activity calories, daily heart aggregates, sleep totals, and SpO2 aggregates when present.
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
- Main product direction is Personal Body Intelligence: understand the user's own body from long-term personal data, including yearly/monthly steps, estimated km, sleep/activity correlations, cardio efficiency, VO2 max, weight, and future body composition.
- Use configurable `stepsPerKm` for estimated distance from steps; default should be `1250`.
- Correlation insights require sample size and confidence rules. Do not show claims from fewer than 14 comparable days.
- User profile currently set in app DB: male, 40 years, 186 cm, 88 kg, `stepsPerKm = 1250`.
- Health Connect foreground sync must merge with richer historical import rows and must not overwrite history with zero/poorer records.
- Mi Fitness historical activity totals must use `hlth_center_aggregated_fitness_data.csv` `daily_report/steps`. Raw `hlth_center_fitness_data.csv` step rows across multiple `Sid` values double-count phone and wearable sources, and raw-only step days can make monthly/yearly totals disagree with Mi Fitness.
- Do not mark history import correct until `tools/audit_mifitness_import.py` reports 0 bad activity days, 0 bad months, 0 bad years, and 0 extra positive activity days.
- Latest verified history audit: 1,031 canonical activity days, 46 months, 6 years, all matching; 2024-09 = 325,587 steps; 2025 = 3,191,192 steps; best month = 2026-04 with 559,104 steps.
- Latest live-vs-history comparison: Health Connect is connected but much poorer than the Mi Fitness export on 2026-06-05. Live has current steps/activity/heart/exercise records, but 0 Health Connect sleep, SpO2, VO2 max, and weight records in the latest 30-day quality snapshot. Keep Mi Fitness export as historical baseline and Health Connect as live/freshness source.
- Main UI direction changed from diagnostic tabs to domain tabs: `Sen`, `Sport`, `Waga`, `Zdrowie`, `Analiza`, `Opcje`. Synchronization and permissions belong only in `Opcje`; health domains should show useful human summaries, trends, and future AI analysis entry points.
- Important product rule: `Waga` must distinguish a simple historical mass record from full scale/body-composition data. Body composition means weight, body fat, muscle, water, etc. Do not present pulse, VO2 max, or SpO2 as weight/scale data. Those signals can be correlated with weight later in `Analiza` or `Zdrowie`, but the app must clearly say when body-composition data is not available yet.
- Domain tabs should not dump every card into one long scroll. Each domain should use internal sections, for example `Przeglad / Historia / Analiza`, so the user manages one focused view at a time.
- Sport must keep `Kroki` separate from training-derived `Chodzenie` and `Bieganie`. Steps come from daily activity summaries. Walking/running come from Mi Fitness sport records and `daily_workout_type_summaries`.
- `Zdrowie` now includes a local health journal direction: daily notes, subjective physical/mental state, future lab scans, and AI summaries comparing newest lab results with historical results and daily context.
- Course correction from 2026-06-05: daily aggregate tables are not enough for the product goal. They are useful for fast totals and UI checks, especially yearly/monthly steps and estimated kilometers, but the analytical foundation must preserve rich details first: sleep stages/score/times and individual workout sessions with pace, calories, heart rate, cadence, VO2 max, and GPX references when present. Build detailed analytical tables before expanding UI or AI.

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
- Public Personal Body Intelligence prompt: `docs/personal-body-intelligence-prompt.md`
- Public data source comparison: `docs/data-source-comparison.md`
- Public health journal and lab analysis plan: `docs/health-journal-and-labs.md`
- Public AI architecture plan: `docs/ai-analysis-architecture.md`
- Public personal analytics architecture: `docs/personal-analytics-architecture.md`
- Public local importer tool: `tools/import_mifitness_history_to_db.py`
- Public local import audit tool: `tools/audit_mifitness_import.py`
- Public living memory: `docs/agent-memory.md`

## Next Recommended Steps

1. Freeze UI expansion and define the analytical data model before adding more screens.
2. Add detailed Mi Fitness import tables/models for sleep details and individual workout sessions.
3. Preserve raw/staging payload references so importer mistakes can be fixed without losing source detail.
4. Add parser/importer tests and audits for sleep stages, sleep score, workout sessions, pace, calories, heart rate, cadence, VO2 max, yearly/monthly step totals, and estimated kilometers.
5. Re-run the private export import and verify counts manually before trusting analysis.
6. Keep the useful long-term step/km stats because Mi Fitness does not expose them well.
7. Only after detailed import is verified, build deterministic analysis context and correlation confidence rules. Start with monthly sleep phase averages, monthly sport trends, high-activity versus low-activity sleep comparison, running/walking cardio efficiency, and calorie efficiency.
8. Then redesign the UI around insights, with domain tabs as drill-down rather than the product core.
9. Add AI only after deterministic analytics can produce a compact `AiHealthSummary`.

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
| 2026-06-05 | `vitrace/mvp-foundation` | Fixed system bar clipping and redesigned the Data tab from technical Health Connect records into user-facing source coverage cards. | `:app:assembleDebug` passed; APK installed with ADB; bottom of Start, Analysis, and Data verified with screenshots. |
| 2026-06-05 | `vitrace/mvp-foundation` | Expanded product plan around Personal Body Intelligence, yearly/monthly steps, estimated kilometers, correlation confidence, body composition, VO2 max, and AI summary rules. | Documentation only; no build needed. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added user profile table, long-term activity summaries, local Mi Fitness history importer, and imported private history into the phone database. | `:app:assembleDebug` passed; importer reported 984 daily rows; APK installed; Start, Analysis, and Profile screenshots verified; Health Connect sync kept imported history intact. |
| 2026-06-05 | `vitrace/mvp-foundation` | Fixed historical step import to use Mi Fitness daily reports instead of summing raw multi-source step rows. | Importer rerun; September 2024 changed from incorrect 611,046 raw steps to canonical 325,587 steps; phone database updated and Analysis screenshot verified. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added repeatable Mi Fitness import auditor and made historical heart, sleep, and SpO2 prefer canonical daily reports. Disabled raw step fallback so monthly/yearly totals match Mi Fitness. | `python -m py_compile tools/import_mifitness_history_to_db.py tools/audit_mifitness_import.py` passed; audit passed with activity daily_bad=0, month_bad=0, year_bad=0, extra_positive_days=0; heart/sleep/SpO2/workouts had 0 canonical mismatches. |
| 2026-06-05 | `vitrace/mvp-foundation` | Compared Mi Fitness historical import with current Health Connect live data and documented source coverage. | Manual Health Connect sync from phone UI; pulled phone DB; latest snapshot showed live steps/activity/heart/exercise only, with sleep/SpO2/VO2/weight all 0 in Health Connect. |
| 2026-06-05 | `vitrace/mvp-foundation` | Reworked Android UI into domain tabs for Sleep, Sport, Weight, Health, Analysis, and Options; moved sync/profile/source controls into Options and added first sleep/sport/body summaries. | `:app:assembleDebug` passed; APK installed with ADB; UI dumps verified Sleep, Sport, Weight, Health, Analysis, and Options tabs on the phone. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added local health notes storage and expanded the Health tab into daily journal plus future AI/lab analysis area. | `:app:assembleDebug` passed; APK installed with ADB; Room migrated to version 4 on phone; Health tab UI verified; test note save was verified and no test note remains in the phone DB. |
| 2026-06-05 | `vitrace/mvp-foundation` | Reworked domain screens to use internal section switches instead of dumping all cards into long vertical scrolls. | `:app:assembleDebug` passed; APK installed with ADB; screenshots/UI dumps verified Health, Sport, and Options section switches on the phone. |
| 2026-06-05 | `vitrace/mvp-foundation` | Split Sport into `Kroki`, `Chodzenie`, `Bieganie`, and `Analiza`; added workout-type summaries so walking/running come from training records, not step totals. | `:app:assembleDebug` passed; importer rerun; phone DB updated; UI dumps verified Sport/Kroki, Sport/Chodzenie, and Sport/Bieganie with running 4 sessions and 16.3 km in last 30 days. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added detailed analytical storage for sleep stages and workout sessions; refocused AI placement as an explanation layer over deterministic `AiHealthSummary`, not over UI cards. | `:app:assembleDebug` passed; importer and audit passed; phone DB verified with 133 sleep detail rows and 303 workout sessions after app launch. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added first deterministic `AnalysisContextBuilder`: monthly sleep phases, sleep/activity comparison, and monthly walking/running sport trends; surfaced first results in Sleep and Analysis tabs. | `:app:assembleDebug` passed; APK installed; phone UI verified Sleep/Historia, Sleep/Analiza, and Analysis tab with 12 sleep-phase months, 133 sleep+activity days, and 24 sport trend rows. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added persistent `analysis_results` current snapshots plus time context so analyses distinguish closed history/current windows from today's partial data. | `:app:assembleDebug` passed; APK installed; phone DB verified v7 with one `sleep_activity/current_snapshot`, current period 2026-05-06 to 2026-06-04, confidence High, sample 132. |
| 2026-06-05 | `vitrace/mvp-foundation` | Added deterministic `sport_efficiency/current_snapshot` comparing closed walking/running months for distance, sessions, HR, pace, kcal/km, kcal/min, and confidence; documented chart plan and retained only recent unpinned current snapshots. | `:app:assembleDebug` passed; APK installed; phone DB verified current `sleep_activity` and `sport_efficiency`; UI verified Sport/Analiza shows walking 2026-05 vs 2026-04 with low-confidence volume warning and running as insufficient. |

## Update Protocol

When you finish a session, append or update:

- What changed.
- Which files matter.
- Which tests/checks were run.
- What remains next.
- Any new decision or assumption.

Keep this file short enough to read quickly. Move long details into separate docs and link them here.
