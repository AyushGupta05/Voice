---
id: A26
title: Functional Requirements Specification
stage: 01-analysis
status: Baselined (awaiting approval)
priority: P0
source_revisions: [see source analysis documents]
related_requirements: [all FR-*]
related_decisions: [DEC-010, DEC-011, DEC-012]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A26 — Functional Requirements Specification

## Executive Summary

This document consolidates the 223 candidate requirements (`CR-Axx-nn`) raised in A01–A30 into **functional requirements (FR)**. Non-functional requirements are in [A27](A27-non-functional-requirements.md). Every FR has a stable ID, a statement, a rationale, a priority, acceptance criteria, its dependencies and the CRs it derives from. The mapping CR → FR/NFR is in §15 and is authoritative. Four conflicts between candidates were resolved, and the resolutions are recorded in §14.

Several FRs depend on the proposed architecture (DEC-010, A21): a persistent non-headless Fiji JVM with an in-JVM engine service. They are written so that they remain valid if O4 (an out-of-process UI) is chosen instead. Where wording assumes O2, the FR says so.

## Conventions

- **Priority** (F04 §3):
  - `MVP`: minimum viable demonstration of the architecture.
  - `HACK`: hackathon target.
  - `PARITY`: post-hackathon Fiji parity.
  - `LONG`: long-term.

  A31 assigns the delivery plan. The priority states what must be true for that tier to be claimed.
- **Acceptance criteria** name a verification method:
  - **T**: automated test;
  - **R**: reference-equivalence test (A30);
  - **I**: inspection or review;
  - **D**: demonstration.
- "Engine" means the product's in-JVM engine service (A21). "Catalogue" means `docs/data/commands.csv` together with `docs/data/commands-core-parameters.csv`, as built at runtime (FR-CMD-002).
- **Core set** means the 54 commands in `commands-core-parameters.csv`. **MVP set** means the 10 commands listed in A31 §3.

## 1. System and engine (SYS)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-SYS-001 | The system shall run the pinned Fiji as its scientific engine, in a persistent non-headless JVM for the whole user session, with the Fiji main window not shown. | ROI Manager, Recorder, non-blocking dialogs, OME-TIFF export and plugin windows fail headless and work in this mode | MVP | T: on start-up, `GraphicsEnvironment.isHeadless()==false`, `LegacyService.isActive()==true`, and the Fiji main frame `isVisible()==false`. D: the session persists across 20 consecutive commands without a JVM restart. | CR-A15-01, CR-A21-01, CR-A07-02 | — |
| FR-SYS-002 | The system shall create the SciJava `Context` (or call `LegacyInjector.preinit()`) before any `ij.*` class is loaded, and shall fail start-up with a clear message if the legacy layer is inactive. | Patch-order failure observed (P42 attempt 1) | MVP | T: a start-up self-test asserts `LegacyService.isActive()` and aborts otherwise. | CR-A07-02 | FR-SYS-001 |
| FR-SYS-003 | The system shall implement the engine as a UI-agnostic service in the Fiji JVM, with a documented Java API whose requests and events are serialisable. | Every architecture option needs an in-JVM component; keeps out-of-process clients possible | MVP | I: API documented in D09. T: every request and event type round-trips through JSON serialisation. | CR-A21-02, CR-A07-01 | FR-SYS-001 |
| FR-SYS-004 | All actions that mutate scientific state shall execute in order on a single per-session engine worker thread, which shall not be the AWT EDT. | Shared IJ1 statics; thread-confined macro options | MVP | T: concurrency test, in which 100 queued actions from three modalities execute in submission order on one named thread that is never the EDT. | CR-A18-02, CR-A09-07, CR-A12-09, CR-A15-02 | FR-SYS-003 |
| FR-SYS-005 | The system shall terminate the engine JVM through an explicit quit sequence (save or discard state as the user chooses, dispose the context, then a bounded halt) that does not depend on `System.exit` completing. | `System.exit` hangs in GUI mode (P85) | MVP | T: quit completes within a bounded time (D03 sets the bound) with no orphan `java`/`fiji` process. Prefs are saved when the user chose to keep them. | CR-A21-06, CR-A07-06, CR-A08-08 | FR-SYS-001 |
| FR-SYS-006 | The system shall show the engine's start-up state (starting, initialising Fiji, ready, failed) and shall refuse state-changing actions, with an explanation, until the engine is ready. | Start-up takes 4.5–5.3 s (P85) | MVP | T: actions submitted before ready are rejected with code `ENGINE_NOT_READY`. D: the states are visible during start-up. | CR-A20-06 | FR-SYS-001 |
| FR-SYS-007 | The system shall detect loss of the engine JVM (crash, plugin `System.exit`, hang beyond a watchdog limit) and shall offer recovery by replaying the recorded history from the original inputs, stating which unsaved changes are lost. | Plugins can terminate the JVM; `IJ.getImage()` exits when headless | PARITY | T: killing the engine process leads to a recovery prompt. Replay reproduces the history's final state (R, E1–E6). | CR-A15-08, CR-A19-11 | FR-HIST-001 |
| FR-SYS-008 | Product-managed Fiji launches shall disable the updater's automatic check, and shall not modify the Fiji installation except through an explicit, user-initiated Updater action. | Network egress; pin integrity | MVP | T: launch arguments include `-Dimagej.updater.disableAutocheck=true`. The lock check is identical before and after a session. | CR-A22-01, CR-A13-06, CR-A25-06 | — |
| FR-SYS-009 | The system shall be installable into an existing Fiji through a Fiji update site, without modifying or redistributing Fiji's own files. | Packaging and licence boundary (A21, A24) | PARITY | D: install into a fresh copy of the pinned Fiji through the update site. The lock check passes for all non-product files. | CR-A21-03 | DEC-010 |
| FR-SYS-010 | At start-up the system shall compare host Fiji core component versions with its declared supported versions, and shall warn on mismatch. | Users' installs differ from the pin | PARITY | T: a mocked version mismatch produces a warning listing the components. | CR-A22-03 | FR-SYS-009 |
| FR-SYS-011 | The system shall list the start-up auto-run sources in the managed Fiji (`RunAtStartup.ijm`, AutoRun macros, `macros/AutoRun/`, `scripts/Plugins/AutoRun/`) and shall allow them to be disabled for product-managed launches. | Auto-execution risk (A12, A25) | PARITY | T: a fixture install with an AutoRun macro shows it in the list, and it does not run when disabled. | CR-A12-08 | — |

