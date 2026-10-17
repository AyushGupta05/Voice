package science.luma.qupath;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.*;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.Window;
import javafx.stage.Stage;
import javafx.event.ActionEvent;
import org.fxmisc.richtext.CodeArea;
import qupath.lib.images.servers.ServerTools;
import java.lang.ref.WeakReference;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.images.ImageData;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.gui.TaskRunnerFX;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.scripting.languages.GroovyLanguage;
import qupath.lib.scripting.ScriptParameters;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.plugins.parameters.*;
import java.nio.file.Files;
import java.nio.file.Path;
import qupath.imagej.detect.cells.WatershedCellDetection;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local semantic adapter. The application, not a screenshot, is the source of truth. */
public final class LumaExtension implements QuPathExtension {
    private static final Gson JSON = GsonTools.getInstance();
    private static final int MAX_BODY = 256 * 1024;
    private QuPathGUI qupath;
    private HttpServer server;
    private String token;
    private final Map<String,Object> bridgeIdentity=BridgeIdentity.description();
    private final WeakHashMap<Node,String> nodeIds = new WeakHashMap<>();
    private final Map<String,WeakReference<Node>> uiNodes = new HashMap<>();
    private final AtomicBoolean analysisRunning = new AtomicBoolean();
    private Map<String,Object> lastDispatch = Map.of();
    private final AtomicLong revision = new AtomicLong();
    private ImageData<BufferedImage> observedImage;
    private final LinkedHashMap<String, Checkpoint> checkpoints = new LinkedHashMap<>();
    private record Checkpoint(String id, String label, ImageData<BufferedImage> image, byte[] hierarchy, Map<String, Long> counts) {}

    @Override public String getName() { return "Luma scientific companion"; }
    @Override public String getDescription() { return "Authenticated local application context and actions for the Luma MCP/voice companion."; }
    @Override public Version getQuPathVersion() { return Version.parse("0.7.0"); }
    @Override public Version getVersion() { return Version.parse(BridgeIdentity.VERSION); }

