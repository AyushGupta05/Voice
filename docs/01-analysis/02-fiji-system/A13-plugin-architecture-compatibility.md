---
id: A13
title: Plugin Architecture and Compatibility
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p, imagej/imagej-legacy@imagej-legacy-3.0.0, imagej/ij1-patcher@ij1-patcher-2.0.0, scijava/scijava-common@scijava-common-2.101.0, imagej/imagej-common@imagej-common-2.1.2, imagej/imagej-ops@imagej-ops-2.2.0, imagej/imagej-updater@imagej-updater-2.0.3, fiji/fiji@ddc9a45d]
related_requirements: []
related_decisions: []
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A13 — Plugin Architecture and Compatibility

## Executive Summary

The pinned Fiji contains these plugin families:

- ImageJ 1.x plugins: about 438 `plugins.config` menu lines in 96 jars, plus underscore-named classes, jars and scripts;
- 225 SciJava `Command` plugins (120 flagged `headless = true`);
- 901 imagej-ops op entries;
- 12 script languages;
- several large applications that open their own Swing/AWT/Java 3D windows (TrakEM2, 3D Viewer, Trainable Weka, TrackMate, BigDataViewer, Labkit).

Counts are from P48 (annotation index and `plugins.config`). Class declarations are from P45 and static GUI references from P44.

Plugins reach state in two ways. IJ1 plugins pull it from static singletons (`WindowManager.getCurrentImage`, `IJ.getImage`, `RoiManager.getInstance`, `ResultsTable.getResultsTable`). IJ2 modules receive it from preprocessors (`ActiveImagePlusPreprocessor`, `net.imagej.display.process.ActiveImagePreprocessor`, `RoiManagerPreprocessor`, `ResultsTablePreprocessor`, `MacroPreprocessor`).

This document defines seven compatibility categories (C1–C6 plus S for scripts) and gives installed examples with evidence. **Compatibility is a property of each plugin's code path, not of its family.** Two probe results show why: a plain `GenericDialog` plugin works headless with macro options (P41 D2), yet the Bio-Formats Exporter, also driven by `GenericDialog`, fails headless with a `NullPointerException` (P46 b1) and works in a non-headless JVM (P47 S2).

## 1. Purpose and Scope

This document covers how plugins are written, registered, discovered and installed, how they obtain image, ROI and results state, and how they can be classified for a modern UI that preserves plugin compatibility. GUI mechanisms in detail are in [A15](A15-gui-dependence-reuse-boundaries.md); architecture is in [A07](A07-fiji-imagej-architecture.md). `docs/data/commands.csv` (agent G1) did not exist when this was written (checked 2026-10-17), so per-command classification is left to it.

## 2. Sources and Evidence

- IJ1 source `imagej/ImageJ@v1.54p`; bridge sources as listed in the front matter.
- P44: static bytecode reference scan of `plugins/**/*.jar` and 10 `jars/` entries (`probes/P44-plugin-gui-reference-scan.out.txt`). A reference proves the class **links against** an API, not that it executes it.
- P45: `javap` declarations of 48 classes (`probes/P45-class-hierarchy-javap.out.txt`).
- P48: annotation-index and `plugins.config` inventory of all 662 jars (`probes/P48-*.csv`).
- Runtime probes P41, P42, P46, P47 ([A15](A15-gui-dependence-reuse-boundaries.md)).

## 3. Plugin families

