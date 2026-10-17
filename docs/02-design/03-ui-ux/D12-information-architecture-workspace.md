---
id: D12
title: Information Architecture and Workspace
stage: 02-design
status: Draft (provisional on DEC-010)
priority: P0
source_revisions: [imagej/ImageJ@v1.54p (03382be1), imagej/imagej-legacy@imagej-legacy-3.0.0 (fc31a563), "Fiji runtime pin f6695397 (fiji-2.18.1-SNAPSHOT / ij-1.54p)"]
related_requirements: [FR-WS-001, FR-WS-002, FR-WS-003, FR-CMD-001, FR-CMD-002, FR-CMD-004, FR-CMD-005, FR-CMD-006, FR-CMD-007, FR-SYS-006, FR-SYS-007, FR-IMG-002, FR-IMG-011, FR-VIEW-002, FR-VIEW-003, FR-VIEW-004, FR-PROC-003, FR-PROC-007, FR-PROC-013, FR-PROC-014, FR-PROC-015, FR-MEAS-002, FR-MEAS-003, FR-MEAS-005, FR-ROI-004, FR-HIST-001, FR-HIST-002, FR-HIST-004, FR-HIST-007, FR-HIST-008, FR-PLUG-001, FR-PLUG-002, FR-NL-004, FR-NL-013, FR-SPEECH-002, NFR-USE-001, NFR-USE-004, NFR-A11Y-001, NFR-PLAT-002, NFR-PERF-003, NFR-REL-005]
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# D12 — Information Architecture and Workspace

## Executive Summary

This document compares three workspace layouts against the requirements and personas and recommends **(a) a single window with fixed dock regions and tabbed documents that can be split (IDE-like)** (**PROPOSED**). The other two options are an MDI desktop and floating panels close to legacy Fiji.

- **Why (a).** It is the only option that satisfies FR-WS-001 (single window) while keeping one visible, explicit target (FR-IMG-002, UP-02) and supporting side-by-side comparison of real documents.
- **Legacy windows.** They remain operating-system windows (R-A04-03; PAR-045 *partial*), are tracked (UI-16) and are never faked as embedded.

The document then:

- defines surfaces **UI-01…UI-19** (the brief's IDs) and adds **UI-20 Log**. For each surface it gives purpose, contents, requirements, the implementing CMP (D01) and default visibility per persona preset.
- fixes the **menu rule**. The full Fiji menu hierarchy is generated from the runtime catalogue and is not reorganised (FR-CMD-001/002). Organisation is only ever *added*: a command palette, context panels, and an additive *Workspace* menu. The one exception is a short list of window- and view-management commands, which are **rebound** to workspace equivalents (with labels unchanged) because their IJ1 implementations act on hidden windows (§5.3).
- specifies navigation, the document model and legacy-window handling.
- provides functional wireframes W-1…W-5.

Everything here is provisional on DEC-010.

## Purpose and Scope

**In scope:** layout, regions, surfaces, menus, navigation, documents and legacy windows.

**Out of scope:**

| Topic | Owner |
|---|---|
| Viewer internals and ROI tools | D13 |
| Component states | D14 |
| Feedback treatments | D15 |
| Journeys | D16 |

The principles applied throughout are D11 UXP-01…UXP-16.

## Sources and Evidence

| Source | Use | Evidence |
|---|---|---|
| A04 §5.1–§5.20 | Legacy main window, menus, Window menu, image windows, tool windows, shortcuts | VERIFIED |
| A05 UP-01, UP-02, UP-09, UP-12, UP-13 | Window sprawl, mis-targeting, global ROI/Results, discoverability, HiDPI | VERIFIED |
| A02 PER-1…PER-6, beginner/expert table, R-A02-02 ("ship a classic layout") | Persona presets | INFERRED archetypes |
| A06 F2 | `org.scijava.ui.swing.mdi.SwingMdiUI` (MDI with `JMDIDesktopPane`) ships in `scijava-ui-swing-1.0.5.jar` but is not Fiji's default; why it is not the default is UNKNOWN | VERIFIED (presence) |
| A15, A21, P82 | IJ1 commands open their own windows regardless of policy; window policy is needed | VERIFIED |
| `Fiji/jars/` listing (2026-10-17) | No docking library is installed (no FlexDock, DockingFrames or JIDE). `miglayout-swing-5.3`, `swingx-1.6.1`, `rsyntaxtextarea-3.6.0`, `flatlaf-3.6.1` and `jfreechart-1.5.6` are present. | VERIFIED |
| `parity-matrix.csv` PAR-006, PAR-026, PAR-033, PAR-034, PAR-035, PAR-045, PAR-049 | Target parity for documents, plots, menus, shortcuts, status, windows, macro tools | — |

## 1. Layout options compared

Criteria are drawn from requirements and personas. Ratings: ● meets; ◐ partially; ○ fails.

| # | Criterion (source) | (a) Single window, fixed docks, tabs and splits | (b) MDI desktop (`JDesktopPane`, as in `SwingMdiUI`) | (c) Floating panels near legacy Fiji |
|---|---|---|---|---|
| K1 | Single application window (FR-WS-001) | ● | ● | ○ every panel and image is a top-level window |
| K2 | Explicit, visible target; no mis-targeting (FR-IMG-002, UP-02) | ● the focused tab in the focused pane, plus a target strip | ◐ the focused internal frame; frames overlap and hide each other (UP-01 in miniature) | ○ reproduces focus-based targeting |
| K3 | Lost or hidden windows (UP-01) | ● no overlap; tabs are always listed | ◐ overlap inside the desktop; needs Tile/Cascade | ○ the original problem |
| K4 | Side-by-side comparison of real documents (W03, W04, W06 in A03; D15 §6) | ● splits up to 2×2 with linked navigation | ◐ manual tiling | ◐ manual window arrangement |
| K5 | Keyboard navigation between regions; accessibility (NFR-A11Y-001) | ● fixed regions allow deterministic F6 cycling | ◐ internal-frame cycling only | ○ OS window switching |
| K6 | HiDPI consistency (NFR-PLAT-002; UP-13) | ● one Swing tree | ● one Swing tree | ◐ many windows, mixed toolkits |
| K7 | Coexistence with legacy OS windows (FR-PLUG-001/002) | ● legacy windows float above; tracked | ◐ two windowing models at once (internal versus OS frames) | ● native |
| K8 | Familiarity for PER-4 (A02 R-A02-02) | ◐ menus, shortcuts and labels are unchanged, but the window model changes. The Compact preset (§3) mitigates. | ◐ | ● |
| K9 | Discoverability of state panels for PER-1/PER-5 (UP-12; FR-MEAS-002) | ● panels are in fixed, predictable places | ◐ | ○ panels must be opened and found |
| K10 | Implementation risk (A22: no toolchain; no docking library installed) | ◐ fixed regions are buildable with `JSplitPane`/`JTabbedPane` only; drag-anywhere docking would need a new library | ● the JDK provides `JDesktopPane`; `SwingMdiUI` exists as prior art | ● trivial |
| K11 | Results and ROI lists visibly bound to a document (UP-09; FR-ROI-004, FR-MEAS-005) | ● panel headers follow the focused tab | ◐ | ○ |

**Recommendation (PROPOSED, DP-D12-01): option (a).** It is the only option that satisfies K1–K3 and K11 together. Its residual risk is K8 familiarity, which is addressed by the Compact preset and by leaving menus and shortcuts untouched (D11 UXP-02/08).

Option (b) is rejected because it carries UP-01 into the desktop pane and adds a second windowing model beside the unavoidable legacy OS windows. Option (c) fails FR-WS-001. Its one strength, native behaviour, is kept for legacy windows only.

**Docking model (PROPOSED, DP-D12-02): fixed regions with movable tabs, and no third-party docking library at MVP/HACK.**

- **What the user can do:** move panels between the right and bottom docks, reorder tabs, collapse a dock to its tab strip, resize splitters, and split the document area.
- **Not at MVP/HACK:** floating (detached) product panels and arbitrary drag-docking. No requirement asks for them, and adding a docking library is a licensing and packaging decision (NFR-PKG-001/003, DEC-005).

## 2. Workspace regions

| Region | Contents (surfaces) | Resizable / collapsible | Notes |
|---|---|---|---|
| R-MENU | UI-02 menu bar | No | Generated (§5) |
| R-CMD | UI-11 command bar | Collapsible to the search field only | One row. Never taller than one text line plus the recording indicator (D11 AP-02). |
| R-TOOLS | UI-03 tool palette | Width is fixed per preset (icons, or icons and labels) | Vertical, left |
| R-DOC | UI-04 document area → panes → UI-05 viewers with their target strip and UI-06 dimension controls | Splits up to 2×2 | The largest region in every preset (UXP-01) |
| R-RIGHT | Tabbed dock: UI-07 Inspector, UI-14 Display (B&C), UI-15 Threshold, UI-13 Channels, UI-09 ROI Manager, UI-17 Settings & globals | Collapsible, resizable | The upper and lower halves are independent tab groups |
| R-BOTTOM | Tabbed dock: UI-08 Results, UI-10 History & macro, UI-20 Log | Collapsible, resizable | — |
| R-STATUS | UI-12 status and activity bar, which includes the UI-16 legacy-window badge | No | Fixed slots (D11 §3.2) |
| Overlays | UI-18 plan/confirmation sheet (anchored under R-CMD or over the Inspector); banners (D14 §4.9) | — | Non-modal to other documents |
| Replacement | UI-19 engine start-up screen, which replaces R-DOC content until the engine is ready | — | FR-SYS-006 |

## 3. Persona presets

Presets change visibility and expansion only. They never change command reachability (UXP-11, FR-NL-013, FR-CMD-001).

| Preset | Personas (A02) | Intent |
|---|---|---|
| **Guided** | PER-1, PER-5 | State panels visible, explanations expanded (FR-VIEW-006), tool labels shown |
| **Standard** (default) | PER-2 | The panels used in W01–W06 visible; explanations collapsed to one line |
| **Compact** | PER-3, PER-4, PER-6 | Maximum document area; docks collapsed to tab strips; icon-only tools; command bar collapsed to search |

Visibility codes used in §4:

- **V**: open and expanded.
- **T**: present as a tab, but collapsed or not selected.
- **C**: contextual, opened automatically when relevant (and closed again only if it was opened automatically).
- **H**: hidden; open from the *Workspace* menu or the palette.

## 4. Surface catalogue (UI-01…UI-20)

Each implementing CMP is from D01. Surface IDs UI-01…UI-19 are those in the brief. UI-20 is added here.

| ID | Surface | Purpose | Contents | Requirements | CMP | Guided / Standard / Compact | Tier |
|---|---|---|---|---|---|---|---|
| **UI-01** | Main workspace window | The single application window hosting all product surfaces | Regions R-MENU…R-STATUS; window title `<product name> — <focused document title>` (product name pending DEC-001); quit sequence with unsaved-document prompts | FR-WS-001, FR-SYS-005, NFR-PLAT-002 | CMP-01 | V / V / V | MVP |
| **UI-02** | Menu bar | Full Fiji menu hierarchy plus the additive *Workspace* menu | §5 | FR-CMD-001, FR-CMD-002, FR-CMD-007, NFR-USE-004 | CMP-04 | V / V / V | HACK (core set), PARITY (all) |
| **UI-03** | Tool palette | Selection, navigation and annotation tools, with visible variants | Tools 0–14 with variants (A04 §5.3); options on double-click; hover hint text from `Toolbar.showMessage`. Macro and plugin tool slots are deferred (PAR-049). | FR-ROI-001…003, NFR-A11Y-001 | CMP-03 | V with labels / V icons / V icons | MVP (rect, polygon), HACK (others) |
| **UI-04** | Document area | Holds the open documents as tabs in 1–4 panes | Tab strips per pane; tab label = IJ1 title plus markers (`*` unsaved, `V` virtual, `G` global calibration, external badge); splits; linked-navigation toggle; drop target for files | FR-WS-001, FR-IMG-002, FR-IMG-011 | CMP-01 (layout) + CMP-02 (per viewer) | V / V / V | MVP (tabs), HACK (splits) |
| **UI-05** | Image viewer | Render and interact with one document | Canvas (D13); target strip: title, ID, subtitle (FR-VIEW-002), zoom %, state markers; preview and comparison labels (D15) | FR-VIEW-001…005, FR-ROI-001…003 | CMP-02, CMP-03 | V / V / V | MVP |
| **UI-06** | Dimension (C/Z/T) controls | Navigate hyperstack positions | One labelled slider per non-singleton dimension, in order c, z, t; `n/N`; channel name or slice label; animation control (deferred, PAR-011) | FR-VIEW-002, FR-VIEW-004 | CMP-02 | C (stacks) / C / C | HACK |
| **UI-07** | Inspector / parameter panel | Non-modal, schema-driven form for the command being invoked; also shows a selected history step's parameters (read-only) | Header with target and scope; fields with labels, keys, units and consequential markers; globals section; preview toggle; live macro line; OK/Cancel/Help (D14 §4.3) | FR-PROC-003, FR-PROC-010, FR-MEAS-002, FR-VIEW-006, FR-HIST-005 | CMP-05 | V (explanations expanded) / V / C (opens on command, collapses after) | MVP |
| **UI-08** | Results panel | Measurements bound to their source document; system-table view | Per-document view and an "All (Results)" view; Fiji column names; full precision on demand; measurement-settings strip | FR-MEAS-002…005 | CMP-06 | V / V / T | MVP |
| **UI-09** | ROI list ("ROI Manager — `<doc>`") | Per-document ROI collection mirroring `RoiManager` | Index (0-based, as macros use), label, type, position; buttons as in `RoiManager` (A04 §5.8); Show All / Labels | FR-ROI-004, FR-ROI-005 | CMP-07 | V / V / T | HACK |
| **UI-10** | History & macro panel | Always-on structured history; generated macro; undo/redo; export and replay | Entries with origin, target, outcome and macro line; per-document undo pointer; macro view; export `.ijm`; replay | FR-HIST-001…010, FR-MACRO-001 | CMP-08 | V / T / T | MVP |
| **UI-11** | Command bar | Command search (palette) and typed NL entry in one field; voice control | Field (`l` focuses it); result list with label, menu path, shortcut, category and AC class; final row "Interpret as instruction"; microphone toggle and recording indicator | FR-CMD-001, FR-CMD-006, FR-NL-004, FR-NL-013, FR-SPEECH-002, FR-SPEECH-004 | CMP-04 (search) + CMP-09 (NL/voice) | V with hint / V / V (search width only) | MVP |
| **UI-12** | Status & activity bar | Separate slots for read-out, activity, dialogs and Fiji windows, undo budget, and engine state | Slot 1: pixel read-out (FR-VIEW-003) or ROI geometry while drawing. Slot 2: current operation, lifecycle state, real progress, Cancel. Slot 3: dialog-pending and Fiji-window badge (UI-16). Slot 4: undo depth and snapshot budget. Slot 5: engine state. | FR-VIEW-003, FR-SYS-006, FR-PROC-007, FR-PROC-013, NFR-USE-002, NFR-PERF-003, NFR-REL-005 | CMP-10 (+ CMP-02 for the read-out) | V / V / V | MVP |
| **UI-13** | Channels panel | Composite mode, channel visibility, per-channel LUT and range | Mode choice (6 modes, A04 §5.14); channel rows with visibility, LUT and min/max; colour-blind-safe option (FR-WS-003) | FR-VIEW-001, FR-WS-003, FR-HIST-003 | CMP-12 | C (multichannel) / C / T | HACK |
| **UI-14** | Display (B&C/LUT) panel | Display range, separate from data; explicit *Apply* | Histogram; Minimum/Maximum/Brightness/Contrast; Auto, Reset, Set; *Apply* with the "changes pixels" marker (D13 §9) | FR-PROC-014, FR-VIEW-006, FR-HIST-003 | CMP-12 | V / T / T | HACK |
| **UI-15** | Threshold panel | Non-destructive threshold preview; explicit commit | Histogram; lower/upper; method; display mode; Dark background; Stack histogram; Apply → `Convert to Mask` form (D13 §9.2) | FR-PROC-015, FR-PROC-016, FR-MEAS-002 | CMP-12 | C (on `Threshold...`) / C / C | MVP (via command), HACK (panel) |
| **UI-16** | Legacy window tracker | List, focus and attribute visible IJ1 and plugin windows | Status-bar badge with count; popover list (title, kind, toolkit, modal flag, originating step, controlled document); entries in Window menu and *Workspace* menu | FR-PLUG-001, FR-PLUG-002, FR-PROC-007 | CMP-11 | V (badge) / V / V | MVP (tracking), HACK (C4/C5 demos) |
| **UI-17** | Settings & result-affecting globals | Show all result-affecting globals with their current values and changes; links to the original Fiji options commands | Table of the FR-MEAS-001 globals (value, Fiji default, differs?, last changed by); "Change…" runs the exact Fiji command (for example `Set Measurements...`, `Binary...`, `Conversions...`); product preferences (preset, snapshot budget, voice) | FR-MEAS-001, FR-MEAS-002, FR-HIST-007, NFR-PERF-003, FR-WS-002 | CMP-12 | T / T / H | HACK |
| **UI-18** | Plan preview / confirmation sheet | Preview and confirm NL/voice plans; also the shared confirmation pattern for AC4/AC6/AC7 | D14 §4.8 | FR-NL-002…008, FR-SPEECH-004, FR-MACRO-002, FR-HIST-005, FR-IMG-006, FR-IMG-009 | CMP-09 (plans); the same D14 component is reused by CMP-05 (AC4/AC6 from forms) and CMP-08 (AC7 macro capability) | C / C / C | MVP |
| **UI-19** | Engine start-up screen | Truthful engine state until ready; recovery offer after loss | Phases (D15 §3); elapsed time; failure detail and log excerpt; recovery prompt (FR-SYS-007) | FR-SYS-006, FR-SYS-007, FR-SYS-010, FR-SYS-002 | CMP-01 (screen) + CMP-10 (state) + CMP-50 (source) | V while not ready | MVP (start-up), PARITY (recovery) |
| **UI-20** | Log panel *(added)* | Shows `IJ.log` output, product diagnostics and shortcut-conflict notices in order | Read-only text with timestamps; copy; filter by source (Fiji or product); links from failed-operation entries to their log excerpt | NFR-REL-005, FR-PROC-005; PAR-035 | CMP-10 | T / T / T | MVP |

### 4.1 Notes on specific surfaces

- **UI-07 and UI-18 are one-at-a-time per target.** A second command invoked while a form is open replaces the form after the first is cancelled or submitted. Edits are never lost silently: a dirty form asks *Discard changes to Gaussian Blur...?* (**PROPOSED**; D14).
- **UI-08 and UI-09 follow the focused document.** Each header always names the document (D11 UXP-03). This fixes B&C's silent re-targeting (A04 §5.11) by making the re-target visible. It does not freeze the panel.
- **UI-17 does not duplicate Fiji's options dialogs.** Values are changed through the original commands, recorded as AC5 actions (FR-HIST-007). This avoids a second implementation of option semantics (FR-PROC-001 spirit).
- **UI-20 and the IJ1 Log window.** Whether the IJ1 Log window also appears when `IJ.log` is called in this mode is **UNKNOWN** (A20: `DefaultLegacyHooks` overrides `log`). If it appears, it is tracked by UI-16 like any legacy window. Verify with a GUI-JVM probe that calls `IJ.log` with the main window hidden.

## 5. Menu organisation

### 5.1 Rules

| Rule | Statement | Requirement / evidence |
|---|---|---|
| M-01 | The menu bar is generated from the runtime catalogue (CMP-22 → CMP-04, built from `Menus.getCommands()`, `ModuleService` and `ScriptService`). It is never authored by hand. | FR-CMD-002; A08 |
| M-02 | Top-level order is `File, Edit, Image, Process, Analyze, Plugins, Window, Help`. Submenu order, separators and item labels are exactly those of the effective Fiji menu (IJ_Props + `plugins.config` + SciJava `menuPath`). | FR-CMD-001; A04 §5.2; PAR-033 |
| M-03 | No scientific menu item is renamed, moved, merged, hidden or disabled. Precondition failures are explained at execution (`PRECONDITION_FAILED`, FR-PROC-004), not by greying items out. Rationale: the catalogue lacks per-command precondition metadata for most of the roughly 1,100 declarations (A17 R-A17-03), so greying would be incomplete and therefore misleading. | FR-CMD-001, FR-PROC-004 |
| M-04 | Each item shows its IJ1 shortcut as Fiji does. Checkbox items (`Image>Type>8-bit`…) reflect the focused document. | FR-CMD-007; A04 §5.2 |
| M-05 | Shadowed or duplicate labels carry a small marker; the tooltip names the class that will run. | FR-CMD-004 |
| M-06 | A **single additive top-level menu, *Workspace***, is inserted between `Window` and `Help`. It contains: panel visibility toggles, presets, split commands, linked navigation, the Fiji-windows list (UI-16), History export, engine status, and product preferences. No existing path changes. Every item is also in the palette. | FR-WS-001; UXP-09 |
| M-07 | The image context menu (right-click on the canvas) contains the IJ_Props `popup01…popup11` items (A04 §5.2), followed by a separator and product items (Add to ROI Manager [t], Compare with previous state (D15 §6), Copy read-out). Fiji's StartupMacros "Popup Menu" macro is deferred with macro tools (PAR-049). | FR-CMD-001 |

### 5.2 Additive organisation

| Aid | Behaviour | Requirement |
|---|---|---|
| Command palette (UI-11 search) | Uses SciJava search (`scijava-search`) where possible. Matches label, menu path and aliases. Shows the exact label, menu path, shortcut, category C1–C6/S and AC class. Enter behaves exactly like the menu item (A19 §4: palette = menu). | FR-CMD-001, FR-CMD-005, FR-CMD-006 |
| Context panels | Invoking a command that has a schema opens UI-07. Commands that IJ1 implements as tool windows open the corresponding product panel (§5.3). | FR-PROC-003, FR-PROC-014/015 |
| Recents / favourites | When the palette query is empty, it lists recently run commands and user-pinned commands. **No requirement covers this (GAP-D12-01).** It is designed as optional, sits only in the palette, and never alters menus. | — (gap) |
| Menu-path teaching | Every executed action shows its menu path and macro line in History (UI-10) and in the plan sheet (UI-18) | FR-NL-004, FR-HIST-002 |

### 5.3 Rebound commands (labels unchanged; behaviour routed to workspace equivalents)

These IJ1 commands manage IJ1 *windows*, or open IJ1 *tool windows* that the product replaces. With product images hidden (FR-PLUG-002), their IJ1 implementations would act on hidden windows, on `WindowManager`'s current image, or on the hidden Fiji main window (FR-SYS-001).

**Mechanism.** CMP-04 keeps a routing table keyed by `command_id`. When one of these menu items, shortcuts or palette rows is activated in the workspace, the product behaviour runs instead of R1 (**PROPOSED**, DP-D12-03).

**Macros are not rerouted.** A macro run through R4 still calls the IJ1 class. If that opens an IJ1 window, the window is tracked (UI-16) (**INFERRED**).

| Command (exact label) | IJ1 behaviour | Workspace behaviour | Requirement / PAR |
|---|---|---|---|
| `Brightness/Contrast...` [C] | Opens `ContrastAdjuster` bound to the current image | Opens or focuses UI-14 for the focused document | FR-PROC-014; PAR-013 |
| `Threshold...` [T] | Opens `ThresholdAdjuster` | Opens or focuses UI-15 | FR-PROC-015; PAR-014 |
| `Channels Tool...` [Z] | Opens `Channels` | Opens or focuses UI-13 | FR-VIEW-001; PAR-012 |
| `ROI Manager...` (Analyze › Tools) | Shows the `RoiManager` frame | Opens or focuses UI-09 | FR-ROI-004; PAR-022 |
| `Undo` [z] (Edit) | IJ1 single-slot `Undo` | Product multi-step undo for the focused document (D15 §8) | FR-HIST-004; PAR-031 |
| `Record...` (Plugins › Macros) | Opens the Recorder frame | Opens UI-10 macro view. The IJ1 Recorder can still be opened from the History panel as a legacy window, fed by `Recorder.recordString` (FR-HIST-009). | FR-HIST-002, FR-HIST-009; PAR-028 |
| `Find Commands...` / Fiji *Focus Search Bar* [l] | Command Finder / search bar | Focuses UI-11 | FR-CMD-006; PAR-033 |
| `Image>Zoom>` `In [+]`, `Out [-]`, `Original Scale[4]`, `View 100%[5]`, `To Selection`, `Scale to Fit`, `Set... `, `Maximize` | Act on the image window's canvas | Viewer zoom operations (D13 §3) | FR-VIEW-004; PAR-008 |
| `Window>` `Show All` [ ] ], `Main Window` [enter], `Put Behind` [tab], `Cascade`, `Tile`, and the window list | Act on IJ1 frames | `Show All` and `Main Window`: focus the workspace and the command bar. `Put Behind`: next document tab in the focused pane. `Tile`: split the document area into a grid of panes holding the open documents (up to 2×2). `Cascade`: return to a single pane. Window list: open documents, then a separator, then tracked Fiji windows. | FR-WS-001, FR-PLUG-002; PAR-045 |
| `Start Animation [\]`, `Animation Options...` | `Animator` on the `StackWindow` | Viewer playback (deferred; PAR-011 *partial*) | FR-VIEW-004 |

