# QuPath workflow coverage

Every common QuPath 0.7 workflow, the route Penny's agent takes for it, and how far that route has been verified. Routes are tried in this order: a **structured tool** (exact JSON, validated, checkpointed, result includes a `highlight` so the cursor shows what happened), then a **menu command** (`qupath_commands` → `qupath_run_command`, driving its dialog with `qupath_ui` → `qupath_ui_action`), then **Groovy** (`qupath_run_script` with a recipe from [skills/qupath/references/recipes.md](../skills/qupath/references/recipes.md), verified against the pinned QuPath source with `qupath_api_search`). The agent never says "QuPath can't do that" before checking `qupath_commands` and `qupath_knowledge`.

Menu paths are the exact `qupath_commands` form (`Menu > Submenu > Command`), which matches the official [command reference](https://qupath.readthedocs.io/en/0.7/docs/reference/commands.html) with `-->` replaced by `>`. The audit of the local official docs that produced this table found that the docs script only stains, channel names, pixel size, GeoJSON/image export, the measurement exporter and deep-learning extensions; everything else is dialog-only there, so the Groovy below comes from the pinned source, not the docs.

Status key: **real-app** = exercised against the running QuPath through the gateway; **contract** = compiled against QuPath 0.7 and covered by an isolated Java/gateway test, not yet run in the live application; **untested** = route exists (menu path or API verified in source) but has not been executed by Penny.

## Images and projects

| Workflow | Penny route | Status |
|---|---|---|
| Find a slide by name | `qupath_find_images` | real-app |
| Open an image or `.qpproj` | `qupath_open_path` (native reader/type dialogs via `qupath_ui` → `qupath_ui_action` Apply) | real-app |
| Open another project image | `qupath_project_images` → `qupath_open_project_image` | real-app (isolated QuPathGUI) |
| Save image data into the project | `qupath_save` (`entry.saveImageData` + `project.syncChanges`) | contract |
| Save as `.qpdata` outside a project | `qupath_run_command "File > Save As"` + OS file dialog (researcher) | untested |
| Create project / add images / remove images | `qupath_run_command "File > Project... > Create project"`, `"File > Project... > Add images"` (OS dialogs need the researcher); Groovy recipe `Projects.createProject` + `ImageServerProvider`/`ProjectCommands.addImage` in recipes.md | untested |
| Project metadata, URIs, image list export | `qupath_run_command "File > Project... > Edit project metadata"`, `"... > Check project URIs"`, `"... > Export image list"` | untested |
| Reload saved data | `qupath_run_command "File > Reload data"` | untested |
| Set pixel size | Groovy `setPixelSizeMicrons(x, y)` recipe | untested |
| List images / batch over a project | `qupath_project_images`; Groovy project-loop recipe; `qupath_save_workflow` + Automate > Script editor Run for project | real-app (list), untested (batch) |

## Image type and stains

| Workflow | Penny route | Status |
|---|---|---|
| Set image type | `qupath_image_type` | real-app |
| Set stain vectors (preset or explicit) | `qupath_set_stains {preset: "H_DAB"}` or explicit `stains` | contract |
| Estimate stain vectors automatically | `qupath_estimate_stains` (QuPath `EstimateStainVectors`, selected region or visible area); dialog route `qupath_run_command "Analyze > Estimate stain vectors"` | contract |
| Set channel names / colours (fluorescence) | Groovy `setChannelNames(...)`, `setChannelColors(...)` recipe | untested |
| Read current stains | `qupath_context` → `image.stains` | contract |

## Annotations and objects

| Workflow | Penny route | Status |
|---|---|---|
| Draw a rectangle/ellipse region | `qupath_annotate` (position/size inferred from the viewer) | real-app |
| Full-image annotation | `qupath_run_command "Objects > Annotations... > Create full image annotation"`; Groovy `createFullImageAnnotation(true)` | untested |
| Polygon / arbitrary ROI | Groovy `ROIs.createPolygonROI` recipe | untested |
| Merge selected annotations | `qupath_edit_objects {action: "merge"}` (`QP.mergeAnnotations`) | contract |
| Split disconnected parts | `qupath_edit_objects {action: "split"}` (`RoiTools.splitROI`) | contract |
| Lock / unlock | `qupath_edit_objects {action: "lock" / "unlock"}` | contract |
| Set class of annotations or detections | `qupath_edit_objects {action: "set_class", value}` | contract |
| Rename / duplicate | `qupath_edit_objects {action: "rename" / "duplicate"}` | contract |
| Fill holes / expand or shrink | `qupath_edit_objects {action: "fill_holes" / "expand", distance or distanceMicrons}`; plugin route `qupath_run_plugin {plugin: "dilate_annotations" / "fill_annotation_holes" / "refine_annotations"}` | contract |
| Make inverse, simplify, transform, split by lines | `qupath_run_command "Objects > Annotations... > Make inverse"`, `"... > Simplify shape"`, `"... > Transform annotations"`, `"... > Split annotations by lines"`; Groovy `makeInverseAnnotation`, `simplifySelectedAnnotations`, `splitSelectedAnnotationAreasByLines` | untested |
| Select by class / kind / region / IDs | `qupath_select` | real-app |
| Select by measurement | Groovy `selectObjects { it.getMeasurementList().get("…") > x }` recipe | untested |
| Delete selected | `qupath_delete_selected` | real-app |
| Delete by kind / class / scope | `qupath_delete_objects` (TMA cores refused) | contract |
| Insert into / resolve hierarchy | `qupath_run_command "Objects > Annotations... > Resolve hierarchy"`; Groovy `resolveHierarchy()` | untested |
| Import annotations (GeoJSON) | `qupath_inspect_import` → `qupath_import_annotations` | real-app (isolated QuPathGUI) |
| Undo | `qupath_checkpoints` → `qupath_restore` (hierarchy only), `qupath_compare` | real-app |

## Detection

| Workflow | Penny route | Status |
|---|---|---|
| Cell / nucleus detection (watershed) | `qupath_detect_cells` (validated parameters, `qupath_analysis_catalog` for defaults) | real-app (899 cells) |
| Positive cell detection | `qupath_detect_positive_cells` (`PositiveCellDetection`: thresholdCompartment, thresholdPositive1/2/3, singleThreshold) | contract |
| Cell membrane detection, subcellular detection, fast cell counts | `qupath_run_plugin {plugin: "cell_membrane_detection" / "subcellular_detection" / "fast_cell_counts"}` | contract (allow-list, parameter validation) |
| Simple tissue detection | `qupath_run_plugin {plugin: "tissue_detection"}` | contract |
| Positive pixel count | `qupath_run_plugin {plugin: "positive_pixel_count"}` | contract |
| StarDist / InstanSeg / WSInfer | Groovy recipes (extension must be installed; check `qupath_commands` for `Extensions >`) | untested |

## Classification

| Workflow | Penny route | Status |
|---|---|---|
| Threshold one measurement → Positive/Negative | `qupath_classify {measurement, threshold}` | real-app |
| Intensity classes Negative/1+/2+/3+ (H-score style) | `qupath_classify {measurement, thresholds: [t1, t2, t3]}` (`PathObjectTools.setIntensityClassifications`, keeps base class) | contract |
| Count / percentage by class | `qupath_class_summary` (falls back to the whole image when nothing is selected) | real-app |
| Train an object classifier | `qupath_run_command "Classify > Object classification > Train object classifier"` and drive the dialog (`qupath_ui_action`); training annotations via `qupath_edit_objects set_class` | untested |
| Single-measurement / composite classifier | `qupath_run_command "Classify > Object classification > Create single measurement classifier"` / `"... > Create composite classifier"` | untested |
| Apply a saved object classifier | `qupath_classifiers` → `qupath_apply_object_classifier {name}` | contract |
| Reset detection classifications | `qupath_run_command "Classify > Object classification > Reset detection classifications"`; Groovy `resetDetectionClassifications()` | untested |
| Create thresholder / train pixel classifier | `qupath_run_command "Classify > Pixel classification > Create thresholder"` / `"... > Train pixel classifier"`, then the dialog; save it in the project | untested |
| Apply a saved pixel classifier: annotations, detections, area measurements, classify detections | `qupath_apply_pixel_classifier {name, mode}` | contract |
| Classify tiles → annotations | `qupath_run_plugin {plugin: "tiles_to_annotations"}` | contract |

## TMA, tiles and features

| Workflow | Penny route | Status |
|---|---|---|
| TMA dearray | `qupath_run_plugin {plugin: "tma_dearray", parameters: {coreDiameterMM, labelsHorizontal, labelsVertical, ...}}` | contract |
| Specify / edit / relabel / delete TMA grid | `qupath_run_command "TMA > Specify TMA grid"`, `"TMA > Add... > ..."`, `"TMA > Relabel TMA grid"`, `"TMA > Delete TMA grid"` | untested |
| TMA measurements / data viewer | `qupath_run_command "Measure > Show TMA measurements"`, `"File > TMA data... > Export TMA data"`; `qupath_export_measurements {kind: "all"}` filtered by class | untested |
| Create tiles | `qupath_run_plugin {plugin: "tiles", parameters: {tileSizeMicrons or tileSizePx, trimToROI, makeAnnotations}}` | contract |
| SLIC / DoG superpixels | `qupath_run_plugin {plugin: "superpixels_slic" / "superpixels_dog"}` | contract |
| Add intensity / shape / smoothed / Haralick features | `qupath_run_plugin {plugin: "intensity_features" / "shape_features" / "smoothed_features" / "haralick_features"}` | contract |
| Delaunay clusters, distances to annotations | `qupath_run_plugin {plugin: "delaunay_clusters"}`; `qupath_run_command "Analyze > Spatial analysis > Distance to annotations 2D"`; Groovy `detectionToAnnotationDistances(true)` | contract / untested |

## Measurements, maps and export

| Workflow | Penny route | Status |
|---|---|---|
| Read measurements / distributions | `qupath_measurements`, `qupath_measurement_summary`, `qupath_analysis_catalog` | real-app |
| Measurement tables | `qupath_run_command "Measure > Show annotation measurements"` / `"... > Show detection measurements"` | real-app (command discovery), untested (dispatch) |
| Measurement map | `qupath_display {measurementMap: "Nucleus: DAB OD mean"}` (`""` clears); dialog `qupath_run_command "Measure > Show measurement maps"` | contract |
| Export measurements CSV | `qupath_export_measurements {kind, scope, columns}` → `.local/exports/*.csv` (same columns as Measure > Export measurements) | contract |
| Export objects as GeoJSON | `qupath_export_annotations` → `.local/exports/*.geojson` | contract |
| Export view / region / whole image | `qupath_export_image {region: "view" / "selection" / "full", rendered}` | contract |
| Export OME-TIFF, SVG, snapshots | `qupath_run_command "File > Export images... > OME TIFF"` etc. (OS dialogs need the researcher) | untested |
| Density maps | `qupath_run_command "Analyze > Density maps > Create density map"` and drive the dialog; Groovy `DensityMaps.builder(...)` recipe | untested |

## Viewer and display

| Workflow | Penny route | Status |
|---|---|---|
| Navigate / zoom / point at things | `qupath_focus`, `qupath_highlight` (zooms to fit off-screen targets, `focused`) | real-app |
| Show / hide annotations, detections, names, fills, grid, TMA grid, pixel classification; opacity; cell display mode; show one class | `qupath_overlays` | real-app (basic), contract (new toggles) |
| Brightness / contrast, channel visibility, auto range, gamma, grayscale, invert | `qupath_display` | contract |
| Rotate image | `qupath_display {rotationDegrees}` | contract |
| Scalebar, slide overview, cursor location, mini/channel viewer, multi-view | `qupath_run_command "View > Show scalebar"`, `"View > Show slide overview"`, `"View > Show mini viewer"`, `"View > Multi-view... > ..."` | untested |
| Left panel tabs (Project, Image, Annotations, Hierarchy, Workflow) | `qupath_ui` → `qupath_ui_action {action: "select", value: "Annotations"}` on the TabPane | contract (JavaFX test), untested in app |
| Viewer snapshot for inspection | `qupath_snapshot` | real-app |

## Scripting and workflows

| Workflow | Penny route | Status |
|---|---|---|
| Workflow history / generated script | `qupath_workflow_steps` | real-app |
| Save a reusable workflow script | `qupath_save_workflow {name}` → `.local/workflows/*.groovy` | real-app |
| Run exact Groovy | `qupath_run_script` (project + image required; `qupath_api_search` / `qupath_api_read` first) | real-app |
| Script editor / run for project | `qupath_run_command "Automate > Script editor"`; Run for project is the researcher's menu in the editor (not exposed; see recipes for a project loop) | untested |

## Tool inventory

52 MCP tools; 46 adapter operations (`BridgeIdentity.OPERATIONS`). This increment added: `qupath_delete_objects`, `qupath_edit_objects`, `qupath_export_measurements`, `qupath_export_annotations`, `qupath_export_image`, `qupath_set_stains`, `qupath_estimate_stains`, `qupath_display`, `qupath_plugins`, `qupath_run_plugin`, `qupath_detect_positive_cells`, `qupath_classifiers`, `qupath_apply_object_classifier`, `qupath_apply_pixel_classifier`, `qupath_save`; and extended `qupath_classify` (`thresholds`), `qupath_overlays` (names, grid, TMA grid, pixel classification, fill annotations, detection display mode), `qupath_highlight` (`focus`), `qupath_context` (`image.stains`, `image.inProject`), and lenient `region`/`selection` scopes on read tools (`scopeUsed`). Every targeting operation now returns a `highlight` so the companion cursor moves to the region, objects or control involved. Schemas are generated in [TOOL-REFERENCE.md](TOOL-REFERENCE.md).

The new adapter operations take effect only after QuPath is quit normally and relaunched with `./script/run-qupath.sh`, because the extension jar is loaded at startup (`adapterReadiness` reports the mismatch until then).

## Extending coverage

For a recurring task, add a narrow schema and real API operation, exact prerequisites, observable outcome and a focused contract test; keep `BridgeIdentityContractTest` in sync. For an uncommon installed tool, discover its command and inspect its dialog; consult the pinned source before proposing a script. Unsupported custom canvases, OS-owned dialogs and third-party plugin contracts are concrete missing capabilities, not hidden behind a claimed universal tool.
