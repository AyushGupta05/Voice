package science.luma.qupath;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.StainVector;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.measure.ui.SummaryMeasurementTable;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.io.PathIO;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.PathInteractivePlugin;
import qupath.lib.plugins.PathPlugin;
import qupath.lib.plugins.parameters.*;
import qupath.lib.roi.RoiTools;
import qupath.lib.scripting.QP;

/** Exact QuPath public-API routes for common pathologist workflows. No scientific values are invented here. */
final class Workflows {
    private Workflows() {}

    /** Allow-listed QuPath 0.7 plugins runnable through {@code run_plugin}. Keys are stable voice-friendly names. */
    static final Map<String,String> PLUGINS = new LinkedHashMap<>();
    static {
        PLUGINS.put("cell_detection","qupath.imagej.detect.cells.WatershedCellDetection");
        PLUGINS.put("positive_cell_detection","qupath.imagej.detect.cells.PositiveCellDetection");
        PLUGINS.put("cell_membrane_detection","qupath.imagej.detect.cells.WatershedCellMembraneDetection");
        PLUGINS.put("subcellular_detection","qupath.imagej.detect.cells.SubcellularDetection");
        PLUGINS.put("fast_cell_counts","qupath.opencv.CellCountsCV");
        PLUGINS.put("tissue_detection","qupath.imagej.detect.tissue.SimpleTissueDetection2");
        PLUGINS.put("positive_pixel_count","qupath.imagej.detect.tissue.PositivePixelCounterIJ");
        PLUGINS.put("tma_dearray","qupath.imagej.detect.dearray.TMADearrayerPluginIJ");
        PLUGINS.put("tiles","qupath.lib.algorithms.TilerPlugin");
        PLUGINS.put("superpixels_slic","qupath.imagej.superpixels.SLICSuperpixelsPlugin");
        PLUGINS.put("superpixels_dog","qupath.imagej.superpixels.DoGSuperpixelsPlugin");
        PLUGINS.put("intensity_features","qupath.lib.algorithms.IntensityFeaturesPlugin");
        PLUGINS.put("haralick_features","qupath.lib.algorithms.HaralickFeaturesPlugin");
        PLUGINS.put("coherence_features","qupath.lib.algorithms.CoherenceFeaturePlugin");
        PLUGINS.put("local_binary_patterns","qupath.lib.algorithms.LocalBinaryPatternsPlugin");
        PLUGINS.put("shape_features","qupath.lib.plugins.objects.ShapeFeaturesPlugin");
        PLUGINS.put("smoothed_features","qupath.lib.plugins.objects.SmoothFeaturesPlugin");
        PLUGINS.put("delaunay_clusters","qupath.opencv.features.DelaunayClusteringPlugin");
        PLUGINS.put("dilate_annotations","qupath.lib.plugins.objects.DilateAnnotationPlugin");
        PLUGINS.put("fill_annotation_holes","qupath.lib.plugins.objects.FillAnnotationHolesPlugin");
        PLUGINS.put("refine_annotations","qupath.lib.plugins.objects.RefineAnnotationsPlugin");
        PLUGINS.put("split_annotations","qupath.lib.plugins.objects.SplitAnnotationsPlugin");
        PLUGINS.put("convex_hull_detections","qupath.lib.plugins.objects.FindConvexHullDetectionsPlugin");
        PLUGINS.put("tiles_to_annotations","qupath.lib.plugins.objects.TileClassificationsToAnnotationsPlugin");
    }