| Family | Contract (pinned source) | Registration | Typical UI | Evidence |
|---|---|---|---|---|
| IJ1 `PlugIn` | `void run(String arg)` (`ij/plugin/PlugIn.java`) | `IJ_Props.txt`, `plugins.config`, or an underscore class/jar in `plugins/` | Whatever the plugin builds; often `GenericDialog` | VERIFIED |
| IJ1 `PlugInFilter` | `int setup(String arg, ImagePlus imp)` returns capability flags (`DOES_ALL`, `DOES_STACKS`=32, `SUPPORTS_MASKING`=64, `NO_CHANGES`=128, `NO_IMAGE_REQUIRED`=512, `PARALLELIZE_STACKS`=32768, `FINAL_PROCESSING`=65536; `ij/plugin/filter/PlugInFilter.java#L49-L82`), then `run(ImageProcessor)`; driven by `PlugInFilterRunner` against the current image | as above | `GenericDialog` in `setup` or before it | VERIFIED |
| IJ1 `ExtendedPlugInFilter` | Adds `showDialog(ImagePlus, String, PlugInFilterRunner)`, `setNPasses`, flag `KEEP_PREVIEW` (`ij/plugin/filter/ExtendedPlugInFilter.java#L53-L70`); preview through `DialogListener` (e.g. `ij.plugin.filter.GaussianBlur implements ExtendedPlugInFilter, DialogListener`, P45) | as above | `GenericDialog` with preview | VERIFIED |
| IJ1 `PlugInFrame` / `PlugInDialog` | `PlugInFrame extends java.awt.Frame implements PlugIn`; `PlugInDialog extends java.awt.Dialog implements PlugIn` (P45). Core examples: `RoiManager`, `Recorder` (frames), `ContrastAdjuster`, `ThresholdAdjuster` (dialogs) | as above | Persistent non-modal window | VERIFIED |
| IJ1 macro / script in `plugins/` | `.ijm`/`.txt` macros and scripts are found by `Menus` (`validMacroName`, `ij/Menus.java#L1068-L1085`). In Fiji, IJ1 script discovery is suppressed (`LegacyEnvironment.suppressIJ1ScriptDiscovery`, called from `LegacyService.java#L776`) and the SciJava `ScriptService` takes over | `plugins/` is a script directory with menu prefix `Plugins` and `plugins/Scripts` (`LegacyService.java#L484-L489`); top-level `scripts/` (`ScriptService.SCRIPTS_RESOURCE_DIR = "scripts"`, `scijava-common@…:src/main/java/org/scijava/script/ScriptService.java#L72`) | `#@` parameters → input harvester | VERIFIED |
| SciJava `Command` | `@Plugin(type = Command.class, menuPath = …, headless = …)` + `@Parameter` fields; `run()` | annotation index (`META-INF/json/org.scijava.plugin.Plugin`) | Input harvester of the visible UI ([A07](A07-fiji-imagej-architecture.md) §7.2) | VERIFIED |
| SciJava `Op` | `net.imagej.ops.Op` plugins, called by name through `OpService.run("…")`; 901 entries (P48) | annotation index | None (programmatic); some op commands, e.g. `net.imagej.ops.commands.morphology.FillHoles` (`headless = true`) | VERIFIED |
| SciJava `Service` | `@Plugin(type = Service.class)`; 115 installed (P48) | annotation index | None | VERIFIED |
| IJ1 commands wrapped for IJ2 | `LegacyCommandFinder` wraps every IJ1 menu command as a `LegacyCommandInfo` module (830 at runtime, P49 A6); executed through `net.imagej.legacy.command.LegacyCommand implements Command` (P45) | automatic | IJ1 GUI | VERIFIED |
| Fiji compatibility code | `sc.fiji.compat.DefaultFijiService` registers AWT `FileDialog`/`JFileChooser` decorators and runs `MenuRefresher` when an IJ1 main frame exists (`fiji/fiji@ddc9a45d:src/main/java/sc/fiji/compat/DefaultFijiService.java#L48-L75`); `FijiAppConfiguration` (`LegacyAppConfiguration`), `DefaultLegacyPostRefreshMenus`; deprecated `fiji.IJ1Patcher`, which defers to `LegacyInjector.preinit()` (`fiji/IJ1Patcher.java#L52-L62`) | annotation index | — | VERIFIED |
| Applications with their own windows | e.g. `ini.trakem2.*`, `ij3d.*`, `trainableSegmentation.*`, `fiji.plugin.trackmate.*`, `bdv.*`, `sc.fiji.labkit.*` | `plugins.config` (IJ1 entry) or `@Plugin` | Custom Swing/AWT/Java 3D | VERIFIED (§7) |

IJ2 commands tagged `no-legacy` are removed from the module registry at start-up unless `-Dimagej.legacy.modernOnlyCommands=true` is set (`LegacyService.java#L491-L501`, property name at L126-L127). The string `no-legacy` occurs 136 times in `imagej-plugins-commands-1.0.0.jar`'s index, 24 times in `imagej-common` and 8 times in `scijava-plugins-commands`. Most of the 139 IJ2 commands in `imagej-plugins-commands` are therefore hidden in Fiji. **VERIFIED** (counts by string occurrence).

## 4. Menu registration and discovery

