# Fiji setup, launcher and bridge

Luma's second application is Fiji (ImageJ2 2.18.0 with ImageJ 1.54p). The contract both halves implement is [docs/FIJI-PROTOCOL.md](FIJI-PROTOCOL.md). This page covers the pinned download, the Java bridge build, how the launcher starts the bridge, how the tests run, and what was verified in the real application.


Headless and batch Fiji processes (`-batch`, `--headless`, `-macro`, `-eval`, `--run`, or `java.awt.headless=true`, as the contract tests and recipe runs use) never auto-start a bridge, so they cannot fight the interactive instance for port 49557 or flash a window; `-Dluma.bridgePort=<port>` opts one in deliberately.
## Install (idempotent)

```bash
python3 script/setup-fiji.py
```

- Downloads the official macOS arm64 build pinned in `script/dependencies.json` (`fiji`): `https://downloads.imagej.net/fiji/archive/latest/20261013-1517/fiji-latest-macos-arm64-jdk.zip`, SHA-256 `7bf4a0ebbb308a9cd3328f59b81edb4e680100bff130277ae8de95f0f2416f02`, 669 MB. The published `.sha256` file next to it carries the same digest. The `stable` channel ships no macOS arm64 build at all, so the pinned, immutable `archive/latest/<build>` URL is used instead of the moving `latest/` alias.
- Verifies the hash before anything is installed, extracts into `.local/fiji/Fiji.app` and records the archive hash in `Fiji.app/.luma-source-sha256` so a rerun preserves the install.
- Restores the 223 symlinks that Python's `zipfile` writes as text files (the bundled Zulu JDK 21 needs `bin -> zulu-21.jdk/Contents/Home/bin`; without this the launcher reports "No matching Java installations found"). The repair pass runs on every invocation and is safe while Fiji is running.
- Needs `.local/jdk` from `python3 script/setup.py` first: the bridge compiles with that JDK 25 (`--release 21`), Fiji itself runs on its bundled Java 21.

Nothing inside `Fiji.app` is modified except one symlink, `plugins/luma-fiji-bridge.jar -> dist/luma-fiji-bridge.jar`, created by `script/run-fiji.sh`. Fiji's updater rewrites `db.xml.gz` and `.checksums` on its own when it checks update sites.

## What the 2026 distribution looks like

| Item | Location |
|---|---|
| Native launcher (Jaunch) | `Fiji.app/Fiji.app/Contents/MacOS/fiji-macos-arm64` (also `Fiji.app/fiji` shell wrapper) |
| Launcher config | `Fiji.app/config/jaunch/fiji.toml`, `jvm.toml` |
| Core jars | `Fiji.app/jars/ij-1.54p.jar`, `imagej-2.18.0.jar`, `imagej-legacy-3.0.0.jar`, `scijava-common-2.101.0.jar`, `gson-2.14.0.jar` |
| Bundled Java | `Fiji.app/java/macos-arm64/zulu21.42.19-ca-jdk21.0.7-macosx_aarch64` |
| Plugin class path | `jars/*.jar`, `jars/*/*.jar`, `plugins/*.jar`, `plugins/*/*.jar` (all on the JVM class path) |

There is no `Contents/MacOS/ImageJ-macosx` any more; the ImageJ Launcher was replaced by Jaunch.

### Passing JVM options and running a command at startup

Verified on 17 October 2026 (`--dry-run` shows the exact `java` command line; `--debug` traces argument handling):

- `-Dname=value` anywhere on the command line goes straight to the JVM (`-Dluma.root=…` is how the bridge finds `.local/state/bridge-token` when the environment variable is absent).
- ImageJ 1.x's single-dash options are handled by `imagej-legacy` (`LegacyCommandline`): `-run "Luma Bridge" silent` runs the ImageJ1 command after the GUI is up, `-eval 'run("Luma Bridge", "silent");'` does the same through a macro, `-macro file.ijm`, `-batch file.ijm`. Both `-run` and `-eval` were verified to start the bridge.
- Jaunch's documented double-dash `--run <plugin>` is passed to `net.imagej.Main`, whose SciJava `RunService` only accepts class names or script files; with a menu label it logs `Ignoring invalid argument: --run` and does nothing.
- `--headless` adds `-Djava.awt.headless=true`; `--allow-multiple` stops ImageJ from handing the arguments to another Fiji process (for example a headless macro run) and quitting.
- Do not pass `--java-home` for the GUI: the SciJava app launcher then relaunches Fiji on the bundled Java with the main arguments dropped, so `-run` never fires. The bridge jar targets Java 21 for exactly this reason.
- `StartupMacros.fiji.ijm` and `macros/AutoRun/*.ijm` would also work but mean editing `Fiji.app`; they are not used.

