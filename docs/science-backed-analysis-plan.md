# VitaTrace Science-Backed Analysis Plan

Date: 2026-06-05

This document translates health and exercise research into product rules for VitaTrace. It overrides older UI ideas when they conflict with the rule below:

> The app must show useful body interpretation, not sync status, API status, raw record counts, or developer diagnostics.

## Current Data Reality

From the pulled phone DB and Mi Fitness import:

- Daily activity is strong: about 1030 closed activity days.
- Walking workouts are strong: 273 walking sessions, but some records have impossible duration/pace and must be filtered before analysis.
- Sleep is medium: 132 closed nights with stages and score. Useful for personal baseline, but not for clinical claims.
- Daily heart is medium/weak: 198 days, but mostly daily average heart rate, not clean resting heart rate.
- Running is weak for trends: 6 closed running sessions.
- Weight/body composition is blocked: only 3 weight days, no real body-composition history.
- Health Connect live is an internal source only. Do not show Health Connect, sync, API, quota, permissions, or technical status on the main product screen.

## Research Rules

### Activity And Fitness

Useful signals:

- Steps and estimated kilometers: good for long-term activity load and consistency.
- Active minutes by intensity: useful only if duration and heart-rate data are credible.
- Pace at comparable distance: useful for fitness trend.
- Heart rate at comparable pace/distance: useful for aerobic efficiency.
- kcal/km: useful as a supporting efficiency metric, not a metabolism claim.
- VO2max: useful as a fitness estimate only when linked to comparable walks/runs.
- Heart-rate recovery: strong fitness/recovery marker, but only if we have post-exercise HR data. We do not currently have enough clean recovery data.

Rules:

- Compare walking only inside comparable distance bands.
- Filter impossible sessions before using workout duration, pace, calories, or HR.
- Do not compare a 3 km slow walk with a 30 km long walk as if they were the same physiological signal.
- For the user profile age 40, AHA age-predicted max HR is about 180 bpm; moderate activity is roughly 50-70% of max HR and vigorous is roughly 70-85%. This is only a general guide, not a diagnosis.

Sources:

- CDC says intensity affects heart rate and breathing, brisk walking is moderate, running is vigorous, and talk-test / METs can classify intensity: https://www.cdc.gov/physical-activity-basics/measuring/index.html
- AHA gives age-based target HR zones and says age 40 has estimated max HR about 180 bpm with target zone 90-153 bpm: https://www.heart.org/en/healthy-living/exercise-and-physical-activity/fitness-basics/target-heart-rates
- NEJM Cole et al. found heart-rate recovery after exercise predicted mortality in clinical exercise testing; use only if we have real recovery HR data: https://www.nejm.org/doi/full/10.1056/NEJM199910283411804
- Wearable/free-living cardiorespiratory-fitness research uses resting HR, physical activity, and anthropometry to estimate CRF/VO2max-like fitness: https://pmc.ncbi.nlm.nih.gov/articles/PMC9718831/

### Sleep

Useful signals:

- Total sleep duration.
- Sleep consistency and recent 7/14/30 measured-night windows.
- Personal monthly baseline.
- REM/deep/light as wearable estimates, best used as personal trends, not exact truth.
- Sleep after heavy activity and next-day activity after poor sleep.

Rules:

- Do not claim more steps improve sleep unless the user's own data supports it.
- Use lagged tests:
  - same-day activity vs same-night sleep
  - previous-day activity vs night sleep
  - sleep vs next-day activity/heart/training
- Sleep stages should be shown as estimated trend, not medical fact.
- If sample is under 14 paired nights, do not show a correlation claim.

Sources:

- CDC adult sleep reference: adults should get at least 7 hours per day: https://www.cdc.gov/sleep/data-research/facts-stats/adults-sleep-facts-and-stats.html
- PubMed systematic review: consumer wearables can estimate sleep, but stage classification still needs improvement and literature is limited: https://pubmed.ncbi.nlm.nih.gov/38557808/
- Bidirectional sleep/activity research supports testing both directions rather than assuming one-way causality: https://pmc.ncbi.nlm.nih.gov/articles/PMC12686483/
- Systematic review of wearable sleep staging: wearable sleep stages are inferred from sensors and should not be treated like lab polysomnography: https://pmc.ncbi.nlm.nih.gov/articles/PMC7956647/

### Heart

Useful signals:

