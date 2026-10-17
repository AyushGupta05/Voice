---
id: D11
title: UX Principles and Visual Direction
stage: 02-design
status: Draft (provisional on DEC-010)
priority: P0
source_revisions: [imagej/ImageJ@v1.54p (03382be1), "Fiji runtime pin f6695397 (fiji-2.18.1-SNAPSHOT / ij-1.54p)", "Fiji/jars/flatlaf-3.6.1.jar (installed)"]
related_requirements: [FR-WS-001, FR-WS-003, FR-CMD-001, FR-CMD-002, FR-CMD-006, FR-CMD-007, FR-IMG-002, FR-VIEW-001, FR-VIEW-002, FR-VIEW-003, FR-VIEW-006, FR-PROC-004, FR-PROC-005, FR-PROC-007, FR-PROC-013, FR-PROC-014, FR-PROC-015, FR-PROC-016, FR-MEAS-002, FR-MEAS-003, FR-HIST-001, FR-HIST-002, FR-HIST-003, FR-HIST-004, FR-HIST-005, FR-HIST-007, FR-HIST-008, FR-PLUG-001, FR-PLUG-002, FR-NL-004, FR-NL-013, FR-SPEECH-002, FR-SPEECH-004, NFR-USE-001, NFR-USE-002, NFR-USE-004, NFR-A11Y-001, NFR-A11Y-002, NFR-PLAT-002, NFR-PERF-003]
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# D11 — UX Principles and Visual Direction

## Executive Summary

The workspace is a dense scientific workstation in which the **image is the primary object** and Fiji's semantics are the contract. Modernisation means fixing the problems that change results or waste work (A05 UP-01…UP-19). It does not mean a visual refresh: A05 classifies the dated look (AE-01) as aesthetic only and explicitly not a requirements driver.

This document defines:

- 16 UX principles (UXP-01…UXP-16). Each has a testable statement, the evidence it rests on (A04 sections, A05 UP-IDs, A02 personas) and the requirements it serves.
- Rules for density, hierarchy, terminology, keyboard ownership, accessibility and HiDPI.
- A **visual direction limited to tokens and rules**. Detailed colours, iconography and aesthetics are **provisional, pending a dedicated UI review**. This document fixes the token *names*, their *semantics* and the rules that constrain their values. It fixes no values.
- Anti-patterns AP-01…AP-09 that the design must not contain, starting with the five named in brief §1.4.

The base look-and-feel is FlatLaf. FlatLaf Light is already active by default in the pinned Fiji (**VERIFIED**: P87 `UIManager.getLookAndFeel() -> com.formdev.flatlaf.FlatLightLaf`). Everything here is provisional on DEC-010 (O2: an in-JVM Swing + FlatLaf workspace).

## Purpose and Scope

**In scope:**

- principles every UI surface must satisfy;
- terminology rules;
- keyboard ownership rules;
- accessibility and scaling baseline;
- visual tokens and rules;
- anti-patterns.

**Out of scope, and where it lives instead:**

| Topic | Owner |
|---|---|
| Layout and surfaces (UI-01…UI-20) | D12 |
| Viewer and ROI interaction | D13 |
| Component behaviour and states | D14 |
| Feedback and lifecycle presentation | D15 |
| Journeys J-01…J-14 | D16 |
| Error-handling internals | D17 |

## Sources and Evidence

| Source | Used for | Evidence |
|---|---|---|
| A04 §5.1–§5.20 and screenshots `a04-01`…`a04-17` | The baseline Fiji UI: menus, toolbar, subtitle, read-out, B&C, threshold, ROI Manager, shortcuts, dialogs | VERIFIED (runtime captures and source at v1.54p) |
| A05 UP-01…UP-19, AE-01 | Problem register and its classification | VERIFIED (sources per UP) |
| A02 PER-1…PER-6, must-stay-familiar matrix | Persona needs; what must not change | INFERRED archetypes from VERIFIED literature |
| A19 §3–§4 | Consequence classes AC0–AC7; confirmation policy by modality | PROPOSED (analysis) |
| A20 §1 | Feedback truthfulness classes | PROPOSED (analysis), resting on VERIFIED signals |
| P87 | FlatLaf Light active; Java2D normalising transform 1.333 with `sun.java2d.uiScale=true` | VERIFIED |
| `Fiji/jars/flatlaf-3.6.1.jar` listing | `FlatLightLaf`, `FlatDarkLaf`, `FlatIntelliJLaf` and `FlatDarculaLaf` classes are present | VERIFIED (`unzip -l`, 2026-10-17) |
| F02 §Frontend | The prototype UI used a hero heading, static before/after `<img>` figures and an event timeline | VERIFIED |
| `docs/data/risks.csv` R-004 | Risk that the UI drifts back to a prompt box | — |

