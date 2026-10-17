# Fiji demonstration guide: real biology by voice

Penny drives the real Fiji (ImageJ2 2.18.0 / ImageJ 1.54p) the way it drives QuPath: the researcher talks, Penny observes the open image, composes the ImageJ macro or TrackMate script the sentence implies, runs it immediately as one journaled, checkpointed action, highlights the result on the image and reads back the numbers Fiji produced. Nothing is mocked; every count below came out of the installed Fiji on the data below.

## Preparation

1. Install and build as in [PENNY-SETUP.md](PENNY-SETUP.md); Fiji is pinned by `script/setup-fiji.py`. Fetch the demo data once:

   ```bash
   python3 -I script/setup-fiji-samples.py
   ```

   It downloads 65 MB of images into `.local/data/fiji` (archives kept in `.local/downloads/fiji-samples`, 115 MB), verifies every file against the SHA-256 pins in `script/dependencies.json` (`fiji-samples`) and is idempotent.
2. Launch the project Fiji with the bridge (`./script/run-fiji.sh`) and Penny with the Fiji profile (`LUMA_APP=fiji`, see [FIJI-PROTOCOL.md](FIJI-PROTOCOL.md)). Keep the Fiji window and Penny's bar visible; click the mic.
3. Have the data folder open in Finder for the "whole folder" step and know the three files you will open by name.

## The data (what to say it is)

| Set | Files in `.local/data/fiji` | What it shows | Source and licence |
|---|---|---|---|
| ImageJ samples | `imagej/FluorescentCells.tif` (512 × 512, 3 channels, 8-bit), `imagej/confocal-series.tif` (400 × 400, 2 ch × 25 z, 0.054 µm/px), `imagej/mitosis.tif` (171 × 196, 2 ch × 5 z × 51 t, 16-bit, 0.0885 µm/px), `imagej/t1-head.tif` (256 × 256 × 129, 16-bit MRI), `imagej/blobs.gif` | Bovine pulmonary artery endothelial cells, DAPI (blue, ch 3) / tubulin (green, ch 2) / F-actin (red, ch 1), from a Molecular Probes demo slide (`FluorescentCells.txt`); a single cell in a confocal Z series; one HeLa cell dividing in 5D; a human head MRI; the classic blobs | The images ImageJ's File ▷ Open Samples fetches from `https://imagej.net/images/`, distributed by NIH with ImageJ (ImageJ is public domain, U.S. Government work, 17 U.S.C. § 105; the sample files carry no separate licence text) |
| BBBC039v1 subset | `bbbc039/images/*.tif` (10 fields, 696 × 520, 16-bit), `bbbc039/masks/*.png` (ground-truth label masks), `bbbc039/metadata/` | Hoechst-stained U2OS nuclei from a high-throughput screen (Caicedo et al. 2018); the first 10 fields of the published test split | Broad Bioimage Benchmark Collection, Ljosa et al., Nature Methods 2012; **CC0** |
| TrackMate tutorial | `trackmate/FakeTracks.tif` (128 × 128, 50 frames, 8-bit) | Synthetic moving spots over noise, the "getting started" data of TrackMate | Fiji sample server `samples.fiji.sc`, TrackMate project (GPL-3 software; tutorial data distributed with it) |

Cite the data as above; the ImageJ samples are demonstration images, not a publication's dataset. The BBBC039 ground truth is used only to say how close a count is; Penny never reads it during the demo.

## The flow (verified 17 October 2026 in batch mode; numbers are Fiji's)

Each line: what you say → what Penny does (the recipe in `skills/fiji/references/biology-recipes.md` or `tracking.md`) → what it says, with the exact numbers obtained on this data. Timings are the macro's own run time in a warm Fiji (headless runs including JVM start took 3–6 s each; inside the live Fiji the macro part is a fraction of that).

