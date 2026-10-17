---
id: A19
title: User Control, Confirmation and Error Recovery
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p (03382be1)]
related_requirements: []
related_decisions: [DEC-007]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A19 — User Control, Confirmation and Error Recovery

## Executive Summary

Fiji gives the user control through immediacy. Most commands either run at once or open a modal dialog, and the result can be inspected straight away. Its safety nets are thin:

- one static undo slot, reset whenever another image is activated;
- a "Save changes?" prompt driven by `ImagePlus.changes`;
- cooperative cancellation through the Escape flag.

Cancellation is checked only *between* stack slices. Cancelling a stack filter therefore leaves the stack **partly processed** (`ij/plugin/filter/PlugInFilterRunner.java#L200`, `#L163-L171`). This is **VERIFIED** by source.

Three further hidden inputs can change what a command does without the user seeing it:

- process-wide modifier-key flags, read in 40 IJ1 source files;
- dialog values remembered in static fields (A11);
- global preferences (A11 §6).

The modernised product must therefore do two things:

1. Classify every action by its consequence, and apply one confirmation policy across all input modalities. Typed natural-language and speech input get stricter treatment than direct manipulation (A17).
2. Provide its own recovery mechanisms (snapshot undo, a transactional wrapper around commands, and safe file handling) rather than relying on IJ1's.

## Purpose and Scope

This document defines analysis-level requirements for the following: classifying actions by consequence; when to confirm; undo and redo; cancellation; validation before execution; protecting files; permission to run code; and recovery from partial failure. The concrete UI flows belong to D15–D17, and the execution contracts to D06/D09.

## Sources and Evidence

- `imagej/ImageJ@v1.54p`: `ij/Undo.java`, `ij/IJ.java`, `ij/macro/Interpreter.java`, `ij/Macro.java`, `ij/plugin/filter/PlugInFilterRunner.java`, `ij/plugin/Duplicator.java`, `ij/plugin/filter/Filler.java`, `ij/ImagePlus.java`.
- Probe evidence: G2's P33 (undo in batch mode); G4's A04 §Undo (window switch resets undo, `WindowManager.java#L48`; clicking the main window resets undo, `ImageJ.java#L403-L404`).
- A11 (global state), A12 (macro capabilities), A17 (NL policy), A25 (threat model).

## Verified Findings

### 1. Existing IJ1 control mechanisms