## 1. Modernisation philosophy

**Preserve semantics; modernise structure, visibility and access.**

| Preserve unchanged (A04 Executive Summary; A02 matrix) | Change |
|---|---|
| Menu paths and exact labels, including shortcut decorations such as `In [+]` (A04 §5.2) | **Window sprawl** becomes a single window (UP-01 → FR-WS-001) |
| The implicit "current image" *concept* (A04 §5.20) | The current image becomes **explicit and visible** (UP-02 → FR-IMG-002) |
| Display range ≠ pixel data; threshold as reversible display state (A04 §5.11, §5.12) | The distinction becomes **visible at the control**, not only documented (UP-06/07 → FR-VIEW-006) |
| ROI modifier algebra, coordinate conventions (A04 §5.7, A10) | Modifier semantics become **discoverable** through hints, without being remapped (A04 §5.7 limitation) |
| The 1-based C/Z/T notation and subtitle content (A04 §5.4) | The subtitle becomes **selectable text** in a persistent strip (FR-VIEW-002) |
| Single-key shortcuts and their precedence (A04 §5.18) | Shortcuts are **shown** in the menus and the palette, and are never shadowed (FR-CMD-007) |
| `GenericDialog` label → macro key mapping (A04 §5.19) | Forms are **non-modal**, grouped, with units and visible globals (UP-16 → FR-PROC-003, FR-MEAS-002) |
| Recording as replayable macro text (A04 §5.16) | Recording becomes an **always-on history**, a superset of the Recorder (UP-10/15 → FR-HIST-001/002) |
| Esc aborts (A04 §5.17) | Cancellation and its consequences are **stated per family** (FR-PROC-013) |
| — | Single-level undo becomes **multi-step undo per document** (UP-03 → FR-HIST-004) |
| — | Painted AWT canvases become **accessible Swing components** (UP-14 → NFR-A11Y-001) |

A change is admissible only when it addresses a UP item, a requirement or a persona failure mode. "It looks dated" (AE-01) is not a sufficient reason on its own (**PROPOSED**).

## 2. Principles (UXP)

Every principle is **PROPOSED**. The "Test" column states how a review or test can show the principle is met.

