# Fiji bridge contract

Penny's second application is Fiji (ImageJ2 + ImageJ1 compatibility). The same voice design applies: the companion bar, Transcribe → OpenAI on Bedrock → Polly, instant routes, acknowledgments, auto-approved journaled actions, the flying cursor, and the desktop (Accessibility) tools. Only the application adapter changes. This file is the contract both halves build against.

## Processes and selection

- `LUMA_APP=fiji` (default stays `qupath` until the Fiji profile is verified) selects the app profile in the gateway: tool list, instructions, skill, knowledge set, fast path, bridge port, launcher script. `/health` reports `app`.
- The Java bridge is a Fiji plugin JAR in `adapters/fiji` (ImageJ1 `PlugIn` + SciJava `@Plugin(type = Command.class, menuPath = "Plugins>Penny>Bridge status")`), loaded from the Fiji installation under `.local/fiji/Fiji.app/plugins/luma-fiji-bridge.jar` by the dev launcher `script/run-fiji.sh`, which starts Fiji with `LUMA_BRIDGE_TOKEN`, `LUMA_ROOT` and `-Dluma.root` and auto-starts the bridge (startup macro or `--run "Penny Bridge"`), writing stdout/stderr to `.local/fiji.log`.
- HTTP server on `127.0.0.1:49557`, `POST /execute` with `Authorization: Bearer <token>` (token from `.local/state/bridge-token`, same file as QuPath), body `{operation, arguments, expectedContext?, approved?, expectedControl?}` → `200 {result}` or `4xx/5xx {error}`. Read operations may run off the EDT; anything touching ImageJ windows runs on the EDT via `EventQueue.invokeAndWait`.
- `context` returns `{contextKey, application:"Fiji", version, adapter:{adapterVersion, protocolVersion:1, sourceSha256, operations:[…]}, image, images, roi, overlay, measurements, viewer, counts, lastDispatch, analysisRunning}`. `contextKey` is a SHA-256 over active image id + title + stack position + ROI bounds/type + overlay size + results-table row count + ROI Manager count + open-image list, so the gateway's "context changed" check works unchanged.

## Operations (all names are the Java `operation` strings; the gateway tool is `fiji_<name>`)

