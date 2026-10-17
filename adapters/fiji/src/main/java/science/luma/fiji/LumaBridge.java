package science.luma.fiji;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;
import java.awt.EventQueue;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local semantic adapter for Fiji. The application, not a screenshot, is the source of truth; every mutation is journaled and checkpointed first. */
public final class LumaBridge implements PlugIn {
    static final int DEFAULT_PORT = 49557;
    private static final Gson JSON = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();
    private static final int MAX_BODY = 1024 * 1024;
    private static final long UI_TIMEOUT_SECONDS = 120, COMMAND_TIMEOUT_MS = 20_000, OPEN_TIMEOUT_MS = 100_000;
    static final Set<String> READ = Set.of("context", "images", "roi_manager", "results", "commands", "ui", "recorder", "checkpoints", "compare", "snapshot", "log", "plugins", "status", "colocalize", "object_stats", "workflows", "analysis_workflow", "explain_object");
    /** Status-bar wording while a data/code operation runs, so the action is visible in Fiji itself. */
    static final Map<String, String> STATUS = Map.ofEntries(Map.entry("segment_cells", "segmenting cells"), Map.entry("measure", "measuring"), Map.entry("threshold", "thresholding"),
        Map.entry("analyze_particles", "counting particles"), Map.entry("filter", "applying the filter"), Map.entry("convert", "converting"), Map.entry("open_path", "opening"),
        Map.entry("run_command", "running the command"), Map.entry("run_macro", "running a macro"), Map.entry("run_script", "running a script"), Map.entry("restore", "going back"), Map.entry("undo", "going back"),
        Map.entry("save_workflow", "saving the macro"), Map.entry("save_image", "saving the image"), Map.entry("export_results", "exporting results"), Map.entry("duplicate", "duplicating"), Map.entry("close", "closing"));
    static final Set<String> VIEW = Set.of("activate_image", "select_roi", "zoom", "highlight", "overlay", "display", "stack");
    static final Set<String> DATA = Set.of("add_to_manager", "measure", "clear_results", "threshold", "analyze_particles", "segment_cells", "filter", "convert", "duplicate", "checkpoint", "restore", "close", "undo", "track_objects", "intensity_over_time", "tumor_region", "classify_intensity", "filter_objects", "split_objects", "merge_objects");
    static final Set<String> CODE = Set.of("open_path", "run_command", "ui_action", "run_macro", "run_script", "save_workflow", "save_image", "export_results", "make_macro", "run_workflow", "capture_macro", "apply_analysis");
    /** Data/code operations that change an image's pixels, ROIs, overlay, manager or results and therefore get a checkpoint first. */
    static final Set<String> TIDY_AFTER = Set.of("open_path", "activate_image", "segment_cells", "measure", "analyze_particles", "filter_objects", "split_objects", "merge_objects", "classify_intensity", "object_stats", "track_objects", "tumor_region", "colocalize");
    static final Set<String> CHECKPOINT_FIRST = Set.of("add_to_manager", "measure", "clear_results", "threshold", "analyze_particles", "segment_cells", "filter", "convert", "run_command", "ui_action", "run_macro", "run_script", "track_objects", "tumor_region", "classify_intensity", "filter_objects", "split_objects", "merge_objects");
    private static final Object LOCK = new Object();
    private static LumaBridge current;

    private HttpServer server;
    private String token;
    private int port = DEFAULT_PORT;
    private final Map<String, Object> identity = BridgeIdentity.description();
    final Journal journal = new Journal();
    final Checkpoints checkpoints = new Checkpoints();
    final UiTree ui = new UiTree();
    final Operations ops = new Operations(journal);
    final AtomicBoolean analysisRunning = new AtomicBoolean();
    final Scripting scripting = new Scripting(journal, ui, analysisRunning);
    final Tracking tracking = new Tracking(ops, journal);
    final Analysis analysis = new Analysis(ops, journal);
    final Macros macros = new Macros(scripting, journal, analysisRunning);
    private final Map<ImagePlus, Pipeline> pipelines = new WeakHashMap<>();
    private Pipeline pipeline(ImagePlus imp) { return pipelines.computeIfAbsent(Objects.requireNonNull(imp, "Open an image first."), ignored -> new Pipeline()); }
    private volatile Map<String, Object> lastDispatch = Map.of();
    private String lastUndoCheckpoint;