`Color Balance...` and `Window/Level...` (ContrastAdjuster variants, A04 §5.11) are **not** rebound at HACK. A legacy window bound to a hidden current image is a mis-targeting risk, so they are run only with the target pinned by the engine. Whether `ContrastAdjuster` follows a hidden temporary current image correctly is **UNKNOWN** and needs a GUI-JVM probe.

## 6. Navigation

### 6.1 Region cycling and focus

| Key | Behaviour | Notes |
|---|---|---|
| F6 / Shift+F6 | Cycle focus R-CMD → R-TOOLS → R-DOC (focused pane) → R-RIGHT → R-BOTTOM → R-STATUS | Product reserved key (D11 DP-D11-02); conflict-checked against macro `[f6]` shortcuts |
| `l` (document focus) | Focus the command bar | Fiji meaning kept (A04 §5.18) |
| Enter (document focus) | Focus the command bar | IJ1 "Main Window [enter]" analogue (D11 §5) |
| Tab (document focus) | Next document tab in the pane (`Put Behind`) | IJ1 `WindowManager.putBehind()` cycle semantics (A04 §5.20) |
| Ctrl+Tab / Ctrl+Shift+Tab | Next/previous pane | Product; conflict-checked |
| F10 / Alt | Menu bar | Swing default |
| Esc | Context-dependent stack (D14 §2): close popup → cancel form edit → abort ROI edit → abort the running operation for the focused document | CR-A04-13, FR-PROC-013 |

