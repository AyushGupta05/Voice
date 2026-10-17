# MCP tool reference (QuPath)

Generated from `packages/gateway/src/apps/qupath-tools.ts`; this profile is active when `LUMA_APP=qupath` (the default). Do not edit individual schemas here; regenerate after changing tool definitions. [Complete machine-readable registry](tool-schemas.json) retains every schema constraint and description. MCP discovery reports the running gateway, which may be older than these source definitions. Other applications: [Fiji](TOOL-REFERENCE-FIJI.md), [Fidelis](TOOL-REFERENCE-FIDELIS.md).

All inputs are JSON objects. Unknown properties are rejected. Read and view tools run immediately. Data and code tools go through the recorded decision path: executed at once under the default `LUMA_APPROVALS=auto` policy, shown as human review cards under `review`. The approval control is absent from the MCP tool list.

| Tool | Risk | Decision path |
|---|---|---|
| [`qupath_context`](#qupath-context) | read | No |
| [`qupath_objects`](#qupath-objects) | read | No |
| [`qupath_measurements`](#qupath-measurements) | read | No |
| [`qupath_measurement_summary`](#qupath-measurement-summary) | read | No |
| [`qupath_analysis_catalog`](#qupath-analysis-catalog) | read | No |
| [`qupath_project_images`](#qupath-project-images) | read | No |
| [`qupath_find_images`](#qupath-find-images) | read | No |
| [`qupath_open_path`](#qupath-open-path) | code | Yes |
| [`qupath_open_project_image`](#qupath-open-project-image) | code | Yes |
| [`qupath_inspect_import`](#qupath-inspect-import) | read | No |
| [`qupath_import_annotations`](#qupath-import-annotations) | data | Yes |
| [`qupath_select`](#qupath-select) | view | No |
| [`qupath_focus`](#qupath-focus) | view | No |
| [`qupath_highlight`](#qupath-highlight) | view | No |
| [`qupath_overlays`](#qupath-overlays) | view | No |
| [`qupath_snapshot`](#qupath-snapshot) | read | No |
| [`qupath_commands`](#qupath-commands) | read | No |
| [`qupath_ui`](#qupath-ui) | read | No |
| [`qupath_ui_action`](#qupath-ui-action) | code | Yes |
| [`qupath_annotate`](#qupath-annotate) | data | Yes |
| [`qupath_class_summary`](#qupath-class-summary) | read | No |
| [`qupath_workflow_steps`](#qupath-workflow-steps) | read | No |
| [`qupath_save_workflow`](#qupath-save-workflow) | code | Yes |
| [`qupath_detect_cells`](#qupath-detect-cells) | data | Yes |
| [`qupath_classify`](#qupath-classify) | data | Yes |
| [`qupath_image_type`](#qupath-image-type) | data | Yes |
| [`qupath_checkpoint`](#qupath-checkpoint) | read | No |
| [`qupath_checkpoints`](#qupath-checkpoints) | read | No |
| [`qupath_compare`](#qupath-compare) | read | No |
| [`qupath_restore`](#qupath-restore) | data | Yes |
| [`qupath_delete_selected`](#qupath-delete-selected) | data | Yes |
| [`qupath_delete_objects`](#qupath-delete-objects) | data | Yes |
| [`qupath_edit_objects`](#qupath-edit-objects) | data | Yes |
| [`qupath_export_measurements`](#qupath-export-measurements) | data | Yes |
| [`qupath_export_annotations`](#qupath-export-annotations) | data | Yes |
| [`qupath_export_image`](#qupath-export-image) | data | Yes |
| [`qupath_set_stains`](#qupath-set-stains) | data | Yes |
| [`qupath_estimate_stains`](#qupath-estimate-stains) | data | Yes |
| [`qupath_display`](#qupath-display) | view | No |
| [`qupath_plugins`](#qupath-plugins) | read | No |
| [`qupath_run_plugin`](#qupath-run-plugin) | data | Yes |
| [`qupath_detect_positive_cells`](#qupath-detect-positive-cells) | data | Yes |
| [`qupath_classifiers`](#qupath-classifiers) | read | No |
| [`qupath_apply_object_classifier`](#qupath-apply-object-classifier) | data | Yes |
| [`qupath_apply_pixel_classifier`](#qupath-apply-pixel-classifier) | data | Yes |
| [`qupath_save`](#qupath-save) | code | Yes |
| [`qupath_run_command`](#qupath-run-command) | code | Yes |
| [`qupath_run_script`](#qupath-run-script) | code | Yes |
| [`qupath_knowledge`](#qupath-knowledge) | read | No |
| [`qupath_api_search`](#qupath-api-search) | read | No |
| [`qupath_api_read`](#qupath-api-read) | read | No |
| [`qupath_action_status`](#qupath-action-status) | read | No |
| [`qupath_desktop_inspect`](#qupath-desktop-inspect) | read | No |
| [`qupath_desktop_act`](#qupath-desktop-act) | code | Yes |
| [`qupath_desktop_type`](#qupath-desktop-type) | code | Yes |
| [`qupath_desktop_key`](#qupath-desktop-key) | code | Yes |
| [`qupath_desktop_click`](#qupath-desktop-click) | code | Yes |

<a id="qupath-context"></a>

## qupath_context

Read the real active image/project, primary selection, full selectionCount and an explicit bounded selection preview, image type, measurements, viewer and result counts. Refresh before resolving “this”, “those” or a mutation.

Input: `{}`.

<a id="qupath-objects"></a>

## qupath_objects

List real objects with IDs, ROIs and classifications. region means centroids inside the selected ROI on the same image plane. Results are paginated; total is untruncated.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `offset` | No | integer; min 0 |  |
| `limit` | No | integer; min 1; max 500 |  |

<a id="qupath-measurements"></a>

## qupath_measurements

Read measured values, never infer them from appearance. Returns at most 200 rows and an explicit truncation flag.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `limit` | No | integer; min 1; max 200 |  |

<a id="qupath-measurement-summary"></a>

## qupath_measurement_summary

Summarize the entire matching object scope, not a preview: finite counts, missing values, min/max/mean/sample standard deviation and histograms, optionally grouped by exact class. Read the actual measurement catalog first. Descriptive statistics are not biological conclusions.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `measurements` | Yes | array of (string; min 1 characters); min 1 items; max 20 items; unique items |  |
| `histogramBins` | No | integer; min 1; max 50 |  |
| `groupByClass` | No | boolean |  |

<a id="qupath-analysis-catalog"></a>

## qupath_analysis_catalog

Discover actual stored measurement names/classes, current-image watershed detection parameters with units/ranges/choices, and recent workflow step names. Use before an unfamiliar analysis; no algorithm executes.

Input: `{}`.

<a id="qupath-project-images"></a>

## qupath_project_images

List images in the actual open project with stable entry IDs, source fingerprints, current-entry identity and saved-data availability. Paginated, or query an exact entryId; does not load or analyze other images.

| Parameter | Required | Accepted value |
|---|---|---|
| `entryId` | No | string; min 1 characters; max 200 characters |
| `offset` | No | integer; min 0 |
| `limit` | No | integer; min 1; max 200 |

<a id="qupath-find-images"></a>

## qupath_find_images

Find slide images and QuPath projects on this computer by a loose name ("OS-2", "the CMU slide"): searches the project data folder, Desktop, Downloads and Documents. Use before qupath_open_path when the researcher names a slide instead of a path; never ask them for a folder.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | No | string; max 200 characters |
| `limit` | No | integer; min 1; max 50 |

<a id="qupath-open-path"></a>

## qupath_open_path

Open an exact existing absolute local image path or .qpproj using QuPath’s native opening workflow. Refuses unsaved current-image changes; may open the native "Set image type" or reader dialogs: inspect with qupath_ui and press the right option/Apply with qupath_ui_action, then re-read context to verify the active image. Dispatch is not completion. URLs and .qpdata are excluded.

| Parameter | Required | Accepted value |
|---|---|---|
| `path` | Yes | string; min 1 characters; max 4096 characters |

<a id="qupath-open-project-image"></a>

## qupath_open_project_image

Open an exact entry ID and source fingerprint discovered in the current project after human approval. Refuses unsaved current-image changes and changed entry sources. Native reader/type dialogs can require interaction; re-read context to verify the active image.

| Parameter | Required | Accepted value |
|---|---|---|
| `entryId` | Yes | string; min 1 characters; max 200 characters |
| `entryFingerprint` | Yes | string; pattern `^[a-f0-9]{64}$` |

<a id="qupath-inspect-import"></a>

## qupath_inspect_import

Inspect an existing absolute local .geojson/.json annotation Features or FeatureCollection without changing QuPath. Returns SHA256, counts, preview and image-fit validation. Only leaf annotations, max 16MiB/10,000 objects; coordinates must already be full-resolution image pixels.

| Parameter | Required | Accepted value |
|---|---|---|
| `path` | Yes | string; min 1 characters; max 4096 characters |

<a id="qupath-import-annotations"></a>

## qupath_import_annotations

Add the inspected GeoJSON annotations to the current image after human review. Requires the exact inspect_import SHA256 and explicit image_pixels coordinate space. Revalidates file/geometry/IDs/image context, creates a hierarchy checkpoint, never replaces existing objects or writes source files.

| Parameter | Required | Accepted value |
|---|---|---|
| `path` | Yes | string; min 1 characters; max 4096 characters |
| `sha256` | Yes | string; pattern `^[a-f0-9]{64}$` |
| `coordinateSpace` | Yes | exactly "image_pixels" |

<a id="qupath-select"></a>

## qupath_select

Change the real selection using IDs or exact classification. Set ids to target specific objects; an empty filter selects all objects. Does not change scientific data.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |

<a id="qupath-focus"></a>

## qupath_focus

Center the real viewer on an object, current selection or image-pixel coordinates. Smaller downsample zooms in. Returns a real screen-coordinate pointer target.

| Parameter | Required | Accepted value |
|---|---|---|
| `objectId` | No | string |
| `x` | No | number |
| `y` | No | number |
| `downsample` | No | number; min 0.05; max 256 |

<a id="qupath-highlight"></a>

## qupath_highlight

Visibly point at and outline what you are talking about, like a presenter: objects by IDs/class/scope, an image-pixel region (x, y, width, height) or a visible JavaFX control (nodeId from qupath_ui). Penny flies its cursor there with a short label. When most targets are off screen the viewer first zooms to fit them (focus; force with true/false; returns focused). select also selects the matched objects in QuPath. Display only.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `x` | No | number |  |
| `y` | No | number |  |
| `width` | No | number; greater than 0 |  |
| `height` | No | number; greater than 0 |  |
| `nodeId` | No | string |  |
| `label` | No | string; max 80 characters | Two to six words shown beside the cursor, e.g. "49 positive cells". |
| `select` | No | boolean |  |
| `focus` | No | boolean |  |
| `limit` | No | integer; min 1; max 120 |  |

<a id="qupath-overlays"></a>

## qupath_overlays

Show/hide detections, annotations, names, counting grid, TMA grid or pixel-classification overlay; fill cells/annotations; nuclei-vs-boundary detection display; opacity; or show only an exact classification (empty clears). Display only.

| Parameter | Required | Accepted value |
|---|---|---|
| `detections` | No | boolean |
| `annotations` | No | boolean |
| `fill` | No | boolean |
| `fillAnnotations` | No | boolean |
| `names` | No | boolean |
| `grid` | No | boolean |
| `tmaGrid` | No | boolean |
| `pixelClassification` | No | boolean |
| `detectionMode` | No | "nuclei", "boundaries", "both", "centroids" |
| `opacity` | No | number; min 0; max 1 |
| `classification` | No | string |

<a id="qupath-snapshot"></a>

## qupath_snapshot

Capture only the current QuPath viewer as PNG for visual inspection. No unrelated desktop content is captured.

Input: `{}`.

<a id="qupath-commands"></a>

## qupath_commands

Discover the installed application’s live menu command paths, including extensions and enabled state. Search before invoking an exact path. A command may open a dialog; dispatch does not prove completion.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | No | string |

<a id="qupath-ui"></a>

## qupath_ui

Inspect visible JavaFX QuPath windows and controls, including exact node IDs, text, disabled state, combo options and screen bounds. Use for native dialogs after menu dispatch. Excludes other applications and OS-owned dialogs.

Input: `{}`.

<a id="qupath-ui-action"></a>

## qupath_ui_action

Operate a previously observed visible QuPath JavaFX control after human approval. fire presses a button, set_text edits an input, select chooses an exact combo-box/choice-box option or enabled tab title. May have irreversible file/plugin effects; re-inspect afterward. Never bypass disabled controls.

| Parameter | Required | Accepted value |
|---|---|---|
| `nodeId` | Yes | string |
| `action` | Yes | "fire", "set_text", "select" |
| `value` | No | string; max 32000 characters |

<a id="qupath-annotate"></a>

## qupath_annotate

Create a rectangle or ellipse annotation using full-resolution image pixels on the active z/t plane, optionally with a class such as "Tumor" or "Stroma". A hierarchy checkpoint is made first. Infer position and size from the viewer/selection; never ask the researcher for coordinates.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `x` | Yes | number |  |
| `y` | Yes | number |  |
| `width` | Yes | number; greater than 0 |  |
| `height` | Yes | number; greater than 0 |  |
| `shape` | No | "rectangle", "ellipse" |  |
| `name` | No | string |  |
| `classification` | No | string; max 80 characters | Class label to assign, e.g. "Tumor". Omit for an unclassified region. |

<a id="qupath-class-summary"></a>

## qupath_class_summary

Count objects by their current class with percentages computed by QuPath, for the whole image, the selection or the selected region (falls back to the whole image when nothing is selected; see scopeUsed). Use this for "how many / what percentage are positive", never arithmetic on listings.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |

<a id="qupath-workflow-steps"></a>

## qupath_workflow_steps

List the analysis steps QuPath has recorded for this image (its workflow history) and the Groovy script it would generate. Read only.

Input: `{}`.

<a id="qupath-save-workflow"></a>

## qupath_save_workflow

Save the recorded analysis steps as a reusable Groovy script in Penny's workflow folder, so the same procedure can be applied to other samples. Returns the path, step names and script. Pick a short descriptive name yourself.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; max 80 characters |

<a id="qupath-detect-cells"></a>

## qupath_detect_cells

Run QuPath watershed cell detection in selected annotations with explicit parameters (qupath_detect_positive_cells does detection + positive/negative in one step). May take time. If the image has pixelSizeMicrons, use ONLY the *Microns parameters (requestedPixelSizeMicrons, backgroundRadiusMicrons, medianRadiusMicrons, sigmaMicrons, minAreaMicrons, maxAreaMicrons, cellExpansionMicrons); pixel parameters are rejected there, and vice versa. Good brightfield H-DAB start: detectionImageBrightfield "Hematoxylin OD", requestedPixelSizeMicrons 0.5, backgroundRadiusMicrons 8, medianRadiusMicrons 0, sigmaMicrons 1.5, minAreaMicrons 10, maxAreaMicrons 400, threshold 0.1, cellExpansionMicrons 5, includeNuclei, smoothBoundaries and makeMeasurements true.

| Parameter | Required | Accepted value |
|---|---|---|
| `parameters` | Yes | object |

Allowed `parameters` properties:

| Parameter | Required | Accepted value |
|---|---|---|
| `detectionImage` | No | string |
| `detectionImageBrightfield` | No | string |
| `backgroundByReconstruction` | No | boolean |
| `excludeDAB` | No | boolean |
| `maxBackground` | No | number |
| `backgroundRadius` | No | number |
| `medianRadius` | No | number |
| `sigma` | No | number |
| `minArea` | No | number |
| `maxArea` | No | number |
| `cellExpansion` | No | number |
| `requestedPixelSizeMicrons` | No | number |
| `backgroundRadiusMicrons` | No | number |
| `medianRadiusMicrons` | No | number |
| `sigmaMicrons` | No | number |
| `minAreaMicrons` | No | number |
| `maxAreaMicrons` | No | number |
| `threshold` | No | number |
| `watershedPostProcess` | No | boolean |
| `cellExpansionMicrons` | No | number |
| `includeNuclei` | No | boolean |
| `smoothBoundaries` | No | boolean |
| `makeMeasurements` | No | boolean |

<a id="qupath-classify"></a>

## qupath_classify

Assign fixed output classes Positive (measurement >= threshold) and Negative to matching detections. classification is ONLY a filter on existing labels: omit it when thresholding all matching cells; do not set it to Positive to choose the output. A real matching-candidate count is shown for human approval; empty scopes fail before a proposal. Replaces existing classes; not a biological diagnosis.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `measurement` | Yes | string |  |
| `threshold` | No | number |  |
| `thresholds` | No | array of (number); min 1 items; max 3 items | Alternative to threshold: one value (Positive/Negative) or three increasing values (Negative/1+/2+/3+, H-score style) using QuPath's intensity classification, which keeps each object's base class. |

<a id="qupath-image-type"></a>

## qupath_image_type

Set the image interpretation after checking metadata (e.g. BRIGHTFIELD_H_E, BRIGHTFIELD_H_DAB, FLUORESCENCE). Requires approval. Hierarchy undo does not restore this setting.

| Parameter | Required | Accepted value |
|---|---|---|
| `type` | Yes | "BRIGHTFIELD_H_E", "BRIGHTFIELD_H_DAB", "BRIGHTFIELD_OTHER", "FLUORESCENCE", "OTHER", "UNSET" |

<a id="qupath-checkpoint"></a>

## qupath_checkpoint

Save an in-memory object hierarchy checkpoint for this image (maximum 6, 200k objects / 64MiB each). Does not save files, viewer settings or external script effects.

| Parameter | Required | Accepted value |
|---|---|---|
| `label` | No | string |

<a id="qupath-checkpoints"></a>

## qupath_checkpoints

List recoverable in-memory hierarchy checkpoints and whether they belong to the active image.

Input: `{}`.

<a id="qupath-compare"></a>

## qupath_compare

Compare exact object and classification counts now against a named checkpoint for the same image. Returns baseline/current/delta.

| Parameter | Required | Accepted value |
|---|---|---|
| `checkpointId` | Yes | string |

<a id="qupath-restore"></a>

## qupath_restore

Replace the current image’s object hierarchy with a checkpoint. Requires approval and creates a checkpoint of the current hierarchy first. Not a general undo for files or settings.

| Parameter | Required | Accepted value |
|---|---|---|
| `checkpointId` | Yes | string |

<a id="qupath-delete-selected"></a>

## qupath_delete_selected

Remove only selected objects, retaining their children. Human approval and automatic hierarchy checkpoint required.

| Parameter | Required | Accepted value |
|---|---|---|
| `scope` | Yes | exactly "selection" |

<a id="qupath-delete-objects"></a>

## qupath_delete_objects

Delete objects by scope, kind, existing class or IDs ("delete all detections", "remove the Necrosis annotations"). Default scope is the selection; use scope "all" with a kind/class for the whole image. TMA cores are refused. A hierarchy checkpoint is made first.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `keepChildren` | No | boolean | Keep child objects (default true). false also removes cells inside a deleted annotation. |

<a id="qupath-edit-objects"></a>

## qupath_edit_objects

Edit existing objects with QuPath's own tools: merge (union of selected annotations), split (disconnected parts into separate annotations), lock/unlock, set_class (value = class name, empty clears), rename (value), duplicate, fill_holes, expand (distance px or distanceMicrons; negative shrinks). Scope defaults to the selection. Checkpointed.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `action` | Yes | "merge", "split", "lock", "unlock", "set_class", "rename", "duplicate", "fill_holes", "expand" |  |
| `value` | No | string; max 200 characters | Class name for set_class, new name for rename. |
| `distance` | No | number |  |
| `distanceMicrons` | No | number |  |

<a id="qupath-export-measurements"></a>

## qupath_export_measurements

Write a CSV measurement table (QuPath's Measure > Export measurements columns) for the objects in scope to Penny's export folder (.local/exports). kind picks annotation/cell/detection rows. Returns the path, row and column counts. No research data is sent anywhere.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `columns` | No | array of (string; min 1 characters); max 100 items | Optional exact column names to keep; default all. |

<a id="qupath-export-annotations"></a>

## qupath_export_annotations

Write the objects in scope as a GeoJSON FeatureCollection (full-resolution image pixels) to Penny's export folder; QuPath and QuPath's importer can read it back. Default kind annotation. Returns the path and count.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `scope` | No | "all", "selection", "region" |  |
| `kind` | No | "all", "annotation", "cell", "detection" |  |
| `classification` | No | string | Filter objects by their EXISTING class. Omit or use an empty string for all current classes. This never specifies an output/assigned class. |
| `ids` | No | array of (string); max 1000 items |  |
| `excludeMeasurements` | No | boolean |  |

<a id="qupath-export-image"></a>

## qupath_export_image

Save the current view, the selected region or the whole image as PNG in Penny's export folder. rendered true (default) writes what the viewer shows including overlays; false writes raw pixels. Downsample is chosen automatically (max 4096 px) unless given. Returns the path and output size.

| Parameter | Required | Accepted value |
|---|---|---|
| `region` | No | "view", "selection", "full" |
| `rendered` | No | boolean |
| `downsample` | No | number; min 1; max 256 |

<a id="qupath-set-stains"></a>

## qupath_set_stains

Set colour-deconvolution stain vectors for a brightfield image: preset "H_E" or "H_DAB", or explicit stains {"Hematoxylin":[r,g,b],"DAB":[r,g,b],"Background":[255,255,255]} with a name. Set the image type first. Not restored by hierarchy checkpoints.

| Parameter | Required | Accepted value |
|---|---|---|
| `preset` | No | "H_E", "H_DAB" |
| `name` | No | string; max 80 characters |
| `stains` | No | object |

<a id="qupath-estimate-stains"></a>

## qupath_estimate_stains

Run QuPath's automatic stain-vector estimation (Analyze > Estimate stain vectors, "Auto") on the selected region or the visible area and apply it (apply false only previews). Returns before/after vectors and the angle change. Use when DAB/haematoxylin separation looks wrong.

| Parameter | Required | Accepted value |
|---|---|---|
| `apply` | No | boolean |
| `minStainOD` | No | number; min 0; max 2 |
| `maxStainOD` | No | number; min 0.1; max 5 |
| `ignorePercentage` | No | number; min 0; max 50 |
| `excludeUnrecognisedColors` | No | boolean |

<a id="qupath-display"></a>

## qupath_display

Brightness/contrast and channels (display only): pick a channel by name or index and set min/max, auto range, or visible; gamma (0.1–10), grayscale, invertBackground, rotationDegrees, or measurementMap (a detection measurement name to colour cells by; "" clears). With no arguments it just reports the channel table.

| Parameter | Required | Accepted value |
|---|---|---|
| `channel` | No | string or integer; min 0 |
| `min` | No | number |
| `max` | No | number |
| `auto` | No | boolean |
| `visible` | No | boolean |
| `gamma` | No | number |
| `grayscale` | No | boolean |
| `invertBackground` | No | boolean |
| `rotationDegrees` | No | number |
| `measurementMap` | No | string; max 200 characters |

<a id="qupath-plugins"></a>

## qupath_plugins

List the QuPath analysis plugins Penny can run directly (tiles, superpixels, tissue detection, TMA dearray, intensity/shape/smoothed features, positive cell detection, cell membrane detection, Delaunay clusters, annotation dilate/fill/refine/split, …) or, with plugin, read that plugin's parameters, defaults, units, ranges and choices for the current image. Read only.

| Parameter | Required | Accepted value |
|---|---|---|
| `plugin` | No | string; max 60 characters |

<a id="qupath-run-plugin"></a>

## qupath_run_plugin

Run an allow-listed QuPath plugin (see qupath_plugins) with explicit parameters, exactly like Analyze menu dialogs but without the dialog. Most plugins work inside the selected annotation(s); TMA dearray and tissue detection work on the whole image. Parameters are validated against the plugin's own list; a checkpoint is made first; returns object-count deltas.

| Parameter | Required | Accepted value |
|---|---|---|
| `plugin` | Yes | string; min 1 characters; max 60 characters |
| `parameters` | No | object |

<a id="qupath-detect-positive-cells"></a>

## qupath_detect_positive_cells

QuPath's Positive cell detection: watershed cell detection plus intensity classification in one run (Negative/Positive, or Negative/1+/2+/3+ when singleThreshold is false). Same watershed parameters as qupath_detect_cells plus thresholdCompartment (e.g. "Nucleus: DAB OD mean"), thresholdPositive1/2/3 and singleThreshold. Runs in the selected annotations; replaces existing cells there.

| Parameter | Required | Accepted value |
|---|---|---|
| `parameters` | Yes | object |

<a id="qupath-classifiers"></a>

## qupath_classifiers

List the object and pixel classifiers saved in the open project (names to pass to apply_object_classifier / apply_pixel_classifier). Read only.

Input: `{}`.

<a id="qupath-apply-object-classifier"></a>

## qupath_apply_object_classifier

Apply a saved object classifier (project name or absolute .json path) to all compatible detections, like Classify > Object classification > Load object classifier. Returns per-class counts. Replaces existing classes; checkpointed.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; min 1 characters; max 500 characters |

<a id="qupath-apply-pixel-classifier"></a>

## qupath_apply_pixel_classifier

Apply a saved pixel classifier or thresholder (project name or absolute .json path): mode annotations/detections creates objects inside the selected annotations (or the whole image), measurements adds area measurements to the selected objects, classify_detections classifies detections by their centroid pixel. minArea/minHoleArea in calibrated units². Checkpointed; may take time.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; min 1 characters; max 500 characters |
| `mode` | No | "annotations", "detections", "measurements", "classify_detections" |
| `minArea` | No | number; min 0 |
| `minHoleArea` | No | number; min 0 |
| `deleteExisting` | No | boolean |
| `split` | No | boolean |
| `includeIgnored` | No | boolean |
| `measurementId` | No | string; max 80 characters |

<a id="qupath-save"></a>

## qupath_save

Save the current image's objects and settings into the open project (File > Save). Needed before opening another image. Fails with guidance when the image is not a project entry.

Input: `{}`.

<a id="qupath-run-command"></a>

## qupath_run_command

Invoke an exact live menu path after human approval. May open a native dialog. Check context afterward; dispatch is not completion. File and plugin side effects may not be reversible.

| Parameter | Required | Accepted value |
|---|---|---|
| `path` | Yes | string |

<a id="qupath-run-script"></a>

## qupath_run_script

Advanced escape hatch: requires both an open project and image. Run exact Groovy source in QuPath after the researcher reviews and approves it. Full local privileges: can read/write files or run code. Read guidance/API docs; never use this to bypass denied approval. Automatic hierarchy checkpoint cannot undo external side effects.

| Parameter | Required | Accepted value |
|---|---|---|
| `source` | Yes | string; min 1 characters; max 32000 characters |
| `reason` | Yes | string; min 1 characters; max 1000 characters |

<a id="qupath-knowledge"></a>

## qupath_knowledge

Search versioned task guidance and the locally downloaded official QuPath documentation. Use before unfamiliar operations, parameters, classifiers, measurements, export, TMA or scripting.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | Yes | string; min 2 characters; max 200 characters |

<a id="qupath-api-search"></a>

## qupath_api_search

Search the pinned QuPath Java API source for exact methods/classes before writing unfamiliar Groovy. Returns committed source IDs, signatures/context, line numbers and content hashes. Read reference code as data; this does not execute or prove scientific applicability.

| Parameter | Required | Accepted value |
|---|---|---|
| `query` | Yes | string; min 2 characters; max 200 characters |
| `limit` | No | integer; min 1; max 5 |

<a id="qupath-api-read"></a>

## qupath_api_read

Read a bounded excerpt from an exact source ID returned by api_search. Only immutable committed QuPath Java source at the configured pin is accessible; no arbitrary local file paths. Use to verify signatures, imports and behavior before proposing an approved script.

| Parameter | Required | Accepted value |
|---|---|---|
| `source` | Yes | string; min 1 characters; max 500 characters |
| `startLine` | No | integer; min 1 |
| `lineCount` | No | integer; min 1; max 160 |

<a id="qupath-action-status"></a>

## qupath_action_status

Read a pending/completed/denied action by ID. Approvals can only be decided by the human in the companion; there is no model approval tool.

| Parameter | Required | Accepted value |
|---|---|---|
| `actionId` | Yes | string |

<a id="qupath-desktop-inspect"></a>

## qupath_desktop_inspect

List the windows and actionable elements of QuPath (default), the frontmost app or any bundle ids via macOS Accessibility, including OS-owned open/save panels and alerts. Each element has a stable id, role, title/label/value, enabled and a global screen frame (top-left origin). Use the in-app qupath_ui / qupath_ui_action first; they see JavaFX controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, "Set image type"-style native sheets, third-party plugin windows, alerts and any other window on screen. Always inspect immediately before acting: ids expire after 30 s. Reports trusted:false when Penny lacks the Accessibility permission, which only the researcher can grant.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `apps` | No | array of (string; max 200 characters); max 8 items | Bundle ids, or "frontmost". Default ["science.luma.qupath"]. |
| `windowTitleContains` | No | string; max 200 characters |  |
| `maxElements` | No | integer; min 1; max 2000 |  |

<a id="qupath-desktop-act"></a>

## qupath_desktop_act

Operate one element id from the latest qupath_desktop_inspect (within 30 s): press a button/menu item, toggle a checkbox, focus or select a row/tab/option, setValue or type into a field. Accessibility actions are tried first; otherwise Penny moves the real mouse to the element and clicks or types. The cursor flies there first. Use the in-app qupath_ui / qupath_ui_action first; they see JavaFX controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, "Set image type"-style native sheets, third-party plugin windows, alerts and any other window on screen. Re-inspect afterwards; dispatch is not completion. Never bypass disabled controls.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `id` | Yes | string; min 1 characters; max 200 characters |  |
| `action` | Yes | "press", "setValue", "focus", "select", "toggle", "type" |  |
| `value` | No | string; max 4096 characters | Text for setValue/type. |
| `label` | No | string; max 80 characters | Two to six words shown beside Penny’s cursor while it flies to the target. |

<a id="qupath-desktop-type"></a>

## qupath_desktop_type

Type text into whatever currently has keyboard focus (QuPath is brought forward first), optionally pressing Return after. For a file dialog: qupath_desktop_key ["cmd+shift+g"], then qupath_desktop_type the absolute path with submit true, then qupath_desktop_key ["return"] to open. Use the in-app qupath_ui / qupath_ui_action first; they see JavaFX controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, "Set image type"-style native sheets, third-party plugin windows, alerts and any other window on screen.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `text` | Yes | string; min 1 characters; max 4096 characters |  |
| `submit` | No | boolean | Press Return after typing. |
| `app` | No | string; max 200 characters | Bundle id to bring forward first: "science.luma.qupath" (default), "frontmost" or "none". |
| `label` | No | string; max 80 characters | Two to six words shown beside Penny’s cursor while it flies to the target. |

<a id="qupath-desktop-key"></a>

## qupath_desktop_key

Press keyboard shortcuts in order, e.g. ["cmd+shift+g"] to open the path sheet in a file chooser, ["return"], ["escape"], ["tab"], ["cmd+o"]. Modifiers cmd/shift/alt/ctrl plus one key name (letters, digits, return, escape, tab, space, delete, arrows, f1-f12). Use the in-app qupath_ui / qupath_ui_action first; they see JavaFX controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, "Set image type"-style native sheets, third-party plugin windows, alerts and any other window on screen.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `keys` | Yes | array of (string; min 1 characters; max 40 characters); min 1 items; max 10 items |  |
| `app` | No | string; max 200 characters | Bundle id to bring forward first: "science.luma.qupath" (default), "frontmost" or "none". |
| `label` | No | string; max 80 characters | Two to six words shown beside Penny’s cursor while it flies to the target. |

<a id="qupath-desktop-click"></a>

## qupath_desktop_click

Click at exact global screen coordinates (top-left origin, from a frame returned by qupath_desktop_inspect) when no element id fits, e.g. a canvas or custom-drawn plugin control. Penny flies its cursor there, then moves the real mouse and clicks. Use the in-app qupath_ui / qupath_ui_action first; they see JavaFX controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, "Set image type"-style native sheets, third-party plugin windows, alerts and any other window on screen. Prefer qupath_desktop_act with an id whenever one exists.

| Parameter | Required | Accepted value | Description |
|---|---|---|---|
| `x` | Yes | number |  |
| `y` | Yes | number |  |
| `count` | No | integer; min 1; max 3 | 2 for a double-click. |
| `button` | No | "left", "right" |  |
| `modifiers` | No | array of ("cmd", "shift", "alt", "ctrl"); max 4 items |  |
| `app` | No | string; max 200 characters | Bundle id to bring forward first: "science.luma.qupath" (default), "frontmost" or "none". |
| `label` | No | string; max 80 characters | Two to six words shown beside Penny’s cursor while it flies to the target. |

See [analysis semantics and verification limits](CAPABILITIES.md), [setup](PENNY-SETUP.md) and [scientific task guidance](../skills/qupath/SKILL.md).