- Resting heart rate or night heart rate, if available.
- Daily average HR as load/recovery context only.
- Exercise HR at comparable pace/distance.
- Max HR during running as intensity context.
- HR trend after similar walks/runs.

Rules:

- Do not treat daily average HR as resting HR.
- Do not use heart metrics for medical diagnosis.
- High daily HR days can trigger a "check recovery context" insight only when paired with sleep/activity and enough samples.
- Better future source: nightly HR, resting HR, HRV if Health Connect or Mi Fitness exposes it.

Sources:

- Longitudinal wearable study found daily resting HR varies by person and associates with sleep, BMI, age, sex, and season: https://pmc.ncbi.nlm.nih.gov/articles/PMC7001906/
- Meta-analysis found higher resting HR is associated with increased mortality risk, but this applies to resting HR, not arbitrary daily average HR: https://pmc.ncbi.nlm.nih.gov/articles/PMC4754196/
- AHA notes resting HR is affected by stress, anxiety, hormones, medication, and physical activity, and active people may have lower resting HR: https://www.heart.org/en/healthy-living/exercise-and-physical-activity/fitness-basics/target-heart-rates

## What VitaTrace Should Show

Main screen:

- "Co sie zmienilo"
- "Co jest mocne"
- "Co wyglada podejrzanie"
- "Czego nie wiemy"
- "Co sprawdzic dalej"

No main-screen technical words:

- Health Connect
- API
- quota
- permissions
- sync
- record count
- SDK
- database

Allowed technical access:

- A later Options/Debug screen may show sync, permissions, source coverage, and errors.
- It must not be the default screen.

## First Real Product Modules

### 1. Activity Over Time

Question: "Czy moj poziom ruchu rosnie, spada czy jest nierowny?"

Show:

- Yearly steps.
- Estimated km using 1250 steps/km.
- Source km when available.
- Best month, worst month, current month status.
- Peak/slump label versus prior baseline.

Current user finding:

- 2025 was +14.0% steps versus 2024.
- 2026 is uneven, not clearly worse.
- April 2026 is a major peak.

### 2. Walking Fitness

Question: "Czy chodze szybciej / na nizszym tetnie / taniej energetycznie przy podobnym dystansie?"

Show:

- Comparable distance bands: 3-6 km, 6-10 km, 10-15 km, 15+ km.
- Pace.
- Average HR.
- kcal/km.
- VO2max when present.
- Session count and confidence.

Current user finding:

- For filtered 3-15 km walking sessions, pace improved from 14:43/km in 2024 to 13:00/km in 2025 and 12:38/km in 2026.
- kcal/km dropped from 68.3 to 59.9 by 2026.
- This is currently the best fitness signal in the dataset.

### 3. Sleep And Recovery

Question: "Czy sen jest ponizej mojej normy i co go moze ruszac?"

Show:

- Last 7/14/30 measured nights against personal baseline.
- Total sleep first.
- REM/deep/light as estimates.
- Sleep score only as support.
- Same-day and previous-day activity correlation, but only if sample is enough.

Current user finding:

- Last 30 measured nights are about 7.26 h, around +7 min versus baseline.
- More steps do not clearly improve same-night sleep in current data.
- Previous-day high steps may be linked with lower score/REM, but this is a hypothesis, not proof.

### 4. Heart Load

Question: "Czy dni z wysokim tetnem wygladaja jak obciazenie albo slaba regeneracja?"

Show:

- Only if enough HR days.
- Separate daily average HR from resting/night HR.
- Pair high-HR days with sleep, steps, workout time.
- Do not diagnose.

Current user finding:

- High average HR days have shorter sleep in available paired data, but this is older/partial coverage and must be treated as a monitoring candidate.

## What Not To Build Yet

- Weight/body-composition analysis.
- Blood-lab AI analysis.
- Strong running trends.
- Calories/metabolism conclusions.
- Medical alerts.
- Readiness score from unsupported inputs.

## Next Engineering Tasks

1. Keep `tools/deep_health_report.py` as the offline truth-checker.
2. Add research-backed metrics to the report:
   - HR zone minutes for running/walking where HR is credible.
   - walking efficiency by distance band and month.
   - peak/slump detector for activity.
   - sleep after long walks versus normal days.
   - high-HR day context with sample-count filtering.
3. Rewrite Android visible copy around the four product modules above.
4. Move sync/source/errors to Options/Debug only.
5. Do not add AI until deterministic metrics produce clean, compact facts.