| ID | Principle | Evidence | Requirements | Test |
|---|---|---|---|---|
| **UXP-01** | **The image is primary.** The document area is the largest region in every layout preset. Chrome never covers image pixels. Only image-state overlays may be drawn on the canvas: the ROI, the Overlay, the threshold LUT, and comparison and preview labels (D15). | A04 §5.4–§5.5: the canvas is the work surface. F02: the prototype replaced the viewer with static figures. PER-1/PER-2 work *on* images. | FR-WS-001, FR-VIEW-001, FR-VIEW-002 | I: in each D12 wireframe the document area is the largest region. No product widget overlaps the canvas. |
| **UXP-02** | **Fiji's words, unchanged.** Command labels, menu paths, dialog field labels, Results column names and option keys appear verbatim, including shortcut decorations and trailing `...`. Product-coined terms are limited to the glossary in §4. | A02 must-stay-familiar (M for all personas); A04 §5.2 (labels are API); A05 UP-12 | NFR-USE-004, FR-CMD-001, FR-CMD-003 | I: a terminology review against `commands.csv` and `commands-core-parameters.csv` |
| **UXP-03** | **One explicit target, always visible.** The focused document is marked unambiguously. Every surface that can start an action shows the target's title and ID, its C/Z/T scope and its ROI. | UP-02 (silent mis-targeting); A04 §5.20, §5.11 (B&C re-targets silently); PER-2/PER-4 failure modes | FR-IMG-002, FR-MEAS-002, FR-NL-004 | T: with two documents open, the parameter panel, the plan sheet and the palette all name the same target as the focused tab. |
| **UXP-04** | **Result-affecting state is visible where it matters.** Globals that change a command's result are shown in that command's form and in its history entry. Values that differ from Fiji defaults are marked. | UP-04, UP-05, UP-06, UP-08; R-A05-01 (avoid overwhelming beginners) | FR-MEAS-002, FR-HIST-007, FR-MEAS-001 | D: the Measure, Convert to Mask and 8-bit forms list BlackBackground, the measurement set, Limit/Redirect and scaling. |
| **UXP-05** | **Display is not data.** Every control that changes only the display is labelled *display only*. Every control that rewrites pixels carries the *changes pixels* marker and is never the default button of a display panel. | UP-06, UP-07; A04 §5.11–§5.12; PER-1 failure mode (A02) | FR-PROC-014, FR-PROC-015, FR-VIEW-006 | T: moving any *display only* control leaves the pixel hash unchanged. I: the marker is present on B&C *Apply*, threshold *Apply* and conversions. |
| **UXP-06** | **Truthful feedback only.** A state is shown only when a real signal supports it (A20 classes: determinate, stage-based, indeterminate, observed). Success is shown only after postconditions pass. Observed actions are labelled as observed. | A20 §1; A18 F4 (`IJ.run` swallows exceptions); brief §1.4 | NFR-USE-002, NFR-REL-005, FR-PROC-005, FR-PROC-007, FR-HIST-008 | I: the D15 §9 checklist. T: the progress component rejects updates that have no counter source. |
| **UXP-07** | **Direct modalities keep Fiji's immediacy.** Mouse, keyboard, menu and palette actions in AC0–AC3 execute with no added confirmation. Confirmation strictness depends on modality and consequence class (A19 §4), never on whether the UI is new. | A19 §4; PER-4 ("a new UI layer is an obstacle"); A02 R-A02-02 | NFR-USE-001, FR-HIST-005, FR-NL-004 | T: a policy test (NFR-USE-001 acceptance) |
| **UXP-08** | **Keyboard first for experts.** When a document has focus, the IJ1 shortcut table runs with IJ1 precedence. Product shortcuts never shadow it (§5). Every product function is reachable by keyboard. | A04 §5.18; A02 matrix (bare keys M for PER-3/PER-4); R-A04-02 | FR-CMD-007, NFR-A11Y-001 | T: each A04 §5.18 shortcut triggers its command with document focus. A startup conflict check passes (§5). |
| **UXP-09** | **Discoverability without reorganisation.** The scientific menus stay exactly as generated by Fiji. Discovery is additive: search showing the menu path and shortcut, a palette, context panels, and the macro line shown for every action as a teaching aid. | UP-12; A02 PER-1/PER-5; A04 §7.2 (palette); A06 (napari palette) | FR-CMD-001, FR-CMD-002, FR-CMD-006, FR-NL-004 | T: every runtime catalogue command is found by its menu path and by search (FR-CMD-001 acceptance). |
| **UXP-10** | **Density with a clear hierarchy.** Information density matches Fiji's or exceeds it. Each region has one job: the status line is split into separate read-out, activity and engine slots. Hierarchy: image > target and subtitle strip > active panel > secondary panels > chrome. | A04 §5.1, §5.17 (one status line used for everything); PER-2/PER-3/PER-4 (A02) | FR-VIEW-002, FR-VIEW-003, FR-SYS-006 | I: no region carries two unrelated message streams. The read-out is never overwritten by progress text. |
| **UXP-11** | **Progressive disclosure by layout, never by removing capability.** Persona presets change which panels are visible and how much explanation is expanded. They never hide menus, commands or shortcuts. | A02 beginner/expert table; PER-4 versus PER-1/PER-5; CR-A02-05 | FR-NL-013, FR-CMD-001 | T: in every preset, every catalogue command remains reachable by menu and search. |
| **UXP-12** | **Reversibility is visible before and after acting.** Undo depth and the snapshot budget are visible. An action that cannot be undone says so *before* it runs. Rollback and partial states are shown on the document. | UP-03; A19 §3, §5–§6 | FR-HIST-004, FR-HIST-005, FR-HIST-006, NFR-PERF-003 | T: the FR-HIST-005 acceptance. D: the budget meter is visible (NFR-PERF-003). |
| **UXP-13** | **Every action is reproducible text.** Each history entry shows its exact macro line, or an explicit *not macro-expressible* marker. Product UI never shows approximate macro text. | UP-10, UP-15; A04 §5.16; A18 §Constraints; PER-3 | FR-HIST-001, FR-HIST-002, FR-HIST-003 | R: E7 replay (A30). I: no history row lacks either a macro line or the marker. |
| **UXP-14** | **Legacy windows are first-class guests.** Plugin and IJ1 windows are never hidden or faked as embedded. They are tracked, badged as external, linked to the step that opened them, and one action brings them to the front. | A04 §5.15; A15; PER-6; PAR-037/038/045 | FR-PLUG-001, FR-PLUG-002 | D: TrackMate and Cell Counter appear in the tracker (FR-PLUG-001 acceptance). |
| **UXP-15** | **Accessibility and scaling are baseline, not a mode.** All product-rendered controls have names, roles, values and keyboard operation. Contrast meets WCAG 2.1 AA. Layout is correct at 100/150/200 %. | UP-13, UP-14, UP-19; A04 §5.3, §5.9, §5.10 (painted canvases) | NFR-A11Y-001, NFR-A11Y-002, NFR-PLAT-002 | T: an accessible-tree inspection; contrast checks on tokens; renders at the three scales. |
| **UXP-16** | **Natural language and voice are auxiliary.** The command bar is one row beside the menus, never the centre of the window. NL and voice produce previews of catalogue commands, never direct execution of data changes. The whole product works with NL and voice ignored. | brief §1.4; R-004 (`risks.csv`); A17 §8; PER-4 | FR-NL-004, FR-NL-013, FR-SPEECH-002, FR-SPEECH-004 | D: all MVP journeys are completed with the command bar collapsed (FR-NL-013 acceptance). |

