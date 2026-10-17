---
id: A15
title: GUI Dependence and Reuse Boundaries
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p, imagej/imagej-legacy@imagej-legacy-3.0.0, imagej/ij1-patcher@ij1-patcher-2.0.0, scijava/scijava-common@scijava-common-2.101.0, imagej/imagej-common@imagej-common-2.1.2, imagej/pyimagej@8f9b8faf, ome/bioformats@v8.5.0]
related_requirements: []
related_decisions: []
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A15 — GUI Dependence and Reuse Boundaries

## Executive Summary

This document determines which parts of Fiji a modern UI can drive, which must stay as native Swing/AWT, and which require code running inside the Fiji JVM. All results come from the pinned runtime: P41 headless, P42 non-headless with no UI shown, P46/P47 follow-ups and P49 alternative UI. Each claim is tied to source lines at the pin.

Main conclusions:

1. **Headless is not the target mode for an interactive workstation.** In a headless JVM the following fail (P41):
   - every `java.awt.Frame`/`Dialog`/`Canvas` (`ImageWindow`, `RoiManager`, `Recorder`, `ContrastAdjuster`, `ThresholdAdjuster`) throws `HeadlessException`;
   - `Toolbar.getInstance()` is `null` and `IJ.setTool` throws an NPE;
   - macro `roiManager(...)` aborts;
   - `NonBlockingGenericDialog` throws a `VerifyError`;
   - `GenericDialog` without macro options throws, and with macro options it works only for the methods that `HeadlessGenericDialog` implements. The Bio-Formats Exporter fails with an NPE (P46).
   - `IJ.getImage()` with no image calls `System.exit(0)` (`ij/IJ.java#L1806-L1816`).
2. **A GUI-capable JVM in which the Fiji main window is never shown works for everything tested** (P42). The cases are macro-option dialogs (including non-blocking ones), hidden `RoiManager(false)`, batch-mode macro ROI Manager, hidden `Recorder(false)`, `IJ.run` on images whose windows are hidden, and OME-TIFF export (P47). **It does not hide windows automatically.** The legacy layer always creates a hidden `ij.ImageJ` frame, so `LegacyService.isLegacyMode()` is true and patched `ImageWindow.show()` displays windows. `imp.show()` gave a visible `ImageWindow`, and `new RoiManager()` and `ResultsTable.show("Results")` gave visible windows. Hiding needs explicit policy: batch mode, `setVisible(false)`, or the hidden constructors.
3. **Reuse boundaries (PROPOSED, based on 1–2).**
   - *Drive programmatically from a separate UI:* opening and saving, IJ1 commands through `IJ.run(imp, cmd, options)`, IJ2 commands and ops with resolved inputs, measurements and `ResultsTable` data, ROI and `Overlay` objects, macro and script execution, status and progress events.
   - *Keep as native Swing/AWT (legacy-window fallback):* C4–C6 plugins ([A13](A13-plugin-architecture-compatibility.md) §7), especially anything that listens to `ImageCanvas`/`ImageWindow`/Toolbar events, and `NonBlockingGenericDialog` interactions with no macro equivalent.
   - *Requires a JVM-resident component:* anything that touches IJ1 static singletons, per-thread macro options and temp images, IJ1 listeners (`ImageListener`, `RoiListener`, `CommandListener`), SciJava events, input harvesting, the `UserInterface` plugin, and in-memory pixel access.

The matrix in §9 summarises behaviour per subsystem and execution mode.

## 1. Purpose and Scope

For each Fiji subsystem this document establishes whether it works without a GUI, under which mechanism, on which threads, and what that means for a modern UI that must preserve "the underlying scientific functionality, macro language, plugin compatibility and familiar interaction semantics" (project brief). It builds on [A07](A07-fiji-imagej-architecture.md) (architecture, patching) and [A13](A13-plugin-architecture-compatibility.md) (plugin categories C1–C6). It does not choose the final architecture (see §11, Decisions Required).

## 2. Sources and Evidence

| Probe | Mode | What it established | File |
|---|---|---|---|
| P41 | Headless (`--headless --run`) | 47 checks across all subsystems | `probes/P41-headless-subsystems.out.txt` |
| P42 | Non-headless, `new net.imagej.ImageJ()`, no `showUI()` | Same checks; window visibility | `probes/P42-gui-jvm-hidden-windows.out.txt` |
| P46 | Headless | `roiManager` error, Exporter NPE, thread state | `probes/P46-headless-followups.out.txt`, `P46-stdout-stderr.txt` |
| P47 | Non-headless, no UI | OME-TIFF export works | `probes/P47-ome-tiff-gui-jvm.out.txt` |
| P49 | Non-headless, Swing SDI UI | Alternative UI plus runtime UI registration | `probes/P49-alternative-ui-registration.out.txt` |

Commands and pin checks are in `probes/README-G3.md`. The pin hash matched after every run. "Visible" in P42 means `Component.isVisible()` and `isShowing()` returned true and windows appeared briefly on the desktop. Only Windows 11 was probed: macOS and Linux behaviour is **INFERRED** from source and documentation.

## 3. Execution modes