The jar also registers a SciJava service (`LumaBridgeService`, tagged `SciJavaService` because `net.imagej.ImageJ` only instantiates `SciJavaService`/`ImageJService` services eagerly; a plain `Service` is indexed but never created). Its `initialize()` returns immediately and a daemon thread starts the HTTP server by reflection once imagej-legacy reports ready, so no launcher argument is required and ImageJ1 classes are never loaded before the legacy patcher runs. It logs `[Luma] …` lines to stderr (the launcher log). The token comes from `LUMA_BRIDGE_TOKEN`, else `<-Dluma.root or LUMA_ROOT>/.local/state/bridge-token`, else the repository that owns the symlinked jar (`plugins/luma-fiji-bridge.jar -> <root>/dist/luma-fiji-bridge.jar`). `-run "Luma Bridge"` and the Plugins > Luma menu entries remain as manual fallbacks; a second start is a no-op.

## Build, run, test

```bash
./script/build-fiji-bridge.sh     # dist/luma-fiji-bridge.jar + .local/fiji-bridge-build.json (sourceSha256 stamped into the jar)
./script/run-fiji.sh              # symlink jar, export LUMA_BRIDGE_TOKEN/LUMA_ROOT, start Fiji, log to .local/fiji.log
./script/test-fiji-bridge.sh      # contract tests, no running Fiji needed
```

- `build-fiji-bridge.sh` compiles `adapters/fiji` against `Fiji.app/jars/*` with `-proc:full` so SciJava's annotation processor writes `META-INF/json/org.scijava.plugin.Plugin`; it refuses to package a jar without that index. The jar is replaced atomically; a failed compile leaves the previous jar.
- `run-fiji.sh` refuses while an interactive instance of this launcher is running (headless/batch runs are ignored) or while `127.0.0.1:49557` is in use. Startup to a listening bridge took 60–105 s on the demo Mac (Fiji reads its update sites first).
- The bridge listens on `127.0.0.1:49557`: `GET /health`, `POST /execute` with `Authorization: Bearer <token>` (`.local/state/bridge-token`, the same file the QuPath bridge and the gateway use). Browser origins are rejected.
- `test-fiji-bridge.sh` runs `BridgeIdentityContractTest` and `GeometryContractTest` headless, then `HeadlessContractTest` in a windowless ImageJ (`ImageJ.NO_SHOW`; ImageJ's ROI Manager is a `Frame` even when hidden, so this one needs a window session but opens nothing and sets `apple.awt.UIElement`). It drives the real HTTP server on port 49597 through a synthetic 8-bit image with 12 discs: policy (token, approval, stale context), ROI, measure, threshold, Analyze Particles, `segment_cells`, checkpoints, compare, restore, undo, filters, conversions, recorder/journal, `save_workflow`, snapshot, commands, macros with the dialog watcher, `ui`/`ui_action` on a real dialog, and close.

## Behaviour notes

- Every data/code operation first brings the active image window to the front and writes `Luma: segmenting cells…` (or measuring, thresholding, counting particles, applying the filter, running <command>, …) into Fiji's status bar, cleared when the call returns; `status` (read) returns that text plus `analysisRunning` (ImageJ's progress bar exposes no value).
- `analyze_particles` and `segment_cells` draw the detections into the image overlay: magenta outlines, stroke 2, numbered labels when there are at most 200, so results are visible even with the ROI Manager closed.
- `open_path`, and `run_command` when it opened a new image (samples, importers), zoom the new window so the image fills at least 60% of the screen height (ImageJ zoom levels, never above 32×), centre it on screen and return a whole-image `highlight`.
- Every highlight payload carries `flash: true` for the companion's outline animation.

