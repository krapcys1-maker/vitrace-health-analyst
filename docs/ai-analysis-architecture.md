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
AI Context Bundle
        |
        v
AI provider, for example DeepSeek
        |
        v
plain-language report in Analiza / Zdrowie
```

The app UI should call an `AnalysisContextBuilder` or equivalent service. The UI should not assemble prompts from cards.

The current offline implementation is:

```powershell
python tools\build_ai_context_bundle.py --db build\phone-db-check\phone-current-vitrace.db --output build\ai-context-bundle.json --prompt-output build\ai-context-prompt.md
```

Generated files stay in `build/` because they summarize private health data.

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
- which 3-5 findings deserve the main screen
- how to write those findings without sync/API/database language

## Exact AI Insertion Point

AI should run only after all of these are true:

1. The importer and deterministic engine have produced normalized summaries and `tested_insight` rows.
2. Each insight has answer, evidence, sample size, date range, confidence, limitations, and next step.
3. `tools/build_ai_context_bundle.py` has built a compact bundle from `analysis_results` and coverage tables.
4. The user explicitly taps a future "Generate analysis" action or enables an analysis run.

AI should not run:

- during sync
- during import
- while calculating correlations
- on raw CSV, raw JSON payloads, GPX routes, images, scans, or API logs
- on the main UI render path

This keeps the app deterministic first and makes AI a reviewer/explainer.

The fuller module and UI design is in `docs/personal-analytics-architecture.md`.

## What AI May Receive

AI may receive a compact `AI Context Bundle` built from local tables:

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

## Output Contract

AI output should be structured, not a long chatty paragraph:

```json
{
  "topFindings": [
    {
      "title": "short human title",
      "message": "plain Polish explanation",
      "confidence": "High|Medium|Low|Insufficient",
      "whyItMatters": "what the user can learn from it",
      "doNotOverclaim": "what this does not prove"
    }
  ],
  "whatChanged": [],
  "whatIsWeak": [],
  "nextTests": [],
  "mainScreenCopy": []
}
```

The app should store AI outputs separately from deterministic `analysis_results`, with provider, model, input bundle hash, generated date, and user-visible text. Do not overwrite deterministic insights.

## Source Difference Rule

Mi Fitness export is currently rich historical data. Health Connect is currently a sparse live source.

The analysis layer must explicitly mark source coverage:

- historical source has detailed sleep stages and workout session metrics
- live Health Connect currently has steps, active calories, distance, exercise, and limited heart data
- live Health Connect currently has no sleep, SpO2, VO2 max, or weight coverage in the latest snapshot

AI reports must mention when a conclusion is based mostly on historical export and when current live data is too thin.

## UI Placement

The first AI surface should be a human report area, not a technical tab:

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
3. Build `AI Context Bundle`.
4. Add a local prompt builder.
5. Add tests proving raw payloads/routes do not enter the bundle.
6. Add DeepSeek provider adapter.
7. Add user consent and a manual "Generate analysis" action.
8. Store AI output with bundle hash and generated date.
9. Show AI output in the human report area, with limitations and source coverage.