- **IJ1 built-ins.** `jars/ij-1.54p.jar!/IJ_Props.txt` (e.g. `save01="Tiff...",ij.plugin.filter.Writer("tiff")`). **VERIFIED**
- **`plugins.config`.** Lines have the form `Menu>Submenu, "Label", fully.qualified.Class("arg")`, e.g. `Plugins>Segmentation, "Trainable Weka Segmentation", trainableSegmentation.Weka_Segmentation`. They are parsed by `Menus.installJarPlugin` (`ij/Menus.java#L764-L800`). A line starting `Plugins>` goes to a Plugins submenu; any other menu name is resolved with `getMenu(name)` (e.g. `File>Import`, `Image>Adjust`). ij1-patcher fixes an NPE and removes duplicate jar entries here (`LegacyInjector.java#L272-L291`). **VERIFIED**
- **Underscore rule.** In `plugins/`, only `.class` files, `.jar` and `.zip` whose names contain `_` are installed as plugins (`ij/Menus.java#L1076-L1082`). For SciJava scripts to appear in the legacy menus, their file name must also contain `_` (`LegacyService.getScriptsAndNonLegacyCommands`, `LegacyService.java#L678`). **VERIFIED**
- **Class loading.** The Jaunch classpath holds `jars/*.jar`, `jars/*/*.jar`, `plugins/*.jar` and `plugins/*/*.jar` (P40, `config/jaunch/fiji.toml` `jvm.classpath`). imagej-legacy calls `noPluginClassLoader()`, so IJ1 uses the context class loader instead of its own `PluginClassLoader` (`LegacyService.java#L775`, `ij1-patcher@…:LegacyExtensions.java#L869-L900`). The patched `PluginClassLoader` also adds `jars/` recursively when it is used (`LegacyInjector.java#L242-L269`). Extra plugin jars come from `-Dij1.plugin.dirs` or `~/.plugins` (`LegacyHooks.handleExtraPluginJars`, `LegacyHooks.java#L265-L290`). **VERIFIED**
- **How `plugins.config` inside `jars/` (e.g. `jars/TrackMate-8.1.6.jar`, 4 lines, P48) becomes menu items.** The likely route is the patched `Menus.getPlugins` that appends `handleExtraPluginJars()` results (`LegacyExtensions.java#L490-L509`), but the exact path was not traced. **UNKNOWN**. To verify, check `ij.Menus.getCommands().get("TrackMate")` in a probe. G1's command catalogue may already settle it.
- **SciJava discovery.** The annotation index, read by `DefaultPluginFinder` (`scijava-common@…:src/main/java/org/scijava/plugin/DefaultPluginFinder.java#L81`). No class scanning happens at runtime, so a plugin is visible only if its jar carries the index, which the annotation processor generates at compile time. Runtime registration is possible through `PluginService.addPlugin` or `UIService.addUI` (P49). **VERIFIED**

## 5. How plugins access state

