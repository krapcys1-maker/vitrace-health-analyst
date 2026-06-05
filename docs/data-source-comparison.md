# Data Source Comparison

Current comparison date: 2026-06-05.

## Summary

Mi Fitness export is currently the rich historical source. Health Connect is connected and readable, but the live data available through it is much thinner right now.

This does not mean Health Connect is useless. It means VitaTrace should treat Health Connect as the forward/live source and the Mi Fitness export as the long baseline source until Health Connect accumulates more records and Mi Fitness writes more metric types into it.

## Latest Health Connect Snapshot

Latest sync captured at: 2026-06-05 14:32.

Health Connect 30-day coverage:

- Steps: 31 records, origin `com.xiaomi.wearable, android`, last record 2026-06-05 13:59.
- Active calories: 3 records, origin `com.xiaomi.wearable`, last record 2026-06-05 13:59.
- Distance: 1 record, origin `com.xiaomi.wearable`, last record 2026-06-05 13:31.
- Exercise: 1 record, origin `com.xiaomi.wearable`, last record 2026-06-05 13:31.
- Heart rate: 1 Health Connect record, origin `com.xiaomi.wearable`, last record 2026-06-05 13:31.
- Sleep: 0 records.
- SpO2: 0 records.
- VO2 max: 0 records.
- Weight: 0 records.

Important interpretation: Health Connect record counts are not the same as useful daily coverage. For example, heart rate shows 1 Health Connect record, but VitaTrace expands that record into 1,459 heart samples for 2026-06-05.

## Current VitaTrace Daily Database

After importing history and syncing Health Connect:

- Activity rows: 1,073 total days.
- Heart rows: 1,073 total days.
- Sleep rows: 1,073 total days.
- Workout rows: 1,073 total days.
- Body rows: 1,073 total days.

Positive source coverage:

- Activity:
  - `MI_FITNESS_EXPORT`: 1,030 positive days.
  - `com.xiaomi.wearable, android, MI_FITNESS_EXPORT`: 1 positive merged day, 2026-06-05.
- Heart:
  - `MI_FITNESS_EXPORT`: 198 positive days.
  - `com.xiaomi.wearable`: 1 positive live day, 2026-06-05.
- Sleep:
  - `MI_FITNESS_EXPORT`: 133 positive days.
  - Health Connect live: 0 positive days.
- Workouts:
  - `MI_FITNESS_EXPORT`: 230 positive days, 303 sessions.
  - Health Connect live: not currently preserved as separate richer data because 2026-06-05 already had historical workout data.
- Body:
  - `MI_FITNESS_EXPORT`: 220 positive days.
  - Health Connect live: 0 positive days for weight, VO2 max, and SpO2.

Detailed historical tables:

- Sleep details: 133 rows, all with stages, sleep score, bedtime, and wake time.
- Workout sessions: 303 rows.
- Workout detail coverage:
  - distance: 286 sessions
  - active calories: 303 sessions
  - average heart rate: 299 sessions
  - max heart rate: 300 sessions
  - calculated average pace: 286 sessions
  - cadence: 282 sessions
  - VO2 max: 280 sessions
  - GPX reference: 281 sessions

Last 30 days in VitaTrace after merge:

- Activity: 30 active days, 276,583 steps, 163.8 km, 11,219 active kcal.
- Heart: 1 positive day, 1,459 samples, average daily BPM about 108.6.
- Sleep: 22 positive days, 9,518 minutes, average 432.6 minutes on days with sleep.
- Workouts: 5 positive days, 5 sessions, 211 minutes.
- Body: 5 positive days, 10 VO2 records, 0 weight records, 0 SpO2 records.

## Why Live Looks Poorer

Current live Health Connect is poorer for three reasons:

- Health Connect was connected only recently, so it has not accumulated historical records.
- Mi Fitness/Xiaomi currently writes only some metric types into Health Connect.
- VitaTrace currently syncs only the last 30 days from Health Connect.

The latest live snapshot shows no Health Connect sleep, SpO2, VO2 max, or weight records. Those metrics currently come from the Mi Fitness export, not live sync.

## Product Decision

VitaTrace should keep the current direction:

- Mi Fitness export = historical baseline and trend analysis source.
- Health Connect = live/forward source and freshness signal.
- Local VitaTrace database = dashboard and analysis source of truth.

Next architecture improvement:

- Store source-specific daily rows or source audit rows before merging.
- Keep one merged row for dashboard speed.
- Add a source comparison screen that shows, per metric and per day:
  - Mi Fitness export value
  - Health Connect value
  - chosen dashboard value
  - reason for choosing it

This is needed because the current merge keeps the best final value but does not preserve a separate per-day Health Connect value for direct later comparison.