- Mutating operations (`data`/`code` in the protocol table) need `approved:true` and an `expectedContext` equal to the current `contextKey`; `checkpoint` is the exception (always allowed). `stack` with `project` counts as data.
- Before `add_to_manager`, `measure`, `clear_results`, `threshold`, `analyze_particles`, `segment_cells`, `filter`, `convert`, `run_command`, `run_macro`, `run_script` and `ui_action` the bridge copies the active image's pixels, ROI, overlay, ROI Manager and Results table (512 MiB cap per checkpoint, 1 GiB in total, six kept). `restore` puts them back; `undo` restores the newest checkpoint of the active image and falls back to ImageJ's single-step Undo. Files, windows and display settings are never restored.
- `segment_cells` picks the nuclei channel when none is given: a blue-LUT channel, else a channel labelled DAPI/Hoechst/nuc, else the current one (`channelChosenBy` says which rule applied); `context.image.channels` lists each channel's LUT colour name and label. With an active area ROI it keeps only particles whose centroid lies inside it, restores the ROI and reports `scope:"selection"` with `excludedOutsideSelection`.
- `context.pointer` reports where the researcher is pointing: `screenX/screenY` (global), `x/y` in image pixels and `overCanvas:true` while the mouse is over the image canvas, else `lastX/lastY` from the canvas's last cursor location, so "these two cells" can resolve to the objects under the pointer.
- Bridge measurements never trigger ImageJ's modal "Save N measurements?" prompt: `Analyzer.setUnsavedMeasurements(false)` is called before each Measure/Analyze Particles run, because that prompt would park the call in a nested event loop (observed live when a measure asked for a different column set). The checkpoint taken before the operation preserves the previous Results rows.
- `images` lists every image window on screen (including ones ImageJ's own list missed) with `kind:"image"|"plot"`, plots last; `stack` accepts `slice`/`channel`/`frame` as numbers or `next`/`previous`/`first`/`last`.
- `threshold`/`segment_cells` default `dark` to "not an inverting LUT", which selects the objects both on fluorescence images and on the inverting-LUT Blobs sample.
- `run_command`, `run_macro` and `run_script` run off the event thread with a watcher: if an AWT `Dialog` appears and stays for 400 ms the call returns `{dispatched:true, completed:false, dialog:{…controls with ids and screen bounds…}}`; `ui_action` stays allowed while that dialog is open so it can be filled and confirmed. New image windows are results, not dialogs. After 20 s the call returns `running:true` and `fiji_context.analysisRunning` stays true until the command finishes.
- `open_path` reads the file on a watched worker thread (ImageJ reader, then Bio-Formats), so a whole-slide import never blocks the event thread; a reader dialog comes back as `dialog`, a read longer than 100 s as `running:true` with `analysisRunning` set until the image appears. Showing, zooming and the highlight still happen on the event thread.
- `run_command` accepts legacy labels: "Blobs (25K)" resolves to the 1.54 label "Blobs" (the result reports both).
- `recorder` returns the Macro Recorder text when that window is open, otherwise the bridge's own journal of macro-equivalent lines; `save_workflow` writes it to `.local/workflows/<slug>.ijm`.
- `save_image` and `export_results` default to `.local/exports/`; an explicit absolute `path` is honoured. Nothing is written next to the source image unless asked.
- Highlights are global screen points (top-left origin) computed from `ImageCanvas.getLocationOnScreen()` and the canvas `srcRect`/magnification; targets are clipped to the canvas with an 8 px minimum. `select_roi`, `measure`, `threshold`, `analyze_particles`, `segment_cells` and `highlight` zoom to their target (ImageJ's own "To Selection" algorithm, padded 30%) when it would cover less than 12% of the canvas or lie outside the displayed region; results report `focused:true` when that happened.
- Without a window (tests, headless) every highlight is `{}` and `viewer` is `null`; nothing throws for display reasons.

## Biology operations (added by the biology-platform session, same contract)

`track_objects`, `intensity_over_time` (Tracking.java: per-frame detection and nearest-neighbour linking in the bridge, no TrackMate dependency), `tumor_region`, `colocalize`, `classify_intensity`, `object_stats` (Analysis.java), `make_macro`, `run_workflow`, `capture_macro`, `workflows` (Macros.java: compose and run macros without dialogs, run saved `.local/workflows/*.ijm` on the active image or a folder, capture the Macro Recorder, list saved workflows). They follow the same risk classes (`colocalize`, `object_stats`, `workflows` read; the rest data/code with approval, context binding and checkpoints) and have their own contract tests (`TrackingContractTest`, `AnalysisContractTest`, `MacrosContractTest`) run by `test-fiji-bridge.sh`.

## Verified in the real application (17 October 2026)

Fiji started with `./script/run-fiji.sh`; calls made with `curl`/Python against `127.0.0.1:49557` with the token:

| Call | Result |
|---|---|
| `context` | `application:"Fiji"`, `version:"1.54p99"`, `guiVisible:true`, 51 operations declared (40 from the protocol, `status`, and the ten biology operations below) |
| `run_command {"name":"Blobs (25K)"}` | resolved to `Blobs`, `completed:true` in 1.2 s, image `blobs.gif` 256×254 8-bit, highlight at the canvas rectangle |
| `segment_cells` | 67 cells, mean area 326.7 px², Default threshold 126–255, 67 ROIs in the ROI Manager, 67 ellipse targets |
| `measure {"scope":"manager"}` | 67 new rows, mean intensity 190.3 (146.3–220.2), circularity 0.84 mean |
| `highlight {"rois":"manager"}` | 67 screen targets with ROI names, pointer at their centre |
| `select_roi` 24×20 rectangle | `focused:true`, the view zoomed to 16× on it (`viewer.magnification:16`), highlight 384×320 screen px |
| `measure {"scope":"roi"}` | one row, area 480 px², mean 166.4, min 40, max 248 |
| `undo` | restored the "Before measure" checkpoint (ROI Manager 67, Results 134 rows) |
| `ui` | Fiji toolbar, ROI Manager and Results windows with control ids and screen bounds |
| `zoom {"fit":true}` | viewer `magnification:1`, `srcRect` 256×254 |
| `snapshot` | PNG of the live canvas saved under `.local/evidence/fiji-*.png` |
| `recorder` | journal with `run("Blobs");`, the segmentation lines, `roiManager("Measure");` |
| `save_workflow {"name":"Blobs segmentation demo"}` | `.local/workflows/blobs-segmentation-demo.ijm` |

Not verified in the live app: `run_script` with SciJava languages (groovy/python), `open_path` with Bio-Formats fallback, `stack` projection on a real stack, `save_image`/`export_results` paths beyond the exports directory. They are implemented and the macro/script paths share the tested watcher.