## 2. Command catalogue, menus and discovery (CMD)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-CMD-001 | Every command in the installed Fiji shall be reachable through its original menu path and exact label, and through search. | Familiarity; tutorials and macros use labels | HACK (menu+search for core set); PARITY (all) | T: for every row of the runtime catalogue, the command is found by menu path and by a search for its label. D: a user follows an imagej.net tutorial path. | CR-A01-04, CR-A02-01, CR-A03-07, CR-A04-01 | FR-CMD-002 |
| FR-CMD-002 | The catalogue presented to users and to the NL layer shall be built at runtime from `Menus.getCommands()`, `ModuleService` and `ScriptService`, enriched with the static parameter schema. No separate plugin registry shall be maintained. | Fiji rewrites its command table at runtime | MVP | T: the runtime catalogue diff against `commands.csv` reports only explained differences. A newly installed plugin appears without code changes. | CR-A08-02, CR-A13-05 | FR-SYS-001 |
| FR-CMD-003 | Commands shall be addressed internally by catalogue `command_id`, resolving to the exact label including whitespace. Labels absent from the runtime table shall be rejected before execution. | 26 whitespace collisions; unknown labels abort or do nothing | MVP | T: executing `"Rotate... "` and `"Rotate..."` reaches the two different classes. An unknown label is rejected with `UNKNOWN_COMMAND`. | CR-A08-01 | FR-CMD-002 |
| FR-CMD-004 | The catalogue shall flag duplicate and shadowed labels and show the class that will actually run. | Silent rebinding (A08) | PARITY | T: the 10 known shadowed labels are flagged with their bound class. | CR-A08-10 | FR-CMD-002 |
| FR-CMD-005 | Each catalogue command shall carry a compatibility category (C1–C6/S) with its evidence, and an action consequence class (AC0–AC7), and both shall be shown in the UI. | Honest compatibility (PR-9); one confirmation policy | HACK (core set); PARITY (all) | T: every core-set command has both fields. I: evidence links to a probe or scan. | CR-A13-01, CR-A19-01 | FR-CMD-002 |
| FR-CMD-006 | Command search shall reuse the SciJava search infrastructure present in Fiji (`scijava-search`), or the design shall record why it cannot. | Reuse (PR-7) | HACK | I: D04/D14 records the decision. T: search returns the Fiji command for its label and its menu path. | CR-A06-03 | FR-CMD-002 |
| FR-CMD-007 | Default ImageJ keyboard shortcuts, user-installed macro shortcuts and the "Require control key" preference shall work unchanged when an image document has focus, with the IJ1 precedence (macro shortcuts, then menu shortcuts, then hard-coded keys). | Expert muscle memory | PARITY | T: each default shortcut in A04 §5.18 triggers its command. A user macro shortcut overrides a menu shortcut. Shift+letter stays distinct. | CR-A02-02, CR-A04-08 | FR-CMD-002 |

## 3. Command execution (PROC)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-PROC-001 | Every image-processing and measurement result shall be produced by invoking the original Fiji command or API in the engine. No substitute implementation shall be used. | PR-1 | MVP | R: E1–E6 equivalence for every supported command. I: code review finds no reimplemented algorithm. | CR-A01-01, CR-A03-01, CR-A11-01 | — |
| FR-PROC-002 | IJ1 commands shall execute through `IJ.run(ImagePlus target, label, options)` on the engine worker, with the target passed explicitly. Plugin classes shall never be called directly for user-visible commands. | Thread-confined options; explicit target | MVP | T: the execution log shows the worker thread name `Run$_…` and the explicit target ID for each IJ1 command. | CR-A13-02, CR-A15-02, CR-A11-01, CR-A09-01 | FR-SYS-004 |
| FR-PROC-003 | Options passed to a command shall contain every parameter key of its schema explicitly, including booleans and stack/slice scope. Execution shall never rely on remembered static values. | Missing keys fall back silently | MVP (MVP set); HACK (core set) | T: for each schema'd command, the generated option string contains every key. R: running after a "poisoned" interactive run with different remembered values still gives the reference result. | CR-A11-02, CR-A08-04, CR-A12-02, CR-A17-02 | FR-CMD-002 |
| FR-PROC-004 | Before executing a command, the engine shall validate its documented preconditions (image present, type, dimensionality, binary state, ROI required) and shall explain any failure in terms of the required state. | Silent wrong behaviour; `IJ.getImage()` exit hazard | MVP | T: each core-set command with preconditions is tested with a violating state and returns `PRECONDITION_FAILED` with an explanation. No JVM exit. | CR-A05-08, CR-A09-09, CR-A13-04 | FR-CMD-002 |
| FR-PROC-005 | Command success shall be determined by postconditions per command family (expected output image, pixel change, Results delta, no new error log lines, no pending modal dialog), not by the return of `IJ.run`. | `IJ.run` swallows exceptions | MVP | T: a fixture plugin that throws inside `run()` is reported as failed, with the log excerpt. | CR-A08-03, CR-A18-05, CR-A20-07 | FR-SYS-004 |
| FR-PROC-006 | The engine shall clear IJ1 modifier-key state before each programmatic command, unless the action records a modifier as an explicit input. | 40 IJ1 files branch on global modifier flags | MVP | T: with `IJ.setKeyDown(VK_ALT)` left set, `Duplicate...` still shows its option path (no dialog skip) and the result matches the reference. | CR-A19-07 | FR-PROC-002 |
| FR-PROC-007 | If a command opens a modal dialog during execution (no options, or an unrecognised key), the engine shall detect it within a bounded time and surface it to the user as "waiting for input in a Fiji dialog". It shall never leave the worker blocked invisibly. | Dialogs block the worker (P83) | HACK | T: running a dialog command with empty options is surfaced within the bound. The user's OK resumes it and the action is recorded. | CR-A15-02, CR-A20-04 | FR-SYS-004 |
| FR-PROC-008 | Multi-image commands (Image Calculator, Merge Channels and similar) shall run with their inputs registered so that `WindowManager` resolves them, using the macro interpreter with batch mode or an equivalent registration. | Bare `IJ.run` fails for these (A08 ops 20, 32) | HACK | R: Image Calculator and Merge Channels on fixtures match the reference. | CR-A08-07 | FR-PROC-002 |
| FR-PROC-009 | Commands whose headless classification is not `pass` shall run only in the GUI-capable engine, or through a documented macro-function substitute. | Headless failures (A08 §7) | MVP | I: the catalogue marks such commands. T: the batch runner rejects them in M1. | CR-A08-06, CR-A15-09 | FR-SYS-001 |
| FR-PROC-010 | The system shall offer live preview, on a copy of the image or plane and never mutating the user's image before confirmation, for the ImageJ commands that have an IJ1 preview (`ExtendedPlugInFilter` with `DialogListener`). | Preview is lost in macro mode; fidelity | HACK (Gaussian, Median, Subtract Background, Threshold); PARITY (all 12) | T: the image hash is unchanged while previewing. R: the confirmed result equals the reference run with the same parameters. | CR-A11-06 | FR-PROC-003 |
| FR-PROC-011 | ImageJ2 commands shall run with all inputs resolved by the UI before `ModuleService`/`CommandService.run`, or with a product input harvester registered. | Harvesting is bound to the visible UI | PARITY | T: an IJ2 command with `@Parameter` inputs runs from the product UI without a legacy dialog. | CR-A07-03, CR-A15-07 | FR-SYS-003 |
| FR-PROC-012 | ImageJ2 Ops shall not replace an IJ1 command unless an A30 parity test passes. | Separate implementations | MVP | I: the design lists no Op substitutions without a test reference. | CR-A11-08 | — |
| FR-PROC-013 | Cancellation shall be available for every running action. The UI shall state per command family whether cancellation is cooperative and whether partial results are possible. | Escape is checked only between slices | HACK | T: cancelling a 100-slice filter stops it and triggers FR-HIST-004. The UI text matches the family's metadata. | CR-A19-08, CR-A04-13 | FR-SYS-004 |
| FR-PROC-014 | User-visible display semantics shall be preserved. Brightness/contrast controls change only the display range. Applying a display change to pixels requires an explicit Apply, which warns before changing pixels and is recorded as its macro equivalent. | Display range ≠ data | HACK | T: moving B&C leaves the pixel hash unchanged. Apply changes pixels and records `run("Apply LUT", …)`. | CR-A04-03, CR-A05-04, CR-A09-03 | — |
| FR-PROC-015 | Thresholding shall be previewed as a non-destructive overlay. Committing shall use the original `setThreshold`/`Convert to Mask` path, with method, dark-background flag, numeric limits and BlackBackground recorded. | Reproducibility; hidden global | HACK | T: preview leaves pixels unchanged. R: the committed mask equals the reference. The history line contains all four values. | CR-A04-04, CR-A11-07 | FR-PROC-003 |
| FR-PROC-016 | The UI shall warn before Convert to Mask, Analyze Particles or binary operations run without an explicit threshold or foreground definition. | IJ1 silently auto-thresholds | HACK | T: running Convert to Mask on an unthresholded image shows the warning before execution. | CR-A11-07 | — |