Single-key IJ1 shortcuts fire only with **document focus** (D11 §5). Clicking a canvas or pressing F6 into R-DOC gives document focus. The focused pane shows a focus frame *outside* the canvas (D11 §7.2).

### 6.2 Panel opening rules

- **Opening a panel by command does not move keyboard focus off the document**, except for UI-07 and UI-18, which take focus because they need input. This keeps IJ1 single-key flow intact for B&C, Threshold and Channels: users press `C`, then keep using document keys (**PROPOSED**).
- A contextual panel (code **C**) closes automatically only if it was opened automatically.

## 7. Document model

| Aspect | Specification | Requirement / evidence |
|---|---|---|
| Identity | A document is one `ImagePlus` registered in CMP-24 with a `docId`. The tab shows the IJ1 title verbatim. The ID is shown in the target strip and in tooltips (macros use both: `selectImage(id/title)`, A04 §5.4). | FR-IMG-002, NFR-USE-004 |
| Markers | `*` unsaved (`ImagePlus.changes`); `V` virtual stack; `G` global calibration; external badge (§8); partial badge (D15). Markers are text glyphs with accessible names. | FR-IMG-011, FR-HIST-006, FR-PLUG-001 |
| Focused document | Exactly one, workspace-wide: the selected tab of the focused pane. It is the default target of every action and deictic reference. | FR-IMG-002; A18 §3 |
| New images | A command that creates an image (catalogue `creates_new_image`) opens it as a new tab next to its source, in the same pane, and focuses it. This matches IJ1, where the new image becomes current, which recorded macros rely on. The tab tooltip names the source step (FR-HIST-001 link). | A04 §5.20; FR-HIST-001 |
| Splits | The document area splits into 1, 2 (horizontal or vertical) or 4 panes. Each pane has its own tabs. A document is shown in only one pane at a time: IJ1 holds one C/Z/T position per `ImagePlus`, so two independent views would diverge from engine state. The limit of 2×2 is PROPOSED: the comparison workflows (A03 W03, W04, W06) involve two to four images; more images use tabs. | FR-WS-001, FR-IMG-001 |
| Linked navigation | A per-split toggle links zoom, pan centre and C/Z/T position across panes, in the spirit of IJ1 `Analyze>Tools>Synchronize Windows` (`SyncWindows`). Linking is view state only (AC1). Position changes are committed per document (D13 §6.4). **No requirement covers linking (GAP-D12-02).** | FR-VIEW-004 |
| Comparison | Before/after on real data is a viewer mode (D15 §6). It is not a static image. | D11 AP-03 |
| Closing | Closing a document with `changes=true` prompts `Save changes to "<title>"?` with Yes / No / Cancel, as in IJ1 (A04 §5.4). This is AC6 (A19). | FR-SYS-005, FR-IMG-009 |
| Input protection | *Save* over an input path is refused by default and requires a per-document override (D15 §7 warning). | FR-IMG-009 |

