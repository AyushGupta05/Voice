# Import, explore and summarize

## Open a local image or project

Use `qupath_context`, then `qupath_open_path` with an exact existing absolute local image path or `.qpproj`. The researcher reviews the path. QuPath runs its normal native open/import workflow; image-reader, project-import and image-type dialogs may still need interaction. Inspect `qupath_ui`, `context.lastDispatch` and the actual active image afterward. `dispatched` does not mean the requested image is loaded. No URL or `.qpdata` opening is provided by this narrow endpoint. Use the native File > Open workflow for saved data.

The endpoint refuses unsaved current-image changes. Ask the researcher to save or close that image explicitly; never silently discard it. Opening another project may synchronize existing project files through QuPath's native implementation, outside hierarchy recovery.

## Navigate a project

`qupath_project_images` lists real entry IDs, names, saved-data availability and source fingerprints. Use pagination, or an exact `entryId` lookup. `qupath_open_project_image` requires both that ID and its observed fingerprint. The review displays the discovered name. A source/name change invalidates the request. Native open dialogs and actual context must still be checked. Opening an entry is not a project-wide analysis.

## Import annotations

1. Establish the target image and the researcher's intended file. Do not guess a file or coordinate system.
2. `qupath_inspect_import` reads an exact local `.geojson`/`.json`. It supports a Feature, Feature array or FeatureCollection, with 1–10,000 leaf annotation objects and a 16 MiB input limit. Archives, Java serialized objects, remote downloads, detections and TMA grids are excluded from this narrow importer.
3. Inspect counts, exact file SHA256, classes, bounded object preview and image/plane fit. Names/classes have a 1000-character limit, with at most 32 class components. Strict UTF-8/JSON, raw 2D coordinate numbers, original floating-precision topology, planes, object type and classification bounds are checked before QuPath conversion; recursive legacy classes, child objects and nucleus geometries are excluded. Class summaries show at most 50 classes and object previews at most 20; truncation is explicit.
4. Confirm coordinates are full-resolution image pixels. A GIS coordinate reference system, normalized polygon or thumbnail-space ROI must be transformed explicitly first; a small coordinate value is not proof of correct units.
5. Call `qupath_import_annotations` with the same path, exact SHA256 and `coordinateSpace: image_pixels`. In `LUMA_APPROVALS=review` the human card includes counts/classes, explicit class-summary truncation and parameters, with a button to inspect the full source in TextEdit before approval. Parsing happens off JavaFX. File hash, image/context, geometry, plane and UUID uniqueness are checked again before a checkpoint and insertion.
6. Verify actual imported counts and contours. QuPath can quantize geometry to 0.01 image pixels; inspection/review discloses changed features and the maximum area change in px². Loss of components or holes is refused, and image-fit checks use original coordinate bounds as well as converted ROIs. Existing objects are retained and the source file is never written. Canonical UUIDs are preserved; other supplied IDs and absent IDs receive deterministic UUIDs bound to the exact file hash (and source ID or feature index). Duplicate supplied IDs and existing object UUIDs are refused; do not repeat a timed-out import blindly.

The generic reviewed script/native command path remains available for other supported formats, after checking the relevant documentation and exact side effects. Narrow import limits are intentional, not a claim that QuPath cannot read other data.

## Analyze existing measurements

Use `qupath_analysis_catalog` to discover actual stored measurement names/classes, current-image watershed parameter names, image-initialized defaults, choices, ranges/units and recent workflow step names. Defaults are not biological recommendations; calibration and image interpretation still matter. Measurement/class catalogs show up to 500 names with explicit counts/truncation. Workflow shows the latest 30 steps, not complete provenance.

Use `qupath_measurement_summary` for a full matching scope, rather than counting the first page of measurement rows. Supply 1–20 exact measurement names, an explicit object/selection/region filter, 1–50 histogram bins and optional grouping by exact class. All matching objects are summarized, up to one million objects / five million numeric values; larger scopes fail explicitly. It reports finite-value count, missing/non-finite count, minimum, maximum, mean, sample standard deviation (n−1) and histogram bins. The final bin includes its upper edge; a constant distribution has one bin. Missing values never count as zero. Up to 50 class groups are shown with an explicit group count/truncation flag; the global summary still covers the full scope.

The companion plots the first two requested measurement distributions and retains their source image/context. A context change marks them as an earlier observation; ask for a fresh summary rather than presenting them as live results. Read a named full result or rerun a specific measurement for additional plots. No segmentation, significance test, protein identification or clinical inference is implied by descriptive statistics.

Primary sources: pinned QuPath 0.7 source (`QuPathGUI.openImage/openImageEntry`, `GsonTools.parseObjectsFromGeoJSON`, `ProjectImageEntry`, watershed parameter lists), [objects](https://qupath.readthedocs.io/en/0.7/docs/concepts/objects.html), [measurement export](https://qupath.readthedocs.io/en/0.7/docs/tutorials/exporting_measurements.html), [scripting](https://qupath.readthedocs.io/en/0.7/docs/scripting/overview.html).
