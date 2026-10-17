---
id: DEV-03
title: Verification results
stage: 03-development
status: Living record
priority: P0
source_revisions: [Fiji archive 20261013-1517 (lock f6695397 / 9403b619 LF), imagej/ImageJ@v1.54p, integration branch development/fidelis-workstation]
related_requirements: [NFR-FID-001, NFR-FID-002, NFR-FID-003, NFR-FID-004, FR-MEAS-003, FR-MACRO-001]
related_decisions: [DEC-010, ADR-010]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# VERIFICATION RESULTS — Fidelis workstation (agent A4)

Status as of 2026-10-17. Every number below was produced by a run in this worktree (`agent/verify`); the
commands are given so that each can be repeated. Two kinds of evidence are kept apart on purpose:

- **Fiji parity (E1–E7, docs A30)** is exact equality with a reference execution of the pinned Fiji. It proves
  that the *invocation* is faithful. Pass or fail.
- **Biological or analytical plausibility (E9)** compares a workflow's output with independent ground truth
  (BBBC counts). It is **reported, never pass/fail**: a workflow can be faithful to Fiji and still be a poor
  biological method (A30 section 1).

## 1. Fixtures obtained (`fixtures/manifest.json`, `python -I verification/fetch_fixtures.py --list`)

| Id | Fixture | Obtained | Bytes | SHA-256 (first 16) | Licence | Policy |
|---|---|---|---|---|---|---|
| FX-001 | `fixtures/two-squares.png` | in repo | 133 | e251d96a332e33cf | project work (DEC-005 open) | committed |
| FX-003 | ImageJ sample `blobs.gif` | **downloaded** | 25,765 | 507d168e317a6b80 | none stated by imagej.net (UNKNOWN) | cache only |
| FX-004 | `FluorescentCells.zip` | downloaded | 436,133 | 38507fab9787016f | UNKNOWN; Molecular Probes slide | cache only |
| FX-005 | `hela-cells.zip` | downloaded | 1,374,676 | 9d121c922be1dedad | UNKNOWN | cache only |
| FX-030 | BBBC001 v1 TIFF zip + `counts.txt` | **downloaded** (832 kB zip) | 854,168 + 303 | d6d4ad4743dc8dfd, b246712302 | **CC BY-NC-SA 3.0** | cache only, never committed |
| FX-031 | BBBC004 v1, overlap 0.00: images zip + foreground zip | **downloaded** (13.0 MB + 0.48 MB) | 13,034,200 + 479,591 | 9156756acddba488, cdc55860766a4bba | **CC BY-NC-SA 3.0** | cache only, never committed |
| FX-040 | OME-TIFF `single-channel.ome.tif` | **downloaded** | 76,095 | 8188f23c90bbdebc | CC BY 4.0 (OME Consortium) | cache only until DEC-005 |

Sizes equal those recorded in `docs/data/datasets.csv` (A29). Digests are pinned in the manifest by an explicit
`--pin` after the first fetch; later fetches verify and delete a mismatching file. Zip members are extracted with
a path-traversal guard to `fixtures/cache/<id>/extracted/`. The cache and the NC-licensed baseline folders are
git-ignored (only aggregate `summary.json` files for FX-030/FX-031 are committed).

BBBC001 manual counts (`BBBC001_v1_counts.txt`): A03f00d0 350/362, A03f01d0 336/342, A03f02d0 396/447,
A03f03d0 320/341, A03f04d0 398/533, A03f05d0 241/257.

## 2. Reference runner (the oracle)

`verification/reference_runner.py` launches the pinned Fiji as a separate process:
`fiji-windows-x64.exe --java-home=<bundled Zulu 21> -Dimagej.updater.disableAutocheck=true -Duser.home=<run>/home --allow-multiple [--headless] -macro <file>`,
with the working directory a fresh run directory. `verify_runtime` (copied from the prototype, not imported)
checks the 1,379-file inventory of `external/fiji.lock.json` before and after each run (0.9 s) and the runner
asserts that `Fiji/update` is empty and that no `IJ_Prefs.txt` appears in the Fiji root.

Observed facts (each from a run, not from documentation):