## 8. Legacy windows (UI-16)

| Window kind (A13 category / source) | Example | Behaviour in the workspace | Tracked as | Requirement |
|---|---|---|---|---|
| Modal `GenericDialog` from R1 with missing or unknown keys, or a legacy-fallback command without a schema | `Gaussian Blur...` without options; third-party C3 | Stays an OS modal dialog. The dialog-pending banner shows `WAITING_FOR_DIALOG` (D15). **Bring to front** is one click or key. | Dialog (modal) | FR-PROC-007 |
| C4 plugin with its own windows | TrackMate | OS windows; tracked; linked to the originating history step; external badge on that step | Fiji window | FR-PLUG-001 |
| C5 canvas-interactive plugin | Cell Counter | The engine shows the IJ1 `ImageWindow` for the target document (window policy, CMP-24). The document enters **external control**: its tab carries the external badge; its viewer becomes a read-only mirror refreshed from digests; product tools are disabled *for that document only*; a document banner reads "Open in a Fiji window (Cell Counter). Edit there; actions are recorded as observed." Control returns when the window closes. | Fiji window + controlled document | FR-PLUG-001, FR-HIST-008 |
| C6 native 3D | 3D Viewer | OS window; tracked | Fiji window | FR-PLUG-001 (PARITY) |
| IJ1 output windows opened by commands | Histogram, Plot Profile, Show Info, Log (if shown) | OS windows; tracked. Embedded plot panels are not designed (PAR-026 *partial*; GAP-D12-03). | Fiji window | FR-PLUG-002 |
| Script Editor, Updater | — | Legacy windows; tracked | Fiji window | PAR-029, PAR-041 |

