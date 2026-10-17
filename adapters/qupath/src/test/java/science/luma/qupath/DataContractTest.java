package science.luma.qupath;

import java.util.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.io.PathIO;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

/** Synthetic in-memory data and temporary GeoJSON only; never the running scientific session. */
public final class DataContractTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] ignored) throws Exception {
        var values=new ArrayList<PathObject>();
        for(int i=0;i<350;i++){var object=annotation();object.getMeasurementList().put("Score",i);object.setPathClass(PathClass.fromString(i<100?"A":"B"));values.add(object);}
        var result=MeasurementSummary.summarize(values,List.of("Score"),10,true);
        var stats=(Map<String,Object>)((Map<?,?>)result.get("measurements")).get("Score");
        exact((double)stats.get("mean"),174.5);if(!stats.get("count").equals(350)||!Boolean.FALSE.equals(result.get("sampled")))throw new AssertionError("Summary used a bounded preview");
        var frozen=MeasurementSummary.capture(values,List.of("Score"));values.get(5).getMeasurementList().put("Score",999);
        var frozenStats=(Map<String,Object>)((Map<?,?>)MeasurementSummary.summarize(frozen,10,false).get("measurements")).get("Score");
        exact((double)frozenStats.get("mean"),174.5);values.get(5).getMeasurementList().put("Score",5);
        var histogram=(List<Map<String,Object>>)stats.get("histogram");if(histogram.stream().mapToLong(row->((Number)row.get("count")).longValue()).sum()!=350)throw new AssertionError("Histogram dropped values");
        values.get(0).getMeasurementList().put("Score",Double.NaN);values.get(1).getMeasurementList().clear();
        result=MeasurementSummary.summarize(values,List.of("Score"),2,false);stats=(Map<String,Object>)((Map<?,?>)result.get("measurements")).get("Score");
        if(!stats.get("missingOrNonFinite").equals(2)||!stats.get("count").equals(348))throw new AssertionError("Missing values were hidden");
        reject(()->MeasurementSummary.summarize(values,List.of("Absent"),10,false));
        reject(()->MeasurementSummary.summarize(values,List.of("Score","Score"),10,false));
        var extremes=new ArrayList<PathObject>();for(double value:new double[]{-1e308,1e308}){var object=annotation();object.getMeasurementList().put("Extreme",value);extremes.add(object);}
        result=MeasurementSummary.summarize(extremes,List.of("Extreme"),4,false);stats=(Map<String,Object>)((Map<?,?>)result.get("measurements")).get("Extreme");
        exact((double)stats.get("mean"),0);if(!Double.isFinite((double)stats.get("sampleStandardDeviation")))throw new AssertionError("Finite extreme deviation overflowed");
        histogram=(List<Map<String,Object>>)stats.get("histogram");if(histogram.stream().mapToLong(row->((Number)row.get("count")).longValue()).sum()!=2)throw new AssertionError("Extreme finite histogram failed");
        for(var row:histogram)if(!Double.isFinite((double)row.get("lower"))||!Double.isFinite((double)row.get("upper")))throw new AssertionError("Overflowed histogram edges");
        var image=new ImageData<BufferedImage>(new WrappedBufferedImageServer("Synthetic import fixture",new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)),ImageData.ImageType.BRIGHTFIELD_H_DAB);
        var folder=Files.createTempDirectory("luma-geojson-test-");
        try{
            var path=folder.resolve("annotations.geojson");var object=annotation();object.setName("Synthetic region");
            PathIO.exportObjectsAsGeoJSON(path,List.of(object));
            var input=GeoJsonImport.read(path.toString(),null);GeoJsonImport.validateImage(input,image);
            if(input.objects().size()!=1||!input.objects().getFirst().getID().equals(object.getID())||input.sha256().length()!=64)throw new AssertionError("GeoJSON identity changed");
            var feature=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            var missing=feature.deepCopy();missing.remove("id");Files.writeString(path,missing.toString());
            var noId=GeoJsonImport.read(path.toString(),null);var noIdAgain=GeoJsonImport.read(path.toString(),null);
            if(!noId.objects().getFirst().getID().equals(noIdAgain.objects().getFirst().getID()))throw new AssertionError("Absent IDs are not deterministic");
            image.getHierarchy().addObjects(noId.objects());reject(()->GeoJsonImport.validateImage(noIdAgain,image));
            var custom=feature.deepCopy();custom.addProperty("id","external-42");Files.writeString(path,custom.toString());
            var mapped=GeoJsonImport.read(path.toString(),null);if(!mapped.objects().getFirst().getID().equals(GeoJsonImport.read(path.toString(),null).objects().getFirst().getID()))throw new AssertionError("External IDs are not deterministic");
            var duplicate=new JsonArray();duplicate.add(custom);duplicate.add(custom.deepCopy());Files.writeString(path,duplicate.toString());reject(()->GeoJsonImport.read(path.toString(),null));
            var malformed=feature.deepCopy();malformed.getAsJsonObject("geometry").addProperty("type","MultiPoint");malformed.getAsJsonObject("geometry").add("coordinates",JsonParser.parseString("[[1,1],[\"NaN\",2]]"));Files.writeString(path,malformed.toString());reject(()->GeoJsonImport.read(path.toString(),null));
            var excessiveClass=feature.deepCopy();excessiveClass.getAsJsonObject("properties").addProperty("classification","component:".repeat(20000));Files.writeString(path,excessiveClass.toString());reject(()->GeoJsonImport.read(path.toString(),null));
            var collapsed=feature.deepCopy();collapsed.getAsJsonObject("geometry").addProperty("type","MultiPolygon");collapsed.getAsJsonObject("geometry").add("coordinates",JsonParser.parseString("[[[[1,1],[10,1],[10,10],[1,10],[1,1]]],[[[70,70],[70.001,70],[70.001,70.001],[70,70.001],[70,70]]]]"));Files.writeString(path,collapsed.toString());reject(()->GeoJsonImport.read(path.toString(),null));
            var invalid=feature.deepCopy();invalid.getAsJsonObject("geometry").add("coordinates",JsonParser.parseString("[[[1,1],[10,10],[1,10],[10,1],[1,1]]]"));Files.writeString(path,invalid.toString());reject(()->GeoJsonImport.read(path.toString(),null));
            Files.writeString(path,feature.toString());
            reject(()->GeoJsonImport.read(path.toString(),"0".repeat(64)));
            image.getHierarchy().addObjects(input.objects());reject(()->GeoJsonImport.validateImage(input,image));
            PathIO.exportObjectsAsGeoJSON(path,List.of(PathObjects.createAnnotationObject(ROIs.createRectangleROI(60,60,20,20,ImagePlane.getDefaultPlane()))));
            var outside=GeoJsonImport.read(path.toString(),null);reject(()->GeoJsonImport.validateImage(outside,image));
            PathIO.exportObjectsAsGeoJSON(path,List.of(PathObjects.createDetectionObject(ROIs.createRectangleROI(1,1,2,2,ImagePlane.getDefaultPlane()))));reject(()->GeoJsonImport.read(path.toString(),null));
            PathIO.exportObjectsAsGeoJSON(path,List.of(object,object));reject(()->GeoJsonImport.read(path.toString(),null));
            Files.writeString(path,"{\"type\":\"FeatureCollection\",\"features\":[]}");reject(()->GeoJsonImport.read(path.toString(),null));
            Files.writeString(path,"not JSON");reject(()->GeoJsonImport.read(path.toString(),null));
            Files.writeString(path,"[".repeat(80)+"0"+"]".repeat(80));reject(()->GeoJsonImport.read(path.toString(),null));
        }finally{try(var files=Files.walk(folder)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        image.getServer().close();
        System.out.println("Full-scope summary, missing data, finite histogram extremes, GeoJSON identity/hash/geometry/type/duplicate rejection: passed");
    }
    private static PathObject annotation(){return PathObjects.createAnnotationObject(ROIs.createRectangleROI(1,2,10,20,ImagePlane.getDefaultPlane()));}
    private static void exact(double actual,double expected){if(!Double.isFinite(actual)||Math.abs(actual-expected)>Math.max(1e-10,Math.abs(expected)*1e-12))throw new AssertionError(actual+" != "+expected);}
    interface Check { void run() throws Exception; }
    private static void reject(Check check) throws Exception {try{check.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Invalid data accepted");}
}
