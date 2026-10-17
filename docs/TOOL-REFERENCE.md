# MCP tool reference

Generated from `packages/gateway/src/tools.ts`. Do not edit individual schemas here; regenerate after changing tool definitions. [Complete machine-readable registry](tool-schemas.json) retains every schema constraint and description. MCP discovery reports the running gateway, which may be older than these source definitions.

All inputs are JSON objects. Unknown properties are rejected. Read and view tools run immediately; data and code tools create human-review proposals. The approval control is absent from the MCP tool list.

| Tool | Risk | Human approval |
|---|---|---|
| [`qupath_context`](#qupath-context) | read | No |
| [`qupath_objects`](#qupath-objects) | read | No |
| [`qupath_measurements`](#qupath-measurements) | read | No |
| [`qupath_measurement_summary`](#qupath-measurement-summary) | read | No |
| [`qupath_analysis_catalog`](#qupath-analysis-catalog) | read | No |
| [`qupath_project_images`](#qupath-project-images) | read | No |
| [`qupath_open_path`](#qupath-open-path) | code | Required |
| [`qupath_open_project_image`](#qupath-open-project-image) | code | Required |
| [`qupath_inspect_import`](#qupath-inspect-import) | read | No |
| [`qupath_import_annotations`](#qupath-import-annotations) | data | Required |
| [`qupath_select`](#qupath-select) | view | No |
| [`qupath_focus`](#qupath-focus) | view | No |
| [`qupath_highlight`](#qupath-highlight) | view | No |
| [`qupath_overlays`](#qupath-overlays) | view | No |
| [`qupath_snapshot`](#qupath-snapshot) | read | No |
| [`qupath_commands`](#qupath-commands) | read | No |
| [`qupath_ui`](#qupath-ui) | read | No |
| [`qupath_ui_action`](#qupath-ui-action) | code | Required |
| [`qupath_annotate`](#qupath-annotate) | data | Required |
| [`qupath_class_summary`](#qupath-class-summary) | read | No |
| [`qupath_workflow_steps`](#qupath-workflow-steps) | read | No |
| [`qupath_save_workflow`](#qupath-save-workflow) | code | Required |
| [`qupath_detect_cells`](#qupath-detect-cells) | data | Required |
| [`qupath_classify`](#qupath-classify) | data | Required |
| [`qupath_image_type`](#qupath-image-type) | data | Required |
| [`qupath_checkpoint`](#qupath-checkpoint) | read | No |
| [`qupath_checkpoints`](#qupath-checkpoints) | read | No |
| [`qupath_compare`](#qupath-compare) | read | No |
| [`qupath_restore`](#qupath-restore) | data | Required |
| [`qupath_delete_selected`](#qupath-delete-selected) | data | Required |
| [`qupath_run_command`](#qupath-run-command) | code | Required |
| [`qupath_run_script`](#qupath-run-script) | code | Required |
| [`qupath_knowledge`](#qupath-knowledge) | read | No |
| [`qupath_api_search`](#qupath-api-search) | read | No |
| [`qupath_api_read`](#qupath-api-read) | read | No |
| [`qupath_action_status`](#qupath-action-status) | read | No |

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

<a id="qupath-open-path"></a>

## qupath_open_path

Open an exact existing absolute local image path or .qpproj using QuPath’s native opening/import workflow after human approval. Refuses unsaved current-image changes; may open image-reader, project-import or other dialogs. Dispatch is not completion. URLs and .qpdata are excluded. Does not bypass native prompts.

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

Visibly point at and outline what you are talking about, like a presenter: objects by IDs/class/scope, an image-pixel region (x, y, width, height) or a visible JavaFX control (nodeId from qupath_ui). Luma flies its cursor there with a short label. Only objects visible in the current viewer are outlined, so focus first if needed. select also selects the matched objects in QuPath. Display only.

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
| `limit` | No | integer; min 1; max 120 |  |

<a id="qupath-overlays"></a>

## qupath_overlays

Show/hide detections and annotations, fill cells, change opacity, or show only an exact classification. Empty classification clears the filter. Changes display only.

| Parameter | Required | Accepted value |
|---|---|---|
| `detections` | No | boolean |
| `annotations` | No | boolean |
| `fill` | No | boolean |
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

Count objects by their current class with percentages computed by QuPath, for the whole image, the selection or the selected region. Use this for "how many / what percentage are positive", never arithmetic on listings.

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

Save the recorded analysis steps as a reusable Groovy script in Luma's workflow folder, so the same procedure can be applied to other samples. Returns the path, step names and script. Pick a short descriptive name yourself.

| Parameter | Required | Accepted value |
|---|---|---|
| `name` | Yes | string; max 80 characters |

<a id="qupath-detect-cells"></a>

## qupath_detect_cells

Run QuPath watershed cell detection in selected annotations with explicit parameters. Requires human approval; may take time. If the image has pixelSizeMicrons, use ONLY the *Microns parameters (requestedPixelSizeMicrons, backgroundRadiusMicrons, medianRadiusMicrons, sigmaMicrons, minAreaMicrons, maxAreaMicrons, cellExpansionMicrons); pixel parameters are rejected there, and vice versa. Good brightfield H-DAB start: detectionImageBrightfield "Hematoxylin OD", requestedPixelSizeMicrons 0.5, backgroundRadiusMicrons 8, medianRadiusMicrons 0, sigmaMicrons 1.5, minAreaMicrons 10, maxAreaMicrons 400, threshold 0.1, cellExpansionMicrons 5, includeNuclei, smoothBoundaries and makeMeasurements true.

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
| `threshold` | Yes | number |  |

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

See [analysis semantics and verification limits](CAPABILITIES.md), [setup](SETUP.md) and [scientific task guidance](../skills/qupath/SKILL.md).