**Rules:**

- **One badge, everywhere.** The `state.external` badge (D11 §7.3) appears on the tracker entries, on history steps and on controlled documents (FR-PLUG-001).
- **No untracked visible window.** The tracker lists every visible Fiji-owned window. FR-PLUG-002's acceptance ("no untracked visible Fiji window remains") is checked against the tracker.
- **Leaving the workspace.** Closing the workspace while legacy windows are open lists them in the quit sequence (FR-SYS-005).
- **External control is PROPOSED.** Whether product edits must be locked out during external control is **INFERRED**. Concurrent edits to one `ImagePlus` from a plugin's EDT handlers and from the engine worker would race, because the worker is the single writer only for product actions (FR-SYS-004). Validation is in D04.

## 9. Functional wireframes

All wireframes in this section are **functional wireframes — not a visual design**. Proportions are indicative. Labels in brackets are surface IDs.

### W-1 Default workspace (Standard preset, PER-2; single document, form open)

```
functional wireframe — not a visual design
+----------------------------------------------------------------------------------------------------+
| File  Edit  Image  Process  Analyze  Plugins  Window  Workspace  Help                     [UI-02]  |
+----------------------------------------------------------------------------------------------------+
| [l] Search commands or type an instruction...                                  (mic) off  [UI-11]  |
+----+--------------------------------------------------------------+--------------------------------+
|[] R| blobs.gif *  | cells.tif  V |                       [UI-04] | Inspector| B&C |Thresh|Channels|
|() O+--------------------------------------------------------------+--------------------------------+
|<> P| blobs.gif (id 3)  256x254 pixels; 8-bit (inverting LUT); 64K  | Gaussian Blur...      [UI-07]  |
|~~ L|                                                   zoom 200%  | Process > Filters              |
|/  F+--------------------------------------------------------------+ Target: blobs.gif (id 3)       |
|<  P|                                                              |  plane 1/1 . ROI: none . AC3   |
|.. W|                                                              | Sigma (Radius): [ 2.00 ] px    |
|*  T|                    IMAGE  [UI-05]                            |   key: sigma   ◆ affects result|
|A  M|                                                              | Globals: none for this command |
|Q  H|                                                              | [x] Preview — not recorded     |
|   C|                                                              | run("Gaussian Blur...",        |
|   [|                                                              |     "sigma=2");                |
|UI-03                                                              | [Help]      [Cancel]   [OK]    |
|    |                                                              +--------------------------------+
|    |                                                              | ROI Manager — blobs.gif [UI-09]|
|    |                                                              |  0  0060-0060  rect   [Add t]  |
|    |                                                              |  1  0065-0155  oval   [Measure]|
+----+--------------------------------------------------------------+--------------------------------+
| Results — blobs.gif [UI-08] | History & macro [UI-10] | Log [UI-20]                                |
| Measurements: Area Mean Min&Max . Limit: off . Redirect: None . Decimals: 3                        |
|  #  Label            Area     Mean    Min   Max                                                    |
|  1  blobs.gif:0060-0060  ...                                                                       |
+----------------------------------------------------------------------------------------------------+
| x=120, y=88, value=248 | Done: Measure (blobs.gif) 0.04 s | Fiji windows: 0 | Undo 3 . 0.2/6.0 GB | Engine: ready |
+----------------------------------------------------------------------------------------------------+
  [UI-12: read-out | activity | dialogs & Fiji windows (UI-16) | undo budget | engine]
```

### W-2 Beginner-oriented layout (Guided preset, PER-1/PER-5; hyperstack open)