## 3. Density and visual hierarchy

### 3.1 Hierarchy levels (PROPOSED)

| Level | Content | Rule |
|---|---|---|
| L0 Image | The rendered plane, overlay, ROI and threshold LUT | Largest area. Achromatic surround (§7.2). Nothing product-coloured is drawn over it (UXP-01). |
| L1 Target strip | Document title, ID, subtitle (FR-VIEW-002), zoom %, state markers | Always visible per viewer pane. Selectable text. Never truncates the C/Z/T part first (truncation order is in D13 §5). |
| L2 Active panel | The form or panel bound to the action in progress (Inspector, B&C, Threshold, plan sheet) | One active panel at a time. The focus ring shows where keyboard input goes. |
| L3 Secondary panels | Results, ROI Manager, History, Channels, Log | Tabbed docks. Collapsed to their tab strips by default in the expert preset. |
| L4 Chrome | Menu bar, tool palette, command bar, status bar | Compact. No headings larger than one step above body text (§7.4). |

### 3.2 Density rules (PROPOSED)

- **Rows and fields.** Table rows, list rows and form fields use the compact density token (`size.row.compact`). Their information density is at least that of the Fiji counterparts: Results (`a04-06`), ROI Manager (`a04-05`) and the `GenericDialog` field list (`a04-17`).
- **Precision.** Numeric cells use tabular (monospaced) digits. Displayed precision follows the Fiji *Decimal places* setting, as presentation only; the full value is available on demand (FR-MEAS-003; D14 §4.4).
- **Fixed-width text.** Macro text, option strings, command IDs and file hashes are shown in the monospace face.
- **No ornament.** No illustration, empty-state artwork or decorative iconography. Empty states are one line of text that says what is empty and the action that fills it (for example, "No ROIs for *blobs.gif* — draw a selection and press `t`").
- **Fixed slots.** The status bar has fixed slots (D12 UI-12): read-out | activity | dialogs/legacy windows | undo budget | engine. A message never overwrites a different slot's content. This replaces the single shared line criticised in A04 §5.17.

## 4. Terminology rules (NFR-USE-004)

| Rule | Detail | Evidence |
|---|---|---|
| T-01 | Commands are shown by their exact runtime label, including `...` and shortcut decorations (`Next Slice [>]`, `Original Scale[4]`). Whitespace collisions are resolved by `command_id`; the label is displayed as stored (FR-CMD-003). | A04 §5.2; A08 (26 trim collisions) |
| T-02 | Parameter fields show the `GenericDialog` label (for example `Sigma (Radius):`) together with the macro key (`sigma`). Neither is paraphrased. | A04 §5.19; `commands-core-parameters.csv` `parameter_key` |
| T-03 | Results columns use Fiji's names (`Area`, `Mean`, `IntDen`, `RawIntDen`, …). | A02 matrix; FR-MEAS-005 |
| T-04 | The per-document ROI list panel is titled **"ROI Manager"**, followed by the document title, because it mirrors `RoiManager` for macros (FR-ROI-004). It is not renamed to "Layers" or "Annotations". | A04 §5.8; A02 (ROI Manager M for PER-2/3/4) |
| T-05 | Product-coined terms are limited to: **Document** (an image open in the workspace, with a `docId`), **History**, **Plan** (an NL/voice-derived command sequence), **Activity** (running and recent operations), **Fiji window** (a legacy window), **Snapshot** (an undo state), **Preset** (a layout). Each appears in `GLOSSARY.md` (the lead merges it). | — |
| T-06 | Lifecycle states are presented with plain words mapped 1:1 to the engine vocabulary: *Queued*, *Checking*, *Needs confirmation*, *Running*, *Waiting for Fiji dialog*, *Done*, *Failed*, *Cancelled*, *Restored*, *Partial*. The mapping is in D15 §2. | Brief engine contract |

