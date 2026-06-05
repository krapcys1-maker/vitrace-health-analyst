# Health Journal And Lab Analysis

`Zdrowie` is the place for context that watches and scales do not know:

- subjective physical state
- subjective mental state
- symptoms
- unusual events
- blood test results and other medical documents
- AI summaries of current results versus personal history

The goal is not diagnosis. The goal is to help the user understand patterns in their own body and prepare better questions for a doctor, coach, or future self.

## Current Implementation

The Android app now has a local `health_notes` table.

Each note stores:

- date
- note text
- inferred tags
- optional future mood score
- optional future physical score
- created/updated timestamps

The `Zdrowie` tab now includes:

- daily note input
- latest note list
- AI/lab analysis plan cards
- explanation that notes will be correlated with sleep, sport, pulse, body data, and lab results

Current simple tag inference:

- `psychika`
- `fizycznie`
- `sen`
- `aktywnosc`

This is intentionally local and deterministic. AI can later use these tags as context, but the raw note stays local unless the user explicitly asks for AI analysis.

## Future Lab Result Flow

1. User imports a photo/PDF/scan of blood results.
2. OCR extracts possible parameters.
3. App shows extracted values for confirmation.
4. Confirmed values are stored as structured lab observations:
   - parameter key, for example `glucose`, `ferritin`, `crp`
   - display name
   - value
   - unit
   - reference range from the document, if available
   - date
   - source document
5. AI summary compares:
   - newest result versus prior result
   - newest result versus personal trend
   - newest result versus reference range
   - result context from notes, sleep, sport, weight, pulse, and VO2 max

## Useful Analyses

High-value analyses to build:

- Sleep versus next-day physical and mental note tags.
- Hard training versus next-day fatigue notes.
- Step volume versus sleep duration and sleep consistency.
- Resting/average heart rate versus bad-feeling notes.
- VO2 max trend versus running/walking volume.
- Weight trend versus sleep and active calories.
- Lab trend versus training blocks, body weight, sleep, and subjective energy.
- Symptom clustering: which notes repeat around low sleep, high activity, or high heart rate.
- Recovery flags: high activity plus poor sleep plus negative note.
- Positive pattern mining: which weeks combine better sleep, more stable activity, and better subjective state.

## Important Product Rules

- AI should not diagnose.
- AI should state uncertainty and data coverage.
- AI should cite which days, metrics, and notes it used.
- Notes are private and must not be sent to AI without explicit user action.
- For correlations, show sample size and avoid conclusions below a minimum threshold.
- User-entered notes should be treated as signal, not objective medical measurement.

## Next Engineering Steps

1. Add source-specific daily rows so Health Connect and Mi Fitness values can be compared before merge.
2. Add structured lab result tables and document metadata.
3. Add note filters by tag and date range.
4. Add simple local correlations before AI summaries.
5. Add an AI prompt builder that sends only selected aggregates and selected note summaries.