| You say | Penny does | Penny says (verified output) |
|---|---|---|
| "Penny, open the fluorescent cells image." | `fiji_open_path` on `imagej/FluorescentCells.tif` | "Opened FluorescentCells.tif, 512 by 512, three channels." |
| "Segment the nuclei in the blue channel and count them." | Recipe B1, channel 3, Otsu, σ 2, min 100 px², then `fiji_highlight` the manager | "15 nuclei, Otsu threshold 64 to 255, mean area 496 px², mean intensity 124." Outlines appear on the image. |
| "How much tubulin is in each nucleus?" | Recipe B2b, marker channel 2, background = mode | "Mean tubulin 103.5 per nucleus, 86.5 above a background of 17; corrected total 36,277 on average." |
| "What fraction of them are tubulin-positive above 100?" | Recipe B3, cutoff 100 | "9 of 15 nuclei, 60 %, are above 100." The positives are highlighted (indices 6–14). With "use the automatic cut-off": 14 of 15 (93 %) at 72. |
| "Do actin and tubulin colocalise?" | Recipe B4, channels 1 vs 2 | "Pearson 0.40; Manders M1 0.37, M2 0.52 with Otsu thresholds." Coloc 2 gives the same Pearson (0.40). |
| "Measure the tubulin in this region." (draw a rectangle) | Recipe B2 on the active ROI | For the 100 × 100 box at (200, 200): "Mean 94.2, 77.2 above background; corrected total 771,901." |
| "Open the first BBBC nuclei image." | `fiji_open_path` on `bbbc039/images/IXMtest_A09_s1_…tif` | "Opened a 696 by 520 16-bit image." |
| "Count the nuclei." | Recipe B1, channel 1, Otsu, σ 2, min 100 px², no exclude | "149 nuclei, Otsu threshold 385; mean area 592 px²." (Ground truth for this field: 156.) |
| "Which threshold method works best here?" | Recipe B8 | "Default 149, Huang 151, Li 152, Otsu 149, Triangle 149, Yen 1." |
| "Make the threshold stricter." | Recipe B8b, factor 0.2 | "92 nuclei with the threshold raised to 612." "Looser" (−0.2): 72, because neighbours merge at 158; Penny says so. |
| "Go back." | `fiji_restore` of the checkpoint before the change | The 149-nuclei outlines return. |
| "Count only the nuclei in this region." (draw an oval) | Recipe B7 on the ROI | For a 300 × 300 oval at (150, 100): "25 nuclei in 70,688 px², 0.35 per 1000 px², 22 % of the area; mean intensity 578." |
| "Do that for the whole folder." | Recipe B6 on `bbbc039/images` → `.local/exports/…/nuclei-per-image.csv` | "10 images, 963 nuclei in total; the table is saved." Per field: 149, 18, 84, 83, 60, 105, 104, 138, 119, 103 (ground truth 156, 69, 93, 95, 66, 122, 115, 156, 137, 119). |
| "Open the confocal series and segment the cell on a max projection." | Recipe B5, channel 2, Max Intensity | "One object, 86 µm², on the projection of 25 slices." (channel 1 gives 5 objects) |
| "Open FakeTracks and track the cells over time." | Tracking T1, radius 2.5, automatic quality cut-off | "8 tracks over 50 frames, mean length 28 frames, mean displacement 28 px, speed 2.0 px per frame." Tracks drawn in colour on the stack. |
| "Only the ones that actually move." | T1 again with `minDisplacement` 10 | "4 tracks, mean displacement 56 px." |
| "Save the tracks." | T1 with `outPrefix` | A spots CSV, a track XML and a TrackMate file in `.local/exports`. |
| "Track the chromosomes in the mitosis stack." | T1 on `imagej/mitosis.tif`, channel 2, radius 1 µm, 3D over 5 slices | "33 tracks over 51 frames, mean displacement 1.0 µm." (fragments of two chromosome masses in one cell, in 5.8 s headless) |
| "Save this as a macro." | `fiji_save_workflow` | The recorded steps go to `.local/workflows/`. |

Audience requests that are covered: "how many objects are in each channel" (B1 variant: 49 / 24 / 15 on FluorescentCells), "project the stack", "what's the mean intensity here", "zoom in on the biggest nucleus", "export the table", "undo". Anything else goes through `fiji_knowledge` (the imagej.net wiki and ImageJ guide are local) and a composed macro per the "Dynamic macros" rules in `skills/fiji/SKILL.md`.

## What to say honestly

- A count is the number of objects after the stated threshold, blur, size and circularity filters and watershed. It is not a validated cell count. The BBBC039 ground truth exists so the audience can see how close Otsu gets (149 of 156 on A09; a dim field, A12, drops to 18 of 69 and is the cue for "which method works best").
- Thresholds are software settings; Penny always says the method and bounds. Making a threshold looser can lower a count by fusing neighbours.
- The ImageJ samples are demonstration images (one cell in the confocal and mitosis stacks); FakeTracks is synthetic. For "cells moving" on real data use the researcher's own time-lapse; TrackMate's parameters come from the image calibration (radius in µm).
- Checkpoints restore pixels, ROI, overlay and ROI Manager of the active image; they do not undo files written, closed images, or TrackMate's own windows.
- Voice is AWS Transcribe → the model on Bedrock → Polly, from AWS credits; no cash-billed calls.

## Verification status

- **Verified headless/batch** (17 Oct 2026, `Fiji.app/fiji -batch` and `--headless --run`): every recipe in `biology-recipes.md` (B1–B8b) and `tracking.md` (T1, T2) on the files above, with the outputs quoted in those files; `script/setup-fiji-samples.py` twice (second run verified and extracted nothing new); BBBC039 ground-truth counts for the 10 fields from the label masks.
- **Not verified by this pass**: the same recipes through the live bridge (`fiji_run_macro` / `fiji_run_script` inside the GUI Fiji with highlights and checkpoints); the TrackMate overlay rendering (guarded by a headless check, skipped headless); Coloc 2's output in the GUI session (in batch mode its table went to the console, not the Log, so recipe B4b's summary line was empty); spoken end-to-end timing.
- **Left out**: the C. elegans 3D+T sample from `samples.fiji.sc` (94 MB) was tried for real cell tracking; 3D LoG detection over 41 × 17 planes took over ten minutes headless, too slow for a live demo, so it is not downloaded by the script. Coloc 2 via `fiji_run_command` with its dialog was not exercised.