## 5. Keyboard ownership (UXP-08)

| Focus zone | Who owns plain and Shift keys | Who owns Ctrl/Cmd+key | Evidence / requirement |
|---|---|---|---|
| **Document** (viewer has focus) | IJ1 precedence: text-ROI typing → macro shortcuts → menu shortcuts (bare when `Prefs.requireControlKey` is false; Shift = `keyCode+200`) → hard-coded keys (arrows, Tab, Esc, Delete, Space, `+ - = , . ; /`) | IJ1 menu shortcuts | A04 §5.18; `ij/ImageJ.java#L425-L579`; FR-CMD-007 |
| **Text input** (command bar, form field, table cell editor, transcript) | The text component | Standard editing keys (copy, paste, select all, undo *within the field*) | Swing conventions; R-A04-02 |
| **Panels and lists** (non-text) | Panel navigation (arrows, Home/End, Space/Enter to activate) | IJ1 menu shortcuts still fire through the menu bar | NFR-A11Y-001 |
| **Global** (any zone) | none | Product shortcuts in the reserved space below | — |

**Reserved product shortcut space (PROPOSED, DP-D11-02):** `Ctrl+Alt+<key>` and `F6`/`Shift+F6` (region cycling).

- **Conflict check.** At start-up, CMP-04 checks each product binding against the runtime tables `Menus.getShortcuts()` and `Menus.getMacroShortcuts()`. A conflicting product binding is disabled and listed in the Log (UI-20). The IJ1 binding is never the one disabled.
- **Fiji's search key.** `l` keeps its Fiji meaning, *Focus Search Bar*, which here focuses the command bar (A04 §5.18; `SearchBarHacker.java#L201-L206`).
- **Redo.** Shift+Z is *Channels Tool* and lowercase y is *Selection Properties*, so `Ctrl+Shift+Z` and `Ctrl+Y` conflict with IJ1 shortcuts. Redo therefore uses `Ctrl+Alt+Z` (D15 §8).
- **Enter.** In IJ1, Enter on a document brings the main window to the front (`WindowManager.toFront`). In the workspace, the main window's role (command surface) belongs to the command bar, so Enter with document focus focuses the command bar (D12 §6.3).

## 6. Accessibility and scaling

| Rule | Detail | Requirement |
|---|---|---|
| A-01 | Every product control is a Swing `JComponent` with `AccessibleContext` name, role, description and value. Tool buttons expose the tool name (for example "Rectangle, variant: Rounded Rectangle"). Toggle states expose `AccessibleState.CHECKED`. | NFR-A11Y-001 |
| A-02 | The image viewer exposes an accessible description: document title, the subtitle string (FR-VIEW-002), zoom, and the current read-out string (FR-VIEW-003). The read-out slot is a focusable read-only text field, so a screen reader can read it. | NFR-A11Y-001; UP-14 |
| A-03 | Tables (Results, History, ROI Manager) are `JTable`/`JList`-based, with header names and cell values exposed. They replace the inaccessible painted `TextPanel` (A04 §5.9). | NFR-A11Y-001; PAR-048 |
| A-04 | Text contrast is at least 4.5:1 (WCAG 2.1 SC 1.4.3). Non-text UI components and state indicators are at least 3:1 (SC 1.4.11). Focus indicators are always visible (SC 2.4.7). These thresholds come from WCAG 2.1 AA, which NFR-A11Y-002 adopts. | NFR-A11Y-002 |
| A-05 | No state is conveyed by colour alone (SC 1.4.1). Each semantic state token (§7.3) is paired with an icon shape and a text label. | NFR-A11Y-002 |
| A-06 | Everything is keyboard-operable, including the tool palette, tool variants and options, dock tabs, the splitter, the plan sheet and banners. Regions are cycled with F6. | NFR-A11Y-001 |
| A-07 | Motion: there are no decorative animations. The only permitted motion is the indeterminate activity indicator (D14 §4.10). It honours a "reduce motion" preference by showing static text instead. | NFR-USE-002 |
| A-08 | Legacy AWT windows remain inaccessible and are not HiDPI-correct. The workspace does not hide this: PAR-048 is *partial* and PAR-052 is *unsupported*. | PAR-048, PAR-052 |
| H-01 | All sizes are logical-pixel tokens. Device scaling is left to Java2D. The pinned launcher sets `-Dsun.java2d.uiScale=true`, and P87 recorded a normalising transform of 1.333 (**VERIFIED**). | NFR-PLAT-002 |
| H-02 | Image pixels follow IJ1 magnification semantics in logical pixels. Interpolation follows the IJ1 rule: none at magnification 1, render quality below 1, nearest neighbour above 1 unless `Prefs.interpolateScaledImages` is set (`ij/gui/ImageCanvas.java#L250-L260`, **VERIFIED**). At fractional OS scaling, nearest-neighbour blocks are non-uniform in device pixels (**INFERRED**). D13 §3.4 records this as a validation item. | NFR-PLAT-002, FR-VIEW-001 |
| H-03 | Tool and state icons must stay sharp at 150 % and 200 %. They are either vector-rendered or supplied as multi-resolution images at 1×/1.5×/2×. The FlatLaf core jar has no SVG icon class: there are no `svg` entries in `flatlaf-3.6.1.jar`, and `flatlaf-extras` is not installed (**VERIFIED**, jar listing 2026-10-17). Batik 1.19 jars *are* present in `Fiji/jars/` (**VERIFIED**). The icon mechanism is therefore a choice for the UI review: Java2D-drawn icons, multi-resolution PNGs, or Batik. Adding a dependency is subject to NFR-PKG-001/003. | NFR-PLAT-002 |

