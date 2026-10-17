---
id: A11
title: Image Processing and Measurement Infrastructure
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p, imagej/imagej-ops@imagej-ops-2.2.0, imagej/pyimagej@8f9b8faf]
related_requirements: []
related_decisions: []
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A11 — Image Processing and Measurement Infrastructure

## Executive Summary

IJ1 processing is organised as follows:

- **`PlugInFilter`s** run by `PlugInFilterRunner`. The runner handles type checks, masking, stack and thread parallelisation, the undo snapshot, and the preview loop for `ExtendedPlugInFilter`s.
- **Pixel primitives** on `ImageProcessor`.
- **Thresholding** (`ImageProcessor.setThreshold`, `AutoThresholder`, `Thresholder` "Convert to Mask").
- **Measurement** (`Analyzer`, `ResultsTable`, `ImageStatistics`, `ParticleAnalyzer`).

Measurement is governed by **JVM-global** settings: measurement flags, redirect image, decimal places, `Prefs.blackBackground`, `ImageConverter` scaling, `Prefs.padEdges`, binary iteration and count, and RGB weights. Many plugins also remember their last *interactive* parameter values in static fields, and a `run()` options string that omits a key falls back to those values. To reproduce a result exactly, a run must therefore capture every relevant global and every option explicitly.

Two probe findings change the prototype's assumptions:

1. In headless Fiji, `run("Measure")` **does not accumulate**. The system Results table is truncated to one row after each Measure (P39).
2. Saved Results files are **rounded** to the display precision, 3 decimals by default (P37).

The recommended invocation route is to call original IJ1 commands with complete option strings on an explicit image (`IJ.run(imp, cmd, opts)`), and to use direct API calls only for read-only queries and ROI construction. ImageJ2 Ops are separate implementations and must not be substituted for IJ1 commands unless equivalence has been tested.

## Purpose and Scope

This document maps where processing and measurement functionality lives, how it can be invoked without reimplementation (F04 §10.3), how live preview works, and which global settings alter numeric results. The command catalogue itself is in A08. Image state is in A09, ROI-to-mask conversion in A10, and macro and Recorder mechanics in A12.

## Research Questions

1. Where do the filter and processing families live, and what contract do they follow?
2. How do thresholding and binary operations depend on settings?
3. How do `Analyzer`, `ResultsTable`, `ImageStatistics` and `ParticleAnalyzer` behave, and what global state do they read or write?
4. How should a frontend invoke original functionality: `IJ.run`, a direct API call, or IJ2 Ops?
5. Which operations offer live preview, and how is it implemented?
6. Which global preferences change results?

## Sources and Evidence

The primary source is `imagej/ImageJ@v1.54p` (03382be1), including the menu table `IJ_Props.txt`. Also used are `imagej/imagej-ops@imagej-ops-2.2.0` (5a90b5f0) and `imagej/pyimagej@8f9b8faf` (main). Probes P31, P33, P37 and P39 are indexed in [README-G2](../references/probes/README-G2.md).

## Verified Findings

### 1. Filter and processing families

