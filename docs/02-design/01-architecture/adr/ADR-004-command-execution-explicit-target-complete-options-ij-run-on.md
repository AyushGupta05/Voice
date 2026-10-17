---
id: ADR-004
title: Command execution: explicit target, complete options, IJ.run on a single non-EDT worker
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-PROC-002, FR-PROC-003, FR-PROC-005, FR-PROC-006, FR-SYS-004, FR-IMG-002]
related_decisions: [DEC-021]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-004 — Command execution: explicit target, complete options, IJ.run on a single non-EDT worker

## Context
- `IJ.run(imp, label, options)` binds options and the temp image per thread, runs synchronously, and renames the thread `Run$_…` (A15 §6, P80).
- Missing keys fall back to remembered values (A11).
- `IJ.run` swallows exceptions (A08).
- Modifier-key flags are global (A19).
- IJ1's static state requires serialised access (A18).

## Decision (PROPOSED)
CMP-21 executes every state-mutating action on one dedicated worker thread, never the EDT.

IJ1 commands use route R1, `IJ.run(target, exactLabel, completeOptions)`. Before each call the worker:
1. validates against `CommandSchema` and the command's preconditions;
2. clears the modifier flags (`IJ.setKeyUp(IJ.ALL_KEYS)`);
3. applies recorded globals, when replaying;
4. takes a snapshot (ADR-008).

Success is decided by per-family postconditions plus inspection of the log and of dialogs (CMP-26).

## Alternatives considered
| Option | Reason not chosen |
|---|---|
| `ModuleService.run(info, true)` for IJ1 commands, as fiji-llm does | No typed inputs; parameters come from dialogs; `CommandListener` does not see these runs (P80) |
| Calling plugin classes directly | Bypasses the macro-option contract |

## Consequences
Every supported command needs a schema; the core set already has one (`commands-core-parameters.csv`). Commands without a schema run with the original dialog surfaced to the user (FR-PROC-007).
