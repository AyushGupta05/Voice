---
id: ADR-003
title: History is the source of reproducibility; macros are generated from it
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-HIST-001, FR-HIST-002, FR-HIST-003, FR-HIST-008, FR-HIST-009, FR-MACRO-006]
related_decisions: [DEC-020]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-003 — History is the source of reproducibility; macros are generated from it

## Context
The IJ1 Recorder loses information (P81, A10, A12):
- it skips programmatic calls, because of thread-name gating;
- it drops the options of preview dialogs;
- it records ROIs as integers;
- it records `setAutoThreshold` only as a comment.

Users still need standard `.ijm` output (PR-6).

## Decision (PROPOSED)
- CMP-27 stores structured `HistoryRecord`s: the command, complete options, the explicit target, ROI geometry, a globals snapshot, the origin and the outcome.
- Macro text is **generated** from these records (D07), with:
  - complete keys;
  - globals set at the start;
  - unambiguous image addressing.
- Observed actions are captured through `CommandListener` and SciJava module events (ADR-011).
- The Recorder may be mirrored through `Recorder.recordString` for user familiarity. It is never authoritative.

## Alternatives considered
| Option | Reason not chosen |
|---|---|
| Use the Recorder as the source | Lossy (P81) |
| Patch the Recorder | Modifies upstream code |

## Consequences
The macro generator must be verified by replay (E7, NFR-FID-001). Some direct manipulations cannot be expressed as macro text; they are labelled as such (FR-HIST-003).
