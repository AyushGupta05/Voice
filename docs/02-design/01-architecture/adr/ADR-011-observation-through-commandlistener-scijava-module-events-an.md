---
id: ADR-011
title: Observation through CommandListener, SciJava module events and a chained legacy hook
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-HIST-008, FR-PROC-005, NFR-REL-004, NFR-REL-005]
related_decisions: [DEC-027]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-011 — Observation through CommandListener, SciJava module events and a chained legacy hook

## Context
- `CommandListener` sees `Executer` commands with their options and can veto them. It misses macro built-ins, IJ2 runs and `ModuleService` runs; module events cover these (P80).
- `ImageListener` fires asynchronously on the EDT.
- There is no Results listener.
- Progress and status reach only `IJ._hooks`, and `DefaultLegacyHooks` does not forward them (A20).

## Decision (PROPOSED)
CMP-25:
- registers a `CommandListener`;
- subscribes to SciJava module events;
- registers `ImageListener`, `RoiListener`, `IJEventListener` and an AWT window listener;
- installs a delegating `LegacyHooks`, chained through `LegacyInjector.installHooks`.

The hook chaining depends on a validation probe. If the probe fails, progress is reported as unavailable. CMP-26 computes the state digest after every action.

## Validation needed
- A hook-chaining probe (NFR-REL-004).
- Veto behaviour, tested against each plugin category (R-A18-02).