## 7. Visual direction: tokens and rules only

> **Provisional.** Colour values, icon set, typeface choice and spacing values are deferred to a dedicated UI review (DP-D11-04). This section fixes token names, semantics and constraints only.

### 7.1 Base

- **Look-and-feel.** FlatLaf, which is already Fiji's active look-and-feel (**VERIFIED**, P87; A21 V6). FlatLaf is JVM-global through `UIManager`, so the workspace, the Script Editor and SciJava Swing windows share one look-and-feel (**INFERRED** from `UIManager` semantics).
- **AWT windows.** IJ1 AWT windows (`GenericDialog`, `ImageWindow`, `PlugInFrame`) are unaffected by FlatLaf (A05 UP-19). They keep their native look.
- **Theme customisation.** Tokens are realised as FlatLaf UI defaults or properties (the jar ships 12 `.properties` theme files, **VERIFIED** by listing). No custom Swing UI delegates are written unless a token cannot be expressed otherwise (**PROPOSED**; NFR-MAINT-001 spirit).

### 7.2 Neutral chrome (bias-free colour perception)

| Token | Semantics | Rule |
|---|---|---|
| `surface.canvas` | Background around and behind the image when it is smaller than the viewport | **Achromatic** (R=G=B). Same value in light and dark themes. Its luminance is distinct from pure black and pure white, so image edges stay visible on both dark-field and bright-field images. |
| `surface.chrome`, `surface.panel`, `surface.raised` | Window, dock and popup backgrounds | Achromatic (saturation 0). |
| `border.*`, `text.primary`, `text.secondary`, `text.disabled` | Separators and text | Achromatic. |
| `accent.focus`, `accent.selection` | Keyboard focus ring and selected rows/tabs | The only chromatic chrome tokens. They are **never adjacent to the canvas**: the focused viewer is marked by its target strip and outer frame (D12), not by a coloured canvas border touching image pixels. |

Rationale: a chromatic surround shifts the perceived colour and brightness of adjacent image regions, a simultaneous-contrast effect that is standard perception science but is **INFERRED** here, not measured. Microscopy display decisions (B&C, LUT) must not be biased by chrome (UP-07, PER-1).

### 7.3 Semantic state colours

Each token has a fixed **icon shape** and **text label** (A-05). The tokens are never drawn on the canvas.

| Token | Meaning | Paired cue | Used by |
|---|---|---|---|
| `state.neutral` | Idle, queued, informational | Hollow circle; "Queued" | D15 |
| `state.running` | An operation is executing | Activity indicator; "Running" | D15 |
| `state.success` | Completed **and** postconditions passed | Check mark; "Done" | D15; FR-PROC-005 |
| `state.warning` | Requires attention but nothing failed: AC4, lossy save, partial result, global differs from default | Triangle; text | D14, D15 |
| `state.error` | Failed, lost or invalid | Cross; text | D15, D17 |
| `state.attention` | Waiting for the user: needs confirmation, waiting for Fiji dialog | Hand or bell; text | FR-PROC-007, FR-NL-004 |
| `state.external` | A Fiji window or an observed action | Window glyph; "Fiji window" or "Observed" | FR-PLUG-001, FR-HIST-008 |
| `state.recording` | Microphone capture active | Filled dot; "Recording" and elapsed seconds | FR-SPEECH-002 |
| `state.preview` | Preview or comparison, not applied | Dashed outline; "Preview — not applied" | FR-PROC-010; D15 |
| `marker.pixels` | Control changes pixel data | Glyph and "changes pixels" | UXP-05 |
| `marker.consequential` | Parameter affects results (catalogue `consequential=yes`) | Glyph and "affects results" | FR-NL-003, FR-PROC-003 |