## 4. Image documents, IO and metadata (IMG / IO)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-IMG-001 | The engine shall be the single authoritative store of image, display, calibration, ROI and Results state. UI views shall be refreshed from it after each action and shall never be the source of truth. | Avoid dual truth | MVP | I: D05 identifies the owner per object. T: an engine-side change by a plugin is reflected in the UI after the action digest. | CR-A09-02, CR-A07-04 | FR-SYS-003 |
| FR-IMG-002 | Every action shall carry an explicit target document. The UI's focused document shall be the default target, shown at all times. The engine shall never rely on IJ1 current-image or focus state. | Mis-targeted commands (A04, A05) | MVP | T: with two images open, a command issued while document B is focused modifies only B, even if IJ1's current image is A. | CR-A04-02, CR-A09-01, CR-A18-07 | FR-SYS-004 |
| FR-IMG-003 | Opening files shall follow Fiji's `File › Open` routing (`IJ.openImage`/`Opener` with Bio-Formats delegation), unless the user chooses the Bio-Formats importer, whose options (series, colour mode, autoscale, ROI import) shall be exposed. | Familiar semantics; metadata differences | MVP (TIFF/PNG); HACK (Bio-Formats importer) | R: E1/E3 equivalence of opened images with `open(path)` in reference Fiji for FX fixtures. | CR-A14-04 | — |
| FR-IMG-004 | For each opened image, the history shall record the path or URI, a content SHA-256, the reader used (IJ1, Bio-Formats with jar variant, SCIFIO) and key metadata (dimensions, type, calibration, units, frame interval). | Provenance | MVP | T: the history entry for an open contains all the fields for FX-001 and an OME-TIFF fixture. | CR-A14-05, CR-A09-08, CR-A24-07 | FR-HIST-001 |
| FR-IMG-005 | Saving shall default to the IJ1 TIFF writer and shall preserve pixels, dimensions, calibration, frame interval, composite mode, per-channel display ranges, overlay, ROI, Info and slice labels. | The only full-fidelity path (P43) | HACK | R: save then re-open round trip, E1/E3/E4 equal to the P43 reference. | CR-A14-01 | — |
| FR-IMG-006 | Before writing a lossy format (PNG, JPEG, GIF, BMP, 8-bit conversion, RGB flattening), the system shall show which properties will be lost. | Silent loss | HACK | T: saving a 16-bit calibrated composite as PNG shows the loss list before writing. | CR-A14-02, CR-A19-09 | — |
| FR-IMG-007 | OME-TIFF export shall run in the GUI-capable engine, and success shall be reported only after the output file is verified to exist and to be non-empty. | Headless exporter fails silently | PARITY | T: export of a fixture produces a readable OME-TIFF. A forced failure is reported as failed. | CR-A14-03 | FR-SYS-001 |
| FR-IMG-008 | The system shall not route IJ1 images through SCIFIO/`Dataset` conversion for saving unless a test shows that the frame interval and display ranges survive. | P43 losses | PARITY | I: the save paths listed in D04 have tests. | CR-A14-06 | — |
| FR-IMG-009 | By default the system shall refuse to write output to a path that is an input of the current session. Overwriting an input shall require an explicit per-document user decision, and input SHA-256 values shall be recorded before and after the session. | Data integrity (resolved conflict §14-C3) | MVP | T: Save over an input is refused by default. The override requires an explicit choice. The hashes are present in provenance. | CR-A19-09, CR-A25-07 | FR-IMG-004 |
| FR-IMG-010 | Before loading pixels, the system shall estimate the in-memory size from metadata. Above a configurable fraction of the heap it shall ask for confirmation or offer a virtual stack. | Out-of-memory protection | PARITY | T: a header fixture claiming 50 GB triggers the prompt without loading pixels. | CR-A25-14 | — |
| FR-IMG-011 | The system shall detect virtual stacks and global calibration and show them in the document status. | Hidden state | HACK | T: a virtual-stack fixture and a global-calibration toggle are both indicated. | CR-A09-06 | — |

