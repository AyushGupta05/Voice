---
id: ADR-008
title: Undo by per-document snapshots, independent of IJ1 Undo
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-HIST-004, FR-HIST-005, FR-HIST-006, NFR-PERF-003]
related_decisions: [DEC-024]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-008 — Undo by per-document snapshots, independent of IJ1 Undo

## Context
IJ1 Undo is a single static slot. It is reset when the image changes and does not cover stacks (A19, P86). Cancelling a stack operation leaves it partially processed (A19).

## Decision (PROPOSED)
- Before each AC3 action, CMP-28 snapshots the target's pixel data and its display and calibration state, within a memory budget (NFR-PERF-003).
- Undo restores the snapshot through the API. Redo re-executes the recorded action.
- A cancelled or failed action triggers a rollback to the snapshot.
- AC4 actions (budget exceeded, or a virtual stack) require confirmation and are marked as not undoable.

## Consequences
Memory use grows with history depth; the budget bounds it. Plugin-internal state is outside undo, and the UI labels it so.
