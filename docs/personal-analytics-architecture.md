# VitaTrace Personal Analytics Architecture

This document describes what the app should calculate, what AI should explain, and how the screens should present it.

## Product Shape

VitaTrace should be a personal organism analysis app:

- deterministic engine calculates trends, baselines, comparisons, correlations, and confidence
- AI explains those results in plain language for the user's profile
- UI presents answer-first cards with drill-down charts and source coverage

The user should not have to read raw records to understand the point.

## Layering

```text
Detailed local data
  sleep_details
  workout_sessions
  daily_activity_summaries
  daily_heart_summaries
  future body_composition_records
  health_notes
        |
        v
Feature builders
  SleepFeatureBuilder
  SportFeatureBuilder
  BodyCompositionFeatureBuilder
  SourceCoverageBuilder
        |
        v
Deterministic analytics engine
  monthly averages
  rolling 7/30/90-day trends
  same-effort comparisons
  correlation candidates
  confidence and sample-size rules
        |
        v
AiHealthSummary
        |
        v
AI explanation layer
        |
        v
UI: Analiza, Sen, Sport, Waga, Zdrowie
```

AI does not calculate from raw rows. AI receives deterministic findings with sample size, date range, confidence, and missing-data flags.

## Sleep Analytics

The `Sen` tab should have focused sections:

- `Miesiace`
- `Fazy`
- `Zaleznosci`
- `AI`

Monthly sleep view should show, per month:

- average total sleep hours
- average REM minutes and percent
- average deep sleep minutes and percent
- average light sleep minutes and percent
- average awake minutes
- average sleep score
- sleep regularity: bedtime/wake-time variability
- number of nights used in the calculation
- data source coverage

Sleep analysis should calculate:

- current month versus previous month
- last 30 days versus prior 30 days
- personal baseline versus current period
- activity day versus low-activity day sleep differences
- training day versus non-training day sleep differences
- evening training versus non-evening training when timestamps allow it
- next-day effect: short/poor sleep versus next-day steps, training, and heart rate

Example deterministic finding shape:

```text
On days above 10,000 steps:
- total sleep: +24 min versus lower-activity days
- REM: +5%
- deep sleep: +10%
- light sleep: -4%
- sample: 38 nights
- confidence: medium
```

The engine must decide whether a pattern is:

- not enough data
- one-off / weak
- repeated but low confidence
- repeated and likely meaningful

Norm references may be used only as context, not as diagnosis. For the user profile, for example male, 40 years, 186 cm, 88 kg, AI may say that deep sleep appears low compared with common adult ranges, but it must also compare against the user's own baseline first.

## Sport Analytics

The `Sport` tab must keep these separate:

- `Kroki`: daily steps and estimated kilometers
- `Chodzenie`: walking workouts only
- `Bieganie`: running workouts only
- `Wydolnosc`: cross-workout analysis
- `AI`

Steps analytics:

- daily, weekly, monthly, yearly steps
- estimated kilometers using `stepsPerKm`
- best month/year/week
- trend month-to-month and year-to-year
- active days versus inactive days

Walking/running workout analytics:

- sessions per month
- total distance per month
- total duration per month
- active calories and total calories per month
- average pace
- average and max heart rate
- cadence
- VO2 max when present
- calories burned per kilometer
- calories burned per minute
- same-effort heart-rate trend

Cardio efficiency should compare similar sessions, not random totals. Candidate grouping:

- same workout type
- similar distance band, for example 3-5 km
- similar duration band
- similar pace band
- enough sessions in both compared periods

Example deterministic finding shape:

```text
Running, 3-5 km sessions:
- this week: avg pace 7:36/km, avg HR 154 bpm
- previous comparable sessions: avg pace 7:48/km, avg HR 159 bpm
- interpretation candidate: faster pace with lower HR
- sample: 4 recent sessions versus 8 baseline sessions
- confidence: low/medium depending on sample
```

Calorie efficiency should track:

- kcal per km
- kcal per minute
- trend over months
- relation to pace, heart rate, and later weight/body composition

The engine must avoid claiming "you burn faster" unless it controls for workout type, distance, duration, and intensity well enough.

## Body Composition And Weight Analytics

The `Waga` tab must stay honest:

- current data has simple mass records only
- future body composition may include fat, muscle, water, bone mass, visceral fat, BMR, protein, metabolic age

When body composition exists, calculate:

- weight trend
- body fat trend
- muscle trend
- water trend
- relation to sleep stages and sleep score
- relation to resting heart rate
- relation to walking/running heart rate at similar effort
- relation to kcal/km and kcal/min

Example future finding:

```text
When body fat percentage is higher than your 90-day average:
- sleep score is lower by X
- deep sleep is lower by Y%
- running HR at similar pace is higher by Z bpm
- sample: N days
- confidence: low/medium/high
```

Smart-scale composition values must be treated as noisy trend data, not exact medical truth.

## AI Placement

AI has two surfaces:

- `Analiza`: whole-body report and cross-domain explanation
- `Zdrowie`: notes, lab result explanation, and medical-document context after review

Domain tabs can have an `AI` section, but it should be scoped:

- `Sen AI`: explain sleep trends and sleep-related correlations
- `Sport AI`: explain performance, cardio efficiency, calorie efficiency, and recovery
- `Waga AI`: explain weight/body composition trends after data exists

`Opcje` holds AI configuration:

- provider status
- DeepSeek API key status
- consent
- what data categories AI may use
- last generated report timestamp

## AI Input Contract

`AiHealthSummary` should contain:

- profile: sex, age, height, weight, stepsPerKm
- source coverage: history versus live Health Connect
- monthly sleep phase averages
- monthly sport totals and workout metrics
- deterministic correlation findings
- confidence and sample size for every finding
- limitations and missing data
- selected notes/labs only with consent

It must not contain:

- raw CSV rows
- raw JSON payloads
- raw GPX URLs or points
- unreviewed OCR
- API keys

## UI Design Principles

Each analytical screen should follow this hierarchy:

1. Top answer card: one clear conclusion.
2. Trend chart: month/week/day selector.
3. Metric breakdown: compact rows or small charts.
4. Evidence: sample size, date range, confidence.
5. Drill-down list: days or sessions used.

Avoid one huge scroll of unrelated cards. Use tabs/segmented controls inside a domain.

Use visual language:

- line chart for trends over time
- stacked bar for sleep phases
- paired comparison cards for this period versus baseline
- scatter plot for correlations, for example steps versus deep sleep
- session list for running/walking workouts
- confidence badge: weak / medium / strong
- source badge: Mi Fitness history / Health Connect live / manual

Copy should be answer-first:

- good: "More activity is linked with better deep sleep in your data"
- bad: "Steps record found"

Every insight should show why it exists:

- sample size
- compared periods
- confidence
- missing data

## Best Next Implementation Step

Build a deterministic `AnalysisContextBuilder` with no AI call yet.

First outputs:

- monthly sleep phase averages from `sleep_details`
- monthly running/walking summaries from `workout_sessions`
- monthly steps and estimated kilometers from `daily_activity_summaries`
- first sleep/activity comparison:
  - high-activity days versus low-activity days
  - total sleep, REM, deep, light, score differences
  - sample size and confidence
- first sport trend:
  - monthly km
  - pace and heart-rate trend for running/walking
  - kcal/km and kcal/min trend

Only after this exists should the DeepSeek prompt receive an `AiHealthSummary`.