```
functional wireframe — not a visual design
+----------------------------------------------------------------------------------------------------+
| File  Edit  Image  Process  Analyze  Plugins  Window  Workspace  Help                              |
+----------------------------------------------------------------------------------------------------+
| [l] Search commands (e.g. "measure") or type an instruction                (mic) off               |
+-----------+-------------------------------------------------+--------------------------------------+
| Rectangle | mitosis.tif |                                   | Display (B&C) — mitosis.tif [UI-14]  |
| Oval      +-------------------------------------------------+ DISPLAY ONLY: pixel values unchanged |
| Polygon   | mitosis.tif (id 7)  c:2/2 z:3/5 t:12/51;        | [histogram]                          |
| Freehand  |  171x196 pixels; 16-bit; 34MB        zoom 300%  | Minimum [====|------]  1120          |
| Line      +-------------------------------------------------+ Maximum [=========|-]  3890          |
| Angle     |                                                 | [Auto] [Reset] [Set...]              |
| Point     |                                                 | [Apply ▲ changes pixels]             |
| Wand      |              IMAGE [UI-05]                      |  "Apply rewrites pixel values using  |
| Text      |                                                 |   the current display range."        |
| Zoom      |                                                 +--------------------------------------+
| Hand      |                                                 | Channels [UI-13]  Mode: Composite    |
| (labels   +-------------------------------------------------+  [x] c1 Red     1120-3890           |
|  shown)   | c [==|=======]  2/2  GFP                [UI-06] |  [x] c2 Green    300-1400            |
| [UI-03]   | z [====|=====]  3/5                             +--------------------------------------+
|           | t [=====|====] 12/51                            | ROI Manager — mitosis.tif (empty)    |
|           |                                                 | "Draw a selection and press t."      |
+-----------+-------------------------------------------------+--------------------------------------+
| History & macro [UI-10] (expanded)          | Results — mitosis.tif [UI-08]                        |
| 1 Open  mitosis.tif   open("…/mitosis.tif") | (no measurements yet)                                |
| 2 B&C min/max c2      setMinAndMax(300,1400)|                                                      |
+---------------------------------------------+------------------------------------------------------+
| x=121, y=80, z=2, value=1530 | Ready | Fiji windows: 0 | Undo 0 | Engine: ready                    |
+----------------------------------------------------------------------------------------------------+
```

In the Guided preset, explanations are expanded (FR-VIEW-006), tool labels are shown, History is open, and Settings & globals (UI-17) is one tab away. No command is removed (UXP-11).

### W-3 Expert layout (Compact preset, PER-3/PER-4; two documents compared side by side)

```
functional wireframe — not a visual design
+----------------------------------------------------------------------------------------------------+
| File  Edit  Image  Process  Analyze  Plugins  Window  Workspace  Help    [l] search...  (mic)      |
+--+----------------------------------------------+-----------------------------------------------+--+
|[]| raw.tif * |                     [linked ⇄]   | raw.tif-mask |                                |I |
|()| raw.tif (id 4) 512x512 pixels; 16-bit; 512K  | raw.tif-mask (id 9) 512x512 pixels; 8-bit; 256K |n |
|<>|                                  zoom 150%   |                                   zoom 150%   |s |
|~~|                                              |                                               |p |
|/ |                                              |                                               |B |
|..|           IMAGE (pane 1)                     |            IMAGE (pane 2, focused)            |&C|
|* |                                              |                                               |Th|
|A |                                              |                                               |Ch|
|Q |                                              |                                               |RM|
|H |                                              |                                               |Gl|
+--+----------------------------------------------+-----------------------------------------------+--+
| Results | History | Log                                                          (dock collapsed)  |
+----------------------------------------------------------------------------------------------------+
| x=301, y=77, value=0 | Running: Analyze Particles... (raw.tif-mask) [Cancel] | Fiji windows: 1 | Undo 4 . 0.9/6.0 GB | ready |
+----------------------------------------------------------------------------------------------------+
```

The right and bottom docks are collapsed to tab strips. They open on click, on F6, or when a command needs UI-07 (contextual). The command bar is merged into the menu row as a search field. All IJ1 shortcuts work with document focus.

### W-4 State while a legacy plugin window is open (C5 Cell Counter on `blobs.gif`; modal dialog pending elsewhere)

```
functional wireframe — not a visual design
+----------------------------------------------------------------------------------------------------+
| File  Edit  Image  Process  Analyze  Plugins  Window  Workspace  Help                              |
+----------------------------------------------------------------------------------------------------+
| ⚑ Waiting for input in a Fiji dialog: "Subtract Background..." (step 6, cells.tif) [Bring to front]|
+----+--------------------------------------------------------------+--------------------------------+
|    | blobs.gif ⧉ | cells.tif ⧗ |                                | History & macro                |
| (t +--------------------------------------------------------------+ 5 ⧉ Cell Counter (Fiji window) |
| o  | ⧉ blobs.gif is open in a Fiji window (Cell Counter).         |    opened . observed actions:  |
| o  |   Edit there; actions are recorded as observed.  [Show it]   |    5.1 ⧉ observed: Initialize  |
| l  +--------------------------------------------------------------+    5.2 ⧉ observed change:      |
| s  |                                                              |        overlay of blobs.gif    |
|    |        IMAGE (read-only mirror, refreshed from digests)      |        (parameters unknown)    |
| d  |                                                              | 6 ⧗ Subtract Background...     |
| i  |                                                              |    waiting for Fiji dialog     |
| s  |                                                              |                                |
| a  |                                                              |                                |
| b  |                                                              |                                |
| l  |                                                              |                                |
| e  |                                                              |                                |
| d) |                                                              |                                |
+----+--------------------------------------------------------------+--------------------------------+
| x=40, y=12, value=8 | ⧗ Waiting for Fiji dialog | ⧉ Fiji windows: 3 ▾ | Undo 2 | Engine: ready     |
+----------------------------------------------------------------------------------------------------+
   ▾ Fiji windows [UI-16]
   +-----------------------------------------------------------------------------+
   | ⧉ Cell Counter            Swing  plugin  step 5  controls blobs.gif  [Front] |
   | ⧉ blobs.gif (Fiji image)  AWT    image   step 5  controls blobs.gif  [Front] |
   | ⧗ Subtract Background...  AWT    MODAL   step 6  cells.tif           [Front] |
   +-----------------------------------------------------------------------------+

   (OS windows outside the workspace, not drawn: the Cell Counter frame, the IJ1 ImageWindow
    for blobs.gif, and the modal GenericDialog.)
```

Glyph key: ⧉ = `state.external`; ⧗ = `state.attention`. Each glyph is paired with text, as D11 A-05 requires.

### W-5 Engine start-up screen (UI-19)

