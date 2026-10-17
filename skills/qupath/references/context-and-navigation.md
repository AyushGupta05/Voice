# Images, selection and navigation

## Open image and project

Use the real File menu through command discovery to open an image or project. Dialogs may need human interaction. Whole-slide images can contain very large pyramids; never load the full-resolution slide into a model request. `qupath_snapshot` captures the viewer only, at a bounded width.

Inspect context after opening an image. The image type (brightfield H&E, H-DAB, fluorescence or another type) controls how detection and measurements should be interpreted. Calibration must come from the image metadata or an explicitly justified researcher setting. Do not fabricate microns-per-pixel.

## Selected region and object identity

A selected annotation is an ROI with full-resolution pixel coordinates and a z/t plane. Store its stable object ID. Refresh context before each operation that depends on the selection. A data action is bound to image, selection and hierarchy revision at dispatch; if they change first, the gateway cancels it and you re-plan from the new context.

## Resolving places and sizes

Never ask for pixel coordinates. Derive them from `qupathObservation`:

| Spoken | Resolution |
| --- | --- |
| “this”, “here” | `selection[0].roi`; with no selection, a quarter of the visible area centred on the viewer centre, max ~3000 px per side; the whole view only for “everything visible” |
| visible area | `viewer.centerX/centerY ± (1600 × downsample)/2` by `(900 × downsample)/2`, unless the context gives the viewer's width/height |
| “the right side”, “top-left”, “a section on the right” | a rectangle a third of the visible width and height, offset toward that side inside the visible area |
| “a small region” | about 1500–2500 full-resolution px per side on a typical slide; roughly `image.width / 40`, clamped 500–4000 |
| “a bit bigger / smaller” | scale the last rectangle by 1.5 about its centre |

Clamp to the image bounds and the active z/t plane, then act with `qupath_annotate` (pass `classification`, e.g. “Tumor”, and a short `name`). Say the place in words (“on the right of the view”), not in pixels.

Use `qupath_objects` with scope=region and kind=cell to list cell centroids inside the selected ROI. Use scope=selection for selected objects themselves. Pagination gives total and offset; don't mistake a first page for the population.

## Viewer and overlays

Use `qupath_focus` to center on an ID or image coordinates, with a downsample factor for zoom (×2 for “a little”, ×4 for “a lot”; larger zooms out). It returns a real screen pointer target. Use display overlays to hide/show annotations, detections, fills or a named exact classification. Empty classification clears a filter.

Display filtering never deletes objects and should not modify scientific counts. A pointer is a visual cue; it does not move or click the user's actual mouse.

Source: https://qupath.readthedocs.io/en/0.7/docs/starting/annotating.html
