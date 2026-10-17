---
id: D14
title: Component and Interaction System
stage: 02-design
status: Draft (provisional on DEC-010)
priority: P0
source_revisions: [imagej/ImageJ@v1.54p (03382be1), "Fiji runtime pin f6695397 (fiji-2.18.1-SNAPSHOT / ij-1.54p)", "docs/data/commands-core-parameters.csv (schema columns)"]
related_requirements: [FR-CMD-001, FR-CMD-003, FR-CMD-004, FR-CMD-005, FR-CMD-006, FR-CMD-007, FR-SYS-006, FR-PROC-003, FR-PROC-004, FR-PROC-007, FR-PROC-008, FR-PROC-010, FR-PROC-013, FR-PROC-014, FR-PROC-016, FR-MEAS-001, FR-MEAS-002, FR-MEAS-003, FR-MEAS-005, FR-ROI-004, FR-ROI-006, FR-HIST-001, FR-HIST-002, FR-HIST-003, FR-HIST-004, FR-HIST-005, FR-HIST-007, FR-HIST-008, FR-MACRO-002, FR-MACRO-006, FR-IMG-006, FR-IMG-009, FR-NL-002, FR-NL-003, FR-NL-004, FR-NL-005, FR-NL-008, FR-NL-011, FR-NL-013, FR-SPEECH-002, FR-SPEECH-004, FR-PLUG-001, FR-PLUG-002, NFR-USE-001, NFR-USE-002, NFR-USE-004, NFR-A11Y-001, NFR-A11Y-002, NFR-PLAT-002, NFR-PERF-004, NFR-PERF-005, NFR-PERF-006]
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# D14 — Component and Interaction System

## Executive Summary

This document defines the reusable controls of the workspace. For each one it gives its responsibilities, anatomy, states, keyboard behaviour and the requirements it serves. All controls share **one interaction-state model** with seven core states: idle, hover, focus, disabled, busy, error, awaiting confirmation. Two qualifiers, *stale* and *preview*, are defined in D15.

**Central controls:**

| Control | Key properties |
|---|---|
| **Schema-driven parameter form** (UI-07) | Built from `CommandSchema` (IF-04, from `commands-core-parameters.csv`). Every key is explicit (FR-PROC-003). Consequential markers (FR-NL-003). Units and calibration conversion. Visible result-affecting globals (FR-MEAS-002). Preview toggle (FR-PROC-010). A live macro line from the single macro generator (FR-HIST-002). |
| **Confirmation sheet** (UI-18) | One component with four variants: NL/voice plan (FR-NL-004), irreversible action (FR-HIST-005), file safety (FR-IMG-006/009), macro capability (FR-MACRO-002) |
| **Truthful indicators** | Progress (NFR-USE-002), dialog pending (FR-PROC-007), recording (FR-SPEECH-002), external window (FR-PLUG-001) |

Swing notes appear only as constraints (§5). There is no code. Provisional on DEC-010.

## Purpose and Scope

**In scope:** component responsibilities, anatomy, states, keyboard behaviour and accessibility per component.

**Out of scope:**

| Topic | Owner |
|---|---|
| Layout | D12 |
| Viewer gestures | D13 |
| Mapping of lifecycle states to visuals | D15 |
| Token values | D11 DP-D11-04 (UI review) |

## Sources and Evidence

| Source | Use |
|---|---|
| A04 §5.8 (ROI Manager), §5.9 (Results, `TextPanel`), §5.16 (Recorder), §5.19 (`GenericDialog`), screenshots `a04-05`, `a04-06`, `a04-13`, `a04-16`, `a04-17` | The legacy controls being replaced and their contracts (**VERIFIED**) |
| `docs/data/commands-core-parameters.csv` columns `command_id, parameter_key, type, default, range_or_choices, recorded_as, source, consequential, native_unit, hidden_global_dependency` (213 rows) | Form schema input (**VERIFIED** header) |
| A17 §4, §7, §8 | Consequential defaults; ambiguity classes; confirm/clarify policy |
| A19 §3–§4 | Consequence classes and confirmation by modality |
| A20 §1 | Truthfulness classes |
| A12; A25 | Macro capability detection (`ij.macro.Tokenizer`) |
| P37, P81 | CSV rounding; Recorder loss |
| P87 (1c) | A lightweight `JPanel` painting `imp.getBufferedImage()` inside a FlatLaf `JFrame` works in the pinned Fiji (**VERIFIED**) |
| `Fiji/jars/` listing | `miglayout-swing-5.3`, `rsyntaxtextarea-3.6.0`, `swingx-1.6.1` present (**VERIFIED**) |

## 1. Interaction-state model

Every interactive component implements the applicable subset of these states. The visual treatment uses D11 tokens. Colour is never the sole cue (D11 A-05).

| State | Definition | Visual rule | Keyboard | Accessibility mapping (Java Accessibility) |
|---|---|---|---|---|
| **Idle** | Available, not engaged | Base tokens | Reachable in traversal order | `ENABLED`, `FOCUSABLE` |
| **Hover** | Pointer over an actionable element | `surface.raised`; tooltip after the platform delay. Tooltips carry the exact command label, menu path and shortcut where relevant. | Not applicable; tooltips are also reachable with Ctrl+F1 (Swing default) | Tooltip text is mirrored in `AccessibleDescription` |
| **Focus** | Has keyboard focus | `accent.focus` ring, always visible (WCAG SC 2.4.7). It never touches the canvas (D11 §7.2). | Receives keys per the D11 §5 zone rules | `FOCUSED` |
| **Disabled** | The action is impossible in the current state | `text.disabled`. The reason is in the tooltip and the accessible description ("Requires an image", "Engine not ready"). **Scientific menu items are never disabled** (D12 M-03); only product controls are. | Disabled controls stay in traversal order where Swing allows, so the reason can be read. Otherwise the reason is repeated in the containing panel's description. | `ENABLED` absent, plus a description |
| **Busy** | The component's action is in progress (QUEUED, VALIDATING or RUNNING for an operation it started) | Inputs locked. The activity indicator (§4.10) plus a state label. The *Cancel* control stays enabled (FR-PROC-013). | Esc cancels per the D12 §6.1 stack | `BUSY` |
| **Error** | The last action from this component failed, or an input is invalid | `state.error` glyph plus message *adjacent* to the cause: the field for `VALIDATION_FAILED`, the header for `PRECONDITION_FAILED` or `EXECUTION_FAILED` (D15 §7). It persists until the user edits or dismisses it. | Focus moves to the first invalid field on submit | `AccessibleDescription` contains the message; the error is announced through the accessible-property change |
| **Awaiting confirmation** | The engine returned `CONFIRMATION_REQUIRED` or `CAPABILITY_CONFIRMATION_REQUIRED`, or policy requires confirmation (A19 §4) | `state.attention` glyph. The confirm control is explicit and is not the default for AC4/AC6/AC7. Its text names the consequence ("Run — cannot be undone"). | Enter confirms only when the confirm control has focus or is the default (AC2/AC3 plans, A17 §8). Esc cancels. | `AccessibleDescription` states the consequence |

