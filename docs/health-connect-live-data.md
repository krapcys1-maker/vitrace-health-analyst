# Health Connect Live Data Plan

This file explains how VitaTrace will access live/new data from Android Health Connect.

## What We Need From The User

Before implementing or testing real Health Connect reads, collect:

- Phone Android version.
- Whether Health Connect opens in Android Settings.
- Whether Mi Fitness appears in Health Connect app permissions.
- Whether Mi Fitness has permission to write data into Health Connect.
- Which data types are visible in Health Connect after 24 hours: steps, sleep, heart rate, exercise, distance, calories, VO2 max, SpO2, weight.
- A screenshot or short note from Health Connect data sources if available.

Do not ask for raw exports or private screenshots unless needed. Notes like "steps and sleep are visible, heart rate is not" are enough.

## Device Setup Checklist

On the phone:

1. Open Android Settings and search for Health Connect.
2. Confirm Health Connect is available.
3. Open Mi Fitness settings and look for Health Connect integration.
4. Allow Mi Fitness to write supported data types into Health Connect.
5. In Health Connect, check App permissions and confirm Mi Fitness is connected.
6. Wait for a normal Mi Fitness sync cycle, ideally overnight.
7. Check Health Connect data screens for new records.

Important: if Health Connect was installed today, the first meaningful records may appear after Mi Fitness syncs new data.

## App Implementation Plan

Implement Health Connect in this order:

1. Add Health Connect SDK dependency.
2. Add manifest declarations and health permissions.
3. Implement `HealthConnectAvailabilityProbe`.
4. Build a Settings screen that shows:
   - SDK available / unavailable
   - permissions granted / missing
   - supported features
   - visible record counts for the last 1, 7, and 30 days
5. Add foreground read for a short range first.
6. Use aggregate reads for cumulative metrics such as steps, distance, and calories.
7. Store imported records with source provenance:
   - `source = HEALTH_CONNECT`
   - `dataOrigin` when available
   - `importedAt`
   - quality flags
8. Recalculate only changed days after sync.
9. Add WorkManager background sync only after foreground sync is proven.

## Permissions And History

Health Connect normally allows reading data up to 30 days before the first permission grant. Older reads require the Health Connect history permission.

VitaTrace should not rely on Health Connect for historical data. The Mi Fitness CSV export remains the history source. Health Connect is for new/live records.

## Data Type Priorities

MVP probe:

- steps
- distance
- total calories burned
- heart rate
- sleep sessions
- exercise sessions
- VO2 max if available
- oxygen saturation if available
- weight if available

Later:

- speed
- active calories
- basal metabolic rate
- body fat
- hydration/body water if exposed by source apps
- medical records only if the platform, permissions, and user consent clearly support it

## Important Rules

- Do not hardcode Health Connect data origins.
- Do not assume Mi Fitness writes every data type.
- Do not treat missing Health Connect records as missing user behavior until data reliability checks confirm the source is expected to write them.
- Do not send Health Connect raw records to AI.
- Use app database as source of truth.
- Use Health Connect aggregate APIs for cumulative totals when possible to avoid double counting.

## Official References

- Health Connect get started: https://developer.android.com/health-and-fitness/health-connect/get-started
- Read raw data: https://developer.android.com/health-and-fitness/health-connect/read-data
- Read aggregated data: https://developer.android.com/health-and-fitness/health-connect/aggregate-data
- Data types: https://developer.android.com/health-and-fitness/health-connect/data-types
- Feature availability: https://developer.android.com/health-and-fitness/health-connect/features/availability

