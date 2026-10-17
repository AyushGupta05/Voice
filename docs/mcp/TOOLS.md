# Practical guide to every MCP tool

Every fenced JSON block below is a **call example**, not a recorded result or permission to execute it. Replace uppercase IDs/paths, zero hashes, measurement names and scientific settings with freshly observed values. Examples are checked for source-schema validity; schema validity alone does not satisfy runtime/image/approval prerequisites.

For complete required/optional fields, enums, lengths, nested parameter keys and numeric constraints, follow each generated-schema link or use the [JSON registry](../tool-schemas.json). Unknown top-level properties are rejected. Never attach `approved` to arguments.

## Shared filtering and result conventions

- `scope: all` (default) uses the non-root hierarchy; `selection` uses selected objects themselves; `region` uses object centroids inside the primary selected ROI on the same z/t plane and excludes the parent region itself.
- Omitted `kind` defaults to all where supported. Cells are detections, so those categories overlap.
- `classification` matches an **existing** exact class; omitted/empty means no class filter. It never specifies an output class.
- `ids` narrows to known IDs (at most 1,000). Omitted or empty means no ID restriction. Combined filters are restrictive, not a union of separate target sets.
- All coordinates are full-resolution image pixels unless explicitly labeled as screen coordinates. z/t are image-plane indices; physical measurement units belong to their actual stored names/metadata.
- Read/view calls return observations or immediate acknowledgements. Data/code calls return a **proposal first**. Their execution results described below are available only after human approval, through `action_status` or companion events.
- Ordinary MCP success results are JSON encoded in text content. `snapshot` instead returns image content. Protocol errors use `isError: true`; a failed/denied action can also be an ordinary structured action-status result.

Use [operations](OPERATIONS.md) for lifecycle/error handling, [workflows](WORKFLOWS.md) for sequencing and [coverage](COVERAGE.md) for evidence.

<a id="qupath-context"></a>

