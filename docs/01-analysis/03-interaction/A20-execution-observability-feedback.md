---
id: A20
title: Execution Observability and Feedback
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p (03382be1), imagej/ij1-patcher@ij1-patcher-2.0.0 (70e570dc), imagej/imagej-legacy@imagej-legacy-3.0.0 (fc31a563)]
related_requirements: []
related_decisions: []
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A20 — Execution Observability and Feedback

## Executive Summary

The product must show real operations taking effect and never fake them (PR-5).

Inside a persistent Fiji JVM these signals are **genuinely observable**:

- command start (`CommandListener`, IJ1 paths only);
- module start and finish (SciJava events, IJ2 and `ModuleService` paths);
- image updates (`ImageListener.imageUpdated`, asynchronous on the EDT);
- ROI changes (`RoiListener`);
- log lines (legacy hook `log`);
- completion of synchronous `IJ.run` calls (the call returns);
- per-slice progress of stack filters (`IJ.showProgress`, reachable only through a custom legacy hook).

These are **not observable**:

- progress inside a 2D filter pass or inside plugins that never report it;
- changes to Results tables (no listener exists);
- the parameters of actions taken inside custom plugin windows;
- success or failure of `IJ.run`, which swallows plugin exceptions.

The design must therefore combine events with **post-action state digests** and **postcondition checks** (A18 CR-A18-04/05). It must show indeterminate progress honestly where no measure exists.

## Purpose and Scope

This document covers which execution signals exist, on which threads, and with what fidelity, and which feedback the UI can truthfully show. Visual treatment is D15. Event contracts are D09.

## Verified Findings

| Signal | Source API | Thread / timing | Covers | Gaps | Evidence |
|---|---|---|---|---|---|
| Command about to run | `Executer.addCommandListener` → `commandExecuting(cmd)`; `Macro.getOptions()` readable | Caller's thread, synchronous, before execution | Menu, `IJ.doCommand`, `IJ.run`, macro `run()` | Macro built-ins, IJ2 `CommandService`, legacy via `ModuleService`. No completion event. | **VERIFIED** P31, P80; `ij/Executer.java#L54-L61` |
| Module lifecycle | SciJava `EventService`: `ModuleStartedEvent`/`ModuleFinishedEvent`/`ModuleExecutedEvent` (names per P80) | EDT delivery | IJ2 commands, `ModuleService` legacy runs, `ScriptService` `.ijm` | Not `IJ.run` paths | **VERIFIED** P80 |
| Image changed | `ImagePlus.addImageListener` → `imageUpdated` | Asynchronous on the EDT (`EventQueue.invokeLater`, `ij/ImagePlus.java#L3043-L3048`) | All paths that call `updateAndDraw`/`changes` (P80 table) | Headless: no open or close events, `updateAndDraw` is a no-op (P32). Ordering against the worker's completion is not guaranteed. | **VERIFIED** P32, P80 |
| ROI changed | `Roi.addRoiListener` → CREATED/MOVED/MODIFIED/EXTENDED/COMPLETED/DELETED | Synchronous on the caller's thread | Interactive and API ROI changes | `Roi.setLocation` fires nothing | **VERIFIED** `ij/gui/RoiListener.java#L9-L14`; P80 |
| Tool, colour, log-window events | `IJ.addEventListener` | Synchronous | Tool changes, colours | — | **VERIFIED** `ij/IJEventListener.java#L9-L13`; P80 |
| Progress | Patched `IJ.showProgress(...)` calls `IJ._hooks.showProgress(...)` at the end of the method | Caller's thread | Stack filters (`PlugInFilterRunner` reports per slice), any plugin calling `showProgress` | `DefaultLegacyHooks` does not override `showProgress`/`showStatus` (only `log`), so nothing is forwarded by default. A custom hook would have to be chained through `LegacyInjector.installHooks(loader, hooks)`, which returns the previous hooks. | **VERIFIED** (source): `ij1-patcher@2.0.0 LegacyInjector.java#L177-L185, L443-L451`; `imagej-legacy@3.0.0 DefaultLegacyHooks.java` (overrides `log` at L213; no `showProgress`). Hook chaining is **INFERRED** feasible and not probed. |
| Status text | `IJ.showStatus` → `_hooks.showStatus` | As above | Status messages such as "Gaussian Blur: 0.2 s", "INTERRUPTED" | Same as progress | Same |
| Log | `IJ.log` → `_hooks.log` (overridden by `DefaultLegacyHooks.log`) | Caller | Plugin log messages, swallowed exceptions | — | **VERIFIED** `LegacyInjector.java#L195-L197` (line reference corrected in Phase 8, finding 32); `DefaultLegacyHooks.java#L213` |
| Completion | `IJ.run` returns (synchronous on the worker; A15 §6). A module's `Future` completes. | Worker | Product-initiated actions | **No failure signal** from `IJ.run`. `Executer` catches `Throwable` and logs it (A08; `Executer.java#L74-L117`). *Correction (Phase 8, finding 7): `Executer` logs the exception only when `IJ.getInstance()==null` (headless); in a GUI JVM (M2) it opens an "Exception" `TextWindow` instead (`Executer.java#L113-L117`; D17 §3.6).* | **VERIFIED** |
| Results changed | none | — | — | No `ResultsTable` listener; poll the row count and hash after actions (P80) | **VERIFIED** |
| Blocking dialog | `Window.getWindows()` / `GenericDialog.getInstance()` scan | Any thread | Modal dialogs opened by commands without options | `WindowManager.getWindow` does not list dialogs | **VERIFIED** P83 |
| Pixel content change | Compare a hash or sample before and after (fiji-llm uses bounded SHA-256) | Worker | Any change, including plugins mutating images directly | Cost scales with size (A23) | INFERRED; fiji-llm precedent |

