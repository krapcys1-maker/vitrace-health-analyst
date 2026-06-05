# VitaTrace Project Context

VitaTrace is a local-first Android health analytics app. It is not a Mi Fitness clone. Its job is to turn watch, Health Connect, body composition, and future document data into reliable personal trends.

## Product Goal

Answer questions like:

- Is fitness improving, stable, or regressing?
- Is heart rate lower for similar walking/running effort over time?
- Does sleep affect next-day activity?
- Is Health Connect data complete?
- What changed over the last 7, 30, and 90 days?

The app must not diagnose disease. It can say that a trend is unusual and worth checking, but it must not claim medical certainty.

## MVP Scope

Build first:

- Android app in Kotlin + Jetpack Compose.
- Room/SQLite local database.
- Mi Fitness CSV import.
- Raw event storage.
- Normalized metric records.
- Daily aggregates.
- Basic dashboard.
- Data Quality screen.
- Health Connect probe/diagnostics.

Defer:

- Full Health Connect sync until real records are available.
- DeepSeek/AI report generation.
- OCR for scale photos or lab scans.
- Backend, accounts, payments, social features.
- Direct connection to Xiaomi watch or Xiaomi cloud.

## Data Architecture

Data flow:

```text
Mi Fitness CSV export
        |
        v
CSV adapters -> raw events -> normalized metrics -> daily aggregates
                                                     |
Health Connect probe/sync later --------------------+
                                                     |
                                                     v
                                            analytics engine
                                                     |
                                                     v
                                       dashboard and reports
```

The database is the source of truth. Raw payloads stay local and allow importer fixes later.

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
- active calories from movement or workouts
- active days
- training sessions

Cardio:

- heart rate samples
- resting heart rate
- min/max heart rate
- VO2 max
- cardio efficiency for similar walking/running sessions

Recovery:

- sleep duration
- sleep stages where available
- sleep score where available
- stress
- readiness/recovery trend

Body composition:

- weight
- BMI
- body fat percentage
- muscle mass
- body water
- bone mass
- visceral fat
- basal metabolic rate if available

Future labs/documents:

- store source document metadata
- extract only after review
- keep confidence and units
- never treat OCR as automatically trusted

## AI Rules

DeepSeek or any AI provider may receive only an `AiHealthSummary` made from:

- user-approved profile basics
- aggregates
- trend deltas
- data quality flags
- deterministic anomaly labels

AI must not receive:

- raw CSV rows
- raw JSON payloads
- GPX tracks
- photos or scans
- API keys
- unreviewed OCR text

## Acceptance Standard

Each meaningful feature should include:

- a deterministic calculation path
- source provenance
- deduplication behavior
- data quality behavior
- tests or documented reason for no tests yet
- update to `docs/agent-memory.md`
