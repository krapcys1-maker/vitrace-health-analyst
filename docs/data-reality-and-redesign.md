# VitaTrace Data Reality And Redesign

This is the reset document after manually reviewing the imported database and checking how stronger wearable apps frame useful health insights.

## Current Local Data Reality

Pulled phone database snapshot checked on 2026-06-05.

### Strong Data

- Daily activity: 1,073 rows from 2021-11-09 to 2026-06-05.
- Steps are the strongest long-term signal.
- Year totals already provide value that Mi Fitness does not present well:
  - 2024: 2,799,620 steps, 1,711.4 km, 335 active days.
  - 2025: 3,191,192 steps, 1,891.0 km, 350 active days.
  - 2026 so far: 1,366,486 steps, 808.1 km, 156 active days.
- Workout sessions: 303 rows from 2022-01-16 to 2026-06-05.
- Walking workouts are the richest training dataset:
  - 273 sessions.
  - 1,952.9 km.
  - 268 sessions with VO2 max.
  - 270 sessions with GPX references.

### Medium Data

- Sleep details: 133 nights from 2021-11-10 to 2026-06-05.
- Each sleep row has useful details: total sleep, REM, deep, light, score, bed/wake.
- This is enough for personal sleep baselines and tentative activity/sleep patterns, but not enough for overconfident claims.
- Daily heart summaries: 199 days from 2021-11-09 to 2026-06-05.
- Historical heart coverage is patchy by month.
- Live Health Connect on 2026-06-05 contains one very rich heart day with 1,459 samples.

### Weak Data

- Running: only 7 sessions total.
  - Useful as individual session history.
  - Not enough for monthly trend claims yet.
- Weight: only 3 records.
  - 2021-11-09: 81 kg.
  - 2024-08-18: 94 kg.
  - 2025-07-16: 85 kg.
  - Not enough for weight/sleep/heart correlations.
- SpO2: 5 days only.
- Body composition: not available yet.

## Quick Manual Signal Checks

These are exploratory only, not app conclusions yet.

Sleep plus activity sample:

- 133 days with both steps and sleep.
- Average steps: 13,778.
- Average sleep: 7.15 h.
- Average REM: 75 min.
- Average deep sleep: 58 min.
- Average sleep score: 72.1.

Simple step correlations were weak:

- Steps vs total sleep: r = 0.002.
- Steps vs REM: r = -0.085.
- Steps vs deep sleep: r = -0.108.
- Steps vs sleep score: r = -0.147.

Step buckets:

- `<8k`: 41 days, 7.12 h sleep, score 72.0.
- `8-12k`: 34 days, 7.33 h sleep, score 73.3.
- `12-18k`: 20 days, 6.87 h sleep, score 72.2.
- `18k+`: 38 days, 7.16 h sleep, score 71.3.

Interpretation: the current data does not support a simple claim that more steps clearly improve sleep. The better app behavior is to say: "No strong simple step/sleep relationship yet; we should test training load, timing, and next-day effects."

Walking monthly data is rich but mixed:

- 2026-05: 3 walking sessions, 40.4 km, avg HR 105.3, pace 13.98 min/km, VO2 42.7.
- 2026-04: 14 walking sessions, 310.1 km, avg HR 112.6, pace 16.96 min/km, VO2 44.9.

Interpretation: lower HR and faster pace in May cannot be called better fitness by itself because volume collapsed. Month-to-month aggregate charts are too easy to misread.

## What Good Wearable Apps Teach Us

The useful pattern is not "show every metric." It is:

- establish personal baselines
- compare recent data against that personal baseline
- show source coverage and confidence
- separate readiness/recovery from fitness progress
- avoid single-day overinterpretation

References:

