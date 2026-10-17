---
id: D10
title: External Plugin and Compatibility Strategy
stage: 02-design
status: Draft (provisional on DEC-010)
priority: P1
source_revisions: [imagej/ImageJ@v1.54p; Fiji archive 20261013-1517]
related_requirements: [FR-CMD-002, FR-CMD-005, FR-PROC-007, FR-PROC-009, FR-PROC-011, FR-PLUG-001, FR-PLUG-002, FR-PLUG-003, FR-SYS-008, FR-WS-002, FR-HIST-008]
related_decisions: [ADR-002, ADR-004, ADR-011]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# D10 — External Plugin and Compatibility Strategy

## Executive Summary

Plugins run **unchanged** in the same Fiji JVM. How the workspace presents them depends on the compatibility category (A13 C1–C6, S), which is recorded per command (FR-CMD-005):

- **C1–C3** plugins are driven like core commands. Typed panels are used where a schema exists; otherwise the original dialog is surfaced.
- **C4–C6** plugins run in their own native windows, tracked by the workspace (legacy fallback).

Discovery uses Fiji's own registries. No product plugin registry exists (FR-CMD-002). Compatibility is stated honestly per category and is never claimed globally (PR-9).

## 1. Category handling

| Cat. | Definition (A13) | Execution route | UI presentation | History fidelity | Example |
|---|---|---|---|---|---|
| C1 | Headless-safe parameterised command (IJ2 `@Parameter`, ops) | R2 with resolved inputs (FR-PROC-011) | Schema form generated from `@Parameter` metadata, or the product harvester (V-01) | Full (module inputs) | `FillHoles` ops command |
| C2 | Needs image/ROI/Results state, no custom window | R1 with an explicit target | Menu or search, runs immediately (no parameters) or with a schema form | Full (label + options) | Skeletonize (2D/3D) |
| C2r | C2 plus the ROI Manager | R1, with the ROI bridge load and read-back (D04 §4) | As C2. The ROI list updates after the run. | Full, with ROI deltas | `Colocalisation_Analysis` (A13 P44 `RoiManager` reference) |
| C3 | GenericDialog only, macro-drivable | R1 with complete options **if a schema exists**. Otherwise R1 without options and the native dialog surfaced (`WAITING_FOR_DIALOG`, event `DIALOG_PENDING`, FR-PROC-007). | A schema form, or the native dialog brought forward with a banner | Full when a schema exists. When the dialog is surfaced: the dialog-snapshot values (provenance `dialog-snapshot`, V-10, D04 §7) if that validation passes; otherwise **partial** ("parameters entered in a Fiji dialog — not captured", excluded from E7). `CommandListener` cannot capture dialog-entered values: it fires before the dialog opens. | Auto Threshold, Gaussian Blur |
| C4 | Own Swing/AWT window | R1/R2 launch. The window runs natively. | Tracked in UI-16 with an "external window" badge, associated with the launching history step (FR-PLUG-001) | Observed actions only (partial) | TrackMate, BigDataViewer, TrakEM2, Labkit |
| C5 | Needs IJ1 `ImageWindow`/`ImageCanvas`/Toolbar interaction | R1 after switching the target to window policy P-C (visible IJ1 image window, D04 §3). Restored on close. | The workspace shows "Document is being edited in a Fiji window"; the document becomes read-only in the workspace until it is released | Observed (partial) | Trainable Weka, Cell Counter, Sync Windows |
| C6 | Native rendering (Java 3D/JOGL) | R1 launch | External window (as C4) | Observed (partial) | 3D Viewer |
| S | Script plugin | R4 via `ScriptService` | As its category (by inspection), plus a capability scan for user scripts | Depends on the script | Fiji scripts under `scripts/` |

**Default for uncategorised commands** (third-party, not yet classified): treat as **C3**, run without options, surface the dialog, and observe. If the command opens its own window, the tracker reclassifies it as C4 for the session, and records a schema-gap note to support catalogue curation.

## 2. Discovery and catalogue curation

- **Runtime discovery (FR-CMD-002):** `Menus.getCommands()`, `ModuleService.getModules()` and `ScriptService` scripts, rebuilt when the plugin set changes (`CatalogueChanged`).
- **Static evidence:** A13 P44/P45 scans give initial category hints. A per-command probe harness (A13 U-A13-2) refines them over time. Results are written to the catalogue with their evidence (FR-CMD-005).
- **Schema growth:** core set (54) → MVP and HACK coverage → PARITY. Each new schema needs its source citation and an E-level equivalence test (NFR-FID-001).

## 3. Update sites and plugin installation (FR-SYS-008, FR-WS-002)

- The product never adds or removes update sites, and never triggers updates automatically.
- The workspace lists active update sites, read from `db.xml.gz`/the updater API, and offers "Open Fiji Updater" as a legacy-window action. A warning explains the integrity limitations (SHA-1, HTTP fallback; A25).
- After an update, the catalogue is rebuilt and the version check runs (FR-SYS-010).

## 4. GUI-dependent dialogs and interaction

| Situation | Strategy |
|---|---|
| Plugin's `GenericDialog` without a product schema | Native dialog surfaced. Values are **not** captured by `CommandListener`/`Macro.getOptions()` (`Executer.java#L54-L61`; `Macro.java#L97-L103`). They are taken from the dialog snapshot (V-10, D04 §7), or the step is recorded as "parameters entered in a Fiji dialog — not captured" (PARTIAL). |
| `NonBlockingGenericDialog` ("wait for user") | Runs in M2 (P42 E2). Surfaced as a non-modal tracked window; the operation stays `WAITING_FOR_DIALOG` until it closes. |
| Plugin opens a file chooser | Native chooser shown. Paths are recorded as observed inputs. |
| Plugin calls `IJ.getImage()` with no image | Prevented by the precondition `image.open` for catalogued commands (FR-PROC-004). For uncategorised commands, the engine ensures that a document is current before launch, or warns. |
| Plugin calls `System.exit` / Quit | Not supported in-session (A28 PAR-050). The product shutdown hook (DEC-036) and journal recovery at the next start handle it (FR-SYS-007; there is no separate supervisor process in O2, D01 IF-09). |
| Plugin mutates images from its own threads | Detected by coalesced digests as observed changes with no parameters (D09 SY-4) |

## 5. Veto and policy hooks (FR-PLUG-003)

The `CommandListener` veto (returning `null`) is used **only** for:
- (a) AC7 code actions started from legacy menus (macro or script runners), held until capability consent is given;
- (b) commands that the policy marks as unsupported in session (e.g. `Quit`, replaced by the product's own quit sequence, D03 §6).

The veto is never used to restrict ordinary scientific commands.

## 6. Compatibility statement (user-facing)

The UI shows a command's category badge and one of these messages:
- "Runs in workspace" (C1–C3 with a schema);
- "Uses Fiji dialog" (C3 without a schema);
- "Opens in a Fiji window" (C4/C6);
- "Requires a Fiji image window" (C5).

The parity statuses in A28 drive release notes. Only VERIFIED statuses are advertised (R-A28-01).

## Change log

- 2026-10-17: Initial version by the lead.
- 2026-10-17: Phase 8 review fixes (findings #2, #28, #30).