| Accessor | Semantics at pin | GUI coupling | Evidence |
|---|---|---|---|
| `WindowManager.getCurrentImage()` | Thread-local temp image (`tempImageTable`) first, then the current window, then the front window, then the newest window in the image list, then `Interpreter.getLastBatchModeImage()` (`ij/WindowManager.java#L69-L113`) | Window-based except the temp and batch fallbacks | VERIFIED |
| `IJ.getImage()` | As above, but with no image it calls `IJ.noImage()` and then **`System.exit(0)` if `ij == null`** (no IJ1 main frame, i.e. headless), otherwise `abort()` (`ij/IJ.java#L1806-L1816`) | Kills a headless JVM | VERIFIED (source); runtime INFERRED |
| `IJ.run(imp, cmd, options)` | Sets per-thread macro options and the thread name prefix `Run$_`, then runs `Executer` synchronously in the calling thread (`ij/IJ.java#L310-L332`) | None per se | VERIFIED |
| `RoiManager.getInstance()` / `getRoiManager()` | Singleton window. In a macro with no window, `getInstance()` returns the hidden batch-mode manager; `getRoiManager()` creates one with `new RoiManager()`, which shows a window (`ij/plugin/frame/RoiManager.java#L87-L112`, `#L2068-L2087`) | `Frame`, so `HeadlessException` when headless (P41 G1/G2) | VERIFIED |
| `ResultsTable.getResultsTable()` | Static system table `Analyzer.systemRT` (`ij/measure/ResultsTable.java#L98-L100`, `ij/plugin/filter/Analyzer.java#L999-L1001`) | None; works headless (P41 F1) | VERIFIED |
| `ResultsTable.getResultsTable(String title)` / `getActiveTable()` | Looks up a `TextWindow` frame by title (`ResultsTable.java#L103-L120`) | Named tables exist only as windows | VERIFIED |
| IJ2 `@Parameter ImagePlus/ImageStack/ImageProcessor` | `ActiveImagePlusPreprocessor` (priority VERY_HIGH+1) fills the single input from `WindowManager.getCurrentImage()` (`imagej-legacy@…:src/main/java/net/imagej/legacy/plugin/ActiveImagePlusPreprocessor.java#L78-L114`) | Same as `getCurrentImage` | VERIFIED; headless injection worked (P41 I6) |
| IJ2 `@Parameter Dataset/ImageDisplay/DatasetView` | `net.imagej.display.process.ActiveImagePreprocessor` → `ImageDisplayService.getActiveImageDisplay()`, which the legacy override maps from the current `ImagePlus` (`imagej-common@…:src/main/java/net/imagej/display/process/ActiveImagePreprocessor.java#L63-L113`; `imagej-legacy@…:…/display/LegacyImageDisplayService.java#L113-L123`) | As above | VERIFIED (P41 I6) |
| IJ2 `@Parameter RoiManager` | `RoiManagerPreprocessor`: a required input uses `getRoiManager()` (may open a window), an optional one uses `getInstance()` (`…/legacy/plugin/RoiManagerPreprocessor.java#L57-L74`) | Window | VERIFIED |
| IJ2 `@Parameter ResultsTable` | `ResultsTablePreprocessor` → `ResultsTable.getResultsTable()` (`…/ResultsTablePreprocessor.java#L52-L61`) | None | VERIFIED |
| IJ2 inputs from macro options | `MacroPreprocessor` (priority 2×VERY_HIGH) converts `ij1Helper.getMacroParameter(name)` to each input when `IJ.isMacro()` (`…/legacy/plugin/MacroPreprocessor.java#L54-L95`) | None | VERIFIED |

## 6. Update sites

- Format: an update site is a URL holding `db.xml.gz` (`UpdaterUtil.XML_COMPRESSED`, `imagej-updater@…:src/main/java/net/imagej/updater/util/UpdaterUtil.java#L88`). Each `<plugin filename=…>` record carries a current `<version checksum timestamp filesize>`, descriptions, authors, dependencies and `<previous-version>` entries. Files are downloaded from `<site URL><filename>-<timestamp>` (`FilesCollection.java#L946`), staged under `update/` (`FilesCollection.java#L1117`), and moved into place by `Installer.moveUpdatedIntoPlace` (`Installer.java#L399`). Jaunch applies pending updates at launch (`config/jaunch/fiji.toml` directive `LAUNCH:JVM|!--dry-run|apply-update`). **VERIFIED**
- The pinned installation's `db.xml.gz` lists one update site, `Fiji-Latest` (`https://sites.imagej.net/Fiji/`, `official="true"`, timestamp `20261013172405`), and 1 291 plugin records (read-only `gzip` parse, 2026-10-17). The [Update Sites](https://imagej.net/update-sites/) page (accessed 2026-10-17) says Fiji "ships with the `ImageJ` and `Fiji` sites enabled", which does not match this installation. **VERIFIED** (local file); the discrepancy is noted, not explained.
- User model: users enable further sites through *Help › Update… › Manage update sites* (documentation). Personal sites are hosted under `sites.imagej.net` (`HTTPSUtil.java#L51-L52`). Third-party sites can add any of the families in §3, so the compatibility of installed plugins cannot be known ahead of time. **VERIFIED** (documentation, code); **INFERRED** (consequence).
- The Updater writes into the installation (`jars/`, `plugins/`, `update/`, `.checksums`) and checks for updates automatically when a non-headless UI is first shown (`DefaultUpdateService.java#L113-L125`). P49 triggered this check ("Reading available sites from https://imagej.net/"). **VERIFIED**

## 7. Compatibility categories

Categories describe what a plugin needs from the host. A plugin can belong to several; its **highest** category governs. Execution modes are those of [A15](A15-gui-dependence-reuse-boundaries.md) §9.