| Mode | Definition | How it arises |
|---|---|---|
| **M1 Headless** | `java.awt.headless=true`; `GraphicsEnvironment.isHeadless()` is true; `LegacyHeadless` patches applied; `IJ.getInstance()==null` | Jaunch `--headless` → `-Djava.awt.headless=true -Dapple.awt.UIElement=true` (P40); PyImageJ `mode="headless"` adds the same option (`imagej/pyimagej@8f9b8faf:src/imagej/__init__.py#L1531-L1532`) |
| **M2 GUI-JVM, hidden** | Non-headless JVM; SciJava context and legacy layer started; main window never shown; `IJ.getInstance()` is a hidden `ij.ImageJ` (P42 B3) | Embedding (`new net.imagej.ImageJ()` without `ui().showUI()`); PyImageJ `mode="interactive"` before `ij.ui().showUI()` |
| **M3 GUI-JVM, visible** | Normal Fiji: `LegacyUI` (or another UI) shown | `fiji-windows-x64.exe` without `--headless`; PyImageJ `mode="gui"`, or interactive followed by `showUI()` |
| **M4 PyImageJ headless** | M1 inside a JPype-hosted JVM in the Python process | `imagej.init(…, mode="headless")` (default) |
| **M5 PyImageJ interactive** | M2/M3 inside a JPype JVM; on macOS only under certain conditions (§10) | `imagej.init(…, mode="interactive")` |

## 4. Mechanisms

### 4.1 AWT headless

In a headless JVM, constructing any heavyweight AWT top-level or `Canvas` throws `java.awt.HeadlessException`. P41 observed this for `new ImageWindow(imp)` (E5), `new RoiManager(false)` and `new RoiManager()` (G1, G2), `new Recorder(false)` (H3), `new ContrastAdjuster()` (H4) and `new ThresholdAdjuster()` (H5). The class hierarchy in P45 explains why: `ImageWindow`/`TextWindow`/`PlugInFrame extends java.awt.Frame`, `GenericDialog`/`PlugInDialog extends java.awt.Dialog`, and `ImageCanvas`/`Toolbar extends java.awt.Canvas`. **VERIFIED**

### 4.2 ij1-patcher headless patches and `HeadlessGenericDialog`

Applied only when headless (`LegacyInjector.java#L161-L162`, `LegacyHeadless.java#L77-L128`):

- `ij.gui.GenericDialog` gets the superclass `net.imagej.patcher.HeadlessGenericDialog`, its AWT methods are stubbed, and AWT instantiations are skipped (P41 B7 confirms the superclass).
- `HeadlessGenericDialog()` throws `RuntimeException("Cannot instantiate headless dialog except in macro mode")` when `Macro.getOptions()==null` (`HeadlessGenericDialog.java#L92-L94`; P41 D1). `showDialog()` throws `"Cannot run dialog headlessly"` under the same condition (L227–L229). `wasCanceled()` is always `false` (L253–L255).
- Values come from macro options. Numbers, strings and choices fall back to the default when the key is absent. Booleans are true only if the key is present (`findBooleanMacroParameter`, L325) (P41 D2: `sigma=3.5 mode=b flag` → `3.5, b, true`).
- **Gaps:**
  - `addFileField`, `addDirectoryField` and `addImageChoice` are no-ops, and `getNextImage()` returns `null` (L283–L288);
  - `getCheckboxes()`, `getChoices()`, `getNumericFields()`, `getStringFields()` and `getSliders()` return `null` (L290–L299).

  Any plugin that calls these fails headless. The Bio-Formats Exporter failed with `NullPointerException … "checkboxes" is null` (P46). A plugin that uses `addFileField` followed by `getNextString()` would read a shifted field. That case is **INFERRED** from the no-op and not probed.
- `NonBlockingGenericDialog` (`extends GenericDialog`) fails headless with `java.lang.VerifyError: … 'ij/gui/NonBlockingGenericDialog' … is not assignable to 'java/awt/Window'` (P41 D3). It works in M2 (P42 E2). **VERIFIED**
- Batch mode is forced: `Interpreter.batchMode = true` is set in `IJ`'s static initializer, `isBatchMode()` always returns true, and every reset is overridden (`LegacyHeadless.java#L103-L127`; P41 B6). **VERIFIED**

### 4.3 Batch mode (all modes)

`ImagePlus.show()` does not create a window `if ((IJ.isMacro() && ij==null) || Interpreter.isBatchMode())`. It sets the image as the thread's temp current image and appends it to the interpreter's batch list (`ij/ImagePlus.java#L496-L503`). `WindowManager.getCurrentImage()` falls back to `Interpreter.getLastBatchModeImage()` (`ij/WindowManager.java#L99-L113`). P41 E1–E4 (headless) showed `getWindow()==null`, the current image tracked and `getIDList()=[-2]`. P42 D1 (M2) showed `Interpreter.batchMode=true` → no window, current image tracked. `StackWindow` creation is suppressed in batch mode (`LegacyInjector.java#L322-L328`). In a macro, `RoiManager.getInstance()` returns a hidden batch-mode manager (`RoiManager.java#L2081-L2086`). In M2 this worked: `roiManager("count")` returned 1 (P42 F3). **VERIFIED**

### 4.4 "Legacy mode" gating of IJ1 windows

The patched `ImageWindow.show()/setVisible(true)` and `RoiManager.show()/setVisible()` call `super` only `if (ij.IJ._hooks.isLegacyMode())` (`LegacyInjector.java#L229-L240`, `#L300-L306`). With imagej-legacy that is `IJ.getInstance() != null` ([A07](A07-fiji-imagej-architecture.md) §5.7). In M2 the hidden `ij.ImageJ(NO_SHOW)` exists (P42 B3), so windows **are** shown (P42 C1, F2, G1). The suppression branch applies only to a JVM without that frame, which does not occur with a normal `LegacyService` start. **VERIFIED**

