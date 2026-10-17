# Worked QuPath workflows

These sequences describe real tool composition, including human decisions and native prompts. They are not scripts to run on arbitrary research data. Exact call examples for each tool are in the [tool guide](TOOLS.md); runtime schemas come from live MCP discovery.

## A conversation turn

For “show those positive cells”, read context to resolve the active image, selected region and prior result. Inspect actual classes/IDs rather than interpreting “positive” as a visual guess. Use a view action to select/focus/filter the intended existing objects, then verify the visible result. Keep the user-facing response short: report what actually happened and any necessary next decision.

For “analyze this”, establish what analysis, region and measurement the researcher intends. Use knowledge and the live analysis catalog to propose concrete settings. The scientific model must not invent a threshold or claim the analysis ran while a proposal is waiting. See the detection sequence below.

For “undo that”, identify the preceding operation and its real recovery scope. Hierarchy replacement may be available; opening, exporting and changing settings need different handling. Never turn conversational context into a universal Undo guarantee.

## Open an image or project

| Step | Tool or human action | Required observation |
|---|---|---|
| 1 | `qupath_context`, optionally `qupath_ui` | Current image/project, unsaved work and blocking prompts |
| 2 | Researcher identifies the exact local path | No guessed file or folder; existing readable absolute image or `.qpproj` |
| 3 | `qupath_open_path` | Proposal for that path; no completed opening yet |
| 4 | Researcher approves in Luma | Exact proposal and destination context |
| 5 | `qupath_action_status` | Actual execution outcome; native dispatch may still be incomplete |
| 6 | `qupath_ui`, human or separately approved supported UI actions | Reader/import/image-type prompts resolved normally |
| 7 | `qupath_context`, `qupath_snapshot` | Intended image/project and expected dimensions/metadata, actual visible viewer |

If current work is unsaved, save or close it normally before preparing a fresh opening proposal. An approval does not authorize discarding it. `.qpdata` needs QuPath's native workflow rather than `open_path`. URLs and folder batch-import are not accepted by this structured endpoint.

For an existing project entry, replace steps 2–3 with `qupath_project_images` followed by `qupath_open_project_image` using the exact observed entry ID/fingerprint. Re-list if the entry changed. Opening one entry is not permission for an unattended project-wide analysis.

## Navigate, highlight and inspect measurements

1. Read `qupath_context`; for a region, confirm its ROI and z/t plane.
2. Read `qupath_analysis_catalog` for actual class and measurement names.
3. Use `qupath_objects` with a suitable scope/kind/existing-class filter. Page through results when exact object inspection is needed. Capture real IDs rather than positional descriptions from a preview.
4. Use `qupath_select` for an exact object set, `qupath_focus` for a target and `qupath_overlays` for visibility/fill/class filtering. These are immediate view operations. Focus alone does not change selection.
5. Read `qupath_snapshot` to confirm the viewport. Use `qupath_measurements` for a bounded row preview and `qupath_measurement_summary` for full-scope statistics.
6. State the actual population and measure. If rows/groups are truncated, say so. Do not compute a whole-image mean from the first 50 rows.

Selecting an annotation then requesting `scope: selection, kind: cell` returns selected cells, not its children. Use `scope: region, kind: cell` to query cell centroids inside the primary selected ROI. Existing-class filtering is exact and does not assign labels.

## Detect, classify and compare

| Stage | Operation | Verification or decision |
|---|---|---|
| Establish baseline | Context, analysis catalog, image metadata, optional `qupath_checkpoint` | Actual region, calibration, interpretation and eligible recovery scope |
| Set interpretation if necessary | `qupath_image_type` | Separate exact human approval; refresh catalog afterward; hierarchy undo excludes this setting |
| Define region | Mouse selection or approved `qupath_annotate` | Correct ROI bounds/plane and exact selected ID |
| Detect | `qupath_detect_cells` with reviewed current-image parameters | Human approval; await outcome; inspect native progress, contours, logs and counts |
| Inspect measured data | Catalog, measurements, full-scope summary | Actual measurement name/units, finite values and candidate population |
| Threshold | `qupath_classify` | Real candidate count in proposal, justified threshold, human approval |
| Display/compare | Overlays, snapshot, `qupath_compare` | Visible Positive/Negative labels and exact count deltas against retained baseline |
| Recover if requested | `qupath_restore` | New human approval, then context/objects verify hierarchy restoration |

