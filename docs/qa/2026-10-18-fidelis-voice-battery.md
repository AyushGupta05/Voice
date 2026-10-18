# Fidelis voice battery, 18 October 2026

Live end-to-end verification of the Penny × Fidelis integration on `integration/fidelis-penny` (macOS arm64, pinned
Fiji 1.54p99, a second Penny gateway on `127.0.0.1:49565` with `LUMA_APP=fidelis`, Fidelis started by
`FIDELIS_PENNY_GATEWAY=ws://127.0.0.1:49565/events ./script/run-fidelis.sh`, bridge on 49558). The live stand-alone demo
(gateway 49555, Fiji bridge 49557, Swift companion) was never touched.

Requests were sent as the gateway's voice turns would be: typed over the `/events` websocket with `spoken: true`
(`.local/qa/drive.ts`, the same path as `script/text-drive.ts`), then the state read back from the bridge (`context`,
`history`, `macro`). **Voice through AWS Transcribe → Bedrock → Polly could not be run:** the user's `aws login` session
expired at about 00:04 UTC (`aws sts get-caller-identity` → `ExpiredToken`), before the battery reached the voice pass,
and the funding policy forbids logging in from a session. Everything that needs a model round (Bedrock) stopped working
at the same moment, so the rows below say which path each request took. Timings are from the first socket message to
`text_result`.

## Summary