    @Override public void installExtension(QuPathGUI app) {
        qupath = app;
        token = System.getenv("LUMA_BRIDGE_TOKEN");
        if (token == null || token.length() < 32) {
            System.err.println("Luma bridge disabled: launch through script/run-qupath.sh to set a random local token.");
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 49556), 8);
            server.setExecutor(Executors.newFixedThreadPool(4, r -> { var t = new Thread(r, "luma-bridge"); t.setDaemon(true); return t; }));
            server.createContext("/", this::handle);
            server.start();
            var item = new MenuItem("Luma bridge status");
            item.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Luma is connected locally on port 49556. Close QuPath to stop the bridge.", ButtonType.OK).show());
            app.getMenu("Extensions", true).getItems().add(item);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            System.out.println("Luma bridge listening on 127.0.0.1:49556");
        } catch (IOException e) { throw new IllegalStateException("Cannot start Luma bridge: " + e.getMessage(), e); }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestHeaders().containsKey("Origin")) { respond(exchange, 403, Map.of("error", "Browser origins are not permitted")); return; }
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                respond(exchange, 401, Map.of("error", "Unauthorized")); return;
            }
            var path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/health".equals(path)) {
                respond(exchange, 200, Map.of("application", "QuPath", "version", "0.7.0", "adapterVersion", BridgeIdentity.VERSION, "adapter", bridgeIdentity)); return;
            }
            if (!"POST".equals(exchange.getRequestMethod()) || !"/execute".equals(path)) { respond(exchange, 404, Map.of("error", "Not found")); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) { respond(exchange, 413, Map.of("error", "Request too large")); return; }
            var request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String operation = request.get("operation").getAsString();
            JsonObject args = request.has("arguments") ? request.getAsJsonObject("arguments") : new JsonObject();
            var result = switch (operation) {
                case "detect" -> detect(args,request);
                case "script" -> script(args,request);
                case "inspect_import" -> inspectImport(args,request);
                case "import_annotations" -> importAnnotations(args,request);
                case "measurement_summary" -> measurementSummary(args,request);
                default -> fx(() -> execute(operation,args,request));
            };
            respond(exchange, 200, Map.of("result", result == null ? Map.of() : result));
        } catch (Exception e) {
            var cause = e instanceof ExecutionException ? e.getCause() : e;
            respond(exchange, cause instanceof IllegalArgumentException ? 400 : 409, Map.of("error", Objects.toString(cause.getMessage(), cause.getClass().getSimpleName())));
        } finally { exchange.close(); }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        if (Platform.isFxApplicationThread()) return work.call();
        var task = new FutureTask<T>(work);
        Platform.runLater(task);
        try { return task.get(120, TimeUnit.SECONDS); }
        catch (TimeoutException e) { task.cancel(false); throw new IllegalStateException("Operation timed out; inspect QuPath before retrying. An already-running operation may still complete."); }
    }
    private static void respond(HttpExchange e, int code, Object value) throws IOException {
        byte[] body = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.sendResponseHeaders(code, body.length);
        e.getResponseBody().write(body);
    }

    private ImageData<BufferedImage> image() {
        var image = qupath.getImageData();
        if (image == null) throw new IllegalStateException("Open an image in QuPath first.");
        observe(image);
        return image;
    }
    private void observe(ImageData<BufferedImage> image) {
        if (image != observedImage) {
            observedImage = image;
            revision.incrementAndGet();
            image.getHierarchy().addListener(event -> revision.incrementAndGet());
            image.addPropertyChangeListener(event -> revision.incrementAndGet());
        }
    }
    private String contextKey() {
        var project = qupath.getProject();
        String projectKey = project == null ? "no-project" : System.identityHashCode(project) + ":" + project.getURI();
        var image = qupath.getImageData();
        String identity = projectKey + ":no-image";
        if (image != null) {
            observe(image);
            String selection = String.join(",",image.getHierarchy().getSelectionModel().getSelectedObjects().stream().map(o -> o.getID().toString()).sorted().toList());
            var primary = image.getHierarchy().getSelectionModel().getSelectedObject();
            identity = projectKey + ":" + System.identityHashCode(image) + ":" + revision.get() + ":" + qupath.getViewer().getImagePlane() + ":" + (primary == null ? "" : primary.getID()) + ":" + selection;
        }
        return digest(identity);
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private Object execute(String operation, JsonObject args, JsonObject request) throws Exception {
        if (analysisRunning.get() && !Set.of("context","objects","measurements","measurement_summary","analysis_catalog","project_images","commands","ui","snapshot","checkpoints","compare","class_summary","workflow_steps").contains(operation)) throw new IllegalStateException("An analysis or script is running. Wait for it or use its native cancellation controls before starting another action.");
        if (request.has("expectedContext") && !contextKey().equals(request.get("expectedContext").getAsString())) throw new IllegalStateException("QuPath image, selection or results changed. Review the action again.");
        return switch (operation) {
            case "context" -> context();
            case "objects" -> objects(args);
            case "measurements" -> measurements(args);
            case "analysis_catalog" -> analysisCatalog();
            case "project_images" -> projectImages(args);
            case "select" -> select(args);
            case "focus" -> focus(args);
            case "highlight" -> highlight(args);
            case "class_summary" -> classSummary(args);
            case "workflow_steps" -> workflowSteps();
            case "save_workflow" -> {
                if (!request.has("approved") || !request.get("approved").getAsBoolean()) throw new IllegalStateException("Saving a workflow requires the recorded decision path.");
                yield saveWorkflow(args);
            }
            case "overlays" -> overlays(args);
            case "snapshot" -> snapshot();
            case "commands" -> commands(args);
            case "ui" -> ui();
            case "checkpoints" -> checkpointList();
            case "checkpoint" -> checkpoint(string(args, "label", "Researcher checkpoint"));
            case "compare" -> compare(args);
            case "annotate", "classify", "delete", "restore", "command", "script", "image_type", "detect", "ui_action", "open_path", "open_project_image" -> {
                if (!request.has("approved") || !request.get("approved").getAsBoolean()) throw new IllegalStateException("This operation requires explicit human approval through the companion.");
                if (!request.has("expectedContext")) throw new IllegalStateException("An approved operation must be bound to application context.");
                yield mutate(operation, args, request);
            }
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }
    private Map<String, Object> context() {
        var result = new LinkedHashMap<String, Object>();
        result.put("application", "QuPath"); result.put("version", "0.7.0");
        result.put("adapter",bridgeIdentity);
        result.put("lastDispatch",lastDispatch);
        var project = qupath.getProject();
        result.put("project",project == null ? null : Map.of("name",project.getName(),"uri",Objects.toString(project.getURI(),"")));
        var image = qupath.getImageData();
        if (image == null) { result.put("image", null); result.put("contextKey", contextKey()); return result; }
        observe(image);
        var metadata = image.getServer().getMetadata();
        var calibration = image.getServer().getPixelCalibration();
        result.put("image", Map.of("name",Objects.toString(ServerTools.getDisplayableImageName(image.getServer()),"Unnamed image"), "width", image.getServer().getWidth(), "height", image.getServer().getHeight(), "channels", metadata.getChannels().stream().map(c -> c.getName()).toList(), "zSlices", image.getServer().nZSlices(), "timepoints", image.getServer().nTimepoints(), "type", image.getImageType().name(), "pixelSizeMicrons", finite(calibration.getAveragedPixelSizeMicrons())));
        result.put("counts", counts(image.getHierarchy()));
        result.put("unsavedChanges",image.isChanged());
        result.put("analysisRunning", analysisRunning.get());
        var primary = image.getHierarchy().getSelectionModel().getSelectedObject();
        result.put("primarySelectedId", primary == null ? null : primary.getID().toString());
        var selection = image.getHierarchy().getSelectionModel().getSelectedObjects();
        result.put("selectionCount",selection.size());
        result.put("selectionTruncated",selection.size() > 100);
        result.put("selection",selection.stream().limit(100).map(this::describe).toList());
        var viewer = qupath.getViewer();
        result.put("viewer", Map.of("centerX", viewer.getCenterPixelX(), "centerY", viewer.getCenterPixelY(), "downsample", viewer.getDownsampleFactor(), "z", viewer.getImagePlane().getZ(), "t", viewer.getImagePlane().getT()));
        result.put("measurementNames", image.getHierarchy().getDetectionObjects().stream().limit(100).flatMap(o -> o.getMeasurementList().getNames().stream()).distinct().limit(100).toList());
        result.put("checkpoints", checkpointList());
        result.put("contextKey", contextKey());
        return result;
    }
    private static Object finite(double value) { return Double.isFinite(value) ? value : "unknown"; }
    private static Map<String, Long> counts(PathObjectHierarchy hierarchy) {
        var result = new TreeMap<String, Long>();
        for (var object : hierarchy.getFlattenedObjectList(null)) {
            if (object.isRootObject()) continue;
            result.merge(object.isAnnotation() ? "annotations" : object.isCell() ? "cells" : "otherDetections", 1L, Long::sum);
            if (object.getPathClass() != null) result.merge("class:" + object.getPathClass(), 1L, Long::sum);
        }
        return result;
    }
    private Map<String, Object> describe(PathObject object) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", object.getID().toString()); result.put("name", object.getName());
        result.put("kind", object.isAnnotation() ? "annotation" : object.isCell() ? "cell" : "detection");
        result.put("classification", object.getPathClass() == null ? null : object.getPathClass().toString());
        var roi = object.getROI();
        if (roi != null) result.put("roi", Map.of("x", roi.getBoundsX(), "y", roi.getBoundsY(), "width", roi.getBoundsWidth(), "height", roi.getBoundsHeight(), "areaPixels", finite(roi.getArea()), "centroidX", roi.getCentroidX(), "centroidY", roi.getCentroidY(), "z", roi.getZ(), "t", roi.getT()));
        return result;
    }
    private List<PathObject> matching(JsonObject args) {
        var hierarchy = image().getHierarchy();
        Collection<PathObject> values;
        String scope = string(args, "scope", "all");
        if (scope.equals("selection")) values = hierarchy.getSelectionModel().getSelectedObjects();
        else if (scope.equals("region")) {
            var selected = hierarchy.getSelectionModel().getSelectedObject();
            if (selected == null || selected.getROI() == null) throw new IllegalStateException("Select a region first.");
            var roi = selected.getROI();
            values = hierarchy.getFlattenedObjectList(null).stream().filter(o -> o != selected && o.getROI() != null && o.getROI().getImagePlane().equals(roi.getImagePlane()) && roi.contains(o.getROI().getCentroidX(), o.getROI().getCentroidY())).toList();
        } else values = hierarchy.getFlattenedObjectList(null);
        String kind = string(args, "kind", "all"), classification = string(args, "classification", "");
        Set<String> ids = new HashSet<>();
        if (args.has("ids")) args.getAsJsonArray("ids").forEach(id -> ids.add(id.getAsString()));
        return values.stream().filter(o -> !o.isRootObject()).filter(o -> kind.equals("all") || kind.equals("annotation") && o.isAnnotation() || kind.equals("cell") && o.isCell() || kind.equals("detection") && o.isDetection()).filter(o -> classification.isEmpty() || o.getPathClass() != null && o.getPathClass().toString().equals(classification)).filter(o -> ids.isEmpty() || ids.contains(o.getID().toString())).toList();
    }
    private Object objects(JsonObject args) {
        var matches = matching(args);
        int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE), limit = integer(args, "limit", 100, 1, 500);
        return Map.of("total", matches.size(), "offset", offset, "objects", matches.stream().skip(offset).limit(limit).map(this::describe).toList());
    }
    private Object measurements(JsonObject args) {
        var matches = matching(args);
        int limit = integer(args, "limit", 50, 1, 200);
        var rows = matches.stream().limit(limit).map(o -> {
            var values = new LinkedHashMap<String, Object>();
            o.getMeasurementList().getNames().forEach(name -> values.put(name, finite(o.getMeasurementList().get(name))));
            return Map.of("object", describe(o), "measurements", values);
        }).toList();
        return Map.of("total", matches.size(), "rows", rows, "truncated", matches.size() > limit);
    }
    private Object measurementSummary(JsonObject args,JsonObject request) throws Exception {
        var names = args.getAsJsonArray("measurements").asList().stream().map(JsonElement::getAsString).toList();
        record Observation(MeasurementSummary.Snapshot data,String imageName,String key) {}
        var observed=fx(()->{
            if(analysisRunning.get())throw new IllegalStateException("Wait for the current analysis before summarizing measurements.");
            String key=contextKey();
            if(request.has("expectedContext")&&!key.equals(request.get("expectedContext").getAsString()))throw new IllegalStateException("Application context changed. Request a fresh summary.");
            var captured=MeasurementSummary.capture(matching(args),names);
            if(!key.equals(contextKey()))throw new IllegalStateException("Objects changed while reading measurements. Request a fresh summary.");
            return new Observation(captured,Objects.toString(ServerTools.getDisplayableImageName(image().getServer()),"Unnamed image"),key);
        });
        // Only the immutable numeric snapshot leaves JavaFX; plots and arithmetic do not block the UI.
        var result=MeasurementSummary.summarize(observed.data(),integer(args,"histogramBins",10,1,50),args.has("groupByClass")&&args.get("groupByClass").getAsBoolean());
        result.put("imageName",observed.imageName());result.put("contextKey",observed.key());result.put("scope",args.deepCopy());return result;
    }
    private Object analysisCatalog() {
        var image=image(); var objects=image.getHierarchy().getFlattenedObjectList(null);
        if(objects.size()>1_000_000)throw new IllegalStateException("Catalog exceeds one million objects.");
        var names=new TreeSet<String>(); var classes=new TreeSet<String>();
        for(var object:objects) {
            names.addAll(object.getMeasurementList().getNames());
            if(object.getPathClass()!=null)classes.add(object.getPathClass().toString());
            if(names.size()>10_000||classes.size()>10_000)throw new IllegalStateException("Catalog is too large; inspect a scoped measurement query.");
        }
        var parameters=detectionParameters(image);
        var steps=image.getHistoryWorkflow().getSteps();
        var result=new LinkedHashMap<String,Object>();result.put("measurementNames",names.stream().limit(500).toList());result.put("measurementNameCount",names.size());result.put("measurementNamesTruncated",names.size()>500);result.put("classifications",classes.stream().limit(500).toList());result.put("classificationCount",classes.size());result.put("classificationsTruncated",classes.size()>500);
        result.put("cellDetection",Map.of("plugin",WatershedCellDetection.class.getName(),"parameters",parameters,"note","Current-image defaults and visible choices, not scientific recommendations or proof of applicability."));
        result.put("workflow",Map.of("total",steps.size(),"steps",steps.stream().skip(Math.max(0,steps.size()-30)).map(step->Map.of("name",Objects.toString(step.getName(),"Unnamed step"),"type",step.getClass().getSimpleName())).toList(),"truncated",steps.size()>30));
        return result;
    }
    static List<Map<String,Object>> detectionParameters(ImageData<BufferedImage> image) {
        var parameters=new ArrayList<Map<String,Object>>();
        for(var entry:new WatershedCellDetection().getDefaultParameterList(image).getParameters().entrySet()) {
            var parameter=entry.getValue(); if(parameter.isHidden()||parameter instanceof EmptyParameter)continue;
            var row=new LinkedHashMap<String,Object>();row.put("key",entry.getKey());row.put("prompt",parameter.getPrompt());row.put("help",parameter.getHelpText());row.put("default",parameter.getValueOrDefault());row.put("constructorDefault",parameter.getDefaultValue());
            row.put("type",parameter.getClass().getSimpleName());
            if(parameter instanceof NumericParameter<?> numeric){row.put("unit",numeric.getUnit());row.put("minimum",Double.isFinite(numeric.getLowerBound())?numeric.getLowerBound():null);row.put("maximum",Double.isFinite(numeric.getUpperBound())?numeric.getUpperBound():null);}
            if(parameter instanceof ChoiceParameter<?> choice)row.put("choices",choice.getChoices().stream().map(String::valueOf).toList());
            parameters.add(row);
        }
        return parameters;
    }
    private Object projectImages(JsonObject args) {
        var project=qupath.getProject();if(project==null)return Map.of("open",false,"total",0,"images",List.of());
        int offset=integer(args,"offset",0,0,Integer.MAX_VALUE),limit=integer(args,"limit",100,1,200);
        var entries=project.getImageList().stream().filter(entry->!args.has("entryId")||entry.getID().equals(string(args,"entryId",""))).toList();var current=qupath.getImageData()==null?null:project.getEntry(qupath.getImageData());
        return Map.of("open",true,"name",project.getName(),"total",entries.size(),"offset",offset,"images",entries.stream().skip(offset).limit(limit).map(entry->Map.of("id",entry.getID(),"name",Objects.toString(entry.getImageName(),"Unnamed image"),"fingerprint",entryFingerprint(entry),"active",entry==current,"hasSavedData",entry.hasImageData())).toList(),"truncated",offset+(long)limit<entries.size());
    }
    private static String entryFingerprint(ProjectImageEntry<BufferedImage> entry) { return digest(entry.getID()+":"+entry.getImageName()+":"+JSON.toJson(entry.getServerBuilder())); }
    private static Path openablePath(String value) throws IOException {
        Path path=Path.of(value);if(!path.isAbsolute()||!Files.isRegularFile(path)||!Files.isReadable(path))throw new IllegalArgumentException("Choose an existing readable absolute local file path.");
        path=path.toRealPath();if(path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".qpdata"))throw new IllegalArgumentException("Saved .qpdata requires the native File > Open workflow; this endpoint accepts images and .qpproj only.");
        return path;
    }
    private void requireSavedImage() {
        if(qupath.getImageData()!=null&&qupath.getImageData().isChanged())throw new IllegalStateException("The current image has unsaved changes. Save or close it explicitly in QuPath before opening another path.");
    }
    private Object select(JsonObject args) {
        var matches = matching(args);
        image().getHierarchy().getSelectionModel().setSelectedObjects(matches, matches.isEmpty() ? null : matches.getFirst());
        return Map.of("selected", matches.size(), "contextKey", contextKey());
    }
    private PathObject findObject(String id) {
        return image().getHierarchy().getFlattenedObjectList(null).stream().filter(o -> o.getID().toString().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Object no longer exists: " + id));
    }
    private Object focus(JsonObject args) {
        var viewer = qupath.getViewer(); image();
        double x, y;
        if (args.has("objectId")) { var roi = findObject(args.get("objectId").getAsString()).getROI(); if (roi == null) throw new IllegalArgumentException("Object has no ROI."); viewer.setZPosition(roi.getZ()); viewer.setTPosition(roi.getT()); x = roi.getCentroidX(); y = roi.getCentroidY(); }
        else if (args.has("x") && args.has("y")) { x = number(args,"x",0); y = number(args,"y",0); }
        else { var selected = viewer.getSelectedObject(); if (selected == null) throw new IllegalStateException("Select an object first, or provide image coordinates."); viewer.setZPosition(selected.getROI().getZ()); viewer.setTPosition(selected.getROI().getT()); x = selected.getROI().getCentroidX(); y = selected.getROI().getCentroidY(); }
        if (args.has("downsample")) { double d = number(args,"downsample",1); if (d < 0.05 || d > 256) throw new IllegalArgumentException("downsample must be 0.05–256"); viewer.setDownsampleFactor(d, -1, -1); }
        viewer.setCenterPixelLocation(x,y);
        return Map.of("x", x, "y", y, "downsample", viewer.getDownsampleFactor(), "pointer", pointer(x,y));
    }
    /** Display-only: screen rectangles for objects, an image region or a visible control, for the companion's highlight overlay. */
    private Object highlight(JsonObject args) {
        String label = string(args, "label", "");
        if (label.length() > 80) label = label.substring(0, 80);
        int limit = Math.max(1, Math.min(120, args.has("limit") ? args.get("limit").getAsInt() : 40));
        if (args.has("nodeId")) {
            var reference = uiNodes.get(args.get("nodeId").getAsString()); var node = reference == null ? null : reference.get();
            if (node == null || node.getScene() == null || !node.isVisible()) throw new IllegalStateException("Control is no longer visible. Inspect UI again.");
            var b = node.localToScreen(node.getBoundsInLocal());
            if (b == null) throw new IllegalStateException("Control is not on screen.");
            return Map.of("highlight", Map.of("label", label, "total", 1, "shown", 1, "truncated", false, "kind", "control",
                "targets", List.of(Map.of("x", b.getMinX(), "y", b.getMinY(), "width", b.getWidth(), "height", b.getHeight(), "shape", "rect")),
                "pointer", Map.of("screenX", b.getMinX() + b.getWidth() / 2, "screenY", b.getMinY() + b.getHeight() / 2), "coordinateSystem", "javafx-screen-top-left"));
        }
        var viewer = qupath.getViewer(); var hierarchy = image().getHierarchy();
        var view = viewer.getView(); var viewBounds = view.localToScreen(view.getBoundsInLocal());
        if (viewBounds == null) throw new IllegalStateException("The QuPath viewer is not on screen.");
        var targets = new ArrayList<Map<String, Object>>();
        int total;
        if (args.has("width") && args.has("height")) {
            double x = number(args, "x", 0), y = number(args, "y", 0), w = number(args, "width", 0), h = number(args, "height", 0);
            if (w <= 0 || h <= 0) throw new IllegalArgumentException("Region width and height must be positive.");
            total = 1; var rect = screenRect(x, y, x + w, y + h, viewBounds);
            if (rect != null) { rect.put("shape", "rect"); targets.add(rect); }
        } else {
            var plane = viewer.getImagePlane();
            var matches = matching(args).stream().filter(o -> o.getROI() != null && o.getROI().getZ() == plane.getZ() && o.getROI().getT() == plane.getT()).toList();
            total = matches.size();
            for (var object : matches) {
                if (targets.size() >= limit) break;
                var roi = object.getROI();
                var rect = screenRect(roi.getBoundsX(), roi.getBoundsY(), roi.getBoundsX() + roi.getBoundsWidth(), roi.getBoundsY() + roi.getBoundsHeight(), viewBounds);
                if (rect == null) continue;
                rect.put("shape", object.isCell() || object.isDetection() || roi.getRoiName().toLowerCase(Locale.ROOT).contains("ellipse") ? "ellipse" : "rect");
                rect.put("id", object.getID().toString());
                targets.add(rect);
            }
            if (args.has("select") && args.get("select").getAsBoolean()) hierarchy.getSelectionModel().setSelectedObjects(matches, matches.isEmpty() ? null : matches.getFirst());
        }
        Map<String, Object> pointer = Map.of();
        if (!targets.isEmpty()) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (var t : targets) { double tx = (double) t.get("x"), ty = (double) t.get("y"); minX = Math.min(minX, tx); minY = Math.min(minY, ty); maxX = Math.max(maxX, tx + (double) t.get("width")); maxY = Math.max(maxY, ty + (double) t.get("height")); }
            pointer = Map.of("screenX", (minX + maxX) / 2, "screenY", (minY + maxY) / 2);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("label", label); result.put("total", total); result.put("shown", targets.size());
        result.put("truncated", targets.size() < total); result.put("kind", "image"); result.put("targets", targets); result.put("pointer", pointer);
        result.put("viewer", Map.of("x", viewBounds.getMinX(), "y", viewBounds.getMinY(), "width", viewBounds.getWidth(), "height", viewBounds.getHeight()));
        result.put("coordinateSystem", "javafx-screen-top-left");
        result.put("note", targets.size() < total ? "Only objects visible in the current viewer are outlined; zoom or focus to see others." : "");
        return Map.of("highlight", result);
    }
    /** Image-pixel rectangle to screen rectangle, clipped to the visible viewer; null when fully off screen. Minimum 8 px so tiny cells stay visible. */
    private Map<String, Object> screenRect(double x0, double y0, double x1, double y1, javafx.geometry.Bounds view) {
        var viewer = qupath.getViewer();
        var a = viewer.imagePointToComponentPoint(new java.awt.geom.Point2D.Double(x0, y0), null, false);
        var b = viewer.imagePointToComponentPoint(new java.awt.geom.Point2D.Double(x1, y1), null, false);
        var sa = viewer.getView().localToScreen(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()));
        var sb = viewer.getView().localToScreen(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()));
        if (sa == null || sb == null) return null;
        double left = sa.getX(), top = sa.getY(), right = sb.getX(), bottom = sb.getY();
        if (right < view.getMinX() || bottom < view.getMinY() || left > view.getMaxX() || top > view.getMaxY()) return null;
        left = Math.max(left, view.getMinX()); top = Math.max(top, view.getMinY()); right = Math.min(right, view.getMaxX()); bottom = Math.min(bottom, view.getMaxY());
        double cx = (left + right) / 2, cy = (top + bottom) / 2, w = Math.max(8, right - left), h = Math.max(8, bottom - top);
        var rect = new LinkedHashMap<String, Object>();
        rect.put("x", cx - w / 2); rect.put("y", cy - h / 2); rect.put("width", w); rect.put("height", h);
        return rect;
    }
    /** Per-class counts and percentages computed here, so spoken numbers never come from the model's arithmetic. */
    private Object classSummary(JsonObject args) {
        var matches = matching(args);
        var counts = new TreeMap<String, Integer>();
        for (var object : matches) counts.merge(object.getPathClass() == null ? "Unclassified" : object.getPathClass().toString(), 1, Integer::sum);
        int total = matches.size();
        var classes = new ArrayList<Map<String, Object>>();
        for (var entry : counts.entrySet()) classes.add(Map.of("classification", entry.getKey(), "count", entry.getValue(), "percent", total == 0 ? 0.0 : Math.round(entry.getValue() * 10000.0 / total) / 100.0));
        var selected = image().getHierarchy().getSelectionModel().getSelectedObject();
        var result = new LinkedHashMap<String, Object>();
        result.put("summary", Map.of("scope", string(args, "scope", "all"), "kind", string(args, "kind", "all"), "total", total, "classes", classes,
            "region", selected == null ? null : Objects.toString(selected.getName(), "Selected region"),
            "note", "Counts of QuPath objects by current class. A class is a software label from the applied threshold or classifier, not a validated biological finding."));
        return result;
    }
    /** QuPath's own history of applied steps, and the Groovy it would generate, without writing anything. */
    private Object workflowSteps() {
        var workflow = image().getHistoryWorkflow(); var steps = workflow.getSteps();
        var script = workflow.createScript();
        return Map.of("workflow", Map.of("total", steps.size(), "truncated", steps.size() > 50,
            "steps", steps.stream().skip(Math.max(0, steps.size() - 50)).map(step -> Map.of("name", Objects.toString(step.getName(), "Unnamed step"), "type", step.getClass().getSimpleName())).toList(),
            "script", script.length() > 60_000 ? script.substring(0, 60_000) + "\n// … truncated" : script,
            "note", "Generated from QuPath's workflow history; review parameters before reuse on other images."));
    }
    private static Path workflowDirectory() throws IOException {
        String root = System.getenv("LUMA_ROOT");
        Path directory = root != null && !root.isBlank() ? Path.of(root, ".local", "workflows") : Path.of(System.getProperty("user.home"), "Luma workflows");
        return Files.createDirectories(directory);
    }
    /** Save the history as a reusable Groovy script. Only writes inside Luma's workflow directory. */
    private Object saveWorkflow(JsonObject args) throws IOException {
        var workflow = image().getHistoryWorkflow(); var steps = workflow.getSteps();
        if (steps.isEmpty()) throw new IllegalStateException("No workflow steps have been recorded for this image yet.");
        String name = string(args, "name", "Luma analysis").trim();
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) slug = "luma-analysis";
        if (slug.length() > 60) slug = slug.substring(0, 60);
        var directory = workflowDirectory();
        var path = directory.resolve(slug + ".groovy");
        for (int suffix = 2; Files.exists(path); suffix++) path = directory.resolve(slug + "-" + suffix + ".groovy");
        var header = "// " + name + "\n// Generated by Luma from QuPath's workflow history on " + java.time.ZonedDateTime.now() + "\n// Image: " + image().getServer().getMetadata().getName() + "\n// Review parameters before applying to other images.\n\n";
        Files.writeString(path, header + workflow.createScript(), StandardCharsets.UTF_8);
        return Map.of("workflow", Map.of("name", name, "path", path.toString(), "steps", steps.size(),
            "stepNames", steps.stream().limit(50).map(step -> Objects.toString(step.getName(), "Unnamed step")).toList(),
            "script", header + workflow.createScript(),
            "howToRun", "QuPath → Automate → Show script editor → File → Open, or Automate → Project scripts after copying it into the project's scripts folder."));
    }
    private Map<String, Object> pointer(double x, double y) {
        var viewer = qupath.getViewer();
        var component = viewer.imagePointToComponentPoint(new java.awt.geom.Point2D.Double(x,y), null, false);
        var screen = viewer.getView().localToScreen(component.getX(), component.getY());
        if (screen == null) return Map.of();
        return Map.of("screenX", screen.getX(), "screenY", screen.getY(), "coordinateSystem", "javafx-screen-top-left");
    }
    private Object overlays(JsonObject args) {
        image(); var options = qupath.getViewer().getOverlayOptions();
        if (args.has("detections")) options.setShowDetections(args.get("detections").getAsBoolean());
        if (args.has("annotations")) options.setShowAnnotations(args.get("annotations").getAsBoolean());
        if (args.has("fill")) options.setFillDetections(args.get("fill").getAsBoolean());
        if (args.has("opacity")) { double opacity = number(args,"opacity",1); if (opacity < 0 || opacity > 1) throw new IllegalArgumentException("opacity must be 0–1"); options.setOpacity((float)opacity); }
        if (args.has("classification")) {
            String filter = args.get("classification").getAsString();
            options.setShowObjectPredicate(filter.isEmpty() ? null : o -> o.isAnnotation() || o.getPathClass() != null && o.getPathClass().toString().equals(filter));
        }
        return Map.of("updated", true);
    }
    private Object snapshot() throws IOException {
        image(); var view = qupath.getViewer().getView();
        var params = new javafx.scene.SnapshotParameters();
        double factor = Math.min(1, 1600 / Math.max(1, view.getWidth()));
        params.setTransform(javafx.scene.transform.Transform.scale(factor, factor));
        var snapshot = view.snapshot(params, null);
        var out = new ByteArrayOutputStream();
        ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), "png", out);
        return Map.of("mimeType", "image/png", "data", Base64.getEncoder().encodeToString(out.toByteArray()), "source", "QuPath viewer only");
    }
    private Object commands(JsonObject args) {
        var result = new ArrayList<Map<String, Object>>();
        String query = string(args,"query", "").toLowerCase(Locale.ROOT);
        for (var menu : qupath.getMenuBar().getMenus()) visitMenu(menu, "", query, result);
        return result.stream().limit(300).toList();
    }
    private void visitMenu(Menu menu, String prefix, String query, List<Map<String,Object>> result) {
        String path = prefix.isEmpty() ? clean(menu.getText()) : prefix + " > " + clean(menu.getText());
        for (var item : menu.getItems()) {
            if (item instanceof Menu child) visitMenu(child, path, query, result);
            else if (!(item instanceof SeparatorMenuItem) && item.getText() != null && !item.getText().isBlank()) {
                String full = path + " > " + clean(item.getText());
                if (full.toLowerCase(Locale.ROOT).contains(query)) result.add(Map.of("path", full, "enabled", !item.isDisable(), "requiresApproval", true));
            }
        }
    }
    private static String clean(String text) { return text == null ? "" : text.replace("_", ""); }
    private MenuItem command(String target) {
        var items = new ArrayList<MenuItem>();
        for (var menu : qupath.getMenuBar().getMenus()) findMenu(menu,"",target,items);
        if (items.size() != 1) throw new IllegalArgumentException("Exact command path not found or ambiguous. Discover commands again.");
        return items.getFirst();
    }
    private void findMenu(Menu menu,String prefix,String target,List<MenuItem> found) {
        String path = prefix.isEmpty() ? clean(menu.getText()) : prefix + " > " + clean(menu.getText());
        for (var item : menu.getItems()) { if (item instanceof Menu child) findMenu(child,path,target,found); else if ((path + " > " + clean(item.getText())).equals(target)) found.add(item); }
    }
    private Object checkpoint(String label) {
        var image = image();
        if (image.getHierarchy().nObjects() > 200000) throw new IllegalStateException("Checkpoint exceeds the prototype's 200,000 object memory limit. Save a project copy first.");
        byte[] serialized = HierarchySnapshot.capture(image.getHierarchy());
        String id = UUID.randomUUID().toString();
        var checkpoint = new Checkpoint(id,label,image,serialized,counts(image.getHierarchy()));
        checkpoints.put(id, checkpoint);
        while (checkpoints.size() > 6) checkpoints.remove(checkpoints.keySet().iterator().next());
        return Map.of("id", id, "label", label, "counts", checkpoint.counts(), "scope", "object hierarchy only; not files, scripts or image settings");
    }
    private List<Map<String,Object>> checkpointList() {
        return checkpoints.values().stream().map(c -> Map.<String,Object>of("id",c.id(),"label",c.label(),"counts",c.counts(),"currentImage",c.image() == qupath.getImageData())).toList();
    }
    private Object compare(JsonObject args) {
        var checkpoint = requireCheckpoint(string(args,"checkpointId",""));
        var current = counts(image().getHierarchy()); var changes = new TreeMap<String, Long>();
        var keys = new TreeSet<>(current.keySet()); keys.addAll(checkpoint.counts().keySet());
        keys.forEach(k -> changes.put(k, current.getOrDefault(k,0L) - checkpoint.counts().getOrDefault(k,0L)));
        return Map.of("baseline",checkpoint.counts(),"current",current,"delta",changes);
    }
    private Checkpoint requireCheckpoint(String id) {
        var checkpoint = checkpoints.get(id);
        if (checkpoint == null) throw new IllegalArgumentException("Checkpoint expired or not found.");
        if (checkpoint.image() != image()) throw new IllegalStateException("Checkpoint belongs to another image.");
        return checkpoint;
    }
    private Object ui() {
        uiNodes.entrySet().removeIf(entry -> entry.getValue().get() == null);
        var windows = new ArrayList<Map<String,Object>>();
        for (var window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) continue;
            var controls = new ArrayList<Map<String,Object>>();
            visitNode(window.getScene().getRoot(), controls);
            windows.add(Map.of("title",window instanceof Stage stage ? stage.getTitle() : window.getClass().getSimpleName(), "focused",window.isFocused(),"fingerprint",controlFingerprint(controls),"controls",controls));
        }
        return Map.of("windows",windows,"note","Only JavaFX QuPath controls are exposed. Custom canvases and OS file dialogs may require researcher interaction.");
    }
    private void visitNode(Node node,List<Map<String,Object>> controls) {
        if (!node.isVisible() || controls.size() >= 500) return;
        if (node instanceof CodeArea area) {
            String id = nodeIds.computeIfAbsent(node,n -> UUID.randomUUID().toString());
            uiNodes.put(id,new WeakReference<>(node));
            controls.add(Map.of("id",id,"type","CodeArea","disabled",area.isDisabled(),"editable",area.isEditable(),"text","Source text: " + area.getLength() + " characters","sourceDigest",digest(area.getText()),"selectionStart",area.getSelection().getStart(),"selectionEnd",area.getSelection().getEnd(),"caretPosition",area.getCaretPosition()));
        }
        if (node instanceof Control control) {
            String id = nodeIds.computeIfAbsent(node,n -> UUID.randomUUID().toString());
            uiNodes.put(id,new WeakReference<>(node));
            var item = new LinkedHashMap<String,Object>();
            item.put("id",id); item.put("type",control.getClass().getSimpleName()); item.put("disabled",control.isDisabled());
            item.put("text", control instanceof PasswordField ? "[redacted]" : control instanceof Labeled labeled ? labeled.getText() : control instanceof TextInputControl input ? input.getText() : Objects.toString(control.getAccessibleText(),""));
            if (control instanceof ToggleButton toggle) item.put("selected",toggle.isSelected());
            if (control instanceof CheckBox check) item.put("selected",check.isSelected());
            if (control instanceof ListView<?> list && list.getSelectionModel() != null) item.put("selection",selectionState(list.getSelectionModel().getSelectedIndices(),list.getSelectionModel().getSelectedItems()));
            if (control instanceof TableView<?> table && table.getSelectionModel() != null) item.put("selection",selectionState(table.getSelectionModel().getSelectedIndices(),table.getSelectionModel().getSelectedItems()));
            if (control instanceof TreeView<?> tree && tree.getSelectionModel() != null) item.put("selection",selectionState(tree.getSelectionModel().getSelectedIndices(),tree.getSelectionModel().getSelectedItems()));
            if (control instanceof TreeTableView<?> tree && tree.getSelectionModel() != null) item.put("selection",selectionState(tree.getSelectionModel().getSelectedIndices(),tree.getSelectionModel().getSelectedItems()));
            if (control instanceof TabPane tabs && tabs.getSelectionModel() != null) {
                item.put("selection",Map.of("index",tabs.getSelectionModel().getSelectedIndex(),"item",Objects.toString(tabs.getSelectionModel().getSelectedItem(),"")));
                item.put("options",tabs.getTabs().stream().limit(100).map(tab->Map.of("text",Objects.toString(tab.getText(),""),"disabled",tab.isDisable())).toList());
            }
            if (control instanceof ChoiceBox<?> choice) { item.put("value",Objects.toString(choice.getValue(),""));item.put("options",choice.getItems().stream().limit(100).map(String::valueOf).toList()); }
            if (control instanceof ColorPicker color) item.put("value",Objects.toString(color.getValue(),""));
            if (control instanceof Slider slider) item.put("value",slider.getValue());
            if (control instanceof Spinner<?> spinner) item.put("value",Objects.toString(spinner.getValue(),""));
            if (control instanceof DatePicker date) item.put("value",Objects.toString(date.getValue(),""));
            if (control instanceof ComboBox<?> combo) { item.put("options",combo.getItems().stream().limit(100).map(String::valueOf).toList()); item.put("value",Objects.toString(combo.getValue(),"")); }
            var bounds = node.localToScreen(node.getBoundsInLocal());
            if (bounds != null) item.put("bounds",Map.of("x",bounds.getMinX(),"y",bounds.getMinY(),"width",bounds.getWidth(),"height",bounds.getHeight()));
            controls.add(item);
        }
        if (node instanceof Parent parent) for (var child : parent.getChildrenUnmodifiable()) visitNode(child,controls);
    }
    private Map<String,Object> selectionState(List<Integer> indices,List<?> items) {
        var identity = new StringBuilder();
        for (int index = 0; index < items.size(); index++) identity.append(indices.get(index)).append(':').append(System.identityHashCode(items.get(index))).append(':').append(Objects.toString(items.get(index),"")).append('\n');
        return Map.of("count",items.size(),"digest",digest(identity.toString()),"preview",items.stream().limit(20).map(item -> Objects.toString(item,"")).toList());
    }
    private String controlFingerprint(List<Map<String,Object>> controls) {
        var states = controls.stream().map(control -> {
            var state = new TreeMap<String,Object>(control); state.remove("bounds"); return state;
        }).toList();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJson(states).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean containsEditableSource(Node node) {
        if (node instanceof CodeArea area && area.isEditable()) return true;
        return node instanceof Parent parent && parent.getChildrenUnmodifiable().stream().anyMatch(LumaExtension::containsEditableSource);
    }
    private Node reviewedControl(JsonObject args, JsonObject expected) {
        String id=string(args,"nodeId","");
        var reference=uiNodes.get(id); var node=reference == null ? null : reference.get();
        if (node == null || node.getScene() == null || node.getScene().getWindow() == null || !node.getScene().getWindow().isShowing() || !node.isVisible()) throw new IllegalStateException("Control is no longer visible. Inspect UI again.");
        if (containsEditableSource(node.getScene().getRoot())) throw new IllegalStateException("Native source-editor actions are not exposed: QuPath binds its worker context after dispatch. Use qupath_run_script with exact reviewed source and captured project/image.");
        if (node instanceof PasswordField) throw new IllegalStateException("Enter credentials manually in QuPath; password fields are not exposed to agents.");
        if (node.isDisabled()) throw new IllegalStateException("Control is disabled. Inspect the active dialog before proceeding.");
        if (expected == null) throw new IllegalStateException("UI actions must be bound to a reviewed control.");
        var currentControls = new ArrayList<Map<String,Object>>();
        visitNode(node.getScene().getRoot(),currentControls);
        if (!controlFingerprint(currentControls).equals(string(expected,"windowFingerprint",""))) throw new IllegalStateException("Dialog values changed. Review the action again.");
        String actualText = node instanceof Labeled label ? label.getText() : node instanceof TextInputControl input ? input.getText() : Objects.toString(node.getAccessibleText(),"");
        if (!node.getClass().getSimpleName().equals(string(expected,"type","")) || !Objects.equals(actualText,string(expected,"text",""))) throw new IllegalStateException("Control text changed. Review the action again.");
        if (node instanceof ComboBox<?> combo && !Objects.toString(combo.getValue(),"").equals(string(expected,"value",""))) throw new IllegalStateException("Control value changed. Review the action again.");
        var modals = Window.getWindows().stream().filter(window -> window.isShowing() && window instanceof Stage stage && stage.getModality() != javafx.stage.Modality.NONE).toList();
        if (!modals.isEmpty() && node.getScene().getWindow() != modals.getLast()) throw new IllegalStateException("Another modal dialog is active. Inspect UI before continuing.");
        return node;
    }
    private void deferReviewed(String expectedContext, Runnable action) {
        String dispatchId = UUID.randomUUID().toString();
        lastDispatch = Map.of("id",dispatchId,"status","queued");
        Platform.runLater(() -> {
            try {
                if (analysisRunning.get() || !contextKey().equals(expectedContext)) throw new IllegalStateException("Application context changed before dispatch. Review again.");
                action.run();
                if (dispatchId.equals(lastDispatch.get("id"))) lastDispatch = Map.of("id",dispatchId,"status","returned","completionVerified",false);
            } catch (Exception error) {
                lastDispatch = Map.of("id",dispatchId,"status","failed","error",Objects.toString(error.getMessage(),error.getClass().getSimpleName()));
                System.err.println("Luma deferred action failed: " + error.getMessage());
            }
        });
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private Object uiAction(JsonObject args, JsonObject expected, String expectedContext) {
        var node = reviewedControl(args,expected);
        String action=string(args,"action","");
        switch (action) {
            case "fire" -> {
                if (!(node instanceof ButtonBase button)) throw new IllegalArgumentException("Target is not a button.");
                deferReviewed(expectedContext, () -> ((ButtonBase)reviewedControl(args,expected)).fire());
            }
            case "set_text" -> {
                if (!(node instanceof TextInputControl input)) throw new IllegalArgumentException("Target is not a text input.");
                input.setText(string(args,"value",""));
            }
            case "select" -> selectControlOption(node,string(args,"value",""));
            default -> throw new IllegalArgumentException("Unknown UI action");
        }
        return Map.of("dispatched",true,"completionVerified",false,"note","Inspect UI and scientific state after dispatch.");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void selectControlOption(Node node,String requested) {
    if(node instanceof TabPane tabs) {
        var matches=tabs.getTabs().stream().filter(tab->Objects.toString(tab.getText(),"").equals(requested)).toList();
        if(matches.size()!=1||matches.getFirst().isDisable())throw new IllegalArgumentException("Tab is absent, ambiguous or disabled. Inspect UI again.");
        tabs.getSelectionModel().select(matches.getFirst());
    } else {
        var options=node instanceof ComboBox combo?combo.getItems():node instanceof ChoiceBox choice?choice.getItems():null;
        if(options==null)throw new IllegalArgumentException("Target must be a combo box, choice box or tab pane.");
        var matches=((javafx.collections.ObservableList<?>)options).stream().filter(value->String.valueOf(value).equals(requested)).toList();
        if(matches.size()!=1)throw new IllegalArgumentException("Value is absent or ambiguous. Inspect UI options again.");
        if(node instanceof ComboBox combo)combo.setValue(matches.getFirst());else ((ChoiceBox)node).setValue(matches.getFirst());
    }
    }
    private record ImportTarget(ImageData<BufferedImage> image,String key) {}
    private Object inspectImport(JsonObject args,JsonObject request) throws Exception {
        var target=fx(()->{
            if(analysisRunning.get())throw new IllegalStateException("Wait for the current analysis before inspecting an import.");
            if(request.has("expectedContext")&&!contextKey().equals(request.get("expectedContext").getAsString()))throw new IllegalStateException("Application context changed. Inspect again.");
            return new ImportTarget(image(),contextKey());
        });
        var input=GeoJsonImport.read(string(args,"path",""),null);
        var preview=input.preview();
        return fx(()->{
            if(qupath.getImageData()!=target.image()||!contextKey().equals(target.key()))throw new IllegalStateException("Image or objects changed during import inspection. Inspect again.");
            GeoJsonImport.validateImage(input,target.image());return preview;
        });
    }
    private Object importAnnotations(JsonObject args,JsonObject request) throws Exception {
        if(!analysisRunning.compareAndSet(false,true))throw new IllegalStateException("An analysis or script is already running.");
        try{
            var target=fx(()->{
                if(!request.has("approved")||!request.get("approved").getAsBoolean()||!request.has("expectedContext"))throw new IllegalStateException("Import requires approved, context-bound parameters.");
                if(!contextKey().equals(request.get("expectedContext").getAsString()))throw new IllegalStateException("Image, selection or objects changed. Review import again.");
                if(!string(args,"coordinateSpace","").equals("image_pixels")||!string(args,"sha256","").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Inspect the file first and explicitly confirm full-resolution image pixels.");
                requireNoModal();return new ImportTarget(image(),contextKey());
            });
            // Parse and validate the exact reviewed bytes off JavaFX; never reread after approval validation.
            var input=GeoJsonImport.read(string(args,"path",""),string(args,"sha256",""));
            return fx(()->{
                if(qupath.getImageData()!=target.image()||!contextKey().equals(target.key()))throw new IllegalStateException("Image or objects changed while parsing import. Review again.");
                requireNoModal();GeoJsonImport.validateImage(input,target.image());var backup=checkpoint("Before import annotations");
                target.image().getHierarchy().addObjects(input.objects());
                return Map.of("result",Map.of("imported",input.objects().size(),"sha256",input.sha256(),"counts",counts(target.image().getHierarchy()),"sourceFileUnchanged",true),"checkpoint",backup,"contextKey",contextKey());
            });
        }finally{analysisRunning.set(false);}
    }
    private record DetectionPlan(ImageData<BufferedImage> image,List<PathObject> parents,JsonObject parameters,ApprovedCellDetection plugin,Object checkpoint) {}
    private Object detect(JsonObject args,JsonObject request) throws Exception {
        if (!analysisRunning.compareAndSet(false,true)) throw new IllegalStateException("An analysis or script is already running.");
        try {
            var plan = fx(() -> {
                if (!request.has("approved") || !request.get("approved").getAsBoolean() || !request.has("expectedContext")) throw new IllegalStateException("Cell detection requires an approved, context-bound invocation.");
                if (!contextKey().equals(request.get("expectedContext").getAsString())) throw new IllegalStateException("Image, primary selection or results changed. Review detection again.");
                var image = image();
                var parents = image.getHierarchy().getSelectionModel().getSelectedObjects().stream().filter(PathObject::isAnnotation).toList();
                if (parents.isEmpty()) throw new IllegalStateException("Select an annotation to scope cell detection.");
                var parameters = args.has("parameters") ? args.getAsJsonObject("parameters").deepCopy() : new JsonObject();
                var plugin = new ApprovedCellDetection(parents);
                plugin.validateParameters(image,parameters);
                return new DetectionPlan(image,List.copyOf(parents),parameters,plugin,checkpoint("Before detect"));
            });
            // Capture the approved parent set: a later primary/selection change cannot retarget the plugin.
            var plugin = plan.plugin();
            var runner = new TaskRunnerFX(qupath);
            boolean finished = plugin.runPlugin(runner,plan.image(),JSON.toJson(plan.parameters()));
            if (!finished || runner.isCancelled()) throw new IllegalStateException("Cell detection was cancelled or could not run. Partial results may remain; inspect QuPath and the Before detect checkpoint.");
            return fx(() -> {
                var hierarchy = plan.image().getHierarchy();
                if (!hierarchy.getFlattenedObjectList(null).containsAll(plan.parents())) throw new IllegalStateException("An approved target region was removed during detection. Inspect the saved checkpoint and results before retrying.");
                return Map.of("result",Map.of("executionFinished",true,"completionVerified",false,"counts",counts(hierarchy),"activeImageChanged",qupath.getImageData()!=plan.image(),"verification","QuPath can log per-tile failures without propagating them. Inspect segmentation and QuPath logs before accepting the analysis."),"checkpoint",plan.checkpoint(),"contextKey",contextKey());
            });
        } finally { analysisRunning.set(false); }
    }
    private record ScriptPlan(ScriptParameters parameters,ImageData<BufferedImage> image,Object checkpoint) {}
    static ScriptParameters scriptParameters(Project<BufferedImage> project,ImageData<BufferedImage> image,String source) {
        if (project == null || image == null) throw new IllegalStateException("Open a project and image before running scripts. QuPath otherwise falls back to changing GUI defaults for absent bindings.");
        return ScriptParameters.builder().setProject(project).setImageData(image)
            .setDefaultImports(QPEx.getCoreClasses()).setDefaultStaticImports(Collections.singletonList(QPEx.class))
            .setScript(source).setBatchSaveResult(false).useLogWriters().build();
    }
    private Object script(JsonObject args,JsonObject request) throws Exception {
        if (!analysisRunning.compareAndSet(false,true)) throw new IllegalStateException("An analysis or script is already running.");
        try {
            var plan = fx(() -> {
                if (!request.has("approved") || !request.get("approved").getAsBoolean() || !request.has("expectedContext")) throw new IllegalStateException("Script requires an approved, context-bound invocation.");
                if (!contextKey().equals(request.get("expectedContext").getAsString())) throw new IllegalStateException("Project, image, selection or results changed. Review the script again.");
                String source = string(args,"source","");
                if (source.isBlank() || source.length() > 32000) throw new IllegalArgumentException("Script source must contain 1–32000 characters.");
                var image = qupath.getImageData();
                var parameters = scriptParameters(qupath.getProject(),image,source);
                return new ScriptPlan(parameters,image,checkpoint("Before script"));
            });
            // Match QuPath's script-editor model: background execution with captured project/image bindings.
            Object value = GroovyLanguage.getInstance().execute(plan.parameters());
            return fx(() -> Map.of("completed",true,"value",String.valueOf(value),"checkpoint",plan.checkpoint(),"activeImageChanged",qupath.getImageData()!=plan.image(),"warning","Scripts have full local privileges; checkpoints do not reverse files or external effects. UI code must explicitly use JavaFX dispatch."));
        } finally { analysisRunning.set(false); }
    }
    private static void requireNoModal() {
        if (Window.getWindows().stream().anyMatch(window -> window.isShowing() && window instanceof Stage stage && stage.getModality() != javafx.stage.Modality.NONE)) throw new IllegalStateException("Close the active modal dialog before invoking a main-window command.");
    }
    static void fireMenu(MenuItem item) {
        // MenuItem.fire alone skips the selection behavior normally supplied by JavaFX's menu skin.
        if (item instanceof CheckMenuItem check) check.setSelected(!check.isSelected());
        else if (item instanceof RadioMenuItem radio) radio.setSelected(true);
        item.fire();
    }
    private Object mutate(String operation, JsonObject args, JsonObject request) throws Exception {
        if(operation.equals("open_project_image")) {
            requireNoModal();requireSavedImage();var project=qupath.getProject();if(project==null)throw new IllegalStateException("Open a project first.");
            var entry=project.getImageList().stream().filter(item->item.getID().equals(string(args,"entryId",""))).findFirst().orElseThrow(()->new IllegalArgumentException("Project entry not found. Refresh project_images."));
            if(!entryFingerprint(entry).equals(string(args,"entryFingerprint","")))throw new IllegalStateException("Project image source changed. Inspect and review again.");
            if(qupath.getImageData()!=null&&project.getEntry(qupath.getImageData())==entry)return Map.of("alreadyActive",true,"entryId",entry.getID());
            deferReviewed(request.get("expectedContext").getAsString(),()->{
                requireNoModal();requireSavedImage();
                if(qupath.getProject()!=project||!project.getImageList().contains(entry)||!entryFingerprint(entry).equals(string(args,"entryFingerprint","")))throw new IllegalStateException("Project entry changed. Review again.");
                if(!qupath.openImageEntry(entry))throw new IllegalStateException("Native image open did not complete or was cancelled. Inspect QuPath.");
            });
            return Map.of("dispatched",true,"completionVerified",false,"entryId",entry.getID(),"note","Inspect native dialogs and refresh context to verify the active image.");
        }
        if(operation.equals("open_path")) {
            requireNoModal();requireSavedImage();var path=openablePath(string(args,"path",""));
            deferReviewed(request.get("expectedContext").getAsString(),()->{
                requireNoModal();requireSavedImage();
                try {
                    if(!openablePath(string(args,"path","")).equals(path))throw new IllegalStateException("Resolved file path changed. Review again.");
                    if(!qupath.openImage(qupath.getViewer(),path.toString(),true,false))throw new IllegalStateException("Native open/import did not complete or was cancelled. Inspect QuPath before retrying.");
                }catch(IOException error){throw new IllegalStateException(error.getMessage(),error);}
            });
            return Map.of("dispatched",true,"completionVerified",false,"path",path.toString(),"note","Native reader/import dialogs may require interaction. Verify actual image/project and lastDispatch afterward; no file rollback is provided.");
        }
        if (operation.equals("ui_action")) {
            Object backup = qupath.getImageData() == null ? Map.of("scope","No hierarchy to checkpoint") : checkpoint("Before UI action");
            return Map.of("result",uiAction(args, request.getAsJsonObject("expectedControl"),request.get("expectedContext").getAsString()),"checkpoint",backup);
        }
        if (operation.equals("command")) {
            requireNoModal();
            var item = command(string(args,"path",""));
            if (item.isDisable()) throw new IllegalStateException("Command is currently disabled in QuPath.");
            Object backup = qupath.getImageData() == null ? Map.of("scope","No image hierarchy to checkpoint") : checkpoint("Before command");
            // Return before dispatching: modal menu dialogs must not block the HTTP response.
            deferReviewed(request.get("expectedContext").getAsString(), () -> {
                requireNoModal();
                if (command(string(args,"path","")) != item || item.isDisable()) throw new IllegalStateException("Command changed or became disabled. Review again.");
                fireMenu(item);
            });
            return Map.of("dispatched",true,"completionVerified",false,"checkpoint",backup,"note","A native dialog may need interaction. File/plugin side effects are not reversed by a hierarchy checkpoint.");
        }
        var image = image(); var hierarchy = image.getHierarchy();
        if (operation.equals("restore")) {
            var target = requireCheckpoint(string(args,"checkpointId",""));
            var restored = HierarchySnapshot.restore(target.hierarchy());
            checkpoint("Before restore");
            hierarchy.getSelectionModel().clearSelection();
            hierarchy.setHierarchy(restored);
            return Map.of("restored",target.id(),"counts",counts(hierarchy));
        }
        var backup = checkpoint("Before " + operation);
        Object result;
        switch (operation) {
            case "annotate" -> {
                double x=number(args,"x",0),y=number(args,"y",0),w=number(args,"width",0),h=number(args,"height",0);
                if (x < 0 || y < 0 || w <= 0 || h <= 0 || x+w > image.getServer().getWidth() || y+h > image.getServer().getHeight()) throw new IllegalArgumentException("Annotation must fit within the image and have positive dimensions.");
                var plane = qupath.getViewer().getImagePlane();
                var roi = string(args,"shape","rectangle").equals("ellipse") ? ROIs.createEllipseROI(x,y,w,h,plane) : ROIs.createRectangleROI(x,y,w,h,plane);
                var object = PathObjects.createAnnotationObject(roi);
                object.setName(string(args,"name","Luma region"));
                String classification = string(args,"classification","").trim();
                if (!classification.isEmpty()) object.setPathClass(PathClass.fromString(classification));
                hierarchy.addObject(object); qupath.getViewer().setSelectedObject(object);
                result = describe(object);
            }
            case "classify" -> {
                String measurement = string(args,"measurement",""); double threshold = number(args,"threshold",0);
                var matches = matching(args).stream().filter(PathObject::isDetection).toList();
                if (matches.isEmpty()) throw new IllegalStateException("No detections in scope.");
                if (matches.stream().anyMatch(o -> !Double.isFinite(o.getMeasurementList().get(measurement)))) throw new IllegalArgumentException("Every target must have a finite value for the named measurement.");
                int positive = 0;
                for (var object : matches) { boolean above = object.getMeasurementList().get(measurement) >= threshold; object.setPathClass(PathClass.fromString(above ? "Positive" : "Negative")); if (above) positive++; }
                hierarchy.fireObjectClassificationsChangedEvent(this,matches);
                result = Map.of("positive",positive,"negative",matches.size()-positive,"measurement",measurement,"threshold",threshold);
            }
            case "delete" -> {
                var matches = List.copyOf(matching(args));
                if (!string(args,"scope","").equals("selection")) throw new IllegalArgumentException("Deletion is restricted to the current selection.");
                if (matches.stream().anyMatch(PathObject::isTMACore)) throw new IllegalArgumentException("TMA cores require QuPath's grid-aware TMA commands; ordinary deletion is refused.");
                hierarchy.removeObjects(matches,true);
                hierarchy.getSelectionModel().clearSelection();
                result = Map.of("removed", matches.size());
            }
            case "image_type" -> { var type = ImageData.ImageType.valueOf(string(args,"type","")); image.setImageType(type); result = Map.of("imageType",type.name(),"warning","Image type settings are not restored by hierarchy checkpoints."); }
            default -> throw new IllegalArgumentException("Unknown mutation");
        }
        return Map.of("result",result,"checkpoint",backup,"contextKey",contextKey());
    }
    private static String string(JsonObject object,String name,String fallback) { return object.has(name) ? object.get(name).getAsString() : fallback; }
    private static double number(JsonObject object,String name,double fallback) { double value=object.has(name)?object.get(name).getAsDouble():fallback; if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite"); return value; }
    private static int integer(JsonObject object,String name,int fallback,int min,int max) { double value=number(object,name,fallback); if (value != Math.rint(value) || value < min || value > max) throw new IllegalArgumentException(name + " out of range"); return (int)value; }
}