- Apple Vitals focuses on overnight health metrics and typical ranges: heart rate, respiratory rate, wrist temperature, blood oxygen, sleep duration, and outliers from typical range.
- Apple also connects training load with how workout intensity may affect the body over time.
- Oura readiness is explicitly based on individual averages and uses factors like resting heart rate, HRV balance, sleep balance, regularity, and previous-day activity.
- Oura compares HRV over recent windows against a longer baseline and treats RHR shifts against personal average as possible stress/illness/overload context.
- Garmin Training Readiness combines sleep, recovery time, HRV status, acute load, and stress.
- Wearable research on resting heart rate emphasizes individual baselines: a person's normal can differ greatly from population norms, so personal trend matters more than generic ranges.

## Product Reset

VitaTrace should not be a prettier Mi Fitness clone.

It should be a Personal Body Intelligence app with three kinds of screens:

1. **What We Know**
   - Data coverage and trust.
   - Strong, medium, weak data domains.
   - What is not available yet.

2. **Personal Baselines**
   - Steps and kilometers by year/month.
   - Sleep baseline by month: duration, REM, deep, light, score.
   - Walking baseline: distance bands, pace, HR, kcal/km, VO2.
   - Heart baseline only where coverage is enough.

3. **Tested Hypotheses**
   - Each insight is a hypothesis with evidence, sample size, date range, and confidence.
   - Example: "More steps do not currently show a strong sleep improvement in your data."
   - Example: "Walking HR at comparable distance/pace is lower/higher than your baseline."
   - Example: "After short sleep, next-day activity or HR changes."

## Screens To Redesign

### Start / Analiza

First screen should not be raw metrics. It should show:

- "Najmocniejszy sygnal teraz"
- "Co wiemy dobrze"
- "Czego jeszcze nie wiemy"
- "Hipotezy do sprawdzenia"

### Sport

Split into:

- Steps and yearly/monthly kilometers.
- Walking baseline and comparable-session analysis.
- Running session log; no trend claims until enough sessions.
- VO2 max trend from workout/body data.

Do not use raw month-to-month HR/pace line as the primary claim.

Better walking analysis:

- group sessions by distance band, for example 3-6 km, 6-10 km, 10-15 km, 15+ km
- optionally control for pace band
- compare recent 30/60/90 days against historical baseline
- output:
  - recent average HR
  - baseline HR
  - pace difference
  - kcal/km difference
  - VO2 difference
  - confidence

### Sen

Show monthly baselines first:

- average sleep duration
- REM/deep/light minutes and percent
- score
- regularity when bed/wake timestamps are enough

Then show tested relationships:

- steps bucket versus sleep phases
- training day versus non-training day
- high walking load day versus next-night sleep
- short sleep versus next-day activity or heart

Current manual check says simple steps-to-sleep relationship is weak, so the app should not force a positive story.

### Waga

Keep honest:

- We only have 3 historical weight records.
- No body composition yet.
- Do not correlate weight with sleep/heart until more weight/body composition records exist.

### Zdrowie

This should become the place for:

- notes
- future labs/OCR
- "outlier days" where sleep, heart, activity, and notes move together

## Next Best Implementation Step

Stop expanding UI widgets.

Build a deterministic `InsightEngine` that outputs `TestedHypothesis` objects:

- title
- plain-language answer
- domain
- evidence rows
- date range
- sample size
- confidence
- limitations
- source coverage

First hypotheses to implement:

1. `steps_sleep_relationship`
   - Use the 133 shared sleep/activity days.
   - Compare step buckets and calculate simple correlation.
   - Current expected answer: weak/no clear simple relationship.

2. `walking_comparable_effort`
   - Use walking sessions only.
   - Group by distance bands.
   - Compare recent closed period against historical baseline.
   - Avoid month-to-month totals as primary evidence.

3. `data_coverage_reality`
   - Tell the user what is strong/medium/weak.
   - This should drive UI confidence.

Only after these objects exist should AI explain them.

## Testing Rule

Every insight must have tests for:

- partial current day/month exclusion
- minimum sample size
- confidence downgrade when coverage is poor
- no positive claim when correlation is weak
- no trend claim when data is not comparable

