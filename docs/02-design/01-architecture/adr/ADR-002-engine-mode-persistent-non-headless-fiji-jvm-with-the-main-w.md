---
id: ADR-002
title: Engine mode: persistent non-headless Fiji JVM with the main window hidden (M2)
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-SYS-001, FR-SYS-002, FR-SYS-005, FR-PLUG-002, FR-ROI-004, FR-IMG-007]
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-002 — Engine mode: persistent non-headless Fiji JVM with the main window hidden (M2)

## Context
Headless mode (M1) loses the ROI Manager, the Recorder, non-blocking dialogs, OME-TIFF export and plugin windows. It also truncates Measure results (P35, P39, P41, P46). M2 runs all of these (P42, P47, P82, P83). The prototype's per-run JVM costs 4.1–4.7 s per process (P01) and keeps no state between runs.

## Decision (PROPOSED)
- The engine is one persistent non-headless Fiji JVM per user session.
- The SciJava `Context` is created before any `ij.*` class loads (FR-SYS-002).
- The legacy main frame stays hidden.
- Images under product control are hidden or in batch mode, by explicit window policy (D04 §4).
- Headless processes are used only for verification reference runs and for batch steps that are VERIFIED headless-safe (FR-PROC-009).

## Alternatives considered
| Option | Reason not chosen |
|---|---|
| M1, one process per run | Loses state and plugin compatibility |
| M1, persistent | Loses plugin compatibility |
| M3, with the visible legacy UI | Brings back window sprawl, the problem being solved |

## Consequences
The engine must manage window visibility, dialogs and the shutdown hang: `System.exit` hangs in GUI mode (P85). A display is required, so headless servers are out of scope.

## Validation needed
- Behaviour on macOS and Linux (U-A15-4).
- Hook chaining (NFR-REL-004).
