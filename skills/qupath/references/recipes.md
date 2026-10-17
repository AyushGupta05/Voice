# Groovy recipes and menu paths for everything without a structured tool

Order of routes: structured tool → `qupath_run_command` (exact path from `qupath_commands`) with `qupath_ui` / `qupath_ui_action` for its dialog → `qupath_run_script` with one of these recipes. Each snippet uses QuPath 0.7 public APIs checked against the pinned source (`qupath_api_search` confirms signatures). Scripts need an open project and image; QPEx static imports are available, so `getCurrentImageData()`, `getSelectedObjects()`, `getProject()` work directly. Replace values in angle brackets; never invent measurement names (read `measurementNames` first).

## Menu paths (exact `qupath_commands` form)

| Task | Path |
|---|---|
| Create / open / add images to project | `File > Project... > Create project`, `File > Project... > Open project`, `File > Project... > Add images` (OS file dialogs need the researcher) |
| Save / save as | `qupath_save`; `File > Save As` |
| Full image annotation | `Objects > Annotations... > Create full image annotation` |
| Inverse / simplify / transform / split by lines | `Objects > Annotations... > Make inverse`, `... > Simplify shape`, `... > Transform annotations`, `... > Split annotations by lines`, `... > Remove fragments & holes` |
| Select sets | `Objects > Select... > Select annotations`, `... > Select detections... > Select all detections`, `... > Select cells`, `... > Select tiles`, `... > Select TMA cores`, `... > Reset selection` |
| Delete sets | `Objects > Delete... > Delete all objects`, `... > Delete all annotations`, `... > Delete all detections` (or `qupath_delete_objects`) |
| Measurement tables / maps / manager | `Measure > Show annotation measurements`, `Measure > Show detection measurements`, `Measure > Show TMA measurements`, `Measure > Show measurement maps`, `Measure > Show measurement manager`, `Measure > Export measurements` |
| Spatial analysis | `Analyze > Spatial analysis > Distance to annotations 2D`, `... > Signed distance to annotations 2D`, `... > Detection centroid distances 2D`, `... > Delaunay cluster features 2D` |
| Classifiers (train/create) | `Classify > Object classification > Train object classifier`, `... > Create single measurement classifier`, `... > Create composite classifier`, `... > Set cell intensity classifications`, `... > Reset detection classifications`, `Classify > Pixel classification > Create thresholder`, `... > Train pixel classifier`, `... > Load pixel classifier` |
| Density maps | `Analyze > Density maps > Create density map`, `... > Load density map` |
| TMA grid | `TMA > TMA dearrayer` (or `qupath_run_plugin tma_dearray`), `TMA > Specify TMA grid`, `TMA > Add... > Add TMA row above selected`, `TMA > Remove... > Remove TMA row`, `TMA > Relabel TMA grid`, `TMA > Delete TMA grid`, `File > TMA data... > Export TMA data` |
| Export images | `File > Export images... > Original pixels`, `... > Rendered RGB (with overlays)`, `... > OME TIFF`, `... > Rendered SVG`; snapshots under `File > Export snapshot... > Current viewer content` |
| Viewer | `View > Brightness/Contrast`, `View > Show scalebar`, `View > Show grid`, `View > Set grid spacing`, `View > Show slide overview`, `View > Show mini viewer`, `View > Show channel viewer`, `View > Rotate image`, `View > Zoom... > Zoom to fit`, `View > Cell display > Nuclei only` |
| Scripting | `Automate > Script editor`, `Automate > Show workflow command history`, `Automate > Create command  history script` (two spaces in QuPath's label) |

Left panel tabs (Project, Image, Annotations, Hierarchy, Workflow): `qupath_ui`, find the `TabPane` control, then `qupath_ui_action {nodeId, action: "select", value: "Annotations"}`.

## Objects and selection

```groovy
// Select by a measurement range, then act with structured tools on the selection
selectObjects { it.isDetection() && it.getMeasurementList().get("<Nucleus: Area µm^2>") > <50> }
println getSelectedObjects().size()
```

```groovy
// Full-image annotation, polygon annotation, point annotation
createFullImageAnnotation(true)
def plane = getCurrentViewer().getImagePlane()
def poly = ROIs.createPolygonROI([new qupath.lib.geom.Point2(<x1>, <y1>), new qupath.lib.geom.Point2(<x2>, <y2>), new qupath.lib.geom.Point2(<x3>, <y3>)], plane)
def annotation = PathObjects.createAnnotationObject(poly, getPathClass("<Tumor>"))
addObject(annotation)
```

```groovy
// Inverse of the selected annotation, simplify, split by drawn lines, remove touching bounds
makeInverseAnnotation(getSelectedObject())
simplifySelectedAnnotations(<2.0>)
splitSelectedAnnotationAreasByLines()
removeObjectsTouchingSelectedBounds()
```

```groovy
// Resolve hierarchy / insert selected objects so cells become children of annotations
resolveHierarchy()
```

```groovy
// Reset all detection classes
resetDetectionClassifications()
```

## Image settings

```groovy
setPixelSizeMicrons(<0.25>, <0.25>)
```

```groovy
// Fluorescence channel names and colours (order = channel order)
setChannelNames('<DAPI>', '<CD3>', '<CD8>')
setChannelColors(getColorRGB(0, 0, 255), getColorRGB(0, 255, 0), getColorRGB(255, 0, 0))
```

```groovy
// Stain vectors by JSON (same form QuPath writes to the workflow); prefer qupath_set_stains / qupath_estimate_stains
setImageType('BRIGHTFIELD_H_DAB')
setColorDeconvolutionStains('{"Name" : "H-DAB adjusted", "Stain 1" : "Hematoxylin", "Values 1" : "0.65111 0.70119 0.29049", "Stain 2" : "DAB", "Values 2" : "0.26917 0.56824 0.77759", "Background" : " 255 255 255"}')
```

## Detection and features by plugin (prefer `qupath_run_plugin`; this is the same call)

```groovy
runPlugin('qupath.imagej.detect.cells.PositiveCellDetection', '{"detectionImageBrightfield":"Hematoxylin OD","requestedPixelSizeMicrons":0.5,"backgroundRadiusMicrons":8.0,"medianRadiusMicrons":0.0,"sigmaMicrons":1.5,"minAreaMicrons":10.0,"maxAreaMicrons":400.0,"threshold":0.1,"cellExpansionMicrons":5.0,"includeNuclei":true,"smoothBoundaries":true,"makeMeasurements":true,"thresholdCompartment":"Nucleus: DAB OD mean","thresholdPositive1":0.2,"thresholdPositive2":0.4,"thresholdPositive3":0.6,"singleThreshold":true}')
```

```groovy
runPlugin('qupath.lib.algorithms.TilerPlugin', '{"tileSizeMicrons":100.0,"trimToROI":true,"makeAnnotations":false,"removeParentAnnotation":false}')
runPlugin('qupath.imagej.superpixels.SLICSuperpixelsPlugin', '{"sigmaMicrons":5.0,"spacingMicrons":50.0,"maxIterations":10,"regularization":0.25,"adaptRegularization":false,"useDeconvolved":false}')
runPlugin('qupath.imagej.detect.tissue.SimpleTissueDetection2', '{"threshold":127,"requestedPixelSizeMicrons":20.0,"minAreaMicrons":10000.0,"maxHoleAreaMicrons":1000000.0,"darkBackground":false,"smoothImage":true,"medianCleanup":true,"dilateBoundaries":false,"smoothCoordinates":true,"excludeOnBoundary":false,"singleAnnotation":true}')
runPlugin('qupath.imagej.detect.dearray.TMADearrayerPluginIJ', '{"coreDiameterMM":1.2,"labelsHorizontal":"1-16","labelsVertical":"A-J","labelOrder":"Row first","densityThreshold":5,"boundsScale":105}')
runPlugin('qupath.lib.plugins.objects.ShapeFeaturesPlugin', '{"area":true,"perimeter":true,"circularity":true,"useMicrons":true}')
runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons":2.0,"region":"ROI","tileSizeMicrons":25.0,"colorOD":true,"colorStain1":true,"colorStain2":true,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":false,"doHaralick":false}')
runPlugin('qupath.opencv.features.DelaunayClusteringPlugin', '{"distanceThresholdMicrons":20.0,"limitByClass":false,"addClusterMeasurements":true}')
```

Parameter keys and current-image defaults come from `qupath_plugins {plugin}`; micron keys exist only on calibrated images (use the `Px`/`Pixels` keys otherwise).

## Intensity classification and distances

```groovy
// Negative / 1+ / 2+ / 3+ on all cells (qupath_classify {thresholds:[...]} does this with a checkpoint)
setCellIntensityClassifications('<Nucleus: DAB OD mean>', <0.2>, <0.4>, <0.6>)
```

```groovy
// Distance of every detection to the nearest annotation of each class (adds measurements)
detectionToAnnotationDistances(true)
detectionCentroidDistances(true)
```

## Classifiers (prefer `qupath_apply_object_classifier` / `qupath_apply_pixel_classifier`)

```groovy
runObjectClassifier('<classifier name in project>')
createAnnotationsFromPixelClassifier('<thresholder name>', <minAreaMicrons2>, <minHoleAreaMicrons2>, 'SPLIT', 'DELETE_EXISTING')
createDetectionsFromPixelClassifier('<thresholder name>', <minArea>, <minHoleArea>)
addPixelClassifierMeasurements('<thresholder name>', '<measurement id>')
classifyDetectionsByCentroid(loadPixelClassifier('<classifier name>'))
```

## Density map (object density per mm², then hotspots)

```groovy
import qupath.lib.analysis.heatmaps.DensityMaps
import qupath.lib.objects.PathObjectFilter
import qupath.lib.objects.PathObjectPredicates
def imageData = getCurrentImageData()
def positive = PathObjectPredicates.filter(PathObjectFilter.DETECTIONS_ALL).and(PathObjectPredicates.exactClassification(getPathClass('<Positive>')))
def builder = DensityMaps.builder(PathObjectPredicates.filter(PathObjectFilter.DETECTIONS_ALL)).addDensities('<Positive>', positive).radius(<250.0>).type(DensityMaps.DensityMapType.PERCENT)
def server = builder.buildServer(imageData)
DensityMaps.findHotspots(imageData.getHierarchy(), server, 0, <5>, <250.0>, <0.0>, getPathClass('<Hotspot>'), false, true)
```

Saved density maps live under the project's `classifiers/density_maps`; the Analyze > Density maps dialog is the usual way to build and save one.

## Export

```groovy
// Measurements for every image in the project, one TSV (qupath_export_measurements covers the current image)
import qupath.lib.gui.tools.MeasurementExporter
import qupath.lib.objects.PathCellObject
def out = new File(buildFilePath(PROJECT_BASE_DIR, '<cells.tsv>'))
new MeasurementExporter().imageList(getProject().getImageList()).separator('\t').exportType(PathCellObject.class).includeOnlyColumns('Image', 'Name', 'Class', '<Nucleus: DAB OD mean>').exportMeasurements(out)
println out
```

```groovy
// GeoJSON of selected objects; original pixels of the selected region; rendered view (qupath_export_* cover these for .local/exports)
exportSelectedObjectsToGeoJson(buildFilePath(PROJECT_BASE_DIR, '<selected.geojson>'), 'FEATURE_COLLECTION')
def request = RegionRequest.createInstance(getCurrentServer().getPath(), <1.0>, getSelectedROI())
writeImageRegion(getCurrentServer(), request, buildFilePath(PROJECT_BASE_DIR, '<region.tif>'))
writeRenderedImage(getCurrentViewer(), buildFilePath(PROJECT_BASE_DIR, '<view.png>'))
```

```groovy
// Labelled mask of annotations by class
import qupath.lib.images.servers.LabeledImageServer
def labels = new LabeledImageServer.Builder(getCurrentImageData()).backgroundLabel(0).addLabel('<Tumor>', 1).addLabel('<Stroma>', 2).downsample(<8.0>).multichannelOutput(false).build()
writeImage(labels, buildFilePath(PROJECT_BASE_DIR, '<labels.tif>'))
```

```groovy
// Tiles for training
import qupath.lib.images.writers.TileExporter
new TileExporter(getCurrentImageData()).downsample(<4.0>).imageExtension('.png').tileSize(512).annotatedTilesOnly(true).overlap(0).writeTiles(buildFilePath(PROJECT_BASE_DIR, '<tiles>'))
```

## Project loop (batch)

```groovy
// Apply the same steps to every image; nothing is saved unless you call entry.saveImageData
for (entry in getProject().getImageList()) {
    def imageData = entry.readImageData()
    def hierarchy = imageData.getHierarchy()
    println "${entry.getImageName()}: ${hierarchy.getDetectionObjects().size()} detections"
    // entry.saveImageData(imageData)  // only after the researcher agreed to overwrite saved data
}
```

Show the exact script and reason before running; a run is bound to the current project and image, and checkpoints do not undo file writes.
