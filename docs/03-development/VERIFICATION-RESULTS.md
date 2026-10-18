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

## 5. Engine parity tests (now run on the product engine; see section 10)

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
`fidelis-app/src/test/java/sc/fidelis/verification/*.java`, `.gitignore` (cache and raw dumps).

## 10. Verification on the real engine (T7, 2026-10-17, branch `agent/verify` merged with `development/fidelis-workstation` 913629a)

All of the following was run against `sc.fidelis.engine.DefaultEngine`, obtained from `sc.fidelis.testkit.ProductEngine.get()`
(one engine per test JVM, registered with `EngineProvider`). The stub-era notes of the earlier version of this section
(stub export, skipped replay, "development stub" status bar, `engine.rois()` not mirrored) no longer apply.

**Suite.** `python -I fidelis-app/tools/test.py`, run twice in sequence (one JVM at a time): **216 tests, 0 failed** both times
(211 at the merge plus the FX-030 and FX-031 parity cases, the two speech end-to-end tests and the status-bar test).
No order-dependence was observed: both runs have identical counts and no differing test. `build.py` is green at each commit.

**J-12 parity through the real engine** (`EngineParityTest`, typed requests, every step compared exactly with the pinned-Fiji GUI
baseline under lock key `9403b6197ac4bf75`: E1 pixel SHA-256, E3 titles/dimensions, E4 ROI vertices, E5 Results as doubles, E6 globals;
the saved Tiff is read back and compared by pixel hash):

| Fixture | Image | Result | Particles (Analyze Particles rows = engine ROI list) | Published figure (reported only, E9) |
|---|---|---|---|---|
| FX-001 | two-squares.png | exact at s01-s10 | 2 | none |
| FX-003 | Blobs (Otsu dark) | exact at s01-s10 | 71 | none |
| FX-030 | BBBC001 `..._A03f00d0.tif` | exact at s01-s10 | **345** | manual counts 350 / 362 |
| FX-031 | BBBC004 `1GRAY.tif` (overlap 0.00) | exact at s01-s10 | **300** | ground truth 300 |

The brief called BBBC001/BBBC004 FX-004/FX-005; in `fixtures/manifest.json` they are FX-030 and FX-031 (FX-004 is
FluorescentCells.tif, FX-005 the 48-bit RGB HeLa image). J-12 was not run on FX-004/FX-005: it is a grey-level
threshold-and-count journey and those two are colour images, so it would need a colour conversion the journey does not define and
no baseline exists. Published counts are never pass/fail: BBBC001 345 against 350/362 and BBBC004 300 against 300 are reported figures.
The FX-030 GUI baseline and the headless cross-check (E1/E3/E5/E6 exact) are in `verification/baselines/FX-030/9403b6197ac4bf75/`.

**Workspace path** (`WorkspaceJourneyTest`): J-12 on FX-001 and FX-003 through the workspace on the product engine equals the
baseline, including the Results panel's copy-as-TSV text parsed back to doubles bit for bit (Mean 155.9435746801181, IntDen 10140075 on
Blobs, 71 and 2 rows). The status bar's engine label, read from the live frame, is exactly `Engine: ready`.

**E7 replay.** `exportMacroSince(n)` of the J-12 journey, run in the pinned reference Fiji on a fresh copy of the fixture, reproduces the live
session (E1/E4/E5/E6): passes (replay about 4.8 s, 71 Results rows and 2 images on Blobs). The independent reference macro replays to the stored baseline as before.

**Results TSV precision.** Exact (bit equality of `Double.doubleToLongBits`) in the Results panel; also `MeasurementRow` doubles equal the baseline.

**Speech to execution** (`SpeechToExecutionTest`, D08): `invert-command.wav` -> `WhisperSpeechService.transcribeFile`
("Invert the image.") -> `DefaultNlService` (READY, `run("Invert");`, `ij1:Edit>Invert`, origin NL_SPOKEN) -> consequence class checked <= AC3 -> `engine.submit` on a
64x32 8-bit ramp: pixel SHA-256 `b0593af6...7d1d` before and `a6ba55d9...2a9e` after, the latter equal to the hash of the ramp inverted by
255-v computed in the test (not by Fiji); the history record carries origin NL_SPOKEN. `silence-5s.wav` yields NEEDS_CLARIFICATION, no request and unchanged pixels.
Skipped by assumption when the worker is UNAVAILABLE. An AC4+ proposal is never executed (the test asserts the class first). Live microphone: not verified.

