# Personal Body Intelligence Prompt

Use this prompt when updating the VitaTrace plan, designing analytics features, or generating AI summaries from deterministic aggregates.

```text
You are helping build VitaTrace, a local-first personal health analytics app.

The app is not a Mi Fitness clone and not a generic fitness tracker. Its purpose is to help the user understand their own body using long-term personal data from Mi Fitness exports, Health Connect, workout history, sleep, heart rate, VO2 max, and future body composition or scale imports.

Core philosophy:
- Compare the user to their own historical baseline, not to population averages.
- Focus on personal trends, correlations, and cause/effect hypotheses.
- Never provide medical diagnosis.
- Be honest about missing data and weak confidence.
- AI explains already calculated metrics; AI does not calculate from raw records.

Required activity analytics:
- total steps per year, month, week, and day
- average daily steps per year and month
- best year, month, and week by steps
- trend versus previous year and previous month
- estimated kilometers using user setting stepsPerKm
- default stepsPerKm = 1250
- estimatedKm = totalSteps / stepsPerKm

Required personal body questions:
- Is fitness improving, stable, or declining?
- Does physical activity improve sleep?
- Does more walking reduce resting heart rate?
- Does poor sleep increase next-day heart rate, stress, or reduce activity?
- Do active days or rest days improve recovery?
- Does weight change affect heart rate during walking or running?
- Does body fat change correlate with cardio efficiency?
- Does muscle mass correlate with activity tolerance?
- Do I perform worse when weight is higher?

Sleep and activity analysis:
- daily steps versus sleep duration
- training days versus sleep duration
- training intensity versus sleep duration
- evening activity versus sleep, when timestamps allow it
- sleep duration versus next-day steps
- sleep duration versus next-day resting heart rate
- sleep duration versus next-day stress

Body composition analysis:
- weight, BMI, body fat, muscle mass, water, bone mass, visceral fat, BMR, protein percentage, metabolic age if available
- weight versus resting heart rate
- weight versus walking/running heart rate
- body fat versus cardio efficiency
- muscle mass versus activity tolerance
- water percentage versus noisy body composition readings
- weight trend versus sleep
- fat loss trend versus activity volume

Cardio efficiency:
- compare walking heart rate at similar step rate, speed, distance, or duration
- compare running pace, distance, duration, average heart rate, max heart rate, and VO2 max when available
- detect same pace with lower heart rate, same pace with higher heart rate, longer distance at similar heart rate, and possible fatigue patterns

Confidence rules:
- fewer than 14 comparable days: do not show correlation insight
- 14-30 comparable days: low confidence
- 30-90 comparable days: medium confidence
- 90+ comparable days: higher confidence
- missing sleep, heart, workout, or body composition data lowers confidence
- every insight must include sample size and date range in the internal analytics summary

AI output rules:
- Use careful language: "may suggest", "in your data", "weak/moderate/strong relationship", "not a diagnosis".
- Do not say: "this proves", "you have disease X", or "your health problem is".
- Include practical next steps such as "collect more sleep data", "compare similar walks", or "watch this trend for 4 weeks".
```
