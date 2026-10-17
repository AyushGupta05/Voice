package science.luma.fiji;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.OvalRoi;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Windowless ImageJ (NO_SHOW) with a synthetic 8-bit image, driven through the real HTTP server and policy. Never touches research data. */
public final class HeadlessContractTest {
    static final int PORT = 49597;
    static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void main(String[] ignored) throws Exception {
        new ImageJ(ImageJ.NO_SHOW);
        ij.Prefs.blackBackground = true;
        var bridge = new LumaBridge();
        bridge.start(TOKEN, PORT);
        try { run(bridge); } catch (Throwable error) { error.printStackTrace(); System.exit(1); } finally { bridge.stop(); }
        System.out.println("Windowless Fiji contract (policy, context key, ROI, measure, threshold, particles, segmentation, checkpoints, restore, recorder, snapshot): passed");
        System.exit(0);
    }

    static void run(LumaBridge bridge) throws Exception {
        // --- auth + unknown operation
        expect(raw("POST", "/execute", "{\"operation\":\"context\"}", "wrong").statusCode() == 401, "wrong token is rejected");
        expect(raw("GET", "/health", null, TOKEN).body().contains("\"application\":\"Fiji\""), "health reports Fiji");
        var unknown = raw("POST", "/execute", "{\"operation\":\"nope\"}", TOKEN);
        expect(unknown.statusCode() == 400, "unknown operation is a 400: " + unknown.body());
        var noImage = call("context", new JsonObject(), false, null);
        expect(noImage.get("image").isJsonNull() && noImage.get("contextKey").getAsString().length() == 64, "context without an image still has a key");

        // --- synthetic image: dark background, 12 bright discs (radius 8 and 12) on a 256x256 8-bit canvas
        var processor = new ByteProcessor(256, 256);
        processor.setValue(20); processor.fill();
        int discs = 0;
        for (int row = 0; row < 3; row++) for (int column = 0; column < 4; column++) {
            int cx = 40 + column * 56, cy = 48 + row * 72, r = (row + column) % 2 == 0 ? 8 : 12;
            processor.setValue(200 + row * 10); processor.fill(new OvalRoi(cx - r, cy - r, 2 * r, 2 * r)); discs++;
        }
        var imp = new ImagePlus("synthetic-blobs.tif", processor);
        WindowManager.setTempCurrentImage(imp);
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(imp));

        var context = call("context", new JsonObject(), false, null);
        String key = context.get("contextKey").getAsString();
        expect(context.getAsJsonObject("image").get("title").getAsString().equals("synthetic-blobs.tif"), "context sees the synthetic image");
        expect(context.getAsJsonObject("image").get("bitDepth").getAsInt() == 8, "8-bit image");
        expect(context.get("viewer").isJsonNull(), "no window means no viewer geometry");
        expect(context.has("pointer") && !context.getAsJsonObject("pointer").get("overCanvas").getAsBoolean() && context.getAsJsonObject("pointer").get("x").isJsonNull(), "pointer is reported but not over a canvas: " + context.get("pointer"));
        expect(context.getAsJsonObject("adapter").getAsJsonArray("operations").size() == 54, "adapter declares 54 operations");

        var status = call("status", new JsonObject(), false, null);
        expect(status.has("status") && !status.get("analysisRunning").getAsBoolean(), "status reports the status bar text: " + status);
        // --- policy: data ops need approved + expectedContext, and a stale key is refused
        var refused = rawCall("measure", obj("scope", "all"), null, null);
        expect(refused.statusCode() == 409 && refused.body().contains("approved"), "data op without approval refused: " + refused.body());
        var stale = rawCall("measure", obj("scope", "all"), true, "0".repeat(64));
        expect(stale.statusCode() == 409 && stale.body().contains("changed"), "stale expectedContext refused: " + stale.body());