| Question | Result |
|---|---|
| Does `-Duser.home` pass through the Jaunch launcher? | Yes: `getDirectory("home")` returned the run directory. IJ1 prefs therefore stay in `<run>/home` (VD18-1 answered for this runtime). |
| Is stdout captured from the launcher? | No (GUI-subsystem exe, even with `--console`). Macro errors are invisible in headless mode and show a **modal "Macro Error" dialog in GUI mode**, so every reference macro writes a done marker last and the runner uses a hard timeout and kills only the PID tree it started. |
| How does a GUI-mode reference run terminate? | `eval("script","System.exit(0);")` leaves the process alive (killed after 5 s). `run("Quit")` exits in 3.7 s with exit code 1. `eval("script","java.lang.Runtime.getRuntime().halt(0);")` exits in 3.5 s with code 0 after the done marker: **used for GUI mode**. Headless: `System.exit(0)` works (exit 0). |
| Macro number printing precision | `"" + x` and `print(x)`: 4 significant decimals (`1/3` → `0.3333`, `1e10/3` → `3.3333E9`). `d2s(x, n)` is capped at 9 decimals (`d2s(1/3,12)` → `0.333333333`). **Macros cannot dump full precision.** The dumper therefore calls Java through `eval("js", ...)` (Rhino, with full `Packages.*` access) and writes `Double.toString` (round-trips exactly) plus `Double.toHexString`. |
| Which script engines are reachable from a macro? | `eval("js", …)` works in headless and GUI mode. `eval("bsh", …)` did not run (aborted the macro; BeanShell not usable here). |
| Macro functions that do not exist | `is("Black Background")` is invalid (aborts the macro). The global is read in the dumper from `Prefs.blackBackground`. |
| Reference mode per step (from `docs/data/commands.csv`, `headless_probe`) | Open… `fail`, Subtract Background `pass`, Gaussian Blur `pass`, Threshold… `fail`, Convert to Mask `pass`, Watershed `untested`, Set Measurements `pass`, Measure `pass`, Analyze Particles `partial`, Save As Tiff `pass` → **GUI** for the chain (DEC-043). The runner picks headless only if every command is `pass`. |
| Time per run | 5–8 s per mode (JVM start dominates), 11 dump points. |

Note on the lock hash: `verify_runtime` returns SHA-256 of `external/fiji.lock.json` = `9403b6197ac4bf751e46921d2af6f12624809b0d2ee553d811491791f04c84a5` (file is LF in git, `sha256sum` of the blob and the working copy agree). The documentation (F02, F03, A02, A30) quotes `f6695397…`. The file in this branch does not hash to that value. Baselines are keyed by the first 16 hex digits of the **computed** value (`9403b6197ac4bf75`); the lead should reconcile the documented value or confirm that the lock file changed after those documents were written.

## 3. Baselines for journey J-12 (`verification/baselines/<fixture>/9403b6197ac4bf75/`)

Independent hand-written macro `verification/j12.py` (never shares code with the product generator), explicit
option strings from `commands-core-parameters.csv`, `setOption("BlackBackground", true)` first, dump after every step:

```
open(path)  Subtract Background... "rolling=50"  Gaussian Blur... "sigma=2"  setAutoThreshold("Otsu dark")
Convert to Mask  Watershed  Set Measurements... "area mean min integrated redirect=None decimal=9"  Measure
Analyze Particles... "size=20-Infinity show=Masks display clear include summarize add"  saveAs("Tiff", …)
```

Each step stores: per-plane and whole-image pixel SHA-256 (little-endian raw bytes, identical definition in
Python, JavaScript and Java), type, size, calibration, LUT hash, display range, threshold limits, every ROI
(float vertices, mask SHA-256), every Results cell at full double precision, the Summary table and the
globals. Raw planes are kept in git-ignored `raw/` folders.

| Fixture | Measure row (whole mask image) | Analyze Particles | Notes |
|---|---|---|---|
| FX-003 Blobs 256×254 | Area 65024, Mean 155.9435746801181, Min 0, Max 255, IntDen 10140075 | **71 particles**, Total Area 39765, Average 560.0704225352113, %Area 61.15434301181102; 71 ROIs; Results rows 71 | GIF has an inverting LUT; pixel SHA-256 after Watershed `1636854cd21f69cd…`; saved Tiff 92,002 B |
| FX-003 variant `Otsu` (no `dark`) | Mean 91.82494156003936 | **66 particles**, Total Area 23399, %Area 35.98517470472441 | stored as `FX-003-otsu-light`; see finding F2 |
| FX-001 two-squares 64×64 | Area 4096, Mean 30.87890625, IntDen 126480 | 2 particles, areas 192 and 304, Total Area 496 | after blur and Otsu the two 256 px squares are no longer equal-area objects |
| FX-030 BBBC001 A03f00d0 512×512 | Area 262144, Mean 40.20853042602539 | **345 particles**, Total Area 41297, %Area 15.7536 | E9 below |
| FX-031 BBBC004 `1GRAY` (overlap 0.00) 950×950 | Area 902500, Mean 27.857584487534627 | **300 particles**, Total Area 98594 | E9 below; 950×950 confirmed (902,500 px), settling the A29 INFERRED dimension |

