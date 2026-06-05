# VitaTrace Insight Research Reset

Date: 2026-06-05

This is the hard reset after the UI started drifting into empty tabs and weak charts. VitaTrace must not be a copy of Mi Fitness and must not pretend that unavailable data exists.

## What The Data Actually Contains

Checked local phone database snapshot: `build/phone-db-check/vitrace.db`.

Strong signals:

- Daily activity: 1,073 days from 2021-11-09 to 2026-06-05.
- Steps and distance are the strongest long-term value.
- Year totals:
  - 2021: 173,975 steps, 97.3 km.
  - 2022: 455,416 steps, 289.8 km.
  - 2023: 1,150,305 steps, 755.5 km.
  - 2024: 2,799,620 steps, 1,711.4 km.
  - 2025: 3,191,192 steps, 1,891.0 km.
  - 2026 so far: 1,366,486 steps, 808.1 km.
- Best month by verified Mi Fitness daily reports: 2026-04, 559,104 steps, 330.5 km.
- 2024-09 is verified as 325,587 steps, not the old raw double-counted 611k.

Useful but uneven signals:

- Sleep details: 133 nights.
- Sleep has total sleep, REM, deep, light, awake, sleep score, bed/wake timestamps, raw payload.
- Average over available nights:
  - total sleep: 7.15 h
  - REM: 74.7 min
  - deep: 58.0 min
  - light: 280.0 min
  - score: 72.1
- Walking workouts: 273 sessions, 1,952.9 km, 270 GPX refs, 268 VO2 values.
- Running workouts: 7 sessions, useful as session log only, not trend.
- Daily heart summaries: 199 measured days, patchy historical coverage; one rich live day on 2026-06-05.
- VO2 appears mainly through workouts and can be used cautiously with walking/running context.

Weak or not product-ready signals:

- SpO2: 5 days only.
- External scale/body-composition module: not available as a real analytical signal now. Do not build a UI area around it yet.
- Calories: useful as a rough support metric only. Wearable energy expenditure has known accuracy problems, so do not make it the main conclusion.

## Manual Checks Already Run

Shared sleep/activity days: 133.

Simple same-day steps versus sleep correlations:

- steps vs total sleep: r = 0.002
- steps vs REM: r = -0.085
- steps vs deep: r = -0.108
- steps vs light: r = 0.041
- steps vs sleep score: r = -0.147
- steps vs daily average heart rate: r = 0.350

Sleep versus next-day steps:

- total sleep vs next-day steps: r = -0.136, n = 131
- REM vs next-day steps: r = -0.135, n = 131
- deep sleep vs next-day steps: r = 0.050, n = 131
- sleep score vs next-day steps: r = -0.049, n = 131

Step buckets:

- `<8k`: 41 days, 7.12 h sleep, 77.6 REM, 58.7 deep, score 72.0
- `8-12k`: 34 days, 7.33 h sleep, 80.8 REM, 55.5 deep, score 73.3
- `12-18k`: 20 days, 6.87 h sleep, 61.8 REM, 64.5 deep, score 72.2
- `18k+`: 38 days, 7.16 h sleep, 72.9 REM, 55.9 deep, score 71.3

Interpretation:

- Do not claim that more daily steps clearly improve same-night sleep.
- The useful question is more specific: timing, training day versus non-training day, high load versus low load, and next-day behavior after poor sleep.

Training day versus non-training day sleep:

- Training day nights: 50, 7.30 h sleep, 73.8 REM, 54.2 deep, score 71.3.
- Non-training nights: 83, 7.05 h sleep, 75.2 REM, 60.3 deep, score 72.6.

Interpretation:

- There is no obvious simple win for training days. Treat as a hypothesis with low confidence until controlled by type, timing, and load.

## Research Direction From Literature

What the papers support:

- Exercise can improve sleep, but effects are often small/moderate and moderated by age, sex, baseline activity, exercise type, time of day, duration, and adherence. This means the app should not expect a simple one-line step/sleep relationship.
- Consumer wearables are variable by device, context, and metric. Steps and heart rate are usually more usable than energy expenditure; sleep time is often better than exact sleep stages.
- Resting heart rate should be interpreted against the person's own baseline, not only generic population ranges.
- Wearable sleep staging is inferred from movement and heart/PPG signals; stages are useful as trends, not exact truth.
- Sleep and physical activity are bidirectional. It may be more useful to ask whether poor sleep changes next-day movement than only whether more activity improves same-night sleep.
- HRV would be very useful for recovery if available, but it is not currently in our verified dataset.

Sources checked:

