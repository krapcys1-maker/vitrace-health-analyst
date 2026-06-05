# VitaTrace Agent Guide

This file is the first thing every agent should read when continuing work on VitaTrace.

## Start Here

Read these files in order before making changes:

1. `README.md`
2. `docs/agent-memory.md`
3. `docs/project-context.md`
4. `docs/insight-contract.md`
5. Local private docs in `dokumentacja/` only when needed and only on this machine.

Current working branch: `vitrace/mvp-foundation`.

## Project Mindset

Work like a careful health data analyst, not like a fast demo builder.

- Inspect data and code before deciding.
- Keep health data private by default.
- Prefer deterministic calculations before AI interpretation.
- Build small, testable layers.
- Treat `docs/insight-contract.md` as the current product/analytics contract when older plans disagree.
- Record decisions and progress in `docs/agent-memory.md`.
- If something is uncertain, prove it with a small check or mark it as an assumption.

## Privacy Rules

Never commit:

- `.env` or any real API key.
- Raw Mi Fitness exports.
- Local health files, scans, photos, databases, or generated private reports.
- Anything under `dane historyczne z zegarka/`.
- Anything under `data/raw/`.

The app is local-first. DeepSeek or any future AI provider may receive only aggregated summaries after explicit user consent. Raw CSV rows, raw GPX tracks, raw JSON payloads, images, and lab scans must not be sent to AI.

## Architecture Rules

- The local Room/SQLite database is the app source of truth.
- Mi Fitness CSV is the historical import channel.
- Health Connect is a future/new-data input channel, not the source of truth.
- Store raw payloads locally so importers can be fixed later without data loss.
- Normalize into stable metric tables and daily aggregates.
- AI reports must depend on calculated metrics, trends, and quality flags, not raw records.
- Medical language must stay conservative: no diagnosis, no disease claims.

## Work Protocol

Before work:

1. Run `git status --short --branch`.
2. Read `docs/agent-memory.md`.
3. Check whether files you need are tracked, ignored, or private local-only files.
4. For non-trivial work, state the plan briefly.

During work:

1. Keep changes scoped.
2. Add or update tests when behavior changes.
3. Run the smallest meaningful checks first, then broader checks.
4. Update `docs/agent-memory.md` when a meaningful step is finished.

Before finishing:

1. Run `git status --short --branch`.
2. Run relevant tests or clearly state why they were not run.
3. Confirm no private files are staged.
4. Summarize changed files, tests, and next steps.

## Testing Standard

Minimum tests expected as the app grows:

- CSV parser tests for quoted JSON, commas, malformed rows, and large files.
- Import idempotency and deduplication tests.
- Daily aggregation tests for steps, calories, heart rate, sleep, stress, VO2 max, SpO2, and weight.
- Data Quality tests for missing days, duplicates, unknown keys, and source coverage.
- Health Connect probe/sync tests with fake data sources.
- AI reporter tests proving prompts contain only aggregated metrics.

No feature is considered done until it has either automated tests or a documented reason why it cannot yet be tested.
