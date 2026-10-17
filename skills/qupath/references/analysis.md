# Cell detection, classification and measurements

## Cell detection

Prerequisites: a real image, correct image interpretation, known calibration or pixel-unit parameters, and one or more selected annotations. Start with a small region and inspect segmentation before scaling up.

The bridge calls QuPath 0.7 WatershedCellDetection. Common calibrated parameters include requestedPixelSizeMicrons, backgroundRadiusMicrons, medianRadiusMicrons, sigmaMicrons, minAreaMicrons, maxAreaMicrons and cellExpansionMicrons. Uncalibrated images use backgroundRadius, medianRadius, sigma, minArea, maxArea and cellExpansion in pixels; inspect official source for the active mode. Brightfield uses detectionImageBrightfield (e.g. Hematoxylin OD); fluorescence uses detectionImage with an exact channel name. threshold is on that image/channel's detection scale, not a universal probability.

Other parameters include backgroundByReconstruction, maxBackground, watershedPostProcess, excludeDAB, includeNuclei, smoothBoundaries and makeMeasurements. Defaults are starting points, never validated biological truth. Read the exact parameter schema and source for the active image mode. Detection can replace existing child detections; approval and checkpoint are required.

## Threshold classification

Read measurement names and values first. Select a real measurement such as a DAB optical density measurement only when it exists. The structured classifier marks value >= threshold Positive and below Negative, replacing current classes within scope. Missing or non-finite measurements fail the action. Preserve the measurement name, threshold, scope and counts in the result.

`classification` filters **existing** object labels; it never selects the output class. To threshold all cells in the selected region, use `scope: region`, `kind: cell`, the actual measurement and threshold, and omit `classification`. Add it only when the researcher explicitly wants a subset with that current label. The gateway previews the actual eligible-detection count before approval and refuses an empty scope; never silently remove an intentional filter to make an analysis run. The output labels remain Positive/Negative. Annotation objects are outside this structured detection classifier.

Use trained object or pixel classifiers via discovered commands and approved scripts when appropriate. Classifier files must already exist or be explicitly created; never claim a model is trained because a dialog opened.

## Quantification

`qupath_measurements` returns measured rows and explicit truncation. Inspect detection/annotation measurement tables through the Measure menu for larger exports. A marker-positive fraction requires a stated denominator. Counting all detected cells is not automatically a tumor-cell Ki67 labelling index.

## Compare and recover

Create a labelled checkpoint before experimenting. Compare exact object/class counts against it. To restore the earlier hierarchy, submit the checkpoint ID and let the researcher approve. Checkpoints are bounded, volatile and not a backup of the source image or project files.

Sources:
- https://qupath.readthedocs.io/en/0.7/docs/tutorials/cell_detection.html
- https://qupath.readthedocs.io/en/0.7/docs/tutorials/cell_classification.html
- https://github.com/qupath/qupath/blob/v0.7.0/qupath-core-processing/src/main/java/qupath/imagej/detect/cells/WatershedCellDetection.java