**Canvas rule.** Image-state overlays keep IJ1's own colours:

- the ROI colour (`Roi.getColor()` defaults);
- Overlay stroke and fill colours;
- the threshold LUT red;
- the Show All label colours.

FR-VIEW-001 requires IJ1-equivalent rendering, so product theme tokens never recolour these (**PROPOSED**).

**Constraint.** The `state.*` values must be distinguishable for the common colour-vision deficiencies. Shape and text already carry the meaning (A-05), so this is a secondary safeguard. FR-WS-003 (a colour-blind-safe channel option) concerns *image LUTs* and is handled in D13 §8.

### 7.4 Typography scale principles

- **Typeface.** One UI typeface: the FlatLaf default, which is the platform UI font. One monospace face for numbers, macro text, IDs and hashes.
- **Scale.** At most four steps: `type.small` (dense tables, secondary metadata), `type.body`, `type.strong` (panel titles; same size as body), `type.title` (one step larger; the start-up screen title and dialog titles only).
- **No large headings.** No display or hero sizes (AP-01).
- **Numbers.** Tabular digits wherever numbers are compared in columns: Results, the read-out, the History duration column.
- **Respect user settings.** Sizes scale with the OS scale (H-01) and with FlatLaf's default font size. They are never hard-coded in device pixels.

### 7.5 Dark/light policy (PROPOSED, DP-D11-01)

1. **Default Light.** Light (FlatLaf Light) is the default because it is Fiji's default (P87). Changing it would visually separate the workspace from the Script Editor and the SciJava windows that share the JVM.
2. **Single control.** The theme is a JVM-wide setting that follows Fiji's own *Edit › Options › Look and Feel* (`org.scijava.ui.swing.laf.OptionsLookAndFeel`, A05 UP-19). The workspace does not add a second, conflicting theme switch.
3. **Dark is optional.** Dark (`FlatDarkLaf`, present in the jar) is supported for product surfaces at PARITY tier. Legacy AWT windows stay light (PAR-052), so a dark workspace will show light legacy windows. The design accepts this; it does not hide it.
4. **Theme-independent canvas.** `surface.canvas` and image rendering are identical in both themes.
5. **No OS following at MVP.** Following the OS theme automatically is not designed at MVP (no requirement).

## 8. Anti-patterns (must not appear)

AP-01 to AP-05 are the patterns named in brief §1.4, as recorded in F02, A20 and `risks.csv` R-004. AP-06 to AP-09 are derived from requirements.