- Kredlow et al., "The effects of physical activity on sleep: a meta-analytic review", PubMed: https://pubmed.ncbi.nlm.nih.gov/25596964/
- "Keeping Pace with Wearables", umbrella review, PMC: https://pmc.ncbi.nlm.nih.gov/articles/PMC11560992/
- "Inter- and intraindividual variability in daily resting heart rate...", PMC: https://pmc.ncbi.nlm.nih.gov/articles/PMC7001906/
- "Sleep duration and timing are associated with next-day physical activity", PubMed: https://pubmed.ncbi.nlm.nih.gov/40587790/
- "Bidirectional associations between sleep and physical activity...", PMC: https://pmc.ncbi.nlm.nih.gov/articles/PMC12686483/
- "Sleep stage prediction with raw acceleration and PPG heart rate data...", PMC: https://pmc.ncbi.nlm.nih.gov/articles/PMC6930135/
- "Can Wearable Devices Accurately Measure Heart Rate Variability?", PubMed: https://pubmed.ncbi.nlm.nih.gov/29668452/
- "Reliability and Validity of Commercially Available Wearable Devices...", PMC: https://pmc.ncbi.nlm.nih.gov/articles/PMC7509623/

## Product Decision

Remove tabs as the main product structure.

The first real app surface should be an insight feed, not the old domain menu.

Every visible conclusion must have:

- question
- answer
- evidence
- date range
- sample size
- confidence
- limitations
- source coverage
- next check to improve confidence

## What To Build First

### 1. Data Reality Feed

Purpose: tell the user what the app can and cannot currently know.

Cards:

- Strongest signal now: long-term steps and kilometers.
- Useful signal now: sleep detail and walking workouts.
- Weak signal now: running trend, SpO2, calories, live Health Connect freshness.
- Not an active module now: external scale/body-composition data, because we do not have a real analytical signal for it.

Visual form:

- one vertical feed
- no domain tabs
- each card has one answer, not a pile of raw metrics
- evidence rows under the answer
- confidence badge: high / medium / low / blocked

### 2. Personal Baselines

Purpose: learn the user's normal.

Baselines worth building:

- yearly/monthly steps and km
- sleep monthly baseline: total, REM, deep, light, score, regularity
- walking baseline by distance band:
  - 3-6 km
  - 6-10 km
  - 10-15 km
  - 15+ km
- walking efficiency:
  - heart rate at comparable distance
  - pace at comparable distance
  - kcal/km as support only
  - VO2 trend only when sessions are comparable
- heart baseline only for months with enough measured days

Visual form:

- baseline cards with compact sparkline and date range
- no chart without an answer above it
- chart should compare two windows or show baseline band, not just decorative lines

### 3. Tested Hypotheses

First hypotheses:

1. `activity_sleep_same_night`
   - Current expected answer: no clear simple relationship.
   - Evidence: correlations and step buckets.

2. `sleep_next_day_activity`
   - Test whether short/late/poor sleep changes next-day steps, workout likelihood, and average heart.

3. `training_day_sleep`
   - Compare workout days to non-workout days.
   - Later control by workout type, timing, duration, and intensity.

4. `walking_efficiency_by_distance_band`
   - Compare recent walking sessions against historical baseline inside the same distance band.
   - Do not use raw month-to-month totals as the primary evidence.

5. `heart_outlier_days`
   - Find days where average/resting heart is above personal baseline and check sleep/activity context.
   - Only run where heart coverage is good enough.

6. `vo2_workout_context`
   - Use VO2 only with workout type, distance, pace, and HR context.
   - Never display VO2 as a standalone magic score.

## What Not To Build Now

- No weight/body-composition screen.
- No tabbed UI.
- No generic dashboard with every metric.
- No calorie-first conclusions.
- No "more steps improved sleep" claim from current data.
- No running trend from 7 total sessions.
- No medical/lab analysis until scan/manual import exists.
- No AI answer before deterministic engine computes evidence.

## AI Placement

AI should not read UI cards.

AI should receive compact, deterministic `TestedHypothesis` objects:

- title
- plain-language answer
- evidence rows
- date range
- sample size
- confidence
- limitations
- source coverage
- raw-data links or IDs for audit

AI output should be:

- explain the result in normal language
- rank which finding matters most
- say what is not proven
- suggest what data would improve confidence

## Next Implementation Step

Build `InsightEngine` and make the app home screen render only its output.

Minimum first output:

- data coverage summary
- `activity_sleep_same_night`
- `sleep_next_day_activity`
- `training_day_sleep`
- `walking_efficiency_by_distance_band`

Only after these are real should any deeper screens return.