| Family | Location | Contract | Evidence |
|---|---|---|---|
| `PlugInFilter` | `ij/plugin/filter/PlugInFilter.java` | `int setup(String arg, ImagePlus imp)`, then `void run(ImageProcessor ip)` (`#L23`, `#L36`). Capability flags: `DOES_8G=1`, `DOES_8C=2`, `DOES_16=4`, `DOES_32=8`, `DOES_RGB=16`, `DOES_ALL`, `DOES_STACKS=32`, `SUPPORTS_MASKING=64`, `NO_CHANGES=128`, `NO_UNDO=256`, `NO_IMAGE_REQUIRED=512`, `ROI_REQUIRED=1024`, `STACK_REQUIRED=2048`, `DONE=4096`, `CONVERT_TO_FLOAT=8192`, `SNAPSHOT=16384`, `PARALLELIZE_STACKS=32768`, `FINAL_PROCESSING=65536`, `KEEP_THRESHOLD=131072`, `PARALLELIZE_IMAGES=262144`, `NO_UNDO_RESET=524288` (`#L39-L91`). | **VERIFIED** |
| `ExtendedPlugInFilter` | `ExtendedPlugInFilter.java` | Adds `int showDialog(ImagePlus, String command, PlugInFilterRunner pfr)` and `setNPasses(int)`, plus the flag `KEEP_PREVIEW=0x1000000` (`#L53`, `#L64`, `#L70`). Implemented by `BackgroundSubtracter`, `Binary`, `Convolver`, `EDM`, `GaussianBlur`, `ImageMath`, `MaximumFinder`, `RankFilters`, `Rotator`, `StackLabeler`, `Translator` and `UnsharpMask` (grep of `implements.*ExtendedPlugInFilter` in `ij/`). | **VERIFIED** |
| Runner | `PlugInFilterRunner.java` | It locks the image (`#L57-L58`) and calls `showDialog` for extended filters (`#L61-L80`). For single images it snapshots for undo unless `Prefs.disableUndo` or `NO_UNDO` is set (`#L104-L121`). For stacks it calls `Undo.reset()` and runs slices on `Prefs.getThreads()` threads when `PARALLELIZE_STACKS` is set (`#L123-L135`). | **VERIFIED** |
| Other plugin kinds | `ij.plugin.*` (`PlugIn`, for example `Thresholder`, `BatchProcessor`) and `ij.plugin.frame.*` (non-modal windows, for example `ThresholdAdjuster`, `ContrastAdjuster`, `RoiManager`, `Recorder`) | Frames extend `PlugInFrame`, which is a `java.awt.Frame` and therefore GUI-bound (A10, A12, A15). | **VERIFIED** |
| Pixel primitives | `ij/process/ImageProcessor.java` and subclasses | `snapshot()`/`reset()`/`reset(mask)` (`#L2239-L2249`), `setRoi(Roi)` with a mask (`#L900-L915`), `convertToByte(boolean doScaling)` (`#L2480`), thresholds (§2) and filters (`smooth`, `sharpen`, `findEdges`, `invert`, `erode`/`dilate` and so on) | **VERIFIED** (signatures); full list in A08 |
| Menu mapping | `IJ_Props.txt` | For example `filters02="Gaussian Blur...",ij.plugin.filter.GaussianBlur` (`#L324`), `binary02="Convert to Mask",ij.plugin.Thresholder("mask")` (`#L269`), `binary04="Erode",ij.plugin.filter.Binary("erode")` (`#L271`), `binary19="Options...",ij.plugin.filter.Binary("options")` (`#L287`), `options05="Conversions...",ij.plugin.Options("conv")` (`#L102`), `adjust04="Threshold...[T]",ij.plugin.frame.ThresholdAdjuster` (`#L124`), `batch03="Macro... ",ij.plugin.BatchProcessor` (`#L345`) | **VERIFIED** |

### 2. Thresholding and binary operations

- `ImageProcessor.setThreshold(min, max, lutUpdate)` stores the levels. The levels are display state until applied, with `NO_THRESHOLD=-808080.0` (`ImageProcessor.java#L37`, `#L510-L560`). `setAutoThreshold(String)` parses the method and options (`#L594-L612`), and `setAutoThreshold(Method, darkBackground, lutUpdate)` (`#L631`). `IJ.setAutoThreshold(imp, method)` interprets the keywords `dark`, `16` (full 16-bit histogram) and `stack` inside the method string (`ij/IJ.java#L1492-L1516`). **VERIFIED**
- `AutoThresholder.Method` has 17 values: `Default`, `Huang`, `Intermodes`, `IsoData`, `IJ_IsoData`, `Li`, `MaxEntropy`, `Mean`, `MinError`, `Minimum`, `Moments`, `Otsu`, `Percentile`, `RenyiEntropy`, `Shanbhag`, `Triangle` and `Yen` (`ij/process/AutoThresholder.java#L11-L29`). **VERIFIED**
- **Convert to Mask** (`ij/plugin/Thresholder.java`, `applyThreshold` `#L150-L264`):
  - With no threshold set, it silently applies an automatic `ISODATA2` threshold (`#L163`, `#L218-L219`, `autoThreshold` `#L432-L437`). P37: an 8-bit ramp with no threshold became pixel(10)=255 and pixel(200)=0.
  - Non-8-bit images are first converted to 8-bit with `convertToByte` (`#L214-L215`), so the display-range scaling of A09 §3 applies.
  - Foreground is always pixel value 255. When `Prefs.blackBackground` is false, the *LUT* is inverted so that foreground displays black (`#L244-L251`). P37: the pixel values were identical (`0`/`255`) for both settings; only `invertedLut` differed.
  - The "Convert to Mask" options dialog for stacks temporarily sets `Prefs.blackBackground` from a checkbox and restores it afterwards (`#L71-L100`, `#L139`).

  **VERIFIED**
