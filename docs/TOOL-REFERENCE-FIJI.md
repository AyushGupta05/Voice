# MCP tool reference (Fiji)

Generated from `packages/gateway/src/apps/fiji-tools.ts`; this profile is active when `LUMA_APP=fiji`. Do not edit individual schemas here; regenerate after changing tool definitions. [Complete machine-readable registry](tool-schemas-fiji.json) retains every schema constraint and description. MCP discovery reports the running gateway, which may be older than these source definitions. Other applications: [QuPath](TOOL-REFERENCE.md).

All inputs are JSON objects. Unknown properties are rejected. Read and view tools run immediately. Data and code tools go through the recorded decision path: executed at once under the default `LUMA_APPROVALS=auto` policy, shown as human review cards under `review`. The approval control is absent from the MCP tool list.

| Tool | Risk | Decision path |
|---|---|---|
| [`fiji_context`](#fiji-context) | read | No |
| [`fiji_images`](#fiji-images) | read | No |
| [`fiji_open_path`](#fiji-open-path) | code | Yes |
| [`fiji_activate_image`](#fiji-activate-image) | view | No |
| [`fiji_select_roi`](#fiji-select-roi) | view | No |
| [`fiji_roi_manager`](#fiji-roi-manager) | read | No |
| [`fiji_add_to_manager`](#fiji-add-to-manager) | data | Yes |
| [`fiji_measure`](#fiji-measure) | data | Yes |
| [`fiji_results`](#fiji-results) | read | No |
| [`fiji_clear_results`](#fiji-clear-results) | data | Yes |
| [`fiji_threshold`](#fiji-threshold) | data | Yes |
| [`fiji_analyze_particles`](#fiji-analyze-particles) | data | Yes |
| [`fiji_segment_cells`](#fiji-segment-cells) | data | Yes |
| [`fiji_track_objects`](#fiji-track-objects) | data | Yes |
| [`fiji_intensity_over_time`](#fiji-intensity-over-time) | data | Yes |
| [`fiji_tumor_region`](#fiji-tumor-region) | data | Yes |
| [`fiji_colocalize`](#fiji-colocalize) | read | No |
| [`fiji_classify_intensity`](#fiji-classify-intensity) | data | Yes |
| [`fiji_object_stats`](#fiji-object-stats) | read | No |
| [`fiji_filter`](#fiji-filter) | data | Yes |
| [`fiji_convert`](#fiji-convert) | data | Yes |
| [`fiji_duplicate`](#fiji-duplicate) | data | Yes |
| [`fiji_zoom`](#fiji-zoom) | view | No |
| [`fiji_highlight`](#fiji-highlight) | view | No |
| [`fiji_overlay`](#fiji-overlay) | view | No |
| [`fiji_display`](#fiji-display) | view | No |
| [`fiji_stack`](#fiji-stack) | view | No |
| [`fiji_project_stack`](#fiji-project-stack) | data | Yes |
| [`fiji_commands`](#fiji-commands) | read | No |
| [`fiji_run_command`](#fiji-run-command) | code | Yes |
| [`fiji_ui`](#fiji-ui) | read | No |
| [`fiji_ui_action`](#fiji-ui-action) | code | Yes |
| [`fiji_run_macro`](#fiji-run-macro) | code | Yes |
| [`fiji_run_script`](#fiji-run-script) | code | Yes |
| [`fiji_recorder`](#fiji-recorder) | read | No |
| [`fiji_save_workflow`](#fiji-save-workflow) | code | Yes |
| [`fiji_make_macro`](#fiji-make-macro) | code | Yes |
| [`fiji_workflows`](#fiji-workflows) | read | No |
| [`fiji_run_workflow`](#fiji-run-workflow) | code | Yes |
| [`fiji_capture_macro`](#fiji-capture-macro) | code | Yes |
| [`fiji_save_image`](#fiji-save-image) | code | Yes |
| [`fiji_export_results`](#fiji-export-results) | code | Yes |
| [`fiji_checkpoint`](#fiji-checkpoint) | data | Yes |
| [`fiji_checkpoints`](#fiji-checkpoints) | read | No |
| [`fiji_compare`](#fiji-compare) | read | No |
| [`fiji_restore`](#fiji-restore) | data | Yes |
| [`fiji_close`](#fiji-close) | data | Yes |
| [`fiji_snapshot`](#fiji-snapshot) | read | No |
| [`fiji_log`](#fiji-log) | read | No |
| [`fiji_plugins`](#fiji-plugins) | read | No |
| [`fiji_undo`](#fiji-undo) | data | Yes |
| [`fiji_knowledge`](#fiji-knowledge) | read | No |
| [`fiji_find_images`](#fiji-find-images) | read | No |
| [`fiji_action_status`](#fiji-action-status) | read | No |
| [`fiji_desktop_inspect`](#fiji-desktop-inspect) | read | No |
| [`fiji_desktop_act`](#fiji-desktop-act) | code | Yes |
| [`fiji_desktop_type`](#fiji-desktop-type) | code | Yes |
| [`fiji_desktop_key`](#fiji-desktop-key) | code | Yes |
| [`fiji_desktop_click`](#fiji-desktop-click) | code | Yes |

<a id="fiji-context"></a>

## fiji_context

Read Fiji’s real state: the active image (id, title, width/height, channels/slices/frames, bit depth, calibration unit, unsaved changes), open images, the active ROI, overlay/ROI Manager/Results counts, and the viewer (magnification, visible srcRect in image px, canvas screen rectangle). Refresh before resolving “this”, “here” or any mutation.

Input: `{}`.

<a id="fiji-images"></a>

## fiji_images

List open images with ids, titles, sizes, which one is active and whether each has unsaved changes.

Input: `{}`.

<a id="fiji-open-path"></a>

## fiji_open_path

Open an exact absolute local image path in Fiji (IJ.openImage, Bio-Formats importer for microscopy formats). A Bio-Formats/Open dialog with default settings is confirmed for you; any other dialog is reported for fiji_ui. Returns the opened image and a highlight; re-read fiji_context to verify the active image. Dispatch is not completion.

| Parameter | Required | Accepted value |
|---|---|---|
| `path` | Yes | string; min 1 characters; max 4096 characters |

<a id="fiji-activate-image"></a>

## fiji_activate_image

Bring an open image window to the front by id or title and make it the active image. Display only.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `id` | No | integer | ImageJ image id exactly as fiji_context/fiji_images report it (ImageJ ids are negative integers). |
| `title` | No | string; min 1 characters; max 500 characters |  |

<a id="fiji-select-roi"></a>

## fiji_select_roi

Set the active selection (ROI) in image pixels: rectangle or oval by x, y, width, height; polygon or line by points [[x,y],…]; point by x, y; none clears it. Infer the region from viewer.srcRect or the image size, never ask for coordinates. The view zooms to a ROI that would be invisible. Returns the ROI and a highlight.

| Parameter | Required | Accepted value |
|---|---|---|
| `type` | Yes | "rectangle", "oval", "polygon", "line", "point", "none" |
| `x` | No | number |
| `y` | No | number |
| `width` | No | number; greater than 0 |
| `height` | No | number; greater than 0 |
| `points` | No | array of (array of (number); min 2 items; max 2 items); min 2 items; max 10000 items |
| `name` | No | string; max 200 characters |

<a id="fiji-roi-manager"></a>

## fiji_roi_manager

List ROI Manager entries (index, name, type, bounds). Bounded by limit; total is untruncated.

| Parameter | Required | Accepted value |
|---|---|---|
| `limit` | No | integer; min 1; max 500 |

<a id="fiji-add-to-manager"></a>

## fiji_add_to_manager

Add the active ROI to the ROI Manager. Returns its index and the new total.

Input: `{}`.

<a id="fiji-measure"></a>

## fiji_measure

Measure with ImageJ (Analyze > Measure): configures Set Measurements from names (area, mean, min, max, perimeter, shape, integrated, median, centroid, feret, …) and measures the active ROI (scope roi), every ROI Manager entry (manager, multi-measure) or the whole image (all). Returns rows, columns, the last row and per-column min/max/mean summaries. Numbers come from here, never from appearance.

| Parameter | Required | Accepted value |
|---|---|---|
| `measurements` | No | array of (string; min 1 characters; max 40 characters); max 20 items; unique items |
| `scope` | No | "roi", "manager", "all" |

<a id="fiji-results"></a>

## fiji_results

Read Results table rows (bounded by limit and offset) with their column names.

| Parameter | Required | Accepted value |
|---|---|---|
| `limit` | No | integer; min 1; max 500 |
| `offset` | No | integer; min 0 |

<a id="fiji-clear-results"></a>

## fiji_clear_results

Clear the Results table.

Input: `{}`.

<a id="fiji-threshold"></a>

## fiji_threshold

Threshold the active image or ROI: an automatic method (Otsu, Default, Huang, Li, Triangle, Moments, MaxEntropy, Yen, …; dark true for bright objects on a dark background) or explicit lower/upper bounds. Returns the lower/upper values applied and a highlight. For “stricter” raise the lower bound (narrower range) from the last result, for “looser” lower it, then run fiji_analyze_particles again.

| Parameter | Required | Accepted value |
|---|---|---|
| `method` | No | string; max 40 characters |
| `dark` | No | boolean |
| `lower` | No | number |
| `upper` | No | number |

<a id="fiji-analyze-particles"></a>

## fiji_analyze_particles

Count and outline objects in the thresholded image (Analyze > Analyze Particles): minSize/maxSize in calibrated units², minCircularity 0–1, exclude edge particles, include holes, show Outlines/Masks/Nothing, addToManager (default true), summarize. Returns count, totalArea, averageSize and a highlight of up to 120 particles.

| Parameter | Required | Accepted value |
|---|---|---|
| `minSize` | No | number; min 0 |
| `maxSize` | No | number; greater than 0 |
| `minCircularity` | No | number; min 0; max 1 |
| `exclude` | No | boolean |
| `include` | No | boolean |
| `show` | No | "Outlines", "Masks", "Nothing" |
| `addToManager` | No | boolean |
| `summarize` | No | boolean |

<a id="fiji-segment-cells"></a>

## fiji_segment_cells

One-step cell/nucleus segmentation of the active image: duplicate → 8-bit → Gaussian 1 → auto threshold (method, default Otsu) → fill holes → watershed (default true) → Analyze Particles on the original, adding every cell to the ROI Manager. Pick channel and minSize yourself from the image. Returns count, the steps taken (macro-equivalent) and a highlight; follow with fiji_measure scope manager for areas and intensities.

| Parameter | Required | Accepted value |
|---|---|---|
| `channel` | No | integer; min 1 |
| `method` | No | string; max 40 characters |
| `minSize` | No | number; min 0 |
| `watershed` | No | boolean |

<a id="fiji-track-objects"></a>

## fiji_track_objects

Follow cells, nuclei, protein puncta or particles through a time-lapse: detect objects in every frame (auto threshold, method default Otsu; dark true for bright objects on dark), link them frame to frame (maxDistance px/frame, maxGap frames, minLength frames) and draw the trajectories on the overlay. Pick channel, sizes and distances yourself from the image. Returns tracks, objects per frame, mean speed/displacement/path length with units, divisions, the top tracks and a “Tracks” table.

| Parameter | Required | Accepted value |
|---|---|---|
| `channel` | No | integer; min 1 |
| `method` | No | string; max 40 characters |
| `dark` | No | boolean |
| `minSize` | No | number; min 0 |
| `maxSize` | No | number; greater than 0 |
| `maxDistance` | No | number; greater than 0 |
| `maxGap` | No | integer; min 0; max 10 |
| `minLength` | No | integer; min 1 |
| `startFrame` | No | integer; min 1 |
| `endFrame` | No | integer; min 1 |
| `sigma` | No | number; min 0 |
| `replaceOverlay` | No | boolean |

<a id="fiji-intensity-over-time"></a>

## fiji_intensity_over_time

Mean intensity of the active ROI (scope active) or ROI Manager entries (scope manager, optional indices) in every frame: protein expression kinetics, FRAP recovery, photobleaching. Returns per-ROI first/last/min/max, percent change, peak frame and values, plus a bleaching estimate when measurable.

| Parameter | Required | Accepted value |
|---|---|---|
| `scope` | No | "active", "manager" |
| `indices` | No | array of (integer; min 0); max 1000 items |
| `channel` | No | integer; min 1 |

<a id="fiji-tumor-region"></a>

## fiji_tumor_region

Outline the tumour or tissue region of a section (H&E, IHC or fluorescence; stain auto picks colour deconvolution for brightfield): smooth (sigma) → threshold (method) → keep the largest regions (keepLargest, minAreaFraction of the image) → add to the ROI Manager. Returns regions, area, area fraction of the image, perimeter, circularity, solidity, mean intensity, the steps and a highlight.

| Parameter | Required | Accepted value |
|---|---|---|
| `channel` | No | integer; min 1 |
| `stain` | No | "auto", "he", "ihc", "fluorescence" |
| `sigma` | No | number; min 0 |
| `method` | No | string; max 40 characters |
| `minAreaFraction` | No | number; min 0; max 1 |
| `keepLargest` | No | integer; min 1; max 20 |
| `name` | No | string; max 80 characters |

<a id="fiji-colocalize"></a>

## fiji_colocalize

Colocalisation of two channels (channelA/channelB) of the active image, or two open images (imageA/imageB ids), inside the active ROI when roi is true: Pearson’s r, Manders’ M1/M2 above thresholds (auto method or explicit thresholdA/thresholdB), overlap and ICQ. Read only. Correlation is not interaction; say which thresholds were used.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `channelA` | No | integer; min 1 |  |
| `channelB` | No | integer; min 1 |  |
| `roi` | No | boolean |  |
| `imageA` | No | integer | ImageJ image id exactly as fiji_context/fiji_images report it (ImageJ ids are negative integers). |
| `imageB` | No | integer | ImageJ image id exactly as fiji_context/fiji_images report it (ImageJ ids are negative integers). |
| `method` | No | string; max 40 characters |  |
| `thresholdA` | No | number |  |
| `thresholdB` | No | number |  |

<a id="fiji-classify-intensity"></a>

## fiji_classify_intensity

Score every ROI Manager object (usually cells from fiji_segment_cells) as positive or negative for a marker by its mean/intdens/median/max in a channel, against an explicit threshold or an auto method on the per-cell values: Ki67 index, protein-positive cells. Returns total, positive, negative, percent positive, the threshold used and a highlight of the positives. Label is the marker name (“Ki67”).

| Parameter | Required | Accepted value |
|---|---|---|
| `channel` | No | integer; min 1 |
| `measurement` | No | "mean", "intdens", "median", "max" |
| `threshold` | No | number |
| `method` | No | string; max 40 characters |
| `label` | No | string; max 40 characters |

<a id="fiji-object-stats"></a>

## fiji_object_stats

Summarise the objects in the ROI Manager: count, area/intensity/circularity mean, median, SD, min and max, an area histogram and the largest and smallest objects with a highlight. Read only.

Input: `{}`.

<a id="fiji-filter"></a>

## fiji_filter

Apply an ImageJ filter to the active image or ROI: gaussian/median/mean (radius or sigma), unsharp, subtract_background (rolling radius), enhance_contrast (saturated %), invert, smooth, sharpen, find_edges. Changes pixels; a checkpoint is taken first.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | "gaussian", "median", "mean", "unsharp", "subtract_background", "enhance_contrast", "invert", "smooth", "sharpen", "find_edges" |
| `radius` | No | number; greater than 0 |
| `sigma` | No | number; greater than 0 |
| `rolling` | No | number; greater than 0 |
| `saturated` | No | number; min 0; max 100 |

<a id="fiji-convert"></a>

## fiji_convert

Convert the active image type: 8-bit, 16-bit, 32-bit, RGB, Composite or Grayscale; splitChannels splits a multichannel image into separate windows.

| Parameter | Required | Accepted value |
|---|---|---|
| `to` | Yes | "8-bit", "16-bit", "32-bit", "RGB", "Composite", "Grayscale" |
| `splitChannels` | No | boolean |

<a id="fiji-duplicate"></a>

## fiji_duplicate

Duplicate the active image, or only its ROI, into a new window with an optional title. Returns the new image.

| Parameter | Required | Accepted value |
|---|---|---|
| `title` | No | string; max 200 characters |
| `roiOnly` | No | boolean |

<a id="fiji-zoom"></a>

## fiji_zoom

Change the view of the active image: direction in/out (one ImageJ zoom step), fit true to fit the window, or an exact magnification, optionally centred on image pixel x, y. Returns the viewer state. Display only.

| Parameter | Required | Accepted value |
|---|---|---|
| `direction` | No | "in", "out" |
| `fit` | No | boolean |
| `magnification` | No | number; min 0.03125; max 32 |
| `x` | No | number |
| `y` | No | number |

<a id="fiji-highlight"></a>

## fiji_highlight

Visibly point at what you are talking about, like a presenter: ROI Manager entries/particles (rois manager, optionally indices), the active ROI (active), both (all) or an image-pixel rectangle. Penny flies its cursor there with a short label; the view zooms to targets that would otherwise be invisible. Display only.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `rois` | No | "manager", "active", "all" |  |
| `x` | No | number |  |
| `y` | No | number |  |
| `width` | No | number; greater than 0 |  |
| `height` | No | number; greater than 0 |  |
| `indices` | No | array of (integer; min 0); max 1000 items |  |
| `label` | No | string; max 80 characters | Two to six words shown beside the cursor, e.g. "64 cells". |
| `limit` | No | integer; min 1; max 120 |  |

<a id="fiji-overlay"></a>

## fiji_overlay

Show or hide the image overlay, load the ROI Manager entries into it (fromManager), clear it, or toggle labels. Returns the overlay count. Display only.

| Parameter | Required | Accepted value |
|---|---|---|
| `show` | No | boolean |
| `fromManager` | No | boolean |
| `clear` | No | boolean |
| `labels` | No | boolean |

<a id="fiji-display"></a>

## fiji_display

Brightness/contrast and lookup table (display only): min/max, auto range, lut name (Grays, Fire, Red, Green, Blue, Cyan, Magenta, Yellow, HiLo, …) and the channel of a composite.

| Parameter | Required | Accepted value |
|---|---|---|
| `min` | No | number |
| `max` | No | number |
| `auto` | No | boolean |
| `lut` | No | string; max 40 characters |
| `channel` | No | integer; min 1 |

<a id="fiji-stack"></a>

## fiji_stack

Navigate a stack or hyperstack: slice (1-based, or "next"/"previous"), channel, frame. Display only.

| Parameter | Required | Accepted value |
|---|---|---|
| `slice` | No | integer; min 1 or "next", "previous" |
| `channel` | No | integer; min 1 |
| `frame` | No | integer; min 1 |

<a id="fiji-project-stack"></a>

## fiji_project_stack

Z-project the active stack (max, sum or avg intensity) into a new image.

| Parameter | Required | Accepted value |
|---|---|---|
| `project` | Yes | "max", "sum", "avg" |

<a id="fiji-commands"></a>

## fiji_commands

Search Fiji’s menu commands (ImageJ1 menus and SciJava commands) by name: returns name, menuPath and source. Search before fiji_run_command and use the exact name.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | Yes | string; min 1 characters; max 200 characters |

<a id="fiji-run-command"></a>

## fiji_run_command

Run an exact menu command by name (from fiji_commands) with an ImageJ options string, e.g. "Blobs (25K)" or "Gaussian Blur..." with "sigma=2". If a dialog opens and stays, the result reports dispatched:true with the dialog’s controls: set them with fiji_ui_action and press OK. Dispatch is not completion; re-read context. File and plugin side effects may not be reversible.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; min 1 characters; max 200 characters |
| `options` | No | string; max 4000 characters |

<a id="fiji-ui"></a>

## fiji_ui

Inspect visible Fiji/ImageJ windows (AWT/Swing): dialogs, buttons, text fields, checkboxes, choices and sliders with ids, values, disabled state and screen bounds. Use for GenericDialogs after a command. Excludes other applications and OS-owned dialogs.

Input: `{}`.

<a id="fiji-ui-action"></a>

## fiji_ui_action

Operate a control seen by fiji_ui: fire presses a button, set_text edits a field, select chooses a choice option, toggle flips a checkbox. Re-inspect afterwards; dispatch is not completion. Never bypass disabled controls.

| Parameter | Required | Accepted value |
|---|---|---|
| `nodeId` | Yes | string |
| `action` | Yes | "fire", "set_text", "select", "toggle" |
| `value` | No | string; max 32000 characters |

<a id="fiji-run-macro"></a>

## fiji_run_macro

Run ImageJ macro code (IJ.runMacro) with an optional argument; returns the result and Log text (bounded). Pixel changes are checkpointed when possible; file effects are not. Prefer a structured tool or fiji_run_command first.

| Parameter | Required | Accepted value |
|---|---|---|
| `code` | Yes | string; min 1 characters; max 32000 characters |
| `argument` | No | string; max 4000 characters |

<a id="fiji-run-script"></a>

## fiji_run_script

Run a SciJava script (groovy, python, javascript or ijm) in Fiji; returns its output and return value as text. Full local privileges: can read/write files or run code. Last resort after structured tools, fiji_run_command and fiji_run_macro.

| Parameter | Required | Accepted value |
|---|---|---|
| `language` | Yes | "groovy", "python", "javascript", "ijm" |
| `code` | Yes | string; min 1 characters; max 32000 characters |

<a id="fiji-recorder"></a>

## fiji_recorder

Read the Macro Recorder text if it is open, otherwise Penny’s own journal of the IJ.run calls it made (steps and an equivalent macro). Read only.

Input: `{}`.

<a id="fiji-save-workflow"></a>

## fiji_save_workflow

Save the recorded steps as a reusable ImageJ macro (.ijm) in Penny’s workflow folder so the same procedure can run on other images. Returns the path, step count and macro text. Pick a short descriptive name yourself.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; min 1 characters; max 80 characters |

<a id="fiji-make-macro"></a>

## fiji_make_macro

Write an ImageJ macro (ijm; groovy or python when needed) for any procedure Fiji has no tool for, save it as a named workflow and test-run it on the active image (test default true). Params are passed as getArgument() key=value pairs. Returns ok, error and errorLine on failure, the Log, Results rows added and images opened. On an error fix the code and call again with the same name, silently, at most 3 times.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; min 1 characters; max 80 characters |
| `code` | Yes | string; min 1 characters; max 32000 characters |
| `description` | No | string; max 200 characters |
| `language` | No | "ijm", "groovy", "python" |
| `test` | No | boolean |
| `params` | No | object |

<a id="fiji-workflows"></a>

## fiji_workflows

List saved workflows/macros (name, file, language, description, params, modified, lines), optionally filtered by query. Read only.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | No | string; max 200 characters |

<a id="fiji-run-workflow"></a>

## fiji_run_workflow

Run a saved workflow by name on the active image, every open image, or every matching file in a folder (target folder with an absolute folder and a pattern such as *.tif; limit files). Params override its defaults; saveResults writes one combined CSV. Returns processed, failed, per-image results and the CSV path.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; min 1 characters; max 80 characters |
| `target` | No | "active", "open", "folder" |
| `folder` | No | string; min 1 characters; max 4096 characters |
| `pattern` | No | string; max 200 characters |
| `params` | No | object |
| `limit` | No | integer; min 1; max 500 |
| `saveResults` | No | boolean |

<a id="fiji-capture-macro"></a>

## fiji_capture_macro

Turn what Penny just did (the recorded steps, or only the lastSteps) into a reusable, tested macro saved under name. Use for “save what you just did / make this a workflow”. Returns the path, lines, tested and ok.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; min 1 characters; max 80 characters |
| `description` | No | string; max 200 characters |
| `lastSteps` | No | integer; min 1; max 500 |

<a id="fiji-save-image"></a>

## fiji_save_image

Save the active image as tiff, png or jpeg to an exact path or, by default, next to its source or into .local/exports. Returns the path.

| Parameter | Required | Accepted value |
|---|---|---|
| `path` | No | string; min 1 characters; max 4096 characters |
| `format` | No | "tiff", "png", "jpeg" |

<a id="fiji-export-results"></a>

## fiji_export_results

Write the Results table as CSV into .local/exports (or an exact path). Returns the path and row count. No research data leaves this computer.

| Parameter | Required | Accepted value |
|---|---|---|
| `path` | No | string; min 1 characters; max 4096 characters |

<a id="fiji-checkpoint"></a>

## fiji_checkpoint

Snapshot the active image’s pixels, ROI, overlay and ROI Manager in memory (bounded 512 MiB) so fiji_restore can go back. Not a file save.

| Parameter | Required | Accepted value |
|---|---|---|
| `label` | No | string; max 80 characters |

<a id="fiji-checkpoints"></a>

## fiji_checkpoints

List in-memory checkpoints and which image each belongs to.

Input: `{}`.

<a id="fiji-compare"></a>

## fiji_compare

Compare counts now (ROI Manager, overlay, Results rows) against a checkpoint. Read only.

| Parameter | Required | Accepted value |
|---|---|---|
| `checkpointId` | Yes | string |

<a id="fiji-restore"></a>

## fiji_restore

Restore a checkpoint’s pixels, ROI, overlay and ROI Manager for its image. Not a general undo for files, closed windows or scripts.

| Parameter | Required | Accepted value |
|---|---|---|
| `checkpointId` | Yes | string |

<a id="fiji-close"></a>

## fiji_close

Close the active image, the image with id, or all images, without saving; unsaved changes are reported.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `id` | No | integer | ImageJ image id exactly as fiji_context/fiji_images report it (ImageJ ids are negative integers). |
| `all` | No | boolean |  |

<a id="fiji-snapshot"></a>

## fiji_snapshot

Capture only the active image canvas as PNG for visual inspection. No unrelated desktop content is captured.

Input: `{}`.

<a id="fiji-log"></a>

## fiji_log

Read the tail of the ImageJ Log window.

| Parameter | Required | Accepted value |
|---|---|---|
| `limit` | No | integer; min 1; max 500 |

<a id="fiji-plugins"></a>

## fiji_plugins

List installed plugin commands under Plugins>, optionally filtered by query. Read only.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | No | string; max 200 characters |

<a id="fiji-undo"></a>

## fiji_undo

Undo the last pixel operation (Edit > Undo) when ImageJ can, otherwise restore the latest checkpoint. Returns what came back.

Input: `{}`.

<a id="fiji-knowledge"></a>

## fiji_knowledge

Search Penny’s Fiji guidance and the locally downloaded official ImageJ documentation (user guide, menus, macro functions). Use before unfamiliar commands, filters, measurements, stacks or macros.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | Yes | string; min 2 characters; max 200 characters |

<a id="fiji-find-images"></a>

## fiji_find_images

Find images on this computer by a loose name ("the blobs tif", "embryos"): searches Penny’s data folder, Desktop, Downloads and Documents. Use before fiji_open_path when the researcher names an image instead of a path; never ask them for a folder.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | No | string; max 200 characters |
| `limit` | No | integer; min 1; max 50 |

<a id="fiji-action-status"></a>

## fiji_action_status

Read a recorded action (completed, failed, denied or expired) by ID. Decisions are taken by policy or the researcher in the companion; there is no model approval tool.

| Parameter | Required | Accepted value |
|---|---|---|
| `actionId` | Yes | string |

<a id="fiji-desktop-inspect"></a>

## fiji_desktop_inspect

List the windows and actionable elements of Fiji (default), the frontmost app or any bundle ids via macOS Accessibility, including OS-owned open/save panels and alerts. Each element has a stable id, role, title/label/value, enabled and a global screen frame (top-left origin). Use the in-app fiji_ui / fiji_ui_action first; they see AWT/Swing controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, OS file choosers, third-party plugin windows, alerts and any other window on screen. Always inspect immediately before acting: ids expire after 30 s. Reports trusted:false when Penny lacks the Accessibility permission, which only the researcher can grant.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `apps` | No | array of (string; max 200 characters); max 8 items | Bundle ids, or "frontmost". Default ["sc.fiji.fiji"]. |
| `windowTitleContains` | No | string; max 200 characters |  |
| `maxElements` | No | integer; min 1; max 2000 |  |

<a id="fiji-desktop-act"></a>

## fiji_desktop_act

Operate one element id from the latest fiji_desktop_inspect (within 30 s): press a button/menu item, toggle a checkbox, focus or select a row/tab/option, setValue or type into a field. Accessibility actions are tried first; otherwise Penny moves the real mouse to the element and clicks or types. The cursor flies there first. Use the in-app fiji_ui / fiji_ui_action first; they see AWT/Swing controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, OS file choosers, third-party plugin windows, alerts and any other window on screen. Re-inspect afterwards; dispatch is not completion. Never bypass disabled controls.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `id` | Yes | string; min 1 characters; max 200 characters |  |
| `action` | Yes | "press", "setValue", "focus", "select", "toggle", "type" |  |
| `value` | No | string; max 4096 characters | Text for setValue/type. |
| `label` | No | string; max 80 characters | Two to six words shown beside Penny’s cursor while it flies to the target. |

<a id="fiji-desktop-type"></a>

## fiji_desktop_type

Type text into whatever currently has keyboard focus (Fiji is brought forward first), optionally pressing Return after. For a file dialog: fiji_desktop_key ["cmd+shift+g"], then fiji_desktop_type the absolute path with submit true, then fiji_desktop_key ["return"] to open. Use the in-app fiji_ui / fiji_ui_action first; they see AWT/Swing controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, OS file choosers, third-party plugin windows, alerts and any other window on screen.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `text` | Yes | string; min 1 characters; max 4096 characters |  |
| `submit` | No | boolean | Press Return after typing. |
| `app` | No | string; max 200 characters | Bundle id to bring forward first: "sc.fiji.fiji" (default), "frontmost" or "none". |
| `label` | No | string; max 80 characters | Two to six words shown beside Penny’s cursor while it flies to the target. |

<a id="fiji-desktop-key"></a>

## fiji_desktop_key

Press keyboard shortcuts in order, e.g. ["cmd+shift+g"] to open the path sheet in a file chooser, ["return"], ["escape"], ["tab"], ["cmd+o"]. Modifiers cmd/shift/alt/ctrl plus one key name (letters, digits, return, escape, tab, space, delete, arrows, f1-f12). Use the in-app fiji_ui / fiji_ui_action first; they see AWT/Swing controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, OS file choosers, third-party plugin windows, alerts and any other window on screen.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `keys` | Yes | array of (string; min 1 characters; max 40 characters); min 1 items; max 10 items |  |
| `app` | No | string; max 200 characters | Bundle id to bring forward first: "sc.fiji.fiji" (default), "frontmost" or "none". |
| `label` | No | string; max 80 characters | Two to six words shown beside Penny’s cursor while it flies to the target. |

<a id="fiji-desktop-click"></a>

## fiji_desktop_click

Click at exact global screen coordinates (top-left origin, from a frame returned by fiji_desktop_inspect) when no element id fits, e.g. a canvas or custom-drawn plugin control. Penny flies its cursor there, then moves the real mouse and clicks. Use the in-app fiji_ui / fiji_ui_action first; they see AWT/Swing controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, OS file choosers, third-party plugin windows, alerts and any other window on screen. Prefer fiji_desktop_act with an id whenever one exists.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `x` | Yes | number |  |
| `y` | Yes | number |  |
| `count` | No | integer; min 1; max 3 | 2 for a double-click. |
| `button` | No | "left", "right" |  |
| `modifiers` | No | array of ("cmd", "shift", "alt", "ctrl"); max 4 items |  |
| `app` | No | string; max 200 characters | Bundle id to bring forward first: "sc.fiji.fiji" (default), "frontmost" or "none". |
| `label` | No | string; max 80 characters | Two to six words shown beside Penny’s cursor while it flies to the target. |

See [analysis semantics and verification limits](CAPABILITIES.md), [setup](SETUP.md) and [scientific task guidance](../skills/fiji/SKILL.md).