### Timing data relevant to feedback

- `getBufferedImage` at 2048² takes 1.7 / 12.6 / 15.1 ms (8/16/32-bit) (P84). An updated plane can be re-rendered within a frame budget of about 16 ms for 8-bit and close to it for 16/32-bit (INFERRED). Larger images need tiled or viewport rendering (A23).
- Speech feedback: warm transcription takes 0.18–0.21 s for a 1.94 s clip (P60), so a transcript can be displayed within about 0.5 s after the user stops speaking with a persistent worker (A16 CR-A16-03).
- Engine start: 4.5–5.3 s to IJ1 ready (P85). Start-up needs an honest "engine starting" state.

## Technical Details

### 1. Feedback truthfulness classes (PROPOSED)

| Class | Meaning | When used |
|---|---|---|
| **Determinate** | Fraction complete from a real counter | `showProgress(i, n)` captured through a chained hook (if validated), or the product's own loop over slices or files |
| **Stage-based** | Discrete real stages | Multi-step plans and macro replays: step k of n completed, from worker completion |
| **Indeterminate (honest)** | Running, no measure available | 2D filter passes, plugins without progress, IJ2 modules without status |
| **Observed (after the fact)** | An action happened outside product control | `CommandListener`, an `imageUpdated` without a product action, a digest difference |

There must be no synthetic progress: no time-based percentages and no animations implying progress that is not measured (brief §1.4, D15).

### 2. Post-action digest (with A18)

After every action, the engine computes and publishes:

- image IDs, titles, types, dimensions and `changes` flags;
- for affected images only, a pixel hash or sampled hash, plus the display range and LUT identity;
- ROI, Overlay and ROI-list counts and the active ROI type;
- Results row counts per table;
- the hash of the result-affecting globals (A11 §6) and the delta against the previous action;
- new log lines;
- pending modal dialogs.

This is the basis for UI refresh, history provenance and postcondition checks.

### 3. What the UI can truthfully show for typical operations