## 5. Viewer and display (VIEW)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-VIEW-001 | Image planes shall be rendered with ImageJ's own rendering of LUT, display range, composite mode and overlay, or with a renderer proven equivalent by the A30 E3 tests. | Display fidelity | MVP | T: the rendered plane equals `ImagePlus.getBufferedImage()` for fixtures (8/16/32-bit, composite). | CR-A21-05 | FR-IMG-001 |
| FR-VIEW-002 | The viewer shall show, at all times, the legacy subtitle information: C/Z/T position (1-based), calibrated and pixel dimensions, bit depth, LUT inversion and memory size. | The only always-visible metadata in Fiji | MVP | T: for the 5D fixture (Mitosis) the status matches `ImageWindow.createSubtitle()` content. | CR-A04-06 | — |
| FR-VIEW-003 | The pixel readout shall report coordinates and values using the formatting rules of `ImagePlus.getLocationAsString`/`getValueAsString`, including calibrated and raw values. | Scientific fidelity | MVP | T: readouts at sampled points on calibrated 8/16/32-bit and signed-16 fixtures equal the IJ1 strings. | CR-A04-07 | — |
| FR-VIEW-004 | Zoom and pan shall be available. Multi-dimensional navigation (arrow keys, wheel, sliders) shall follow the legacy rules (channel by default; Ctrl/Space for slice; Alt for frame; ROI nudge when a selection exists). | Familiarity | HACK (zoom/pan/sliders); PARITY (full key rules) | T: key-event tests per A04 §5.6/§5.13. | CR-A04-11 | — |
| FR-VIEW-005 | The viewer shall cache rendered planes and re-render only when data, display range, LUT, composite mode, overlay or plane position changes. | Performance (A23) | HACK | T: zoom and pan without a data change produce no `getBufferedImage` call (instrumented). | CR-A23-01 | FR-VIEW-001 |
| FR-VIEW-006 | The UI shall explain in context whether a command acts on pixel data or on display, at least for B&C Apply, type conversion and binary operations, and shall show whether display-range scaling applies before a conversion. | Common user error (UP-06/07) | HACK | D: the explanations are shown. T: conversion of a 16-bit image shows the scaling state and records it. | CR-A02-07, CR-A09-03 | — |

## 6. Selections and ROIs (ROI)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-ROI-001 | ROI geometry shall be represented with 32-bit float vertices (or native IJ1 parameters for ellipse, rotated rectangle and rounded rectangle), transmitted to the engine without rounding, and constructed through the IJ1 Java API. | Sub-pixel fidelity; Recorder rounds | MVP (rectangle, polygon); HACK (oval, freehand, line, point); PARITY (all types) | T: a drawn polygon with sub-pixel vertices round-trips with identical float coordinates. E4 mask equality against reference. | CR-A10-01, CR-A10-02 | FR-SYS-003 |
| FR-ROI-002 | Hit-testing and drawing shall implement the IJ1 area (pixel-corner) and line (pixel-centre) conventions. Masks predicted by the UI shall equal `Roi.getMask()` on fixtures. | Identical geometry implies identical measurements | MVP | T: mask comparison on FX-070-series ROI fixtures. | CR-A10-03 | FR-ROI-001 |
| FR-ROI-003 | ROI tools shall implement the legacy modifier semantics: Shift add/constrain, Alt subtract/aspect, Ctrl centre, Alt/Ctrl-click to delete a vertex, Shift-click to add a vertex, with the tool restrictions of `ImageCanvas.setRoiModState`. | Familiarity and fidelity | HACK (Shift/Alt add/subtract); PARITY (all) | T: scripted pointer sequences produce ROIs equal to IJ1's for the same gestures. | CR-A04-09, CR-A10-04 | FR-ROI-001 |
| FR-ROI-004 | The system shall provide an ROI list bound to its image, backed by the engine (hidden `RoiManager(false)` or equivalent). It shall be kept consistent with `RoiManager.getInstance()` for commands and macros that use the singleton, preserve index order, and remain addressable by `roiManager(...)` macro functions. | Macro compatibility; per-image binding | HACK | T: adding ROIs in the UI, then `roiManager("count")` in a macro, returns the same count. `roiManager("select", i)` selects the same ROI. | CR-A04-10, CR-A05-05, CR-A10-06, CR-A15-04 | FR-SYS-001 |
| FR-ROI-005 | ROI sets shall be read and written in the IJ1 `.roi`/`.zip` format through `RoiEncoder`/`RoiDecoder`, passing a round-trip test for every ROI type, sub-pixel geometry, C/Z/T positions, names, groups and properties. | Interchange | HACK | T: round-trip suite (E4). | CR-A10-05 | FR-ROI-001 |
| FR-ROI-006 | Point ROIs with a single stack position shall be stored with explicit positions, or the position loss on `.roi` round trip shall be flagged. | Known loss (P35) | PARITY | T: the point-ROI fixture round trip either preserves the position or shows the flag. | CR-A10-07 | FR-ROI-005 |
| FR-ROI-007 | The system shall not use `Roi.containsPoint` for sub-pixel ROIs. | 1.54p defect | MVP | I: a static check (grep or ArchUnit) in CI. | CR-A10-08 | — |

## 7. Measurements and results (MEAS)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-MEAS-001 | Before each measurement or binary operation, the engine shall capture the result-affecting globals (`Prefs.blackBackground`, measurement flags, redirect, precision, conversion scaling, `calibrateConversions`, RGB weights, `padEdges`, binary iterations/count, `useInvertingLut`, global calibration). It shall record them with the result and be able to restore a recorded state before replay. | Hidden global state | MVP (capture+record); HACK (restore) | T: the history entry contains all fields. R: replay after changing the globals restores them and reproduces E5. | CR-A11-03, CR-A05-01, CR-A08-05 | FR-HIST-001 |
| FR-MEAS-002 | The UI shall show, before execution, the target image and every global option that affects the command's result (including BlackBackground, the measurement set with Limit and Redirect, conversion scaling and the C/Z/T position). | Visible state (PR-2/PR-5) | HACK | D/T: the parameter panel for Measure, Convert to Mask and 8-bit lists the relevant globals with their current values. | CR-A05-01, CR-A03-02, CR-A08-05 | FR-MEAS-001 |
| FR-MEAS-003 | Measurement values shall be held and exported at full double precision. Rounding shall be presentation only. | CSV rounding (P37) | MVP | T: exported CSV values equal API doubles (round trip with `Double.toString`). | CR-A11-04, CR-A30-03 | — |
| FR-MEAS-004 | Measurements shall accumulate correctly across repeated Measure calls, without depending on IJ1 system-table display behaviour. | Headless truncation (P39) | MVP | T: 20 sequential Measure calls produce 20 rows identical to the reference. | CR-A11-05 | FR-SYS-001 |
| FR-MEAS-005 | The system shall keep the standard ImageJ Results table populated for macro compatibility, with unchanged column names, while presenting results bound to their source image in the UI. | Macro compatibility | HACK | T: `nResults` and `getResult("Mean", i)` in a macro after UI measurements equal the UI values. | CR-A02-03, CR-A05-05 | FR-MEAS-004 |

