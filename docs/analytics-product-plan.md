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