- **Binary operations** (`ij/plugin/filter/Binary.java`): foreground is `Prefs.blackBackground ? 255 : 0`, flipped if the LUT is inverted (`#L118-L121`). `iterations` (static, `#L22`) and `count` (`Prefs` key `binary.count`, `#L19-L23`) are global. Erode honours `Prefs.padEdges` (`ij/process/ByteProcessor.java#L693-L697`). P37 recorded identical Erode results for a mask made under each `blackBackground` setting, because Convert to Mask sets the LUT consistently. Applying Erode to an *externally created* 0/255 image with a different LUT or setting gives inverted semantics (**INFERRED** from `#L118-L121`). **VERIFIED** (code)

### 3. Measurement

| Component | Behaviour | Evidence |
|---|---|---|
| Measurement flags | `Measurements` bit flags: `AREA=1`, `MEAN=2`, `STD_DEV=4`, `MODE=8`, `MIN_MAX=16`, `CENTROID=32`, `CENTER_OF_MASS=64`, `PERIMETER=128`, `LIMIT=256`, `RECT=512`, `LABELS=1024`, `ELLIPSE=2048`, `INVERT_Y=4096`, `SHAPE_DESCRIPTORS=8192`, `FERET=16384`, `INTEGRATED_DENSITY=0x8000`, …, `STACK_POSITION=0x100000`, `SCIENTIFIC_NOTATION=0x200000`, `ADD_TO_OVERLAY=0x400000`, `NaN_EMPTY_CELLS=0x800000` (`ij/measure/Measurements.java#L4-L10`) | **VERIFIED** |
| Set Measurements | `Analyzer.systemMeasurements` is `static`, initialised from the pref `measurements` with default `AREA+MEAN+MIN_MAX` (`ij/plugin/filter/Analyzer.java#L34-L40`). `setMeasurements`/`getMeasurements` (`#L967-L974`) and `savePreferences` (`#L987-L988`). P37 read 19 (=1+2+16) on the pinned install. | **VERIFIED** |
| Decimal places | `public static int precision`, from the pref, default 3 (`#L42`). `Analyzer` pushes it into the table (`#L57-L64`, `#L81-L88`). `ResultsTable.saveAs` writes `getRowAsString`, i.e. **rounded** values (`ij/measure/ResultsTable.java#L1360-L1393`). P37 found an in-memory value of 0.42857143921511515 written to CSV as `0.429`. | **VERIFIED** |
| Redirect | `redirectTarget` (image ID), `redirectTitle` and `redirectImage` are static (`Analyzer.java#L45-L47`). With a redirect set, statistics come from *another* image using this image's ROI (`#L278-L281`, `getRedirectImage` `#L346-L360`). | **VERIFIED** |
| `measure()` | Dispatches by ROI type (A10 §8) and appends a row (`#L258-L286`). `displayResults()` **deletes rows beyond the Results window's line count** ("delete rt rows added by particle analyzer", `#L845-L866`). With no Results `TextPanel` (headless), every Measure therefore leaves exactly one row. P39 confirmed this through both `IJ.run(imp,"Measure","")` and macro `run("Measure")`: `nResults` was 1 after each of three calls. | **VERIFIED** |
| `resetCounter()` | In interactive use it may show "Save N measurements?" (`#L938-L963`). It is suppressed in macros and batch mode. | **VERIFIED** |
| `ImageStatistics` | `ImageStatistics.getStatistics(ip, mOptions, cal)` picks `Byte`/`Short`/`Color`/`FloatStatistics` (`ij/process/ImageStatistics.java#L92-L104`). The mask rule is in A10 §8. `LIMIT` restricts pixels to the threshold (`ByteStatistics.java#L22-L32`). `ImagePlus.getStatistics()` defaults to `AREA+MEAN+STD_DEV+MODE+MIN_MAX+RECT`, and `getAllStatistics()` is about 70 times slower (`ij/ImagePlus.java#L1092-L1105`). 16-bit histogram ranges are converted from calibrated to raw values (`#L1149-L1153`). | **VERIFIED** |
| Histogram | `ImageProcessor.getHistogram()`, `ImageStatistics.histogram`, and the `Histogram` command (popup `IJ_Props.txt#L16`). The bin count is set with `setHistogramSize` and reset to 256 afterwards (`ImagePlus.java#L1147`, `#L1158`). | **VERIFIED** |
| `ParticleAnalyzer` | Option flags from `SHOW_RESULTS=1` to `OVERLAY=262144` (`ij/plugin/filter/ParticleAnalyzer.java#L33-L87`). `staticOptions` comes from prefs (`#L98`). It uses `Analyzer.getMeasurements()` when none are given (`#L563-L564`) and strips `LIMIT` (`#L565`). With "Display summary" it **adds `AREA` to the global flags** (`#L427-L428`). Without a threshold, it requires a binary image and infers foreground from `Prefs.blackBackground` and the LUT, logging "threshold not set; assumed to be …" (`#L819-L846`). The Wand is in `LEGACY_MODE`, or 4-connected (`#L158`, `#L218-L219`). `setRoiManager`, `setResultsTable` and `setSummaryTable` are static injection points (`#L1202-L1214`). | **VERIFIED** |