**GUI smoke** (`python -I verification/gui_smoke.py`): exit 0, application exit 0 after about 11 s, PNG `verification/runs/gui-smoke.png` (git-ignored)
1266x792, luminance standard deviation 78.9, 432 sampled colours. The harness now runs Windows OCR (`verification/ocr.ps1`) on the PNG and asserts
"Engine: ready" is present and "stub" absent: the status bar reads `Engine: ready`. I inspected the image: menu bar, command bar, tool palette, tabs
blobs.gif / two-squares.png / synthetic-16bit, Parameters, History and macro panels. Observation: the screenshot run adds a scripted UI scenario
(synthetic-16bit, history rows with origins M/P/C), so the front document is not Blobs; this is the UI's debug scenario, not the engine. The robot
capture is black without an interactive session, and the PNG is then composited from components (smoke evidence only, CR-A30-08).

**Known limitations, exactly.**
- Strict no-op rule: after `Invert`, `Gaussian Blur...`, `Unsharp Mask...`, `Smooth`, `Sharpen` and `Find Edges` the engine treats unchanged pixels as a silent failure (ROLLED_BACK). A blur of a flat image, or a legitimate identity, is therefore reported as a failure.
- `Subtract Background...` is outside that rule because the pinned Fiji leaves FX-001 unchanged at J-12 step s02 (it may be an identity); the baselines record this.
- The FX-030 cross-check is now active (baseline present, parity exact). BBBC counts are reported only.
- Not verified: live microphone, headless-mode engine, FX-004/FX-005/FX-040 through the engine, GUI interaction by a person.
- Whole-suite runtime is about 5 minutes (the replay test starts the reference Fiji twice).

## 11. macOS (Apple silicon), 17 October 2026 (branch `integration/engine-fixes`)

Machine: macOS 26 (Darwin 25.5), Apple silicon; Fiji at `.local/fiji/Fiji.app` (macOS distribution of the same archive),
bundled Zulu 21.42.19 / JDK 21.0.7; Python 3.10 on the PATH.

**Pinned runtime on macOS.** `external/fiji.lock.json` pins the Windows archive. `verification/reference_runner.py` checks
the 781 platform-neutral entries (every lock file outside `java|jars|lib/win64` and the `.exe` launchers) byte for byte:
all equal. The macOS JavaFX/native jars are the pinned component versions of the win64 ones (ffmpeg 6.1.1-1.5.10,
flimlib 2.1.1, gluegen-rt/jogl-all 2.6.0, JavaFX 23.0.2, openblas 0.3.26-1.5.10, opencv 4.9.0-1.5.10) and the JDK is the
pinned Zulu build. Extras outside the pin: `plugins/luma-fiji-bridge.jar` (Penny's stand-alone bridge, kept off the
reference class path) and `plugins/colourdeconvolution.txt` (data a plugin wrote). The lock key stays `9403b6197ac4bf75`.
The reference JVM is the command Fiji's launcher prints with `--dry-run`, minus the bridge jar.

**Baselines reproduced on macOS** (`python -I verification/baseline.py --check ...`, nothing overwritten): FX-001 and FX-003,
GUI and headless, equal the stored Windows per-step dumps (E1, E3-E6), the saved-Tiff digest and `summary.json`. FX-030
headless and FX-031 GUI/headless equal the committed `summary.json` (per-step dumps were not committed, licence) and were
stored locally (git-ignored) with `--fill-missing`. FX-030 GUI differed once only in the saved Tiff's digest: one overlay
ROI stroke colour `ffffff00` instead of `00000000` (the GUI artefact of section 4: the ROI under the pointer); it was not
stored, matched exactly on the next run and was stored then. The same artefact appeared once more on an FX-003 GUI check;
the rerun matched. A check with the wrong threshold (`Otsu` instead of `Otsu dark`) is reported as different.
`python -I verification/test_verification.py`: 14 passed (including the headless dumper self-test on FX-001).

