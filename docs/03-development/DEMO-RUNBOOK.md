---
id: DEV-04
title: Demo runbook (J-12 cell counting)
stage: 03-development
status: Draft
priority: P0
source_revisions: [Fiji archive 20261013-1517 (lock f6695397 / 9403b619 LF), imagej/ImageJ@v1.54p, integration branch development/fidelis-workstation]
related_requirements: [FR-PROC-001, FR-ROI-001, FR-MEAS-003, FR-HIST-002, FR-MACRO-001, FR-NL-001, FR-SPEECH-004]
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# Fidelis demo runbook (J-12 cell counting)

Status 2026-10-17 16:30 (DEMO-RUNBOOK revised after U3/T7/E8/V5). Everything below was run on the integration branch `development/fidelis-workstation`
(see `ACTIVE-DEVELOPMENT.md` for commit ids). The demo shows **one real Fiji engine, several ways to drive
it**: the native image tools, the Fiji menus, typed commands, a spoken instruction, and the macro that
the session generates and that classic Fiji replays.

## 1. Prerequisites (one machine, offline)

| Need | Where | Check |
|---|---|---|
| Pinned Fiji (archive 20261013-1517, ImageJ 1.54p, bundled Zulu JDK 21) | `C:\Users\arnav\Documents\dev\Fiji` (outside the repository, never modified) | `python -I scripts/check_fiji.py` (prototype tool; file inventory against `external/fiji.lock.json`) passes |
| Python 3.14 virtualenv for the tools | `..\click-to-batch\.venv` (or `.venv` in the repository) | `python -I fidelis-app/tools/build.py --no-tests` prints `[build] OK` |
| Speech (optional for the spoken step) | `.venv-stt` (Python 3.11, faster-whisper 1.2.1, CPU int8) and the hash-pinned `tiny.en` model under `external/models` | the Mic button is enabled a few seconds after launch; otherwise the status bar says why |
| Fixtures | `fixtures/cache/FX-003/blobs.gif` (ImageJ sample), BBBC001 under `fixtures/cache/FX-004` | `python -I verification/fetch_fixtures.py` if missing |

Python below means `C:\Users\arnav\Documents\dev\click-to-batch\.venv\Scripts\python.exe -I`.

## 2. Launch

```powershell
python -I fidelis-app/tools/build.py --no-tests
python -I fidelis-app/tools/run.py --isolated-home
```

`run.py` starts the bundled JDK directly with Fiji's own JVM flags (DPI-aware, 150 % display crisp),
creates the SciJava context with the legacy layer, keeps Fiji's main window hidden and opens the
Fidelis workspace on the real engine. The status bar reads `Engine: ready`. Expect about 3 s.

`--isolated-home` keeps ImageJ preferences out of the user's profile. Nothing in the demo writes to the
Fiji installation or to the registry.

## 3. The demo (10 minutes)

Expected numbers come from the pinned Fiji itself (`verification/baselines/FX-003/9403b6197ac4bf75/gui`)
and are asserted by `EngineParityTest`, `WorkspaceJourneyTest` and `MacroReplayTest`.

1. **Open a real microscopy image.** File > Open… → `fixtures/cache/FX-003/blobs.gif` (or drag it in).
   The tab shows the real IJ1 canvas (zoom with the wheel; the native rectangle, oval, polygon and wand
   tools in the palette are Fiji's own). Status bar: `256x254 pixels; 8-bit; 64K`.
2. **A native tool.** Draw a rectangle on the image, then Analyze > Measure. The ROI panel shows the
   selection with float geometry; the Results panel shows the row at full precision; History shows the
   step with provenance NATIVE (observed) or TYPED (menu). Undo is available.
3. **Typed commands with real parameters.** In the command bar type `subtract background` → choose
   *Process > Subtract Background…* → the Parameters form shows every macro key with its Fiji default
   and the exact macro line (`run("Subtract Background...", "rolling=50");`). Run. Then `gaussian`
   → sigma 2 → Run.
4. **Threshold with the overlay visible.** Right dock, *Threshold* tab: method **Otsu**, tick **Dark
   background** (Blobs has an inverting LUT; plain Otsu gives 66 particles, Otsu dark 71). The red
   overlay is IJ1's own and nothing changes in the pixels. *Apply → Convert to Mask…* opens the form with
   `black` shown as the global `Prefs.blackBackground` it is (never set silently) → Run.
5. **Binary and measurements.** Process > Binary > Watershed. Analyze > Set Measurements… (area, mean,
   min, integrated; decimal 9). Analyze > Measure: **Area 65024, Mean 155.9435746801181**.
6. **Count.** Analyze > Analyze Particles… size `20-Infinity`, show Masks, display, clear, include,
   summarize, add → Run. Results: **71 rows**; ROI list: **71 ROIs**; a `Mask of blobs.gif` tab appears.
   Click a row or an ROI: the selection is shown on the real canvas.
7. **History and macro.** Bottom dock *History & macro*: every step with origin, provenance, parameters
   and outcome; the generated macro updates live. *Export .ijm…* writes it. Undo the watershed and redo it:
   the pixels are restored from the snapshot (hash-verified in tests).
8. **Replay in classic Fiji.** `python -I verification/replay.py --macro <exported.ijm> --run-dir <dir>`
   runs the macro in the pinned Fiji in a separate JVM and dumps the final state; `MacroReplayTest`
   does this automatically and compares pixels, Results doubles and ROI vertices exactly.
9. **Spoken instruction.** Click **Mic**, say *"count the cells"* (or type it in the command bar). The
   proposal sheet shows the resolved command, its parameters and the macro line; nothing runs until
   **Confirm**. After confirmation the same engine path executes it and History records origin VOICE.
   Without a microphone, `run.py --isolated-home -- --instruction "count the cells"` shows the same sheet.
10. **A second dataset.** Open `fixtures/cache/FX-030/extracted/human_ht29_colon_cancer_1_images/AS_09125_050118150001_A03f00d0.tif`
    (BBBC001, Hoechst-stained HT29 nuclei) and repeat steps 3–6: 345 particles (the published manual counts
    are 350 and 362; reported only, as in `VERIFICATION-RESULTS.md`).

## 4. Verification commands

```powershell
python -I fidelis-app/tools/build.py               # compiles app and tests with the bundled JDK
python -I fidelis-app/tools/test.py                # the whole suite in one live Fiji JVM (~5 min)
python -I fidelis-app/tools/run.py --smoke --isolated-home   # engine self-test, exit 0
python -I verification/gui_smoke.py --timeout 150  # launches the app, screenshot, luminance checks
python -I verification/baseline.py --fixture FX-003          # regenerate a reference baseline
python -I fidelis-app/tools/run.py --isolated-home -- --screenshot fidelis-app/build/shots/demo.png --open fixtures/cache/FX-003/blobs.gif
```

## 5. What is not in the demo

- Live microphone capture has not been verified by a person (the fixture WAV path is tested).
- Only commands with a catalogued parameter schema (53 of 953) open as typed forms; the others run
  through Fiji's own dialog and are recorded with provenance LEGACY_DIALOG.
- A filter that leaves the pixels unchanged completes with a "no-op" note in History and nothing to undo.
- Plots, histograms and other special IJ1 windows are shown as legacy windows, not as documents.