Qualifiers (D15):

- **Stale**: the view shows data from before a running operation, or before the digest arrives.
- **Preview**: the content is a preview or comparison and has not been applied.

## 2. Keyboard behaviour baseline

| Rule | Behaviour | Requirement |
|---|---|---|
| K-01 | F6 / Shift+F6 cycles regions (D12 §6.1). Tab / Shift+Tab moves within a region, except with document focus, where Tab is IJ1 *Put Behind* (D12). | NFR-A11Y-001 |
| K-02 | **Esc** resolves in this order, stopping at the first that applies: (1) close an open popup or list; (2) cancel an in-field edit; (3) close the confirmation sheet without executing; (4) with document focus, abort the ROI gesture (D13 E13); (5) cancel the running operation targeting the focused document (D15). A single Esc never discards a dirty form; that needs the explicit Cancel. | CR-A04-13, FR-PROC-013 |
| K-03 | **Enter** activates the default button of the focused container. In forms the default is OK, except when the form is AC4 (the default becomes none, D15 §5). In the plan sheet the default is Run only for AC0–AC3 plans (A17 §8). | NFR-USE-001, FR-HIST-005 |
| K-04 | Every button that has an IJ1 shortcut displays it, for example ROI Manager `Add [t]`, `Flatten [F]` (A04 §5.8). | FR-CMD-007, NFR-USE-004 |
| K-05 | Product accelerators use only the reserved space (D11 DP-D11-02) and are listed in the *Workspace* menu. | FR-CMD-007 |
| K-06 | Lists and tables: arrows, Home/End, PgUp/PgDn, Shift for range, Ctrl for toggle selection, Space to toggle checkboxes, Ctrl+C to copy (§4.4). | NFR-A11Y-001 |
| K-07 | Context menus open with Shift+F10 or the Menu key wherever right-click works. Tool variants are reachable this way (D13 E32). | NFR-A11Y-001 |

## 3. Component catalogue

| ID | Component | Surface(s) | CMP | Requirements |
|---|---|---|---|---|
| C-01 | Generated menu bar and menus | UI-02 | CMP-04 | FR-CMD-001/002/004/007 |
| C-02 | Command bar and palette | UI-11 | CMP-04, CMP-09 | FR-CMD-001/005/006, FR-NL-004/013 |
| C-03 | Schema-driven parameter form | UI-07 | CMP-05 | FR-PROC-003/004/008/010, FR-MEAS-002, FR-NL-003, FR-VIEW-006, FR-HIST-005 |
| C-04 | Data table (Results) | UI-08 | CMP-06 | FR-MEAS-002/003/005 |
| C-05 | ROI list | UI-09 | CMP-07 | FR-ROI-004/005/006 |
| C-06 | History list and macro view | UI-10 | CMP-08 | FR-HIST-001…010 |
| C-07 | Toasts and banners | all | CMP-10 | NFR-REL-005, FR-HIST-008 |
| C-08 | Confirmation sheet (plan preview and variants) | UI-18 | CMP-09; reused by CMP-05, CMP-08 | FR-NL-002…008, FR-MACRO-002, FR-HIST-005, FR-IMG-006, FR-IMG-009, FR-SPEECH-004 |
| C-09 | Dialog-pending banner | UI-12, workspace | CMP-10, CMP-11 | FR-PROC-007 |
| C-10 | Progress and activity indicator | UI-12, forms, sheet | CMP-10 | NFR-USE-002, FR-PROC-013 |
| C-11 | Recording indicator and voice control | UI-11, UI-12 | CMP-09 | FR-SPEECH-002, FR-SPEECH-004 |
| C-12 | External-window badge | UI-04 tabs, UI-10, UI-16 | CMP-11 | FR-PLUG-001 |
| C-13 | Target strip | UI-05, C-03, C-08 | CMP-02 | FR-IMG-002, FR-VIEW-002 |
| C-14 | Tool palette | UI-03 | CMP-03 | FR-ROI-001…003, NFR-A11Y-001 |
| C-15 | Dimension slider | UI-06 | CMP-02 | FR-VIEW-004 |
| C-16 | Globals table | UI-17, C-03 section | CMP-12 | FR-MEAS-001/002, FR-HIST-007 |

## 4. Components

### 4.1 C-01 Menus (UI-02)

| Aspect | Specification |
|---|---|
| Responsibilities | Render the catalogue-generated hierarchy (D12 §5). Show IJ1 shortcuts and checkbox states. Mark shadowed labels (FR-CMD-004). Dispatch to the D12 §5.3 routing table, or submit `ActionRequest kind=COMMAND` with the focused document as the target. |
| Before the engine is ready | The scientific menus show one disabled item, "Loading Fiji commands…", until the catalogue is built. This is the truthful state of FR-SYS-006; menus are not pre-filled from a stale cache. The *Workspace* and *Help* menus work. |
| Activation outcome | A command with a schema opens C-03. A command without parameters submits immediately (AC0–AC3 have no added confirmation, NFR-USE-001). A command without a schema submits through R1 and may surface a legacy dialog (C-09). |
| States | Idle, hover, focus. Busy is not shown on menus (it appears in C-10). Disabled only for *Workspace* items, with a reason. |
| Keyboard | F10/Alt; mnemonics only where Fiji has them (none in IJ1 AWT menus; none are invented, NFR-USE-004); arrows; Enter. |
| Accessibility | `JMenuItem` names = exact labels; accelerator text exposed. |

