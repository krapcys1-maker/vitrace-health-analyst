# VitaTrace AI Analysis Architecture

AI is not the data engine and not the importer. AI is an explanation layer over deterministic analysis.

## Placement

AI belongs behind an analysis service:

```text
Mi Fitness export / Health Connect / manual notes
        |
        v
local raw + detailed analytical tables
        |
        v
deterministic analytics engine
        |
        v
AiHealthSummary
        |
        v
AI provider, for example DeepSeek
        |
        v
plain-language report in Analiza / Zdrowie
```

The app UI should call an `AnalysisContextBuilder` or equivalent service. The UI should not assemble prompts from cards.

The deterministic engine owns all calculations:

- monthly sleep phase averages
- monthly sport trends
- calories per kilometer and calories per minute
- same-effort running/walking comparison
- high-activity versus low-activity sleep comparison
- body-composition correlations when future weight/fat/muscle data exists

AI owns only explanation:

- what the calculated pattern may mean
- how strong or weak the evidence is
- which data is missing
- what the user can test next

The fuller module and UI design is in `docs/personal-analytics-architecture.md`.

## What AI May Receive

AI may receive a compact `AiHealthSummary` built from local tables:

- profile basics approved by the user
- data coverage by source
- yearly/monthly/weekly activity totals and estimated kilometers
- sleep detail summaries, including REM/deep/light/awake, sleep score, bedtime, and wake time when present
- workout summaries and comparable-session groups, including running/walking pace, distance, calories, heart rate, cadence, VO2 max, and recovery fields when present
- deterministic correlations with sample size, date range, and confidence
- health notes summarized by date/tag, only after explicit user approval
- lab/scan summaries only after OCR review and approval

AI must not receive:

- raw private CSV files
- raw payload JSON
- raw GPX route points or URLs
- photos/scans before review
- API keys
- unreviewed OCR text

## Source Difference Rule

Mi Fitness export is currently rich historical data. Health Connect is currently a sparse live source.

The analysis layer must explicitly mark source coverage:

- historical source has detailed sleep stages and workout session metrics
- live Health Connect currently has steps, active calories, distance, exercise, and limited heart data
- live Health Connect currently has no sleep, SpO2, VO2 max, or weight coverage in the latest snapshot

AI reports must mention when a conclusion is based mostly on historical export and when current live data is too thin.

## UI Placement

The main AI surface should be the `Analiza` tab:

- personal body report
- sleep/activity relationships
- cardio efficiency
- data coverage limitations
- suggested next checks

`Zdrowie` can have AI for notes and lab results:

- newest lab result explanation
- comparison with older lab results
- relation to notes, sleep, activity, and heart trends

`Opcje` should hold:

- AI provider settings
- API key status
- consent switches
- export/privacy controls

## Implementation Order

1. Build detailed analytical tables and import audits.
2. Build deterministic feature summaries from those tables.
3. Build `AiHealthSummary`.
4. Add a local prompt builder.
5. Add DeepSeek provider adapter.
6. Add user consent and a manual "Generate analysis" action.
7. Show AI output in `Analiza`, with limitations and source coverage.