The public OS-2 fixture previously produced 899 cells, 49 Positive and 850 Negative using a chosen nuclear DAB threshold of 0.2. Those are dated software-operation results for that particular workflow, not targets every new dataset should reproduce or clinically validated parameters. The [scientific agent test](../SCIENTIFIC-AGENT-TEST.md) explains its scope and test-only simulated decisions. Ordinary use still needs real human decisions.

Classification always assigns Positive/Negative. Its `classification` argument is an existing-label filter, so setting it to Positive does not request a Positive output. An empty or unverifiable candidate set is refused before a proposal. Check the selected region and current labels rather than broadening the scope without the researcher's intent.

Detection's `executionFinished` and an action's `completed` status do not certify every tile or segmentation boundary. If a plugin logs failures, progress is still active or the call times out, inspect the partial state before any retry. Keep scientific interpretation separate from observed numeric output.

## Import GeoJSON annotations

1. Confirm the intended destination image, dimensions and plane. Establish that the file uses full-resolution image-pixel coordinates; no automatic geospatial/physical conversion is performed.
2. Call `qupath_inspect_import` with its exact local path. Read the hash, annotation/class counts, preview, truncation and geometry-conversion information.
3. Submit `qupath_import_annotations` using that returned path/hash and literal `coordinateSpace: image_pixels`. The gateway previews the actual bytes before proposing.
4. Have the researcher review and approve. If bytes, context or destination change, inspect again and prepare a new proposal.
5. Read status, then inspect exact imported IDs, names, classifications, geometry, z/t and any expected values. Compare the hierarchy counts with the baseline.
6. If requested, restore the eligible hierarchy checkpoint after a separate approval. This does not rewrite or remove the source GeoJSON file.

Only supported leaf annotations are imported; detections, TMA structures and malformed/oversized/out-of-bounds geometry are refused. Existing duplicate IDs are rejected instead of overwritten. A no-ID feature receives a deterministic identity, so importing the same bytes twice is not a safe duplicate-creation mechanism.

The [isolated synthetic import test](../SYNTHETIC-IMPORT-TEST.md) verifies a particular real-QuPath import round trip. Its permission and simulated decisions apply only to that fixture; documentation examples are not blanket test authorization.

## Operate an unfamiliar menu or dialog

1. Search `qupath_knowledge` for the task and discover `qupath_commands` for installed menus.
2. Prefer a structured tool if it expresses the operation directly. Otherwise submit the exact enabled menu path through `qupath_run_command` for human review.
3. After dispatch, inspect `qupath_ui` and actual context. A native dialog is an intermediate state, not success.
4. For a supported control, prepare one `qupath_ui_action` using its observed node ID/action/value. The researcher reviews the captured control/window; execution refuses changed controls or modal ownership.
5. Re-inspect after each action. If an OS dialog or custom widget is unsupported, hand that specific interaction to the researcher rather than claiming universal control.
6. Verify the task-specific outcome: active data, measured result, exact settings or resulting file through an appropriate route. A button acknowledgement alone is insufficient.

UI actions are not arbitrary cursor clicking. Source-editor writes/Run shortcuts are excluded so that exact code remains visible through the dedicated script approval path.

## Use the reviewed scripting route

Search authored/official guidance with `qupath_knowledge`, find exact methods with `qupath_api_search`, then read registered excerpts with `qupath_api_read`. Check provenance, imports, argument types and thread ownership. A source match establishes a reference, not a runtime plugin or valid scientific method.

With an open project and image, propose the minimal exact Groovy source and reason through `qupath_run_script`. The researcher must see and approve the actual code. Explain any file/network/process effects; hierarchy checkpoints cannot reverse them. Never turn a refused structured action into a script to evade that refusal. Inspect actual outputs after script return before reporting completion.

## Handle interruption and recovery

Stop can cancel queued/cooperative agent work and suppress late answers. It does not roll back already executed operations or decide pending proposals. Native detection cancellation and an MCP client disconnect are separate events; inspect QuPath before claiming analysis stopped.

When a result is uncertain: retain the action ID, read status, observe context/UI, compare against known checkpoints, and reconcile the actual state. Do not automatically retry mutations. For recovery, list retained checkpoints, confirm the same-image eligibility and exact target, obtain a new approval for restore, and verify the resulting hierarchy. If recovery is unavailable, say what is missing instead of inventing a previous result.