| # | Request | Path | Result | Latency | State afterwards | Verdict |
|---|---|---|---|---|---|---|
| 1 | Open the fluorescent cells image | instant (`fidelis_find_images` → `fidelis_open_path`) | "Opened FluorescentCells, 512 by 512." | 0.47 s | doc-1 512×512, 3 channels, 8-bit; history 1 (Open, NL_SPOKEN) | PASS after fix (first run asked "Which folder…": the worktree's image search did not cover the enclosing checkout's data folder) |
| 2 | What am I looking at? | instant (from `context`) | "FluorescentCells: 512 by 512, 8-bit; 3 channels." | 9 ms | unchanged | PASS |
| 3 | Find all the nuclei | instant `fidelis_segment_cells {}` | before the fix: "Found 172 nuclei, mean area 855 square pixels." (the displayed red mitochondria channel was segmented); after the fix (protocol-tested on a 2-channel composite, not yet re-run on FluorescentCells, see Blockers): the blue-LUT channel 3 is chosen, the sentence says "on channel 3 (blue)" | 2.6 s | ROIs on the **original** (doc-1), mask on a duplicate, 9 history steps all `NL_SPOKEN` with `transcriptRef` = the gateway task id; plan card showed the steps; flash payload had 120 of 172 targets inside the canvas rectangle | PASS with the channel fix pending a live re-run |
| 4 | How many nuclei are there? | instant `fidelis_rois {limit:1}` | "172 nuclei." | 8 ms | unchanged | PASS |
| 5 | Remove the tiny ones | model | "What maximum area defines “tiny” cells?" (asked instead of acting) | 2.6 s | unchanged | FAIL → instructions now say: `fidelis_object_stats`, then `fidelis_segment_cells` again with `minSize` a quarter of the median area; never ask what tiny means. Not re-run (model unavailable). Fidelis has no delete-small-ROIs operation; a re-segmentation with a size floor is the honest route |
| 6 | What is the mean intensity of the nuclei? | model → `fidelis_measure {scope: rois, measurements: [mean]}` | "Mean intensity 82.4 over 172 regions." measured on the original pixels (`document.binary: false`), one Measure per ROI (172 history steps) | 4.3 s | results 344 rows, history 183 | PASS for the pixels; measured on the displayed channel 1 because the objects came from channel 1. After the fix `measure` reads the segmented channel and reports `channel`; the sentence says "on channel 3" |
| 7 | Zoom in / Fit to window | instant `fidelis_zoom` | "Zooming in." / "Fitting to the window." | 9–10 ms | magnification 0.75 → 1 → fit | PASS (`viewer.canvasScreen` read back null right after the 172-step measurement: the 500 ms EDT wait timed out while the canvas repainted; it returned on the next call) |
| 8 | Make it brighter | instant `fidelis_display {min 0, max 178.5}` | "Brighter." | 11 ms | display range 0–179 on channel 1 | PASS; the strip's plan card showed `Plan · FluorescentCells.tif ✓ Display range 0–178.5 … done` (screenshot) |
| 9 | Show me the size distribution | model → `fidelis_object_stats` | "172 objects, mean area 855 square pixels, median 77.5 square pixels." | 3.0 s | unchanged | PASS (Fidelis has no histogram window for this; the numbers are the Results table's) |
| 10 | Undo | instant `fidelis_undo` | "That did not work: nothing to undo for this document (no snapshot: virtual stack, over budget or no data action yet)." | 12 ms | unchanged | FAIL by engine design: Fidelis's UNDO reverts pixel snapshots of the focused document; segment_cells leaves the original's pixels untouched (`undoSteps: 0`) and a display-range step keeps no snapshot, so there was nothing the engine could revert. See Blockers |
| 11 | Show me the workflow | model → `fidelis_macro` | model response "incomplete or blocked" after 52 s: the 375 kB macro (172 per-ROI Measure lines) was sent to the model whole | 52.6 s | unchanged | FAIL → fixed: instant route answers locally ("The macro has N lines from N steps; it is in the History and macro tab, ready to export."), and `resultForModel` bounds any macro the model sees to its first 40 lines with its size |
| 12 | Convert it to a mask with a black background | model → `fidelis_segment_cells {dark:false}` then `fidelis_filter {invert}` | "Filter applied." (439 "cells", the original inverted) | 13.5 s | a second `-mask` tab, ROI list replaced | FAIL → fixed: instant route `fidelis_command ij1:Process>Binary>Convert to Mask {black}`; with `black` equal to the current BlackBackground preference it runs at once, a differing polarity waits for "yes" (the bridge's value-aware rule). Not re-run live |
| 13 | Yes (nothing pending) | model | "Done." | 2.1 s | unchanged | note: a bare assent without a pending plan goes to the model, which answered "Done." (no tool ran) |
| 14 | Run Gaussian blur with sigma 2 | model → `fidelis_commands` → `fidelis_command {Gaussian Blur..., sigma 2}` | "Ran Gaussian Blur." | 2.7 s | history +1, `run("Gaussian Blur...", "sigma=2")` | PASS |
| 15 | Open the Coloc 2 plugin | model → `fidelis_commands` → `fidelis_command {Coloc 2}` → `awaiting_confirmation` (AC4) | "Ready to run 1 step; say yes to go ahead." | 2.3 s | pending plan; card waiting | PASS (Fidelis policy: a legacy plugin without undo waits) |
| 15b | Yes | instant `fidelis_confirm` → dialog → model `fidelis_dialog_action {press OK}` | "Done." while Coloc 2's **Save as PDF** file chooser was still open (the model pressed OK with the mask as channel 2) | 8.3 s | plan running, OS file chooser open | FAIL → fixed in the bridge (file choosers a command opens on its way are cancelled by the plan thread, `cancelledFileDialog: true`, protocol-tested with Coloc 2) and in the instructions ("open the plugin" leaves its dialog for the researcher and names its fields; never press OK with inputs that make no sense). The stray chooser was cancelled by hand through `cancel_plan` |
| 16 | Save the macro / export | not run (model unavailable) | — | — | — | not tested; `fidelis_macro` gives the text, Fidelis's History panel exports `.ijm` |
| 17 | Apply this to the whole folder | not run (model unavailable) | — | — | — | not tested; Fidelis has no batch operation, the honest answer is a macro the researcher runs per file |
| 18 | Open the hela cells image and tell me if the channels colocalise | not run (model unavailable) | — | — | — | not tested |

Flash outlines: every highlight payload carried screen targets inside the reported canvas rectangle. The on-canvas
outlines could not be captured in a screenshot without raising the Fidelis window over the researcher's own windows
(the earlier integration screenshots `penny-flash-onscreen.png` are the evidence for the painter).

MCP: `tools/list` over stdio with `LUMA_APP=fidelis LUMA_GATEWAY_PORT=49565` lists the 33 `fidelis_*` tools and
`fidelis_context` answers with the live context (`.local/qa/mcp-list.ts`).

## Act 2 script by voice (18 Oct, after the undo work; AWS session restored)

Real voice turns through my gateway (`script/voice-pipeline-smoke.ts --say`: Polly speaks the line, AWS Transcribe hears
it, the agent acts, Polly reads the answer), on my Fidelis instance from an empty workspace. "Speech end → reply audio"
is the latency the researcher hears.

| Line | Heard | Tools | Reply | Speech end → reply audio | Fidelis afterwards | Verdict |
|---|---|---|---|---|---|---|
| "Open the blobs image." | "Open the blob's image." | `fidelis_open_path` (blobs.gif) | "Opened blobs, 256 by 254." | 1.07 s | blobs.gif open, History 1 (Open, NL_SPOKEN) | PASS |
| "Find all the nuclei." | "Find all the nuclei" | `fidelis_segment_cells {}` | "Found 67 nuclei, mean area 327 square pixels." | 1.06 s | 67 ROIs on blobs.gif, 67 Results rows, 10 History steps NL_SPOKEN with the task's transcriptRef, mask closed, plan card ran | PASS |
| "Go back." | "Go back" | `fidelis_undo {}` | "Undid: 67 objects → 0, Results cleared." | 2.81 s | 0 ROIs, 0 rows, History 11 (Undo NL_SPOKEN) | PASS (engine undo of ROI-list/Results changes, below) |
| Export .ijm / Open in editor | — | `fidelis_macro` (what the panel exports) | 30 lines from 11 steps; the undone copy appears as `// step 9 undone: ROI_LIST_ADD` | — | — | PASS by replay below |
| "Run this macro." | "Run this macro." | `fidelis_macro` → `fidelis_run_macro {code}` | "Ready to run 1 step; say yes to go ahead." | 1.40 s | pending plan, plan card waiting with Confirm | PASS (paused) |
| "Yes." | "Yes," | `fidelis_confirm {planId}` | "Ran the macro: 67 result rows, 1 new image." | 1.14 s | blobs-1.gif opened by the replay, 67 rows (the same count), History 21 with the macro's steps as MACRO/NATIVE children of the NL_SPOKEN CODE step; no dialog open | PASS |

Before the fixes the first "Run this macro." went to the model, which asked "Which macro would you like me to run?"; the
instant route now reads the session macro itself. The earlier typed-as-spoken pass of the same script is kept below.

### Undo of ROI-list and Results changes (engine, 18 Oct)

The engine's undo stack (pixel snapshots) now also records the ROI list before ROI_LIST_ADD / DELETE / RENAME and
Analyze Particles with `add`, and what Measure, Analyze Particles with `display` and Clear Results do to the Results
table. UNDO rebuilds the ROI list, removes the rows that action appended (per action: other documents' rows keep their
place and attribution) and puts back rows it cleared; REDO re-runs the step. segment_cells' copy of the objects names
its Analyze Particles (`resultsOf`), so one "go back" removes the objects and their rows together; `undoSteps` is 1.
`EngineUndoRoiTest` (4), `EngineScienceTest`, `FidelisBridgeJourneyTest` and `FidelisBridgeTest`: 42 tests, 0 failed
(one JVM). Limits: a redo of the copy fails once its mask was closed; a second segmentation's undo brings the first
segmentation's objects back (its rows were cleared by the second Analyze Particles and are gone).

## Act 2 script (integrator's demo order), typed as spoken turns (before the undo work)

| Line | Result |
|---|---|
| "Open the blobs image." | PASS, 115 ms: `fidelis_open_path` of `blobs.gif` from Penny's data folder; history Open `NL_SPOKEN`. Needed a fix: "blobs" was deliberately left to the model (ImageJ sample command); it now opens the file on disk instantly |
| "Find all the nuclei." | PASS, 550 ms: 67 nuclei, mean area 327 px² (the verified number); ROIs on `blobs.gif`, Results 67 rows, 10 history steps `NL_SPOKEN` with `transcriptRef` = the task id; the mask `blobs-mask` is closed again (no extra tab) |
| "Go back." | FAIL: `fidelis_undo` → engine "nothing to undo for this document (no snapshot …)". The segmentation never changed the original's pixels, and the engine's UNDO only reverts pixel snapshots; ROI_LIST_ADD, Results rows and the closed mask have no undo record. The History panel's own Undo button hits the same engine rule |
| Export .ijm / Open in editor | the macro from `fidelis_macro` is the one the panel exports: 166 lines from 10 steps (open, duplicate, blur, threshold, mask, fill, watershed, particles, ROI copy, close). Replay parity (67 objects, 21886 px²) is verified by `FidelisBridgeJourneyTest`, not re-run here |
| "Run this macro." → "Yes." | not testable: needs the model (`fidelis_macro` then `fidelis_run_macro {code}`); the gateway's protocol tests cover the pause ("Ready to run 1 step; say yes to go ahead.") and the "yes" route |

The Penny strip's transcript line could not be checked: it shows the workspace's own client's conversation (Mic or
Ask Penny), and this battery drove a separate client. The plan card (a bridge hook) was seen on screen.

## Fixes committed on `integration/fidelis-penny`

- `d22e6fc` `LUMA_GATEWAY_PORT` for `script/doctor.ts` and `script/text-drive.ts`; a git worktree's image search also
  covers its enclosing checkout's `.local/data` (test).
- `fb6335e` gateway: instant routes for "convert it to a mask (with a black/white background)" and "show me the
  workflow/macro"; `resultForModel` profile hook (backend) bounding a session macro; "cancel" while a plan is stopped at
  a dialog; count and intensity sentences name the channel; skipped save-dialog wording; instructions for tiny objects,
  plugin dialogs, file choosers and the closed mask; `skills/fidelis/SKILL.md` and `docs/FIDELIS-PROTOCOL.md` updated.
- `ec2c377` bridge: `segment_cells` picks the nuclear channel on multichannel images (blue LUT, then a DAPI/Hoechst/nuc
  label, else current; `channel`, `channelBy` in the result) and closes its mask again (`keepMask: true` keeps it);
  `measure` reads the channel given, else the segmented channel, and reports `channel`/`channelBy`, restoring the
  display; OS file choosers a command opens on its way are cancelled by the plan thread (`cancelledFileDialog`).
  `FidelisBridgeTest`, `FidelisBridgeJourneyTest`, `PennyCatalogueTest`: 28 tests, 0 failed (Coloc 2 end-of-command
  "Save as PDF" cancelled; `File>Save As>PDF…` still refused up front).
- `21a0624` "Open the blobs image" opens the blobs.gif on disk instantly.

`npm run check`: 250 tests, 0 failed; `python3 -I docs/tools/check_docs.py`: 0 errors.

## Blockers and what still fails

1. **AWS session expired** at ~00:04 UTC and was restored later: the Act 2 script was then run by voice (above); the
   rest of the battery (items 5, 12, 16–18 and the FluorescentCells channel re-run) still waits for a voice pass:
   `LUMA_GATEWAY_PORT=49565 npm run smoke:voice-pipeline -- --authorized-aws-speech --say="…"`.
2. ~~"Go back" / "Undo" after a segmentation fails in the engine~~ fixed (engine undo of ROI-list and Results changes,
   above); the closed mask is not reopened by the undo.
3. The nuclear-channel and segmented-channel measurement fixes are protocol-tested (2-channel composite) but the live
   FluorescentCells re-run ("find all the nuclei" → "mean intensity of the nuclei" on channel 3) is still to do once
   voice is back.
4. `measure {scope: rois}` records one History step per ROI (172 for FluorescentCells): the macro grows to hundreds of
   kilobytes and History becomes hard to read. A single multi-measure step is a bridge/engine change for the owners.