## 8. History, undo, recording and provenance (HIST)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-HIST-001 | The system shall keep an always-on per-session history of structured actions (command or operation, complete option map, explicit target, ROI geometry, globals snapshot, origin modality, timestamps, outcome), whatever the input modality. | Reproducibility; Recorder lossiness | MVP | T: every executed action in a scripted multi-modal session appears with all fields. | CR-A01-02, CR-A05-02, CR-A12-01, CR-A03-03, CR-A25-11 | FR-SYS-003 |
| FR-HIST-002 | The history shall export valid ImageJ macro text generated from the structured record. It shall use exact labels and complete keys, set result-affecting globals at the start, address images unambiguously without relying on focus, and never fabricate options that were not applied. | PR-6 | MVP | R: E7, in which the exported macro run in classic Fiji on the original inputs reproduces E1–E6 for every HACK journey. | CR-A04-12, CR-A12-02, CR-A12-03, CR-A03-04, CR-A30-06 | FR-HIST-001 |
| FR-HIST-003 | Direct-manipulation actions (ROI edits, display range, LUT, C/Z/T position) shall be recorded. Exact macro equivalents shall be emitted where they exist, and the entry shall be marked *not macro-expressible* otherwise. | PR-5/PR-6 | HACK | T: ROI polygon edits generate a float `makeSelection`. A composite-ROI operation shows the marker. | CR-A18-06 | FR-HIST-001 |
| FR-HIST-004 | The system shall provide multi-step undo and redo per image document for ROI and reversible data changes (AC2/AC3), independent of the IJ1 `Undo` slot and not reset by switching documents. Undo shall restore a snapshot and redo shall re-execute the recorded action. | Single-level IJ1 undo | HACK (5 levels, 2D); PARITY (budgeted, stacks) | T: 5 actions on image A, then a switch to image B and back, then 5 undos restore the original hash. | CR-A05-03, CR-A09-04, CR-A19-04 | FR-HIST-001 |
| FR-HIST-005 | Before an action whose snapshot would exceed the configured budget, or that targets a virtual stack, the system shall state that it cannot be undone and require confirmation. | AC4 | HACK | T: a budget set to 1 MB with a 2 MB image triggers the confirmation. | CR-A19-05, CR-A23-05, CR-A05-03 | FR-HIST-004 |
| FR-HIST-006 | If a command is cancelled or fails after partly modifying an image, the system shall restore the pre-command snapshot when one exists. Otherwise it shall mark the document as partially processed (with the slice range if known). | Partial stack processing | HACK | T: cancelling mid-stack restores the original hash, or shows the partial flag when the snapshot is disabled. | CR-A19-06 | FR-HIST-004, FR-PROC-013 |
| FR-HIST-007 | Changes to result-affecting global settings shall be recorded with their old and new values, whatever their origin (product, legacy dialog, plugin, macro). | AC5 reproducibility | HACK | T: changing Black background in a legacy Options dialog produces a history entry. | CR-A19-10, CR-A18-04 | FR-HIST-001 |
| FR-HIST-008 | Commands and modules executed in the JVM that the product did not initiate (legacy windows, plugins, user macros) shall be recorded as *observed* actions, captured through `CommandListener` and SciJava module events, with their options or inputs where available and a partial-provenance label otherwise. | Single history (PR-3) | HACK | T: running a command from a legacy plugin window produces an observed entry with its options. A plugin that mutates pixels directly produces an "observed change" entry from the digest. | CR-A18-03, CR-A20-05, CR-A12-04, CR-A15-05 | FR-SYS-003 |
| FR-HIST-009 | The system shall not use the IJ1 Recorder as the source of truth for history. If the Recorder is used, it shall run hidden with `recordInMacros` and its output shall be cross-checked against the history. Exact lines may be emitted to a visible Recorder with `Recorder.recordString`. | Recorder loss (P81) | HACK | I: D07 records the decision. T: the cross-check flags a deliberately lossy Recorder line. | CR-A15-05, CR-A08-09 | FR-HIST-001 |
| FR-HIST-010 | Saved workflows shall reference images by path/URI and content hash, not by session ID or title. | Portability | HACK | T: a saved workflow re-opened in a new session resolves its inputs by hash. | CR-A09-08, CR-A03-04 | FR-HIST-001 |