Repeatability and mode equivalence (compare.py, exact):

| Comparison | E1 pixels | E3 metadata | E4 ROIs | E5 Results | E6 globals |
|---|---|---|---|---|---|
| GUI run vs second GUI run (FX-003, FX-001), 11 steps each | 11/11 | 11/11 | 11/11 | 11/11 | 11/11 |
| GUI vs headless (FX-003, FX-001, FX-030, FX-031), 11 steps each | 11/11 | 11/11 | 9/11 pass, 2 n/a | 9/11 pass, 2 partial (Results equal; Summary table n/a at s09, s10) | 11/11 |
| Saved Tiff, three launcher runs of FX-003 | byte-identical (`6e50db0c…`) | | | | |

"n/a" means the headless side has no ROI Manager or Summary window, never counted as a pass. **Headless and
GUI produce identical pixels and Results for every step on all four fixtures**, including Analyze Particles
without `add` in headless mode (the same 71, 2, 345 and 300 particles). Only `add` (ROI Manager) and the
Summary window need the GUI, which agrees with A08 op 11.

## 4. In-JVM cross-check (no engine involved)

`sc.fidelis.verification.InJvmCrossCheckTest` runs the stored reference macro steps through `IJ.runMacro` in the
test JVM (non-headless, live Fiji context) and captures state with Java code that shares nothing with the
JavaScript dumper (`Snapshot`, `PixelHash`). Run: `python -I fidelis-app/tools/test.py --select-class sc.fidelis.verification.InJvmCrossCheckTest`.

Result: **3 passed** (FX-001, FX-003, FX-030): at every one of the 11 steps, pixel SHA-256 (E1), Results as
doubles (E5), ROI vertices and mask hashes (E4), and globals (E6) equal both the GUI baseline and, where
present, the headless baseline. This validates the runner, the dumper and the Java hashing against each other.

Understood difference (not a Fiji-parity difference): the Tiff **file bytes** after Analyze Particles with
`add` in GUI mode can differ. TIFF saving stores the ROI Manager "show all" list as an overlay (71 ROIs for
Blobs: pixel data starts at offset 26,978 in the 92,002 B file; the headless file is 65,184 B and pixel data starts at offset 160), and
`ImageCanvas` recolours the ROI under the mouse pointer. In one run 3 bytes of one ROI's stroke colour
differed (`00ffffff` versus `00000000` at ROI 37 of Blobs), and FX-003 later matched the launcher file byte
for byte. The test therefore compares the saved file by reading it back: pixel SHA-256 and overlay vertices,
exactly. The file digests are printed for the record.

## 5. Engine parity tests (ready, skipped until A1)

