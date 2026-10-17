package science.luma.fiji;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Immutable identity of the bridge loaded at startup, not a later jar on disk. */
final class BridgeIdentity {
    static final String VERSION = "0.1.0";
    static final int PROTOCOL_VERSION = 1;
    static final List<String> OPERATIONS = List.of(
        "context", "images", "open_path", "activate_image", "select_roi", "roi_manager", "add_to_manager",
        "measure", "results", "clear_results", "threshold", "analyze_particles", "segment_cells", "filter", "convert",
        "duplicate", "zoom", "highlight", "overlay", "display", "stack", "commands", "run_command", "ui", "ui_action",
        "run_macro", "run_script", "recorder", "save_workflow", "save_image", "export_results", "checkpoint",
        "checkpoints", "compare", "restore", "close", "snapshot", "log", "plugins", "undo", "status",
        "track_objects", "intensity_over_time", "tumor_region", "colocalize", "classify_intensity", "object_stats",
        "make_macro", "workflows", "run_workflow", "capture_macro");
    private static final Map<String, Object> DESCRIPTION = Map.of("adapterVersion", VERSION, "protocolVersion", PROTOCOL_VERSION,
        "sourceSha256", sourceDigest(), "operations", OPERATIONS);
    private static String sourceDigest() {
        try (var stream = BridgeIdentity.class.getResourceAsStream("bridge-build.properties")) { return readDigest(stream); }
        catch (Exception error) { return "unknown"; }
    }
    static String readDigest(InputStream stream) throws Exception {
        if (stream == null) return "unknown";
        var values = new Properties(); values.load(stream);
        String value = values.getProperty("sourceSha256", "");
        return value.matches("[a-f0-9]{64}") ? value : "unknown";
    }
    static Map<String, Object> description() { return DESCRIPTION; }
}
