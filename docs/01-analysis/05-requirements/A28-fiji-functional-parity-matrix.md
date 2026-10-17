---
id: A28
title: Fiji Functional Parity Matrix
stage: 01-analysis
status: Baselined (awaiting approval)
priority: P0
source_revisions: [Fiji archive 20261013-1517 (lock f6695397)]
related_requirements: [see parity-matrix.csv requirements column]
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A28 — Fiji Functional Parity Matrix

## Executive Summary

The authoritative matrix is [`docs/data/parity-matrix.csv`](../../data/parity-matrix.csv): 52 rows, `PAR-001`…`PAR-052`. For each audited subsystem it records:

- the original Fiji behaviour;
- the required modernised behaviour;
- the execution path (routes R1–R4 from A18);
- the user-visible interaction;
- compatibility limitations;
- the **target** parity status;
- priority, verification method, risk, linked requirements and evidence.

**Status reflects the target for the proposed architecture (DEC-010), not implemented functionality.** No modernised functionality exists yet. The prototype covers 4 commands on 8-bit PNG only (F02).

| Target status | Count | Meaning |
|---|---|---|
| complete | 23 | Same scientific behaviour, offered through modernised UI. Equivalence is verifiable by A30. |
| complete (new) | 2 | New capability with no Fiji counterpart: NL and voice (PAR-043/044) |
| partial | 18 | Core behaviour preserved; some sub-features, edge cases or interactions are deferred or behave differently (stated per row) |
| legacy fallback | 5 | The original Fiji window or dialog is used unchanged and tracked by the workspace (C4–C6 plugins, Script Editor, Updater) |
| deferred | 2 | Not in scope until later tiers (batch-processing UI, macro toolsets) |
| unsupported | 2 | Deliberately not supported: in-session JVM exit by plugins (recovered by the supervisor), and upstream HiDPI defects in legacy windows |

## Purpose and Scope

This document is the single compatibility reference requested in brief Part 8 A28. It covers every subsystem audited in A04 and A07–A15, plus the two new modalities. It does **not** enumerate per-command parity for all 1,390 catalogue entries. Per-command status derives from the compatibility category (A13, `FR-CMD-005`) and the core parameter set (`commands-core-parameters.csv`). Third-party update-site plugins are not audited (A08 coverage limits).

## How to read the matrix

- **execution_path** uses A18 routes:
  - R1: `IJ.run(imp, label, completeOptions)` on the engine worker;
  - R2: ImageJ2 `CommandService`/`ModuleService` with resolved inputs;
  - R3: direct Java API (ROI, display, IO, data reads);
  - R4: macro or script engine.
- **verification** uses the A30 vocabulary:
  - R = reference-equivalence test (E1–E7);
  - T = automated test;
  - D = demonstration;
  - I = inspection.
- **priority** uses the F04 tiers. A row whose sub-features have different tiers lists each one.
- The **requirements** column lists the FR/NFR IDs from A26/A27. `..` denotes an inclusive ID range.

## Highlights by area

| Area | Rows | Main compatibility limitations | Why |
|---|---|---|---|
| IO | PAR-001…005, 046 | OME-TIFF needs the GUI-capable engine. Lossy formats drop metadata. | P43, P46, P47 |
| Viewer and display | PAR-006…013 | Hidden IJ1 windows still exist; large or pyramidal data needs a BDV fallback | A15, A23 |
| Processing | PAR-014…020 | Multi-image commands need registration; globals are JVM-wide | A08, A11 |
| ROI | PAR-021…023 | ROI Manager singleton consistency; headless impossible | A10, A15, P82 |
| Measurement | PAR-024…026 | Histogram and Profile open their own windows unless data APIs are used | P39, P82 |
| Macro and history | PAR-027…032, 051 | Focus-dependent user macros may differ; observed plugin actions may lack parameters | A12, P81, P82 |
| Discovery | PAR-033…035 | Shadowed labels; per-slice progress depends on hook validation | A08, A20 |
| Plugins | PAR-036…040, 049 | C4–C6 remain separate windows. C5 needs visible legacy image windows. Macro toolsets are deferred. | A13, A15 §7 |
| System | PAR-041, 042, 047, 048, 050, 052 | Updater integrity is upstream. Exit is recovered rather than supported. Legacy windows stay inaccessible and not HiDPI-correct. | A22, A25, A05 |
| New | PAR-043, 044 | Speech accuracy on human voices unverified; LLM optional | A16, A17 |

## Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A28-01 | "complete" targets slip but are still advertised | Medium | High | Advertise status only from verification results (D18 coverage CSV). The UI states the compatibility category (PR-9). |
| R-A28-02 | ROI Manager singleton consistency (PAR-022) proves harder than expected | Medium | High | Early validation spike in implementation milestone 1 (D20) |
| R-A28-03 | Users expect C5 plugins to work inside the modern canvas | High | Medium | Clear "opens in Fiji window" indication (FR-PLUG-001) |

## Change log

- 2026-10-17: Initial matrix by the lead (52 rows).