| Cat. | Definition | Detection signal | Host requirement | Examples at pin (evidence) |
|---|---|---|---|---|
| **C1** | Headless-safe parameterised command: all inputs are `@Parameter` values or explicit objects, no AWT | `@Plugin(headless = true)` (120 commands, P48; only a hint, `Plugin.java#L153-L168`); ops; no AWT references | Any mode; drive through `CommandService`/`OpService` | `net.imagej.ops.commands.morphology.FillHoles`, `…filter.FrangiVesselness` (headless=true, P48); SCIFIO `io.scif.commands.*`; a script with `#@ Dataset` input ran headless (P41 I6); `ops.run("stats.mean")` (P41 I5) |
| **C2** | Needs active image/ROI/Results **state** but opens no custom window | `PlugInFilter`, `WindowManager`/`Analyzer` references, no `Frame`/`JFrame`/`ImageCanvas` | Any mode, if the host sets the current image (`IJ.run(imp,…)`, temp current image) | `sc.fiji.skeletonize3D.Skeletonize3D_` (`PlugInFilter`, 0 `GenericDialog` refs, P44/P45); core `Invert`, `Convert to Mask`, `Measure` (P41 E7, E8, F1) |
| **C2r** | C2 plus the **ROI Manager** | `ij/plugin/frame/RoiManager` reference | GUI-capable JVM (hidden `RoiManager(false)` or batch-mode macro manager); fails headless | P44 RoiManager refs: `Colocalisation_Analysis` (1), `ij_ridge_detect` (1), `QuickPALM_` (1), `BalloonSegmentation_` (1), `Stitching_` (2), `Time_Lapse` (3), `TrackMate` (3), `bio-formats_plugins` (1); macro `roiManager("add")` aborts headless with `HeadlessException` (P46 a1) and works in batch mode in a GUI-JVM (P42 F3) |
| **C3** | Interaction only through `GenericDialog`; can be driven by macro options | `GenericDialog` refs, no window refs | GUI-JVM: reliable (P42 E1/E2, P47 S2). Headless: only if every dialog method used is implemented by `HeadlessGenericDialog`; `NonBlockingGenericDialog` fails headless (P41 D3) | `fiji.threshold.Auto_Threshold` (`PlugIn`, 1 GD class, P44/P45), `ij.plugin.filter.GaussianBlur` (P41 E6), `Auto_Local_Threshold`, `Kuwahara_Filter`, `Statistical_Region_Merging` (1 GD, P44); counter-example: `loci.plugins.LociExporter` (`PlugInFilter`, P45) fails headless with `NullPointerException … "checkboxes" is null` (P46) |
| **C4** | Opens its own Swing/AWT window (wizard, control panel, viewer), but does not depend on IJ1 `ImageWindow` events | `JFrame`/`Frame`/`JDialog` refs | GUI-JVM with real windows (legacy-window fallback). Some have a scriptable core usable as C1/C2 | TrakEM2 (`ini.trakem2.*`, 41 classes ref `JFrame`, 17 `Frame`, P44); BigDataViewer (`bdv.viewer.ViewerFrame extends JFrame`; entry `bdv.ij.BigDataViewerPlugIn implements Command`, P45); TrackMate GUI (27 `JFrame` refs; core `fiji.plugin.trackmate.TrackMate implements Algorithm`, a scriptable core, P45); Labkit (22 `JFrame`); `sc.fiji.cellCounter.CellCounter extends JFrame`; `Volume_Viewer`; `Interactive_3D_Surface_Plot` (4 `JFrame`) |
| **C5** | Depends on IJ1 `ImageWindow`/`StackWindow`/`ImageCanvas` events or the Toolbar (custom window subclasses, canvas listeners, tools) | `ImageCanvas`/`StackWindow`/`Toolbar`/`MouseListener` refs; subclasses of `ImageWindow` | Visible IJ1 image windows on screen; hidden windows cannot receive user input ([A15](A15-gui-dependence-reuse-boundaries.md) §7) | Trainable Weka (`trainableSegmentation.Weka_Segmentation$CustomWindow extends ij.gui.StackWindow`, P45); Cell Counter (`Cell_Counter extends PlugInFrame`, `ImageCanvas` 2, `StackWindow` 1); `Sync_Win` (`ImageWindow` 4, `StackWindow` 4, `ImageCanvas` 5); `BalloonSegmentation_`; `Time_Lapse` (`ImageCanvas` 6); `Image_5D` (`ImageWindow` 12); `VIB_` (`ImageCanvas` 15); `Arrow_` and `Lasso_and_Blow_Tool` (tools) |
| **C6** | Native rendering (Java 3D/JOGL/OpenGL) | `org/jogamp/java3d/` refs; natives in `jars/win64/` (`jogl-all-2.6.0-natives-windows-amd64.jar`, `gluegen-rt-2.6.0-natives-windows-amd64.jar`) | GUI-JVM with a working OpenGL context; cannot be embedded in a non-Java UI | 3D Viewer (92 classes ref Java 3D; `ij3d.DefaultUniverse extends org.jogamp.java3d.utils.universe.SimpleUniverse`; `ij3d.ImageWindow3D extends JFrame`, P44/P45); `Volume_Calculator` (3), `VIB_` (6), TrakEM2 (8) |
| **S** | Script/macro plugins (`.ijm`, `.py`, `.groovy`, `.bsh`, `.js`, `.rb`, `.clj`) | file under `scripts/` or `plugins/` | Category of whatever the script calls | Installed: 11 `.ijm`, 15 `.py`, 10 `.bsh`, 6 `.rb`, 6 `.clj`, 5 `.js`, 1 `.groovy`, 12 `.txt` under `scripts/`, `plugins/`, `macros/` (count 2026-10-17) |

