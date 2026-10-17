# Work Progress — Stage 00–02 (recovery checkpoint)

Read this file with `INDEX.md` and `DECISIONS.md` before resuming work. The plan of record is at `C:\Users\arnav\.claude\plans\splendid-plotting-dijkstra.md`.

## Current state

- **Branch:** `docs/analysis-design`. Commit `08a1433` is the prototype snapshot (DEC-002). Nothing has been pushed or merged.
- **Stage 00–02 status:** all phases (0–9) complete as a **proposal**. Nothing is approved. Implementation is blocked on owner decisions (§3).
- **Last checkpoint:** final Stage 00–02 commit, 2026-10-17. See `git log`.

## 1. Phase checklist

| Phase | Status |
|---|---|
| 0 Context ingestion, scaffold, audit (F01–F04) | Done |
| 1–3 Analysis (A01–A25, A29, A30), 49 probe IDs, 17 screenshots | Done |
| 4 Requirements baseline (A26–A28, A31, A32): 116 FR, 46 NFR, 52 parity rows, 133 risks | Done (baselined, not approved) |
| 5 Architecture (D01–D10, ADR-001…011) | Done (proposed) |
| 6 UX (D11–D15) | Done (proposed) |
| 7 Engineering design (D16–D20), `verification-coverage.csv`, `traceability.csv` | Done (proposed) |
| 8 Internal quality review | Done. 32 findings applied, plus residual fixes (`02-design/06-handoff/phase8-review-record.md`). Checker: 0 errors, 0 warnings. Traceability: no gaps. |
| 9 Handoff | Done (D20, plus the final report in the session) |

## 2. How to verify the documentation state

```
.venv/Scripts/python.exe -I docs/tools/check_docs.py          # IDs, links, acceptance criteria, decision hygiene
.venv/Scripts/python.exe -I docs/tools/build_traceability.py  # regenerate traceability.csv; must report no gaps
.venv/Scripts/python.exe -I docs/tools/check_docs.py --index  # regenerate INDEX.md from front matter
git diff 08a1433 --stat -- backend frontend speech scripts tests fiji external   # must be empty (no prototype changes)
```

## 3. Outstanding owner decisions (blocking order)

1. **DEC-010** Architecture O2. All design ADRs depend on it.
2. **DEC-030** Menu-item rerouting deviation, which needs explicit acceptance.
3. **DEC-012** Hackathon strategy, including whether a spike may be built.
4. **DEC-007** Hosted-LLM policy, including the FR-NL-010 preview-scope clarification (D08).
5. **DEC-005** Licence and distribution, including the PyAV GPL-codec question (ADR-005 correction).
6. **DEC-001** Product name.
7. Review of the remaining proposed decisions: DEC-006, DEC-011, DEC-020…029, DEC-031…047.

## 4. Next stage (after approval)

Convert D20 §4 (candidate milestones M1–M6) into formal milestones, with acceptance gates, tests, commit boundaries and a Git workflow. M1 closes validation items V-01…V-12, VD17-1…3 and VD18-1/2, and sets up the Maven Wrapper toolchain.