        // --- select_roi (view op) changes the key
        var roiArgs = obj("type", "rectangle"); roiArgs.addProperty("x", 20); roiArgs.addProperty("y", 20); roiArgs.addProperty("width", 100); roiArgs.addProperty("height", 100); roiArgs.addProperty("name", "corner");
        var selected = call("select_roi", roiArgs, false, null);
        expect(selected.getAsJsonObject("roi").get("type").getAsString().equals("Rectangle") && selected.getAsJsonObject("roi").get("name").getAsString().equals("corner"), "rectangle ROI set: " + selected);
        expect(selected.getAsJsonObject("highlight").size() == 0, "no window: highlight is empty, never an error");
        String roiKey = selected.get("contextKey").getAsString();
        expect(!roiKey.equals(key), "ROI changes the context key");

        // --- measure within the ROI
        var measureArgs = obj("scope", "roi"); measureArgs.add("measurements", array("area", "mean", "min", "max"));
        var measured = call("measure", measureArgs, true, roiKey);
        expect(measured.get("newRows").getAsInt() == 1 && measured.get("rows").getAsInt() == 1, "one measurement row: " + measured);
        double area = measured.getAsJsonObject("lastRow").get("Area").getAsDouble();
        expect(area == 10000.0, "ROI area is exactly 100x100 pixels: " + area);
        double mean = measured.getAsJsonObject("lastRow").get("Mean").getAsDouble();
        expect(mean > 20 && mean < 200, "mean intensity between background and discs: " + mean);
        expect(measured.getAsJsonObject("summary").getAsJsonObject("area").get("mean").getAsDouble() == 10000.0, "summary.area.mean");
        expect(measured.has("checkpoint") && measured.getAsJsonObject("checkpoint").has("id"), "measure took a checkpoint first");
        String afterMeasure = measured.get("contextKey").getAsString();
        expect(!afterMeasure.equals(roiKey), "results rows change the context key");

        // --- whole-image measure, results listing, clear
        call("select_roi", obj("type", "none"), false, null);
        String noRoiKey = key("context");
        var all = call("measure", obj("scope", "all"), true, noRoiKey);
        expect(all.get("rows").getAsInt() == 2 && all.getAsJsonObject("lastRow").get("Area").getAsDouble() == 65536.0, "whole image area 256x256: " + all.getAsJsonObject("lastRow"));
        var rows = call("results", new JsonObject(), false, null);
        expect(rows.get("total").getAsInt() == 2 && rows.getAsJsonArray("rows").size() == 2, "results lists both rows");
        var cleared = call("clear_results", new JsonObject(), true, key("context"));
        expect(cleared.get("cleared").getAsInt() == 2 && call("results", new JsonObject(), false, null).get("total").getAsInt() == 0, "clear_results empties the table");

        // --- checkpoint before destructive edits
        var checkpoint = call("checkpoint", obj("label", "Before everything"), false, null);
        String checkpointId = checkpoint.get("id").getAsString();
        expect(checkpoint.get("bytes").getAsLong() == 65536, "8-bit 256x256 checkpoint is 64 KiB of pixels");

        // --- threshold
        var thresholded = call("threshold", obj("method", "Otsu"), true, key("context"));
        double lower = thresholded.get("lower").getAsDouble(), upper = thresholded.get("upper").getAsDouble();
        expect(thresholded.get("method").getAsString().equals("Otsu") && thresholded.get("dark").getAsBoolean() && lower > 20 && lower <= 200 && upper == 255, "Otsu dark threshold separates discs from background: " + lower + "-" + upper);
        var bad = rawCall("threshold", obj("method", "Nonsense"), true, key("context"));
        expect(bad.statusCode() == 400, "unknown method rejected");
        var manual = call("threshold", obj("lower", 150, "upper", 255), true, key("context"));
        expect(manual.get("lower").getAsDouble() == 150 && manual.get("method").getAsString().equals("manual"), "manual threshold");