    /** ImageJ1 entry: Plugins > Luma > Luma Bridge (auto-run by run-fiji.sh with --run "Luma Bridge"). */
    @Override public void run(String arg) {
        try { ensureStarted(); } catch (RuntimeException error) { IJ.error("Luma bridge", error.getMessage()); return; }
        if ("status".equals(arg) || Operations.guiVisible() && !"silent".equals(arg)) IJ.showMessage("Luma bridge", statusMessage());
    }

    static String root() {
        String property = System.getProperty("luma.root"); if (property != null && !property.isBlank()) return property;
        String env = System.getenv("LUMA_ROOT"); if (env != null && !env.isBlank()) return env;
        return rootFromJar();
    }
    /** The repository that built this jar: <root>/dist/luma-fiji-bridge.jar (symlinked) or a copy at <root>/.local/fiji/Fiji.app/plugins/luma-fiji-bridge.jar. */
    static String rootFromJar() {
        try {
            var location = LumaBridge.class.getProtectionDomain().getCodeSource().getLocation();
            var jar = Path.of(location.toURI()).toRealPath();
            var candidates = new ArrayList<Path>();
            var parent = jar.getParent();
            if (parent != null && parent.getFileName().toString().equals("dist")) candidates.add(parent.getParent());
            if (parent != null && parent.getFileName().toString().equals("plugins")) { var up = parent; for (int i = 0; i < 4 && up != null; i++) up = up.getParent(); candidates.add(up); }
            for (var root : candidates) if (root != null && Files.isRegularFile(root.resolve(".local/state/bridge-token"))) return root.toString();
        } catch (Exception ignored) { /* not launched from a repository checkout */ }
        return null;
    }
    /** Token from LUMA_BRIDGE_TOKEN, else the gateway's own file under the Luma root; null when the bridge should stay off. */
    static String configuredToken() {
        String token = System.getenv("LUMA_BRIDGE_TOKEN");
        if (token == null || token.strip().length() < 32) {
            String root = root(); token = null;
            if (root != null) { try { token = Files.readString(Path.of(root, ".local", "state", "bridge-token"), StandardCharsets.UTF_8).strip(); } catch (IOException ignored) { token = null; } }
        }
        return token == null || token.strip().length() < 32 ? null : token.strip();
    }
    static int configuredPort() { try { return Integer.parseInt(System.getProperty("luma.bridgePort", String.valueOf(DEFAULT_PORT))); } catch (NumberFormatException error) { return DEFAULT_PORT; } }

    static LumaBridge ensureStarted() {
        synchronized (LOCK) {
            if (current != null) return current;
            var bridge = new LumaBridge();
            bridge.start(configuredToken(), configuredPort());
            current = bridge;
            return bridge;
        }
    }
    static LumaBridge current() { synchronized (LOCK) { return current; } }
    static String statusMessage() {
        var bridge = current();
        if (bridge == null) return "The Luma bridge is not running: no local token (set LUMA_BRIDGE_TOKEN or -Dluma.root; run script/run-fiji.sh once).";
        return "Luma is connected locally on 127.0.0.1:" + bridge.port + " (adapter " + BridgeIdentity.VERSION + ", " + BridgeIdentity.OPERATIONS.size() + " operations). Quit Fiji to stop the bridge.";
    }

