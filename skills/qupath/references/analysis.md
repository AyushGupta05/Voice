# Cell detection, classification and measurements

## Cell detection

Prerequisites: a real image, correct image interpretation, known calibration or pixel-unit parameters, and one or more selected annotations. Start with a small region and inspect segmentation before scaling up.

The bridge calls QuPath 0.7 WatershedCellDetection. Common calibrated parameters include requestedPixelSizeMicrons, backgroundRadiusMicrons, medianRadiusMicrons, sigmaMicrons, minAreaMicrons, maxAreaMicrons and cellExpansionMicrons. Uncalibrated images use backgroundRadius, medianRadius, sigma, minArea, maxArea and cellExpansion in pixels; inspect official source for the active mode. Brightfield uses detectionImageBrightfield (e.g. Hematoxylin OD); fluorescence uses detectionImage with an exact channel name. threshold is on that image/channel's detection scale, not a universal probability.

Other parameters include backgroundByReconstruction, maxBackground, watershedPostProcess, excludeDAB, includeNuclei, smoothBoundaries and makeMeasurements. Defaults are starting points, never validated biological truth. Read the exact parameter schema and source for the active image mode. Detection replaces existing child detections; the gateway checkpoints the hierarchy first and runs it immediately. Never re-run detection while cells exist unless the researcher explicitly asks to redo it.

## “Analyse the cells here” recipe

Run the whole chain without stopping to ask; each data step returns `status: completed` or `failed` with real numbers.

1. Scope: the selected annotation. If nothing is selected, create one with `qupath_annotate` using the place/size rules in the skill (viewer centre, about a third of the view) and select it with `qupath_select`.
2. `qupath_detect_cells` with the calibrated H-DAB defaults from the tool description (requestedPixelSizeMicrons 0.5, backgroundRadiusMicrons 8, medianRadiusMicrons 0, sigmaMicrons 1.5, minAreaMicrons 10, maxAreaMicrons 400, threshold 0.1, cellExpansionMicrons 5, includeNuclei/smoothBoundaries/makeMeasurements true). Fluorescence: `detectionImage` is the nuclear channel name from `image.channels`. Uncalibrated images use the pixel parameters instead.
3. `qupath_classify {scope: "region", kind: "cell", measurement: "Nucleus: DAB OD mean", threshold: 0.2}`. On other stains pick the obvious marker measurement present in `measurementNames` (e.g. `Cell: <channel> mean`); never invent a name.
4. `qupath_class_summary {scope: "region"}` for per-class counts and percentages computed by QuPath.
5. Say: “294 cells, 49 positive (17%).” Numbers come only from the summary.

## Stricter or looser threshold

“Too sensitive / stricter” multiplies the current threshold by about 1.5; “too strict / looser” divides by about 1.5. Rerun `qupath_classify` with the same measurement, scope and kind, then `qupath_class_summary`, and state the new threshold and counts: “Reclassified at 0.3; 31 of 294 cells are positive.” Keep the measurement fixed so the comparison stays meaningful.

## Undo the last threshold

Every data action checkpoints the hierarchy before changing it. “Go back to the previous threshold / undo that” → `qupath_checkpoints`, pick the checkpoint created immediately before the last classify (the newest one for this image), then `qupath_restore {checkpointId}`. Restore checkpoints the current hierarchy too, so the researcher can move forward again. Confirm with `qupath_class_summary` and report the restored counts. “Compare with before” → `qupath_compare {checkpointId}`.

## Threshold classification

Read measurement names and values first. Select a real measurement such as a DAB optical density measurement only when it exists. The structured classifier marks value >= threshold Positive and below Negative, replacing current classes within scope. Missing or non-finite measurements fail the action. Preserve the measurement name, threshold, scope and counts in the result.

`classification` filters **existing** object labels; it never selects the output class. To threshold all cells in the selected region, use `scope: region`, `kind: cell`, the actual measurement and threshold, and omit `classification`. Add it only when the researcher explicitly wants a subset with that current label. The gateway counts the actual eligible detections before running and refuses an empty scope; never silently remove an intentional filter to make an analysis run. The output labels remain Positive/Negative. Annotation objects are outside this structured detection classifier.

Use trained object or pixel classifiers via discovered commands and approved scripts when appropriate. Classifier files must already exist or be explicitly created; never claim a model is trained because a dialog opened.

## Quantification

`qupath_class_summary` returns per-class counts and percentages for a scope, computed by QuPath over every object; use it for “how many are positive” and “what percentage”. `qupath_measurements` returns measured rows and explicit truncation; never compute a fraction from a truncated page. Inspect detection/annotation measurement tables through the Measure menu for larger exports. A marker-positive fraction requires a stated denominator. Counting all detected cells is not automatically a tumor-cell Ki67 labelling index.

## Compare and recover

Every data action creates a checkpoint before it runs. Compare exact object/class counts against one with `qupath_compare`; restore with `qupath_restore` and the checkpoint ID. Checkpoints are bounded (six), volatile and not a backup of the source image or project files.

Sources:
- https://qupath.readthedocs.io/en/0.7/docs/tutorials/cell_detection.html
- https://qupath.readthedocs.io/en/0.7/docs/tutorials/cell_classification.html
- https://github.com/qupath/qupath/blob/v0.7.0/qupath-core-processing/src/main/java/qupath/imagej/detect/cells/WatershedCellDetection.java