### 4.5 Other GUI checks in IJ1

- `ResultsTable.show()` returns at once when headless (`ij/measure/ResultsTable.java#L1065-L1067`; P41 F2/F3 returned normally, F4 `IJ.getTextPanel()` was `null`). **VERIFIED**
- `IJ.recording()` is `!GraphicsEnvironment.isHeadless() && Recorder.record` (`ij/IJ.java#L2578-L2580`). **VERIFIED**
- `IJ.getImage()` with no current image calls `IJ.noImage()` and then `System.exit(0)` if `ij == null`, otherwise `abort()` (`ij/IJ.java#L1806-L1816`). In M1 this terminates the JVM. In M2/M3 `ij` is the hidden or visible frame, so it aborts instead. **VERIFIED** (source); runtime exit **INFERRED** (not probed, to avoid killing a probe JVM).
- `GenericDialog` in non-headless macro mode is still a real `java.awt.Dialog`. `showDialog()` disposes it without showing it when `macro` is true (`ij/gui/GenericDialog.java#L120-L121`, `#L1506-L1510`; P42 E1 `visible=false`). **VERIFIED**

## 5. Subsystem classification

Classes: **I** = GUI-independent; **C** = conditionally independent (works without visible GUI under the stated conditions); **B** = GUI-bound (needs real windows and user input).