        // --- analyze particles with the hidden ROI Manager
        var particleArgs = obj("minSize", 10); particleArgs.addProperty("addToManager", true);
        var particles = call("analyze_particles", particleArgs, true, key("context"));
        expect(particles.get("count").getAsInt() == discs, "finds all " + discs + " discs: " + particles.get("count"));
        expect(particles.get("addedToManager").getAsInt() == discs && particles.get("managerTotal").getAsInt() == discs, "discs added to the ROI Manager: " + particles);
        expect(imp.getOverlay() != null && imp.getOverlay().size() == discs && imp.getOverlay().get(0).getStrokeColor().equals(java.awt.Color.MAGENTA) && "1".equals(imp.getOverlay().get(0).getName()), "particles outlined in the overlay with numbers");
        @SuppressWarnings("unchecked") var flash = particles.getAsJsonObject("highlight");
        expect(flash.size() == 0 || flash.get("flash").getAsBoolean(), "highlight payloads carry flash:true when a window exists");
        expect(LumaBridge.rootFromJar() == null || java.nio.file.Files.isRegularFile(java.nio.file.Path.of(LumaBridge.rootFromJar(), ".local/state/bridge-token")), "jar-relative root only when a token exists there");
        double average = particles.get("averageSize").getAsDouble();
        double expectedAverage = (6 * Math.PI * 64 + 6 * Math.PI * 144) / 12;
        expect(Math.abs(average - expectedAverage) / expectedAverage < 0.12, "average disc area near πr² (" + expectedAverage + "): " + average);
        var manager = call("roi_manager", new JsonObject(), false, null);
        expect(manager.get("total").getAsInt() == discs && manager.getAsJsonArray("rois").get(0).getAsJsonObject().has("x"), "ROI Manager lists the particle ROIs");

        // --- measure the particles on the original intensities
        var managerMeasure = call("measure", obj("scope", "manager"), true, key("context"));
        expect(managerMeasure.get("newRows").getAsInt() == discs, "one row per manager ROI: " + managerMeasure.get("newRows"));
        double discMean = managerMeasure.getAsJsonObject("summary").getAsJsonObject("mean").get("min").getAsDouble();
        expect(discMean >= 150, "particle ROIs measure bright pixels: " + discMean);

        // --- restore the checkpoint: threshold gone, manager empty, results empty
        var compare = call("compare", obj("checkpointId", checkpointId), false, null);
        expect(compare.getAsJsonObject("delta").get("roiManager").getAsInt() == discs && compare.getAsJsonObject("delta").get("resultsRows").getAsInt() == 2 * discs && compare.getAsJsonObject("delta").get("overlay").getAsInt() == discs, "compare reports the delta (ImageJ shows manager ROIs as an overlay): " + compare.get("delta"));
        var restored = call("restore", obj("checkpointId", checkpointId), true, key("context"));
        expect(restored.getAsJsonObject("counts").get("roiManager").getAsInt() == 0 && restored.getAsJsonObject("counts").get("resultsRows").getAsInt() == 0, "restore empties manager and results: " + restored.get("counts"));
        expect(RoiManager.getRawInstance() == null, "no visible ROI Manager window was created");
        expect(imp.getProcessor().getPixel(40, 48) >= 200 && imp.getProcessor().getPixel(5, 5) == 20, "pixels intact after restore");

        // --- segment_cells end to end on the raw image (no threshold set)
        expect(call("context", new JsonObject(), false, null).getAsJsonObject("image").get("threshold").isJsonNull(), "no threshold after restore");
        var segmented = call("segment_cells", obj("method", "Otsu"), true, key("context"));
        expect(segmented.get("count").getAsInt() == discs, "segment_cells counts " + discs + " cells: " + segmented.get("count"));
        expect(segmented.getAsJsonArray("steps").size() >= 6 && segmented.get("macro").getAsString().contains("Watershed"), "segmentation steps and macro recorded: " + segmented.get("steps"));
        expect(segmented.get("managerTotal").getAsInt() == discs, "cell ROIs in the manager");
        expect(call("images", new JsonObject(), false, null).getAsJsonArray("images").size() == 1, "the scratch mask was closed: " + call("images", new JsonObject(), false, null));
        expect(WindowManager.getCurrentImage() == imp, "the original image stays active");