### 4. Invoking original functionality without reimplementing it

| Route | How | Strengths | Weaknesses | Use for |
|---|---|---|---|---|
| **A. `IJ.run(imp, command, options)`** | Command name from the menu table; options as a `key=value` string read by `GenericDialog` through `Macro.getValue` (A12) | Exercises *exactly* the code path behind the menu, including `PlugInFilterRunner`, masking, stack handling, undo and recording. P31 showed that a `CommandListener` sees the command and `Macro.getOptions()` on the calling thread. | String typing. A missing key silently falls back to the field's default, which for many plugins is the *last interactive value*: `GaussianBlur` keeps `sigmaS` static and updates it only when `options==null` (`GaussianBlur.java#L40-L44`, `#L98-L101`). Stacks: without the `stack` keyword in options, only the current slice is processed (`IJ.setupDialog`, `IJ.java#L1262-L1266`). Behaviour depends on globals (§5). | **Default for every user-visible operation.** Always pass every dialog key explicitly. |
| **B. Direct IJ1 API** | for example `new GaussianBlur().blurGaussian(ip, sx, sy, acc)` (`GaussianBlur.java#L176`), `ip.setThreshold`, `imp.getStatistics(flags)`, `new ParticleAnalyzer(options, measurements, rt, min, max, minCirc, maxCirc)` (`ParticleAnalyzer.java#L195`) | Typed, no dialog, no global options string, explicit results table | Skips `PlugInFilterRunner` (masking, stack loop, undo snapshot) and the Recorder. The defaults differ: `blurGaussian`'s accuracy is 0.002 for 8-bit/RGB, versus 0.0002 elsewhere (`#L165`). Equivalence to the menu command is not guaranteed. | Read-only queries (statistics, histogram, dimensions), ROI construction (A10), and plugins with a clean API. Verify equivalence per operation (A30). |
| **C. ImageJ2 Ops** | for example `ops.run("filter.gauss", …)`, backed by `GaussRAISingleSigma`, which wraps imglib2 `Gauss3` (`imagej/imagej-ops@imagej-ops-2.2.0:src/main/java/net/imagej/ops/filter/gauss/GaussRAISingleSigma.java#L56-L57`, `DefaultGaussRAI.java#L36-L52`). Thresholds: `ComputeOtsuThreshold` and `ComputeIJ1Threshold` ("Implements the default threshold method from ImageJ 1.x", `threshold/ij1/ComputeIJ1Threshold.java#L46-L53`). | N-dimensional, typed, works on `Img` without IJ1 globals | **Different implementations** from the IJ1 commands (a different Gaussian kernel and edge handling). Using them would be a reimplementation from the user's point of view. | Only where a parity test (A30) shows equivalence, or for new functionality that has no IJ1 counterpart. |
| D. Macro text through the interpreter | `IJ.runMacro`, `-macro`, pyimagej `run_macro` (`imagej/pyimagej@8f9b8faf:src/imagej/__init__.py#L235`) | Same as A, plus control flow. Replayable. | Extra parsing layer, error handling through dialogs or abort, global batch mode (A12) | Exported or replayed workflows (A12) |

