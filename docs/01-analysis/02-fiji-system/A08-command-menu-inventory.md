---
id: A08
title: Full Command and Menu Inventory
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p, imagej/imagej-legacy@imagej-legacy-3.0.0, imagej/ij1-patcher@ij1-patcher-2.0.0, scijava/scijava-common@scijava-common-2.101.0, "Fiji install (pin f6695397…bafa)"]
related_requirements: [FR-CMD-001, FR-MACRO-001, FR-PLUG-001, FR-NL-001]
related_decisions: [DEC-001]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A08 — Full Command and Menu Inventory

## Executive Summary

The pinned Fiji exposes its operations through three registries that merge at start-up: the ImageJ 1.x command table (`ij.Menus`, label → `class("arg")`), the SciJava plugin index (ImageJ2 commands, tools, ops), and SciJava script discovery. [`docs/data/commands.csv`](../../data/commands.csv) is the authoritative catalogue: **1390 rows** from a static parse of every jar and file in the install, reconciled against a headless runtime dump. [`docs/data/commands-core-parameters.csv`](../../data/commands-core-parameters.csv) gives the macro parameter keys, defaults, units and hidden global dependencies of a curated MVP core set of 54 commands (212 rows), each derived from `GenericDialog` code at ImageJ v1.54p with line citations.

Main findings (all **VERIFIED** for this runtime):

- At runtime the IJ1 command table holds **944 labels**: 829 IJ1 classes plus 115 ImageJ2 commands and scripts that imagej-legacy inserts as `command:`/`script:` identifiers. The table is populated headless, but the AWT menu bar is `null` and the keyboard-shortcut table is empty headless (probe P11).
- Every operation is addressed by its **exact menu label**, and the label is the API. Labels carry significant trailing spaces that tell apart same-named items (for example `"Rotate... "` in Image>Transform versus `"Rotate..."` in Edit>Selection). Duplicate labels silently overwrite each other: 10 Multiview-Reconstruction labels are bound to the `SPIM_Registration` classes.
- `IJ.run` does **not** propagate most plugin exceptions. `Executer.run` catches `Throwable` and logs it (`Executer.java#L74-L117`), so a headless `HeadlessException` (Histogram, Brightness/Contrast, Threshold, ROI Manager) leaves the caller with no error signal (P13).
- Across 39 probe cases, the core processing, measurement, stack and conversion commands run headless. GUI-window commands fail: Histogram, the Brightness/Contrast and Threshold frames, and the ROI Manager. `run("Open...")` and Find Maxima `output=Count` produce no usable result headless. Merge Channels and Image Calculator work only from the macro interpreter (§7).
- The macro Recorder cannot run headless (`IJ.recording()` is false when headless, `IJ.java#L2578-L2580`). Recordability is a GUI-JVM capability.

## Purpose and Scope

This document gives designers and later implementers one authoritative list of what the installed Fiji can do, how each item is named and invoked, what it records as macro text, and whether it runs without a display. It covers only the installed pinned distribution. Update sites, plugins that are not installed, and the behaviour of third-party plugins beyond their registration are excluded (see Constraints and Limitations).

## Sources and Evidence

### Methodology and reproducibility