| Operation | Risk | Arguments | Result (JSON) |
|---|---|---|---|
| `context` | read | | as above. `image`: `{id, title, width, height, channels, slices, frames, bitDepth, type, calibration:{pixelWidth, pixelHeight, unit}, path?, changes:boolean}`; `images`: `[{id,title,active}]` (bounded 50); `roi`: `{type, x, y, width, height, name?, points?}` or null; `viewer`: `{magnification, srcRect:{x,y,width,height}, canvasScreen:{x,y,width,height}}` (canvas location on screen via `getLocationOnScreen`, top-left origin); `counts`: `{overlay, roiManager, resultsRows}` |
| `images` | read | | `{images:[{id,title,width,height,active,changes}]}` |
| `open_path` | code | `path` | opens with `IJ.openImage`/`IJ.open` (Bio-Formats fallback via `IJ.run("Bio-Formats Importer", …)` when `IJ.openImage` returns null); result `{opened:true, image, highlight}` |
| `activate_image` | view | `id` or `title` | brings a window to front |
| `select_roi` | view | `type:rectangle|oval|polygon|line|point|none`, `x,y,width,height` or `points:[[x,y],…]`, `name?` | sets the active ROI (image pixels); result `{roi, highlight}` |
| `roi_manager` | read | `limit?` | `{total, rois:[{index,name,type,x,y,width,height}]}` |
| `add_to_manager` | data | | adds current ROI to ROI Manager → `{index, total}` |
| `measure` | data | `measurements?:[…names]`, `scope:roi|manager|all` | configures `Set Measurements` from names (area, mean, min, max, perimeter, shape, integrated, median, centroid, feret, …), runs Measure (or ROI Manager multi-measure) → `{rows, columns, lastRow:{…}, summary:{area:{min,max,mean}, mean:{…}}, highlight}` |
| `results` | read | `limit?`, `offset?` | the Results table rows (bounded) |
| `clear_results` | data | | clears Results |
| `threshold` | data | `method?:Otsu|Default|Huang|Li|…`, `dark?:boolean`, `lower?,upper?` | auto or manual threshold on the active image/ROI (`setAutoThreshold`), returns `{lower, upper, method, highlight}` |
| `analyze_particles` | data | `minSize?, maxSize?, minCircularity?, exclude?:boolean, include?:boolean, show?:Outlines|Masks|Nothing, addToManager?:boolean(default true), summarize?:boolean` | runs Analyze Particles on the thresholded image → `{count, totalArea, averageSize, highlight}` (highlight outlines up to 120 particles from the ROI Manager) |
| `segment_cells` | data | `channel?`, `method?`, `minSize?`, `watershed?:boolean(default true)` | convenience: duplicate → 8-bit → (optional) Gaussian 1 → auto threshold → fill holes → watershed → Analyze Particles (add to manager, show nothing) on the original → `{count, highlight, steps}` ; records a macro-equivalent in the result |
| `filter` | data | `name:gaussian|median|mean|unsharp|subtract_background|enhance_contrast|invert|smooth|sharpen|find_edges`, `radius?`, `sigma?`, `rolling?`, `saturated?` | runs the ImageJ filter on the active image/ROI |
| `convert` | data | `to:8-bit|16-bit|32-bit|RGB|Composite|Grayscale`, `splitChannels?:boolean` | |
| `duplicate` | data | `title?`, `roiOnly?` | → `{image}` |
| `zoom` | view | `magnification?` or `fit:boolean` or `in|out`, `x?,y?` (image px centre) | sets canvas magnification/`srcRect`; result `{viewer, highlight}` |
| `highlight` | view | `rois:manager|active|all`, `x,y,width,height`, `indices?`, `label?`, `limit?` | screen rectangles of ROIs/particles for the companion cursor; auto-zooms when targets would be invisible (same semantics as QuPath) |
| `overlay` | view | `show?:boolean`, `fromManager?:boolean`, `clear?:boolean`, `labels?:boolean` | `{overlay:count}` |
| `display` | view | `min?,max?`, `auto?:boolean`, `lut?:name`, `channel?` | brightness/contrast/LUT |
| `stack` | view | `slice?`, `channel?`, `frame?`, `project?:max|sum|avg` | navigate or project stacks (project is `data`) |
| `commands` | read | `query` | searches ImageJ1 menu commands (`Menus.getCommands()`) and SciJava `CommandService` commands → `[{name, menuPath, source}]` |
| `run_command` | code | `name`, `options?` | `IJ.run(imp, name, options)` on the EDT, with a dialog watcher: if a dialog opens and stays, return `{dispatched:true, dialog:{title, controls:[…]}}` via `ui` |
| `ui` | read | | AWT/Swing windows and controls (buttons, text fields, checkboxes, choices, sliders) with ids and screen bounds, for `GenericDialog`s and plugin windows |
| `ui_action` | code | `nodeId`, `action:fire|set_text|select|toggle`, `value?` | operate a control seen by `ui` |
| `run_macro` | code | `code`, `argument?` | `IJ.runMacro` with result/log text (bounded) |
| `run_script` | code | `language:groovy|python|javascript|ijm`, `code` | SciJava `ScriptService` run, returns stdout/log and return value as text |
| `recorder` | read | | current Macro Recorder text if open, else the bridge's own journal of `IJ.run` calls it made (`{steps:[…], macro:"…"}`) |
| `save_workflow` | code | `name` | writes the recorded macro to `<LUMA_ROOT>/.local/workflows/<slug>.ijm` → `{path, steps, macro}` |
| `save_image` | code | `path?`, `format?:tiff|png|jpeg` | saves the active image (default next to source or `.local/exports`) |
| `export_results` | code | `path?` | saves Results table CSV to `.local/exports` → `{path, rows}` |
| `checkpoint` | data | `label?` | snapshot of active image pixels + ROI + overlay + ROI Manager (bounded 512 MiB) → `{id,label}` |
| `checkpoints` | read | | list |
| `compare` | read | `checkpointId` | counts/diff summary |
| `restore` | data | `checkpointId` | restores pixels/ROI/overlay/manager of that image |
| `close` | data | `id?`, `all?` | closes image(s) without saving (changes flag reported) |
| `snapshot` | read | | PNG of the active canvas (`ImagePlus.flatten()` or canvas paint) |
| `log` | read | `limit?` | tail of the ImageJ Log window |
| `plugins` | read | `query?` | installed plugin commands under `Plugins>` |
| `undo` | data | | `IJ.run("Undo")` when available, else restore last checkpoint |