**Conclusion (INFERRED):** Route A, with an explicit image and a complete option string, is the only route that is the original functionality by construction. Routes B and C need per-operation equivalence evidence.

### 5. Live preview

- Preview exists only where a dialog has a preview checkbox wired to a `PlugInFilterRunner`, i.e. the twelve `ExtendedPlugInFilter`s listed in §1. Each calls `gd.addPreviewCheckbox(pfr)` (`ij/gui/GenericDialog.java#L590-L615`). `ThresholdAdjuster` and `ContrastAdjuster` are separate live frames that change display state only. **VERIFIED**
- Mechanism: `PlugInFilterRunner.setDialog` registers itself as a `DialogListener` (`PlugInFilterRunner.java#L588-L596`). `dialogItemChanged` starts a lower-priority "<command> Preview" thread when the checkbox is on, and interrupts and restarts it on every change (`#L609-L635`). `runPreview()` snapshots the *real* processor and, on each pass, restores the ROI and pixels from the snapshot (`ip.reset()`) before reprocessing the **current slice only**. It then calls `updateAndDraw` (`#L483-L556`). If the dialog is cancelled the original is restored; on OK with `KEEP_PREVIEW` the preview result is kept for that slice (`#L72-L80`, `#L543-L550`). `killPreview` restores the overlay (`#L559-L569`). **VERIFIED**
- Consequence: preview **mutates the live image** while the dialog is open, and the preview checkbox state is never recorded (`GenericDialog.java#L568`, `#L584-L586`). A frontend that shows previews must use a `duplicate()` or a decimated copy, not the user's image (**PROPOSED**; see A09 H8).
- Preview is unavailable in macro mode: `showDialog` disposes the dialog immediately (`GenericDialog.java#L1508-L1511`) (**VERIFIED**).

### 6. Global settings that alter results

| Setting | Where | Effect on results | How it is set by macro/UI | Evidence |
|---|---|---|---|---|
| `Prefs.blackBackground` | `ij/Prefs.java#L76` | Binary foreground for Erode/Dilate/Open/Close/Outline/Fill Holes/Skeletonize. The LUT after Convert to Mask. Particle-analyser foreground when no threshold is set. | Process>Binary>Options ("black"); `setOption("BlackBackground", b)` | **VERIFIED** (code, P37) |
| Measurement flags `systemMeasurements` | `Analyzer.java#L40` | Which columns are computed, `LIMIT` (threshold-limited statistics), `INVERT_Y`, `STACK_POSITION`; also used by ROI Manager multi-measure and the particle analyser | Analyze>Set Measurements… (`Analyzer("set")`); `run("Set Measurements...", "area mean … redirect=None decimal=3")` | **VERIFIED** |
| Redirect image | `Analyzer.java#L45-L47` | Statistics come from a different image | Set Measurements "redirect=" | **VERIFIED** |
| Decimal places `Analyzer.precision` | `Analyzer.java#L42` | Rounding of the displayed and *saved* values | Set Measurements "decimal=" | **VERIFIED** (P37) |
| `ImageConverter` scaling (`doScaling`) | `ij/process/ImageConverter.java#L15`, `#L385` | 16/32→8 and 32→16-bit values depend on the display range | Edit>Options>Conversions; `setOption("ScaleConversions", b)` | **VERIFIED** (P37) |
| `Prefs.calibrateConversions` | `Prefs.java#L197` | Conversions apply the value calibration | Conversions… "Calibrate" (`ij/plugin/Options.java#L176-L187`) | **VERIFIED** |
| `Prefs.weightedColor` and RGB weights | `Prefs.java#L80`; `ColorProcessor.setWeightingFactors` | RGB→8-bit uses (0.299, 0.587, 0.114) instead of 1/3 each | Conversions… weighted; `setRGBWeights` (macro, `ij/macro/Functions.java#L2922-L2976`) | **VERIFIED** |
| `Prefs.padEdges` | `Prefs.java#L116` | Erosion at image borders | Binary Options "pad" | **VERIFIED** (`ByteProcessor.java#L693-L697`) |
| Binary `iterations`, `count` | `Binary.java#L22-L23` | Number and strength of erode/dilate passes | Binary Options | **VERIFIED** |
| `Prefs.useInvertingLut` | `Prefs.java#L90` | 8-bit images get an inverting LUT on show/convert, which flips binary semantics | Edit>Options>Appearance | **VERIFIED** (`ImageConverter.java#L51-L54`, `ImagePlus.java#L504-L505`) |
| Plugin static "last values" (for example `GaussianBlur.sigmaS`, `Thresholder.staticMethod`, `ParticleAnalyzer.staticOptions`/`staticMinSize`) | per plugin | Used when a `run()` key is missing | Previous interactive use | **VERIFIED** (code) |
| `ParticleAnalyzer` adds `AREA` to the global flags | `ParticleAnalyzer.java#L427-L428` | A later `Measure` gains an Area column | Side effect | **VERIFIED** |
| Global calibration | `ImagePlus.setGlobalCalibration` | Overrides per-image units and scale | Analyze>Set Scale "global" | **VERIFIED** (A09) |
| `Prefs.disableUndo` / `supportMacroUndo` / `keepUndoBuffers` | `Prefs.java#L100`, `#L189`, `#L144` | Undo availability (not the numbers) | Edit>Options>Memory etc. | **VERIFIED** |
| `Prefs.getThreads()` | `Prefs.java#L691` | Parallelism. Results are expected to be the same, but ordering-sensitive floating-point sums are **UNKNOWN**. | Edit>Options>Memory & Threads | **VERIFIED** (code) / **UNKNOWN** (determinism) |