### 4.2 C-02 Command bar and palette (UI-11)

| Aspect | Specification |
|---|---|
| Responsibilities | One text field with two explicit outcomes: (a) **run a catalogue command** found by search; (b) **interpret the text as an instruction** (NL). The user always chooses which; nothing is guessed silently. |
| Search | Uses the SciJava search infrastructure (`scijava-search-3.0.2`) through CMP-04 where it can deliver catalogue rows; otherwise the reason is recorded (FR-CMD-006). Matches exact label, menu path and aliases. |
| Result row | Exact label · menu path · shortcut · compatibility category (C1–C6/S) · AC class badge (FR-CMD-005) · shadowed marker (FR-CMD-004) |
| Last row | "Interpret as instruction: '<text>'" (C-08 follows). When there is no command match, it is the only and selected row. When there are matches, the first command row is selected and the NL row is one ↓ away. **PROPOSED.** Rationale: the palette is a menu equivalent (A19 §4); NL always passes through the preview, so a mis-selection cannot execute data changes (FR-NL-004). |
| Empty query | Recents and pinned commands (D12 GAP-D12-01; optional) |
| States | Idle ("Search commands or type an instruction"); focus; typing (results update locally); **busy** while the NL interpreter runs (C-10 indeterminate, "Interpreting… 0.8 s", real elapsed time); error (`UNKNOWN_COMMAND`, interpreter refusal with reason and nearest commands, A17 §8); disabled with reason when the engine is not ready (search still works, execution is refused). |
| Keyboard | `l` or Enter from document focus focuses the bar (D12 §6.1). ↑/↓ moves through results. Enter runs the selected row. Esc clears, then returns focus to the document. Ctrl+Alt+V toggles voice (C-11; reserved space, conflict-checked). |
| Accessibility | `JTextField` plus `JList` popup with `AccessibleRole.LIST`. Each row's accessible name concatenates label, path, shortcut and class. |
| Requirements | FR-CMD-001, FR-CMD-005, FR-CMD-006, FR-NL-004, FR-NL-013 (the bar can stay collapsed; nothing else depends on it) |

### 4.3 C-03 Schema-driven parameter form (UI-07)

**Responsibilities:**

- Replace the modal `GenericDialog` (A04 §5.19; UP-16) with a non-modal form generated from `CommandSchema` (IF-04).
- Produce a **complete** option map, with every key explicit (FR-PROC-003).
- Show the target, scope, ROI, globals, units and the exact macro line before execution.
- Run the IJ1 preview where one exists (FR-PROC-010).

**Anatomy:**

```
functional wireframe — not a visual design
+-----------------------------------------------------------------+
| Gaussian Blur...                       Process > Filters   AC3  |  header: exact label, menu path, class
| Target: cells.tif (id 4) . c:2/3 z:5/12 t:1/1 . ROI: none       |  C-13 target strip
| Scope: (o) current plane  ( ) all 12 z-slices of channel 2      |  explicit 'stack' key (consequential)
|          ( ) whole stack (36 planes)                            |
+-----------------------------------------------------------------+
| Sigma (Radius):  [ 2.00 ]  [px v]   key: sigma   ◆ affects res. |  label | control | unit | key | marker
|   = 0.31 µm (pixel width 0.155 µm)          value: last used    |  conversion line | value provenance
| [x] Scaled units (µm)                key: scaled ◆ affects res. |
+-----------------------------------------------------------------+
| Settings that affect this result                                |  C-16 subset (hidden_global_dependency)
|   none for this command                                         |
+-----------------------------------------------------------------+
| [x] Preview — on a copy, not applied, not recorded              |  only if the command has an IJ1 preview
+-----------------------------------------------------------------+
| run("Gaussian Blur...", "sigma=2 scaled slice");      [Copy]    |  live macro line (single generator)
+-----------------------------------------------------------------+
| [Help]                          [Cancel]          [OK]          |
+-----------------------------------------------------------------+
```

**Field rules (PROPOSED):**

