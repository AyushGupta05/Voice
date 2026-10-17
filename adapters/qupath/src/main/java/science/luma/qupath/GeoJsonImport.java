package science.luma.qupath;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.images.ImageData;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Bounded GeoJSON annotation input. No Java serialization, archives, URLs or implicit coordinate conversion. */
final class GeoJsonImport {
    private static final int MAX_BYTES=16*1024*1024;
    record Input(Path path,String sha256,List<PathObject> objects,List<Geometry> sourceGeometry) {
        Map<String,Object> preview() {
            var classes=new TreeMap<String,Long>();
            for(var object:objects)classes.merge(object.getPathClass()==null?"0":"1"+object.getPathClass(),1L,Long::sum);
            var preview=new LinkedHashMap<String,Object>();
            preview.put("path",path.toString());preview.put("sha256",sha256);preview.put("annotations",objects.size());
            preview.put("classCounts",classes.entrySet().stream().limit(50).map(entry->{var row=new LinkedHashMap<String,Object>();row.put("classification",entry.getKey().equals("0")?null:entry.getKey().substring(1));row.put("count",entry.getValue());return row;}).toList());
            preview.put("classCount",classes.size());preview.put("classCountsTruncated",classes.size()>50);
            preview.put("preview",objects.stream().limit(20).map(object->Map.of("id",object.getID().toString(),"name",Objects.toString(object.getName(),""),"x",object.getROI().getBoundsX(),"y",object.getROI().getBoundsY(),"width",object.getROI().getBoundsWidth(),"height",object.getROI().getBoundsHeight(),"z",object.getROI().getZ(),"t",object.getROI().getT())).toList());
            preview.put("previewTruncated",objects.size()>20);preview.put("coordinateSpace","Full-resolution image pixels required; no spatial coordinate-system conversion was performed.");
            int changed=0;double maxAreaChange=0;for(int i=0;i<objects.size();i++){var converted=objects.get(i).getROI().getGeometry();var original=sourceGeometry.get(i);if(!original.norm().equalsExact(converted.norm()))changed++;maxAreaChange=Math.max(maxAreaChange,Math.abs(original.getArea()-converted.getArea()));}
            preview.put("geometryConversion",Map.of("featuresChanged",changed,"maxAbsoluteAreaChangePixels2",maxAreaChange,"note","QuPath can quantize geometry to 0.01 image pixels. Original topology/bounds are validated; lost components/holes are refused. Review conversion changes before approval."));return preview;
        }
    }
    static Input read(String filename,String expectedSha) throws IOException {
        var path=Path.of(filename);if(!path.isAbsolute()||!Files.isRegularFile(path))throw new IllegalArgumentException("Choose an existing absolute GeoJSON file path.");
        path=path.toRealPath();String lower=path.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!lower.endsWith(".geojson")&&!lower.endsWith(".json"))throw new IllegalArgumentException("Only .geojson/.json annotation FeatureCollections are supported.");
        byte[] bytes;try(var stream=Files.newInputStream(path)){bytes=stream.readNBytes(MAX_BYTES+1);}
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("GeoJSON exceeds 16 MiB.");
        String sha;try{sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
        if(expectedSha!=null&&!sha.equals(expectedSha))throw new IllegalStateException("Import file changed since inspection. Inspect and review again.");
        List<PathObject> objects;var originals=new ArrayList<Geometry>();
        try{
            JsonElement root;
            String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            try(var reader=new JsonReader(new StringReader(decoded))){
                reader.setNestingLimit(64);reader.setStrictness(Strictness.STRICT);
                root=JsonParser.parseReader(reader);
                if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException();
            }
            JsonArray features;
            if(root.isJsonArray())features=root.getAsJsonArray();
            else if(root.isJsonObject()&&root.getAsJsonObject().has("type")&&root.getAsJsonObject().get("type").getAsString().equals("FeatureCollection"))features=root.getAsJsonObject().getAsJsonArray("features");
            else {features=new JsonArray();features.add(root);}
            if(features==null||features.isEmpty()||features.size()>10_000)throw new IllegalArgumentException();
            var sourceIds=new HashSet<String>();int index=0;
            for(var feature:features){
                var f=feature.getAsJsonObject();if(!f.get("type").getAsString().equals("Feature")||!f.has("geometry")||f.get("geometry").isJsonNull())throw new IllegalArgumentException();
                originals.add(validateRawFeature(f));bindIdentity(f,sha,index++,sourceIds);
            }
            var collection=new JsonObject();collection.addProperty("type","FeatureCollection");collection.add("features",features);
            objects=GsonTools.parseObjectsFromGeoJSON(collection);
            if(objects.size()!=features.size())throw new IllegalArgumentException();
        }catch(RuntimeException error){throw new IllegalArgumentException("Expected valid GeoJSON with 1–10,000 annotation Features (a Feature, array or FeatureCollection).");}
        var ids=new HashSet<UUID>();
        for(var object:objects){var roi=object.getROI();
            if(Objects.toString(object.getName(),"").length()>1000||object.getPathClass()!=null&&object.getPathClass().toString().length()>1000)throw new IllegalArgumentException("Imported names/classes must contain at most 1000 characters.");
            if(!object.isAnnotation()||object.hasChildObjects()||roi==null||roi.isEmpty()||!roi.getGeometry().isValid()||!ids.add(object.getID()))throw new IllegalArgumentException("Import requires leaf annotations with valid, non-empty geometry and distinct IDs.");
            if(!Double.isFinite(roi.getBoundsX())||!Double.isFinite(roi.getBoundsY())||!Double.isFinite(roi.getBoundsWidth())||!Double.isFinite(roi.getBoundsHeight()))throw new IllegalArgumentException("Import geometry must have finite bounds.");
        }
        for(int i=0;i<objects.size();i++)if(!topology(originals.get(i)).equals(topology(objects.get(i).getROI().getGeometry())))throw new IllegalArgumentException("QuPath conversion removed or changed geometry components/holes. Simplify explicitly before import.");
        return new Input(path,sha,List.copyOf(objects),List.copyOf(originals));
    }
    private static void bindIdentity(JsonObject feature,String sha,int index,Set<String> seen) {
        String key="index:"+index;UUID id=null;
        if(feature.has("id")) {
            var source=feature.get("id");if(!source.isJsonPrimitive())throw new IllegalArgumentException();var value=source.getAsJsonPrimitive();
            if(value.isString()){
                String text=value.getAsString();if(text.length()>1000)throw new IllegalArgumentException();key="string:"+text;
                if(Set.of("PathTileObject","PathCellObject","TMACoreObject","PathDetectionObject","PathRootObject").contains(text))throw new IllegalArgumentException("Legacy non-annotation object ID");
                try {var parsed=UUID.fromString(text);if(parsed.toString().equalsIgnoreCase(text)){id=parsed;key="uuid:"+parsed;}}catch(IllegalArgumentException ignored){}
            }else if(value.isNumber()&&Double.isFinite(value.getAsDouble()))key="number:"+value.getAsBigDecimal().stripTrailingZeros().toString();
            else throw new IllegalArgumentException();
            if(!seen.add(key))throw new IllegalArgumentException("Duplicate source ID");
        }
        if(id==null)id=UUID.nameUUIDFromBytes(("luma:geojson:"+sha+":"+key).getBytes(StandardCharsets.UTF_8));
        feature.addProperty("id",id.toString());
    }
    private static Geometry validateRawFeature(JsonObject feature) {
        if(feature.has("nucleusGeometry"))throw new IllegalArgumentException();
        var geometry=feature.getAsJsonObject("geometry");String type=geometry.get("type").getAsString();
        int depth=switch(type){case "Point"->0;case "MultiPoint","LineString"->1;case "MultiLineString","Polygon"->2;case "MultiPolygon"->3;default->throw new IllegalArgumentException();};
        validateCoordinates(geometry.get("coordinates"),depth);
        var original=originalGeometry(type,geometry.getAsJsonArray("coordinates"),new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING)));
        if(original.isEmpty()||!original.isValid())throw new IllegalArgumentException("Source topology is invalid");
        if(geometry.has("plane"))for(var entry:geometry.getAsJsonObject("plane").entrySet()){
            if(!Set.of("c","z","t").contains(entry.getKey())||!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();
            double number=entry.getValue().getAsDouble();if(!Double.isFinite(number)||number!=Math.rint(number)||number<Integer.MIN_VALUE||number>Integer.MAX_VALUE)throw new IllegalArgumentException();
        }
        if(!feature.has("properties"))return original;var properties=feature.getAsJsonObject("properties");
        for(String child:List.of("children","childObjects"))if(properties.has(child)&&(!properties.get(child).isJsonArray()||!properties.getAsJsonArray(child).isEmpty()))throw new IllegalArgumentException();
        for(String key:List.of("objectType","object_type","type"))if(properties.has(key)&&!Set.of("annotation","PathAnnotationObject").contains(properties.get(key).getAsString()))throw new IllegalArgumentException();
        if(properties.has("name")&&(!properties.get("name").isJsonPrimitive()||!properties.get("name").getAsJsonPrimitive().isString()||properties.get("name").getAsString().length()>1000))throw new IllegalArgumentException();
        if(properties.has("classification"))validateClassification(properties.get("classification"));
        return original;
    }
    private static Coordinate coordinate(JsonArray array){return new Coordinate(array.get(0).getAsDouble(),array.get(1).getAsDouble());}
    private static Coordinate[] coordinates(JsonArray array){return array.asList().stream().map(value->coordinate(value.getAsJsonArray())).toArray(Coordinate[]::new);}
    private static Geometry originalGeometry(String type,JsonArray array,GeometryFactory factory){
        return switch(type){
            case "Point"->factory.createPoint(coordinate(array));
            case "MultiPoint"->factory.createMultiPointFromCoords(coordinates(array));
            case "LineString"->factory.createLineString(coordinates(array));
            case "MultiLineString"->factory.createMultiLineString(array.asList().stream().map(value->factory.createLineString(coordinates(value.getAsJsonArray()))).toArray(LineString[]::new));
            case "Polygon"->{var shell=factory.createLinearRing(coordinates(array.get(0).getAsJsonArray()));var holes=array.asList().stream().skip(1).map(value->factory.createLinearRing(coordinates(value.getAsJsonArray()))).toArray(LinearRing[]::new);yield factory.createPolygon(shell,holes);}
            case "MultiPolygon"->factory.createMultiPolygon(array.asList().stream().map(value->(Polygon)originalGeometry("Polygon",value.getAsJsonArray(),factory)).toArray(Polygon[]::new));
            default->throw new IllegalArgumentException();
        };
    }
    private static List<String> topology(Geometry geometry){
        var result=new ArrayList<String>();
        if(geometry instanceof Polygon polygon)result.add("area:"+polygon.getNumInteriorRing());
        else if(geometry instanceof GeometryCollection collection){for(int i=0;i<collection.getNumGeometries();i++)result.addAll(topology(collection.getGeometryN(i)));}
        else result.add(geometry.getDimension()==0?"point":"line");
        Collections.sort(result);return result;
    }
    private static void validateCoordinates(JsonElement element,int depth) {
        if(element==null||!element.isJsonArray())throw new IllegalArgumentException();var array=element.getAsJsonArray();
        if(depth==0){if(array.size()!=2)throw new IllegalArgumentException();for(var number:array)if(!number.isJsonPrimitive()||!number.getAsJsonPrimitive().isNumber()||!Double.isFinite(number.getAsDouble()))throw new IllegalArgumentException();}
        else{if(array.isEmpty())throw new IllegalArgumentException();for(var child:array)validateCoordinates(child,depth-1);}
    }
    private static void validateClassification(JsonElement classification) {
        if(classification.isJsonNull())return;
        var names=new ArrayList<String>();
        if(classification.isJsonPrimitive()&&classification.getAsJsonPrimitive().isString())names.add(classification.getAsString());
        else if(classification.isJsonObject()){
            var value=classification.getAsJsonObject();
            // Refuse legacy recursive class representations before QuPath creates cached PathClass chains.
            if(value.has("parentClass")||value.has("colorRGB"))throw new IllegalArgumentException();
            if(value.has("name")){var name=value.get("name");if(!name.isJsonPrimitive()||!name.getAsJsonPrimitive().isString())throw new IllegalArgumentException();names.add(name.getAsString());}
            else if(value.has("names")){var array=value.getAsJsonArray("names");if(array.size()>32)throw new IllegalArgumentException();for(var name:array){if(!name.isJsonPrimitive()||!name.getAsJsonPrimitive().isString())throw new IllegalArgumentException();names.add(name.getAsString());}}
            else throw new IllegalArgumentException();
        }else throw new IllegalArgumentException();
        int length=0,components=0;for(String name:names){length+=name.length();if(length>1000)throw new IllegalArgumentException();components+=name.split(":",-1).length;if(components>32)throw new IllegalArgumentException();}
    }
    static void validateImage(Input input,ImageData<BufferedImage> image) {
        var server=image.getServer();var existing=new HashSet<UUID>();image.getHierarchy().getFlattenedObjectList(null).forEach(object->existing.add(object.getID()));
        for(int i=0;i<input.objects().size();i++){var object=input.objects().get(i);var roi=object.getROI();var envelope=input.sourceGeometry().get(i).getEnvelopeInternal();
            if(envelope.getMinX()<0||envelope.getMinY()<0||envelope.getMaxX()>server.getWidth()||envelope.getMaxY()>server.getHeight())throw new IllegalArgumentException("Original source coordinates are outside this image before QuPath conversion.");
            if(roi.getBoundsX()<0||roi.getBoundsY()<0||roi.getBoundsX()+roi.getBoundsWidth()>server.getWidth()||roi.getBoundsY()+roi.getBoundsHeight()>server.getHeight()||roi.getZ()<0||roi.getZ()>=server.nZSlices()||roi.getT()<0||roi.getT()>=server.nTimepoints()||roi.getC()<-1||roi.getC()>=server.nChannels())throw new IllegalArgumentException("Annotation does not fit this image/plane. Supply full-resolution image-pixel coordinates.");
            if(existing.contains(object.getID()))throw new IllegalArgumentException("An imported object ID already exists. Import was not repeated.");
        }
    }
}