    /** Starts the authenticated local server; package-private so tests can use another port and token. */
    void start(String token, int port) {
        if (token == null) throw new IllegalStateException("Luma bridge disabled: no local token (set LUMA_BRIDGE_TOKEN or -Dluma.root; run script/run-fiji.sh once).");
        this.token = token; this.port = port;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
            server.setExecutor(Executors.newFixedThreadPool(4, r -> { var t = new Thread(r, "luma-bridge"); t.setDaemon(true); return t; }));
            server.createContext("/", this::handle);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            System.out.println("Luma bridge listening on 127.0.0.1:" + port);
        } catch (IOException error) { throw new IllegalStateException("Cannot start Luma bridge on 127.0.0.1:" + port + ": " + error.getMessage(), error); }
    }
    void stop() { if (server != null) server.stop(0); synchronized (LOCK) { if (current == this) current = null; } }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestHeaders().containsKey("Origin")) { respond(exchange, 403, Map.of("error", "Browser origins are not permitted")); return; }
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) { respond(exchange, 401, Map.of("error", "Unauthorized")); return; }
            var path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/health".equals(path)) { respond(exchange, 200, Map.of("application", "Fiji", "version", IJ.getFullVersion(), "adapterVersion", BridgeIdentity.VERSION, "adapter", identity)); return; }
            if (!"POST".equals(exchange.getRequestMethod()) || !"/execute".equals(path)) { respond(exchange, 404, Map.of("error", "Not found")); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) { respond(exchange, 413, Map.of("error", "Request too large")); return; }
            var request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!request.has("operation")) throw new IllegalArgumentException("Missing operation");
            String operation = request.get("operation").getAsString();
            JsonObject args = request.has("arguments") && request.get("arguments").isJsonObject() ? request.getAsJsonObject("arguments") : new JsonObject();
            Object result = dispatch(operation, args, request);
            respond(exchange, 200, Map.of("result", result == null ? Map.of() : result));
        } catch (Exception error) {
            var cause = error instanceof ExecutionException ? error.getCause() : error;
            // Unexpected failures (not argument/state refusals) go to .local/fiji.log with their stack so bridge bugs are diagnosable.
            if (!(cause instanceof IllegalArgumentException || cause instanceof IllegalStateException)) { System.out.println("[Luma] operation failed: " + cause); cause.printStackTrace(System.out); }
            respond(exchange, cause instanceof IllegalArgumentException ? 400 : 409, Map.of("error", Objects.toString(cause.getMessage(), cause.getClass().getSimpleName())));
        } finally { exchange.close(); }
    }
    private static void respond(HttpExchange exchange, int code, Object value) throws IOException {
        byte[] body = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
    }
    static <T> T edt(Callable<T> work) throws Exception {
        if (EventQueue.isDispatchThread()) return work.call();
        var task = new FutureTask<>(work);
        EventQueue.invokeLater(task);
        try { return task.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS); }
        catch (TimeoutException error) { task.cancel(false); throw new IllegalStateException("Operation timed out on the Fiji event thread; inspect Fiji before retrying."); }
    }

    /** Policy, then the operation: read ops run any time, everything else is refused while an analysis runs or the context changed. */
    Object dispatch(String operation, JsonObject args, JsonObject request) throws Exception {
        if (!BridgeIdentity.OPERATIONS.contains(operation)) throw new IllegalArgumentException("Unknown operation: " + operation);
        boolean mutating = DATA.contains(operation) && !operation.equals("checkpoint") || CODE.contains(operation) || operation.equals("stack") && args.has("project");
        if (mutating) {
            if (!request.has("approved") || !request.get("approved").getAsBoolean()) throw new IllegalStateException("This operation requires the recorded decision path (approved:true).");
            if (!request.has("expectedContext")) throw new IllegalStateException("An approved operation must be bound to application context (expectedContext).");
        }
        String expected = request.has("expectedContext") && !request.get("expectedContext").isJsonNull() ? request.get("expectedContext").getAsString() : null;
        if (!READ.contains(operation) && !operation.equals("ui_action") && analysisRunning.get()) throw new IllegalStateException("A command, macro or script is still running in Fiji. Wait for it or close its dialog before starting another action.");
        if (mutating) edt(() -> { announce(operation, args); return null; });
        try {
            return switch (operation) {
                case "run_command" -> {
                    var imp = edt(() -> { check(expected); return WindowManager.getCurrentImage(); }); int before = imp == null ? 0 : imp.getID();
                    var backup = edt(() -> checkpointIfPossible("Before " + Operations.string(args, "name", "command")));
                    var result = new LinkedHashMap<>(scripting.runCommand(args, imp, COMMAND_TIMEOUT_MS));
                    // A command that opened a new image (samples, importers) presents it like open_path does.
                    var opened = edt(() -> { var current = WindowManager.getCurrentImage(); if (current == null || current.getID() == before) return null; Viewer.present(current); return Map.of("image", Operations.describe(current), "highlight", Viewer.imageHighlight(current, "Opened " + current.getTitle())); });
                    if (opened != null) result.putAll(opened);
                    yield withMeta(result, backup);
                }
                case "run_macro" -> { var imp = edt(() -> { check(expected); return WindowManager.getCurrentImage(); }); var backup = edt(() -> checkpointIfPossible("Before macro")); yield withMeta(scripting.runMacro(args, imp, COMMAND_TIMEOUT_MS), backup); }
                case "run_script" -> { var imp = edt(() -> { check(expected); return WindowManager.getCurrentImage(); }); var backup = edt(() -> checkpointIfPossible("Before script")); yield withMeta(scripting.runScript(args, imp, COMMAND_TIMEOUT_MS), backup); }
                case "make_macro" -> { var imp = edt(() -> { check(expected); return WindowManager.getCurrentImage(); }); var backup = Operations.bool(args, "test", true) ? edt(() -> checkpointIfPossible("Before make macro")) : null; yield withMeta(macros.makeMacro(args, imp), backup); }
                case "run_workflow" -> { var imp = edt(() -> { check(expected); return WindowManager.getCurrentImage(); }); var backup = "folder".equals(Operations.string(args, "target", "active")) ? null : edt(() -> checkpointIfPossible("Before workflow")); yield withMeta(macros.runWorkflow(args, imp), backup); }
                case "apply_analysis" -> { var imp = edt(() -> { check(expected); return ops.active(); }); var described = edt(() -> pipeline(imp).describe()); yield withMeta(applyAnalysis(args, imp, described), null); }
                case "capture_macro" -> { var imp = edt(() -> { check(expected); return WindowManager.getCurrentImage(); }); var backup = Operations.bool(args, "test", false) ? edt(() -> checkpointIfPossible("Before capture macro")) : null; yield withMeta(macros.macroFromRecorder(args, imp), backup); }
                case "open_path" -> {
                    var path = edt(() -> { check(expected); return Operations.openablePath(Operations.string(args, "path", "")); });
                    var watched = scripting.watch("open", null, () -> ops.openOffThread(path), OPEN_TIMEOUT_MS);
                    if (watched.error() != null) throw new IllegalStateException("Could not open " + path.getFileName() + ": " + Objects.toString(watched.error().getMessage(), watched.error().getClass().getSimpleName()));
                    if (watched.errorDialog() != null) throw new IllegalStateException("Could not open " + path.getFileName() + ": " + watched.errorDialog());
                    var result = new LinkedHashMap<String, Object>(); result.put("path", path.toString()); result.put("elapsedMs", watched.elapsedMs());
                    if (watched.dialog() != null) { result.put("opened", false); result.put("dispatched", true); result.put("dialog", scripting.dialogJson(watched.dialog())); result.put("note", "The reader opened a dialog (for example Bio-Formats import options); fill it with fiji_ui_action and press OK."); }
                    else if (!watched.finished()) { result.put("opened", false); result.put("dispatched", true); result.put("running", true); result.put("note", "Still reading after " + OPEN_TIMEOUT_MS / 1000 + " s; fiji_context reports analysisRunning until the image appears."); }
                    else { var opened = (Operations.Opened) watched.value(); result.putAll(edt(() -> ops.present(opened, path))); }
                    yield withMeta(result, null);
                }
                default -> edt(() -> { check(expected); return execute(operation, args, request); });
            };
        } finally {
            if (mutating) edt(() -> { if (!analysisRunning.get()) IJ.showStatus(""); return null; });
        }
    }
    /** Visible action: the image window comes to the front and Fiji's status bar says what Luma is doing. EDT only. */
    private void announce(String operation, JsonObject args) {
        var imp = WindowManager.getCurrentImage();
        if (imp != null && imp.getWindow() != null) { try { WindowManager.setCurrentWindow(imp.getWindow()); WindowManager.toFront(imp.getWindow()); } catch (RuntimeException ignored) { /* display only */ } }
        String what = STATUS.getOrDefault(operation, operation.replace('_', ' '));
        if (operation.equals("run_command") && args.has("name")) what = "running " + args.get("name").getAsString();
        IJ.showStatus("Luma: " + what + "…");
    }
    private Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", ij.ImageJ.getStatusBarText()); result.put("analysisRunning", analysisRunning.get()); result.put("lastDispatch", lastDispatch);
        result.put("progress", null); result.put("note", "ImageJ's progress bar exposes no value; analysisRunning tells whether a bridge command, macro or script is still running.");
        return result;
    }
    private void check(String expected) {
        if (expected != null && !contextKey().equals(expected)) throw new IllegalStateException("Fiji image, selection or results changed. Review the action again.");
    }
    private Map<String, Object> withMeta(Map<String, Object> result, Object checkpoint) throws Exception {
        var out = new LinkedHashMap<>(result);
        if (checkpoint != null) out.put("checkpoint", checkpoint);
        out.put("contextKey", edt(this::contextKey));
        var imp = edt(() -> WindowManager.getCurrentImage());
        if (imp != null && !out.containsKey("highlight")) out.put("highlight", edt(() -> Viewer.imageHighlight(imp, Objects.toString(out.get("command"), "Done"))));
        return out;
    }
    /** "Apply this to the whole folder": saves the corrected analysis as a parameterised macro, then replays it on the folder. */
    private Map<String, Object> applyAnalysis(JsonObject args, ij.ImagePlus imp, Map<String, Object> described) throws Exception {
        var make = new JsonObject();
        String title = imp == null ? "image" : imp.getTitle().replaceFirst("\\.[A-Za-z0-9]+$", "");
        make.addProperty("name", Operations.string(args, "name", "analysis " + title));
        make.addProperty("code", String.valueOf(described.get("macro")));
        make.addProperty("description", String.join("; ", castList(described.get("steps"))));
        make.add("params", JSON.toJsonTree(described.get("params")));
        make.addProperty("test", false);
        var saved = macros.makeMacro(make, imp);
        var out = new LinkedHashMap<String, Object>();
        out.put("workflow", saved); out.put("steps", described.get("steps")); out.put("params", described.get("params")); out.put("notReplayed", described.get("notReplayed")); out.put("countOnSource", described.get("countOnSource"));
        String target = Operations.string(args, "target", "folder");
        if (target.equals("none")) return out;
        var run = new JsonObject(); run.addProperty("name", String.valueOf(saved.get("slug"))); run.addProperty("target", target);
        String folder = Operations.string(args, "folder", "");
        if (folder.isBlank() && target.equals("folder")) {
            var info = imp == null ? null : imp.getOriginalFileInfo();
            if (info == null || info.directory == null || info.directory.isBlank()) throw new IllegalStateException("This image has no folder on disk; say which folder to run on.");
            folder = info.directory;
        }
        if (!folder.isBlank()) run.addProperty("folder", folder);
        for (var key : List.of("pattern", "limit", "recursive", "saveResults")) if (args.has(key)) run.add(key, args.get(key));
        // Folders often hold every channel as its own file (…_channel1.tif, …_c1.tif, …_w1…): replay on the same channel only.
        var token = imp == null ? null : java.util.regex.Pattern.compile("(?i)(channel\\d+|_c\\d+|_w\\d+|_ch\\d+)").matcher(imp.getTitle());
        if (!args.has("pattern") && target.equals("folder") && token != null && token.find()) { run.addProperty("pattern", "*" + token.group(1) + "*"); out.put("pattern", "*" + token.group(1) + "*"); }
        out.put("run", macros.runWorkflow(run, imp));
        if (Operations.guiVisible()) edt(() -> { Pipeline.showBatch(imp, out); return null; });
        return out;
    }
    @SuppressWarnings("unchecked") private static List<String> castList(Object value) { return value instanceof List<?> list ? (List<String>) list : List.of(); }
    private Object checkpointIfPossible(String label) {
        var imp = WindowManager.getCurrentImage();
        if (imp == null) return Map.of("skipped", true, "reason", "No image open; nothing to checkpoint.", "scope", Checkpoints.SCOPE);
        return checkpoints.capture(imp, label, ops.managerOrNull(), Operations.results(), ops.counts(imp), pipeline(imp));
    }

    private Object execute(String operation, JsonObject args, JsonObject request) throws Exception {
        Object checkpoint = CHECKPOINT_FIRST.contains(operation) ? checkpointIfPossible("Before " + operation.replace('_', ' ')) : null;
        Map<String, Object> result = switch (operation) {
            case "context" -> context();
            case "status" -> status();
            case "images" -> Map.of("images", ops.images().stream().map(imp -> { var d = Operations.describe(imp); d.put("active", imp == WindowManager.getCurrentImage()); return d; }).toList());
            case "open_path" -> ops.openPath(args);
            case "activate_image" -> ops.activate(args);
            case "select_roi" -> ops.selectRoi(args);
            case "roi_manager" -> ops.roiManager(args);
            case "add_to_manager" -> ops.addToManager(args);
            case "measure" -> ops.measure(args);
            case "results" -> ops.resultsRows(args);
            case "clear_results" -> ops.clearResults();
            case "threshold" -> ops.threshold(args);
            case "analyze_particles" -> ops.analyzeParticles(args);
            case "segment_cells" -> ops.segmentCells(args);
            case "filter" -> ops.filter(args);
            case "convert" -> ops.convert(args);
            case "duplicate" -> ops.duplicate(args);
            case "zoom" -> ops.zoom(args);
            case "highlight" -> ops.highlight(args);
            case "overlay" -> ops.overlay(args);
            case "display" -> ops.display(args);
            case "stack" -> ops.stack(args);
            case "commands" -> Map.of("commands", scripting.commands(args, false));
            case "plugins" -> Map.of("plugins", scripting.commands(args, true));
            case "ui" -> ui.windows();
            case "ui_action" -> ui.act(Operations.string(args, "nodeId", ""), Operations.string(args, "action", ""), args.get("value"), request.has("expectedControl") && request.get("expectedControl").isJsonObject() ? request.getAsJsonObject("expectedControl") : null);
            case "recorder" -> scripting.recorder();
            case "save_workflow" -> scripting.saveWorkflow(args);
            case "save_image" -> ops.saveImage(args);
            case "export_results" -> ops.exportResults(args);
            case "checkpoint" -> { var imp = ops.active(); yield castMap(checkpoints.capture(imp, Operations.string(args, "label", "Researcher checkpoint"), ops.managerOrNull(), Operations.results(), ops.counts(imp), pipeline(imp))); }
            case "checkpoints" -> Map.of("checkpoints", checkpoints.list(WindowManager.getCurrentImage()));
            case "compare" -> compare(args);
            case "restore" -> restore(Operations.string(args, "checkpointId", ""), "Before restore");
            case "close" -> ops.close(args);
            case "snapshot" -> ops.snapshot();
            case "log" -> Operations.log(args);
            case "undo" -> undo();
            case "track_objects" -> tracking.trackObjects(args);
            case "intensity_over_time" -> tracking.intensityOverTime(args);
            case "tumor_region" -> analysis.tumorRegion(args);
            case "colocalize" -> analysis.colocalize(args);
            case "classify_intensity" -> analysis.classifyIntensity(args);
            case "object_stats" -> analysis.regionStats(args);
            case "filter_objects" -> analysis.filterObjects(args);
            case "split_objects" -> analysis.splitObjects(args);
            case "merge_objects" -> analysis.mergeObjects(args);
            case "analysis_workflow" -> pipeline(ops.active()).describe();
            case "explain_object" -> { var pipeline = pipeline(ops.active()); yield analysis.explainObject(args, pipeline.ready() ? pipeline.describe() : null); }
            case "workflows" -> macros.workflows(args);
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
        var observedImage = WindowManager.getCurrentImage();
        if (observedImage != null) pipeline(observedImage).observe(operation, args, result, observedImage);
        // Analyses that open or fill ImageJ's side windows leave the cells in view (no-op without a GUI).
        if (Operations.guiVisible() && TIDY_AFTER.contains(operation)) Pipeline.tidyAround(WindowManager.getCurrentImage());
        var out = new LinkedHashMap<>(result);
        if (checkpoint != null) out.put("checkpoint", checkpoint);
        if (!operation.equals("context")) out.put("contextKey", contextKey());
        return out;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }

    private Map<String, Object> compare(JsonObject args) {
        var checkpoint = checkpoints.require(Operations.string(args, "checkpointId", ""));
        var imp = ops.active();
        if (imp.getID() != checkpoint.imageId()) throw new IllegalStateException("Checkpoint belongs to another image (" + checkpoint.title() + ").");
        var current = ops.counts(imp); var delta = new LinkedHashMap<String, Object>();
        for (var key : current.keySet()) delta.put(key, ((Number) current.get(key)).longValue() - ((Number) checkpoint.counts().getOrDefault(key, 0)).longValue());
        var result = new LinkedHashMap<String, Object>();
        result.put("baseline", checkpoint.counts()); result.put("current", current); result.put("delta", delta);
        result.put("roiChanged", !Operations.roiSignature(checkpoint.roi()).equals(Operations.roiSignature(imp.getRoi())));
        result.put("pixels", Checkpoints.pixelDiff(checkpoint, imp));
        return result;
    }
    private Map<String, Object> restore(String id, String label) {
        var target = checkpoints.require(id);
        ImagePlus imp = null;
        for (var candidate : ops.images()) if (candidate.getID() == target.imageId()) imp = candidate;
        if (imp == null) throw new IllegalStateException("The checkpoint's image (" + target.title() + ") is no longer open.");
        var before = ops.counts(imp);
        var backup = checkpoints.capture(imp, label, ops.managerOrNull(), Operations.results(), before, pipeline(imp));
        Checkpoints.restore(target, imp, ops.managerOrNull());
        pipelines.put(imp, target.pipeline() == null ? new Pipeline() : target.pipeline().copy());
        if (imp.getWindow() != null) WindowManager.setCurrentWindow(imp.getWindow()); else WindowManager.setTempCurrentImage(imp);
        journal.record("restore", "// restored Luma checkpoint \"" + target.label() + "\"");
        var result = new LinkedHashMap<String, Object>();
        result.put("restored", target.id()); result.put("label", target.label()); result.put("imageId", imp.getID()); result.put("title", imp.getTitle());
        result.put("before", before); result.put("counts", ops.counts(imp)); result.put("restoredCounts", ops.counts(imp)); result.put("image", Operations.describe(imp)); result.put("undoCheckpoint", backup);
        result.put("scope", "this image only: its pixels, ROI, overlay, its ROI Manager entries, analysis parameters and the Results table");
        result.put("highlight", Viewer.imageHighlight(imp, "Restored: " + target.label()));
        return result;
    }
    /** "Go back": the most recent bridge checkpoint of the active image, else ImageJ's own single-step Undo. */
    private Map<String, Object> undo() {
        var imp = ops.active();
        var latest = checkpoints.latestFor(imp.getID());
        if (latest != null && !latest.id().equals(lastUndoCheckpoint)) {
            var result = restore(latest.id(), "Before undo");
            lastUndoCheckpoint = String.valueOf(((Map<?, ?>) result.get("undoCheckpoint")).get("id"));
            checkpoints.remove(latest.id());
            result.put("mode", "checkpoint");
            return result;
        }
        IJ.run(imp, "Undo", ""); journal.record("undo", "run(\"Undo\");");
        return Map.of("mode", "imagej-undo", "note", "ImageJ's Undo reverts only the last filter or paste on this image.", "image", Operations.describe(imp), "highlight", Viewer.imageHighlight(imp, "Undo"));
    }

    Map<String, Object> context() {
        var result = new LinkedHashMap<String, Object>();
        result.put("contextKey", contextKey());
        result.put("application", "Fiji"); result.put("version", IJ.getFullVersion()); result.put("adapter", identity);
        var imp = WindowManager.getCurrentImage();
        result.put("image", imp == null ? null : Operations.describe(imp));
        result.put("images", ops.images().stream().limit(50).map(i -> Map.of("id", i.getID(), "title", i.getTitle(), "active", i == imp)).toList());
        result.put("roi", imp == null ? null : Operations.roiJson(imp.getRoi()));
        var overlay = imp == null ? null : imp.getOverlay();
        result.put("overlay", overlay == null ? 0 : overlay.size());
        var table = Operations.results();
        result.put("measurements", Map.of("rows", table.size(), "columns", List.of(table.getHeadings()), "lastRow", table.size() == 0 ? Map.of() : Operations.row(table, table.size() - 1)));
        result.put("viewer", imp == null ? null : Viewer.viewerJson(imp));
        result.put("pointer", Viewer.pointer(imp));
        result.put("counts", ops.counts(imp));
        result.put("lastDispatch", lastDispatch);
        result.put("analysisRunning", analysisRunning.get());
        result.put("checkpoints", checkpoints.list(imp));
        result.put("guiVisible", Operations.guiVisible());
        return result;
    }
    String contextKey() {
        var imp = WindowManager.getCurrentImage();
        var identityText = new StringBuilder();
        if (imp == null) identityText.append("no-image");
        else identityText.append(imp.getID()).append(':').append(imp.getTitle()).append(':').append(imp.getC()).append(',').append(imp.getZ()).append(',').append(imp.getT()).append(':').append(Operations.roiSignature(imp.getRoi())).append(':').append(imp.getOverlay() == null ? 0 : imp.getOverlay().size());
        var manager = ops.managerOrNull();
        identityText.append(":results=").append(Operations.results().size()).append(":manager=").append(manager == null ? 0 : manager.getCount()).append(":images=");
        for (var image : ops.images()) identityText.append(image.getID()).append('/');
        return UiTree.digest(identityText.toString());
    }
}