| Rule | Specification | Requirement / evidence |
|---|---|---|
| F-01 Label and key | The label is the `GenericDialog` label verbatim. The macro key (`parameter_key`) is shown in monospace as secondary text. | NFR-USE-004; A04 §5.19 |
| F-02 Control by type | `number`: text field with numeric validation against `range_or_choices`. `boolean`: checkbox. The help text states the macro semantics: present = true; absent = false in macro mode (`commands-core-parameters.csv` type column). `choice`: combo box with the exact choice strings. `string`: text field. `path`: field plus chooser. Output paths carry `marker.pixels`-equivalent file-effect text (AC6). `image`: a list of open documents by title and ID, for multi-image commands (FR-PROC-008). | FR-PROC-003, FR-PROC-008 |
| F-03 Units | `native_unit` is shown beside the control. If the image is calibrated and the command supports `scaled`, the form shows the conversion line in both directions. The `scaled` key itself is an explicit, consequential field. No silent unit change. | FR-NL-008 (same rule applied to forms), A17 §3 |
| F-04 Consequential marker | Fields with `consequential=yes` carry `marker.consequential` ("affects results"). For **NL-derived** values the field cannot hold a hidden default (FR-NL-003); unfilled fields block OK with "Choose a value". For **direct** use, the field is pre-filled, as Fiji does, and the source of the value is labelled (F-05). Fiji's immediacy is kept (NFR-USE-001). | FR-NL-003, FR-PROC-003 |
| F-05 Value provenance | Each pre-filled value shows its source: *Fiji default* (catalogue `default`), *last used in this session* (from History, not from IJ1 static fields: FR-PROC-003 forbids relying on remembered statics), *from plan* (C-08 "Edit in form"), or *edited*. | FR-PROC-003; UP-08 |
| F-06 Scope | For stacks and hyperstacks, the scope (current plane, channel, z-range or whole stack) is an explicit choice that maps to keys (`slice`, `stack`). It answers the "Process all N images?" prompt by key presence. It is consequential (A17 A5). | FR-PROC-003; A04 §5.19; UP-18 |
| F-07 Globals section | Lists the `hidden_global_dependency` items with current values. A value that differs from the Fiji default is marked `state.warning` with text "differs from Fiji default". "Change…" runs the exact options command (AC5, recorded, FR-HIST-007). For Measure, Convert to Mask and 8-bit this shows the measurement set, Limit/Redirect, BlackBackground and conversion scaling (FR-MEAS-002 acceptance). | FR-MEAS-002, FR-HIST-007; UP-04/05/06 |
| F-08 Data/display note | For conversions and `Apply`-type commands, a one-line explanation of what changes pixels versus display (FR-VIEW-006). Expanded by default in the Guided preset (D12 §3). | FR-VIEW-006 |
| F-09 Preview toggle | Present only for commands with an IJ1 preview (`ExtendedPlugInFilter` + `DialogListener`). Off by default. When on, it runs on a copy (FR-PROC-010) and the viewer shows the preview label (D15 §5). It is never recorded (A04 §5.19). Preview failure appears as an error on the toggle; the document is unchanged. | FR-PROC-010 |
| F-10 Macro line | Generated by the **same** generator as History (CMP-27 via IF-05), from the current draft values, with escaping by the single escaping function (FR-MACRO-006). The form never composes macro text itself. | FR-HIST-002, FR-MACRO-006, CR-A02-04 |
| F-11 Validation | The schema is validated locally for type and range for immediate feedback (`VALIDATION_FAILED` inline). The engine re-validates at submit, together with preconditions. `PRECONDITION_FAILED` appears in the header with the required state and, where defined, an offer of the preparatory command as a separate action (A17 §8). | FR-PROC-004, FR-NL-002 |
| F-12 Irreversible | If the engine classifies the action as AC4 (snapshot over budget, or a virtual stack), an inline warning "Cannot be undone: needs <N> MB, budget <M> MB" appears and OK is relabelled "Run — cannot be undone". It is not the default button (A19 §4). | FR-HIST-005 |
| F-13 Help | Shown when the command declares a help URL (`addHelp`). It opens in the system browser on explicit action only. | — |
| F-14 Legacy fallback | Commands without a schema run through R1. If IJ1 then shows a `GenericDialog`, C-09 takes over. The form is not shown. | FR-PROC-007; A18 §5 |

**States:**

| State | Behaviour |
|---|---|
| Editing (idle/focus) | Normal |
| Invalid (error) | Inline messages; OK disabled with its reason |
| Previewing (busy, preview qualifier) | Fields stay editable; each change restarts the preview |
| Submitted (busy) | Fields locked. The header shows the lifecycle state from C-10 with a link to the History entry. Cancel becomes *Cancel operation*. |
| Awaiting confirmation | AC4 inline confirmation |
| Done | The form closes (Standard/Compact presets) or stays with "Done" (Guided). Edits are kept if the operation failed. |

Dirty-form protection: invoking another command while a dirty form is open asks *Discard changes to <label>?* (D12 §4.1).