## 9. Macros and scripts (MACRO)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-MACRO-001 | The system shall run existing ImageJ macros and recorded `run()` strings through the original interpreter, with unchanged semantics and outputs, including Results column names. | Compatibility (PR-2) | MVP | R: a corpus of FX-050/051 macros gives E1–E6 equal to classic Fiji. | CR-A02-03, CR-A03-01 | FR-SYS-001 |
| FR-MACRO-002 | User-authored or imported macros and scripts shall run only after explicit user confirmation that names their capabilities (file, process, network, reflection, engine access). Text containing `exec`, `eval`, `call`, `runMacro`, `File.delete/rename/copy/append/saveString` or `File.openUrlAsString`, detected with `ij.macro.Tokenizer`, shall require a separate confirmation naming the function. | Full-privilege code | HACK | T: a fixture macro containing `exec("cmd")` triggers the named confirmation, and cancelling prevents execution. | CR-A12-07, CR-A25-04 | — |
| FR-MACRO-003 | The system shall never execute a macro or script as a side effect of opening, previewing or importing it. | T-08 | MVP | T: opening and previewing a fixture macro causes no execution (sentinel file not created). | CR-A25-05 | — |
| FR-MACRO-004 | Scripts with `#@` parameters shall run through SciJava `ScriptService`, never through the IJ1 `-macro` path. The silent failure mode shall be detected. | P36 | PARITY | T: the `#@` fixture runs with parameters, and the `-macro` route is never used for it. | CR-A12-06 | — |
| FR-MACRO-005 | Only one IJ1 macro interpreter shall run at a time per engine. UI-initiated commands shall be serialised while a macro runs. | Static interpreter state | MVP | T: submitting a UI command during a macro queues it until the macro completes. | CR-A12-09 | FR-SYS-004 |
| FR-MACRO-006 | Generated macros shall pass user-controlled strings only through one escaping function verified against ImageJ's tokenizer (quotes, backslashes, newlines, non-ASCII). | Injection (T-23) | MVP | T: a fuzz test of titles and paths through the generator and tokenizer shows no token-boundary changes. | CR-A25-15 | FR-HIST-002 |
| FR-MACRO-007 | The system shall offer "test on one image, then run on a folder" execution of a recorded workflow, showing the overwrite and skip plan before starting and never overwriting files silently. Headless (M1) batch execution shall be used only when every step is VERIFIED headless-safe, and each step's side effects shall be checked. | UP-11; silent headless failures | PARITY | R: a folder of 3 fixtures matches 3 reference runs. The plan screen lists outputs. A non-headless-safe step forces GUI-engine execution. | CR-A03-06, CR-A05-06, CR-A06-04, CR-A15-09 | FR-HIST-002 |

## 10. Plugins and legacy windows (PLUG)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-PLUG-001 | Plugins in categories C4–C6 (own windows, canvas-interactive, native 3D) shall run unchanged through a legacy-window fallback. Their native windows shall be shown, brought to the front, associated with the originating step, and indicated in the UI as external. | Compatibility without reimplementation | HACK (one C4 + one C5 example); PARITY (category-wide) | D: TrackMate (C4) and Cell Counter (C5) launch from the product, function, and appear in the window tracker. | CR-A02-06, CR-A03-05, CR-A04-14, CR-A13-03, CR-A15-06 | FR-SYS-001 |
| FR-PLUG-002 | The system shall apply an explicit window policy: images under product control are hidden or in batch mode, and any IJ1 or plugin window that becomes visible is detected and tracked. | Windows are not hidden automatically in M2 | MVP | T: opening Histogram, Results, ROI Manager and a C4 plugin produces tracker entries. No untracked visible Fiji window remains. | CR-A15-03 | FR-SYS-001 |
| FR-PLUG-003 | The engine shall be able to hold or veto IJ1 commands through the `CommandListener` chain when policy requires confirmation, for example AC7 code started from a legacy window. | Policy enforcement | PARITY | T: running a user macro from a legacy menu path triggers the capability confirmation (FR-MACRO-002). | CR-A18-08 | FR-HIST-008 |

## 11. Natural-language commands (NL)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-NL-001 | Every typed or spoken instruction shall resolve, before anything executes, to zero or more steps, each naming a runtime-catalogue command with typed parameters and an explicit target. | Grounding (PR-1, PR-4) | MVP | T (E8): on the labelled set, 100 % of executed steps reference catalogue commands. | CR-A17-01, CR-A06-02, CR-A18-01 | FR-CMD-002 |
| FR-NL-002 | Each plan shall be validated by deterministic code against the catalogue schema (type, range, enumeration per target image) and a live-state snapshot, and shall be re-validated immediately before each step executes. | Model errors (P65); state drift | MVP | T: plans with out-of-range values, placeholder values or stale targets are rejected. A state change between preview and execution triggers re-validation. | CR-A17-05, CR-A17-13 | FR-NL-001 |
| FR-NL-003 | Parameters flagged consequential in the catalogue shall never be filled from a hidden default. They shall be stated by the user or accepted explicitly from a displayed choice. | Hidden defaults change results | MVP | T: "threshold the image" without a method leads to a clarification listing methods, with no silent default. | CR-A17-03 | FR-NL-002 |
| FR-NL-004 | The plan preview shall show the target image (title and ID), C/Z/T scope, ROI, each parameter with its unit, the equivalent menu path and the exact macro text. Data-changing steps (AC2–AC6) shall execute only after confirmation. AC0/AC1 steps may auto-execute when unambiguous. | User control; teaching | MVP | D/T: the preview contains all fields. No AC2+ execution occurs without a confirm event. | CR-A01-03, CR-A02-04, CR-A17-04, CR-A19-02 | FR-NL-002 |
| FR-NL-005 | Deictic references ("this image", "the selection", "channel N", "the last result") shall resolve by documented rules against the focused document and session state, and shall trigger a clarifying question when more than one candidate exists. | Ambiguity | HACK | T: labelled ambiguous cases produce clarification. Unambiguous cases resolve correctly. | CR-A17-06, CR-A18-07 | FR-NL-001 |
| FR-NL-006 | The confirm, clarify or refuse decision shall be made by deterministic policy code from the validator result, never by a language model. | P65 over-asking and placeholders | MVP | I: code review. T: the policy unit tests cover each validator outcome. | CR-A17-07 | FR-NL-002 |
| FR-NL-007 | The NL path shall never execute model-generated macro or script code, and shall never generate AC7 actions. | Macro language is full-privilege | MVP | T (E8): zero unsafe executions on the labelled set, including adversarial prompts. | CR-A17-11, CR-A19-02, CR-A06-02 | FR-NL-001 |
| FR-NL-008 | Unit expressions (pixels, µm, nm, …) shall be parsed and converted using the target's calibration, with the conversion shown in the preview. | `scaled` semantics | HACK | T: "blur 2 microns" on a 0.5 µm/px image gives `sigma=2 scaled`, or sigma 4 in pixels, as specified in D08, shown in the preview. | CR-A17-14, CR-A16-09 | FR-NL-002 |
| FR-NL-009 | The system shall be fully functional with no language model installed. Any language model, local or hosted, shall be optional. Hosted models shall be opt-in per session. | Offline labs; privacy | MVP | T: with no LLM configured, all MVP journeys pass. | CR-A17-09, CR-A02-05 | — |
| FR-NL-010 | No pixel data, thumbnails or screenshots shall be sent to any language model. Text and metadata may be sent only after a user-visible preview of the exact payload, with redaction of path components and metadata fields offered, and with metadata-derived text passed only as delimited, length-limited data. | Confidentiality; prompt injection | MVP (if any LLM is enabled) | T: an outbound-payload capture contains no image bytes. The preview equals the payload. Injection fixtures are delimited. | CR-A17-08, CR-A25-01, CR-A25-02, CR-A25-10 | DEC-007 |
| FR-NL-011 | Every LLM-proposed plan shall be logged with model identity and digest, prompt-template version, raw output, the user's edits and the final executed command. | Reproducibility; audit | HACK (if LLM enabled) | T: log entries contain all fields. | CR-A17-10, CR-A24-08, CR-A25-11 | FR-HIST-001 |
| FR-NL-012 | If the NL layer exposes Fiji operations as tools to an LLM, the tool definitions shall be compatible with fiji-llm's `AiToolPlugin`/MCP conventions, unless DEC-011 decides otherwise. | Avoid an incompatible ecosystem | LONG | I: D08 maps tool schemas. | CR-A06-05 | DEC-011 |
| FR-NL-013 | Users shall be able to ignore NL and voice entirely and work only through menus, shortcuts and macros with no loss of function. | PER-4 | MVP | D: all MVP journeys complete without NL or voice. | CR-A02-05 | — |