| Step | Method | Evidence |
|---|---|---|
| Static parse | Python (`zipfile`, read-only, no extraction) re-implements the parsing rules of `ij/Menus.java` (`addMenuBar`, `addSubMenu`, `addPluginItem`, `installJarPlugin`, `getPlugins`, `autoGenerateConfigFile`, `convertShortcutToCode`), scijava-common `PluginInfo`/`ScriptFinder` and imagej-legacy script directories. It covers 662 jars (556 in `jars/`, 13 in `jars/win64/` and 93 under `plugins/`; the brief's "557 entries" in `jars/` counts the `win64` folder), `luts/`, `scripts/`, `plugins/` and `macros/`. | `probes/P10-static-inventory.py`, `probes/P10-static-summary.txt` |
| Runtime dump | Headless Groovy run via `fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless --run <script> "k='v'"`. The launcher's stdout cannot be captured, so results are written to files (P16). | `probes/P11-*`, `probes/P16-launcher-notes.txt` |
| Merge/diff | Static rows are joined to runtime command-table labels, the ij1-patcher menu structure and `ModuleService` infos. | `probes/P12-build-and-diff.py`, `probes/P12-diff-report.txt` |
| Headless probe | 39 probe cases (38 via Groovy, 39 via macro) on synthetic images (`IJ.createImage`/`newImage`, no network), run through four paths: `IJ.run(imp, cmd, opts)` in Groovy (with and without `Interpreter.batchMode`), `-macro` CLI with and without `setBatchMode(true)` in one process per operation, and in-process `IJ.runMacroFile` to capture error text. | `probes/P13-*`, `probes/P14-*`, `probes/P15-*` |

Re-running: [README-G1](../references/probes/README-G1.md) lists the exact commands. The pin hash `f66953972bb5ed85b8ab0273e4294af5b03136c28c9ef617e24556bca523bafa` was re-verified after the runs.

## Verified Findings

### 1. How the IJ1 menu is built (`ij/Menus.java` at v1.54p)

| Source | Rule (VERIFIED from source) | Rows |
|---|---|---|
| Hard-coded items | `addMenuBar()` adds top-level items with `addPlugInItem(menu, label, "class(\"arg\")", KeyEvent.VK_x, shift)` and the Image>Type checkbox items (`Menus.java#L109-L265`). File>Open..., Edit>Undo, Image>Crop, Process>Subtract Background..., Analyze>Measure and similar items are defined here and **not** in IJ_Props.txt. | 163 (incl. 85 Help>Examples items) |
| `IJ_Props.txt` | `getMenu(path, true)` → `addSubMenu` reads keys `<first word of submenu name, lower-case>NN` with NN = 01–99 (`Menus.java#L467-L489`). Examples: `Image>Lookup Tables` → `lookup01…`, `Image>Stacks>Tools_` → `tools_01…`, `Analyze>Tools` → `tools01…`, `File>Open Samples` → `open01…`. The Plugins menu uses `plug-inNN`, where `>"Name"` marks a submenu (`Menus.java#L563-L590`). `popupNN` fills the image right-click menu. Note that the comment above `batch01` in IJ_Props.txt says "File/Batch", but the menu is created as `Process>Batch` (`Menus.java#L204`). | 349 (incl. 10 popup references) |
| Item syntax | `"Label[sc]",class("arg")`. `addPluginItem` (`Menus.java#L516-L556`) splits at the **last comma**. A trailing `[x]` is a shortcut only if `convertShortcutToCode` (`#L1553-L1593`) maps it: a–z gives Ctrl+key, A–Z gives Ctrl+Shift+key, digits, `F1`–`F12` (no modifier) and `N0`–`N9`/`N/ N* N- N+ N.` (keypad). Only then is the bracket stripped from the label. `[>]`, `[<]`, `[+]`, `[-]` and `[\]` stay **in the label** ("Next Slice [>]"). | 34 IJ_Props + 25 Menus.java shortcuts |
| LUT files | `addLuts` appends one item per `luts/*.lut`. These are not in the command table and are resolved by `Executer.loadLut` (`Executer.java#L158`). | 36 |
| `plugins/` scan | `getPlugins()` (`Menus.java#L1063-L1135`): underscore classes and underscore `.jar`/`.zip` files at top level or one folder deep. Jar menu lines come from the first entry ending in `plugins.config` (`#L932-L947`), or are auto-generated from underscore classes (`#L950-L985`; none needed in this install). `installJarPlugin` (`#L764-L818`) maps `Plugins>X`, `"…"` lines (Plugins or the jar's folder submenu) and explicit menus (`File>Import`, …; `Help>About*` → `Help>About Plugins`). | 424 lines from 96 jars |
| Macro/script files in `plugins/` | `installMacro` (`#L668-L699`) would add `.ijm/.js/.bsh/.py` (and `_*.txt`) files. **Fiji disables this**: imagej-legacy calls `suppressIJ1ScriptDiscovery` (`LegacyService.java#L776`), which patches `validMacroName` to return false (`ij1-patcher LegacyExtensions.java#L933-L937`). | 29 (status `suppressed`) |

### 2. Fiji/legacy additions (ij1-patcher, imagej-legacy)

- **Jars outside `plugins/`.** ij1-patcher appends `LegacyHooks.handleExtraPluginJars()` to `jarFiles` (`LegacyExtensions.java#L501-L507`; `LegacyHooks.java#L265-L289`), and skips `-sources.jar` files and duplicate jars (`LegacyInjector.java#L280-L291`). In practice, 32 of the 43 `plugins.config` lines in jars under `jars/` are live at runtime. The remaining 11 are the 10 duplicates below plus BigStitcher. *How* `jars/` reaches that list (class-path donation via `LegacyEnvironment.addPluginClasspath`) is **INFERRED**.
- **Menu-structure capture.** ij1-patcher hooks every `addPlugInItem`/`addPluginItem`/`addCheckboxItem` call to record `menuPath → command` even headless (`LegacyExtensions.java#L568-L700`, `LegacyHooks.getMenuStructure()` `#L477-L515`). It deliberately skips `Help>Examples` (`#L492-L495`). This structure has 851 entries including separators (P11). Headless, however, it records **every `plugins.config` item as `Plugins>label`** (398 flat entries, including `File>Import` and `Help>About Plugins` items), so it is used only for IJ_Props and `Menus.java` items. The cause, the `installJarPlugin` hooks that reset `_currentMenuPath` to `"Plugins"` (`LegacyExtensions.java#L623-L633`), is **INFERRED**.
- **IJ1 commands wrapped as SciJava modules.** `LegacyCommandFinder` creates one `LegacyCommandInfo` per command-table entry (`LegacyCommandFinder.java#L71-L106`) with id `legacy:<class>[("arg")]` (`LegacyCommandInfo.java#L83`). Its menu paths come from the AWT `MenuBar`, which is `null` headless, so headless legacy modules have **empty menu paths** (P11: 830 legacy infos, all without a path).
- **ImageJ2 commands and scripts inserted into IJ1 menus.** `getScriptsAndNonLegacyCommands()` keeps commands with menu root `app`, a non-empty menu path, not `no-legacy`, and `visible` (`LegacyService.java#L657-L669`). It keeps scripts with a menu path **and an underscore in the file name** (`#L671-L680`). `IJ1Helper.addMenuItems()` writes `leaf label → identifier` into the IJ1 table and overrides a same-named IJ1 label (it logs "Overriding …"; `IJ1Helper.java#L874-L954`). At runtime this adds 62 commands and 53 scripts. `BigStitcher` is overridden this way.
- **`no-legacy` removal.** All modules carrying the `no-legacy` attribute are removed from the `ModuleService` (`LegacyService.java#L494-L500`). This removes 157 of the 225 indexed `Command` plugins: the ImageJ2 re-implementations of IJ1 menu items, such as `net.imagej.plugins.commands.binary.MakeBinary`.

### 3. SciJava plugin index (scijava-common 2.101.0)

`META-INF/json/org.scijava.plugin.Plugin` is a concatenation of JSON objects `{"class": <FQCN>, "values": {<@Plugin attributes>}}`. It is loaded by `Index.load(Plugin.class, …)` in `DefaultPluginFinder.findPlugins` (`DefaultPluginFinder.java#L78-L92`; the `scijava.plugin.blocklist` system property filters it). `PluginInfo.populateValues` (`PluginInfo.java#L488-L531`) takes `menu` (array of `@Menu`, untrimmed labels with weight, mnemonic and accelerator) in preference to `menuPath` (a string split on `>` and trimmed). It also reads `priority`, `visible`, `selectable` and `attrs`. `headless` (`Plugin.java#L168`, default false) is exposed by `CommandInfo.canRunHeadless()` (`CommandInfo.java#L351-L352`). No code in the cached repositories *enforces* it. The only caller found is a tutorial (grep over all caches; **INFERRED** not enforced).

| Index content (66 jars, 1950 entries) | Count |
|---|---|
| `org.scijava.command.Command` | 225 |
| `org.scijava.tool.Tool` | 26 |
| `org.scijava.options.OptionsPlugin` | 25 |
| ImageJ Ops (`net.imagej.ops.*` op types) | ≈900 (full list in `P10-scijava-plugin-index.csv`) |
| `Converter` / `Service` / other | 201 / 115 / remainder |
| Entries with a menu path | 230 |

Only Command, Tool and menu-bearing entries are rows in `commands.csv` (277). All 1950 entries are in `probes/P10-scijava-plugin-index.csv`. At runtime, `ModuleService` holds 1872 modules: 988 `CommandInfo` (most of them Ops exposed as commands), 830 `LegacyCommandInfo` and 54 `ScriptInfo`. **Only 4 report `canRunHeadless()==true`** (P11).

### 4. Scripts and macro tools

- **Script directories.** `<Fiji>/scripts` (menu = folder path) and `<Fiji>/plugins` with the prefix `Plugins` (`LegacyService.java#L483-L489`), plus classpath resources under `scripts/` (`ScriptFinder.java#L120-L131`). A top-level script goes to `Plugins>Scripts` and `_` becomes a space (`ScriptFinder.java#L159-L205`). There are 12 languages at runtime, including `ijm`, `groovy`, `py` and `js`. `.m` (MATLAB, 19 files) and `.txt` have none, so 28 files are not registered.
- **StartupMacros.** ij1-patcher makes `MacroInstaller` load `macros/StartupMacros.fiji.ijm` (`LegacyExtensions.java#L537-L558`). A macro whose name contains `-` and `Tool` is a toolbar tool (`MacroInstaller.java#L102`). `AutoRun` runs at start-up and `Popup Menu` replaces the popup. The rest go to Plugins>Macros. Rows: 13 macros/tools plus 3 toolsets in `macros/toolsets/`. The 14 built-in tools come from `ij/gui/Toolbar.java#L24-L38`.

### 5. Invocation paths

```mermaid
sequenceDiagram
    participant C as Caller (macro run() / IJ.run / menu click)
    participant IJ as ij.IJ.run(imp,cmd,opts)
    participant E as ij.Executer
    participant M as Menus.getCommands()
    participant H as DefaultLegacyHooks
    participant P as IJ1 plugin (PlugIn / PlugInFilter)
    participant S as SciJava CommandService/ModuleService
    C->>IJ: label + "key=value" options
    IJ->>IJ: Macro.setOptions(options); setTempCurrentImage(imp)
    IJ->>E: new Executer(cmd).run() (same thread)
    E->>M: table.get(label)
    alt value is class("arg")
        E->>IJ: IJ.runPlugIn(label, class, arg)
        IJ->>H: _hooks.interceptRunPlugIn(class,arg)
        H-->>IJ: null (not intercepted) / File>Open... handled
        IJ->>P: newInstance; PlugIn.run(arg) or PlugInFilterRunner
        P->>P: GenericDialog reads Macro.getOptions()
    else value is command:<id> or script:<id>
        E->>IJ: IJ.runPlugIn(label, "command:...", "")
        IJ->>H: interceptRunPlugIn
        H->>S: legacyService.runLegacyCompatibleCommand(id)
    else not found
        E->>E: Macros menu / LUT / Open Recent / aliases / "Unrecognized command"
    end
    E-->>C: returns normally; Throwables caught and logged
```

| Step | Source (VERIFIED) |
|---|---|
| `IJ.run(imp, cmd, opts)` sets the thread-local temp image and calls `run(cmd, opts)` → `Macro.setOptions`, `convert()` (renamed-command aliases), `new Executer(command).run()` | `ij/IJ.java#L310-L330`, `#L343-L405`, `#L410-L420` |
| `Executer.run`: `CommandListener`s may rewrite or veto; Recorder hooks only when `IJ.recording()`; **catches `Throwable` and only logs it** | `ij/Executer.java#L54-L122` |
| `Executer.runCommand`: table lookup, `("arg")` split, `IJ.runPlugIn`; fallbacks to Plugins>Macros, LUT, Open Recent, Help>Examples (GUI only), `Auto Threshold`→`Auto Threshold...` and `Enhance Local Contrast (CLAHE)`→`CLAHE ` aliases, then `IJ.error("Unrecognized command")` | `ij/Executer.java#L125-L186` |
| `IJ.runPlugIn`: patched to call `_hooks.interceptRunPlugIn` first; `ij.*` classes via `Class.forName`, others via the plugin class loader (`runUserPlugIn`) | `ij/IJ.java#L203-L272`; `ij1-patcher LegacyInjector.java#L186-L193` |
| Legacy intercept: `ij.plugin.Commands("open")` → `interceptFileOpen`; any identifier in `legacyCompatible` → `runLegacyCompatibleCommand` → `CommandService`/`ModuleService.run` | `imagej-legacy DefaultLegacyHooks.java#L136-L163`; `LegacyService.java#L292-L306` |
| Programmatic IJ1 class execution through SciJava: `LegacyService.runLegacyCommand(className, arg)` → `commandService.run(LegacyCommand.class, …)` | `LegacyService.java#L271-L280` |

### 6. Static versus runtime differences

| Category | Static | Runtime | Explanation |
|---|---|---|---|
| IJ1 rows from IJ_Props / Menus.java / plugins.config | 840 | 829 present, 10 shadowed, 1 overridden | Shadowed: the same label is defined in `jars/multiview-reconstruction-9.0.11.jar` and `plugins/SPIM_Registration-5.0.26.jar`, and the `pluginsTable.put` executed last wins (`Menus.java#L553`). Overridden: `BigStitcher` (§2). |
| IJ1 macro/script files in `plugins/` | 29 | 0 via IJ1 | `suppressIJ1ScriptDiscovery`. The `.ijm/.py/.js/.bsh` files reappear through SciJava (below). `plugins/**/_*.txt` macros (8 files, e.g. `Macros/Bulls_Eye.txt`) are **not reachable at all** because no ScriptLanguage handles `.txt`. |
| Scripts (scripts/, plugins/, jar `scripts/`) | 111 files | 54 `ScriptInfo`; 53 in IJ1 table | Not registered: `.m`/`.txt` with no language. One module (no `_` in the file name) is absent from the IJ1 table (`LegacyService.java#L678`). |
| SciJava Command/Tool/menu rows | 277 | 76 modules (62 also in IJ1 table) | 157 Commands removed as `no-legacy`. Tools and OptionsPlugins are not modules. |
| Help>Examples, LUT items, popup | 85 + 36 + 10 | not in command table | Resolved by Executer fallbacks. Examples are skipped by the menu hook headless. |
| Shortcuts | 96 static | 0 (`Menus.getShortcuts()` is empty headless) | `addItem` returns early when the AWT menu is null (`Menus.java#L425-L427`). Shortcuts exist only in the GUI JVM (**INFERRED** for the GUI JVM; not probed). |
| Runtime-only rows | — | 0 | Every runtime label was matched by the static parse. |

## Diagrams and Inventories

### Counts

| Framework | Rows | Source kinds |
|---|---|---|
| IJ1 | 972 | IJ_Props 349, Menus.java 163, plugins.config 424, luts-dir 36 |
| IJ2 | 277 | scijava-index 277 |
| script | 111 | scripts-dir 34, plugins-dir-script 74 (45 SciJava + 29 suppressed IJ1), jar-scripts-resource 3 |
| macro-tool | 16 | StartupMacros.fiji.ijm 13, toolsets 3 |
| IJ1-tool | 14 | Toolbar.java built-in tools |

### Effective runtime menu tree (two levels)

These are the declared menu paths of all IJ1 rows that are live in the runtime command table, plus the runtime menu paths of the ImageJ2/script modules inserted into it (P12, 954 leaves). Separators, Help>Examples sub-items, LUT files and the popup are excluded. Path elements are trimmed here. At runtime, `Image>Stacks>Animation_`, `Tools_` and `Plugins>New_` become menus whose names end in a space (`Menus.java#L471`, `_` becomes a space).

| Top-level menu | Leaves | Direct items | Submenus (leaf count) |
|---|---|---|---|
| File | 163 | 11 | Export (3); Import (52); New (8); Open Samples (42); Save As (40); Show Folder (7) |
| Edit | 70 | 11 | Options (28); Selection (31) |
| Image | 161 | 6 | Adjust (16); Annotate (1); Axes (1); Color (21); Drawing (2); Hyperstacks (7); Lookup Tables (14); Overlay (13); Stacks (44); Transform (10); Type (9); Video Editing (9); Zoom (8) |
| Process | 97 | 12 | Batch (6); Binary (15); FFT (9); Filters (19); Math (19); Morphology (1); Noise (7); Shadows (9) |
| Analyze | 67 | 19 | Classification (1); Colocalization (4); Gels (7); Local Thickness (6); Multi Kymograph (4); Optic Flow (1); QuickPALM (11); Skeleton (1); Tools (11); TopoJ (2) |
| Plugins | 364 | 6 | Analyze (13); AutoRun (1); BIJ (4); BigDataViewer (16); BigStitcher (40); Bio-Formats (10); Cluster (5); Debug (4); Examples (36); Feature Extraction (3); HDF5 (8); Image5D (18); Integral Image Filters (5); Kymograph (1); LSM Toolbox (2); Labkit (8); Landmarks (1); Macros (6); Multiview Reconstruction (17); New (8); Optic Flow (3); Process (33); Registration (15); SPIM Registration (4); Sandbox (2); Scripting (1); Segmentation (16); Shortcuts (5); Skeleton (1); Stacks (2); Stitching (9); Time Lapse (6); Tracking (7); Transform (15); Utilities (33) |
| Window | 6 | 6 | — |
| Help | 24 | 11 | About Plugins (12); Examples (1) |

### Duplicate labels and conflicts

- **Same label, different class (runtime winner shown in `P12-diff-report.txt`).** There are 10 Multiview-Reconstruction labels, such as "Define Multi-View Dataset", "Apply Transformations" and "Display View". These are defined both in `jars/multiview-reconstruction-9.0.11.jar` (`net.preibisch.mvrecon.*`) and in `plugins/SPIM_Registration-5.0.26.jar` (`spim.fiji.plugin.*`). The command table binds them to `spim.fiji.plugin.*`. `IJ.run("Display View")` therefore runs a different class from the one the multiview menu entry was written for. Both menu items exist in the GUI.
- **Trailing-space disambiguation (by design).** 39 labels carry leading or trailing spaces, and 26 of them collide with another label once trimmed, for example `Scale... ` (Edit>Selection) versus `Scale...` (Image), `Rotate... ` (Image>Transform) versus `Rotate...` (Edit>Selection), and `Image... `/`Image...  ` in Fiji import plugins. The macro text must reproduce the spaces exactly. Probe op 16b shows that `run("Rotate...")` without a selection reaches Edit>Selection>Rotate and fails with "This command requires a selection".
- **Overrides.** ImageJ2 command leaf labels replace an IJ1 label of the same name (`IJ1Helper.java#L929-L952`). Only `BigStitcher` is affected in this install.
- **Aliases.** `IJ.convert` maps old names, such as sample names with sizes (`IJ.java#L343-L405`). `Executer` maps `Auto Threshold`→`Auto Threshold...` and the CLAHE label (`Executer.java#L168-L171`). Edit>Selection>Add to Manager is `"Add to Manager"` after `[t]` is stripped.

### Macro recordability by family

| Family | Recorded as | Evidence |
|---|---|---|
| IJ1 command with `GenericDialog` | `run("Label", "key=value …")`; keys = `Macro.trimKey(label)`, a checkbox records its key only when true | `Executer.java#L65-L68`; `GenericDialog.java#L1258-L1267`; `Macro.java#L159-L168` (VERIFIED as source) |
| IJ1 command without a dialog | `run("Label")` | same |
| Interactive frames (Brightness/Contrast, Threshold, ROI Manager, Channels Tool) | No `run()` options. The frames record dedicated macro functions themselves (e.g. `setMinAndMax`, `setThreshold`, `roiManager(...)`) | **INFERRED** from frame classes; recorder lines not cited |
| ImageJ2 commands and scripts inserted in IJ1 menus | `run("Label", "name=value …")` from module inputs | `MacroRecorderPreprocessor.java#L75-L82`, `MacroRecorderPostprocessor.java#L62-L80` |
| Macro tools, toolbar tools | Not recorded as commands (tool use records selections etc.) | **INFERRED** |
| Any family, headless | **Not recordable.** `IJ.recording()` is `!isHeadless() && Recorder.record` | `IJ.java#L2578-L2580`; corroborated by `probes/P30-recorder-headless.out.txt` |

## Technical Details

### 7. Headless results matrix (VERIFIED for this runtime only)

Columns: **G** = Groovy `IJ.run(imp, …)`, without / with `Interpreter.batchMode` (P13). **M** = `-macro` CLI, one process per operation, with / without `setBatchMode(true)` (P14). In-process macro error text comes from P15. "exit-hang" means the macro's result was written but the Fiji process did not exit within 120 s.

| Op | Command (options) | G nobatch | G batch | M batch | M nobatch | Notes |
|---|---|---|---|---|---|---|
| 01 | `Invert` | pass | pass | pass | pass |  |
| 02 | `Gaussian Blur...` `sigma=2` | pass | pass | pass, exit-hang | pass, exit-hang |  |
| 03 | `Subtract Background...` `rolling=50` | pass | pass | pass | pass |  |
| 04 | `Median...` `radius=2` | pass | pass | pass, exit-hang | pass, exit-hang | Fixture with three salt pixels. |
| 05 | `Convert to Mask` | pass | pass | pass | pass | IJ.setAutoThreshold("Default dark") first. |
| 06 | `Auto Threshold` `method=Otsu white` | pass | pass | pass | pass | Fiji Auto_Threshold plugin. |
| 07 | `(API) IJ.setAutoThreshold` `Otsu dark` | pass | pass | pass | pass | API/macro function, not a menu command. |
| 08 | `Set Measurements...` `area mean min centroid redirect=None decimal=3` | pass | pass | pass | pass |  |
| 09 | `Measure` | pass | pass | pass | pass |  |
| 10 | `Analyze Particles...` `size=0-Infinity display clear` | pass | pass | pass | pass | setThreshold(128,255) first; 4 particles found. |
| 11 | `Analyze Particles...` `size=0-Infinity clear add` | fail | fail | aborted | aborted | `add` constructs RoiManager → HeadlessException. In-process: HeadlessException logged, macro aborted. |
| 12 | `Histogram` | fail | fail | fail | fail | HistogramWindow → HeadlessException (Histogram.java#L80); swallowed. |
| 35 | `Histogram` `bins=256 use x_min=0 x_max=256 y_max=Auto stack` | fail | fail | fail | fail | NPE: HeadlessGenericDialog.getCheckboxes()==null (Histogram.java#L131). |
| 13 | `Duplicate...` `title=dup` | pass | pass | pass | pass |  |
| 14 | `Crop` | pass | pass | pass | pass | Crop modifies the image in place. |
| 15 | `Scale...` `x=0.5 y=0.5 interpolation=Bilinear average create title=scaled` | pass | pass | pass | pass |  |
| 16 | `"Rotate... "` `angle=15 grid=1 interpolation=Bilinear` | pass | pass | pass | pass | Label is "Rotate... " (trailing space). |
| 16b | `Rotate...` `angle=15` | fail (exc) | fail (exc) | aborted | aborted | Edit>Selection>Rotate... without ROI: error raised as designed (precondition). Macro error: "This command requires a selection". |
| 17 | `Z Project...` `projection=[Max Intensity]` | pass | pass | pass | pass |  |
| 18 | `Make Substack...` `slices=1-2` | pass | pass | pass | pass | slices=1-2. |
| 19 | `Split Channels` | pass | pass | pass | pass |  |
| 20 | `Merge Channels...` `c1=r c2=g create` | fail | fail | pass | pass | Needs WindowManager-visible inputs; works in macro interpreter only. |
| 21 | `Enhance Contrast...` `saturated=0.35 normalize` | pass | pass | pass | pass |  |
| 22 | `Apply LUT` | pass | pass | pass | pass |  |
| 23 | `Fill` `slice` | pass | pass | fail | fail | Macro: prior setColor(220) overrides foreground (Filler.java#L98-L99). |
| 23b | `Fill` `slice` | pass | pass | pass | pass | Same without prior setColor. |
| 24 | `Clear` `slice` | pass | pass | pass | pass |  |
| 25 | `Add to Manager` | fail (exc) | fail (exc) | aborted | aborted | RoiManager.<init> → HeadlessException. In-process: HeadlessException logged, macro aborted. |
| 26 | `Brightness/Contrast...` | fail | fail | fail | fail | ContrastAdjuster (PlugInDialog) → HeadlessException; swallowed. |
| 27 | `Threshold...` | fail | fail | fail | fail | ThresholdAdjuster (PlugInDialog) → HeadlessException; swallowed. |
| 28 | `Undo` | pass | pass | pass | pass | Single-level undo restores pixels. |
| 29 | `8-bit` | pass | pass | pass | pass | 32-bit ramp → 8-bit. |
| 30a | `Tiff...` `path=[…]` | pass | pass | pass | pass | Writer → FileSaver.saveAsTiff, key `path`. |
| 30b | `Open...` `path=[…]` | fail | fail | fail | fail | run("Open...") produces no ImagePlus headless; see 30c. |
| 30c | `open(path)` (macro only) | — | — | pass | pass | Macro open(path) works. |
| 31 | `Find Maxima...` `prominence=10 output=Count` | fail | fail | fail | fail | Count goes to a new ResultsTable when no Results window (MaximumFinder.java#L797-L799). |
| 32 | `Image Calculator...` `image1=a operation=Add image2=b create` | fail | fail | pass | pass | Needs WindowManager-visible inputs. |
| 33 | `Set Scale...` `distance=10 known=1 unit=um` | pass | pass | pass | pass |  |
| 34 | `Add Slice` | pass | pass | pass | pass |  |

Cross-cutting observations:

1. **Exceptions are swallowed.** `HeadlessException` from `ImageWindow`/`PlugInFrame`/`PlugInDialog` constructors is caught in `Executer.run` and logged. `IJ.run` returns, and a macro continues to the next line (ops 12, 26, 27). An adapter must detect failure by **postconditions** (new image, changed pixels, results rows), not by exceptions.
2. **Precondition errors abort.** `IJ.error` during a macro (e.g. "This command requires a selection") aborts the interpreter. Under `IJ.run` from Java it surfaces as `RuntimeException("Macro canceled")` (op 16b, 25).
3. **Hidden macro state.** A macro `setColor(v)` makes a later `run("Fill")` use `v` instead of the foreground colour (`Filler.java#L98-L99`; op 23 versus 23b).
4. **Images created by commands** become the thread-local temp image. They are visible to `WindowManager.getIDList()` only inside the macro interpreter's batch mode (`ImagePlus.java#L496-L502`; `WindowManager.java#L160-L179`). Multi-image commands (Merge Channels, Image Calculator) therefore need the interpreter (ops 20, 32).
5. **ROI Manager** cannot be constructed headless (`RoiManager.<init>` → `PlugInFrame`, P13 trace).
6. **Headless dialogs.** ij1-patcher's `HeadlessGenericDialog.getCheckboxes()` returns `null` (`HeadlessGenericDialog.java#L290`), so `Histogram.java#L131` throws an NPE for stacks (op 35).

### Data file schema

`docs/data/commands.csv` (UTF-8, comma-separated, `;` inside multi-value cells, F04 §6). Five columns were added to the brief's list, each with a reason:

| Column | Meaning |
|---|---|
| `command_id` | Stable ID: `ij1:<menu path>><label trimmed>`, `ij2:<class>`, `script:<scripts\|plugins>/<path>` or `script:<jar>!/<entry>`, `ij1macro:` (suppressed IJ1 macro), `ij1lut:`, `ij1example:`, `ij1popup:`, `ij1tool:<TOOL>`, `macro-tool:<file>#<name>`. There are no duplicates (a `#n` suffix is reserved) |
| `label` | Exact command-table key, including significant spaces (the `IJ.run`/`run()` argument) |
| `menu_path` | Parent menu path as declared statically; the runtime path is appended in `notes` when it differs |
| `shortcut` | `Ctrl+X`/`Ctrl+Shift+X`/`F1`/`NumPadX`, `label-hint[x]` (bracket kept in label, not a menu shortcut), `macro-key[x]`, or SciJava accelerator text |
| `framework` | `IJ1`, `IJ2`, `script`, `macro-tool`, `IJ1-tool` (added for built-in toolbar tools) |
| `implementation`, `arg` | IJ1 class and `("arg")`; IJ2 class; script path; LUT/example file |
| `source_artifact` | File/jar entry with line (`#Lnn`) or upstream source line |
| `source_kind` | `IJ_Props`, `Menus.java`, `plugins.config`, `luts-dir`, `plugins-dir-class`, `plugins-dir-script`, `scripts-dir`, `jar-scripts-resource`, `scijava-index`, `startup-macros`, `Toolbar.java`, `runtime-only`. Added because the brief's four values cannot express the hard-coded `Menus.java` items or the script and LUT sources |
| `headless_probe` | `pass`, `fail`, `partial` (passes in some invocation paths only; added for honesty), or `untested` |
| `notes` | Plugin type, priority, `headless`/`visible`/attrs (IJ2), runtime menu path, probe detail |
| `runtime_status` (added) | `present`, `shadowed`, `overridden-by-IJ2`, `absent`, `suppressed`, `module`, `module + IJ1 command table`, `menu-only`, `n/a`. Needed to record the static/runtime diff in machine-readable form |
| `runtime_value` (added) | Runtime command-table value or module identifier |
| `can_run_headless` (added) | `ModuleInfo.canRunHeadless()` at runtime (IJ2/scripts) |
| `creates_new_image` (added at G5's request) | `yes`/`no`/`conditional`, curated core set only |
| `preconditions` (added at G5's request) | Image type, ROI or stack requirement with source line, curated core set only |

`docs/data/commands-core-parameters.csv`: `command_id, parameter_key, type, default, range_or_choices, recorded_as, source` as briefed. Three columns were added at G5's request:

- `consequential` (yes/no): the parameter changes numerical or scientific output.
- `native_unit`: the unit of the parameter.
- `hidden_global_dependency`: Prefs or static state read or written.

`(none)` rows mark commands without a dialog. Parameters that apply only to stacks or hyperstacks say so in `range_or_choices`. Auto Threshold keys are **INFERRED**: they come from labels in the class constant pool, because the plugin's source is not cached. Their defaults are **UNKNOWN**.

Key rules for designers, all VERIFIED from source:

- An **absent checkbox key means `false` in macro mode**, not the dialog default (`GenericDialog.java#L1349-L1350`). An absent numeric or choice key falls back to the dialog's current value, which is often a static "last used" value (e.g. `GaussianBlur.sigmaS`, `Rotator.angle`, `RankFilters.lastRadius`). Replaying an incomplete options string is therefore **state-dependent**.
- Several commands write global state that persists for later commands: `Set Measurements...` (Analyzer flags), `Binary>Options...` (`Prefs.blackBackground`, `Prefs.padEdges`), Edit>Options>Conversions (`ImageConverter.setDoScaling`, `Options.java#L186`), Z Project (`Prefs zproject.method`), and Subtract Background (`Prefs bs.background`).

## Constraints and Limitations

- **Coverage.** Only the installed pinned distribution is covered (662 jars, 99 `plugins/` entries). Update sites are not audited. Plugins installed later, user `~/.plugins` and `ij1.plugin.dirs` are out of scope (`LegacyHooks.java#L265-L289`). Third-party plugins are inventoried only by registration (menu line and class). Their parameters and headless behaviour are `untested`.
- **Headless results** were obtained with synthetic 8-bit/RGB/32-bit images up to 256×256 and default Prefs. They do not establish numerical equivalence (A30), GUI-JVM behaviour or behaviour on real data.
- **Static menu paths** for IJ1 items reflect `Menus.java`'s rules. The final GUI order (separators, sorted insertion) was not captured because no GUI JVM was run. `P12-effective-menu.json` gives the headless runtime paths.
- The launcher's console output was not observable (P16). The error text in this document comes from in-JVM capture (P13, P15).

## Requirement implications

- **CR-A08-01** The application shall resolve every user-visible operation to an exact `commands.csv` `label` (including leading and trailing spaces), and shall reject labels that are not in the runtime command table before execution. *Rationale: IJ1 dispatch is by exact label; unknown labels abort macros (P16) or silently do nothing.*
- **CR-A08-02** The command catalogue shown to users and to the NL layer shall be built at runtime from `Menus.getCommands()` plus `ModuleService`, not from a static list. *Rationale: Fiji rewrites the table (overrides, `no-legacy` removal, suppressed IJ1 scripts); a static list diverges (§6).*
- **CR-A08-03** The execution adapter shall decide success from postconditions (expected new image, pixel change, results-table delta, error capture via an `IJ.log`/`IJ.error` hook) and shall not rely on exceptions from `IJ.run`. *Rationale: `Executer.java#L74-L117` swallows Throwables (P13 ops 12, 26, 27).*
- **CR-A08-04** Operations whose execution would always send a complete options string shall send every parameter key of `commands-core-parameters.csv` explicitly, including `false` checkboxes by omission, and shall never depend on "last used" static defaults. *Rationale: absent keys fall back to static state (GenericDialog.java#L1197, #L1349-L1350).*
- **CR-A08-05** The UI shall surface, before execution, the hidden global dependencies of a command (`hidden_global_dependency` column), in particular `Prefs.blackBackground`, the Analyzer measurement flags and conversion scaling. *Rationale: they change scientific results without appearing in the macro text.*
- **CR-A08-06** Commands with `headless_probe` other than `pass` shall be executed only in a GUI-capable JVM, or through a documented macro-function substitute (e.g. `setMinAndMax`, `setThreshold`, `open(path)`). *Rationale: HeadlessException/no-result cases in §7.*
- **CR-A08-07** Multi-image commands (Merge Channels, Image Calculator) and stack-producing workflows shall run inside the macro interpreter with batch mode, or an equivalent registration of images, so that `WindowManager` sees the inputs. *Rationale: ops 20, 32 fail under bare `IJ.run`.*
- **CR-A08-08** Any headless runner shall terminate the JVM explicitly after a workflow (`System.exit` or equivalent) and enforce a timeout. *Rationale: Gaussian Blur and Median left the `-macro` process alive (P14 exit-hang); `--run` scripts do not exit by themselves (P13).*
- **CR-A08-09** Macro recording that the product exposes shall come from a GUI-JVM Recorder or from the product's own command log. Headless recording shall not be assumed. *Rationale: `IJ.java#L2578-L2580`.*
- **CR-A08-10** The catalogue shall flag duplicate and shadowed labels (10 in this install) and shall show the class that will actually run. *Rationale: label collisions silently bind to a different implementation.*

## Risks and Unknowns

- **R-A08-01 Label drift across versions.** Likelihood: medium. Impact: high (recorded workflows break). Mitigation: pin the version; regenerate `commands.csv` with P10–P12 on every runtime change; diff the labels.
- **R-A08-02 Silent failure in headless execution.** Likelihood: high. Impact: high (wrong or no scientific output reported as success). Mitigation: CR-A08-03 postcondition checks; install a log/error hook; never treat a return from `IJ.run` as success.
- **R-A08-03 State-dependent defaults.** Likelihood: high. Impact: high (non-reproducible results). Mitigation: CR-A08-04/05; snapshot relevant Prefs before each run and record them with provenance.
- **R-A08-04 Third-party plugin behaviour not audited.** Likelihood: high. Impact: medium. Mitigation: treat non-core rows as `untested`; probe on demand before exposing them to NL.
- **R-A08-05 Exit hangs in headless runs.** Likelihood: medium. Impact: medium (resource leaks, stuck jobs). Mitigation: CR-A08-08. The root cause (a lingering non-daemon thread after `ExtendedPlugInFilter` runs) is **UNKNOWN**; verify with a thread dump (`jcmd <pid> Thread.print`) during an exit-hang.
- **UNKNOWN:** the cause of the Groovy-path failure for ops 20 and 32 under `Interpreter.batchMode=true` (only one image was registered). Verify by inspecting `Interpreter.imageTable` after each `show()`. The cause of `run("Open...")` producing no image headless (op 30b) is also UNKNOWN; check `DefaultLegacyHooks.interceptFileOpen` → `IOService` path. GUI-JVM shortcut table and menu order are unverified.

## References

- `imagej/ImageJ@v1.54p` (03382be1): `ij/Menus.java`, `ij/IJ.java`, `ij/Executer.java`, `ij/Macro.java`, `ij/gui/GenericDialog.java`, `ij/gui/GUI.java`, `ij/gui/Toolbar.java`, `ij/plugin/MacroInstaller.java`, `ij/plugin/filter/*.java` and `ij/plugin/*.java` as cited.
- `imagej/imagej-legacy@imagej-legacy-3.0.0` (fc31a563): `LegacyService.java`, `IJ1Helper.java`, `DefaultLegacyHooks.java`, `command/LegacyCommandFinder.java`, `command/LegacyCommandInfo.java`, `plugin/MacroRecorder*processor.java`.
- `imagej/ij1-patcher@ij1-patcher-2.0.0` (70e570dc): `LegacyExtensions.java`, `LegacyInjector.java`, `LegacyHooks.java`, `HeadlessGenericDialog.java`.
- `scijava/scijava-common@scijava-common-2.101.0` (d7219693): `plugin/Plugin.java`, `plugin/PluginInfo.java`, `plugin/DefaultPluginFinder.java`, `command/CommandInfo.java`, `script/ScriptFinder.java`, `script/DefaultScriptService.java`, `MenuPath.java`.
- Installed files: `Fiji/jars/ij-1.54p.jar!/IJ_Props.txt`, all `plugins.config` and `META-INF/json/org.scijava.plugin.Plugin` entries, `Fiji/config/jaunch/fiji.toml`, `Fiji/macros/StartupMacros.fiji.ijm`.
- Probes: `probes/P10`–`P16` ([README-G1](../references/probes/README-G1.md)); related: `probes/P30-recorder-headless.out.txt`, `probes/P48-plugin-index-inventory.out.txt` (independent index count, also 1950 entries in 662 jars).

## Change log

- 2026-10-17: Initial version (G1).
