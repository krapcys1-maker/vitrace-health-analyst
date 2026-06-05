# VitaTrace

Local-first Android health analytics app for Mi Fitness exports and future Health Connect sync.

VitaTrace is planned as a private health and fitness analyst: raw data stays local, deterministic analytics produce metrics and trends, and any future AI reporting must use only aggregated summaries.

The product direction is Personal Body Intelligence: yearly/monthly activity totals, estimated kilometers from steps, personal baselines, and careful correlations between activity, sleep, heart rate, workouts, VO2 max, weight, and future body composition data.

## Agent Continuity

New coding sessions should start with:

1. `AGENTS.md`
2. `docs/agent-memory.md`
3. `docs/project-context.md`
4. `docs/insight-contract.md`
5. `docs/insight-research-reset.md`
6. `docs/health-connect-live-data.md`
7. `docs/android-setup.md`

These files are the project brain: current state, decisions, privacy rules, test expectations, and next steps.

## Privacy Defaults

- Do not commit `.env` files.
- Do not commit raw Mi Fitness exports or other personal health files.
- Keep AI prompts limited to aggregated metrics, never raw records.