`sc.fidelis.verification.EngineParityTest` obtains the engine by reflection from
`sc.fidelis.engine.Engines.current()` (a missing class skips the test with the message "Engines is not available
yet"). For FX-001 and FX-003 it submits the typed J-12 requests (explicit keys; `SET_AUTO_THRESHOLD`
`{method:Otsu, dark:true}`; `OPEN`; `SAVE_AS`) and compares after every step with the GUI baseline (E1/E3/E4/E5/E6).
Extra leading Results rows are tolerated (a capture log may keep rows that IJ1's `clear` dropped from the table).

Current state with this worktree's code: **skipped** (no `Engines`). To prove the machinery end to end, the
class also accepts `FIDELIS_PARITY_SELFCHECK=1`, which substitutes `HarnessSelfCheckEngine`, a thin `IJ.run`
adapter that is explicitly not the product engine. With it: **2 passed** (FX-001, FX-003), every step exact.
This shows the tests can pass, and `HarnessSelfTest.comparatorDetectsInjectedDifferencesAndAcceptsTheBaselineItself`
shows they fail on a one-ulp Results change, a one-ulp ROI vertex, a flipped pixel-hash digit, and unequal row counts.

## 6. What was run and what passed

| Suite | Command | Result |
|---|---|---|
| Python harness self-tests (comparators detect injected E1/E3/E4/E5/E6 differences; dumper checked on FX-001 in a live pinned Fiji: mean 18.75 before and 236.25 after Invert, and inverted bytes equal 255-x; stored raw planes hash to their digests; saved Tiff read back with Pillow equals the dumped mask; PNG blank-detector) | `python -I verification/test_verification.py` | 14 passed |
| Java, whole suite in one live Fiji JVM | `python -I fidelis-app/tools/test.py` | 9 tests run, 0 failed; `EngineParityTest` skipped (no `Engines`) |
| Java, `HarnessSelfTest` (PixelHash equals Python digests for 8/16/32-bit and RGB incl. NaN and -0.0 bit patterns; ramp hash equals the JavaScript dumper's; comparator mutation tests) | `--select-class sc.fidelis.verification.HarnessSelfTest` | 4 passed |
| Java, `InJvmCrossCheckTest` | see section 4 | 3 passed |
| Java, `EngineParityTest` with the self-check adapter | `FIDELIS_PARITY_SELFCHECK=1` | 2 passed |
| GUI smoke | `python -I verification/gui_smoke.py` | **skipped** (exit 77): the app source has no `--screenshot` yet. Tested separately: blank 800×600 PNG fails, a rendered-looking PNG passes, a missing file fails; with `--force` the launch/timeout/kill path ran (45 s timeout, tree killed, no java process left). |

## 7. Findings other agents must know

- **F1. Option strings.** Every option string above was accepted by the pinned Fiji in both modes. Absent
  booleans are false in macro mode; `scaled` is omitted for uncalibrated images; Convert to Mask and Watershed
  take no options for single images. Nothing failed headless except `add` (the ROI Manager) and the Summary window.
- **F2. Threshold polarity on Blobs.** The specified `setAutoThreshold("Otsu dark")` on `blobs.gif` (inverting
  LUT) gives 71 particles covering 61.2% of the image; `"Otsu"` without `dark` gives 66 particles covering 36.0%.
  Both are Fiji-faithful. This is the UP-04 hazard (D16 step 4: "whether *dark* is needed is UNKNOWN") made
  concrete: the parity baseline must use what the user chose, and the demo should show the overlay before Set.
- **F3. Saved Tiff after `add`.** In GUI mode the saved Tiff carries the ROI Manager list as an overlay; a
  hidden-window engine may or may not reproduce this. The parity test compares pixels strictly and reports the
  overlay count.
- **F4. Analyze Particles `clear`.** After `clear` the live table holds only the 71 particle rows (the Measure
  row is dropped). `engine.results()` is compared as "last N rows equal"; A1 should decide whether the capture
  log keeps the dropped row and document it.
- **F5. Document order.** The comparison assumes `Engine.documents()` lists documents in creation order (the
  mask output of Analyze Particles comes after the source image).
- **F6. Lock hash.** See section 2 (computed 9403b619… versus documented f6695397…).
- **F7. A macro error in a GUI JVM blocks forever** on a modal dialog; `IjState.runMacro` (testkit) runs macros
  on a worker thread with a timeout and disposes the dialog on failure. The engine's own execution path needs the
  same guard.

## 8. E9 plausibility (reported only)

| Fixture | Pipeline output | Reference | Reading |
|---|---|---|---|
| BBBC001 `A03f00d0` (real HT29 nuclei) | 345 particles (Otsu dark, rolling 50, sigma 2, Watershed, size ≥ 20 px²) | manual counts 350 and 362 | 5 below the lower manual count (1.4%) and 17 below the higher (4.7%); the two manual counts themselves differ by 12 here and by up to 135 on another image, so the counts are a band, not an oracle |
| BBBC004 `1GRAY` (synthetic, overlap 0.00) | 300 particles | exactly 300 objects | equal; at zero overlap the segmentation separates every object under this pipeline |
| Blobs | 71 (`Otsu dark`) or 66 (`Otsu`) | none (no ground truth) | informational only |

Not claimed: any accuracy across the other BBBC001 images or the other BBBC004 overlap sets (not run); any
Fiji parity for 16/32-bit data, stacks, calibrated images or multi-channel hyperstacks (the baselines cover 8-bit
single planes only; FX-004, FX-005 and FX-040 are cached but have no baselines yet); any speech or NL evidence.

## 9. Files

`fixtures/manifest.json`, `verification/{fetch_fixtures,reference_runner,j12,baseline,compare,gui_smoke,test_verification}.py`,
`verification/dumper.js`, `verification/baselines/**` (JSON committed, raw planes and NC-licensed per-step data
git-ignored), `fidelis-app/src/test/java/sc/fidelis/testkit/{Fixtures,PixelHash,IjState,HarnessSelfCheckEngine}.java`,
`fidelis-app/src/test/java/sc/fidelis/verification/*.java`, `.gitignore` (cache and baseline-raw lines).

## 10. Follow-up T6 on the merged tree (2026-10-17, after merging `development/fidelis-workstation`)

**GUI smoke, for real.** `python -I verification/gui_smoke.py --timeout 150` (launches `fidelis-app/tools/run.py
--isolated-home -- --screenshot <png> --size 1280x800 --open fixtures/cache/FX-003/blobs.gif`): exit code 0,
application exit 0 after 9.3 s, PNG `verification/runs/gui-smoke.png` (git-ignored), 1266×792, 52,350 B, luminance
standard deviation 78.9, 377 distinct sampled colours, no problems. I looked at the image: the window shows the
`blobs.gif` tab, the `two-squares.png` tab and the `synthetic-16bit` tab the debug run adds, the Parameters dock,
the History table and "Engine: ready (development stub)". The screenshot is smoke evidence only; it is not used as
scientific evidence (CR-A30-08). The robot capture was all black (no interactive session) and was not written.

**UI-path parity, independent of A1.** `sc.fidelis.verification.WorkspaceJourneyTest` builds the workspace as the
application does, around `EngineProvider.get(null)` (the UI's `StubEngine` until a product engine registers; the
test switches to `engine.rois()` automatically when one is registered), submits the J-12 typed requests with the
explicit option strings of section 3, and compares after every step with the GUI baseline: 2 passed (FX-001,
FX-003), every step exact in E1 pixels, E3, E5 Results as doubles, E4 ROI vertices (read from the IJ1 ROI Manager,
because the stub's `rois()` does not mirror it), E6 `blackBackground`. UI checks in the same run: the hosted IJ1
canvas is inside the workspace frame, the viewer's `ImagePlus` pixel hash equals the baseline after open.
Fallbacks (printed as notes by the test): the stub rejects `SET_AUTO_THRESHOLD` and `SAVE_AS` with
UNSUPPORTED_IN_MODE, so those two steps run through `IJ.setAutoThreshold(imp, "Otsu dark")` and
`IJ.saveAs(imp, "tiff", path)`, the calls a product engine uses. Convert to Mask is submitted with no options and
`Prefs.blackBackground = true` set before the first step, exactly as the baseline macro does: the `black` key
belongs to the stack path of `Thresholder`, and the baseline has none (commands-core-parameters.csv). The
EngineParityTest and WorkspaceJourneyTest share `J12Session`, so the product engine is tested by the same code.

**Results precision in the UI.** `ResultsPanel` has no public copy method: `tsv()` and `full()` are
package-private (the copy button calls `tsv()`). The test finds the panel in the frame's component tree and calls
`tsv()` reflectively on the EDT. After Measure the TSV (header `# Label Area Mean Min Max IntDen RawIntDen`) has
1 row; after Analyze Particles it has 71 rows (Blobs) and 2 rows (two-squares); every cell parsed with
`Double.parseDouble` equals the baseline double bit for bit (`doubleToLongBits`), including Mean
155.9435746801181 and IntDen 10140075.

**E7 macro replay.** `verification/replay.py` wraps a macro with the dumper, runs it in the pinned reference
Fiji in GUI mode and writes `out/final.json`; `MacroReplayTest` has two tests. (1) After the J-12 session,
`engine.exportMacro()` is replayed on a fresh copy of the fixture (paths rewritten to the copy and a replay-only
Tiff) and compared with the live session (E1, E4, E5, E6): **skipped** (assumption) because the stub's export
starts with "Development stub macro". (2) The machinery check feeds the independent reference macro through the
same path: the replay finished in 4.8 s and its final state (2 images, 71 Results rows, 71 ROIs) equals the
stored baseline step s10 exactly, and the cached fixture was not touched (the copy has the same SHA-256).
Debugging note: my first replay hung for the full timeout on a modal "Macro Error" because a newline escape in
`replay.py` split a string literal inside the macro; the runner's timeout and tree kill handled it, and the
fix is in the same commit.

**Suites after T6.** `python -I fidelis-app/tools/test.py` (merged tree, one live Fiji JVM): 170 tests run,
0 failed, 2 skipped by assumption (`EngineParityTest`: no `Engines`; `exportedMacroReplaysTheLiveSession`: stub
export). `build.py` is green. Python harness self-tests were not changed in this round (14 passed earlier).
