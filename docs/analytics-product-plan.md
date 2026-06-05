# VitaTrace Analytics Product Plan

Date: 2026-06-05

This is the current product plan. It replaces older dashboard/tab plans.

## Product Definition

VitaTrace is a personal body intelligence app.

It is not:

- a Mi Fitness clone
- a Health Connect dashboard
- a sync/status/debug panel
- a generic chart collection
- a medical diagnosis tool

It should answer:

- what changed in the user's body/activity patterns
- what is improving
- what looks worse or suspicious
- what is not supported by data
- what should be tested next

## Non-Negotiable UI Rule

The main app must not show technical plumbing:

- no sync/API/provider wording
- no SDK/permission/quota language
- no raw record-count panels
- no "records found" style copy
- no source names as product content

Technical status may exist only in a later hidden Options/Debug area.

## Current Data Value

Strong signals:

- daily steps and long-term activity history
- estimated kilometers using `stepsPerKm = 1250`
- walking workouts, after filtering impossible duration/pace/HR records
- yearly and monthly activity changes

Medium signals:

- sleep duration, sleep score, REM/deep/light/awake as wearable trends
- high daily average HR context paired with sleep and activity
- VO2 inside comparable walking/running sessions
- active calories as support only

Weak or blocked signals:

- running trend, because there are too few sessions
- body composition, because there is no real history
- lab scans, because OCR/review/import is not built
- SpO2 conclusions, because coverage is too thin
- metabolism claims from calories
- medical diagnosis

## Product Modules

### 1. Activity Over Time

Question:

> Czy moj poziom ruchu rosnie, spada czy jest nierowny?

Engine outputs:

- yearly steps
- yearly estimated km
- source km when present
- monthly steps and estimated km
- best/worst months
- peak/slump versus previous 6 months
- current-year projection from closed days

Current finding:

- 2025 was +14.0% steps versus 2024.
- 2026 is uneven, not clearly worse.
- April 2026 is a major peak month.

UI should show:

- one plain-language finding
- a yearly/monthly trend view
- peak/slump labels
- details only on demand

### 2. Walking Fitness

Question:

> Czy chodze szybciej, na nizszym tetnie, albo taniej energetycznie przy podobnym dystansie?

Engine outputs:

- credible walking-session filters
- comparable distance bands: 3-6 km, 6-10 km, 10-15 km, 15+ km
- pace
- average HR
- kcal/km
- VO2 when present
- intensity bucket from average session HR

Current finding:

- Filtered 3-15 km walking pace improved from 14:43/km in 2024 to 13:00/km in 2025 and 12:38/km in 2026.
- kcal/km dropped from 68.3 to 59.9 by 2026.
- This is currently the best fitness signal in the dataset.

UI should show:

- "wydolnosc chodzenia" as a clear human module
- comparable sessions only
- confidence and limitations
- no random workout totals as the main point

### 3. Sleep And Recovery

Question:

> Czy sen jest ponizej mojej normy i co moze go ruszac?

Engine outputs:

- personal sleep baseline
- last 7/14/30 measured-night windows
- total sleep first
- REM/deep/light/awake as wearable estimates
- same-day activity vs sleep
- previous-day activity vs sleep
- sleep vs next-day activity/heart

Current finding:

- Last 30 measured nights are about 7.26 h, around +7 min versus baseline.
- More steps do not clearly improve same-night sleep in current data.
- Previous-day high steps may be linked with lower score/REM, but this is only a hypothesis.

UI should show:

- whether sleep is below/near/above personal baseline
- activity-sleep relationship only if sample/confidence is enough
- sleep stages clearly marked as estimates

### 4. Heart Load

Question:

> Czy dni z wysokim tetnem wygladaja jak obciazenie albo slaba regeneracja?

Engine outputs:

- high average-HR days versus baseline
- paired sleep, steps, and workout context
- exercise HR in comparable workout contexts

Current finding:

- High average-HR days show shorter sleep in available paired data.
- This is not resting HR and not a diagnosis.

UI should show:

- load/recovery context
- clear limitation that daily average HR is not resting HR
- no medical claims

## AI Placement

AI is not the calculator.

Deterministic engine calculates:

- totals
- trends
- filters
- baselines
- correlations
- confidence
- limitations

AI receives only an `AI Context Bundle` from:

```powershell
python tools\build_ai_context_bundle.py --db build\phone-db-check\phone-current-vitrace.db --output build\ai-context-bundle.json --prompt-output build\ai-context-prompt.md
```

AI may:

- choose the 3-5 most important findings
- explain them in plain Polish
- say what is weak or not proven
- suggest next tests
- write app-ready text

AI must not:

- calculate from raw CSV/JSON/GPX
- receive routes, photos, scans, secrets, or raw payloads
- invent unavailable data
- diagnose
- show technical source/provider details to the user

AI output must be stored separately from deterministic `analysis_results`.

## Implementation Priority

### Milestone 1: Engine Truth

- keep `tools/deep_health_report.py` as the offline truth-checker
- keep `tools/build_ai_context_bundle.py` as the AI boundary
- add tests for peak/slump detector
- add tests for credible walking-session filters
- add tests for AI bundle privacy

### Milestone 2: Better Metrics

- activity peak/slump detector
- walking efficiency by distance band and month
- sleep after long walks versus normal days
- high-HR day context with sample-count filtering
- HR intensity buckets only where HR is credible

### Milestone 3: Human Product Copy

- rewrite app screen around the four modules
- remove any remaining developer/debug copy from main UI
- show one answer first, details second
- use charts only when they answer a specific question

### Milestone 4: AI Explanation

- create AI output table
- add bundle hash
- add DeepSeek adapter
- add explicit user action for generating analysis
- show AI report only after deterministic results are ready

### Milestone 5: Future Data

Only after real data exists:

- body composition import
- lab scan OCR/review/import
- notes-to-health-context analysis
- running trend after enough sessions
- resting/night HR or HRV if available

## Verification Rules

Before calling a product change done:

```powershell
python tools\deep_health_report.py --db build\phone-db-check\phone-current-vitrace.db --output build\body-intelligence-report.md
python tools\build_ai_context_bundle.py --db build\phone-db-check\phone-current-vitrace.db --output build\ai-context-bundle.json --prompt-output build\ai-context-prompt.md
python -m unittest discover -s tests
.\gradlew.bat :app:assembleDebug
```

Generated files in `build/` are private artifacts and must not be committed.