## 12. Speech (SPEECH)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-SPEECH-001 | Speech recognition shall run locally, in a persistent separate operating-system process, with bounded request size and duration, and with model files pinned by revision and SHA-256 and loaded with downloads disabled. | Privacy, isolation, reproducibility | MVP | T: a network capture during transcription shows no egress. The worker is a separate PID and survives across requests. A model hash mismatch is refused. | CR-A16-01, CR-A16-02, CR-A16-07 | — |
| FR-SPEECH-002 | Microphone capture shall start only on explicit user action and stop on a second action or after 30 s, with a persistent recording indicator. Audio and transcripts shall not be persisted unless the user opts in. | Consent | MVP | T/D: the indicator is visible for the whole capture. Auto-stop occurs at 30 s. No audio file remains after processing. | CR-A25-03 | — |
| FR-SPEECH-003 | Voice activity detection shall be applied to all microphone input. Clips with no detected speech shall produce no transcript. | Hallucination on silence | MVP | T: a 5 s silence fixture produces an empty transcript. | CR-A16-04 | FR-SPEECH-001 |
| FR-SPEECH-004 | Transcripts shall be shown as editable text and passed to the NL layer. Speech shall never trigger execution directly. | Substitution errors | MVP | T: no action executes until the NL preview is confirmed. | CR-A16-05 | FR-NL-004 |
| FR-SPEECH-005 | The speech worker shall accept a vocabulary prompt generated from the catalogue and session context (image and channel names), and shall record the prompt used with each transcript. | Biasing helps and hurts (P61) | HACK | T: the transcript record contains the prompt. A labelled-set comparison is reported with and without the prompt. | CR-A16-06 | FR-CMD-002 |
| FR-SPEECH-006 | Spoken numbers and digits shall be normalised to the same numeric values before parameter extraction. | Prompt-dependent number formats | MVP | T: "two point five", "2.5" and "two and a half" (if supported by D08) yield 2.5. | CR-A16-09 | — |
| FR-SPEECH-007 | Speech shall work on CPU only. GPU acceleration shall be optional. | Lab hardware unknown | MVP | T: the speech suite passes with CUDA unavailable. | CR-A16-08 | — |

## 13. Workspace and UI general (WS)

| ID | Requirement | Rationale | Pri | Acceptance criteria | Sources | Deps |
|---|---|---|---|---|---|---|
| FR-WS-001 | The workspace shall present a single application window containing image documents, tools, menus and command search, with panels for parameters, ROI list, Results, history/macro, and NL/voice. Legacy windows shall be tracked (FR-PLUG-002). | Window sprawl (UP list); brief §1.4/Part 11 | MVP (documents, menus/search, parameters, Results, history, NL); HACK (+ROI list, tools) | D: MVP journeys J-01–J-04 (D16) completed inside one window. | A05 UP-01, A04 §6, brief Part 11 | DEC-010 |
| FR-WS-002 | The workspace shall show which enabled update sites are active in the managed Fiji, and shall warn before any update or site change. | Supply chain | PARITY | T: the site list equals `db.xml.gz` active sites. | CR-A25-06 | — |
| FR-WS-003 | The system shall offer colour-blind-safe default channel colours as an option, without changing stored LUT semantics. | Accessibility | PARITY | T: toggling the option changes the display only (pixel and LUT data unchanged). | CR-A05-07 | FR-VIEW-001 |
| FR-WS-004 | Output folders shall have a documented retention policy and a user-invoked deletion function. | Data hygiene | PARITY | T: the deletion function removes only product outputs. | CR-A25-09 | — |
| FR-WS-005 | The system shall store API keys for hosted providers in the OS credential store and never log them. | Secret handling | LONG (only with hosted LLM) | T: no key appears in logs or files after a scripted session. | CR-A25-12 | DEC-007 |

## 14. Conflicts resolved during consolidation

| # | Conflict | Resolution | Basis |
|---|---|---|---|
| C1 | CR-A04-05 asks for option strings *byte-identical* to the legacy dialog's recording. CR-A11-02/CR-A12-02 require *every key explicit*, but the Recorder omits keys and drops options for preview dialogs (P81). | Generated strings shall contain every key (FR-PROC-003). The compatibility test is **semantic**: replaying the string in classic Fiji gives E1–E6 equality with the dialog run (FR-HIST-002). Byte identity with Recorder output is not required. | P81 (Recorder loss) makes byte identity reproduce the defect. |
| C2 | CR-A07-05 assumes IJ1 progress and status reach SciJava `StatusEvent`s. A20 found `DefaultLegacyHooks` does not override `showProgress`/`showStatus`. | Superseded by CR-A20-03, adopted as NFR-REL-004 with a validation probe. A07 and A15 statements annotated in Phase 8. | `ij1-patcher LegacyInjector.java#L177-L185`; `DefaultLegacyHooks.java` |
| C3 | CR-A25-07 refuses all writes to input paths. CR-A19-09 allows an explicit per-document decision. | Default refuse, with an explicit per-document override (FR-IMG-009). | Integrity and user authority both served |
| C4 | CR-A08-08 mandates `System.exit` for headless runners. In GUI mode `System.exit` hangs (P85). | Headless batch runners may use `System.exit` with a timeout. The interactive engine uses FR-SYS-005. | P85 versus P14 |

## 15. CR → requirement mapping (authoritative)

CRs that become verification obligations rather than product features map to A27 NFRs or to A30 (`VER`). CRs about project documentation map to `DOC` (handled in F04/D20).