**Suite.** `python -I fidelis-app/tools/test.py --timeout 1500` on the branch head (Penny bridge merged, commit 73d86d0): default order
**451 tests finished, 0 failed** in two consecutive runs (also 0 failed in the two default-order runs before the last
commit). `--random-order 17`: 0 failed in 3 of 3 runs after the last fix; `--random-order 42`: 0 failed in 2 of 3 runs, the
third failed once in `InteractiveJourneyTest` at the Threshold adjuster step ("timed out waiting for Otsu dark threshold
0.0-143.0 on the image": the adjuster did not apply the method within 30 s; seen once more earlier in a solo run of the
class, which otherwise passes; open, timing of the adjuster's own thread, not a parity difference).
14 cases are aborted by assumption in every run, all speech: the speech worker's interpreter defaults to
`C:\Users\arnav\...\.venv-stt\Scripts\python.exe` (set `-Dfidelis.stt.python`; voice stream). The aborted cases are
counted in "finished" and are not passes. Reported for this Mac before these fixes (integrator's run of the same
command on the pre-merge branch): 395 tests, 6 failed; the first full runs in this session also aborted on the AppKit
text-input race below.

**Fixed on the way (engine and test harness; details in the commit messages).**
- Native Results mirror by content and command boundaries (EngineNativeSyncTest 3/3).
- Native dialog answers recorded as the command's exact options; interactive J-12 matches the GUI baseline at every step
  and its exported macro replays in the reference Fiji to the live state (InteractiveJourneyTest).
- Image adoption no longer races `ImagePlus.show()` (an unclosable image in EngineQuitTest, EngineConventionsTest) and
  never takes another engine's host window (the UI's StubEngine workspace in the same test JVM, WorkspaceU2Test).
- Test JVM on macOS: text components without input methods (an AppKit text-input race aborted the whole JVM); the
  JUnit thread's stale IJ1 temporary current image is cleared by test isolation.
- Opt-in reserved key `expectedRoi` (`sc.fidelis.engine.RoiSignature`, EngineExpectedRoiTest).

**Not verified here:** speech (no faster-whisper interpreter configured on this Mac), live microphone, OCR of the GUI
smoke screenshot (Windows OCR only; `gui_smoke.py` reports it as not asserted on macOS), a person driving the GUI.

## 12. macOS (Apple silicon), 18 October 2026 (branch `integration/engine2`, from `main` 18567f8)

Same machine and pinned Fiji as section 11. Goal: the engine and its suite solid on macOS (the known flake, the speech
tests, random-order stability, three engine robustness items).

**The Threshold-adjuster flake (InteractiveJourneyTest, "timed out waiting for Otsu dark threshold 0.0-143.0").** Root
cause, from IJ1 1.54p's `ThresholdAdjuster`: its constructor calls `show()`, starts the adjuster thread and *then*
applies the initial auto-threshold (method Default) on the command's `Executer` thread through `setup(imp, true)`;
`setup` is not synchronised. The test drove the adjuster as soon as `isShowing()` was true, so the Otsu it chose ran
on the adjuster thread concurrently with that constructor `setup`, and whichever `setup` wrote last won: with the
stale Default levels the image kept 0-142 and nothing applied Otsu again. Reproduced with a scratch probe (Subtract
Background and Gaussian Blur as in the journey, fresh adjuster state each time): 1 failure in 30 when driven on
`isShowing()` (the command thread was still in the constructor in 9 of the 30), 0 in 40 when the adjuster is driven
only after the "Threshold..." command thread has returned. The fix is in the test's driving (`finish("Threshold...")`
before touching the adjuster, which is what a person does: the frame appears a few milliseconds before the constructor
returns); the engine and its observer were not involved. Proof: the class run 10 times in a row, 10 of 10 green
(threshold 0.0-143.0 every time).

**Speech.** The repository venv now exists on this Mac (`python3.11 -m venv .venv-stt`, `pip install --require-hashes -r
speech/requirements.lock.txt`: faster-whisper 1.2.1, ctranslate2 4.8.3, CPU; `scripts/setup_speech.py` fetched the
pinned `Systran/faster-whisper-tiny.en` revision, 75.5 MB, all five file hashes verified). `WhisperSpeechService`
defaults to `<repo>/.venv-stt/bin/python` on macOS and Linux (`Scripts/python.exe` stays the Windows default) and
`test.py` passes the venv as `-Dfidelis.stt.python` when it exists. The worker's own tests pass (`speech/test_server.py`,
10) and a hand-driven handshake transcribed `fixtures/invert-command.wav` as "Invert the image.". All 14 previously
aborted JUnit cases now run and pass (`WhisperSpeechServiceTest` 13 incl. the fixture transcription at 139 ms,
`VoiceToProposalTest.fixtureAudioToReadyInvertProposal`, plus `SpeechToExecutionTest` 2 and
`NlRealEngineJourneyTest.spokenInvertNeedsConfirmation…`, which were previously skipped by the same assumption).