Mutating operations (`data`/`code`) require `approved:true` + `expectedContext` exactly like QuPath; the bridge takes a `checkpoint("Before <op>")` for `data` ops that change pixels/ROIs (best-effort; skipped with a note when too large). Every result that targets something visible includes `highlight: {label, targets:[{x,y,width,height,shape}], pointer:{screenX,screenY}}` in global screen points, top-left origin, computed from `ImageCanvas.getLocationOnScreen()` + `screenX(x)`/`screenY(y)`. The viewer follows the work: `select_roi`, `measure`, `threshold`, `analyze_particles`, `segment_cells` and `highlight` zoom to the target when it would be invisible (<12% of the canvas) or off-screen.

`BridgeIdentity` for Fiji declares the operation list; `bridge-readiness.ts` compares it with the Fiji tool list (`fiji_*`) when `LUMA_APP=fiji`.

## Launcher

`script/setup-fiji.py` downloads the official Fiji macOS arm64 distribution (latest stable zip from downloads.imagej.net, SHA-256 recorded in `script/dependencies.json`) into `.local/fiji/Fiji.app`, unchanged. `script/build-fiji-bridge.sh` compiles `adapters/fiji` against the jars in `Fiji.app/jars` (ij-*.jar, scijava-common, imagej-common, …) with the local JDK and writes `dist/luma-fiji-bridge.jar` plus `bridge-build.properties`. `script/run-fiji.sh` symlinks the jar into `Fiji.app/plugins/`, exports the token/root, starts `Fiji.app/Contents/MacOS/ImageJ-macosx` (or the `fiji` launcher) with `--run "Penny Bridge"` (fallback: `-eval 'run("Penny Bridge");'`) and refuses while Fiji is running. `script/test-fiji-bridge.sh` runs headless contract tests (`ij.ImageJ` headless with a synthetic image: measure, threshold, particles, ROI, checkpoint/restore, highlight geometry, identity).

## Voice specifics for Fiji

Instant routes (fast path): "zoom in/out", "fit to window", "show/hide overlay", "next/previous slice", "undo". Acknowledgments: `fiji_segment_cells` "Segmenting cells.", `fiji_measure` "Measuring.", `fiji_threshold` "Thresholding.", `fiji_analyze_particles` "Counting particles.", `fiji_filter` "Applying the filter.", `fiji_open_path` "Opening it.", `fiji_run_command` "On it.", `fiji_select_roi` "Selecting the region.", `fiji_zoom` "Zooming." Local sentences: segment/particles → "Found N cells, mean area X unit²."; measure → "Mean intensity X over N regions."; threshold → "Thresholded at L–U (method)."; open → "Opened <title>, W×H."

Demo script (Fiji): "Penny, open the blobs sample" (File>Open Samples>Blobs via `run_command("Blobs (25K)")`), "segment the cells", "how many are there and what's the mean area?", "make the threshold stricter", "go back", "measure the intensity in this region" (user draws an ROI), "save this as a macro".