```
functional wireframe — not a visual design
+----------------------------------------------------------------------------------------------------+
| File  Edit  Image  Process  Analyze  Plugins  Window  Workspace  Help   (scientific menus: loading |
|                                                                          Fiji commands…)           |
+----------------------------------------------------------------------------------------------------+
|                                                                                                    |
|   Starting Fiji engine                                                                             |
|   ✓ Java runtime started                     (bundled JDK 21.0.7)                                  |
|   ✓ SciJava context and legacy layer active  (LegacyService.isActive = true)                       |
|   … Initialising ImageJ 1.54p                elapsed 3.1 s                                         |
|     Command catalogue                        waiting                                               |
|                                                                                                    |
|   Commands are refused until the engine is ready (ENGINE_NOT_READY).                               |
|   [Show log]                                                                                       |
+----------------------------------------------------------------------------------------------------+
| Engine: initialising Fiji                                                                          |
+----------------------------------------------------------------------------------------------------+
```

Each phase line is shown only when a real signal reports it (D15 §3). There is no percentage bar: start-up has no real counter (NFR-USE-002).

## Constraints and Limitations

- **Legacy windows stay OS windows.** They cannot be docked (R-A04-03; PAR-045 *partial*). The tracker mitigates this but does not remove it.
- **Rebinding is a deliberate deviation.** Routing Window and Zoom commands to workspace equivalents (§5.3) departs from IJ1 for focus-dependent macros. Macros calling these labels through R4 still run the IJ1 implementations on hidden windows. Their effect there is **UNKNOWN** per command and is recorded in PAR-051 (*partial*).
- **No docking library.** Fixed regions limit layout freedom. This is a deliberate trade-off (K10).

## Requirement gaps

| ID | Gap | Candidate requirement |
|---|---|---|
| GAP-D12-01 | Recents and favourites in command search are not covered by any FR | "The command search shall list recently executed and user-pinned commands when the query is empty, without altering menus." |
| GAP-D12-02 | Linked navigation across documents (multi-image comparison) has no FR | "The workspace shall allow zoom, pan and C/Z/T position to be linked across side-by-side documents." |
| GAP-D12-03 | Embedded histogram and plot panels have no FR. PAR-026 is *partial* with only FR-PLUG-002 linked. | Decide whether plots stay legacy windows (current design) or require an embedded panel requirement |
| GAP-D12-04 | No FR covers persisting the workspace layout or preset per user | "The workspace layout and preset shall persist across sessions." |
| GAP-D12-05 | No FR governs the external-control state of a document while a C5 plugin window edits it | "While a document is under control of a legacy plugin window, product-initiated mutations to it shall be refused with an explanation." |
| GAP-D12-06 | No FR explicitly requires a Log panel. PAR-035 lists one, but its requirements (FR-SYS-006, NFR-REL-004/005) do not state it. | "`IJ.log` output shall be shown in the workspace in order of emission." |

## Decisions proposed

| ID | Decision | One-line rationale |
|---|---|---|
| DP-D12-01 | Adopt layout (a): a single window with fixed dock regions and tabbed, splittable documents | Only option meeting FR-WS-001 and FR-IMG-002 and fixing UP-01/02 together (§1) |
| DP-D12-02 | Fixed regions with movable tabs; no third-party docking library at MVP/HACK; no floating product panels | No docking library is installed. Avoids a packaging and licensing decision (NFR-PKG-001/003). |
| DP-D12-03 | Rebind the window-management, zoom, tool-window and `Undo`/`Record...` commands to workspace equivalents, labels unchanged (§5.3) | Their IJ1 implementations act on hidden windows or the hidden main window (FR-SYS-001, FR-PLUG-002) |
| DP-D12-04 | Never disable or hide scientific menu items; explain precondition failures at execution | Precondition metadata is incomplete (A17 R-A17-03). FR-CMD-001. |
| DP-D12-05 | One additive *Workspace* top-level menu between Window and Help | Product functions need a menu home without changing any Fiji path (FR-CMD-001) |
| DP-D12-06 | Three presets (Guided, Standard, Compact) varying visibility only; Standard is the default | A02 beginner/expert table; R-A02-02 classic-layout mitigation |
| DP-D12-07 | A document is shown in at most one pane; splits limited to 2×2 | One C/Z/T position per `ImagePlus` (FR-IMG-001). Comparison workflows involve 2–4 images. |
| DP-D12-08 | C5 plugin windows put their document into a read-only *external control* state | Avoid concurrent writers to one `ImagePlus` (FR-SYS-004; FR-HIST-008) |

## References

- A02; A03 (W03, W04, W06); A04 §5.1–§5.20 and screenshots; A05 (UP-01, UP-02, UP-09, UP-12, UP-13); A06 F2; A08; A13 (C1–C6); A15; A17 R-A17-03; A18 §3; A19 §3–§4; A20; A21 (O2); A22; A26; A27; A28 and `docs/data/parity-matrix.csv` (PAR-006, 008, 011–014, 022, 026, 028, 029, 031, 033–035, 037–041, 045, 049, 051); `docs/data/commands.csv` (`creates_new_image`).
- D01 (CMP-01…CMP-12, CMP-22, CMP-24, CMP-50); D11 (UXP, tokens, keyboard ownership).
- `imagej/ImageJ@v1.54p` (03382be1): `ij/Menus.java#L110-L256`, `ij/WindowManager.java#L31-L55, L294-L315, L500, L633-L640`, `ij/plugin/WindowOrganizer.java`, `ij/plugin/Zoom.java`, `IJ_Props.txt` (`popup01…popup11`, `zoom01…zoom08`).
- `imagej/imagej-legacy@imagej-legacy-3.0.0` (fc31a563): `SearchBarHacker.java#L201-L206`.
- Installed jar listing `Fiji/jars/` (2026-10-17): no docking library; `miglayout-swing-5.3`, `swingx-1.6.1`, `flatlaf-3.6.1`, `jfreechart-1.5.6`, `rsyntaxtextarea-3.6.0` present.

## Phase 8 requirement mapping (lead)

This document's gap analysis led to these requirements being added (A26 §13a): FR-WS-006 (layout persistence), FR-WS-007 (UI-20 Log panel), FR-CMD-008 (shortcut conflicts, with DP-D11-02), FR-CMD-009 (recents and pinned commands in the palette, §5.2), FR-VIEW-008 (linked navigation across splits, §7) and FR-PLUG-004 (externally held documents, §8).