        // --- undo goes back to the checkpoint taken before segment_cells
        var undone = call("undo", new JsonObject(), true, key("context"));
        expect(undone.get("mode").getAsString().equals("checkpoint") && undone.getAsJsonObject("counts").get("roiManager").getAsInt() == 0, "undo restores the pre-segmentation checkpoint: " + undone);

        // --- "only this area": an active area ROI scopes segmentation to particles whose centroid lies inside it
        var scopeRoi = obj("type", "rectangle"); scopeRoi.addProperty("x", 0); scopeRoi.addProperty("y", 0); scopeRoi.addProperty("width", 120); scopeRoi.addProperty("height", 254);
        call("select_roi", scopeRoi, false, null);
        var scoped = call("segment_cells", obj("method", "Otsu"), true, key("context"));
        expect(scoped.get("scope").getAsString().equals("selection") && scoped.get("count").getAsInt() == 6 && scoped.get("excludedOutsideSelection").getAsInt() == discs - 6, "six discs have centroids inside x<120: " + scoped.get("count") + " " + scoped.get("scope"));
        expect(scoped.get("managerTotal").getAsInt() == 6 && scoped.get("resultsRows").getAsInt() == 6 && scoped.get("channelChosenBy").getAsString().equals("current"), "manager and results trimmed to the selection: " + scoped);
        expect(imp.getRoi() != null && imp.getRoi().getBounds().width == 120, "the selection is restored after segmentation");
        var ctxChannels = call("context", new JsonObject(), false, null).getAsJsonObject("image").getAsJsonArray("channels");
        expect(ctxChannels.size() == 1 && ctxChannels.get(0).getAsJsonObject().get("lut").getAsString().equals("grays"), "context lists channels with LUT names: " + ctxChannels);
        call("undo", new JsonObject(), true, key("context"));
        call("select_roi", obj("type", "none"), false, null);

        // --- nuclei channel choice on a synthetic composite (green + blue LUTs, then DAPI label)
        var twoChannel = new ij.ImageStack(32, 32); twoChannel.addSlice("GFP", new ByteProcessor(32, 32)); twoChannel.addSlice("DAPI", new ByteProcessor(32, 32));
        var composite = new ij.CompositeImage(new ImagePlus("two-channel", twoChannel), ij.CompositeImage.COMPOSITE);
        composite.setDimensions(2, 1, 1);
        composite.setLuts(new ij.process.LUT[] { ij.process.LUT.createLutFromColor(java.awt.Color.GREEN), ij.process.LUT.createLutFromColor(java.awt.Color.BLUE) });
        var byLut = Operations.nucleiChannel(composite, new JsonObject());
        expect(byLut.channel() == 2 && byLut.by().equals("lut-blue"), "blue LUT channel chosen: " + byLut);
        composite.setLuts(new ij.process.LUT[] { ij.process.LUT.createLutFromColor(java.awt.Color.WHITE), ij.process.LUT.createLutFromColor(java.awt.Color.WHITE) });
        var byName = Operations.nucleiChannel(composite, new JsonObject());
        expect(byName.channel() == 2 && byName.by().equals("name"), "DAPI-labelled channel chosen: " + byName);
        expect(Operations.nucleiChannel(composite, obj("channel", 1)).by().equals("argument"), "explicit channel wins");
        var described = Operations.describe(composite);
        @SuppressWarnings("unchecked") var channels = (List<Map<String, Object>>) described.get("channels");
        expect(channels.size() == 2 && "DAPI".equals(channels.get(1).get("label")) && "grays".equals(channels.get(1).get("lut")), "channels carry labels and LUT names: " + channels);

