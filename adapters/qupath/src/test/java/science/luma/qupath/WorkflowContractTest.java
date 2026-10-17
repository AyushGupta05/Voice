package science.luma.qupath;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.plugins.PathInteractivePlugin;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;

/** Synthetic in-memory hierarchy and a temporary export folder only; never the running scientific session. */
public final class WorkflowContractTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] ignored) throws Exception {
        var image = new ImageData<BufferedImage>(new WrappedBufferedImageServer("Workflow contract fixture",new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB)),ImageData.ImageType.BRIGHTFIELD_H_DAB);
        var hierarchy = image.getHierarchy();
        // Every allow-listed plugin resolves in the installed QuPath build and describes parameters for this image.
        for (var key : Workflows.PLUGINS.keySet()) {
            var plugin = Workflows.plugin(key);
            if (!(plugin instanceof PathInteractivePlugin<?>)) throw new AssertionError("Plugin is not interactive: " + key);
            var described = Workflows.describePlugin(key,image);
            if (!(described.get("parameters") instanceof List<?> parameters) || false) throw new AssertionError("No parameters described for " + key);
        }
        reject(() -> Workflows.plugin("imagej_macro"));
        var tiles = (PathInteractivePlugin<BufferedImage>) Workflows.plugin("tiles");
        var list = tiles.getDefaultParameterList(image);
        Workflows.validateParameters(list,JsonParser.parseString("{\"tileSizePx\":64,\"trimToROI\":false}").getAsJsonObject());
        reject(() -> Workflows.validateParameters(list,JsonParser.parseString("{\"tileSizeMicrons\":64}").getAsJsonObject()));
        reject(() -> Workflows.validateParameters(list,JsonParser.parseString("{\"trimToROI\":\"yes\"}").getAsJsonObject()));
        reject(() -> Workflows.validateParameters(list,JsonParser.parseString("{\"nope\":1}").getAsJsonObject()));
        if (!Workflows.normalizeArguments(JsonParser.parseString("{\"tileSizePx\":1e-3}").getAsJsonObject()).equals("{\"tileSizePx\":\"0.001\"}")) throw new AssertionError("Scientific notation must be normalized");
        // Stains: presets and explicit vectors parse; junk is refused.
        var preset = Workflows.parseStains(JsonParser.parseString("{\"preset\":\"h-dab\"}").getAsJsonObject());
        if (!preset.isH_DAB()) throw new AssertionError("H-DAB preset expected");
        var custom = Workflows.parseStains(JsonParser.parseString("{\"name\":\"Mine\",\"stains\":{\"Hematoxylin\":[0.65,0.70,0.29],\"DAB\":[0.27,0.57,0.78]}}").getAsJsonObject());
        if (!custom.getName().equals("Mine") || custom.getStain(2) == null || !custom.getStain(2).getName().equals("DAB")) throw new AssertionError("Custom stains lost");
        reject(() -> Workflows.parseStains(JsonParser.parseString("{\"preset\":\"CMYK\"}").getAsJsonObject()));
        reject(() -> Workflows.parseStains(JsonParser.parseString("{\"stains\":{\"Hematoxylin\":[1]}}").getAsJsonObject()));
        if (Workflows.describeStains(custom).get("vectors") == null) throw new AssertionError("Stain description missing");
        // Object edits with QuPath's own tools.
        var a = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0,0,50,50,ImagePlane.getDefaultPlane()),PathClass.fromString("Tumor"));
        var b = PathObjects.createAnnotationObject(ROIs.createRectangleROI(40,0,50,50,ImagePlane.getDefaultPlane()),PathClass.fromString("Tumor"));
        var far = PathObjects.createAnnotationObject(ROIs.createRectangleROI(150,150,40,40,ImagePlane.getDefaultPlane()));
        hierarchy.addObjects(List.of(a,b,far));
        var merged = Workflows.editObjects(hierarchy,List.of(a,b),"merge",new com.google.gson.JsonObject(),Double.NaN);
        if (hierarchy.getAnnotationObjects().size() != 2 || !merged.get("merged").equals(2)) throw new AssertionError("Merge failed");
        var union = hierarchy.getSelectionModel().getSelectedObject();
        if (union == null || !"Tumor".equals(String.valueOf(union.getPathClass())) || Math.abs(union.getROI().getArea() - 4500) > 1) throw new AssertionError("Merged ROI/class wrong");
        var twoPart = PathObjects.createAnnotationObject(RoiTools.union(ROIs.createRectangleROI(0,100,10,10,ImagePlane.getDefaultPlane()),ROIs.createRectangleROI(100,100,10,10,ImagePlane.getDefaultPlane())),PathClass.fromString("Stroma"));
        twoPart.setName("Pair"); hierarchy.addObject(twoPart);
        var split = Workflows.editObjects(hierarchy,List.of(twoPart),"split",new com.google.gson.JsonObject(),Double.NaN);
        if (!split.get("created").equals(2) || hierarchy.getAnnotationObjects().stream().filter(o -> "Pair".equals(o.getName()) && "Stroma".equals(String.valueOf(o.getPathClass()))).count() != 2) throw new AssertionError("Split must keep name and class");
        reject(() -> Workflows.editObjects(hierarchy,List.of(far),"split",new com.google.gson.JsonObject(),Double.NaN));
        Workflows.editObjects(hierarchy,List.of(far),"lock",new com.google.gson.JsonObject(),Double.NaN);
        if (!far.isLocked()) throw new AssertionError("Lock failed");
        var args = JsonParser.parseString("{\"value\":\"Necrosis\"}").getAsJsonObject();
        Workflows.editObjects(hierarchy,List.of(far),"set_class",args,Double.NaN);
        if (!"Necrosis".equals(String.valueOf(far.getPathClass()))) throw new AssertionError("set_class failed");
        Workflows.editObjects(hierarchy,List.of(far),"rename",JsonParser.parseString("{\"value\":\"Edge\"}").getAsJsonObject(),Double.NaN);
        if (!"Edge".equals(far.getName())) throw new AssertionError("rename failed");
        double before = far.getROI().getArea();
        Workflows.editObjects(hierarchy,List.of(far),"expand",JsonParser.parseString("{\"distanceMicrons\":5}").getAsJsonObject(),0.5);
        if (far.getROI().getArea() <= before) throw new AssertionError("Expand by microns must grow the ROI");
        reject(() -> Workflows.editObjects(hierarchy,List.of(far),"expand",JsonParser.parseString("{\"distanceMicrons\":5}").getAsJsonObject(),Double.NaN));
        reject(() -> Workflows.editObjects(hierarchy,List.of(far),"expand",JsonParser.parseString("{\"distance\":-1000}").getAsJsonObject(),Double.NaN));
        reject(() -> Workflows.editObjects(hierarchy,List.of(),"lock",new com.google.gson.JsonObject(),Double.NaN));
        reject(() -> Workflows.editObjects(hierarchy,List.of(far),"explode",new com.google.gson.JsonObject(),Double.NaN));
        // Exports go only to the configured folder and report real counts.
        var folder = Files.createTempDirectory("luma-export-test-");
        System.setProperty("luma.exportDirectory",folder.toString());
        try {
            var cell = PathObjects.createCellObject(ROIs.createEllipseROI(10,10,8,8,ImagePlane.getDefaultPlane()),null,PathClass.fromString("Positive"),null);
            cell.getMeasurementList().put("Nucleus: DAB OD mean",0.42); hierarchy.addObject(cell);
            var csv = Workflows.exportMeasurements(image,List.of(cell),"cell-measurements",List.of());
            var lines = Files.readAllLines(Path.of((String) csv.get("path")));
            if (lines.size() != 2 || !csv.get("rows").equals(1) || !lines.getFirst().contains("Nucleus: DAB OD mean") || !lines.get(1).contains("0.42")) throw new AssertionError("CSV export content wrong: " + lines);
            reject(() -> Workflows.exportMeasurements(image,List.of(cell),"x",List.of("Missing column")));
            var geo = Workflows.exportGeoJson(image,new ArrayList<>(hierarchy.getAnnotationObjects()),"annotations",true);
            var parsed = JsonParser.parseString(Files.readString(Path.of((String) geo.get("path")))).getAsJsonObject();
            if (!parsed.get("type").getAsString().equals("FeatureCollection") || parsed.getAsJsonArray("features").size() != hierarchy.getAnnotationObjects().size()) throw new AssertionError("GeoJSON export wrong");
            reject(() -> Workflows.exportGeoJson(image,List.of(),"none",false));
            if (!Path.of((String) geo.get("path")).startsWith(folder)) throw new AssertionError("Export escaped its folder");
        } finally { try (var files = Files.walk(folder)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); } }
        image.getServer().close();
        System.out.println("Plugin allow-list, parameter validation, stain parsing, object edits and bounded exports: passed");
    }
    interface Check { void run() throws Exception; }
    private static void reject(Check check) throws Exception { try { check.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("Invalid request accepted"); }
}
