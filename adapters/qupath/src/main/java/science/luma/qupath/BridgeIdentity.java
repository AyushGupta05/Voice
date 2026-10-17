package science.luma.qupath;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Immutable identity of the extension loaded at startup, not a later jar on disk. */
final class BridgeIdentity {
    static final String VERSION="0.3.0";
    static final List<String> OPERATIONS=List.of(
        "context","objects","measurements","measurement_summary","analysis_catalog","project_images",
        "open_path","open_project_image","inspect_import","import_annotations","select","focus","highlight","class_summary","workflow_steps","save_workflow","overlays",
        "snapshot","commands","ui","ui_action","annotate","detect","classify","image_type","checkpoint",
        "checkpoints","compare","restore","delete","command","script",
        "export_measurements","export_objects","export_image","set_stains","estimate_stains","edit_objects","display","plugins","run_plugin",
        "detect_positive","classifiers","apply_object_classifier","apply_pixel_classifier","save");
    private static final Map<String,Object> DESCRIPTION=Map.of("adapterVersion",VERSION,"protocolVersion",1,
        "sourceSha256",sourceDigest(),"operations",OPERATIONS);
    private static String sourceDigest() {
        try(var stream=BridgeIdentity.class.getResourceAsStream("bridge-build.properties")){return readDigest(stream);}
        catch(Exception error){return "unknown";}
    }
    static String readDigest(InputStream stream) throws Exception {
        if(stream==null)return "unknown";
        var values=new Properties();values.load(stream);
        String value=values.getProperty("sourceSha256","");
        return value.matches("[a-f0-9]{64}")?value:"unknown";
    }
    static Map<String,Object> description(){return DESCRIPTION;}
}