Notes:

1. Static evidence (P44) is an over-approximation. A jar that links `ImageWindow` may use it only in an optional code path. Runtime classification needs a per-command probe. **INFERRED**
2. The IJ1 core (`ij-1.54p.jar`) spans every category: 152 classes reference `GenericDialog`, 63 `ImageWindow`, 57 `ImageCanvas`, 14 `PlugInFrame` (P44). Per-command classification belongs in G1's command catalogue. **VERIFIED** (counts)
3. `ExtendedPlugInFilter` preview needs a displayed image to preview against. In macro mode, `GenericDialog.showDialog` disposes the dialog without showing it (`ij/gui/GenericDialog.java#L1506-L1510`), so preview is unavailable. A modern UI that wants live preview must run the filter itself on a duplicate. **INFERRED**

## 8. Constraints and Limitations

- The `headless = true` annotation is a hint that nothing checks (`Plugin.java#L153-L168`). Most IJ1 plugins carry no machine-readable compatibility metadata at all. **VERIFIED**
- Third-party update sites can install any category, including those that patch IJ1 behaviour or add their own UI. **INFERRED**
- No universal support claim can be made. The categories are a triage aid, not a guarantee.

## 9. Requirements or Design Implications

- **PROPOSED:** Classify every catalogued command (G1 `commands.csv`) into C1–C6/S with an evidence column. Run static evidence (P44 method) automatically and confirm runtime evidence per command with a probe harness that runs the command in each [A15](A15-gui-dependence-reuse-boundaries.md) mode.
- **PROPOSED:** Plugins in C4–C6 keep their native windows (the legacy-window fallback). The modern UI launches them, tracks them, and receives their results through IJ1 state (current image, Results, ROI Manager), but does not re-implement them.

## 10. Alternatives and Trade-offs

| Option | Benefit | Cost |
|---|---|---|
| Support only C1–C3 in the new UI and send everything else to "open classic Fiji" | Simple | Breaks the plugin-compatibility goal for interactive tools (Weka, Cell Counter, 3D Viewer) |
| Host the new UI in the Fiji JVM and let C4–C6 open native windows (fallback) | Preserves all plugins | Two visual languages; window management complexity ([A15](A15-gui-dependence-reuse-boundaries.md) §8) |
| Re-implement C5 interactions (canvas tools) in the new viewer | Uniform UX | Each plugin's canvas logic is private; cannot be reused generally. Contradicts the "do not recreate algorithms" rule |

## 11. Risks and Unknowns

| ID | Item | Tag | Resolution |
|---|---|---|---|
| U-A13-1 | Route by which `jars/*.jar` `plugins.config` entries enter IJ1 menus | UNKNOWN | Probe `Menus.getCommands()` |
| U-A13-2 | Fraction of the 830 IJ1 commands in each category | UNKNOWN | Per-command probe harness over G1's catalogue |
| U-A13-3 | Which `HeadlessGenericDialog` gaps (e.g. `getCheckboxes()` returning `null`) affect which plugins | UNKNOWN | Static scan for calls to non-overridden `GenericDialog` methods |

## Requirement implications