| Operation | Before | During | After |
|---|---|---|---|
| 2D filter (Gaussian) | Parameter preview on a copy (CR-A11-06) | Indeterminate. Typically fast (no timing claim; benchmark per command in D18). | Updated plane; history entry with the exact macro line; undo snapshot |
| Stack filter | Same, plus a "cannot undo" warning when AC4 (A19) | Determinate per slice, if the hook is validated. Otherwise indeterminate. Escape-based cancel; partial-result warning (A19). | Updated stack; partial flag if cancelled |
| Measure | The measurement set and globals visible (A11) | — | New Results rows at full precision (CR-A11-04) |
| Analyze Particles | Threshold state shown; warning if absent (CR-A11-07) | Indeterminate | Results, ROI list and the output image (if any), from the digest |
| Plugin with its own window (C4–C6) | "Opens a Fiji window" notice | The plugin window is tracked and brought to the front | Observed actions; digest differences; partial-provenance label |
| NL/voice instruction | Transcript, then plan preview (A17) | Per step: stage-based | Per-step history entries |

## Requirement implications

- **CR-A20-01** — The UI shall show progress as determinate only when it comes from a real counter. It shall otherwise show an indeterminate state. It shall never show time-estimated or simulated progress. *Rationale:* PR-5; brief §1.4.
- **CR-A20-02** — The engine shall publish lifecycle events (queued, started, progress if available, completed, failed, cancelled) for every action, each carrying an operation identifier, and a post-action digest (§2). *Rationale:* §1, A18.
- **CR-A20-03** — The engine shall capture IJ1 progress and status reports by chaining a legacy hook that delegates to the existing hooks, *or* the design shall state that per-slice progress is unavailable. *Validation required:* probe hook chaining (D04). *Rationale:* `DefaultLegacyHooks` forwards neither.
- **CR-A20-04** — The engine shall detect a modal dialog opened during an action within a bounded time, and surface it in the UI as "waiting for input in a Fiji dialog". *Rationale:* P83; CR-A15-02.
- **CR-A20-05** — Changes to scientific state not caused by a product action (plugins, legacy windows, user macros) shall appear in history as observed changes with whatever detail is available. *Rationale:* §1 Observed class; A18.
- **CR-A20-06** — The engine-start state (JVM loading, Fiji initialising, ready) shall be shown truthfully. Commands shall be refused with an explanatory message until the engine is ready. *Rationale:* P85 (4.5–5.3 s).
- **CR-A20-07** — Failures shall be determined from postconditions, error log lines and swallowed-exception detection. Each shall be reported with the command, its parameters and the captured log excerpt. *Rationale:* `IJ.run` gives no failure signal.

## Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A20-01 | Hook chaining interferes with imagej-legacy behaviour | Low | Medium | Delegate every method; probe before adoption (CR-A20-03) |
| R-A20-02 | Asynchronous EDT `imageUpdated` events arrive after the worker reports completion, causing a stale render | Medium | Low | Render from the digest after completion; treat events as hints |
| R-A20-03 | Pixel hashing large images per action is slow | Medium | Medium | Hash only affected planes; sample hashing for very large data (A23) |

## References

- `imagej/ImageJ@v1.54p`: `ij/Executer.java`, `ij/ImagePlus.java#L3043-L3048`, `ij/gui/RoiListener.java`, `ij/IJEventListener.java`, `ij/plugin/filter/PlugInFilterRunner.java#L161-L200`.
- `imagej/ij1-patcher@ij1-patcher-2.0.0`: `LegacyInjector.java#L170-L200, L443-L451`, `LegacyHooks.java#L140-L165`.
- `imagej/imagej-legacy@imagej-legacy-3.0.0`: `DefaultLegacyHooks.java`, `LegacyService.java#L452-L466`.
- Probes P31, P32, P60, P80, P83, P84, P85. Documents A15, A18, A19.

## Change log

- 2026-10-17: Initial draft by the lead.
- 2026-10-17: Phase 8 review fixes (findings #7, #32).
