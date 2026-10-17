# 03 — Development

Implementation records of the Fidelis workstation (`fidelis-app/`), started 2026-10-17 on the branch `development/fidelis-workstation`:

- [ACTIVE-DEVELOPMENT.md](ACTIVE-DEVELOPMENT.md) — the living record: architecture in force, toolchain, agent team and ownership, milestones M0–M7 with evidence, known issues, resume procedure.
- [DEV-BRIEF.md](DEV-BRIEF.md) — the brief every implementation agent works from.
- [VERIFICATION-RESULTS.md](VERIFICATION-RESULTS.md) — reference baselines from the pinned Fiji and the verification runs.
- [DEMO-RUNBOOK.md](DEMO-RUNBOOK.md) — how to run the J-12 cell-counting demo and what numbers to expect.
- [HANDOFF.md](HANDOFF.md) — the handoff written at the pause of 2026-10-17: what is implemented and verified, decisions, unresolved problems, suggested workstreams.
- [screenshots/](screenshots/) — real screenshots of the workstation on the real engine (J-12 on Blobs, proposal sheet, compare view).

Team delivery (three workstreams, from 2026-10-17 15:00 BST; tracking on reverez/Voice issue #2):

- [TEAM-PLAN.md](TEAM-PLAN.md) — goal, verified baseline, gap analysis, workstreams, milestones and schedule, dependencies, risks, integration authority.
- [WORKSTREAM-CONTRACTS.md](WORKSTREAM-CONTRACTS.md) — file ownership, architecture invariants, the implemented contract, how to change it.
- [GITHUB-WORKFLOW.md](GITHUB-WORKFLOW.md) — branches, issue lifecycle, pull requests, merges, blockers, checkpoints and recovery.
- Execution briefs with launch prompts: [ARNAV-EXECUTION.md](ARNAV-EXECUTION.md) (engine, integration), [AYUSH-EXECUTION.md](AYUSH-EXECUTION.md) (voice, language), [ALEX-EXECUTION.md](ALEX-EXECUTION.md) (UI).
- Workstream handoffs: [handoffs/](handoffs/) (one file per developer, owned by that workstream).

DEC-010 (hybrid B) is implemented as recorded in ACTIVE-DEVELOPMENT; its formal status in `docs/DECISIONS.md` remains "proposed" until the owner approves it.