## qupath_context

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `context`. [Exact schema](../TOOL-REFERENCE.md#qupath-context).

Use before interpreting “this image”, “those objects” or “the selected region”. Returns the actual application/adapter identity, project/image when present, object/class counts, image metadata, viewer position and an opaque `contextKey`. A missing image/project may be omitted rather than explicitly null.

With an image, `selectionCount` is the full count; `selection` is a preview of at most 100 and has an explicit truncation indicator. The measurement-name preview is bounded; use `analysis_catalog` for discovery. Category counts and `class:*` counts overlap, so do not sum every count key. Unknown calibration is not a usable number.

`adapterReadiness` compares the loaded extension with completed build outputs. Matching gateway schemas alone do not prove the right Java classes are loaded. A build identity match does not assess unbuilt edits or scientific validity. Refresh after user interaction and after mutations; do not reuse old IDs or context as if the session were immutable.

```json
{
  "name": "qupath_context",
  "arguments": {}
}
```

<a id="qupath-objects"></a>

## qupath_objects

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `objects`. [Exact schema](../TOOL-REFERENCE.md#qupath-objects).

Read exact IDs, names, existing classifications and ROI geometry. Common filters are described below. The example requires a primary selected region; it lists cells whose centroids fall inside that ROI on the same z/t plane.

Returns an untruncated `total`, `offset` and bounded `objects`. Default limit is 100; maximum 500. Request successive offsets to inspect a larger population. The population can change between pages; refresh context and restart if consistency matters. ROI coordinates and area are image pixels and pixel-squared, with z/t attached.

`kind: detection` includes cells through QuPath's detection inheritance. Do not add detection and cell totals as disjoint populations. These rows describe objects, not all their measurements; use `measurements` or `measurement_summary` next.

```json
{
  "name": "qupath_objects",
  "arguments": {
    "scope": "region",
    "kind": "cell",
    "offset": 0,
    "limit": 100
  }
}
```

<a id="qupath-measurements"></a>

## qupath_measurements

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `measurements`. [Exact schema](../TOOL-REFERENCE.md#qupath-measurements).

Read stored values for the matching objects, without inferring them from appearance. Returns `total`, `rows` containing object identity and measurement maps, and `truncated`. Non-finite values are marked rather than invented.

The default is 50 rows and the maximum 200. There is no offset argument on this tool. A bounded preview is insufficient for a whole-population mean or percentage; use `measurement_summary` for aggregate calculations. An empty selection produces no selected-cell measurements, even when a selected parent annotation contains many cells: use `scope: region` for the latter.

Keep measurement names and units exactly as stored. Their existence does not validate the segmentation or establish a biological interpretation.

```json
{
  "name": "qupath_measurements",
  "arguments": {
    "scope": "selection",
    "kind": "cell",
    "limit": 50
  }
}
```

<a id="qupath-measurement-summary"></a>

## qupath_measurement_summary

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `measurement_summary`. [Exact schema](../TOOL-REFERENCE.md#qupath-measurement-summary).

First discover the actual measurement names with `analysis_catalog`; the example name is illustrative, not universally available. Computes descriptive statistics across the complete matching scope, not the measurement preview. Supports 1–20 unique names, 1–50 bins (default 10) and optional exact-class groups.

Returns the population total, `sampled: false`, statistics/histograms and captured image/context/scope. Finite-value statistics include count, min/max/mean and sample standard deviation using n−1; missing/non-finite counts remain explicit. The final histogram upper edge is inclusive. Missing/undefined statistics must not be treated as zero. If a nonempty scope lacks a requested measurement entirely, the call fails.

Limits are 1,000,000 objects and 5,000,000 object-measurement values. Up to 50 class groups are returned, with group count/truncation metadata. The adapter captures values on JavaFX before doing arithmetic off that thread. Results describe that captured population, not a later changed image.

```json
{
  "name": "qupath_measurement_summary",
  "arguments": {
    "scope": "region",
    "kind": "cell",
    "measurements": [
      "Nucleus: DAB OD mean"
    ],
    "histogramBins": 10,
    "groupByClass": true
  }
}
```

<a id="qupath-analysis-catalog"></a>

## qupath_analysis_catalog

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `analysis_catalog`. [Exact schema](../TOOL-REFERENCE.md#qupath-analysis-catalog).

Requires an active image. Discovers exact stored measurement names/classes, the current image's watershed cell-detection parameter definitions and recent workflow step names. Parameter metadata includes available choices, units, ranges and defaults; discovery does not run detection.

The scan permits up to 1,000,000 objects, with bounded name/class collections (10,000 each). Returned name/class previews are capped at 500 each and expose counts/truncation. Workflow history returns the latest 30 steps with its full count and truncation information.

Use this instead of guessing a measurement spelling or applying parameters from a different image type. A catalog entry establishes availability, not suitable settings, scientific validity or successful completion of a past workflow.

```json
{
  "name": "qupath_analysis_catalog",
  "arguments": {}
}
```

<a id="qupath-project-images"></a>

## qupath_project_images

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `project_images`. [Exact schema](../TOOL-REFERENCE.md#qupath-project-images).

Lists entries in the currently open project without loading or analyzing another image. With no project, returns `open: false`, zero total and an empty list. An open project returns its identity and entries containing IDs, names, opaque source fingerprints, active-entry status and saved-data availability.

Default limit is 100; maximum 200. Use offsets or query a known exact `entryId`. Keep each entry's ID and fingerprint together. The fingerprint binds its observed source configuration; do not construct one from a file name.

To switch images, pass those exact values to `open_project_image` and obtain human approval. Listing a project does not prove that every image can be opened or that a saved analysis is valid.

```json
{
  "name": "qupath_project_images",
  "arguments": {
    "offset": 0,
    "limit": 100
  }
}
```

<a id="qupath-open-path"></a>

## qupath_open_path

**Risk:** `code` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `open_path`. [Exact schema](../TOOL-REFERENCE.md#qupath-open-path).

Requests native opening of an existing readable absolute local image file or `.qpproj`. The path here is a placeholder. URLs, directories and `.qpdata` are excluded. Current unsaved image changes and blocking dialogs must be resolved normally; the tool does not discard work.

This is a `code` operation because native readers/project workflows can have wider effects. It returns a human-review proposal first. Execution revalidates the path/context, then reports dispatch with `completionVerified: false` rather than claiming an image is already open.

Observe `context` and `ui` afterward. QuPath may ask for an image reader, project import or image interpretation. The correct completion check is the intended active image/project and expected metadata after those prompts. A timeout, returned dispatch or closed dialog alone does not establish completion. Hierarchy checkpoints do not undo file-opening/settings effects.

```json
{
  "name": "qupath_open_path",
  "arguments": {
    "path": "/ABSOLUTE/PATH/sample.ndpi"
  }
}
```

<a id="qupath-open-project-image"></a>

## qupath_open_project_image

**Risk:** `code` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `open_project_image`. [Exact schema](../TOOL-REFERENCE.md#qupath-open-project-image).

Requires a current project and an exact entry ID plus source fingerprint from `project_images`. Both values in this example are placeholders. Rejects a changed source, missing entry or unsaved current image; do not retry using an invented fingerprint.

After human approval, opens through QuPath's native project-image workflow. An already active entry can return `alreadyActive`; a newly dispatched open reports `completionVerified: false` and may still present reader/type dialogs.

Refresh context and project listing to verify the active entry and actual image metadata. If the source changed, rediscover and submit a new proposal. The old approval never authorizes a different source. This operation is navigation, not a project-wide batch-analysis endpoint.

```json
{
  "name": "qupath_open_project_image",
  "arguments": {
    "entryId": "ENTRY_ID_FROM_PROJECT_IMAGES",
    "entryFingerprint": "0000000000000000000000000000000000000000000000000000000000000000"
  }
}
```

<a id="qupath-inspect-import"></a>

## qupath_inspect_import

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `inspect_import`. [Exact schema](../TOOL-REFERENCE.md#qupath-inspect-import).

Requires an active destination image. Reads a local `.geojson`/`.json` and validates leaf annotations, geometry, IDs, planes and image bounds without importing. Accepts a Feature, feature array or FeatureCollection; excludes detections, TMA structures, archives and arbitrary serialized objects.

Returns the resolved path, exact-byte SHA-256, annotation/class counts, bounded preview, coordinate-space explanation and geometry-conversion summary. Limits: 16 MiB, 10,000 features and 64 nesting levels. Preview is at most 20 annotations and 50 classes; observe the truncation flags.

Coordinates must already represent full-resolution image pixels; there is no CRS/physical-unit conversion. QuPath geometry precision can quantize to 0.01 pixels; conversion information is review material, and lost holes/components are refused. Missing feature IDs get deterministic IDs based on file hash/index. Inspecting is read-only and grants no import approval.

```json
{
  "name": "qupath_inspect_import",
  "arguments": {
    "path": "/ABSOLUTE/PATH/annotations.geojson"
  }
}
```

<a id="qupath-import-annotations"></a>

## qupath_import_annotations

**Risk:** `data` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `import_annotations`. [Exact schema](../TOOL-REFERENCE.md#qupath-import-annotations).

Use the exact inspected path/hash; the zero hash above is only a schema-valid placeholder. Runtime inspects before proposing and refuses changed bytes. The proposal includes the actual preview and coordinate/conversion information for human review.

After approval, the adapter revalidates the reviewed bytes and destination context, checks geometry/planes/bounds and refuses IDs already present in the hierarchy. It adds annotations after an automatic hierarchy checkpoint; it does not overwrite an existing object with the same ID or rewrite the source file.

Successful execution returns the imported count/hash, hierarchy counts, source-file-unchanged indication, checkpoint and context. Verify IDs, classes, geometry, plane and any expected measurements using observation tools. Repeated import is not idempotent success: duplicates are rejected. A changed file needs a fresh inspection and approval.

```json
{
  "name": "qupath_import_annotations",
  "arguments": {
    "path": "/ABSOLUTE/PATH/annotations.geojson",
    "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
    "coordinateSpace": "image_pixels"
  }
}
```

<a id="qupath-select"></a>

## qupath_select

**Risk:** `view` · **Human approval:** No · **Route:** Java operation `select`. [Exact schema](../TOOL-REFERENCE.md#qupath-select).

Changes the actual QuPath selection immediately. Uses the common filters; returns the selected count and new context. When multiple objects match, the first becomes primary. Selection changes can invalidate an existing proposal bound to the previous selection.

An empty filter selects all matching objects. In particular, `ids: []` means no ID restriction, not “clear selection”. Do not use an empty request as a deselect command. Prefer exact IDs obtained from the current image when resolving “those cells”.

Selection does not modify classifications or measurements. Use `scope: region` to select objects inside a primary selected ROI; `scope: selection` filters only the objects already selected. Verify the count and primary selection before making a subsequent risky request.

```json
{
  "name": "qupath_select",
  "arguments": {
    "ids": [
      "OBJECT_ID_FROM_OBJECTS"
    ]
  }
}
```

<a id="qupath-focus"></a>

## qupath_focus

**Risk:** `view` · **Human approval:** No · **Route:** Java operation `focus`. [Exact schema](../TOOL-REFERENCE.md#qupath-focus).

Moves the real viewer immediately. Target precedence is an explicit `objectId`, otherwise both x and y, otherwise the current selected object. Always send both coordinates for a coordinate target. A partial coordinate pair is not a valid way to move one axis; it falls back to selection behavior.

Coordinates are full-resolution image pixels. Object/selection focus uses its ROI z/t plane; x/y focus keeps the viewer's current plane. Downsample is 0.05–256, and smaller values zoom in. Focusing does not select the target.

Returns the resulting center/downsample and, when available, pointer screen coordinates. JavaFX screen coordinates are a different coordinate system from image pixels; the native overlay maps them to displays. A missing on-screen pointer is not a fabricated click location. Inspect context/snapshot for the actual view.

```json
{
  "name": "qupath_focus",
  "arguments": {
    "x": 1000,
    "y": 1500,
    "downsample": 2
  }
}
```

<a id="qupath-overlays"></a>

## qupath_overlays

**Risk:** `view` · **Human approval:** No · **Route:** Java operation `overlays`. [Exact schema](../TOOL-REFERENCE.md#qupath-overlays).

Changes display immediately: detection/annotation visibility, detection filling, opacity (0–1) and an exact existing-class filter. An empty classification string clears the class filter. Use real class labels from the image catalog.

The classification predicate keeps annotations visible and filters non-annotation objects. `fill` controls detection filling; it is not a general annotation-fill setting. Returns an update acknowledgement, not new analysis counts.

This can show only Positive detections, but it neither assigns that class nor removes hidden cells. Re-read counts or measurements for analysis; use a viewer snapshot to confirm the display. An overlay change is not an explanation of what a positive stain means biologically.

```json
{
  "name": "qupath_overlays",
  "arguments": {
    "detections": true,
    "annotations": true,
    "fill": true,
    "opacity": 0.65,
    "classification": "Positive"
  }
}
```

<a id="qupath-snapshot"></a>

## qupath_snapshot

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `snapshot`. [Exact schema](../TOOL-REFERENCE.md#qupath-snapshot).

Captures the current QuPath viewer, scaled to at most 1600 pixels wide while preserving aspect ratio. It is a viewport image, not the whole-slide dataset or a screenshot of every desktop window.

MCP returns a PNG image content block (`type: image`, `mimeType: image/png`), unlike the JSON-in-text result used by other tools. The gateway HTTP representation includes the encoded image data and source information; clients should handle the transport distinction.

Use for visible confirmation of navigation and contours. Numeric measurements must still come from the measurement tools. A snapshot can contain identifiable research imagery; it inherits the data's sharing restrictions and does not authorize uploading it elsewhere.

```json
{
  "name": "qupath_snapshot",
  "arguments": {}
}
```

<a id="qupath-commands"></a>

## qupath_commands

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `commands`. [Exact schema](../TOOL-REFERENCE.md#qupath-commands).

Discovers live menu paths with enabled status and approval information. Query is a case-insensitive substring; omit it to inspect a broader list. The result is bounded at 300 menu entries without a complete-menu total/truncation flag, so a full-length response is not evidence of exhaustive discovery.

Copy the exact path into `run_command`. Installed plugins/version affect availability; do not treat a menu path from another installation as verified here. Discovery does not execute a command, open a dialog or validate its future side effects.

If no narrow structured tool covers the task, use knowledge plus live menus to choose an appropriate route. Disabled, ambiguous or changed commands must be re-inspected rather than forced.

```json
{
  "name": "qupath_commands",
  "arguments": {
    "query": "measurements"
  }
}
```

<a id="qupath-ui"></a>

## qupath_ui

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `ui`. [Exact schema](../TOOL-REFERENCE.md#qupath-ui).

Inspects supported visible JavaFX windows and controls, including titles, IDs, types, enabled state, values/options, bounds and a dialog fingerprint. Discovery is bounded (roughly 500 controls per window, up to 100 options per control and selected-item previews up to 20 with count/digest information).

Password content is redacted. Source editors expose metadata such as length/hash/caret rather than arbitrary source content. Native OS dialogs, custom canvases and every third-party widget are not universally inspectable.

Use observed IDs/labels/options for a proposed `ui_action`. Bounds are descriptive, not an arbitrary mouse-click tool. UI state can change after inspection; the gateway captures the reviewed control/dialog state and the adapter checks it again before acting.

```json
{
  "name": "qupath_ui",
  "arguments": {}
}
```

<a id="qupath-ui-action"></a>

## qupath_ui_action

**Risk:** `code` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `ui_action`. [Exact schema](../TOOL-REFERENCE.md#qupath-ui-action).

Requires the exact observed node ID and action: `fire` for a supported button, `set_text` for an ordinary text input, or `select` for an exact unique combo/choice/tab option. Supply the intended value for editing/selecting. Values are bounded to 32,000 characters.

Even text entry requires human review because UI controls may trigger broader effects. The proposal includes the observed window/control fingerprint. Missing/disabled/changed controls, ambiguous options, password fields, source-editor writes and targets behind another modal are refused.

Returns a checkpoint wrapper and dispatch result with `completionVerified: false`. Inspect UI and scientific state afterward; a button may open another dialog. A hierarchy checkpoint cannot undo saving, importing, settings or plugin side effects. To run code, use the explicit reviewed `run_script` path rather than an editor Run button.

```json
{
  "name": "qupath_ui_action",
  "arguments": {
    "nodeId": "NODE_ID_FROM_UI",
    "action": "select",
    "value": "OPTION_FROM_UI"
  }
}
```

<a id="qupath-annotate"></a>

## qupath_annotate

**Risk:** `data` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `annotate`. [Exact schema](../TOOL-REFERENCE.md#qupath-annotate).

Creates a rectangle (default) or ellipse in full-resolution image pixels on the active z/t plane. Width/height must be positive and the entire region must fit within the current image. The sample dimensions are illustrative; check actual bounds before proposing.

Requires human approval and creates an automatic hierarchy checkpoint. Successful execution returns the new object description, checkpoint and context; the annotation becomes selected/primary. Verify its exact ID and ROI before using it as a detection region.

This structured operation does not draw arbitrary polygons, label tissue automatically or identify a biological region. Use native QuPath drawing for manual contours or a separately reviewed suitable import/script route.

```json
{
  "name": "qupath_annotate",
  "arguments": {
    "shape": "rectangle",
    "x": 100,
    "y": 100,
    "width": 200,
    "height": 200,
    "name": "Review region"
  }
}
```

<a id="qupath-detect-cells"></a>

## qupath_detect_cells

**Risk:** `data` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `detect`. [Exact schema](../TOOL-REFERENCE.md#qupath-detect-cells).

Runs the installed watershed cell-detection plugin in selected annotation parents after human approval. Inspect image type/calibration and `analysis_catalog` first. The example reproduces parameters used for a public H-DAB fixture; it is not a general scientific recommendation.

The nested parameter object accepts only the keys/types in the generated schema. Active plugin definitions further determine available parameters, choices and ranges; pixel/micron alternatives depend on current-image settings. An empty parameter object being schema-valid does not make defaults suitable.

Creates a hierarchy checkpoint, captures the approved parent objects and uses QuPath's task runner. Other mutations are refused while analysis is running. The result distinguishes `executionFinished` from `completionVerified: false`: plugins may log tile failures without throwing. Inspect contours, measurements, counts and native progress/logs. A timeout/cancel can leave partial effects; do not automatically rerun detection.

```json
{
  "name": "qupath_detect_cells",
  "arguments": {
    "parameters": {
      "detectionImageBrightfield": "Hematoxylin OD",
      "requestedPixelSizeMicrons": 0.5,
      "backgroundRadiusMicrons": 8,
      "medianRadiusMicrons": 0,
      "sigmaMicrons": 1.5,
      "minAreaMicrons": 10,
      "maxAreaMicrons": 400,
      "threshold": 0.1,
      "watershedPostProcess": true,
      "cellExpansionMicrons": 5,
      "includeNuclei": true,
      "smoothBoundaries": true,
      "makeMeasurements": true
    }
  }
}
```

<a id="qupath-classify"></a>

## qupath_classify

**Risk:** `data` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `classify`. [Exact schema](../TOOL-REFERENCE.md#qupath-classify).

Assigns fixed classes **Positive** for measurement ≥ threshold and **Negative** below it, replacing existing labels on eligible detections. The example measurement/threshold is fixture-specific; discover the actual name and justify a threshold with the researcher.

`classification` filters EXISTING classes. Do not set it to Positive to request positive output. The gateway obtains a real eligible-candidate count before proposing; an empty or unverifiable set produces no approval proposal. `kind: all` is narrowed to detections; annotations are not classifiable through this tool.

At execution all matched candidates must have a finite named measurement before labels change. A checkpoint is created; a failed precondition can leave that checkpoint without classification. Successful results include positive/negative counts, measurement, threshold, checkpoint and context. Verify their sum against the intended candidate population and inspect the result. This is threshold labeling, not diagnosis.

```json
{
  "name": "qupath_classify",
  "arguments": {
    "scope": "region",
    "kind": "cell",
    "measurement": "Nucleus: DAB OD mean",
    "threshold": 0.2
  }
}
```

<a id="qupath-image-type"></a>

## qupath_image_type

**Risk:** `data` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `image_type`. [Exact schema](../TOOL-REFERENCE.md#qupath-image-type).

Sets QuPath's image interpretation after human approval. Allowed values are BRIGHTFIELD_H_E, BRIGHTFIELD_H_DAB, BRIGHTFIELD_OTHER, FLUORESCENCE, OTHER and UNSET. Choose from dataset metadata and researcher intent, not a guessed stain from a screenshot.

Returns the image type and a warning with checkpoint/context information. A hierarchy checkpoint does **not** restore this setting or stain configuration. The action can change which detection parameters are available, so refresh the analysis catalog afterward.

It does not establish stain-vector calibration or validate measurement comparability. If QuPath's native opening workflow presents a type dialog, resolve that dialog appropriately and inspect the final context rather than assuming a separate tool call closed it.

```json
{
  "name": "qupath_image_type",
  "arguments": {
    "type": "BRIGHTFIELD_H_DAB"
  }
}
```

<a id="qupath-checkpoint"></a>

## qupath_checkpoint

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `checkpoint`. [Exact schema](../TOOL-REFERENCE.md#qupath-checkpoint).

Captures the active image's object hierarchy in memory, preserving IDs, classifications, measurements, metadata and hierarchy/TMA structures through native serialization. Returns its ID, label, counts and recovery-scope description.

Despite risk `read`, this writes the volatile checkpoint store. The maximum is six checkpoints across the extension, with up to 200,000 objects / 64 MiB serialized per checkpoint; an additional checkpoint can evict the oldest, including one for another image. MCP therefore does not mark it read-only or idempotent.

Not a project save or disk backup. Checkpoints disappear with the application session and do not recover files, image settings, viewer state or arbitrary external effects. Keep the returned ID; list again before relying on old recovery history.

```json
{
  "name": "qupath_checkpoint",
  "arguments": {
    "label": "Before analysis"
  }
}
```

<a id="qupath-checkpoints"></a>

## qupath_checkpoints

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `checkpoints`. [Exact schema](../TOOL-REFERENCE.md#qupath-checkpoints).

Lists retained in-memory hierarchy checkpoints, their IDs/labels/counts and whether each belongs to the current image. This observes the cache without creating a checkpoint.

An entry for another image is not restorable onto the active one. Switching away and back is not a promise that an old image identity/checkpoint remains usable; trust the current eligibility and actual tool validation. Eviction and application exit remove recovery options.

Use before compare/restore when a previous result is mentioned. If the named checkpoint is gone, report that honestly; do not substitute another baseline or claim universal undo.

```json
{
  "name": "qupath_checkpoints",
  "arguments": {}
}
```

<a id="qupath-compare"></a>

## qupath_compare

**Risk:** `read` · **Human approval:** No · **Route:** Java operation `compare`. [Exact schema](../TOOL-REFERENCE.md#qupath-compare).

Compares exact current object/class counts with a retained checkpoint for the same active image. Returns baseline/current/delta counts. It does not modify either hierarchy.

Count categories overlap: class-specific counts describe members already included in object-kind counts. Deltas are arithmetic differences, not object tracking, pixel differences, measurement distributions or matched-cell comparisons. Use measurement summaries captured at the relevant times for distribution comparisons.

The ID must come from a real returned/listed checkpoint. A missing, evicted or different-image checkpoint is an error, not a zero baseline.

```json
{
  "name": "qupath_compare",
  "arguments": {
    "checkpointId": "CHECKPOINT_ID_FROM_CHECKPOINTS"
  }
}
```

<a id="qupath-restore"></a>

## qupath_restore

**Risk:** `data` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `restore`. [Exact schema](../TOOL-REFERENCE.md#qupath-restore).

Proposes replacement of the active image's hierarchy with an eligible retained checkpoint. Requires human approval against the current context. The adapter deserializes the saved hierarchy, checkpoints the current hierarchy first and clears detached selection before replacement.

Returns the restored ID and counts. Refresh context afterward rather than assuming selection or a new context key was included in this result. Verify expected IDs/classes/measurements and the absence of unintended objects.

This is a hierarchy replacement, not general Undo. It does not undo files, type/stain settings, exports, arbitrary Groovy effects or operations in another image. Recovery history is bounded and volatile; creating the pre-restore checkpoint can itself evict an older entry.

```json
{
  "name": "qupath_restore",
  "arguments": {
    "checkpointId": "CHECKPOINT_ID_FROM_CHECKPOINTS"
  }
}
```

<a id="qupath-delete-selected"></a>

## qupath_delete_selected

**Risk:** `data` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `delete`. [Exact schema](../TOOL-REFERENCE.md#qupath-delete-selected).

Removes only the currently selected objects after human approval; children are retained. The sole accepted scope is the literal `selection`. There is no IDs argument or file-delete capability on this tool.

Inspect the exact selected IDs/count first. An automatic hierarchy checkpoint precedes deletion; TMA core removal is refused. Successful execution returns the removed count, checkpoint and new context, and clears selection.

Retaining children means removing a parent annotation does not necessarily remove its cells. Verify hierarchy counts and relationships before reporting what disappeared. Use restore only for an eligible same-image hierarchy checkpoint; it is not a file-recovery operation.

```json
{
  "name": "qupath_delete_selected",
  "arguments": {
    "scope": "selection"
  }
}
```

<a id="qupath-run-command"></a>

## qupath_run_command

**Risk:** `code` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `command`. [Exact schema](../TOOL-REFERENCE.md#qupath-run-command).

Invokes one exact enabled live menu path after human approval. Discover it with `commands`; do not invent separators, plugin names or labels. Ambiguous/disabled commands and incompatible modal state are refused, including revalidation before deferred dispatch.

Returns dispatch acknowledgement, `completionVerified: false` and hierarchy-checkpoint information when an image exists. Without an image, the checkpoint describes the absence of a hierarchy. Dispatch can open a dialog or start plugin work after the request returns.

Inspect `ui`, context and actual output to establish completion. Command effects may include file writes, settings or external plugin operations which a hierarchy checkpoint cannot reverse. Prefer a structured tool when it directly expresses the intended action.

```json
{
  "name": "qupath_run_command",
  "arguments": {
    "path": "EXACT_MENU_PATH_FROM_COMMANDS"
  }
}
```

<a id="qupath-run-script"></a>

## qupath_run_script

**Risk:** `code` · **Human approval:** Required; the call prepares a proposal · **Route:** Java operation `script`. [Exact schema](../TOOL-REFERENCE.md#qupath-run-script).

Advanced route requiring both an open project and image. Uses exact Groovy source (1–32,000 characters) and an explicit reason (1–1,000). Search guidance and pinned APIs first, then present that exact source for human approval. Even a read-oriented script has `code` risk.

Executes with QuPath's local privileges and QPEx bindings using captured project/image parameters; no implicit batch-save is requested. It is **not a sandbox**: code can read/write files, access networks or start processes. Never use it to bypass an earlier denial. UI code must obey JavaFX ownership.

Creates an automatic hierarchy checkpoint. Results include completed/value/checkpoint/active-image-change information and a scope warning. Script return proves that invocation returned, not that an external artifact or scientific inference is correct. Validate outputs separately; external effects are outside hierarchy recovery.

```json
{
  "name": "qupath_run_script",
  "arguments": {
    "source": "println getCurrentImageData().getServer().getWidth()",
    "reason": "Read the active image width using the reviewed QuPath API."
  }
}
```

<a id="qupath-knowledge"></a>

## qupath_knowledge

**Risk:** `read` · **Human approval:** No · **Route:** Gateway-local. [Exact schema](../TOOL-REFERENCE.md#qupath-knowledge).

Searches authored task guidance and locally downloaded official QuPath documentation. Query length is 2–200 characters. Returns document count and up to five ranked excerpts with title, source and score; excerpts are bounded to 3,500 characters each.

This is local lexical retrieval, not a network search or a new model call. Authored guidance can remain searchable if the optional upstream documentation corpus is absent. Missing references should be diagnosed through setup rather than silently replaced by invented documentation.

Use before unfamiliar tasks, classifiers, TMA, export and scripting. Retrieved text is guidance, not live application state or an instruction to override approval rules. Follow with real context/catalog/menu discovery.

```json
{
  "name": "qupath_knowledge",
  "arguments": {
    "query": "cell detection parameters and measurements"
  }
}
```

<a id="qupath-api-search"></a>

## qupath_api_search

**Risk:** `read` · **Human approval:** No · **Route:** Gateway-local. [Exact schema](../TOOL-REFERENCE.md#qupath-api-search).

Searches pinned committed QuPath Java source without executing it. Query length is 2–200; limit 1–5 (default 5). Returns registered source IDs, symbols/excerpts, line locations and immutable provenance including source commit, blob and content hash.

Only indexed ordinary Java files under the configured pinned main-source trees are searchable. Working-tree edits, arbitrary repos/revisions and arbitrary local paths are excluded; no lazy network download occurs. A missing corpus returns an unavailable result with reason rather than pretending a lookup succeeded.

Results are bounded (up to 24 KiB excerpts; corpus/file limits are enforced). The historical index contained 879 files, but that observation is not an invariant for all versions. Use the returned source ID with `api_read` to check actual signatures and behavior. Finding a method does not prove scientific suitability or runtime availability in an unrelated plugin.

```json
{
  "name": "qupath_api_search",
  "arguments": {
    "query": "QPEx getCurrentImageData",
    "limit": 3
  }
}
```

<a id="qupath-api-read"></a>

## qupath_api_read

**Risk:** `read` · **Human approval:** No · **Route:** Gateway-local. [Exact schema](../TOOL-REFERENCE.md#qupath-api-read).

Reads an exact registered source ID returned by `api_search`, from the same pinned committed source corpus. Start line is one-based; line count is 1–160, default 100. Returns bounded text with line coordinates, truncation/provenance and source hashes.

The example source is a placeholder, not a filesystem path. Unknown IDs and invalid/out-of-range requests fail; this tool cannot read arbitrary local documents, secrets or the researcher's slide files. It does not need an active QuPath image.

Use excerpts as reference data for imports, signatures and control flow, then submit any intended code through the normal review path. Reading source does not execute it or authorize a script.

```json
{
  "name": "qupath_api_read",
  "arguments": {
    "source": "SOURCE_ID_FROM_API_SEARCH",
    "startLine": 1,
    "lineCount": 100
  }
}
```

<a id="qupath-action-status"></a>

## qupath_action_status

**Risk:** `read` · **Human approval:** No · **Route:** Gateway-local. [Exact schema](../TOOL-REFERENCE.md#qupath-action-status).

Reads an actual proposal/action ID returned by a risky call. Returns the retained action record: tool/arguments/risk, timestamps, context, status and eventual result/error or audit warning. Pending is not completion; denied/expired must not be treated as permission to retry automatically.

Status observation also settles elapsed pending deadlines. That may append local audit metadata, but it never dispatches or approves a scientific action. Completed outcomes may contain dispatch-only or plugin-verification limitations.

History is bounded and belongs to the current gateway process. After restart/pruning, an unknown ID does not prove nothing happened. Inspect QuPath and the local audit record before any replacement request. A terminal `auditWarning` means the actual result remains authoritative in memory while durable journal persistence is unconfirmed.

```json
{
  "name": "qupath_action_status",
  "arguments": {
    "actionId": "ACTION_ID_FROM_PROPOSAL"
  }
}
```
