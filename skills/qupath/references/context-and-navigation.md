# Images, selection and navigation

## Open image and project

Use the real File menu through command discovery to open an image or project. Dialogs may need human interaction. Whole-slide images can contain very large pyramids; never load the full-resolution slide into a model request. `qupath_snapshot` captures the viewer only, at a bounded width.

Inspect context after opening an image. The image type (brightfield H&E, H-DAB, fluorescence or another type) controls how detection and measurements should be interpreted. Calibration must come from the image metadata or an explicitly justified researcher setting. Do not fabricate microns-per-pixel.

## Selected region and object identity

A selected annotation is an ROI with full-resolution pixel coordinates and a z/t plane. Store its stable object ID. Refresh context before each operation that depends on the selection. A pending data action is bound to image, selection and hierarchy revision; if the researcher changes them, request a new proposal.

Use `qupath_objects` with scope=region and kind=cell to list cell centroids inside the selected ROI. Use scope=selection for selected objects themselves. Pagination gives total and offset; don't mistake a first page for the population.

## Viewer and overlays

Use `qupath_focus` to center on an ID or image coordinates, with a downsample factor for zoom. It returns a real screen pointer target. Use display overlays to hide/show annotations, detections, fills or a named exact classification. Empty classification clears a filter.

Display filtering never deletes objects and should not modify scientific counts. A pointer is a visual cue; it does not move or click the user's actual mouse.

Source: https://qupath.readthedocs.io/en/0.7/docs/starting/annotating.html