    @SuppressWarnings("unchecked")
    static PathPlugin<BufferedImage> plugin(String key) {
        String className = PLUGINS.get(key);
        if (className == null) throw new IllegalArgumentException("Unknown plugin '" + key + "'. Available: " + String.join(", ", PLUGINS.keySet()));
        try { return (PathPlugin<BufferedImage>) Class.forName(className).getConstructor().newInstance(); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Plugin is not available in this QuPath build: " + className); }
    }

    /** Parameter names, prompts, units, ranges, choices and current-image defaults for an allow-listed plugin. */
    @SuppressWarnings("unchecked")
    static Map<String,Object> describePlugin(String key, ImageData<BufferedImage> image) {
        var plugin = plugin(key);
        var result = new LinkedHashMap<String,Object>();
        result.put("plugin",key); result.put("className",PLUGINS.get(key)); result.put("name",plugin.getName()); result.put("description",Objects.toString(plugin.getDescription(),""));
        if (plugin instanceof PathInteractivePlugin<?> interactive) {
            var typed = (PathInteractivePlugin<BufferedImage>) interactive;
            result.put("parameters",describeParameters(typed.getDefaultParameterList(image)));
            result.put("parentObjects",typed.getSupportedParentObjectClasses().stream().map(c -> PathObjectTools.getSuitableName(c,false)).toList());
        } else result.put("parameters",List.of());
        result.put("note","Defaults come from QuPath for the current image; they are starting points, not validated settings. Hidden parameters are omitted (micron parameters appear only on calibrated images).");
        return result;
    }

    static List<Map<String,Object>> describeParameters(ParameterList list) {
        var parameters = new ArrayList<Map<String,Object>>();
        for (var entry : list.getParameters().entrySet()) {
            var parameter = entry.getValue(); if (parameter.isHidden() || parameter instanceof EmptyParameter) continue;
            var row = new LinkedHashMap<String,Object>(); row.put("key",entry.getKey()); row.put("prompt",parameter.getPrompt()); row.put("help",parameter.getHelpText()); row.put("default",parameter.getValueOrDefault()); row.put("type",parameter.getClass().getSimpleName());
            if (parameter instanceof NumericParameter<?> numeric) { row.put("unit",numeric.getUnit()); row.put("minimum",Double.isFinite(numeric.getLowerBound())?numeric.getLowerBound():null); row.put("maximum",Double.isFinite(numeric.getUpperBound())?numeric.getUpperBound():null); }
            if (parameter instanceof ChoiceParameter<?> choice) row.put("choices",choice.getChoices().stream().map(String::valueOf).toList());
            parameters.add(row);
        }
        return parameters;
    }

    /** Refuse unknown, hidden, mistyped or out-of-range values before QuPath silently keeps its defaults. */
    static void validateParameters(ParameterList list, JsonObject requested) {
        var parameters = list.getParameters();
        for (var entry : requested.entrySet()) {
            var parameter = parameters.get(entry.getKey());
            if (parameter == null || parameter.isHidden()) throw new IllegalArgumentException("Parameter is unavailable for this image: " + entry.getKey());
            var value = entry.getValue();
            if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Expected a scalar parameter: " + entry.getKey());
            var primitive = value.getAsJsonPrimitive();
            if (parameter instanceof BooleanParameter && !primitive.isBoolean()) throw new IllegalArgumentException("Expected a boolean: " + entry.getKey());
            if (parameter instanceof NumericParameter<?> numeric) {
                if (!primitive.isNumber()) throw new IllegalArgumentException("Expected a number: " + entry.getKey());
                double number = primitive.getAsDouble();
                if (!Double.isFinite(number) || number < numeric.getLowerBound() || number > numeric.getUpperBound() || parameter instanceof IntParameter && number != Math.rint(number)) throw new IllegalArgumentException("Number is outside the parameter range: " + entry.getKey());
            }
            if (parameter instanceof ChoiceParameter<?> && !primitive.isString()) throw new IllegalArgumentException("Expected an exact choice: " + entry.getKey());
            if (!parameter.duplicate().setStringLastValue(Locale.US,primitive.getAsString())) throw new IllegalArgumentException("Unsupported value for " + entry.getKey() + ": " + primitive.getAsString());
        }
    }

    /** QuPath's Locale.US parser treats lowercase exponents as a numeric prefix; give it plain decimal strings. */
    static String normalizeArguments(JsonObject requested) {
        var normalized = requested.deepCopy();
        for (var entry : normalized.entrySet())
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) entry.setValue(new JsonPrimitive(entry.getValue().getAsBigDecimal().toPlainString()));
        return normalized.toString();
    }