| CR range | Maps to |
|---|---|
| CR-A01-01/02/03/04 | FR-PROC-001 / FR-HIST-001 / FR-NL-004 / FR-CMD-001 |
| CR-A02-01…07 | FR-CMD-001, FR-CMD-007, FR-MACRO-001 + FR-MEAS-005, FR-NL-004, FR-NL-013, FR-PLUG-001, FR-VIEW-006 |
| CR-A03-01…07 | FR-PROC-001, FR-MEAS-002, FR-HIST-001, FR-HIST-010, FR-PLUG-001, FR-MACRO-007, FR-CMD-001 |
| CR-A04-01…16 | FR-CMD-001, FR-IMG-002, FR-PROC-014, FR-PROC-015, (C1) FR-PROC-003/FR-HIST-002, FR-VIEW-002, FR-VIEW-003, FR-CMD-007, FR-ROI-003, FR-ROI-004, FR-VIEW-004, FR-HIST-002, FR-PROC-013, FR-PLUG-001, NFR-A11Y-001, NFR-MAINT-004 |
| CR-A05-01…08 | FR-MEAS-002, FR-HIST-001, FR-HIST-004, FR-PROC-014, FR-ROI-004/FR-MEAS-005, FR-MACRO-007, NFR-A11Y-001/002 + FR-WS-003, FR-PROC-004 |
| CR-A06-01…05 | DOC (prior-work citation rule, F04/D20), FR-NL-007, FR-CMD-006, FR-MACRO-007, FR-NL-012 |
| CR-A07-01…06 | FR-SYS-003, FR-SYS-002, FR-PROC-011, FR-IMG-001, (C2) NFR-REL-004, FR-SYS-005 |
| CR-A08-01…10 | FR-CMD-003, FR-CMD-002, FR-PROC-005, FR-PROC-003, FR-MEAS-002, FR-PROC-009, FR-PROC-008, (C4) FR-SYS-005/NFR-REL-003, FR-HIST-009, FR-CMD-004 |
| CR-A09-01…09 | FR-IMG-002, FR-IMG-001, FR-VIEW-006, FR-HIST-004, NFR-PERF-004, FR-IMG-011, FR-SYS-004, FR-HIST-010, FR-PROC-004 |
| CR-A10-01…08 | FR-ROI-001 (01, 02), FR-ROI-002, FR-ROI-003, FR-ROI-005, FR-ROI-004, FR-ROI-006, FR-ROI-007 |
| CR-A11-01…08 | FR-PROC-002, FR-PROC-003, FR-MEAS-001, FR-MEAS-003, FR-MEAS-004, FR-PROC-010, FR-PROC-016, FR-PROC-012 |
| CR-A12-01…09 | FR-HIST-001, FR-HIST-002, FR-HIST-002, FR-HIST-008, VER (A30 E7), FR-MACRO-004, FR-MACRO-002, FR-SYS-011, FR-MACRO-005 |
| CR-A13-01…06 | FR-CMD-005, FR-PROC-002, FR-PLUG-001, FR-PROC-004, FR-CMD-002, FR-SYS-008 |
| CR-A14-01…06 | FR-IMG-005, FR-IMG-006, FR-IMG-007, FR-IMG-003, FR-IMG-004, FR-IMG-008 |
| CR-A15-01…09 | FR-SYS-001, FR-PROC-002/FR-PROC-007, FR-PLUG-002, FR-ROI-004, FR-HIST-008/009, FR-PLUG-001, FR-PROC-011, FR-SYS-007, FR-MACRO-007/FR-PROC-009 |
| CR-A16-01…10 | FR-SPEECH-001 (01, 02, 07), NFR-PERF-002 (03), FR-SPEECH-003, FR-SPEECH-004, FR-SPEECH-005, FR-SPEECH-007 (08), FR-SPEECH-006 (09), VER/NFR-USE-003 (10) |
| CR-A17-01…14 | FR-NL-001, FR-PROC-003, FR-NL-003, FR-NL-004, FR-NL-002, FR-NL-005, FR-NL-006, FR-NL-010, FR-NL-009, FR-NL-011, FR-NL-007, VER/NFR-USE-003 (12), FR-NL-002 (13), FR-NL-008 |
| CR-A18-01…08 | FR-NL-001/FR-IMG-002, FR-SYS-004, FR-HIST-008, NFR-REL-005, FR-PROC-005, FR-HIST-003, FR-IMG-002, FR-PLUG-003 |
| CR-A19-01…11 | FR-CMD-005, FR-NL-004/007, NFR-USE-001, FR-HIST-004, FR-HIST-005, FR-HIST-006, FR-PROC-006, FR-PROC-013, FR-IMG-009, FR-HIST-007, FR-SYS-007 |
| CR-A20-01…07 | NFR-USE-002, NFR-REL-005, NFR-REL-004, FR-PROC-007, FR-HIST-008, FR-SYS-006, FR-PROC-005 |
| CR-A21-01…06 | FR-SYS-001, FR-SYS-003, FR-SYS-009, NFR-SEC-002, FR-VIEW-001, FR-SYS-005 |
| CR-A22-01…06 | FR-SYS-008, NFR-FID-004, FR-SYS-010, NFR-PLAT-001, NFR-PLAT-002, NFR-MAINT-004 |
| CR-A23-01…05 | FR-VIEW-005, NFR-PERF-003, NFR-PERF-001, NFR-PERF-005, FR-HIST-005 |
| CR-A24-01…08 | NFR-PKG-001, NFR-PKG-002, NFR-PKG-003, NFR-PKG-004, NFR-PKG-005, NFR-PKG-006, FR-IMG-004, FR-NL-011 |
| CR-A25-01…15 | FR-NL-010, FR-NL-010, FR-SPEECH-002, FR-MACRO-002, FR-MACRO-003, FR-WS-002, FR-IMG-009, NFR-SEC-002, NFR-PRIV-002, FR-NL-010, FR-HIST-001, FR-WS-005, NFR-PRIV-001, FR-IMG-010, FR-MACRO-006 |
| CR-A29-01…07 | NFR-MAINT-002, NFR-PKG-007, VER (A30), NFR-MAINT-003, NFR-MAINT-005, NFR-PRIV-003, NFR-FID-005 |
| CR-A30-01…08 | VER: A30 obligations; D18 implements. Also NFR-FID-001…003. |

## Change log

- 2026-10-17: Baseline consolidated by the lead from 223 CRs.