**Random-order stability.** With the speech tests running, the first set of runs on the branch: default order 455
tests, 0 failed; `--random-order 42` and `7`: 0 failed; `--random-order 17`: 1 failed,
`EngineQuitTest.quitRefusesWithUnsavedChangesAndGoesAheadWhenDiscarding` ("expected 0 but was 1" from
`WindowManager.getImageCount()` after quit). Reproduced deterministically with the new `test.py --class-order` (the
first seven classes of the seed-17 order) and diagnosed: `getImageCount()` counts one image when the list is empty but
`getCurrentImage()` still answers, and the JUnit thread held a temporary current image pinned by `ImagePlus.show()`,
which had waited 2 s for a window activation because `IJ.isMacro()` was true. It was true because IJ1's static
`Interpreter.instance` still named a *finished* interpreter: Fiji's start-up runs the StartupMacros `AutoRun` macro on
its own thread while the installer saves the macro set's globals, and `Interpreter.run(String,String)` / `saveGlobals`
restore the previously saved reference, so the JVM starts with a stale instance that survives until some macro fails
or an abort times out (in other orders an early macro error cleared it). Fixed at the source for the product and the
suite: `sc.fidelis.engine.MacroState.clearStale()` drops the reference when no thread is inside `ij.macro`, called from
the engine's observer timer (200 ms) and at every class start by test isolation, whose `[isolation]` line now reports
`interpreter=`. No assertion was weakened; `EngineQuitTest` gained a diagnostic message instead.

**Engine robustness (each with a test in `EngineCodeEffectsTest`).** (a) `EngineEvent.LogLine` is emitted: the
observer timer mirrors IJ1's Log every 200 ms and `CodeRunner` flushes it before a CODE result, comparing line
positions (appended lines once each, a `\\Update` of a line again with its new text, after `\\Clear` only what follows);
the editor console no longer appends the digest's lines a second time and the Log tab shows the lines
(`WorkspaceMacroEditorTest` asserts once / before the result / in the Log tab). (b) The CODE capability scan walks IJ1's
token stream (a capability is its name token followed by `(`, `File.x` as function-dot-word; comments and string
contents do not count); the symbol table, which lists every built-in for every text, is no longer consulted, so
`print("hello")` confirms with no capabilities and `exec("ls")` with `exec`. (c) After a CODE run the ROI Manager is
synchronised like after a native command: the target document's list is loaded into the manager before the macro (a
batch-mode macro therefore writes to the registered manager too) and taken back when its content changed
(`roiManager("Add")` ×2 gives `rois(doc)` = 2 with exact bounds, a reset gives 0, `roiChanged` in the digest).

**Review.** Codex autoreview (`/Users/ayush/.codex/skills/autoreview/scripts/autoreview --mode branch --base 18567f8
--engine codex`, read-only, 15 runs). Run 1 was a reviewer failure (its one finding used an absolute path and was
discarded; no review produced). Runs 2-14 reported 1-5 findings each (35 in all, 6 P1); every one was checked against
the code and IJ1 1.54p's source and, except three rejected as design limits documented in the code, accepted and fixed
with a test in the same commit: ROI ownership and the manager mirror (load only when the manager does not already
hold the document's objects, by identity and the names recorded at the last sync; changes detected on IJ1's own ROI
serialisation, taken on the EDT because the canvas sets a Show All entry's stroke colour while painting; an ownerless
macro gets an empty registered manager; a manager the macro closed, replaced, or IJ1's private batch-mode manager, is
still the source; copies keep the document's position, stroke object and width scaling), `MacroState` as a
compare-and-set with a give-back, the Log mirror's line positions and generations with the editor back-filling
digest lines, and three test races. Rejected and documented: a macro that moves between images keeps its manager
entries on the document it started on (the native rule; IJ1's manager carries no per-entry image identity), IJ1's
slice-prefixed labels for unnamed entries stay IJ1's convention (the engine measures on the ROI's position), and the
editor console's append-only order in the clear-and-reprint-between-two-polls case (the Log tab and the digest keep
the true order); residual noted in `CodeRunner`: a manager a command constructs itself and the macro closes within the
same 100 ms poll interval is not seen. Run 15 (final head): exit 0, no accepted or actionable findings.

**Final results (`python3 -I fidelis-app/tools/test.py`, branch head 473295a).** default order 461 tests, 0 failed, 0 skipped (173 s), twice (the second 174 s); `--random-order 17`, `42` and `7`: 461, 0 failed, 0 skipped each (172-173 s); `--random-order 17 --random-methods`: 461, 0 failed, 0 skipped (173 s). The 461 are the 451 of the integrated `main` plus the 10 of `EngineCodeEffectsTest`; the 14 speech cases that were aborted by assumption before now pass inside these numbers (every run transcribed the fixture WAV).
`python3 -I verification/test_verification.py`: 14 passed (compare, stored baselines, the headless dumper self-test in the pinned Fiji).

**Not verified here:** a live microphone session, Windows (the Windows defaults are unchanged but were not run), a
person driving the GUI.
