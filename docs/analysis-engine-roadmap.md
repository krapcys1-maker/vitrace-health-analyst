# VitaTrace Analysis Engine Roadmap

Date: 2026-06-05

This is the current engine plan. Phone access is not required for these steps as long as we have a pulled SQLite DB in `build/phone-db-check/`.

## Engine Pipeline

```text
historical import + live cache
        |
        v
normalized daily tables + detailed sleep/workout tables
        |
        v
data quality filters
        |
        v
deterministic metric engines
        |
        v
tested insights
        |
        v
AI context bundle
        |
        v
AI explanation report
        |
        v
human app screens
```

## Non-Negotiable Product Rule

The user-facing app must not show technical plumbing:

- no sync status on the main screen
- no provider/API wording
- no record-count panels as the product
- no raw source names
- no "records found" style diagnostics

Technical state belongs only in a future hidden Options/Debug screen.

## Deterministic Engines To Build

### 1. Activity Over Time

Inputs:

- `daily_activity_summaries`
- `user_profile.stepsPerKm`

Outputs:

- yearly steps
- estimated km
- source km
- best/worst months
- peak/slump versus previous 6 months
- current year projection from closed days

AI role:

- explain whether the activity level is rising, falling, or uneven
- choose the clearest human finding
- avoid claiming health outcomes from steps alone

### 2. Walking Fitness

Inputs:

- `workout_sessions`
- only credible walking sessions after duration/pace/HR filters

Outputs:

- comparable distance bands: 3-6 km, 6-10 km, 10-15 km, 15+ km
- year-over-year trend inside each distance band, including sample size, confidence, pace delta, HR delta, and kcal/km delta
- pace
- average HR
- kcal/km
- VO2 when present
- intensity bucket from average session HR

AI role:

- explain whether comparable walks look easier, harder, faster, or more efficient
- make clear when route/weather/elevation are unknown
- propose next test: compare similar routes or similar distance bands

### 3. Sleep And Recovery

Inputs:

- `sleep_details`
- paired `daily_activity_summaries`
- paired workouts and daily heart when available

Outputs:

- personal baseline
- last 7/14/30 measured-night windows
- baseline comparison for each window with confidence, total/REM/deep/light/awake/score deltas, and interpretation
- total sleep first
- REM/deep/light as wearable estimates
- activity-to-sleep and sleep-to-next-day tests
- previous-day activity threshold test: low/typical/high personal step groups versus next measured sleep
- next-night sleep after long walks versus other measured nights

AI role:

- summarize whether sleep is below, near, or above baseline
- state if activity does or does not clearly relate to sleep
- keep wearable sleep stages visibly uncertain

### 4. Heart Load

Inputs:

- `daily_heart_summaries`
- workout HR fields
- sleep/activity context

Outputs:

- credible heart-day filter by sample count and sane average-HR range
- high average-HR days versus baseline
- context: sleep, steps, workout minutes
- exercise HR by comparable workout type/distance

AI role:

- explain load/recovery context
- never call daily average HR "resting HR"
- never diagnose

## AI Insertion Point

AI is inserted after deterministic insights are already computed and bundled:

```powershell
python tools\build_ai_context_bundle.py --db build\phone-db-check\phone-current-vitrace.db --output build\ai-context-bundle.json --prompt-output build\ai-context-prompt.md
```

The bundle includes:

- profile basics
- source-safe coverage numbers
- research rules
- deterministic `engineFacts`: activity peak/slump labels, yearly/monthly km, and filtered walking-fitness facts
- walking distance-band trends so AI compares similar walks instead of mixing short walks with long marches
- sleep baseline facts so AI sees recent 7/14/30 measured nights against the user's own baseline before explaining recovery
- activity-to-next-sleep threshold facts so AI can explain whether high activity days look helpful, harmful, mixed, or neutral for recovery
- deterministic insights
- confidence
- limitations
- next tests

The bundle excludes:

- raw CSV
- raw JSON payloads
- GPX routes
- photos/scans
- keys/secrets
- provider plumbing
- source diagnostics

## AI Output Should Be Stored Separately

Do not overwrite deterministic `analysis_results`.

Future AI table should store:

- `id`
- `bundleSchemaVersion`
- `bundleHash`
- `provider`
- `model`
- `generatedForDate`
- `language`
- `topFindingsJson`
- `mainScreenCopyJson`
- `fullReportMarkdown`
- `createdAtEpochMs`

This makes AI output reproducible and reviewable.

## Current Offline Commands

```powershell
python tools\deep_health_report.py --db build\phone-db-check\phone-current-vitrace.db --output build\body-intelligence-report.md
python tools\build_ai_context_bundle.py --db build\phone-db-check\phone-current-vitrace.db --output build\ai-context-bundle.json --prompt-output build\ai-context-prompt.md
python -m unittest discover -s tests
```

Generated files stay in `build/` and are not committed.