| ID | Anti-pattern | Why it is rejected | Required instead |
|---|---|---|---|
| **AP-01** | **Hero headings** or marketing copy, for example the prototype's "Give an instruction. See the image change." (F02) | Wastes primary space (UXP-01). Marketing tone breaks F04 §5 writing rules. | Compact chrome; the document area starts directly under the command bar (D12 W-1) |
| **AP-02** | **Prompt-box-centric layout**: a large text area as the central element, with the image subordinate | Inverts the image-primary hierarchy (UXP-01, UXP-16). PER-4 would see it as an obstacle (A02). R-004 records the risk. | One-row command bar (UI-11) beside the menus; the NL plan opens as a sheet (UI-18) |
| **AP-03** | **Static before/after images** (the prototype's two `<img>` figures, F02) | Cannot be zoomed, inspected or read out, and is not the live state (FR-VIEW-001, FR-VIEW-003). It invites visual-similarity judgements, which NFR-FID-003 rejects as evidence. | Comparison as a live viewer mode on real documents and snapshots (D15 §6) |
| **AP-04** | **Fake timelines**: event lists composed by the UI or animated on a timer, rather than built from engine lifecycle events and `HistoryRecord`s | Untruthful (UXP-06). The prototype polled every 350 ms and drew a timeline (F02). | History (UI-10) and Activity (UI-12) built only from IF-02 events and records (D15) |
| **AP-05** | **Decorative dashboards**: statistic tiles, summary charts or KPI rows that no requirement asks for (for example the prototype's "stats table" beside previews, F02) | They compete with the image and duplicate Results without full precision (FR-MEAS-003) | Results panel (UI-08) with Fiji columns |
| AP-06 | Simulated, time-estimated or eased progress; ETA from elapsed time | NFR-USE-002 forbids it | Determinate only from a counter; otherwise indeterminate (D15 §4) |
| AP-07 | Renaming or regrouping scientific commands ("Blur" for `Gaussian Blur...`, a custom "Segmentation" menu) | NFR-USE-004, FR-CMD-001; breaks tutorials (A02 Finding 7) | Search, aliases in search only (FR-CMD-006), unchanged menus |
| AP-08 | Wizards or modal multi-step flows wrapping single Fiji commands | Adds steps to AC0–AC3 actions (NFR-USE-001); hides the `GenericDialog` mapping | Non-modal schema form (UI-07); legacy dialog fallback |
| AP-09 | Colour-only state; colour on the canvas for product state | A-05; FR-VIEW-001 | Shape and text plus token; state outside the canvas |

## Constraints and Limitations

- FlatLaf only styles Swing. The AWT parts of Fiji (A04 §5.15) keep their look and their accessibility gaps (PAR-048, PAR-052).
- WCAG contrast can only be checked once token *values* exist, which happens at the UI review (DP-D11-04). This document defines what will be checked, not the result.
- Personas are unvalidated archetypes (A02 R-A02-01). The presets in UXP-11 are hypotheses for usability testing (D18).

## Requirement gaps

| ID | Gap | Suggested requirement (candidate; not created here) |
|---|---|---|
| GAP-D11-01 | No requirement covers product shortcut conflicts with user-installed macro shortcuts beyond IJ1 precedence (FR-CMD-007). | "Product shortcuts shall be checked against the runtime IJ1 shortcut tables at start-up, and conflicting product bindings shall be disabled and reported." |
| GAP-D11-02 | No requirement states a theme policy (light/dark) or the canvas-surround constraint. | "Chrome adjacent to the image canvas shall be achromatic in all themes." |
| GAP-D11-03 | NFR-A11Y-001 does not say how the image canvas exposes its content to assistive technology. | "The viewer shall expose the document title, subtitle and current read-out as accessible text." |
| GAP-D11-04 | No requirement covers reduced motion. Motion is minimal by design (A-07), but this is not mandated. | Optional: an NFR-A11Y item for a reduce-motion preference |

## Decisions proposed

Each item below is a candidate DEC or ADR.

| ID | Decision | One-line rationale |
|---|---|---|
| DP-D11-01 | Default to FlatLaf Light, follow Fiji's own Look and Feel option, and add dark at PARITY with a theme-independent canvas surround | Matches the verified default (P87) and shared-JVM windows. Avoids a second theme switch. |
| DP-D11-02 | Reserve `Ctrl+Alt+<key>` and F6 for product shortcuts, with a start-up conflict check against IJ1 tables | Every bare, Shift and Ctrl letter may be taken by IJ1 or macro shortcuts (A04 §5.18) |
| DP-D11-03 | Keep the panel title "ROI Manager" (per document) and the Fiji names for all mirrored concepts | NFR-USE-004. The panel *is* the `RoiManager` view for macros (FR-ROI-004). |
| DP-D11-04 | Defer all colour, icon and spacing values to a dedicated UI review. Freeze only token names and rules now. | Avoids premature aesthetics. AE-01 is not a requirement driver. |
| DP-D11-05 | Adopt anti-patterns AP-01…AP-09 as review gates for every UI change | Brief §1.4; NFR-USE-002 |

## References

- A02 Users and Research Personas; A04 Complete Existing Fiji UI Audit (screenshots `docs/01-analysis/references/screenshots/a04-*.png`); A05 Usability and Workflow Problems; A06 Existing Solutions; A17 §8; A18; A19 §3–§4; A20 §1; A21 (V6, O2); A26; A27; A28 and `docs/data/parity-matrix.csv` (PAR-013, PAR-048, PAR-052); F02 §Frontend; F04; `docs/data/risks.csv` (R-004).
- D01 Architecture Overview (CMP-01…CMP-12).
- `imagej/ImageJ@v1.54p` (03382be1): `ij/ImageJ.java#L425-L579`; `ij/gui/ImageCanvas.java#L250-L260`; `ij/Menus.java`.
- `imagej/imagej-legacy@imagej-legacy-3.0.0` (fc31a563): `net/imagej/legacy/search/SearchBarHacker.java#L201-L206`.
- Probe `probes/P87-swing-javafx-webview.out.txt` (FlatLaf Light active; uiScale transform).
- Installed jar listing: `Fiji/jars/flatlaf-3.6.1.jar` (theme classes; 12 `.properties` files), checked 2026-10-17.
- W3C, *Web Content Accessibility Guidelines (WCAG) 2.1*, https://www.w3.org/TR/WCAG21/ (accessed 2026-10-17). SC 1.4.1, 1.4.3, 1.4.11 and 2.4.7 are used as the adopted target standard (NFR-A11Y-002).