**Keyboard:** Tab through fields in label order; Enter = OK (K-03); Esc = K-02; Alt+P toggles preview (Swing mnemonic on the product's own control, conflict-checked).

### 4.4 C-04 Data table: Results (UI-08)

| Aspect | Specification | Requirement |
|---|---|---|
| Model | An immutable snapshot of the engine's `ResultsTable` per action digest (IF-03). There are no live references (NFR-PERF-004 principle). Full `double` values are held. | FR-MEAS-003, NFR-REL-005 |
| Views | **Per document** (default; rows whose source is the focused document, from History provenance) and **All (Results)**, a mirror of the standard system table that macros see (`nResults`, `getResult`). The view selector is always visible, and the header names the document. | FR-MEAS-005; UP-09 |
| Columns | Fiji names and order (`Label`, `Area`, `Mean`, …). The row number is Fiji's 1-based display row. The label is `image:roi` when *Display label* is set. | NFR-USE-004, FR-MEAS-005 |
| Settings strip | Always visible above the table: "Measurements: <set> · Limit to threshold: on/off · Redirect: <image/None> · Decimal places: <n>", with "Change…" running `Set Measurements...` | FR-MEAS-002 |
| Precision | Cells display per *Decimal places*, as presentation only. Selecting a cell shows the full value (`Double.toString`) in the table footer. | FR-MEAS-003 |
| Copy | Ctrl+C copies TSV at **full precision**. "Copy as displayed" is a separate menu item. This deliberately differs from Fiji's copy-as-displayed, because FR-MEAS-003 requires full-precision export. **PROPOSED**; listed in DP-D14-04. | FR-MEAS-003; P37 |
| Export | "Export CSV…" writes full precision (AC6; path and hash recorded). Fiji's own `File>Save As` command on Results remains available unchanged, with Fiji's rounding. | FR-MEAS-003; FR-HIST-001 |
| Sorting | Header-click sorting is **view-only**, with a visible "sorted view" label and "Reset order". It never reorders the engine table. Fiji's `Results>Sort...` remains a command. | FR-MEAS-005 |
| New rows | Rows appended by the last action are marked with a "new" glyph until the next action. There is no animation. | NFR-USE-002 |
| Row ↔ ROI | Selecting a row with a linked ROI highlights it (D13 E40) | FR-MEAS-005, FR-ROI-004 |
| States | Empty ("No measurements for <doc>. Press `m` to measure the selection."); idle; **stale** while an action that may append rows is RUNNING (header "Updating after <command>…"); error (digest missing: "Results could not be read after <command>; see Log") | NFR-USE-002 |
| Keyboard / A11Y | `JTable` with row and column headers. K-06. | NFR-A11Y-001 |

### 4.5 C-05 ROI list (UI-09)

| Aspect | Specification | Requirement |
|---|---|---|
| Model | A snapshot of the document's hidden `RoiManager(false)` (CMP-30), refreshed from the digest | FR-ROI-004 |
| Columns | **Index** (0-based, as `roiManager("select", i)` uses it), Label (`ZZZZ-YYYY-XXXX` default, A10 §3), Type, Position (c,z,t or slice; "all" for 0), Group | FR-ROI-004, NFR-USE-004 |
| Buttons | As `RoiManager` (A04 §5.8): `Add [t]`, `Update`, `Delete`, `Rename...`, `Measure`, `Deselect`, `Properties...`, `Flatten [F]`, `More »` (Open…, Save…, Fill, Draw, AND, OR (Combine), XOR, Split, Add Particles, Multi Measure, Multi Plot, Multi Crop, Sort, Specify…, Remove Positions…, Labels…, List, Interpolate ROIs, Scale…, Rotate…, Translate…, Options…); checkboxes `Show All`, `Labels`. Each button submits the request described in D13 §10.6. | FR-ROI-004 |
| Selection semantics | IJ1 semantics: with no selection, Measure and Delete apply to all (Delete asks first, as Fiji does). Multi-selection with Shift/Ctrl. | FR-ROI-004; UP-09 |
| Order | Index order is API. There is no drag reorder. | A04 §5.8 |
| Flags | A single-position `PointRoi` shows the FR-ROI-006 flag ("position will be lost on .roi save") before Save | FR-ROI-006 |
| States | Empty (hint "Draw a selection and press `t`"); idle; busy (button action running); stale; error | — |
| Keyboard / A11Y | `JTable`; K-06; button shortcuts displayed (K-04) | NFR-A11Y-001 |

### 4.6 C-06 History list and macro view (UI-10)

| Aspect | Specification | Requirement |
|---|---|---|
| Entries | One row per `HistoryRecord`: step number · time · origin glyph (menu, key, canvas, palette, NL, voice, macro, **observed**) · exact label (or direct-operation name) · target title and ID · lifecycle outcome (D15) · AC class · duration (measured) | FR-HIST-001, FR-HIST-008 |
| Expanded row | Complete option map; ROI geometry summary; globals snapshot delta (FR-MEAS-001); input hash for opens (FR-IMG-004); the macro line(s), or "not macro-expressible: <reason>" (FR-HIST-003); for NL, the interpretation source and model identity (FR-NL-011); for observed entries, "Observed — parameters partial" and the source window (FR-HIST-008) | FR-HIST-001/003/007/008, FR-NL-011 |
| Nesting | Code actions (R4 macros) nest their observed internal commands (A18 §1). Plan steps nest under the plan. | FR-HIST-008 |
| Filter | "This document" (default in the Standard and Guided presets) / "Session" | FR-HIST-001 |
| Undo pointer | Per document: a marker line "← current state". Entries after it show "Undone" (text, not strike-through alone). Undo and Redo buttons, with a tooltip naming the step ("Undo Gaussian Blur... (step 4)"). An unavailable undo states its reason ("Step 3 cannot be undone: snapshot over budget"). | FR-HIST-004/005/006 |
| Macro view | Read-only generated macro for the selected range or the whole session, in monospace, with syntax colouring (RSyntaxTextArea is bundled; §5 SW-06). Actions: **Export .ijm…** (AC6; FR-HIST-002), **Replay…** (C-08 AC7-free variant, because the content is product-generated: A19 §4 "macro replay" row), **Open in Script Editor** (legacy window, tracked), **Show in Fiji Recorder** (mirror via `Recorder.recordString`; FR-HIST-009). | FR-HIST-002, FR-HIST-009, FR-HIST-010 |
| States | Entries update live through the lifecycle (D15). The panel itself: idle; stale (digest pending); error (an export failure is shown in the export row) | NFR-REL-005 |
| Keyboard / A11Y | `JTable` or tree-table; Enter expands; K-06; Ctrl+C copies the macro line | NFR-A11Y-001 |

### 4.7 C-07 Toasts and banners

| Kind | When | Placement | Dismissal | Rules |
|---|---|---|---|---|
| **Toast** | A completed event whose effect is not otherwise visible on screen: an observed action in a legacy window (FR-HIST-008), an export written ("Saved results.csv — sha256 3f2a…"), a shortcut-conflict notice at start-up | Bottom-right of the document area, outside the image rectangle | Auto-dismiss after a configurable time. Every toast is also written to History or the Log, so nothing exists only in a toast. Hover or focus pauses the timer (WCAG SC 2.2.1 spirit). | Never used for errors or for anything needing action |
| **Document banner** | State of one document needing attention: external control (D12 §8), partial result (FR-HIST-006), rolled back (FR-HIST-006), input-overwrite protection, stale for longer than the operation | Top of the viewer pane, below the target strip | Persistent until the condition ends, or explicit dismissal for informational banners | Names the document and the cause. Offers the recovery action (Undo, Show Fiji window). |
| **Workspace banner** | Workspace-level attention: dialog pending (C-09), engine not ready or lost (D15 §3), version mismatch (FR-SYS-010) | Below the command bar | Persistent until resolved | At most two stacked; further ones collapse into "+N more" |

States: idle, focus (banners are in the F6 cycle), error (`state.error` banners are never auto-dismissed). Accessibility: banners use an accessible role and description that announce when they appear (`AccessibleContext` property change).

### 4.8 C-08 Confirmation sheet (UI-18): plan preview and variants

A non-modal sheet anchored under the command bar (NL/voice) or over the Inspector (form-originated). It blocks nothing else, but it is bound to a **confirmation token** that ties the plan, or the action, to the state version it was validated against (FR-NL-002).

**V1 — NL/voice plan preview (FR-NL-004)**

```
functional wireframe — not a visual design
+------------------------------------------------------------------------------+
| Instruction (from voice, editable): [ blur channel two by two microns      ] |  C-11 transcript; editing re-interprets
| Interpreted by: grammar fast path            . 0.04 s                        |  FR-NL-011 source (model id if LLM)
+------------------------------------------------------------------------------+
| Step 1 of 1  Gaussian Blur...   Process > Filters                      AC3   |
|   Target: cells.tif (id 4) . channel 2 (GFP) only, all 12 z-slices . ROI: none
|   Sigma (Radius) = 2 µm  -> sigma=2 scaled  (= 12.9 px at 0.155 µm/px)  ◆    |  FR-NL-008 conversion shown
|   Scope: channel 2, z 1-12  (keys: stack)                               ◆    |
|   run("Gaussian Blur...", "sigma=2 scaled stack");                          |  exact macro text
|   Undo: available (snapshot 18 MB of 6.0 GB budget)                          |
+------------------------------------------------------------------------------+
| ? Clarify: "Objects are dark or bright?"  ( ) dark  ( ) bright  [Fiji default: bright]   (example of A6 polarity)
+------------------------------------------------------------------------------+
| [Edit in form]                                   [Cancel (Esc)]   [Run (Enter)] |
+------------------------------------------------------------------------------+
```

| Element | Rule | Requirement |
|---|---|---|
| Transcript | Shown for voice input; editable. Editing re-runs interpretation. Speech never executes directly. | FR-SPEECH-004 |
| Interpretation source | Grammar, catalogue retrieval, or LLM with model identity and digest | FR-NL-011 |
| Per step | Exact label, menu path, target title and ID, C/Z/T scope in words and keys, ROI, every parameter with unit and conversion, consequential markers, AC class, exact macro text, undo availability | FR-NL-004, FR-NL-008, FR-HIST-005 |
| Clarifications | Inline choice lists. The classic default is labelled "Fiji default" and is never pre-selected for consequential slots. Deictic ambiguity lists candidate documents, channels or ROIs (FR-NL-005). | FR-NL-003, FR-NL-005; A17 §7–§8 |
| Refusal | "Cannot run: <reason>", plus the nearest catalogue commands as palette rows | FR-NL-001, FR-NL-007; A17 §8 |
| Confirmation | AC0/AC1 plans that are unambiguous **and not speech-originated** may auto-execute; the sheet then shows them as executed. AC2/AC3: Run is the default (Enter). AC4/AC5/AC6: Run is not the default and its text names the consequence. AC7 is never generated. | FR-NL-004, FR-NL-007; A17 §8 |
| Re-validation | If the state version changes before Run (the focused document changed, an image closed, the ROI changed), the sheet shows "State changed — plan re-checked", highlights the differences and requires a fresh confirm | FR-NL-002 |
| Execution | After Run, the sheet shows stage-based progress "Step k of n" with per-step lifecycle (D15 §4). It then collapses into History. | NFR-USE-002 |

**V2 — Irreversible action (AC4) from a direct modality:** rendered **inline in C-03** (F-12), not as a separate sheet. A19 §4 asks for an "inline warning in the dialog". FR-HIST-005.

**V3 — File safety (AC6):**

- **Overwrite input.** Overwriting a session input asks for a per-document decision, naming the path and noting that the input hash will change (FR-IMG-009).
- **Lossy save.** A lossy save lists the properties that will be lost (bit depth, calibration, overlay, composite mode, …) before writing (FR-IMG-006). The default button is *Save As TIFF instead* where applicable.

**V4 — Macro capability confirmation (AC7, FR-MACRO-002):**

- Shows the file name, size and SHA-256, and the capabilities detected with `ij.macro.Tokenizer`: file, process, network, reflection, engine access.
- The dangerous functions found (`exec`, `eval`, `call`, `runMacro`, `File.delete/rename/copy/append/saveString`, `File.openUrlAsString`) each require a **separate named confirmation checkbox** before Run is enabled.
- The confirmation is remembered per file hash only (A19 §4).
- Opening or previewing a macro never executes it (FR-MACRO-003).

**States (all variants):**

| State | Behaviour |
|---|---|
| Interpreting / checking (busy) | Indeterminate, with real elapsed time |
| Needs clarification (awaiting input) | Choice lists |
| Ready (awaiting confirmation) | Run available |
| Re-checking (busy) | — |
| Refused (error) | Reason and nearest commands |
| Executing (busy, stage-based) | Step k of n |
| Done / Failed / Partial | Per D15 |

**Keyboard:** Enter runs (K-03 rules); Esc cancels (K-02); Tab moves through steps and choices; arrows plus Space select clarification options; Alt+E opens *Edit in form*, which loads the step into C-03 with value provenance "from plan" (F-05).

### 4.9 C-09 Dialog-pending banner (FR-PROC-007)

| Aspect | Specification |
|---|---|
| Trigger | The engine reports `DIALOG_PENDING` or the lifecycle state `WAITING_FOR_DIALOG`. A modal dialog was detected during an action (P83 method: a `Window.getWindows()` / `GenericDialog.getInstance()` scan) within the detection bound defined in D04/D09. |
| Content | ⚑ "Waiting for input in a Fiji dialog: "<dialog title>" (step <n>, <document>)". Button **Bring to front** (also Enter when the banner has focus). Text: "Answer the dialog to continue, or press Cancel in it to stop." |
| Placement | Workspace banner (C-07), plus status slot 3, plus the History entry state, plus UI-16 (the modal flag) |
| Cancelling | The product does not close modal dialogs programmatically. Whether a dialog can be cancelled safely from outside is **UNKNOWN**: closing a `GenericDialog` window from another thread is unverified. The user acts in the dialog. |
| End | When the dialog closes, the banner disappears and the operation continues (RUNNING) or ends (CANCELLED if the user cancelled the dialog). The options the user entered are recorded (FR-PROC-007 acceptance: "the action is recorded"). |
| States | Attention (awaiting user); focus |
| A11Y | The banner announcement includes the dialog title |

### 4.10 C-10 Progress and activity indicator (NFR-USE-002)

| Aspect | Specification |
|---|---|
| Constraint | **Determinate** display is accepted only with a counter source: `(done, total, sourceId)`, where `sourceId` names the real counter (the chained `showProgress(i,n)` hook if validated, NFR-REL-004; or the product's own slice or file loop). Updates without a counter are rejected and the indicator stays indeterminate. This is the NFR-USE-002 acceptance test. |
| Stage-based | "Step k of n" for plans and replays, advanced only on real step completion |
| Indeterminate | A minimal, non-filling activity cue plus the text "Running" plus **real elapsed time**. No percentage and no ETA. Under the reduce-motion preference, the cue becomes static text (D11 A-07). |
| Cancel | Always beside a running indicator (FR-PROC-013). Its tooltip states the family's cancellation semantics, for example "Stops between slices; processed slices are restored from the snapshot" or "Cannot interrupt a single-plane pass; stops when it finishes" (A19 §1). |
| States | Idle (hidden), busy (determinate, stage or indeterminate), error and done hand over to C-06 and C-07 |
| A11Y | `AccessibleRole.PROGRESS_BAR` with a value only when determinate. Otherwise the accessible description is "Running, no progress measure available, <n> s". |

### 4.11 C-11 Recording indicator and voice control (FR-SPEECH-002)

| State | Shown as | Transition |
|---|---|---|
| Unavailable | Microphone glyph with a "voice unavailable" tooltip and its reason (worker missing or failed: NFR-REL-002) | — |
| Off | Microphone glyph, "off" | User action (click, Ctrl+Alt+V) → Arming |
| Arming | "Starting microphone…" (real state: capture opening, worker ready check) | → Recording, or Unavailable |
| **Recording** | `state.recording`: filled dot plus "Recording" plus an elapsed counter "12 s / 30 s" (a real timer), in the command bar **and** status slot 2 so it stays visible when the bar is collapsed | Second user action or 30 s auto-stop → Transcribing |
| Transcribing | Indeterminate plus elapsed time | → Transcript ready, or "No speech detected" (FR-SPEECH-003 empty result: shown as text, no plan) |
| Transcript ready | Editable transcript in C-08 V1 | — |

- The indicator is visible for the **whole** capture (FR-SPEECH-002 acceptance).
- No audio level meter is designed, because no requirement asks for one.
- Audio and transcript persistence follow the opt-in (FR-SPEECH-002). The indicator tooltip states the current retention setting.

### 4.12 C-12 External-window badge (FR-PLUG-001)

| Aspect | Specification |
|---|---|
| Glyph | `state.external`: a window glyph plus the text "Fiji window" (tracker, tabs) or "Observed" (History). Never colour alone. |
| Where | UI-16 rows; document tabs under external control (D12 §8); History steps that opened, or were observed in, a legacy window; status slot 3 count |
| Action | Click or Enter: bring the window to the front (CMP-11). On a History step: show the window, or "closed" if it no longer exists. |
| A11Y | Accessible name "Fiji window: <title>" |

### 4.13 C-13 Target strip

- **Content.** `<title> (id <n>)`, then the subtitle (D13 §5), then markers, then zoom.
- **In C-03 and C-08,** it also shows the scope and ROI.
- **Selection.** It is selectable text.
- **Changes.** When the target changes while a form or sheet is open, the strip changes visibly and the form re-validates (FR-NL-002 analogue for forms).

Requirements: FR-IMG-002, FR-VIEW-002.

### 4.14 C-14 Tool palette

| Aspect | Specification |
|---|---|
| Construction | A toggle-button group, one button per tool. A split affordance (small corner arrow) opens the variant menu, in addition to IJ1's right-click, long-press and Alt-click (A04 §5.3), so variants are discoverable and keyboard-reachable (K-07). |
| Labels | Hover text is IJ1's `Toolbar.showMessage` text. The Guided preset shows text labels. |
| Double-click | Opens options (D13 §10.1) |
| States | Idle, hover, focus, selected (`CHECKED`), disabled for a document under external control (with its reason) |
| A11Y | Name "Rectangle tool, variant Rounded Rectangle"; the variant menu is a `JPopupMenu` |

### 4.15 C-15 Dimension slider

- **Contents.** A labelled `JSlider` per dimension: letter, `n/N` (1-based), name.
- **Keys.** Arrow keys on a focused slider move that dimension only. This is product behaviour inside the slider. Document-focus keys follow D13 §6.
- **Commit.** At release (D13 §6.4).
- **States.** Idle, focus, busy (commit pending), stale.
- **Requirements.** FR-VIEW-004, NFR-A11Y-001.

### 4.16 C-16 Globals table (UI-17 and the C-03 section)

| Column | Content |
|---|---|
| Setting | Fiji name, for example `Black background`, `Scale when converting`, measurement set, `Limit to threshold`, `Redirect to`, `Decimal places`, `Pad edges when eroding`, binary iterations and count, `Use inverting LUT`, RGB weights, global calibration (FR-MEAS-001 list) |
| Current value | From the latest digest |
| Fiji default | Catalogue or upstream default |
| Status | "differs from Fiji default" (`state.warning` glyph plus text) |
| Last changed | History step, or "before session" |
| Action | "Change…" runs the exact Fiji command (AC5) |

Changes from any origin (legacy dialog, macro, plugin) appear via the globals delta (FR-HIST-007).

## 5. Swing implementation constraints (no code)

| ID | Constraint | Reason / evidence |
|---|---|---|
| SW-01 | Every product component must be a **lightweight Swing component** (`JComponent` subclass) hosted in the shell `JFrame` (CMP-01), so it embeds, layers (sheets, popups, banners) and themes consistently. No AWT heavyweight components (`Canvas`, `Panel`, `ScrollPane`, `List`) inside the shell. | Heavyweight/lightweight mixing breaks z-order for sheets and popups (**INFERRED**, AWT mixing rules). The IJ1 `TextPanel` and `Toolbar` are heavyweight canvases (A04 §5.3, §5.9) and are not reused. |
| SW-02 | The viewer canvas must be a lightweight component painting cached `BufferedImage`s plus vectors. | P87 1c: a lightweight `JPanel` painting `imp.getBufferedImage()` in a FlatLaf `JFrame` works (**VERIFIED**) |
| SW-03 | All component state changes happen on the EDT. No engine call may block the EDT. IF-02 events are marshalled to the EDT and handled within the listener budget. | NFR-PERF-005 (≤ 5 ms per event, PROPOSED in A27), NFR-PERF-006 |
| SW-04 | Use standard Swing components (`JTable`, `JList`, `JTree`, `JSlider`, `JToggleButton`, `JTextField`, `JPopupMenu`) wherever possible, because they carry `AccessibleContext`. Any custom-painted component must implement `getAccessibleContext` with name, role, value and description. | NFR-A11Y-001 |
| SW-05 | Layout must use layout managers: MigLayout (`miglayout-swing-5.3`, bundled, **VERIFIED**) or standard ones. No absolute pixel positioning. Sizes come from D11 tokens in logical pixels. | NFR-PLAT-002 |
| SW-06 | The macro view may use RSyntaxTextArea (`rsyntaxtextarea-3.6.0`, bundled, **VERIFIED**). Its licence must be checked in the P70 inventory before the product imports it (NFR-PKG-001/003). | NFR-PKG-003 |
| SW-07 | Legacy AWT dialogs and windows are never re-parented, embedded or restyled by the shell. They are tracked only. | FR-PLUG-001/002; A04 §5.15 |
| SW-08 | Theming only through FlatLaf UI defaults and client properties (D11 §7.1). No custom look-and-feel delegates unless a token cannot be expressed otherwise. | NFR-MAINT-001 spirit |
| SW-09 | Table and list models must be immutable snapshots from IF-03. They must never wrap live `ResultsTable`, `RoiManager` or `ImagePlus` objects. | NFR-PERF-004, FR-IMG-001 |
| SW-10 | Components must not hold the macro generator or validator logic. They query CMP-27 and CMP-22 or CMP-41 through IF-03, IF-04 and IF-05. | Single source of truth (FR-HIST-002, FR-NL-006) |

## Constraints and Limitations

- **C-02 search reuse.** Whether `scijava-search` can return catalogue rows with menu path and shortcut through its public API is **UNKNOWN** (FR-CMD-006 allows recording why not). Verify in D04 with a GUI-JVM probe that queries `SearchService`.
- **C-09 dialog cancellation.** Programmatic cancellation of a legacy modal dialog is **UNKNOWN**, so the design relies on the user acting in the dialog.
- **C-04 copy behaviour.** Full-precision copy deviates from Fiji's copy-as-displayed. The deviation is deliberate (FR-MEAS-003) and labelled.

## Requirement gaps

| ID | Gap | Candidate requirement |
|---|---|---|
| GAP-D14-01 | No requirement defines how pre-filled form values are sourced for direct use (Fiji remembered statics versus the product's history). FR-PROC-003 forbids relying on statics at execution, but is silent on display. | "Parameter forms shall pre-fill from the catalogue default or the session's last-used value and label the source." |
| GAP-D14-02 | No requirement covers copying Results to the clipboard at full precision. FR-MEAS-003 covers holding and exporting only. | Extend FR-MEAS-003 acceptance to clipboard copy |
| GAP-D14-03 | No requirement covers toasts or notification history. The design keeps every toast also in History or the Log. | "Every user notification shall also be retrievable from History or the Log." |
| GAP-D14-04 | No requirement defines how the AC4 confirmation is presented for direct modalities beyond A19 §4 | Covered by FR-HIST-005; presentation is a design choice (F-12) |

## Decisions proposed

| ID | Decision | One-line rationale |
|---|---|---|
| DP-D14-01 | One command bar with an explicit "Interpret as instruction" row, rather than separate search and NL boxes | Keeps NL auxiliary (D11 UXP-16). The user always chooses; NL always previews (FR-NL-004). |
| DP-D14-02 | Parameter forms are non-modal, show keys, units, globals and the live macro line, and take the macro text only from the History generator | FR-PROC-003, FR-MEAS-002, FR-HIST-002. Avoids two macro generators. |
| DP-D14-03 | A single confirmation-sheet component with variants V1/V3/V4; AC4 for direct modalities stays inline in the form | One pattern across modalities (A19 §4) |
| DP-D14-04 | Results Ctrl+C copies at full precision; "Copy as displayed" is secondary | FR-MEAS-003; P37 rounding evidence |
| DP-D14-05 | Header sorting in tables is view-only and labelled | Engine table order is macro-visible (FR-MEAS-005) |
| DP-D14-06 | The progress component rejects determinate updates without a named counter source | Enforces NFR-USE-002 by construction |
| DP-D14-07 | The product never closes legacy modal dialogs programmatically | Unverified safety; the user stays in control (FR-PROC-007) |

## References

- A04 §5.3, §5.8, §5.9, §5.16, §5.19 and screenshots `a04-05`, `a04-06`, `a04-13`, `a04-16`, `a04-17`; A05 (UP-04…UP-10, UP-16, UP-18); A10 §3; A11; A12; A17 §3–§8; A18 §1, §5; A19 §1, §3–§4; A20 §1; A25; A26; A27; P37; P81; P83; P87.
- `docs/data/commands-core-parameters.csv` (column header, 213 rows); `docs/data/commands.csv`.
- D01 (CMP-04…CMP-12, CMP-22, CMP-27, CMP-30, CMP-41; IF-02…IF-05); D11; D12; D13; D15.
- `imagej/ImageJ@v1.54p` (03382be1): `ij/gui/GenericDialog.java#L120-L121, L568, L590-L606, L1195-L1265, L1912`; `ij/plugin/frame/RoiManager.java#L136-L200`; `ij/text/TextWindow.java#L160-L202`.
- Installed jars (`Fiji/jars/`, listed 2026-10-17): `miglayout-swing-5.3.jar`, `rsyntaxtextarea-3.6.0.jar`, `swingx-1.6.1.jar`, `flatlaf-3.6.1.jar`, `scijava-search-3.0.2.jar` (A02/A05).
- W3C, *WCAG 2.1*, https://www.w3.org/TR/WCAG21/ (accessed 2026-10-17): SC 2.2.1, 2.4.7.