Persistence: IJ1 prefs are saved to `IJ_Prefs.txt`, found by searching the cwd, then the ImageJ dir, then `~/.imagej` (`Prefs.java#L411-L440`). No such file exists on this machine after repeated headless runs (lead note in `probes/README-lead.md`). Headless runs therefore start from compiled-in defaults (**INFERRED**). P37 read `blackBackground=true`, which is presumably a Fiji default applied at start-up. Its source is **UNKNOWN**; to verify, grep imagej-legacy and Fiji start-up scripts for `blackBackground`.

## Diagrams and Inventories

```mermaid
sequenceDiagram
  participant FE as Frontend
  participant BE as Backend worker thread
  participant IJ as IJ.run / Executer
  participant PFR as PlugInFilterRunner
  participant GD as GenericDialog (macro mode)
  participant F as Filter
  FE->>BE: {cmd:"Gaussian Blur...", image:id, options:{sigma:2, scaled:false, stack:false}, globals}
  BE->>BE: apply/verify globals snapshot
  BE->>IJ: IJ.run(imp, "Gaussian Blur...", "sigma=2")
  IJ->>PFR: Executer.runCommand -> IJ.runPlugIn
  PFR->>F: setup(); showDialog()
  F->>GD: getNextNumber() -> Macro.getValue("sigma")
  PFR->>F: snapshot; run(ip) [mask, threads]
  PFR-->>BE: Undo.setup(FILTER)
  BE-->>FE: new revision: pixels digest, display state, results
```

## Constraints and Limitations

- The numeric equivalence of routes B and C with route A was not tested (**UNKNOWN**; belongs to A30).
- The Fiji default that makes `blackBackground=true` was not located (**UNKNOWN**; see §6).
- GUI-mode Measure accumulation (with a Results window) was not probed. It is **INFERRED** from `displayResults()` that rows accumulate when a window exists.

## Requirement implications