| Mechanism | Behaviour at v1.54p | Evidence |
|---|---|---|
| Undo | One static slot. Types are `NOTHING`, `FILTER`, `TYPE_CONVERSION`, `PASTE`, `COMPOUND_FILTER(_DONE)`, `TRANSFORM`, `OVERLAY_ADDITION`, `ROI`, `MACRO` and `OVERLAY`. The slot is set by `Undo.setup(what, imp)` and cleared by `Undo.reset()`. | **VERIFIED** `ij/Undo.java#L12-L41, L107, L121` |
| Undo reset triggers | Activating another image or clicking the ImageJ main window. | **VERIFIED** (A04; `WindowManager.java#L48`, `ImageJ.java#L403-L404`) |
| Stack undo | Whole-stack filter operations are not undoable. | **VERIFIED** (A09/A11; `PlugInFilterRunner.java#L124-L126`; P33) |
| Unsaved-change tracking | `public boolean changes` on `ImagePlus`. Closing a window with `changes==true` prompts "Save changes?". | **VERIFIED** `ij/ImagePlus.java#L51, L472` |
| Cancellation (commands) | A global `IJ.escapePressed` flag, set by `IJ.setKeyDown(KeyEvent.VK_ESCAPE)` and reset by `Executer` at the start of each command. `PlugInFilterRunner` checks it after each stack slice and breaks the loop. A single 2D filter pass cannot be interrupted part-way. | **VERIFIED** `IJ.java#L1098-L1124, L2388-L2396`; `Executer.java#L46`; `PlugInFilterRunner.java#L200` |
| Cancellation outcome for stacks | After an interrupted stack pass, the runner shows "INTERRUPTED". It still sets `imp.changes=true` and redraws, so the slices processed before Esc stay modified. | **VERIFIED** `PlugInFilterRunner.java#L161-L171` |
| Cancellation (macros) | `Interpreter.abort()` / `abortMacro()` stops the running interpreter and leaves batch mode. `Macro.abort()` sets a flag, and throws only in threads whose names end with `Macro$`. | **VERIFIED** `Interpreter.java#L2102-L2130`; `Macro.java#L71-L78` |
| Image locking | `ImagePlus.lock()`, `lockSilently()` and `unlock()` stop concurrent commands on the same image. A busy image produces the familiar "locked" message. | **VERIFIED** `ImagePlus.java#L186-L242` |
| Modifier-key state | `IJ.altKeyDown()`, `shiftKeyDown()` and `controlKeyDown()` are process-wide flags, read by 40 files in `ij/`. Examples: Alt makes `Duplicator` skip its dialog (`Duplicator.java#L62`); Alt adds labels in `Filler.draw` (`Filler.java#L119`); Shift/Alt alter ROI Manager multi-actions (`RoiManager.java#L2228-L2229`). `IJ.setKeyUp(IJ.ALL_KEYS)` clears them. | **VERIFIED** (grep count, file#line) |
| Remembered dialog values | Many filters keep the last value in static fields. A key missing from a macro options string falls back to this remembered value. | **VERIFIED** (A11 §4, A17 §4) |

### 2. Consequences for a multi-modal UI

1. **INFERRED:** A user who works in the new UI and then performs an action that changes the IJ1 current image (or a hidden plugin does so) loses the IJ1 undo slot without noticing. IJ1 undo cannot be the product's undo model.
2. **INFERRED:** A command started programmatically while a modifier flag is stale (for example after a synthetic or lost key-up) can follow a different code path, such as skipping a dialog or changing a mode. The engine adapter must normalise modifier state before each command.
3. **VERIFIED by source:** cancelling a long stack operation is not atomic. Without a snapshot, "cancel" leaves an inconsistent dataset that looks like a completed result.
4. **INFERRED:** Spoken and typed instructions carry more risk of an unintended action than direct manipulation. A16 P61 recorded substitution errors, and A17 P65 recorded over-asking and placeholder values. The extra confirmation they need must not slow down mouse or keyboard use (PR-2 familiarity).

## Technical Details

### 3. Action consequence classes (PROPOSED)

| Class | Definition | Examples | Reversible by |
|---|---|---|---|
| **AC0 Read-only** | No change to data, view or preferences | Pixel inspection, histogram, reading Results | n/a |
| **AC1 View state** | Changes only display or navigation state | Zoom, pan, C/Z/T position, B&C sliders (display range), LUT choice before *Apply* | View history; trivial |
| **AC2 Selection/annotation** | Changes ROI, overlay or ROI Manager contents | Draw ROI, add to Manager, rename ROI | ROI history (product-level) |
| **AC3 Reversible data change** | Modifies pixels or metadata in memory; a snapshot fits within the memory budget | Filter on a 2D image or small stack, type conversion, crop | Product snapshot undo |
| **AC4 Data change too large to snapshot** | Like AC3, but the snapshot would exceed the budget, or the image is a virtual stack (writes go to disk-backed slices) | Filter on a large or virtual stack | Not undoable. Requires explicit confirmation. |
| **AC5 Result-affecting configuration** | Changes global options that alter later results | Set Measurements, Black background, conversion scaling, Appearance/Misc options | Settings history plus provenance |
| **AC6 Persistent / external effect** | Writes or deletes files, closes unsaved images, exports, or touches the network | Save/overwrite, Close with changes, Batch to folder, update site | Cannot be undone in-app |
| **AC7 Code execution** | Runs a user-provided or imported macro, script or plugin with full privileges | `.ijm`/`.py` from disk, `exec`, `eval`, `call`, AutoRun | n/a (A25) |

### 4. Confirmation policy by modality (PROPOSED)

| Modality | AC0–AC2 | AC3 | AC4 | AC5 | AC6 | AC7 |
|---|---|---|---|---|---|---|
| Mouse, keyboard, menu, dialog (direct) | Immediate, as in Fiji | Immediate. Undo available. | Inline warning in the dialog ("cannot be undone: N MB > budget"); requires OK | Immediate. The setting change is shown in history. | Fiji-equivalent prompt (Save changes? overwrite?) | Capability prompt (A12 CR-A12-07) |
| Command search (palette) | Immediate | Opens the command's dialog. Same as menu. | As above | As above | As above | As above |
| Typed NL / speech | Preview, then confirm. Auto-execute only AC0/AC1 when the plan is unambiguous (A17 §8). | Preview with all parameters, then confirm | Preview plus explicit "cannot be undone" | Preview; the global change is highlighted | Preview plus explicit target path | **Never** generated. Code is not produced from NL (CR-A17-11). |
| Macro replay (generated by the product) | Run | Run, with per-step snapshots where the budget allows | Confirm once before the run | Globals are set at the start (CR-A12-02) and shown in the preview | Confirm output locations once | n/a (generated macros contain only commands) |
| User-authored macro or script | — | — | — | — | — | Capability prompt per file version (hash) |

### 5. Undo and redo model (requirements basis)

- Per-document history. Each AC3 step stores a pre-image snapshot, its parameters and its globals snapshot. ROI (AC2) and view (AC1) changes have their own lightweight history.
- A memory budget, configurable and expressed as a fraction of the JVM heap (A23). Steps whose snapshot exceeds it are AC4 and are labelled before execution.
- "Undo" restores the snapshot through the Java API (`ImagePlus.setStack`/`setProcessor`, calibration and display range). It does not replay IJ1 `Undo`. IJ1's own undo slot is left as it is, for legacy windows that use it.
- Redo re-executes the recorded command with its full option map against the restored state. This is always available, because the action record is complete (CR-A12-01).

### 6. Transactional execution and partial failure

1. **Before** an AC3/AC4 command: validate the preconditions (A17 CR-A17-05); normalise the modifier flags (`IJ.setKeyUp(IJ.ALL_KEYS)`); apply the recorded globals; lock the image; take a snapshot if the budget allows.
2. **During:** cancellation sets the Escape flag (`IJ.setKeyDown(VK_ESCAPE)`) for IJ1 filters, `Interpreter.abort()` for macros, and `Future.cancel` / the module cancel API for SciJava modules (verified in A15/D04).
3. **After a cancel or failure:** if a snapshot exists, **restore it by default** and tell the user. If no snapshot exists (AC4), mark the document *partially processed*, with the slice range when it is known, and keep the history entry flagged.
4. **Lost runtime** (JVM crash or hang): the action history and input hashes (CR-A09-08, CR-A12-01) allow a session to be rebuilt by replay from the original files. In-memory edits that were never saved are lost. The UI must say so. It must not suggest that recovery is complete.

### 7. File safety

- By default the product never overwrites an input file. "Save" on an image opened from disk asks once per document, unless the user sets a preference. "Save As" is the default affordance for derived images.
- Exports (CSV, ROI `.zip`, `.ijm`, images) record their path and content hash in the provenance.
- Lossy saves (JPEG, PNG of a >8-bit image, RGB flattening of a composite) show a fidelity warning. The details are in A14.

## Requirement implications

- **CR-A19-01** — Every command in the catalogue shall be assigned an action consequence class (AC0–AC7). The class shall be computed from the command and the target state before execution. *Rationale:* §3. One policy for every modality.
- **CR-A19-02** — Instructions from typed natural language or speech shall execute AC2–AC6 actions only after the user confirms a preview showing the command, target and all parameters. They shall never generate AC7 actions. *Rationale:* PR-4, A17.
- **CR-A19-03** — Direct-manipulation and menu actions shall keep Fiji's immediacy. The product shall not add confirmation steps to AC0–AC3 for these modalities. *Rationale:* PR-2, A02 must-stay-familiar.
- **CR-A19-04** — The product shall provide multi-step undo and redo per image document for AC2 and AC3 actions, independent of the IJ1 `Undo` slot. It shall not be reset by switching documents. *Rationale:* §1 (single slot, reset on switch); UP list in A05.
- **CR-A19-05** — Before executing an action whose undo snapshot would exceed the configured budget, or which targets a virtual stack, the product shall state that the action cannot be undone and require confirmation. *Rationale:* AC4.
- **CR-A19-06** — If a command is cancelled or fails after partly modifying an image, the product shall restore the pre-command snapshot when one exists. Otherwise it shall mark the document as partially processed. *Rationale:* `PlugInFilterRunner.java#L161-L200`.
- **CR-A19-07** — Before invoking any IJ1 command programmatically, the engine adapter shall clear the IJ1 modifier-key state (`IJ.setKeyUp(IJ.ALL_KEYS)`), unless the action explicitly records a modifier as an input. *Rationale:* 40 IJ1 files branch on these global flags.
- **CR-A19-08** — Cancellation shall be available for every running operation. The UI shall state whether cancellation is cooperative and whether partial results are possible for that command family. *Rationale:* §1 cancellation semantics.
- **CR-A19-09** — The product shall not overwrite an original input file without an explicit, per-document user decision. It shall warn before any lossy save. *Rationale:* §7; scientific data integrity.
- **CR-A19-10** — Changes to result-affecting global settings (AC5) shall be recorded in history with their old and new values, whatever their origin, including a legacy dialog or a plugin. *Rationale:* A11 §6; reproducibility.
- **CR-A19-11** — After a runtime crash, the product shall offer to rebuild the session by replaying the recorded actions from the original inputs. It shall report which unsaved changes cannot be recovered. *Rationale:* §6.4.

## Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A19-01 | Snapshot undo memory pressure on large microscopy stacks causes out-of-memory errors | Medium | High | Budget, AC4 classification, virtual-stack detection (A23) |
| R-A19-02 | Legacy plugins change global settings or the current image outside the product's control, which defeats CR-A19-10 | High | Medium | Diff globals before and after each command (A20); flag unknown changes |
| R-A19-03 | Too much confirmation makes NL/voice slower than the menu, so users abandon it | Medium | Medium | Auto-execute AC0/AC1; batch confirmation for multi-step plans (A17 §8) |
| R-A19-04 | Escape-based cancellation is ignored by plugins that never check `IJ.escapePressed()` | High | Medium | CR-A19-08 messaging; JVM-level kill as a last resort, with session replay (CR-A19-11) |

## References

- `imagej/ImageJ@v1.54p`: `ij/Undo.java`, `ij/IJ.java#L1098-L1145, L2388-L2396`, `ij/Executer.java#L46`, `ij/plugin/filter/PlugInFilterRunner.java#L124-L200`, `ij/macro/Interpreter.java#L2102-L2130`, `ij/Macro.java#L71`, `ij/ImagePlus.java#L51, L186-L242, L472`, `ij/plugin/Duplicator.java#L62`, `ij/plugin/filter/Filler.java#L119`, `ij/plugin/frame/RoiManager.java#L2228`.
- A04, A09, A11, A12, A17, A25 in this repository.

## Change log

- 2026-10-17: Initial draft by the lead.