| Subsystem | Class | Mechanism and conditions | Evidence |
|---|---|---|---|
| Image data (`ImagePlus`, `ImageStack`, `ImageProcessor`, `Calibration`) | I | Plain Java objects; `IJ.createImage`, `IJ.openImage`, processing on `ImageProcessor` | P41 E6–E8; P43 |
| Image display registry (`ImagePlus.show`, `WindowManager`) | C | M1: batch list only (`getWindow()==null`, current image via temp/batch list). M2: windows created and shown unless batch mode or explicitly hidden; hidden windows stay registered and current (P42 C3) | P41 E1–E4; P42 C1–C3, D1; §4.3–4.4 |
| Active image per thread | C | `WindowManager.setTempCurrentImage` is per thread. `IJ.run(imp, …)` sets it. A macro run on the same thread can change it (P41: after `IJ.runMacro` the active display was the macro's image "m", I3) | `ij/WindowManager.java#L69-L97`; P41 I3, I6 |
| ROI objects (`Roi`, `Overlay`) | I | Pure data; overlays persist in TIFF | P41 G5; P43 O1 |
| ROI Manager (`ij.plugin.frame.RoiManager`) | C (M2/M3) / B (M1) | `extends PlugInFrame` (`Frame`). M1: `HeadlessException`; macro `roiManager` aborts. M2: `new RoiManager(false)` is hidden and fully usable (`addRoi`, `getCount`), but not registered as the singleton (`getInstance()` = null). The boolean argument is ignored, so `RoiManager(true)` is equivalent (`RoiManager.java#L105-L112`; lead note, Phase 8); `new RoiManager()` is visible; batch-mode macro manager works | P41 G1–G4; P46 a1; P42 F1–F3; `RoiManager.java#L87-L112` |
| Results data (system `ResultsTable`) | I | Static `Analyzer.systemRT`; `Measure` works headless | P41 F1; P42 C4 |
| Results display (`TextWindow`, named tables) | C / B | M1: `show()` is a no-op, named tables cannot be found (`getResultsTable(title)` looks up a `Frame`). M2: `show("Results")` opens a **visible** `TextWindow` | P41 F2–F4; P42 G1; `ResultsTable.java#L103-L120` |
| Recorder (`ij.plugin.frame.Recorder`) | C (M2/M3) / B (M1) | `extends PlugInFrame`. M1: `HeadlessException`, `IJ.recording()` false. M2: `new Recorder(false)` hidden, `getText()` readable; `IJ.run` from non-macro code was **not** recorded until `Recorder.recordInMacros = true`, after which `run("Invert");` appeared | P41 H1–H3; P42 H1; `Recorder.java#L49-L57` |
| Command observation (`Executer.addCommandListener`) | I (INFERRED) | `CommandListener.commandExecuting(String)` is called in `Executer.run()` before every command (`ij/Executer.java#L54-L61`, `#L265`) with no AWT dependency | source only |
| `GenericDialog` | C | M1: needs macro options and stays within `HeadlessGenericDialog` coverage (§4.2). M2/M3: macro options on a `Run$_` thread give a disposed, invisible dialog; without options the dialog is modal and blocks for the user | P41 D1–D3; P42 E1–E2 |
| `NonBlockingGenericDialog` | C (M2/M3) / B (M1) | `VerifyError` headless; works with macro options in M2 | P41 D3; P42 E2 |
| `ImageWindow` / `StackWindow` / `ImageCanvas` | B (interaction); C (existence) | M1: cannot exist. M2: exist even when hidden (P42 C5 `ImageCanvas` mag=1.0). User interaction (mouse, key, tools) needs a visible window | P41 E5; P42 C1–C5 |
| Toolbar (`ij.gui.Toolbar`) | B | `extends Canvas`; M1: `getInstance()` null, `IJ.setTool` NPE. M2: instance exists on the hidden main frame (P42 B7) | P41 H6–H7; P42 B7 |
| B&C / Threshold dialogs (`ContrastAdjuster`, `ThresholdAdjuster`) | B (UI); I (operations) | `extends PlugInDialog`; `HeadlessException` in M1. P42 H2: `ContrastAdjuster` was not visible 300 ms after construction (cause not determined). The operations behind them (`setMinAndMax`, `setAutoThreshold`, `Convert to Mask`) are independent | P41 H4–H5, E8; P42 H2 |
| Plugin frames (C4–C6) | B | Own `Frame`/`JFrame`/Java 3D windows | [A13](A13-plugin-architecture-compatibility.md) §7; P44/P45 |
| Macro interpreter (`ij.macro.Interpreter`) | C | Runs in M1 (batch mode forced) and M2. Window-oriented functions (`selectWindow`, `roiManager` headless, tools) fail in M1; [Running Headless](https://imagej.net/learn/headless) (accessed 2026-10-17) documents that "Some commands relying on the GUI do not work in headless mode" | P41 G4; P42 F3 |
| IJ2 commands (`CommandService`/`ModuleService`) | I if inputs resolved; C otherwise | Preprocessors fill services, the active image (`ActiveImagePlusPreprocessor`, `ActiveImagePreprocessor`) and macro options. **Input harvesting happens only if the harvester's UI is visible** (`AbstractInputHarvesterPlugin.java#L66-L71`) | P41 I6; [A07](A07-fiji-imagej-architecture.md) §7.2 |
| Ops (`OpService`) | I | — | P41 I5 |
| IO: IJ1 readers/writers, Bio-Formats reader, SCIFIO | I | — | P43 |
| IO: Bio-Formats Exporter | C (M2/M3) | Fails in M1 (P46 b1), works in M2 (P47 S2) | P46, P47 |
| IO: file choosers (`OpenDialog`, `SaveDialog`, `JFileChooser`) | B | Need a display. Avoid by always passing paths | `LegacyUI.chooseFile` uses `threadService.invoke` with IJ1 dialogs (`legacy/ui/LegacyUI.java#L270-L311`) |
| Status / progress / log | I | Patched `IJ.showStatus/showProgress/log` forward to `IJ._hooks` (`LegacyInjector.java#L177-L198`). Only `log` is forwarded on to SciJava by `DefaultLegacyHooks`; progress and status need a chained hook (A20; lead correction, Phase 8). | source |
| Updater auto-check | C | Runs on the first `UIShownEvent` of a non-headless UI (P49 log) | `DefaultUpdateService.java#L113-L125` |

## 6. Threading

- **AWT EDT.** `ThreadService.invoke` runs code on the EDT through `EventQueue.invokeAndWait`, or inline if already on it. `queue` uses `invokeLater` (`scijava-common@…:src/main/java/org/scijava/thread/DefaultThreadService.java#L109-L125`). This reached the EDT even in M1 (P41 I8) and in M2 (P42 J1). `DefaultUIService.showUI` dispatches on the EDT when `ui.requiresEDT()` (`DefaultUIService.java#L163-L190`). `LegacyUI.show(display)` and `chooseFile` use `threadService.invoke` (`LegacyUI.java#L189-L197`, `#L274-L302`). **VERIFIED**
- **`IJ.run` threading.** `IJ.run(command, options)` sets `Macro.setOptions(options)` **for the current thread**, prefixes the thread name with `Run$_`, and runs the `Executer` **synchronously in the calling thread** (`ij/IJ.java#L310-L332`). `IJ.doCommand` and menu clicks (`ImageJ.actionPerformed` → `new Executer(cmd, imp)`) start a new thread instead (`ij/IJ.java#L286-L294`, `ij/ImageJ.java#L383`, `ij/Executer.java#L36-L52`). `Macro.getOptions()` returns options only on threads whose name starts with `Run$_` or `RMI TCP` (`ij/Macro.java#L86-L94`). **VERIFIED**
- **Implication for a driver.** Every IJ1 command for one image should run on a dedicated worker thread through `IJ.run(imp, cmd, options)`. That keeps the temp current image and macro options thread-confined. It must not run on the EDT, because `ImagePlus.waitTillActivated()` returns immediately on the EDT (`ImagePlus.java#L564-L579`) and modal dialogs would block the UI. **INFERRED**
- **IJ2 module threads.** `ModuleService.run` submits to `ThreadService` (`DefaultModuleService.java#L244`). The threads are named `SciJava-<hash>-Thread-<n>` (P41 A4, I6, I7) and are therefore **not** `Run$_` threads: IJ1 macro options set elsewhere are invisible there. `MacroPreprocessor` only reads them `if (ij1Helper.isMacro())`. **VERIFIED**
- **Waiting on windows.** For non-stack images in macro mode, `ImagePlus.show()` waits up to 2 s for window activation (`ImagePlus.java#L521-L522`, `#L570-L577`). This latency applies whenever windows are shown during macro execution in M2/M3. **VERIFIED** (source)
- **Shared global state.** `WindowManager`, `RoiManager.instance`, `Analyzer.systemRT`, `Recorder.record` and `Interpreter.batchMode` are JVM-wide statics, so concurrent workflows interfere. **INFERRED** from §5.

## 7. "GUI-supported but hidden" execution

Question: can a persistent non-headless JVM run Fiji with its windows hidden or managed, and do plugins that need an `ImageWindow` still work?

1. **Starting hidden works.** `new net.imagej.ImageJ()` without `showUI()` gives an active `LegacyService`, a hidden `ij.ImageJ` main frame, a `Toolbar` instance, and `LegacyUI` as default UI with `isVisible()=false` (P42 B1–B7, 1.5 s context start-up). **VERIFIED**
2. **Windows are not hidden by default.** `imp.show()` → visible `ImageWindow`; `new RoiManager()` → visible; `ResultsTable.show("Results")` → visible `TextWindow` (P42 C1, F2, G1). **VERIFIED**
3. **Available hiding mechanisms** (all **VERIFIED**):

   | Mechanism | What stays functional | Evidence |
   |---|---|---|
   | Batch mode (`Interpreter.batchMode = true`, macro `setBatchMode(true)`) | No window; current image tracked; batch ROI Manager in macros | P42 D1, F3 |
   | `ImageWindow.setVisible(false)` after `show()` | Window, canvas and registration kept; `IJ.run` + ROI + `Measure` work (Area = 600 for a 30×20 ROI) | P42 C3–C5 |
   | `new RoiManager(false)` | Full API; not the singleton | P42 F1 |
   | `new Recorder(false)` | `getText()`; record macros with `recordInMacros` | P42 H1 |
   | Macro options for dialogs | `GenericDialog` and `NonBlockingGenericDialog` never shown | P42 E1–E2 |

4. **Plugins that need an `ImageWindow`.**
   - Code paths that only need the window to *exist*, for example to read `imp.getWindow()`, `getCanvas().getMagnification()`, or to add an overlay, work with a hidden window. This is **INFERRED** from P42 C5 and is not probed per plugin.
   - Plugins that need *user interaction* on the canvas cannot be used hidden: C5 (Weka `CustomWindow extends StackWindow`, Cell Counter, Sync Windows, the tools). Nobody can click a hidden window. **INFERRED** (this follows from what interaction requires).
   - Plugins that open their own frames (C4/C6) show them regardless, because the patch guards only `ImageWindow` and `RoiManager`. **VERIFIED** for `TextWindow` (P42 G1); **INFERRED** for third-party frames.
   - Moving IJ1 windows off-screen instead of hiding them would keep them "showing" for code that checks `isShowing()`, but it is fragile with multiple monitors. **INFERRED**; not probed.
5. **Xvfb (Linux).** PyImageJ documents running `interactive` mode under Xvfb so GUI-bound features work without a screen ([py.imagej.net Headless](https://py.imagej.net/en/latest/Headless.html), accessed 2026-10-17; `pyimagej@8f9b8faf:doc/Headless.md`). The ImageJ wiki notes that under Xvfb "plugins might get stuck because they wait for user input which never comes" ([Running Headless](https://imagej.net/learn/headless)). This is VERIFIED as documentation; it was not probed on this Windows host.

## 8. Reuse boundaries for a modern UI

### 8.1 Can be driven programmatically from a separate UI (through a JVM-side adapter)

| Capability | Driving API | Mode | Evidence |
|---|---|---|---|
| Open/save images | `IJ.openImage`, `IJ.saveAsTiff`, `IJ.saveAs`, `BF.openImagePlus`, `DatasetIOService` | M1–M3 (OME-TIFF export M2/M3) | P43, P47 |
| IJ1 menu commands with dialogs | `IJ.run(imp, "<Command>", "<macro options>")` on a worker thread | M2/M3 (M1 only within `HeadlessGenericDialog` coverage) | P41 E6–E8, D2; P42 C4, E1 |
| IJ2 commands, scripts, ops | `CommandService.run(cls, true, inputs)`, `ScriptService.run`, `OpService.run` with all inputs supplied | M1–M3 | P41 I5–I6 |
| Macro language | `IJ.runMacro`, `ScriptService` with `.ijm` (IJ1MacroEngine), `-macro` | M1 (batch), M2/M3 | P41 G4; P42 F3 |
| Measurements / Results data | `IJ.run(imp, "Set Measurements...", …)`, `"Measure"`, `ResultsTable.getResultsTable()` | M1–M3 | P41 F1; P42 C4 |
| ROIs | `imp.setRoi`, `Overlay`, hidden `RoiManager(false)` (M2/M3) | M1 (no manager) – M3 | P41 G5; P42 F1 |
| Display state (LUT, display range, slice/channel position) | `ImagePlus`/`CompositeImage` setters | M1–M3 | INFERRED (API; not probed) |
| Recording equivalent | `Executer.addCommandListener` plus macro-option capture by the adapter; or hidden `Recorder(false)` with `recordInMacros` (M2/M3) | — | P42 H1; source |
| Pixels for display in a non-Java viewer | `ImagePlus.getProcessor().getPixels()` / ImgLib2 views ([A07](A07-fiji-imagej-architecture.md) §5.5) | M1–M3 | P41 I1–I2 |
| Status / progress / log | Log: SciJava `LogService`. Progress and status: a chained `LegacyHooks` (validation V-06; A20) | M1–M3 | source |

### 8.2 Must remain native Swing/AWT (legacy-window fallback)

- C4 plugin frames: TrakEM2, TrackMate wizard, BigDataViewer, Labkit, Cell Counter panel, Volume Viewer.
- C5 canvas-interactive plugins and IJ1 tools: Trainable Weka, Cell Counter, Sync Windows, Arrow, Lasso/Blow, drawing tools and other Toolbar tools.
- C6 3D rendering: 3D Viewer (Java 3D/JOGL natives).
- IJ1 interactive dialogs used without macro options, e.g. a live preview of an `ExtendedPlugInFilter`, B&C/Threshold adjusters used interactively, `NonBlockingGenericDialog` prompts such as "wait for user". The new UI can offer native equivalents for core operations by calling the same commands (`setMinAndMax`, `setThreshold`), but third-party dialogs remain native.
- The Script Editor and Updater UI, if they are exposed.

Evidence: [A13](A13-plugin-architecture-compatibility.md) §7 (P44/P45). Fallback windows require M2/M3.

### 8.3 Requires a JVM-resident component

Required because the API is in-process Java static state, per-thread state, or a callback:

- per-thread command execution with macro options and temp current image (§6);
- IJ1 listeners (`ImagePlus.addImageListener` `ij/ImagePlus.java#L3072`, `Roi.addRoiListener` `ij/gui/Roi.java#L2920`, `Executer.addCommandListener` `ij/Executer.java#L265`) and SciJava `EventService` subscriptions;
- a `UserInterface` plugin or `UIService.addUI` registration (P49) plus input harvester or widgets for IJ2 modules;
- window policy (batch mode, hiding, tracking fallback windows) and the patch-order rule ([A07](A07-fiji-imagej-architecture.md) CR-A07-02);
- zero-copy pixel access and `Dataset` views.

An out-of-process UI (Electron/web/Python) can sit on top of such a component through IPC. Per-workflow headless processes, as in the prototype, cannot provide persistent state, ROI Manager, Recorder or fallback windows. **INFERRED** from §5–§7.

## 9. Behaviour matrix

Legend: ✔ works (VERIFIED by the cited probe); ✔* works under the stated condition; ✖ fails (VERIFIED); ~ INFERRED; ? UNKNOWN. M4/M5 entries are INFERRED from M1/M2 plus PyImageJ source (same JVM mechanisms via JPype), except where noted.

| Subsystem | M1 Headless | M2 GUI-JVM hidden | M3 GUI-JVM visible | M4 PyImageJ headless | M5 PyImageJ interactive |
|---|---|---|---|---|---|
| Image data, IJ1 processing via `IJ.run(imp,…)` | ✔ P41 E6–E8 | ✔ P42 C4 | ~ (standard Fiji) | ~ as M1 | ~ as M2 |
| `ImagePlus.show` / WindowManager | ✔* batch list only, `getWindow()==null` P41 E1–E4 | ✔ visible window unless batch/hidden P42 C1–C3, D1 | ~ | ~ as M1 (`WindowManager.getCurrentWindow()` null, documented) | ~ as M2 (`ij.py.sync_image` may be needed, documented) |
| `IJ.getImage()` with no image | ✖ `System.exit(0)` (source) | ✔* `abort()` (source) | ✔* abort | ✖ ~ terminates the Python process's JVM | ✔* ~ abort |
| ROI / Overlay objects | ✔ P41 G5 | ~ | ~ | ~ | ~ |
| ROI Manager | ✖ P41 G1–G2, P46 a1 | ✔* hidden `RoiManager(false)`, batch macro P42 F1, F3; `new RoiManager()` visible P42 F2 | ~ | ✖ (documented: "a `java.awt.Frame` … cannot be used headless") | ✔* ~ as M2 |
| Results data | ✔ P41 F1 | ✔ P42 C4 | ~ | ~ ✔ | ~ ✔ |
| Results window / named tables | ✖ no-op, no lookup P41 F2–F4 | ✔ visible `TextWindow` P42 G1 | ~ | ~ ✖ | ~ visible |
| Recorder | ✖ P41 H1–H3 | ✔* hidden, needs `recordInMacros` for `IJ.run` P42 H1 | ~ | ~ ✖ | ~ as M2 |
| `GenericDialog` + macro options | ✔* within coverage P41 D2; ✖ without options P41 D1 | ✔ invisible P42 E1 | ~ | ~ as M1 | ~ as M2 |
| `GenericDialog` without options | ✖ throws | ~ modal dialog blocks until user responds (window shown) | ~ | ~ ✖ | ~ blocks |
| `NonBlockingGenericDialog` + options | ✖ `VerifyError` P41 D3 | ✔ P42 E2 | ~ | ~ ✖ | ~ ✔ |
| `ImageWindow` / `ImageCanvas` | ✖ P41 E5 | ✔* exist (hidden or visible) P42 C1, C5; interaction only if visible | ~ ✔ | ~ ✖ | ~ as M2 |
| Toolbar / tools | ✖ P41 H6–H7 | ✔* instance on hidden frame P42 B7; usable only if visible | ~ | ~ ✖ | ~ as M2 |
| B&C / Threshold dialogs | ✖ P41 H4–H5 | ? P42 H2 not visible after 300 ms | ~ | ~ ✖ | ? |
| Plugin frames C4–C6 | ✖ ~ (`HeadlessException` class) | ~ visible frames | ~ | ~ ✖ | ~ visible frames; macOS restrictions §10 |
| Macro interpreter | ✔* batch forced; GUI functions fail P41 G4 | ✔ P42 F3 | ~ | ✔ ~ (`ij.py.run_macro` via ScriptService) | ✔ ~ |
| IJ2 commands with resolved inputs | ✔ P41 I6 | ~ ✔ | ~ ✔ | ~ ✔ | ~ ✔ |
| IJ2 commands needing harvesting | ✖ ~ (no visible UI → no harvest) | ✖ ~ unless a visible UI's harvester (§5) | ✔ ~ | ✖ ~ | ✔* after `showUI()` ~ |
| Ops | ✔ P41 I5 | ~ ✔ | ~ ✔ | ~ ✔ | ~ ✔ |
| IO read (IJ1, Bio-Formats, SCIFIO) | ✔ P43 | ✔ P47 | ~ | ~ ✔ | ~ ✔ |
| Bio-Formats Exporter (OME-TIFF) | ✖ P46 b1 | ✔ P47 S2 | ~ ✔ | ~ ✖ | ~ ✔ |
| ImagePlus ↔ Dataset (on demand) | ✔ P41 I1–I3 | ~ ✔ | ~ ✔ | ✔ ~ (`ij.py.to_dataset`, `active_imageplus`) | ~ ✔ |
| Process exit after work | ? P41 hung / P46 exited | ? P42 hung after `System.exit` | ~ quit via IJ1 exits JVM | ~ JVM lives with Python | ~ "JVM and Python will both terminate when ImageJ closes" (documented) |

## 10. PyImageJ review

- **Modes** (`imagej/pyimagej@8f9b8faf:src/imagej/__init__.py#L70-L78`, `init` L1273–L1449):
  - `headless` (default) adds `-Djava.awt.headless=true` (L1531–L1532).
  - `gui` creates the gateway, calls `ui().showUI()` and blocks: on macOS through `jpype.setupGuiEnvironment`, which needs PyObjC, otherwise by polling `ui().isVisible()` every second (L1404–L1446).
  - `interactive` returns the gateway without showing the UI (L1448–L1449).
  - `interactive:force` strips `:force` and bypasses the macOS check (L1349–L1351, L1362–L1392).

  **VERIFIED** (source).
- **macOS restriction.** Interactive mode needs the CoreFoundation/AppKit event loop on the main thread. `_macos_enable_interactive` succeeds under IPython (`%gui osx`) or when not on the main thread (`pthread_main_np() != 1`), and otherwise raises `OSError` unless forced (L1745–L1795). The init docstring says it "will not work from plain Python, but it works in IPython/Jupyter, as well as when launching Python via the Jaunch launcher". Jaunch's Fiji config has a Python launch mode with `pyimagej>=1.4.1` (`config/jaunch/fiji.toml` `python.packages`; `config/fiji.py`). **VERIFIED** (source and config).
- **Documented limits.**
  - The ROI Manager is "a `java.awt.Frame`, and therefore cannot be used headless".
  - `WindowManager.getCurrentWindow()` always returns null headless.
  - In GUI or interactive mode `ij.py.sync_image(imp)` may be needed, because "an ImagePlus can sometimes be modified without modifying the linked Dataset/ImageDisplay" (`doc/Troubleshooting.md` §Known Limitations; `src/imagej/__init__.py#L536-L562`; [py.imagej.net Headless](https://py.imagej.net/en/latest/Headless.html), accessed 2026-10-17).
  - With legacy enabled in GUI or interactive mode, "the JVM and Python will both terminate when ImageJ closes".

  These are VERIFIED as documentation and consistent with P41.
- **Entry points.** `ij.py.run_macro` → `ScriptService.run("macro.ijm", …)` (L235–L280); `ij.py.run_plugin` → `IJ.run(plugin, argline)` or `IJ.run(imp, plugin, argline)` (L282–L319); `ij.WindowManager`/`ij.RoiManager`/`ij.IJ` require an active legacy service (`_check_legacy_active`, L978). **VERIFIED**
- **Relevance.** PyImageJ M5 is a Python-hosted equivalent of M2 and inherits all of its window behaviour (§7). It adds the JPype in-process coupling: the JVM lives for the whole Python process and cannot be restarted. **INFERRED** (JPype semantics; not probed here).

## 11. Decisions Required

- **DEC candidate (lead):** Persistent-JVM topology for the modern UI. Options:
  - (a) JVM-resident adapter plus out-of-process modern UI over IPC;
  - (b) modern UI inside the JVM as a `UserInterface` plugin (Swing/JavaFX);
  - (c) PyImageJ-hosted adapter.

  All three need M2 to preserve plugin compatibility, with fallback windows. M1 per-workflow processes (the prototype) cannot meet §8.2/§8.3. This document does not choose between them.

## 12. Risks and Unknowns

| ID | Item | Tag | How to resolve |
|---|---|---|---|
| U-A15-1 | Why `ContrastAdjuster` was not visible in M2 (P42 H2) | UNKNOWN | Probe with a longer wait and `IJ.isMacro()` state logged |
| U-A15-2 | Why JVMs did not terminate (P41 headless, P42 after `System.exit`) | UNKNOWN | `jcmd <pid> Thread.print` during the hang; check shutdown hooks |
| U-A15-3 | M3 behaviour of `IJ.run` from a non-EDT worker while the user interacts with IJ1 windows (shared statics) | UNKNOWN | Concurrency probe in M3 |
| U-A15-4 | macOS/Linux window behaviour in M2 | UNKNOWN | Repeat P42 on macOS (Jaunch) and on Linux with or without Xvfb |
| U-A15-5 | Per-plugin behaviour of C4–C6 under M2 | UNKNOWN | Per-command harness ([A13](A13-plugin-architecture-compatibility.md) U-A13-2) |

## Requirement implications

1. **CR-A15-01**: The Fiji engine for the interactive workstation shall run in a persistent, non-headless JVM (M2) for the whole user session. *Rationale:* ROI Manager, Recorder, `NonBlockingGenericDialog`, the OME-TIFF exporter and plugin windows fail in M1 (§5, P41/P46) and work in M2 (P42/P47).
2. **CR-A15-02**: Every IJ1 command shall be executed with `IJ.run(ImagePlus, command, options)` on a dedicated non-EDT worker thread, with complete macro options, and the system shall never let an unparameterised modal `GenericDialog` block that worker without surfacing it to the user. *Rationale:* thread-confined macro options and temp image (§6); modal dialogs without options block (§4.5).
3. **CR-A15-03**: The system shall apply an explicit window policy: images under modern-UI control are kept in batch mode or hidden (`setVisible(false)`), and any IJ1 window that becomes visible (fallback plugins, `TextWindow`, `RoiManager()`) shall be detected and tracked by the UI. *Rationale:* windows are not hidden automatically in M2 (§7, P42 C1/F2/G1).
4. **CR-A15-04**: The system shall use a hidden `RoiManager(false)` (or an `Overlay`) as the ROI store under modern-UI control and shall keep it consistent with IJ1 `RoiManager.getInstance()` for commands that use the singleton. *Rationale:* `RoiManager(false)` is not registered as the singleton (P42 F1); C2r plugins use `getInstance()`.
5. **CR-A15-05**: Workflow history and macro export shall be generated from commands and options captured by the system itself (e.g. `CommandListener` plus the options passed to `IJ.run`). The IJ1 Recorder, if used, shall run hidden with `recordInMacros = true`, and its output shall be checked against the captured history. *Rationale:* the Recorder ignores `IJ.run` calls from scripts unless `recordInMacros` is set (P42 H1).
6. **CR-A15-06**: Plugins in categories C4–C6 shall run through a legacy-window fallback in which their native windows are shown, brought to the front and associated with the originating workflow step. *Rationale:* §8.2.
7. **CR-A15-07**: IJ2 modules launched from the new UI shall either have all inputs resolved by the UI before `ModuleService.run`, or have a UI-specific input harvester registered. *Rationale:* harvesting is tied to the visible UI (§5).
8. **CR-A15-08**: The system shall guard against JVM termination by plugins (`IJ.getImage()` without an image, `System.exit` in plugins) with a session supervisor that detects JVM loss and offers recovery from the last saved state. *Rationale:* §4.5; observed exit anomalies (§12).
9. **CR-A15-09**: Headless (M1) execution shall be offered only for batch jobs whose every step has a VERIFIED M1 classification, and shall check each step's expected side effects (output files, Results rows). *Rationale:* silent headless failures (P46 b1).

## Risks

| ID | Risk | Likelihood | Impact | Mitigation idea |
|---|---|---|---|---|
| R-A15-01 | IJ1 windows pop up unexpectedly over the modern UI (M2) | High | Medium | CR-A15-03 window tracker; batch mode by default |
| R-A15-02 | Shared IJ1 statics cause cross-talk between concurrent operations | Medium | High | Serialise IJ1 commands per session on one worker queue; per-thread temp image |
| R-A15-03 | Modal dialog from a plugin blocks the worker invisibly | Medium | High | Detect new `Dialog` windows (AWT event listener) and surface them; timeout warnings |
| R-A15-04 | Headless shortcuts are adopted for speed and break plugin compatibility | Medium | High | CR-A15-09 gating with per-command evidence |
| R-A15-05 | Plugin or `IJ.getImage()` terminates the JVM | Low–Medium | High | CR-A15-08 supervisor; always keep a current image or check before commands |
| R-A15-06 | macOS main-thread/AppKit constraints break M2 hosting (PyImageJ documents them) | Medium | High | Prefer the Jaunch-launched JVM as host on macOS; verify early (U-A15-4) |

## References

- Source (tags in front matter; SHAs in `docs/data/upstream-manifest.yaml`):
  - `imagej/ImageJ@v1.54p`: `ij/IJ.java`, `ij/Macro.java`, `ij/Executer.java`, `ij/ImagePlus.java`, `ij/WindowManager.java`, `ij/gui/GenericDialog.java`, `ij/gui/ImageWindow.java`, `ij/plugin/frame/RoiManager.java`, `ij/plugin/frame/Recorder.java`, `ij/measure/ResultsTable.java`;
  - `imagej/ij1-patcher@ij1-patcher-2.0.0`: `LegacyInjector.java`, `LegacyHeadless.java`, `HeadlessGenericDialog.java`;
  - `imagej/imagej-legacy@imagej-legacy-3.0.0`: `LegacyService.java`, `ui/LegacyUI.java`, `plugin/*Preprocessor.java`;
  - `scijava/scijava-common@scijava-common-2.101.0`: `thread/DefaultThreadService.java`, `ui/DefaultUIService.java`, `ui/AbstractInputHarvesterPlugin.java`, `module/DefaultModuleService.java`;
  - `imagej/pyimagej@8f9b8faf`: `src/imagej/__init__.py`, `doc/Headless.md`, `doc/Troubleshooting.md`.
- Probes: `probes/P40`–`P49` (see `probes/README-G3.md`).
- Docs: [Running Headless](https://imagej.net/learn/headless), [PyImageJ Headless](https://py.imagej.net/en/latest/Headless.html), [ImageJ Legacy](https://imagej.net/libs/imagej-legacy) (all accessed 2026-10-17).
- Rueden C.T. et al. (2017), [doi:10.1186/s12859-017-1934-z](https://doi.org/10.1186/s12859-017-1934-z).

## Change log

- 2026-10-17: Initial draft (agent G3).
- 2026-10-17: Lead annotation (Phase 8). Progress and status forwarding corrected per A20.
