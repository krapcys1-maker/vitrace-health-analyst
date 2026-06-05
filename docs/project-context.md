# VitaTrace Project Context

VitaTrace is a local-first Android health analytics app. It is not a Mi Fitness clone and not a Health Connect status panel. Its job is to turn watch exports, live phone data, future manual entries, and reviewed documents into reliable personal trends.

## Product Goal

Answer questions like:

- Is fitness improving, stable, or regressing?
- Is heart rate lower for similar walking/running effort over time?
- Does sleep affect next-day activity?
- What changed over the last 7, 30, and 90 days?
- How many steps and estimated kilometers did I do by year, month, week, and day?
- Does activity, sleep, heart rate, and workout effort reveal personal patterns?
- Which signals are strong enough to trust, and which must stay hidden until there is enough data?

The app must not diagnose disease. It can say that a trend is unusual and worth checking, but it must not claim medical certainty.

The long-term product direction is Personal Body Intelligence: help the user understand their own body from historical personal data, not generic averages.

## MVP Scope

Build first:

- Android app in Kotlin + Jetpack Compose.
- Room/SQLite local database.
- Mi Fitness CSV import.
- Raw event storage.
- Normalized metric records.
- Daily aggregates.
- Detailed analytical tables for sleep and workouts.
- Deterministic insight engine.
- Offline human report from the real database.
- Privacy-safe AI Context Bundle.
- Answer-first product screen only after the engine produces useful findings.

Defer:

- Full Health Connect sync until real records are available.
- DeepSeek/AI report generation.
- OCR for scale photos or lab scans.
- Body-composition analysis until real body-composition history exists.
- Backend, accounts, payments, social features.
- Direct connection to Xiaomi watch or Xiaomi cloud.

## Data Architecture

Data flow:

```text
Mi Fitness CSV export
        |
        v
CSV adapters -> raw events -> detailed tables -> daily aggregates
                                  |                 |
Health Connect sync later --------+-----------------+
                                  |
                                  v
                         deterministic insight engine
                                  |
                                  v
                           AI Context Bundle
                                  |
                                  v
                         answer-first reports/UI
```

The database is the source of truth. Raw payloads stay local and allow importer fixes later.
The product direction for this analysis layer lives in `docs/analytics-product-plan.md`.

## Planned Data Sources

- `MI_FITNESS_EXPORT`: historical CSV export.
- `HEALTH_CONNECT`: future/new data from Android.
- `MANUAL_ENTRY`: future manual weight/lab/body entries.
- `DOCUMENT_IMPORT`: future photo/scan upload with review before accepting extracted values.
- `FUTURE_XIAOMI_MEDICAL_EXPORT`: placeholder for Xiaomi medical export if available later.

## Planned Import Adapters

- `FitnessDataCsvAdapter`: `hlth_center_fitness_data.csv`
- `AggregatedFitnessCsvAdapter`: `hlth_center_aggregated_fitness_data.csv`
- `SportRecordCsvAdapter`: `hlth_center_sport_record.csv`
- `SportTrackGpxAdapter`: `hlth_center_sport_track_data.csv`
- `ProfileCsvAdapter`: user profile and fitness goals
- `MedicalDataCsvAdapter`: future `hlth_center_medical_data.csv`

## Core Metrics

Activity:

- steps
- distance
- estimated distance from steps using configurable `stepsPerKm`, default `1250`
- active calories from movement or workouts
- active days
- training sessions
- yearly/monthly/weekly/daily step totals
- best year/month/week and trend versus previous period

Visible product surfaces must read these from VitaTrace local analytical tables and summaries, not directly from Health Connect. Health Connect and historical imports are input sources; the local normalized database is the source of truth.

Cardio:

- heart rate samples
- resting heart rate
- min/max heart rate
- VO2 max
- cardio efficiency for similar walking/running sessions
- walking/running heart rate comparison at similar effort

Recovery:

- sleep duration
- sleep stages where available
- sleep score where available
- stress
- readiness/recovery trend

Future body composition:

- weight
- BMI
- body fat percentage
- muscle mass
- body water
- bone mass
- visceral fat
- basal metabolic rate if available
- relationship to sleep, heart rate, training tolerance, and cardio efficiency

This is future scope only. Do not build visible modules, claims, or AI conclusions around body composition until real records exist.

Future labs/documents:

- store source document metadata
- extract only after review
- keep confidence and units
- never treat OCR as automatically trusted

## AI Rules

DeepSeek or any AI provider may receive only an `AI Context Bundle` made from:

- user-approved profile basics
- aggregates
- trend deltas
- data quality flags
- deterministic anomaly labels
- deterministic correlation summaries with sample size, date range, and confidence

AI must not receive:

- raw CSV rows
- raw JSON payloads
- GPX tracks
- photos or scans
- API keys
- unreviewed OCR text

AI should explain deterministic findings in plain language. It must not calculate directly from raw records.

## Acceptance Standard

Each meaningful feature should include:

- a deterministic calculation path
- source provenance
- deduplication behavior
- data quality behavior
- tests or documented reason for no tests yet
- update to `docs/agent-memory.md`

Historical data import rules live in `docs/historical-data-import.md`. Raw Mi Fitness exports stay private and must not be committed.
The first deterministic analysis rules live in `app/src/main/java/com/vitrace/app/analysis/` and should stay separate from Compose UI.
The Personal Body Intelligence prompt lives in `docs/personal-body-intelligence-prompt.md`.
