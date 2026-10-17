# MCP tool reference

Generated from `packages/gateway/src/tools.ts`. Do not edit individual schemas here; regenerate after changing tool definitions.

All inputs are JSON objects. Unknown properties are rejected. Read and view tools run immediately; data and code tools create human-review proposals. The approval control is absent from the MCP tool list.

| Tool | Risk | Human approval |
|---|---|---|
| [`qupath_context`](#qupath-context) | read | No |
| [`qupath_objects`](#qupath-objects) | read | No |
| [`qupath_measurements`](#qupath-measurements) | read | No |
| [`qupath_select`](#qupath-select) | view | No |
| [`qupath_focus`](#qupath-focus) | view | No |
| [`qupath_overlays`](#qupath-overlays) | view | No |
| [`qupath_snapshot`](#qupath-snapshot) | read | No |
| [`qupath_commands`](#qupath-commands) | read | No |
| [`qupath_ui`](#qupath-ui) | read | No |
| [`qupath_ui_action`](#qupath-ui-action) | code | Required |
| [`qupath_annotate`](#qupath-annotate) | data | Required |
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
| [`qupath_action_status`](#qupath-action-status) | read | No |

<a id="qupath-context"></a>

## qupath_context

Read the real active image/project, primary selection, full selectionCount and an explicit bounded selection preview, image type, measurements, viewer and result counts. Refresh before resolving “this”, “those” or a mutation.

Input: `{}`.

<a id="qupath-objects"></a>

## qupath_objects

List real objects with IDs, ROIs and classifications. region means centroids inside the selected ROI on the same image plane. Results are paginated; total is untruncated.

| Parameter | Required | Accepted value |
|---|---|---|
| `scope` | No | "all", "selection", "region" |
| `kind` | No | "all", "annotation", "cell", "detection" |
| `classification` | No | string |
| `ids` | No | array of string; max 1000 items |
| `offset` | No | integer; min 0 |
| `limit` | No | integer; min 1; max 500 |

<a id="qupath-measurements"></a>

## qupath_measurements

Read measured values, never infer them from appearance. Returns at most 200 rows and an explicit truncation flag.

| Parameter | Required | Accepted value |
|---|---|---|
| `scope` | No | "all", "selection", "region" |
| `kind` | No | "all", "annotation", "cell", "detection" |
| `classification` | No | string |
| `ids` | No | array of string; max 1000 items |
| `limit` | No | integer; min 1; max 200 |

<a id="qupath-select"></a>

## qupath_select

Change the real selection using IDs or exact classification. Set ids to target specific objects; an empty filter selects all objects. Does not change scientific data.

| Parameter | Required | Accepted value |
|---|---|---|
| `scope` | No | "all", "selection", "region" |
| `kind` | No | "all", "annotation", "cell", "detection" |
| `classification` | No | string |
| `ids` | No | array of string; max 1000 items |

<a id="qupath-focus"></a>

## qupath_focus

Center the real viewer on an object, current selection or image-pixel coordinates. Smaller downsample zooms in. Returns a real screen-coordinate pointer target.

| Parameter | Required | Accepted value |
|---|---|---|
| `objectId` | No | string |
| `x` | No | number |
| `y` | No | number |
| `downsample` | No | number; min 0.05; max 256 |

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

Operate a previously observed visible QuPath JavaFX control after human approval. fire presses a button, set_text edits an input, select chooses an exact combo-box option. May have irreversible file/plugin effects; re-inspect afterward. Never bypass disabled controls.

| Parameter | Required | Accepted value |
|---|---|---|
| `nodeId` | Yes | string |
| `action` | Yes | "fire", "set_text", "select" |
| `value` | No | string; max 32000 characters |

<a id="qupath-annotate"></a>

## qupath_annotate

Create a rectangle or ellipse annotation using full-resolution image pixels on the active z/t plane. Requires human approval; a hierarchy checkpoint is made first.

| Parameter | Required | Accepted value |
|---|---|---|
| `x` | Yes | number |
| `y` | Yes | number |
| `width` | Yes | number; greater than 0 |
| `height` | Yes | number; greater than 0 |
| `shape` | No | "rectangle", "ellipse" |
| `name` | No | string |

<a id="qupath-detect-cells"></a>

## qupath_detect_cells

Run QuPath watershed cell detection in selected annotations with explicit parameters. Check image type/calibration and read detection guidance first. Requires human approval; may take time.

| Parameter | Required | Accepted value |
|---|---|---|
| `parameters` | Yes | object |

Allowed `parameters` properties (all optional unless listed as required):

| Property | Accepted value |
|---|---|
| `detectionImage` | string |
| `detectionImageBrightfield` | string |
| `backgroundByReconstruction` | boolean |
| `excludeDAB` | boolean |
| `maxBackground` | number |
| `backgroundRadius` | number |
| `medianRadius` | number |
| `sigma` | number |
| `minArea` | number |
| `maxArea` | number |
| `cellExpansion` | number |
| `requestedPixelSizeMicrons` | number |
| `backgroundRadiusMicrons` | number |
| `medianRadiusMicrons` | number |
| `sigmaMicrons` | number |
| `minAreaMicrons` | number |
| `maxAreaMicrons` | number |
| `threshold` | number |
| `watershedPostProcess` | boolean |
| `cellExpansionMicrons` | number |
| `includeNuclei` | boolean |
| `smoothBoundaries` | boolean |
| `makeMeasurements` | boolean |

<a id="qupath-classify"></a>

## qupath_classify

Classify detections Positive when the named existing measurement is >= threshold, Negative otherwise. Exact scope is shown for human approval. This replaces existing object classes, not a biological diagnosis.

| Parameter | Required | Accepted value |
|---|---|---|
| `scope` | No | "all", "selection", "region" |
| `kind` | No | "all", "annotation", "cell", "detection" |
| `classification` | No | string |
| `ids` | No | array of string; max 1000 items |
| `measurement` | Yes | string |
| `threshold` | Yes | number |

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

<a id="qupath-action-status"></a>

## qupath_action_status

Read a pending/completed/denied action by ID. Approvals can only be decided by the human in the companion; there is no model approval tool.

| Parameter | Required | Accepted value |
|---|---|---|
| `actionId` | Yes | string |

See [analysis semantics and verification limits](CAPABILITIES.md), [setup](SETUP.md) and [scientific task guidance](../skills/qupath/SKILL.md).