- **CR-A11-01** The system shall invoke user-visible processing through the original IJ1 command path (`IJ.run(imp, command, options)` or equivalent) with an explicit target image. *Rationale: §4, F04 §10.3.*
- **CR-A11-02** For every invoked command, the system shall pass every dialog key explicitly (including booleans and the `stack`/`slice` scope), using the parameter schema from A08. It shall not rely on plugin-remembered defaults. *Rationale: §4 route A weaknesses, `GaussianBlur.sigmaS`.*
- **CR-A11-03** Before each measurement or binary operation, the system shall capture `Prefs.blackBackground`, the measurement flags, the redirect target, the precision, `ImageConverter` scaling, `calibrateConversions`, the RGB weights, `padEdges`, binary iterations/count, `useInvertingLut` and global calibration. It shall record them with the result, and it shall be able to set them to a recorded state before replay. *Rationale: §6 reproducibility.*
- **CR-A11-04** Measurement results returned to the user or exported shall carry full double precision. Rounded display shall be presentation only. *Rationale: P37 CSV rounding.*
- **CR-A11-05** The system shall not depend on the IJ1 system Results table to accumulate rows in a headless backend. It shall collect each measurement row itself, or measure into an explicitly created `ResultsTable` through the API. *Rationale: P39.*
- **CR-A11-06** Live previews shall run on a copy of the image (or the current plane), shall never mutate the user's image before confirmation, and shall be offered at least for the twelve `ExtendedPlugInFilter` commands that have IJ1 previews. *Rationale: §5.*
- **CR-A11-07** The UI shall warn when Convert to Mask, Analyze Particles or a binary operation would run with no explicit threshold, because IJ1 silently auto-thresholds or assumes the foreground. *Rationale: §2, §3.*
- **CR-A11-08** ImageJ2 Ops shall not replace an IJ1 command unless an A30 parity test passes within the stated tolerance. *Rationale: §4 route C.*

## Risks

| ID | Risk | Likelihood | Impact | Mitigation idea |
|---|---|---|---|---|
| R-A11-01 | A replayed workflow gives different numbers because globals differ between sessions or machines | High | High | Snapshot and restore globals (CR-A11-03), and add a "settings drift" warning. |
| R-A11-02 | Headless Measure loses rows, so batch results are incomplete | High (already true in the prototype architecture for more than one Measure) | High | CR-A11-05, plus a regression test based on P39. |
| R-A11-03 | Omitted option keys pick up the last GUI values | Medium | High | CR-A11-02, with schema validation that rejects incomplete option sets. |
| R-A11-04 | Preview in the frontend differs from the final result (different code or a decimated copy) | Medium | Medium | Label previews as approximate, or run the exact command on a duplicate. |
| R-A11-05 | Exported CSVs are rounded, which harms downstream statistics | Medium | Medium | CR-A11-04: export from the backend's raw values. |
| R-A11-06 | Multithreaded filters are non-deterministic in the last bits | Low | Low | Add an A30 tolerance, and optionally pin `Prefs.setThreads(1)` in verification runs. |

## References

- `imagej/ImageJ@v1.54p` (03382be1): `IJ_Props.txt`; `ij/plugin/filter/PlugInFilter.java`, `ExtendedPlugInFilter.java`, `PlugInFilterRunner.java`, `GaussianBlur.java`, `Binary.java`, `Analyzer.java`, `ParticleAnalyzer.java`; `ij/plugin/Thresholder.java`, `Options.java`; `ij/process/ImageProcessor.java`, `AutoThresholder.java`, `ImageStatistics.java`, `ByteStatistics.java`, `ByteProcessor.java`, `ImageConverter.java`; `ij/measure/Measurements.java`, `ResultsTable.java`; `ij/gui/GenericDialog.java`, `DialogListener.java`; `ij/Prefs.java`; `ij/IJ.java`; `ij/ImagePlus.java`; `ij/macro/Functions.java`.
- `imagej/imagej-ops@imagej-ops-2.2.0` (5a90b5f0): `filter/gauss/GaussRAISingleSigma.java`, `DefaultGaussRAI.java`, `threshold/ij1/ComputeIJ1Threshold.java`, `threshold/otsu/ComputeOtsuThreshold.java`.
- `imagej/pyimagej@8f9b8faf`: `src/imagej/__init__.py`.
- Probes: [P31](../references/probes/P31-command-listener.out.txt), [P33](../references/probes/P33-undo-batch.out.txt), [P37](../references/probes/P37-conversion-threshold-measure.out.txt), [P39 Groovy](../references/probes/P39-measure-accumulation.out.txt), [P39 macro](../references/probes/P39-measure-accumulation-macro.out.txt); index [README-G2](../references/probes/README-G2.md).

## Change log

- 2026-10-17: Initial draft (agent G2).
