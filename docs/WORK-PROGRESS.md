# Work Progress — Stage 00–02 (recovery checkpoint)

Read this file together with `INDEX.md` and `DECISIONS.md` before resuming work. Plan of record: `C:\Users\arnav\.claude\plans\splendid-plotting-dijkstra.md`, with the phase summary below.

## Current state

- **Branch:** `docs/analysis-design`. Commit `08a1433` is the prototype snapshot (DEC-002). Nothing has been pushed.
- **Current phase:** Phase 1–3 (analysis drafting).
- **Last checkpoint:** Phase 0 complete (2026-10-17).

## Phase checklist

| Phase | Status | Notes |
|---|---|---|
| 0 Context ingestion, scaffold, audit | Done | Stray `IJ_Props.txt` removed from the Fiji root; pin re-verified. Tests re-run (23 passed). F01–F04, A01, glossary, decision register and upstream manifest written. |
| 1–3 Upstream, interaction and feasibility analysis | In progress | Research agents G1–G7 are drafting A02–A17, A24, A25 and A29. The lead writes A18–A23 and A30 after them. |
| 4 Requirements baseline (A26–A28, A31, A32) | Not started | |
| 5 Architecture design (D01–D10, ADRs) | Not started | |
| 6 UX design (D11–D15) | Not started | |
| 7 Engineering design (D16–D20) | Not started | |
| 8 Internal quality review | Not started | Checker script plus a reviewer agent |
| 9 Handoff report | Not started | |

## Research sources inspected

- Repository (all source, docs, tests and locks): F02.
- The pinned Fiji install (jar listing, `IJ_Props.txt`, `javap` of listener APIs).
- The upstream cache: 18 shallow clones (see `data/upstream-manifest.yaml`).

## Agent assignments (Phase 1–3)

| Agent | Documents | Probe IDs |
|---|---|---|
| G1 | A08, `data/commands.csv`, `data/commands-core-parameters.csv` | P10–P29 |
| G2 | A09, A10, A11, A12 | P30–P39 |
| G3 | A07, A13, A14, A15 | P40–P49 |
| G4 | A04 + screenshots | P50–P59 |
| G5 | A16, A17 | P60–P69 |
| G6 | A02, A03, A05, A06 | — |
| G7 | A24, A25, A29, `data/datasets.csv` | P70–P79 |
| (planned) G8 | GUI-mode feasibility probes: listeners, Recorder capture of programmatic runs, hidden-window behaviour | P80–P89 (after G4) |

## Outstanding questions

- Product name (DEC-001) and code licence (DEC-005): owner decisions.

## Next required action

When the agents report, review each document for evidence tagging and cross-document consistency. Then write A18–A23 and A30, and run G8.