        // --- filters and conversions change pixels and are checkpointed
        expect(imp.getProcessor().getPixel(47, 48) >= 200, "disc edge pixel starts bright");
        var blurred = call("filter", obj("name", "gaussian", "sigma", 4), true, key("context"));
        expect(blurred.get("command").getAsString().equals("Gaussian Blur...") && imp.getProcessor().getPixel(47, 48) < 180, "gaussian blur softened the disc edge: " + imp.getProcessor().getPixel(47, 48));
        expect(call("restore", obj("checkpointId", blurred.getAsJsonObject("checkpoint").get("id").getAsString()), true, key("context")).has("restored") && imp.getProcessor().getPixel(47, 48) >= 200, "restore after filter brings the pixels back");
        var converted = call("convert", obj("to", "16-bit"), true, key("context"));
        expect(converted.getAsJsonObject("image").get("bitDepth").getAsInt() == 16, "16-bit conversion");
        call("convert", obj("to", "8-bit"), true, key("context"));

        // --- recorder/journal and save_workflow
        var recorder = call("recorder", new JsonObject(), false, null);
        expect(recorder.get("source").getAsString().equals("journal") && recorder.get("macro").getAsString().contains("run(\"Measure\");") && recorder.get("macro").getAsString().contains("setAutoThreshold(\"Otsu dark\");"), "journal holds macro-equivalent lines: " + recorder.get("macro"));
        var workflow = call("save_workflow", obj("name", "Contract test workflow"), true, key("context"));
        var path = java.nio.file.Path.of(workflow.get("path").getAsString());
        expect(java.nio.file.Files.exists(path) && path.getFileName().toString().startsWith("contract-test-workflow") && java.nio.file.Files.readString(path).contains("run(\"Analyze Particles...\""), "workflow .ijm written: " + path);
        java.nio.file.Files.deleteIfExists(path);

        // --- snapshot without a window flattens the image
        var snapshot = call("snapshot", new JsonObject(), false, null);
        expect(snapshot.get("mimeType").getAsString().equals("image/png") && snapshot.get("width").getAsInt() == 256 && snapshot.get("data").getAsString().length() > 100, "flattened PNG snapshot");

        // --- commands and plugins come from the live ImageJ1 menus
        var commands = call("commands", obj("query", "blobs"), false, null);
        expect(commands.getAsJsonArray("commands").size() >= 1 && commands.getAsJsonArray("commands").get(0).getAsJsonObject().get("name").getAsString().startsWith("Blobs"), "Blobs sample command is listed: " + commands);
        expect(commands.getAsJsonArray("commands").get(0).getAsJsonObject().get("menuPath").getAsString().startsWith("File>Open Samples"), "menu path resolved: " + commands);
        var plugins = call("plugins", obj("query", "macro"), false, null);
        expect(plugins.getAsJsonArray("plugins").size() >= 1, "plugin commands listed");

        // --- run_macro with the dialog watcher (no dialog here)
        var macro = call("run_macro", obj("code", "print(\"luma-macro-ok\"); getDimensions(w, h, c, z, t); return \"\" + w;"), true, key("context"));
        expect(macro.get("completed").getAsBoolean() && macro.get("result").getAsString().equals("256") && macro.get("log").getAsString().contains("luma-macro-ok"), "macro ran with a return value and log: " + macro);

        // --- run_command without a dialog (options given), unknown command refused
        int beforeInvert = imp.getProcessor().getPixel(5, 5);
        var commandResult = call("run_command", obj("name", "Invert"), true, key("context"));
        expect(commandResult.get("completed").getAsBoolean() && imp.getProcessor().getPixel(5, 5) == 255 - beforeInvert, "Invert ran synchronously: " + commandResult + " pixel " + imp.getProcessor().getPixel(5, 5));
        expect(rawCall("run_command", obj("name", "Not A Command"), true, key("context")).statusCode() == 400, "unknown command refused");
        expect("Blobs".equals(bridge.scripting.resolveCommand("Blobs (25K)")) && "Invert".equals(bridge.scripting.resolveCommand("invert")) && bridge.scripting.resolveCommand("Nope (1K)") == null, "legacy sample names resolve to the 1.54 labels");

        // --- ui lists nothing visible in a windowless run; ui_action on a bogus id fails cleanly
        var ui = call("ui", new JsonObject(), false, null);
        // ImageJ itself opens the Log window for print() and a Results window after particle analysis; the bridge opens nothing else.
        for (var window : ui.getAsJsonArray("windows")) expect(List.of("Log", "Results").contains(window.getAsJsonObject().get("title").getAsString()), "only ImageJ's own Log/Results windows are visible: " + ui);
        var act = obj("nodeId", "missing", "action", "fire");
        expect(rawCall("ui_action", act, true, key("context")).statusCode() == 409, "ui_action on a stale id is refused");