1. **CR-A13-01**: The system shall record, for every exposed Fiji command, a compatibility category (C1–C6/S) and the evidence (static scan, probe ID) used to assign it. *Rationale:* family alone does not predict behaviour (§7, P46 vs P47).
2. **CR-A13-02**: The system shall execute IJ1 commands with `IJ.run(ImagePlus, String command, String options)` on a thread whose macro options are set, never by calling plugin classes directly. *Rationale:* macro options are read per thread only on `Run$_` threads (§5, `ij/Macro.java#L86-L94`).
3. **CR-A13-03**: Commands in C4–C6 shall remain runnable with their original Swing/AWT/Java 3D windows (legacy-window fallback), and the UI shall show that an external window is in use. *Rationale:* preserves plugin compatibility without re-implementation (§7).
4. **CR-A13-04**: The system shall not call `IJ.getImage()`, or any command known to call it, unless a current image exists. *Rationale:* `IJ.getImage()` calls `System.exit(0)` when there is no image and no IJ1 frame (§5).
5. **CR-A13-05**: Plugin discovery in the new UI shall use the existing sources (IJ1 `Menus` command table, SciJava `ModuleService`, `ScriptService`) and shall not maintain a separate plugin registry. *Rationale:* single source of truth; supports update-site plugins without changes (§4).
6. **CR-A13-06**: The application shall not modify the Fiji installation except through the ImageJ Updater, and shall disable or surface the Updater's automatic check (`-Dimagej.updater.disableAutocheck=true` or explicit UI). *Rationale:* the Updater writes into the installation and runs when a UI is shown (§6).

## Risks

| ID | Risk | Likelihood | Impact | Mitigation idea |
|---|---|---|---|---|
| R-A13-01 | Users rely on C5 plugins (Weka, Cell Counter) that cannot be integrated beyond the fallback | High | High | Make the fallback first-class: window tracking, result capture, clear hand-off |
| R-A13-02 | `HeadlessGenericDialog` gaps cause silent failures (no output file) when running headless | High | High | Run IJ1 commands in a GUI-capable JVM by default; check output side effects after each command |
| R-A13-03 | Third-party update sites change behaviour after compatibility was classified | Medium | Medium | Re-run classification when the installation's `db.xml.gz` checksum changes |
| R-A13-04 | A plugin calls `System.exit` (e.g. through `IJ.getImage()`) and terminates the shared JVM | Low–Medium | High | GUI-capable JVM (`ij != null` → `abort()` instead); session supervisor that restarts the JVM and restores state |

## References

- Source: `imagej/ImageJ@v1.54p` (`ij/Menus.java`, `ij/IJ.java`, `ij/Macro.java`, `ij/WindowManager.java`, `ij/plugin/filter/*.java`, `ij/plugin/frame/RoiManager.java`, `ij/measure/ResultsTable.java`); `imagej/imagej-legacy@imagej-legacy-3.0.0` (`LegacyService.java`, `plugin/*Preprocessor.java`); `imagej/ij1-patcher@ij1-patcher-2.0.0` (`LegacyInjector.java`, `LegacyExtensions.java`, `LegacyHooks.java`); `scijava/scijava-common@scijava-common-2.101.0`; `imagej/imagej-updater@imagej-updater-2.0.3`; `fiji/fiji@ddc9a45d`.
- Installed: `Fiji/db.xml.gz`; `Fiji/jars/*/META-INF/json/org.scijava.plugin.Plugin`; `plugins.config` files (P48).
- Probes: `probes/P41-headless-subsystems.out.txt`, `probes/P42-gui-jvm-hidden-windows.out.txt`, `probes/P44-plugin-gui-reference-scan.out.txt`, `probes/P45-class-hierarchy-javap.out.txt`, `probes/P46-headless-followups.out.txt`, `probes/P46-stdout-stderr.txt`, `probes/P47-ome-tiff-gui-jvm.out.txt`, `probes/P48-plugin-index-inventory.out.txt`, `probes/P49-alternative-ui-registration.out.txt`.
- [Update Sites](https://imagej.net/update-sites/) (accessed 2026-10-17).

## Change log

- 2026-10-17: Initial draft (agent G3).
- 2026-10-17: Phase 8 note. Mentions here of a hidden `RoiManager(false)` describe observed behaviour (VERIFIED). The product design does **not** use such managers as its ROI store, because they are never the singleton that commands and macros see. The design uses one hidden singleton with load and read-back (D04 §4). The boolean argument is ignored (`RoiManager.java#L105-L112`).
