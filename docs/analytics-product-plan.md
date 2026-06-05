# VitaTrace Analytics Product Plan

VitaTrace is not a Mi Fitness clone. Mi Fitness is useful as a data collector and device companion. VitaTrace should be the analysis layer above those data sources.

## What Mi Fitness Already Does

- Shows current health tiles.
- Shows workouts and route details.
- Shows device-specific metrics.
- Holds historical export data.
- Syncs some data into Health Connect.

VitaTrace can display similar raw metrics when needed, but raw metric display is not the main value.

## VitaTrace Differentiators

VitaTrace should answer questions Mi Fitness does not answer well:

- Is my activity trend improving, flat, or declining?
- Which data streams are reliable enough to analyze?
- Are sleep, activity, heart, and body metrics moving together?
- Did training load change before fatigue or poor sleep?
- Are values missing because I did not do something, or because the source did not sync?
- What changed compared with my own baseline, not a generic target?
- Which conclusions are deterministic, and which are uncertain because data quality is weak?
- How many steps and estimated kilometers did I do by year, month, week, and day?
- Does my activity, sleep, weight, body composition, and heart response move together in my own data?

## Main App Areas

Dashboard:

- Today overview.
- 7-day and 30-day summary.
- Data coverage status.
- Short, readable cards.

Analysis:

- Trend direction.
- Baseline comparison.
- Data sufficiency warnings.
- Correlation candidates, such as sleep versus activity.
- Actionable observations that are deterministic, not AI guesses.
- Long-term activity totals by year, month, week, and day.
- Personal Body Intelligence: relationships between activity, sleep, heart, training, weight, and body composition.

Data:

- Health Connect status.
- Local database sync state.
- Source coverage.
- Import status.
- Data quality diagnostics.

Profile:

- User profile basics.
- Consent and privacy state.
- Future AI settings.
- App version and export/import controls.

## Analysis Rules

- Do not analyze a metric until data coverage is known.
- Do not treat missing records as missing behavior.
- Keep local normalized daily tables as the source for dashboard and analysis.
- Health Connect and Mi Fitness export are input sources only.
- Prefer user baseline over generic health norms.
- Use deterministic calculations first. AI summaries come later and only from aggregates.
- Every correlation insight needs sample size, date range, confidence, and data reliability.
- Do not show correlation claims from tiny samples.
- Treat body composition from consumer scales as noisy trend data, not exact medical measurement.

## Long-Term Activity Analytics

VitaTrace must calculate step totals and estimated distance across long personal history:

- total steps per year, month, week, and day
- average daily steps per year and month
- best year, month, and week by steps
- trend versus previous year and previous month
- estimated kilometers for each period

Distance estimation must use a user setting:

- `stepsPerKm`, default `1250`
- `estimatedKm = totalSteps / stepsPerKm`

This setting is required because stride length changes by person and by activity type. The first version can use one global value; later versions can split walking and running if the data is reliable.

Examples the app should answer:

- 2024: X steps, Y estimated km
- 2025: X steps, Y estimated km
- January 2025: X steps, Y estimated km
- Best month ever: X steps, Y estimated km

## Personal Body Intelligence

This module is the main product direction: help the user understand their own body from long-term data.

Questions VitaTrace should answer over time:

- Is my fitness improving, stable, or declining?
- Does physical activity improve my sleep?
- Does more walking reduce resting heart rate over weeks or months?
- Does poor sleep increase next-day heart rate, stress, or lower activity?
- Do I recover better after active days or rest days?
- Does weight change affect heart rate during walking or running?
- Does body fat change correlate with cardio efficiency?
- Does muscle mass correlate with better activity tolerance?
- Do I perform worse when weight is higher?

The app should present these as personal patterns with confidence, not universal health claims.

## Sleep And Activity Correlations

When history exists, calculate relationships between:

- daily steps and sleep duration
- training days and sleep duration
- training intensity and sleep duration
- evening activity and sleep, if timestamps allow it
- sleep duration and next-day steps
- sleep duration and next-day resting heart rate
- sleep duration and next-day stress

Candidate metrics:

- Sleep Impact Score
- Activity-to-Sleep Correlation
- Sleep-to-Next-Day-Performance Correlation

Example insights:

- On days above 10,000 steps, sleep duration is on average X minutes different.
- After nights below 6 hours, next-day steps change by X%.
- Resting heart rate is usually X bpm different after short sleep.

Only show these when data coverage and sample size are sufficient.

Sleep screens should include monthly averages for total sleep, REM, deep sleep, light sleep, awake time, sleep score, bedtime/wake-time regularity, sample size, and source coverage. Sleep analysis should compare high-activity days versus lower-activity days and report percent/minute differences for REM, deep sleep, light sleep, total sleep, and sleep score with confidence.

## Body Composition Analytics

Future body inputs should support:

- weight
- BMI
- body fat percentage
- muscle mass
- body water percentage
- bone mass
- visceral fat
- basal metabolic rate
- protein percentage
- metabolic age, if available

Possible sources:

- CSV export
- Xiaomi/Mi Fitness export if available
- Health Connect if available
- manual entry
- future photo/scan import after user review

When body composition exists, analyze:

- weight versus resting heart rate
- weight versus heart rate during walking
- weight versus heart rate during running
- body fat percentage versus cardio efficiency
- muscle mass versus activity tolerance
- water percentage versus noisy body fat measurements
- weight trend versus sleep duration/quality
- fat loss trend versus activity volume

Use rolling averages and confidence scores. Never treat smart scale composition values as exact.

## Cardio Efficiency

Add a Cardio Efficiency Index.

For walking, compare heart rate at similar step rate, speed, distance, or duration when available.

For running, compare:

- pace/speed
- distance
- duration
- average heart rate
- max heart rate
- VO2 max if available

VitaTrace should detect:

- same pace with lower heart rate
- same pace with higher heart rate
- longer distance at similar heart rate
- possible fatigue when heart rate is higher at normal effort

VO2 max may appear in Mi Fitness history even if Health Connect does not currently expose it. Historical import must therefore preserve VO2 max when present in export files.

Sport screens should show trends by month/week for steps, estimated kilometers, walking workouts, running workouts, pace, heart rate, cadence, VO2 max, active calories, calories per kilometer, and calories per minute. AI may explain what a run or month likely means for the user's body profile only after the deterministic engine calculates the comparison, sample size, and confidence.

## Correlation Confidence Rules

- Less than 14 comparable days: do not show correlation insight.
- 14-30 comparable days: low confidence.
- 30-90 comparable days: medium confidence.
- 90+ comparable days: higher confidence.
- Missing sleep, heart, workout, or body composition data lowers confidence.
- Every insight must include the sample size and date range in the internal analytics summary.

## AI Role

AI must not calculate directly from raw records.

Deterministic analytics engine calculates:

- totals
- averages
- rolling trends
- baselines
- correlations
- confidence scores
- anomalies

AI receives a compact `AiHealthSummary` and writes:

- plain-language explanation
- possible interpretation
- limitations
- practical next steps

The detailed screen and module architecture lives in `docs/personal-analytics-architecture.md`.

Allowed language:

- This may suggest...
- In your data, this pattern appears...
- The relationship is weak/moderate/strong...
- This is not a diagnosis.

Forbidden language:

- This proves...
- You have disease X.
- Your health problem is...

## Future Dashboard Sections

Long-term Activity:

- yearly steps and estimated km
- monthly steps and estimated km
- best month/year
- trend versus previous period

Sleep & Activity:

- whether activity appears to improve sleep
- whether sleep appears to improve next-day activity
- activity thresholds that seem best for sleep

Cardio Efficiency:

- walking heart rate trend
- running heart rate trend
- VO2 max trend
- resting heart rate trend

Body Composition:

- weight trend
- fat trend
- muscle trend
- water trend
- relation to cardio and sleep

AI Body Report:

- what improved
- what worsened
- what patterns were found
- what data is weak or missing
- what to test next

## Implementation Priority

Milestone 1:

- historical CSV import
- raw/staging records that preserve the source JSON/payload, source file, timestamps, provenance, and parser version
- detailed analytical records before UI summaries:
  - sleep details with bedtime, wake time, REM, deep sleep, light sleep, awake duration, awake count, and sleep score when present
  - workout sessions with start/end, type, duration, distance, active calories, total calories, average/min/max heart rate, pace, cadence, training effect, recovery time, VO2 max, and GPX reference when present
  - daily activity records with canonical steps, distance, active calories, and estimated kilometers
  - heart daily summaries and later sample-level or compressed heart series where useful
- daily aggregates generated from detailed records, not treated as the only source
- yearly/monthly step totals
- estimated km using `stepsPerKm`
- data quality/audit screen
- only a minimal dashboard needed to verify import correctness

Milestone 2:

- importer tests and audits for detailed records:
  - yearly/monthly/day step totals
  - sleep stages and sleep score counts
  - workout session counts and key metrics
  - running/walking separation
  - estimated kilometers
- baseline engine and rolling 7/30/90-day trends
- yearly/monthly comparisons

Milestone 3:

- sleep/activity correlation engine
- next-day effect analysis
- confidence scoring

Milestone 4:

- cardio efficiency engine
- walking/running heart-rate comparison
- VO2 max trend

Milestone 5:

- AI weekly/monthly report from deterministic summaries

Milestone 6:

- Health Connect background/new-data sync

Milestone 7:

- body composition import and correlation analysis

Test targets:

- yearly step totals
- monthly step totals
- estimated km calculation
- correlation sample-size rules
- missing-data confidence downgrade

## Historical Data Role

Historical Mi Fitness export is essential because it can provide years of baseline data. The importer should normalize it into the same daily tables used by Health Connect:

- activity
- heart
- sleep
- workouts
- body metrics

After import, the analysis layer should be able to compare:

- today versus personal baseline
- last 7 days versus prior 7 days
- last 30 days versus prior 30 days
- current training volume versus historical normal
- gaps in Health Connect versus available historical export data