        // --- a macro that opens a dialog: the watcher returns the dialog, ui lists it, ui_action presses OK and the macro completes
        var dialogMacro = call("run_macro", obj("code", "showMessage(\"Luma test\", \"press ok\"); print(\"after-dialog\");"), true, key("context"));
        expect(!dialogMacro.get("completed").getAsBoolean() && dialogMacro.has("dialog") && dialogMacro.getAsJsonObject("dialog").get("title").getAsString().equals("Luma test"), "dialog watcher returned the open dialog: " + dialogMacro);
        expect(rawCall("measure", obj("scope", "all"), true, key("context")).statusCode() == 409, "mutations are refused while the macro waits on its dialog");
        var windows = call("ui", new JsonObject(), false, null).getAsJsonArray("windows");
        String okId = null;
        for (var window : windows) for (var control : window.getAsJsonObject().getAsJsonArray("controls")) { var c = control.getAsJsonObject(); if (c.get("type").getAsString().equals("button") && c.get("text").getAsString().strip().equals("OK") && window.getAsJsonObject().get("title").getAsString().equals("Luma test")) okId = c.get("id").getAsString(); }
        expect(okId != null, "ui lists the dialog's OK button with screen bounds: " + windows);
        var pressed = call("ui_action", obj("nodeId", okId, "action", "fire"), true, key("context"));
        expect(pressed.get("dispatched").getAsBoolean() && pressed.getAsJsonObject("highlight").has("pointer"), "OK pressed with a control highlight: " + pressed);
        for (int i = 0; i < 100 && call("context", new JsonObject(), false, null).get("analysisRunning").getAsBoolean(); i++) Thread.sleep(50);
        expect(!call("context", new JsonObject(), false, null).get("analysisRunning").getAsBoolean() && Objects.toString(IJ.getLog(), "").contains("after-dialog"), "the macro continued after OK");

        // --- close
        var closed = call("close", obj("all", true), true, key("context"));
        expect(closed.getAsJsonArray("closed").size() == 1, "closed the synthetic image");
        expect(call("context", new JsonObject(), false, null).get("image").isJsonNull(), "no image after close");
    }

    // ----- helpers
    static JsonObject obj(Object... pairs) {
        var object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            var value = pairs[i + 1];
            if (value instanceof Number number) object.addProperty((String) pairs[i], number); else if (value instanceof Boolean bool) object.addProperty((String) pairs[i], bool); else object.addProperty((String) pairs[i], String.valueOf(value));
        }
        return object;
    }
    static com.google.gson.JsonArray array(String... values) { var array = new com.google.gson.JsonArray(); for (var value : values) array.add(value); return array; }
    static String key(String operation) throws Exception { return call(operation, new JsonObject(), false, null).get("contextKey").getAsString(); }
    static HttpResponse<String> rawCall(String operation, JsonObject args, Boolean approved, String expectedContext) throws Exception {
        var request = new JsonObject(); request.addProperty("operation", operation); request.add("arguments", args);
        if (approved != null) request.addProperty("approved", approved);
        if (expectedContext != null) request.addProperty("expectedContext", expectedContext);
        return raw("POST", "/execute", request.toString(), TOKEN);
    }
    static JsonObject call(String operation, JsonObject args, boolean approved, String expectedContext) throws Exception {
        String key = expectedContext;
        if (approved && key == null) key = key("context");
        var response = rawCall(operation, args, approved ? Boolean.TRUE : null, key);
        if (response.statusCode() != 200) throw new AssertionError(operation + " failed with " + response.statusCode() + ": " + response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
    }
    static HttpResponse<String> raw(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + path)).header("Authorization", "Bearer " + token);
        builder = body == null ? builder.GET() : builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