    static Path exportDirectory() throws IOException {
        String override = System.getProperty("luma.exportDirectory"); String root = System.getenv("LUMA_ROOT");
        Path directory = override != null && !override.isBlank() ? Path.of(override) : root != null && !root.isBlank() ? Path.of(root,".local","exports") : Path.of(System.getProperty("user.home"),"Luma exports");
        return Files.createDirectories(directory);
    }
    static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");
        return slug.isEmpty() ? "image" : slug.length() > 60 ? slug.substring(0,60) : slug;
    }
    static Path exportPath(ImageData<BufferedImage> image, String kind, String extension) throws IOException {
        var directory = exportDirectory();
        String base = slug(Objects.toString(ServerTools.getDisplayableImageName(image.getServer()),"image")) + "-" + slug(kind) + "-" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var path = directory.resolve(base + "." + extension);
        for (int suffix = 2; Files.exists(path); suffix++) path = directory.resolve(base + "-" + suffix + "." + extension);
        return path;
    }

    /** Same table QuPath's Measure > Export measurements writes, for the given objects only. */
    static Map<String,Object> exportMeasurements(ImageData<BufferedImage> image, List<PathObject> objects, String kind, List<String> columns) throws IOException {
        if (objects.isEmpty()) throw new IllegalStateException("No objects match the requested scope; nothing to export.");
        var model = new ObservableMeasurementTableData();
        model.setImageData(image,objects);
        var names = model.getAllNames();
        Collection<String> exclude = List.of();
        if (!columns.isEmpty()) {
            var missing = columns.stream().filter(column -> !names.contains(column)).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("Unknown columns " + missing + ". Available: " + names);
            var excluded = new LinkedHashSet<>(names); columns.forEach(excluded::remove); exclude = excluded;
        }
        var rows = SummaryMeasurementTable.getTableModelStrings(model,",",exclude);
        var path = exportPath(image,kind,"csv");
        Files.write(path,rows,StandardCharsets.UTF_8);
        var header = rows.isEmpty() ? List.<String>of() : List.of(rows.getFirst().split(","));
        var result = new LinkedHashMap<String,Object>();
        result.put("path",path.toString()); result.put("rows",Math.max(0,rows.size()-1)); result.put("objects",objects.size()); result.put("columns",header.size());
        result.put("columnNames",header.stream().limit(60).toList()); result.put("columnNamesTruncated",header.size() > 60); result.put("separator",",");
        return result;
    }

    static Map<String,Object> exportGeoJson(ImageData<BufferedImage> image, List<PathObject> objects, String kind, boolean excludeMeasurements) throws IOException {
        if (objects.isEmpty()) throw new IllegalStateException("No objects match the requested scope; nothing to export.");
        var path = exportPath(image,kind,"geojson");
        var options = new ArrayList<>(List.of(PathIO.GeoJsonExportOptions.FEATURE_COLLECTION));
        if (excludeMeasurements) options.add(PathIO.GeoJsonExportOptions.EXCLUDE_MEASUREMENTS);
        PathIO.exportObjectsAsGeoJSON(path,objects,options.toArray(PathIO.GeoJsonExportOptions[]::new));
        var result = new LinkedHashMap<String,Object>();
        result.put("path",path.toString()); result.put("objects",objects.size()); result.put("format","GeoJSON FeatureCollection, full-resolution image pixels"); result.put("includesMeasurements",!excludeMeasurements);
        result.put("bytes",Files.size(path));
        return result;
    }

    /** Preset (H_E, H_DAB) or explicit stain vectors: {"name":..., "stains": {"Hematoxylin":[r,g,b], "DAB":[r,g,b], "Background":[255,255,255]}}. */
    static ColorDeconvolutionStains parseStains(JsonObject args) {
        if (args.has("preset")) {
            String preset = args.get("preset").getAsString().toUpperCase(Locale.ROOT).replace("&","_").replace("-","_");
            try { return ColorDeconvolutionStains.makeDefaultColorDeconvolutionStains(ColorDeconvolutionStains.DefaultColorDeconvolutionStains.valueOf(preset)); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("preset must be H_E or H_DAB"); }
        }
        if (!args.has("stains") || !args.get("stains").isJsonObject()) throw new IllegalArgumentException("Provide preset or stains.");
        var map = new LinkedHashMap<String,List<Number>>();
        for (var entry : args.getAsJsonObject("stains").entrySet()) {
            if (!entry.getValue().isJsonArray() || entry.getValue().getAsJsonArray().size() < 3) throw new IllegalArgumentException("Each stain needs three numbers: " + entry.getKey());
            var values = new ArrayList<Number>();
            for (var value : entry.getValue().getAsJsonArray()) { double number = value.getAsDouble(); if (!Double.isFinite(number)) throw new IllegalArgumentException("Stain values must be finite"); values.add(number); }
            map.put(entry.getKey(),values);
        }
        if (!map.containsKey("Background")) map.put("Background",List.of(255,255,255));
        return ColorDeconvolutionStains.parseColorDeconvolutionStains(args.has("name") ? args.get("name").getAsString() : "Custom stains",map);
    }

    static Map<String,Object> describeStains(ColorDeconvolutionStains stains) {
        if (stains == null) return null;
        var vectors = new LinkedHashMap<String,Object>();
        for (int i = 1; i <= 3; i++) { var stain = stains.getStain(i); if (stain != null) vectors.put(stain.getName(),round(stain.getArray())); }
        var result = new LinkedHashMap<String,Object>();
        result.put("name",stains.getName()); result.put("vectors",vectors); result.put("background",List.of(stains.getMaxRed(),stains.getMaxGreen(),stains.getMaxBlue()));
        result.put("isHE",stains.isH_E()); result.put("isHDAB",stains.isH_DAB());
        return result;
    }
    static List<Double> round(double[] values) { var out = new ArrayList<Double>(); for (double v : values) out.add(Math.round(v*100000.0)/100000.0); return out; }
    static Map<String,Double> stainAngles(ColorDeconvolutionStains before, ColorDeconvolutionStains after) {
        var angles = new LinkedHashMap<String,Double>();
        for (int i = 1; i <= 2; i++) angles.put(after.getStain(i).getName(),Math.round(StainVector.computeAngle(before.getStain(i),after.getStain(i))*100.0)/100.0);
        return angles;
    }

    /** Structured edits with QuPath's own object tools; the caller has already taken a hierarchy checkpoint. */
    static Map<String,Object> editObjects(PathObjectHierarchy hierarchy, List<PathObject> matches, String action, JsonObject args, double pixelSizeMicrons) {
        if (matches.isEmpty()) throw new IllegalStateException("No objects match the requested scope. Select or filter objects first.");
        var result = new LinkedHashMap<String,Object>(); result.put("action",action); result.put("matched",matches.size());
        switch (action) {
            case "merge" -> {
                var annotations = matches.stream().filter(PathObject::isAnnotation).toList();
                if (annotations.size() < 2) throw new IllegalStateException("Merging needs at least two annotations in scope.");
                if (!QP.mergeAnnotations(hierarchy,annotations)) throw new IllegalStateException("QuPath could not merge these annotations (they must be area or point ROIs on one image plane).");
                var merged = hierarchy.getSelectionModel().getSelectedObject();
                result.put("merged",annotations.size()); result.put("mergedId",merged == null ? null : merged.getID().toString());
            }
            case "split" -> {
                var created = new ArrayList<PathObject>(); var removed = new ArrayList<PathObject>();
                for (var object : matches) {
                    if (!object.isAnnotation() || object.getROI() == null || !object.getROI().isArea()) continue;
                    var parts = RoiTools.splitROI(object.getROI());
                    if (parts.size() < 2) continue;
                    for (var part : parts) { var piece = PathObjects.createAnnotationObject(part,object.getPathClass()); piece.setName(object.getName()); piece.setLocked(object.isLocked()); created.add(piece); }
                    removed.add(object);
                }
                if (created.isEmpty()) throw new IllegalStateException("No annotation in scope has more than one disconnected part.");
                hierarchy.removeObjects(removed,true); hierarchy.addObjects(created);
                hierarchy.getSelectionModel().setSelectedObjects(created,created.getFirst());
                result.put("split",removed.size()); result.put("created",created.size());
            }
            case "lock", "unlock" -> { boolean lock = action.equals("lock"); matches.forEach(o -> o.setLocked(lock)); hierarchy.fireObjectsChangedEvent(Workflows.class,matches); result.put("locked",lock); }
            case "set_class" -> {
                String value = args.has("value") ? args.get("value").getAsString().trim() : "";
                var pathClass = value.isEmpty() ? null : PathClass.fromString(value);
                matches.forEach(o -> o.setPathClass(pathClass)); hierarchy.fireObjectClassificationsChangedEvent(Workflows.class,matches);
                result.put("classification",pathClass == null ? null : pathClass.toString());
            }
            case "rename" -> {
                if (!args.has("value")) throw new IllegalArgumentException("rename needs value.");
                String name = args.get("value").getAsString().trim(); matches.forEach(o -> o.setName(name.isEmpty() ? null : name)); hierarchy.fireObjectsChangedEvent(Workflows.class,matches); result.put("name",name);
            }
            case "duplicate" -> { if (!PathObjectTools.duplicateObjects(hierarchy,new ArrayList<>(matches))) throw new IllegalStateException("QuPath could not duplicate these objects."); result.put("duplicated",matches.size()); }
            case "fill_holes", "expand" -> {
                double distance = 0;
                if (action.equals("expand")) {
                    if (args.has("distanceMicrons")) { if (!Double.isFinite(pixelSizeMicrons) || pixelSizeMicrons <= 0) throw new IllegalStateException("Image is uncalibrated; use distance in pixels."); distance = args.get("distanceMicrons").getAsDouble() / pixelSizeMicrons; }
                    else if (args.has("distance")) distance = args.get("distance").getAsDouble();
                    else throw new IllegalArgumentException("expand needs distance (pixels, negative shrinks) or distanceMicrons.");
                    if (!Double.isFinite(distance) || distance == 0) throw new IllegalArgumentException("distance must be a finite non-zero number.");
                }
                int changed = 0;
                for (var object : matches) {
                    if (!(object instanceof PathROIObject editable) || object.getROI() == null || !object.getROI().isArea() || object.isDetection()) continue;
                    var roi = action.equals("expand") ? RoiTools.buffer(object.getROI(),distance) : RoiTools.fillHoles(object.getROI());
                    if (roi == null || roi.isEmpty()) throw new IllegalStateException("The edit would remove the whole annotation; nothing was changed for " + object.getID());
                    editable.setROI(roi); hierarchy.updateObject(object,false); changed++;
                }
                if (changed == 0) throw new IllegalStateException("No area annotation in scope to edit.");
                result.put("changed",changed); if (action.equals("expand")) result.put("distancePixels",distance);
            }
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
        return result;
    }
}
