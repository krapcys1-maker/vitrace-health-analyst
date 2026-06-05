# VitaTrace Insight Contract

Date: 2026-06-05

This file is the working contract for the current product direction. It takes priority over older domain-tab plans when there is a conflict.

## Product Shape Now

VitaTrace is an answer-first personal health data analyst.

The first screen is an insight feed, not a copy of Mi Fitness and not a generic dashboard. Each visible card must answer one useful question and show the evidence behind it.

## Required Insight Shape

Every visible insight must contain:

- `id`: stable machine-readable scope
- `domain`: short user-facing area, for example `Sen`, `Trening`, `Dane`
- `title`: one question
- `answer`: direct conclusion in plain language
- `evidence`: concrete metric rows used for the conclusion
- `dateRange`: date window actually used
- `sampleSize`: number of days, nights, or sessions behind the result
- `confidence`: `High`, `Medium`, `Low`, or `Insufficient`
- `limitations`: what this does not prove
- `nextStep`: what would make the test stronger
- source coverage and time context in `analysis_results`

No card is allowed to show only raw record counts as the main point.

## Strong Signals In The Current Data

Use these as primary analytical surfaces:

- Long-term daily steps.
- Estimated kilometers from steps using the user profile step ratio.
- Yearly, monthly, weekly, and daily activity totals.
- Sleep details from history: total, REM, deep, light, awake, score, bed/wake time.
- Walking workouts, especially comparable distance bands.
- Workout session fields when present: distance, duration, pace, heart rate, cadence, active kcal, VO2.

## Medium Signals

Use these cautiously:

- Sleep stage trends. Treat stages as wearable estimates, not exact truth.
- Heart rate trends where coverage is enough.
- VO2 inside comparable walking/running contexts.
- Active calories as a support metric, never the main conclusion.

## Weak Or Blocked Signals

Do not build visible modules around these yet:

- Body composition and smart-scale analysis, because there is no real current signal.
- Lab scan analysis, because OCR/import/review is not implemented.
- Running trend as a strong claim, because the current sample is too small.
- SpO2 conclusions, because coverage is too small.
- Medical diagnosis.

## Time Rules

The engine must distinguish:

- historical closed days
- current partial day
- recent possibly incomplete days
- baseline period
- current comparison period

Default rule: today's partial data is visible as live context but must not be used as a closed-day trend input.

## Analysis Rules

The deterministic engine calculates first. AI explains later.

Allowed deterministic tests now:

- data coverage reality
- yearly/monthly steps and estimated kilometers
- same-night steps/activity versus sleep
- poor sleep versus next-day activity and heart
- training day versus non-training day sleep
- walking efficiency by distance band
- personal baseline and outlier days when coverage is enough

Forbidden conclusions from current data:

- "More steps clearly improve sleep."
- "Calories prove metabolism improved."
- "Running fitness trend is reliable."
- "Weight/body composition explains X."
- Any diagnosis or medical certainty.

## UI Rules

Each card should show:

1. Question/title.
2. Answer.
3. Confidence.
4. Sample and date range.
5. One or two evidence lines.
6. Expandable details with all evidence, limitations, and next test.

Charts are allowed only when they help answer a question:

- trend line for long-term steps/km
- stacked sleep phase chart
- paired baseline/current comparison
- scatter plot only for real correlation exploration
- session list only as drill-down evidence

No decorative chart without a sentence explaining what to look at.

## AI Rules

AI may receive only aggregated deterministic insight objects, not raw private files.

AI may explain:

- which finding matters most
- what is weak or not proven
- what may be worth testing next
- how the result relates to the user's profile

AI must not:

- calculate from raw CSV/JSON/GPX
- receive API keys
- receive raw route data
- receive unreviewed scans or OCR
- invent unavailable data

## Offline Verification

When phone access is not available, use the latest pulled database copy and run:

```powershell
python -m py_compile tools\audit_insights.py
python tools\audit_insights.py --db build\phone-db-check\vitrace-after-am-start.db
python tools\offline_signal_report.py --db build\phone-db-check\vitrace-after-am-start.db --output build\offline-signal-report.md
.\gradlew.bat :app:assembleDebug
```

When changing audit behavior, also run:

```powershell
python -m unittest discover -s tests
```

Do not commit generated reports from `build/`; they may summarize private health data.
