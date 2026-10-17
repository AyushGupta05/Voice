package sc.fidelis.penny;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.ActionKind;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.nl.ConfirmationPolicy;
import sc.fidelis.nl.NlService.Proposal;

/**
 * The bridge operations of {@code docs/FIDELIS-PROTOCOL.md} over the Fidelis {@link Engine}. Every change is a plan of
 * {@link ActionRequest}s submitted through {@link Engine#submit} (origin NL_SPOKEN or NL_TYPED, {@code transcriptRef} =
 * the gateway's task id), awaited one by one on the bridge's own plan thread (never the EDT), stopping at the first step
 * the engine does not report COMPLETED. Each plan is assessed by Fidelis's {@link ConfirmationPolicy}: explicit plans
 * (and every plan under {@code fidelis.penny.confirm=always}) are held until {@code confirm}; the rest run at once.
 *
 * <p>Read-only use of {@link Engine#imagePlus}: the current plane position, the inverted-LUT flag, the threshold levels
 * and the pixel range for {@code display auto}. Nothing is ever changed through it.
 */
final class PennyOps {
    static final List<String> OPERATIONS = List.of("context", "images", "open_path", "activate_image", "commands", "command",
            "segment_cells", "measure", "threshold", "analyze_particles", "filter", "convert", "duplicate", "select_roi", "rois",
            "results", "object_stats", "display", "stack", "zoom", "highlight", "undo", "redo", "history", "macro", "run_macro",
            "confirm", "cancel_plan");
    static final Set<String> READ = Set.of("context", "images", "commands", "rois", "results", "object_stats", "history", "macro");
    /** Data, session and code operations: need {@code approved:true} and a matching {@code expectedContext}. */
    static final Set<String> MUTATING = Set.of("open_path", "command", "segment_cells", "measure", "threshold", "analyze_particles",
            "filter", "convert", "duplicate", "undo", "redo", "run_macro", "confirm");

    static final List<String> THRESHOLD_METHODS = List.of("Default", "Huang", "Intermodes", "IsoData", "IJ_IsoData", "Li",
            "MaxEntropy", "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy", "Shanbhag", "Triangle", "Yen");

    /** Columns segment_cells asks for when it has to set measurements (it only does so when Area is not recorded). */
    static final List<String> SEGMENT_MEASUREMENTS = List.of("area", "mean", "centroid", "bounding", "shape");

    static final long HTTP_WAIT_SECONDS = 115;
    static final long STEP_WAIT_MINUTES = 30;
    static final long PENDING_TTL_MS = 15 * 60_000L;
    static final int HIGHLIGHT_LIMIT = 120;

    enum ConfirmMode { AUTO, ALWAYS }

    /** One {@code /execute} call. */
    record Call(String operation, JsonObject args, boolean spoken, String taskId, String utterance) {}

    /** A refusal or failure with its HTTP status and JSON body ({@code error} plus details). */
    static final class Failure extends RuntimeException {
        final int status;
        final Map<String, Object> body;

        Failure(int status, String message, Map<String, Object> extra) {
            super(message);
            this.status = status;
            this.body = new LinkedHashMap<>();
            body.put("error", message);
            if (extra != null) body.putAll(extra);
        }

        Failure(int status, String message) {
            this(status, message, null);
        }
    }

    /** One planned engine request. {@code role} lets the result builder find the step's result. */
    record Step(String role, String label, String menuPath, ActionRequest request, Map<String, String> provenance, String preview) {}

    interface Finisher {
        Map<String, Object> finish(Plan plan, List<ActionResult> results);
    }

    static final class Plan {
        final String id = "plan-" + UUID.randomUUID().toString().substring(0, 8);
        final Call call;
        final Origin origin;
        final String transcriptRef;
        final DocumentId doc;
        final List<Step> steps = new ArrayList<>();
        final List<String> engineReasons = new ArrayList<>();
        final long created = System.currentTimeMillis();
        Finisher finish = (p, r) -> new LinkedHashMap<>();
        boolean explicit;
        List<String> reasons = List.of();
        String contextKey;
        String codeToken;

        Plan(Call call, DocumentId doc) {
            this.call = call;
            this.origin = call.spoken() ? Origin.NL_SPOKEN : Origin.NL_TYPED;
            this.transcriptRef = call.taskId() == null || call.taskId().isBlank() ? "penny-" + id : call.taskId();
            this.doc = doc;
        }

        int index(String role) {
            for (int i = 0; i < steps.size(); i++) if (role.equals(steps.get(i).role())) return i;
            return -1;
        }

        List<Integer> indices(String role) {
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) if (role.equals(steps.get(i).role())) l.add(i);
            return l;
        }

        List<String> labels() {
            return steps.stream().map(Step::label).toList();
        }
    }

    private final Engine engine;
    private final ConfirmMode mode;
    private final ConfirmationPolicy policy;
    private final ExecutorService planRunner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fidelis-penny-plan");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Plan> pending = new AtomicReference<>();
    private final AtomicReference<Plan> running = new AtomicReference<>();

    PennyOps(Engine engine, ConfirmMode mode) {
        this.engine = engine;
        this.mode = mode;
        this.policy = new ConfirmationPolicy(engine::schema, engine::globals);
    }

    ConfirmMode mode() {
        return mode;
    }

    void shutdown() {
        planRunner.shutdownNow();
    }

    // =============================================================================================
    // dispatch
    // =============================================================================================

    Map<String, Object> execute(Call c) {
        JsonObject a = c.args();
        return switch (c.operation()) {
            case "context" -> context();
            case "images" -> images();
            case "commands" -> commands(a);
            case "rois" -> roiList(a);
            case "results" -> resultsTable(a);
            case "object_stats" -> objectStats();
            case "history" -> history(a);
            case "macro" -> macro();
            case "activate_image" -> activate(a);
            case "zoom" -> zoom(a);
            case "highlight" -> highlight(a);
            case "cancel_plan" -> cancel(PennyJson.str(a, "planId", null));
            case "confirm" -> confirm(PennyJson.str(a, "planId", null));
            case "open_path" -> run(openPath(c));
            case "command" -> run(command(c));
            case "segment_cells" -> run(segmentCells(c));
            case "measure" -> run(measure(c));
            case "threshold" -> run(threshold(c));
            case "analyze_particles" -> run(analyzeParticles(c));
            case "filter" -> run(filter(c));
            case "convert" -> run(convert(c));
            case "duplicate" -> run(duplicate(c));
            case "select_roi" -> run(selectRoi(c));
            case "display" -> run(display(c));
            case "stack" -> run(stack(c));
            case "undo" -> run(undoRedo(c, Operations.UNDO));
            case "redo" -> run(undoRedo(c, Operations.REDO));
            case "run_macro" -> run(runMacro(c));
            default -> throw new IllegalArgumentException("Unknown operation: " + c.operation());
        };
    }

    // =============================================================================================
    // plans: assessment, holding, running
    // =============================================================================================

    private Map<String, Object> run(Plan p) {
        assess(p);
        p.contextKey = contextKey();
        if (p.explicit || mode == ConfirmMode.ALWAYS) return hold(p);
        return start(p, false);
    }

    /** Fidelis's own policy on the plan as a READY {@link Proposal}; the bridge adds the engine's capability reasons. */
    private void assess(Plan p) {
        List<ActionRequest> reqs = new ArrayList<>();
        List<String> menus = new ArrayList<>();
        List<String> previews = new ArrayList<>();
        List<Map<String, String>> prov = new ArrayList<>();
        for (Step s : p.steps) {
            reqs.add(s.request());
            menus.add(s.menuPath());
            previews.add(s.preview());
            prov.add(s.provenance());
        }
        String text = p.call.utterance() == null || p.call.utterance().isBlank() ? p.call.operation() : p.call.utterance();
        Proposal proposal = new Proposal(Proposal.Status.READY, text, reqs, menus, previews, prov, List.of(), null);
        ConfirmationPolicy.Decision d = policy.assess(proposal);
        List<String> reasons = new ArrayList<>(d.reasons());
        reasons.addAll(p.engineReasons);
        p.explicit = d.explicit() || !p.engineReasons.isEmpty();
        p.reasons = List.copyOf(reasons);
    }

    private Map<String, Object> hold(Plan p) {
        Plan old = pending.getAndSet(p);
        if (old != null && old != p) ui(() -> PennyUi.current().clearPlan(old.id));
        ui(() -> PennyUi.current().showPlan(card(p), false));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "awaiting_confirmation");
        out.put("planId", p.id);
        out.put("operation", p.call.operation());
        out.put("reasons", p.reasons);
        if (p.reasons.isEmpty() && mode == ConfirmMode.ALWAYS) out.put("policy", "fidelis.penny.confirm=always: every plan waits for confirmation");
        out.put("steps", p.labels());
        out.put("macroPreview", p.steps.stream().map(Step::preview).toList());
        out.put("document", p.doc == null ? null : p.doc.value());
        out.put("contextKey", p.contextKey);
        out.put("note", "Nothing has run. Say yes (confirm) or press Confirm and run in Fidelis; cancel_plan discards it.");
        return out;
    }

    /** Runs the plan on the plan thread and waits for it (bounded); the plan keeps running if the wait times out. */
    private Map<String, Object> start(Plan p, boolean confirmed) {
        Plan old = pending.get();
        if (old != null && old != p && pending.compareAndSet(old, null)) ui(() -> PennyUi.current().clearPlan(old.id));
        if (!running.compareAndSet(null, p)) {
            Plan r = running.get();
            throw new Failure(409, "Penny is still running a plan in Fidelis" + (r == null ? "" : " (" + r.call.operation() + ", " + r.id + ")")
                    + "; wait for it to finish.", Map.of("planId", r == null ? "" : r.id));
        }
        ui(() -> PennyUi.current().showPlan(card(p), true));
        Future<Map<String, Object>> f;
        try {
            f = planRunner.submit(() -> runSteps(p, confirmed));
        } catch (RuntimeException e) {
            running.compareAndSet(p, null);
            throw new Failure(409, "The Penny plan runner is stopped.");
        }
        try {
            return f.get(HTTP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new Failure(409, "Still running in Fidelis after " + HTTP_WAIT_SECONDS + " s (a dialog may be waiting there); "
                    + "check history before retrying.", Map.of("planId", p.id, "running", true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(409, "Interrupted while waiting for Fidelis.");
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof Failure fail) throw fail;
            if (t instanceof IllegalArgumentException iae) throw iae;
            throw new Failure(409, "The plan failed: " + t, Map.of("planId", p.id));
        }
    }

    private Map<String, Object> runSteps(Plan p, boolean confirmed) {
        List<ActionResult> results = new ArrayList<>();
        try {
            for (int i = 0; i < p.steps.size(); i++) {
                Step s = p.steps.get(i);
                final int index = i;
                ui(() -> PennyUi.current().planProgress(p.id, index, s.label()));
                ActionRequest r = s.request();
                if (r.kind() == ActionKind.CODE && p.codeToken != null) r = r.withConfirmation(p.codeToken);
                ActionResult res;
                try {
                    res = engine.submit(r).get(STEP_WAIT_MINUTES, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    throw stepFailure(p, i, null, results, "no answer from the engine after " + STEP_WAIT_MINUTES + " min");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw stepFailure(p, i, null, results, "interrupted");
                } catch (ExecutionException e) {
                    throw stepFailure(p, i, null, results, String.valueOf(e.getCause()));
                }
                results.add(res);
                if (!res.succeeded()) throw stepFailure(p, i, res, results, null);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "completed");
            out.put("planId", p.id);
            out.put("operation", p.call.operation());
            if (confirmed) out.put("confirmed", true);
            out.putAll(p.finish.finish(p, results));
            List<Map<String, Object>> steps = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) steps.add(step(p.steps.get(i), results.get(i)));
            out.put("steps", steps);
            out.put("contextKey", contextKey());
            String summary = out.containsKey("count") ? out.get("count") + " found" : "done";
            ui(() -> PennyUi.current().planFinished(p.id, true, summary));
            return out;
        } catch (Failure f) {
            ui(() -> PennyUi.current().planFinished(p.id, false, String.valueOf(f.getMessage())));
            throw f;
        } catch (RuntimeException e) {
            ui(() -> PennyUi.current().planFinished(p.id, false, String.valueOf(e)));
            throw new Failure(409, "The plan stopped: " + e, Map.of("planId", p.id));
        } finally {
            running.compareAndSet(p, null);
        }
    }

    private Failure stepFailure(Plan p, int i, ActionResult res, List<ActionResult> done, String why) {
        Step s = p.steps.get(i);
        String msg = why != null ? why : res.error() != null ? res.error().message() : "ended in " + res.state();
        String text = p.steps.size() == 1 ? msg : "Step " + (i + 1) + " of " + p.steps.size() + " (" + s.label() + ") did not complete: " + msg;
        Map<String, Object> extra = new LinkedHashMap<>();
        if (res != null) {
            extra.put("code", res.error() == null ? null : String.valueOf(res.error().code()));
            extra.put("subcode", res.error() == null ? null : res.error().subcode());
            extra.put("suggestions", res.error() == null ? List.of() : res.error().suggestions());
            extra.put("state", String.valueOf(res.state()));
        }
        extra.put("planId", p.id);
        extra.put("failedStep", i + 1);
        extra.put("failedLabel", s.label());
        List<Map<String, Object>> completed = new ArrayList<>();
        for (int k = 0; k < done.size() && k < i; k++) completed.add(step(p.steps.get(k), done.get(k)));
        extra.put("completedSteps", completed);
        if (res != null && res.record() != null) extra.put("step", step(s, res));
        extra.put("note", completed.isEmpty() ? "Nothing changed by this plan." : "Earlier steps completed and stay in History (undo restores pixels).");
        return new Failure(409, text, extra);
    }

    private Map<String, Object> confirm(String planId) {
        Plan p = pending.get();
        if (p == null) throw new Failure(409, "Nothing is waiting for confirmation.");
        if (planId != null && !planId.isBlank() && !planId.equals(p.id))
            throw new Failure(409, "Plan " + planId + " is not the one waiting (waiting: " + p.id + ").", Map.of("pending", p.id));
        if (System.currentTimeMillis() - p.created > PENDING_TTL_MS) {
            if (pending.compareAndSet(p, null)) ui(() -> PennyUi.current().clearPlan(p.id));
            throw new Failure(409, "The plan waited too long and was discarded; ask again.");
        }
        if (!contextKey().equals(p.contextKey)) {
            if (pending.compareAndSet(p, null)) ui(() -> PennyUi.current().clearPlan(p.id));
            throw new Failure(409, "The image, selection, results or history changed since the plan was made; it was discarded. Ask again.");
        }
        if (!pending.compareAndSet(p, null)) throw new Failure(409, "The plan was already confirmed or cancelled.");
        return start(p, true);
    }

    /** Workspace Confirm button (via {@link PennyPlans}): the same checks as {@code confirm}, off the EDT. */
    CompletableFuture<Map<String, Object>> confirmFromUi(String planId) {
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                f.complete(confirm(planId));
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        }, "fidelis-penny-confirm");
        t.setDaemon(true);
        t.start();
        return f;
    }

    Map<String, Object> cancel(String planId) {
        Plan p = pending.get();
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null || (planId != null && !planId.isBlank() && !planId.equals(p.id)) || !pending.compareAndSet(p, null)) {
            out.put("cancelled", false);
            out.put("note", "nothing was waiting" + (planId == null ? "" : " under " + planId));
            return out;
        }
        ui(() -> PennyUi.current().clearPlan(p.id));
        out.put("cancelled", true);
        out.put("planId", p.id);
        return out;
    }

    Map<String, Object> pendingJson() {
        Plan p = pending.get();
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", p.id);
        m.put("operation", p.call.operation());
        m.put("reasons", p.reasons);
        m.put("steps", p.labels());
        return m;
    }

    private PennyUi.Plan card(Plan p) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("operation", p.call.operation());
        if (p.doc != null) {
            ctx.put("documentId", p.doc.value());
            engine.document(p.doc).ifPresent(d -> ctx.put("document", d.title()));
        }
        ctx.put("transcriptRef", p.transcriptRef);
        String utterance = p.call.utterance() == null ? "" : p.call.utterance();
        return new PennyUi.Plan(p.id, utterance, p.call.spoken(), p.explicit || mode == ConfirmMode.ALWAYS, p.reasons, p.labels(),
                p.steps.stream().map(Step::preview).toList(), ctx);
    }

    private static void ui(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.err.println("[fidelis-penny] workspace hook failed: " + e);
        }
    }

    // =============================================================================================
    // step builders
    // =============================================================================================

    private Map<String, Object> meta(Plan p, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>(params);
        m.put("transcriptRef", p.transcriptRef);
        return m;
    }

    private CommandSchema schema(String id) {
        return engine.schema(id).orElseThrow(() -> new Failure(409, "The command " + id + " is not in this Fiji runtime's catalogue."));
    }

    /** A COMMAND step: user keys, recipe keys, then catalogue defaults (see {@link Params}). */
    private Step commandStep(Plan p, String role, String id, Map<String, Object> user, Map<String, Object> recipe, TargetScope ts) {
        CommandSchema s = schema(id);
        DocumentInfo d = ts == null ? null : engine.document(ts.document()).orElse(null);
        Map<String, Object> params;
        Map<String, String> prov;
        if (!s.schemaComplete()) {
            if (!user.isEmpty())
                throw new IllegalArgumentException(s.label() + " has no catalogued parameters; call it without params (its own Fiji dialog opens in Fidelis if it has one)");
            params = new LinkedHashMap<>();
            prov = new LinkedHashMap<>();
        } else {
            Params.Filled f = Params.fill(s, user, recipe, d);
            params = f.params();
            prov = f.provenance();
        }
        String menu = s.menuPath() == null || s.menuPath().isBlank() ? s.label() : s.menuPath() + ">" + s.label();
        ActionRequest r = ActionRequest.command(p.origin, id, meta(p, params), ts);
        return new Step(role, s.label(), menu, r, prov, Params.preview(s, params));
    }

    private Step directStep(Plan p, String role, String op, String label, String menu, Map<String, Object> params,
                            Map<String, String> prov, String preview, TargetScope ts) {
        ActionRequest r = ActionRequest.direct(p.origin, op, meta(p, params), ts);
        return new Step(role, label, menu, r, prov, preview);
    }

    private static Map<String, String> prov(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (String) kv[i + 1]);
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // =============================================================================================
    // documents and reads
    // =============================================================================================

    private DocumentInfo requireDoc() {
        Optional<DocumentId> f = engine.focused();
        Optional<DocumentInfo> d = f.flatMap(engine::document);
        if (d.isEmpty()) throw new Failure(409, engine.documents().isEmpty() ? "No image is open in Fidelis; open one first."
                : "No image is focused in Fidelis; activate one first.");
        return d.get();
    }

    private ImagePlus imp(DocumentId id) {
        return id == null ? null : engine.imagePlus(id);
    }

    Map<String, Object> documentJson(DocumentInfo d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id().value());
        m.put("imageId", d.imagePlusId());
        m.put("title", d.title());
        m.put("width", d.width());
        m.put("height", d.height());
        m.put("channels", d.nChannels());
        m.put("slices", d.nSlices());
        m.put("frames", d.nFrames());
        m.put("bitDepth", d.bitDepth());
        m.put("type", d.type());
        m.put("unit", d.unit());
        m.put("pixelWidth", d.pixelWidth());
        m.put("pixelHeight", d.pixelHeight());
        m.put("path", d.sourcePath() == null ? null : d.sourcePath().toString());
        m.put("changed", d.changed());
        ImagePlus imp = imp(d.id());
        if (imp != null) m.put("position", map("channel", imp.getC(), "slice", imp.getZ(), "frame", imp.getT()));
        return m;
    }

    private Map<String, Object> roiJson(RoiGeometry g) {
        if (g == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", g.type());
        Rectangle2D b = Highlight.bounds(g);
        m.put("bounds", b == null ? null : Highlight.rect(b));
        if (g.name() != null) m.put("name", g.name());
        return m;
    }

    /**
     * SHA-256 over the focused document's id, title and plane position, its active ROI (type, bounds, vertex count), its
     * ROI-list size, the Results row count, the history size and the list of open documents.
     */
    String contextKey() {
        StringBuilder sb = new StringBuilder();
        Optional<DocumentId> f = engine.focused();
        if (f.isEmpty()) sb.append("no-document");
        else {
            DocumentId id = f.get();
            sb.append(id.value()).append(':').append(engine.document(id).map(DocumentInfo::title).orElse("?"));
            ImagePlus imp = imp(id);
            if (imp != null) sb.append(':').append(imp.getC()).append(',').append(imp.getZ()).append(',').append(imp.getT());
            Optional<RoiGeometry> roi = engine.activeRoi(id);
            if (roi.isPresent()) {
                Rectangle2D b = Highlight.bounds(roi.get());
                sb.append(":roi=").append(roi.get().type()).append(b == null ? "" : b.getX() + "," + b.getY() + "," + b.getWidth() + "," + b.getHeight())
                        .append('/').append(roi.get().xs() == null ? 0 : roi.get().xs().size());
            } else sb.append(":roi=none");
            sb.append(":rois=").append(engine.rois(id).size());
        }
        sb.append(":results=").append(engine.results().size());
        sb.append(":history=").append(engine.history().size());
        sb.append(":docs=");
        for (DocumentInfo d : engine.documents()) sb.append(d.id().value()).append('/').append(d.title()).append(';');
        return sha256(sb.toString());
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> context() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contextKey", contextKey());
        out.put("application", "Fidelis");
        out.put("version", ij.IJ.getFullVersion());
        out.put("adapter", FidelisBridge.identity());
        out.put("engine", map("state", String.valueOf(engine.state())));
        Optional<DocumentId> f = engine.focused();
        DocumentInfo d = f.flatMap(engine::document).orElse(null);
        out.put("document", d == null ? null : documentJson(d));
        List<Map<String, Object>> docs = new ArrayList<>();
        for (DocumentInfo x : engine.documents()) docs.add(map("id", x.id().value(), "title", x.title(), "focused", d != null && x.id().equals(d.id())));
        out.put("documents", docs);
        out.put("roi", d == null ? null : roiJson(engine.activeRoi(d.id()).orElse(null)));
        List<HistoryRecord> h = engine.history();
        out.put("counts", map("rois", d == null ? 0 : engine.rois(d.id()).size(), "resultsRows", engine.results().size(), "history", h.size()));
        if (h.isEmpty()) out.put("lastStep", null);
        else {
            HistoryRecord r = h.get(h.size() - 1);
            out.put("lastStep", map("actionId", r.actionId(), "label", r.label(), "origin", String.valueOf(r.origin()), "outcome", String.valueOf(r.outcome())));
        }
        out.put("globals", engine.globals());
        out.put("viewer", d == null ? null : viewer(d.id()));
        out.put("pending", pendingJson());
        Plan r = running.get();
        out.put("running", r == null ? null : map("planId", r.id, "operation", r.call.operation()));
        out.put("confirmPolicy", mode == ConfirmMode.ALWAYS ? "always" : "auto");
        return out;
    }

    private Map<String, Object> viewer(DocumentId id) {
        PennyUi ui = PennyUi.current();
        double mag;
        Rectangle canvas;
        try {
            mag = ui.magnification(id);
            canvas = ui.canvasOnScreen(id);
        } catch (RuntimeException e) {
            mag = Double.NaN;
            canvas = null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("magnification", Double.isNaN(mag) ? null : mag);
        m.put("canvasScreen", Highlight.screen(canvas));
        return m;
    }

    Map<String, Object> images() {
        Optional<DocumentId> f = engine.focused();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (DocumentInfo d : engine.documents()) {
            docs.add(map("id", d.id().value(), "imageId", d.imagePlusId(), "title", d.title(), "width", d.width(), "height", d.height(),
                    "focused", f.isPresent() && f.get().equals(d.id()), "changed", d.changed()));
        }
        return map("documents", docs);
    }

    private Map<String, Object> activate(JsonObject a) {
        String id = PennyJson.str(a, "id", null);
        String title = PennyJson.str(a, "title", null);
        Double imageId = PennyJson.num(a, "imageId");
        DocumentInfo hit = null;
        for (DocumentInfo d : engine.documents()) {
            if ((id != null && d.id().value().equals(id)) || (imageId != null && d.imagePlusId() == imageId.intValue())) hit = d;
        }
        if (hit == null && id != null) {
            try {
                int n = Integer.parseInt(id.trim());
                for (DocumentInfo d : engine.documents()) if (d.imagePlusId() == n) hit = d;
            } catch (NumberFormatException ignored) {
                // not an IJ1 id
            }
        }
        if (hit == null && title != null) {
            for (DocumentInfo d : engine.documents()) if (d.title().equals(title)) { hit = d; break; }
            if (hit == null) for (DocumentInfo d : engine.documents()) if (d.title().equalsIgnoreCase(title)) { hit = d; break; }
        }
        if (hit == null) throw new IllegalArgumentException("No open document matches " + (id != null ? "id '" + id + "'" : title != null ? "title '" + title + "'" : "(give id or title)"));
        engine.focus(hit.id());
        return map("document", documentJson(hit), "contextKey", contextKey());
    }

    private Map<String, Object> commands(JsonObject a) {
        String q = PennyJson.str(a, "query", "");
        if (q.isBlank()) throw new IllegalArgumentException("'query' is required");
        int limit = PennyJson.integer(a, "limit", 10, 1, 50);
        List<CommandSchema.Summary> hits = engine.search(q, limit);
        if (hits.isEmpty()) {
            // the engine's search wants every token; long clauses are scanned token by token instead
            String[] tokens = q.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
            List<Map.Entry<Integer, CommandSchema.Summary>> scored = new ArrayList<>();
            for (CommandSchema.Summary s : engine.catalogue()) {
                String hay = (s.label() + " " + s.menuPath()).toLowerCase(Locale.ROOT);
                int n = 0;
                for (String t : tokens) if (t.length() > 2 && hay.contains(t)) n++;
                if (n > 0) scored.add(Map.entry(n, s));
            }
            scored.sort((x, y) -> y.getKey() - x.getKey());
            hits = scored.stream().limit(limit).map(Map.Entry::getValue).toList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CommandSchema.Summary s : hits) {
            Map<String, Object> m = map("commandId", s.commandId(), "label", s.label(), "menuPath", s.menuPath());
            Optional<CommandSchema> sc = engine.schema(s.commandId());
            m.put("schemaComplete", sc.map(CommandSchema::schemaComplete).orElse(false));
            m.put("consequence", sc.map(x -> String.valueOf(x.consequence())).orElse(null));
            if (sc.isPresent() && sc.get().schemaComplete()) {
                List<Map<String, Object>> ps = new ArrayList<>();
                for (ParameterSchema p : sc.get().parameters()) {
                    ps.add(map("key", p.key(), "type", String.valueOf(p.type()), "default", p.defaultValue(), "choices", p.choices(),
                            "consequential", p.consequential()));
                }
                m.put("parameters", ps);
            }
            out.add(m);
        }
        return map("commands", out);
    }

    private Map<String, Object> roiList(JsonObject a) {
        DocumentInfo d = requireDoc();
        int limit = PennyJson.integer(a, "limit", 100, 1, 2000);
        int offset = PennyJson.integer(a, "offset", 0, 0, Integer.MAX_VALUE);
        List<RoiGeometry> rois = engine.rois(d.id());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = offset; i < rois.size() && out.size() < limit; i++) {
            Map<String, Object> m = roiJson(rois.get(i));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", rois.get(i).name());
            e.putAll(m);
            out.add(e);
        }
        return map("document", d.id().value(), "total", rois.size(), "rois", out);
    }

    private static Object finite(Double v) {
        return v == null ? null : Double.isFinite(v) ? v : "NaN";
    }

    private static Map<String, Object> rowJson(MeasurementRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("row", r.rowIndex());
        if (r.label() != null) m.put("Label", r.label());
        for (Map.Entry<String, Double> e : r.values().entrySet()) m.put(e.getKey(), finite(e.getValue()));
        return m;
    }

    private Map<String, Object> resultsTable(JsonObject a) {
        int limit = PennyJson.integer(a, "limit", 50, 1, 500);
        int offset = PennyJson.integer(a, "offset", 0, 0, Integer.MAX_VALUE);
        List<MeasurementRow> rows = engine.results();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = offset; i < rows.size() && out.size() < limit; i++) out.add(rowJson(rows.get(i)));
        return map("columns", engine.resultColumns().headings(), "rows", out, "total", rows.size(), "offset", offset);
    }

    private static Map<String, Object> stats(List<Double> values) {
        List<Double> v = values.stream().filter(x -> x != null && Double.isFinite(x)).sorted().toList();
        if (v.isEmpty()) return null;
        double sum = 0;
        for (double x : v) sum += x;
        int n = v.size();
        double median = n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
        return map("mean", sum / n, "median", median, "min", v.get(0), "max", v.get(n - 1), "count", n);
    }

    private Map<String, Object> objectStats() {
        List<MeasurementRow> rows = engine.results();
        String unit = engine.focused().flatMap(engine::document).map(DocumentInfo::unit).orElse("pixel");
        Map<String, Object> out = map("n", rows.size(), "unit", unit);
        for (String[] col : new String[][] {{"Area", "area"}, {"Mean", "mean"}, {"Circ.", "circularity"}, {"Perim.", "perimeter"},
                {"IntDen", "integratedDensity"}}) {
            List<Double> v = new ArrayList<>();
            for (MeasurementRow r : rows) if (r.values().containsKey(col[0])) v.add(r.values().get(col[0]));
            Map<String, Object> s = stats(v);
            if (s != null) out.put(col[1], s);
        }
        if (rows.isEmpty()) out.put("note", "the Results table is empty; segment or measure first");
        return out;
    }

    private Map<String, Object> history(JsonObject a) {
        int limit = PennyJson.integer(a, "limit", 20, 1, 500);
        List<HistoryRecord> h = engine.history();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = Math.max(0, h.size() - limit); i < h.size(); i++) {
            HistoryRecord r = h.get(i);
            Map<String, Object> m = map("actionId", r.actionId(), "label", r.label(), "commandId", r.commandId(), "origin",
                    String.valueOf(r.origin()), "provenance", String.valueOf(r.provenance()), "outcome", String.valueOf(r.outcome()),
                    "transcriptRef", r.transcriptRef(), "time", String.valueOf(r.time()), "macroLines", r.macroLines());
            if (r.errorSummary() != null) m.put("note", r.errorSummary());
            out.add(m);
        }
        return map("total", h.size(), "steps", out);
    }

    private Map<String, Object> macro() {
        return map("macro", engine.exportMacro(), "steps", engine.history().size());
    }

    private Map<String, Object> step(Step s, ActionResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        HistoryRecord rec = r == null ? null : r.record();
        m.put("actionId", rec == null ? null : rec.actionId());
        m.put("label", rec == null ? s.label() : rec.label());
        m.put("commandId", rec == null ? (s.request().commandId() != null ? s.request().commandId() : s.request().operation()) : rec.commandId());
        m.put("outcome", r == null ? null : String.valueOf(r.state()));
        m.put("durationMs", r == null ? null : r.durationMs());
        m.put("macroLines", rec == null ? List.of() : rec.macroLines());
        return m;
    }

    // =============================================================================================
    // view operations that never reach the engine's history
    // =============================================================================================

    private Map<String, Object> zoom(JsonObject a) {
        DocumentInfo d = requireDoc();
        String how;
        if (PennyJson.bool(a, "fit", false)) how = "fit";
        else if (PennyJson.bool(a, "selection", false)) how = "selection";
        else {
            how = PennyJson.str(a, "direction", "in").toLowerCase(Locale.ROOT);
            if (!how.equals("in") && !how.equals("out")) throw new IllegalArgumentException("direction must be in or out");
        }
        final String h = how;
        ui(() -> PennyUi.current().zoom(d.id(), h));
        Map<String, Object> out = map("zoom", how, "document", d.id().value(), "viewer", viewer(d.id()));
        out.put("highlight", Highlight.canvas(d.id(), "Zoomed " + how));
        return out;
    }

    private Map<String, Object> highlight(JsonObject a) {
        DocumentInfo d = requireDoc();
        String which = PennyJson.str(a, "rois", "all").toLowerCase(Locale.ROOT);
        int limit = PennyJson.integer(a, "limit", HIGHLIGHT_LIMIT, 1, 1000);
        List<RoiGeometry> all = engine.rois(d.id());
        List<RoiGeometry> chosen = new ArrayList<>();
        switch (which) {
            case "all" -> chosen.addAll(all);
            case "indices" -> {
                List<Object> idx = PennyJson.list(a, "indices");
                if (idx == null || idx.isEmpty()) throw new IllegalArgumentException("rois=indices needs 'indices'");
                for (Object o : idx) {
                    if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 0 || n.intValue() >= all.size())
                        throw new IllegalArgumentException("index " + o + " is outside the ROI list (size " + all.size() + ")");
                    chosen.add(all.get(n.intValue()));
                }
            }
            case "active" -> engine.activeRoi(d.id()).ifPresent(chosen::add);
            default -> throw new IllegalArgumentException("rois must be all, indices or active");
        }
        if (chosen.isEmpty()) throw new Failure(409, which.equals("active") ? "There is no active selection." : "The ROI list is empty; segment or add regions first.");
        String label = PennyJson.str(a, "label", chosen.size() == 1 ? "1 region" : chosen.size() + " regions");
        return map("highlight", Highlight.rois(d.id(), chosen, label, limit, true), "total", chosen.size());
    }

    // =============================================================================================
    // plans
    // =============================================================================================

    private Plan openPath(Call c) {
        String raw = PennyJson.str(c.args(), "path", "");
        if (raw.isBlank()) throw new IllegalArgumentException("'path' is required");
        if (raw.startsWith("~/")) raw = System.getProperty("user.home") + raw.substring(1);
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("No such file: " + path);
        Plan p = new Plan(c, null);
        Map<String, Object> params = map("path", path.toString());
        ActionRequest r = ActionRequest.session(p.origin, Operations.OPEN, meta(p, params), null);
        p.steps.add(new Step("open", "Open " + path.getFileName(), "File>Open...", r, prov("path", Params.USER),
                "open(\"" + path.toString().replace("\\", "/") + "\");"));
        p.finish = (plan, res) -> {
            ActionResult o = res.get(0);
            DocumentId id = o.digest() == null || o.digest().added().isEmpty() ? null : o.digest().added().get(0);
            DocumentInfo d = id == null ? null : engine.document(id).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("opened", d != null);
            m.put("document", d == null ? null : documentJson(d));
            m.put("highlight", Highlight.canvas(id, d == null ? "Opened" : "Opened " + d.title()));
            return m;
        };
        return p;
    }

    private Plan command(Call c) {
        String id = PennyJson.str(c.args(), "commandId", "");
        if (id.isBlank()) throw new IllegalArgumentException("'commandId' is required (a catalogue id such as ij1:Process>Filters>Gaussian Blur...)");
        CommandSchema s = engine.schema(id).orElseThrow(() -> {
            List<String> sugg = engine.search(id.substring(id.lastIndexOf('>') + 1).replace("...", ""), 3).stream().map(CommandSchema.Summary::commandId).toList();
            return new IllegalArgumentException("Unknown command " + id + (sugg.isEmpty() ? "" : "; did you mean " + sugg + "?"));
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) PennyJson.toJava(PennyJson.object(c.args(), "params"));
        Optional<DocumentInfo> d = engine.focused().flatMap(engine::document);
        if (s.preconditions().contains("image.open") && d.isEmpty()) requireDoc();
        Plan p = new Plan(c, d.map(DocumentInfo::id).orElse(null));
        TargetScope ts = p.doc == null ? null : TargetScope.of(p.doc);
        p.steps.add(commandStep(p, "command", id, user, Map.of(), ts));
        p.finish = (plan, res) -> {
            ActionResult r = res.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step(plan.steps.get(0), r));
            if (r.digest() != null) {
                m.put("resultsRowsAdded", r.digest().resultsRowsAdded());
                if (!r.digest().added().isEmpty()) engine.document(r.digest().added().get(0)).ifPresent(x -> m.put("document", documentJson(x)));
                if (!r.digest().globalsDelta().isEmpty()) m.put("globalsChanged", r.digest().globalsDelta());
            }
            DocumentId shown = m.containsKey("document") ? r.digest().added().get(0) : plan.doc;
            if (shown != null) m.put("highlight", Highlight.canvas(shown, s.label()));
            return m;
        };
        return p;
    }

    private static String method(JsonObject a, String def) {
        String m = PennyJson.str(a, "method", null);
        if (m == null) return def;
        for (String x : THRESHOLD_METHODS) if (x.equalsIgnoreCase(m.trim().replace(' ', '_')) || x.equalsIgnoreCase(m.trim().replace(" ", ""))) return x;
        throw new IllegalArgumentException("method must be one of " + THRESHOLD_METHODS);
    }

    /** Bright objects on a dark background unless the image shows an inverting LUT (ImageJ's display convention). */
    private boolean darkFor(DocumentInfo d) {
        ImagePlus imp = imp(d.id());
        return imp == null || !imp.isInvertedLut();
    }

    private Set<String> currentMeasurements() {
        String s = engine.globals().getOrDefault("measurements", "");
        return new LinkedHashSet<>(s.isBlank() ? List.of() : List.of(s.trim().split("\\s+")));
    }

    /** A Set Measurements step that records {@code current ∪ wanted}; wanted keys are USER (or DERIVED for recipes). */
    private Step setMeasurementsStep(Plan p, Set<String> wanted, boolean userAsked, DocumentInfo d) {
        CommandSchema s = schema(PennyCommands.SET_MEASUREMENTS);
        Set<String> current = currentMeasurements();
        Map<String, Object> user = new LinkedHashMap<>();
        Map<String, Object> recipe = new LinkedHashMap<>();
        for (ParameterSchema ps : s.parameters()) {
            String k = ps.key();
            if (ps.type() == sc.fidelis.engine.api.Enums.ParameterType.BOOLEAN) {
                if (wanted.contains(k)) (userAsked ? user : recipe).put(k, true);
                else recipe.put(k, current.contains(k));
            }
        }
        recipe.put("redirect", "None");
        String precision = engine.globals().getOrDefault("precision", "3");
        recipe.put("decimal", Long.parseLong(precision.trim()));
        return commandStep(p, "set_measurements", PennyCommands.SET_MEASUREMENTS, user, recipe, d == null ? null : TargetScope.of(d.id()));
    }

    private Plan segmentCells(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        boolean watershed = PennyJson.bool(a, "watershed", true);
        boolean gaussian = PennyJson.bool(a, "gaussian", true);
        Double sigma = PennyJson.num(a, "sigma");
        String method = method(a, "Otsu");
        Boolean darkArg = PennyJson.bool(a, "dark");
        boolean dark = darkArg != null ? darkArg : darkFor(d);
        Double minSize = PennyJson.num(a, "minSize");
        Double maxSize = PennyJson.num(a, "maxSize");
        Boolean exclude = PennyJson.bool(a, "exclude");
        Boolean pixel = PennyJson.bool(a, "pixel");
        if (PennyJson.has(a, "channel")) {
            int ch = PennyJson.integer(a, "channel", 1, 1, Math.max(1, d.nChannels()));
            if (d.nChannels() > 1) p.steps.add(directStep(p, "channel", Operations.SET_POSITION, "Channel " + ch, "Image>Stacks>Set Channel",
                    map("channel", ch), prov("channel", Params.USER), "Stack.setChannel(" + ch + ");", ts));
        }
        if (d.bitDepth() == 24 || "COLOR_256".equals(d.type())) p.steps.add(commandStep(p, "convert", PennyCommands.TO_8_BIT, Map.of(), Map.of(), ts));
        if (gaussian) {
            Map<String, Object> user = new LinkedHashMap<>();
            Map<String, Object> recipe = map("scaled", false, "stack", false);
            if (sigma != null) user.put("sigma", sigma);
            else recipe.put("sigma", 1.0);
            p.steps.add(commandStep(p, "gaussian", PennyCommands.GAUSSIAN, user, recipe, ts));
        }
        p.steps.add(directStep(p, "threshold", Operations.SET_AUTO_THRESHOLD, "Auto threshold " + method + (dark ? " (dark background)" : ""),
                "Image>Adjust>Threshold...", map("method", method, "dark", dark),
                prov("method", PennyJson.has(a, "method") ? Params.USER : Params.DERIVED, "dark", darkArg != null ? Params.USER : Params.DERIVED),
                "setAutoThreshold(\"" + method + (dark ? " dark" : "") + "\");", ts));
        boolean black = Boolean.parseBoolean(engine.globals().getOrDefault("blackBackground", "false"));
        Map<String, Object> mask = d.isStack()
                ? map("method", method, "background", dark ? "Dark" : "Light", "calculate", false, "only", true, "black", black, "list", false, "create", false)
                : map("method", "Default", "background", "Default", "calculate", false, "only", false, "black", black);
        p.steps.add(commandStep(p, "mask", PennyCommands.CONVERT_TO_MASK, Map.of(), mask, ts));
        p.steps.add(commandStep(p, "fill", PennyCommands.FILL_HOLES, Map.of(), Map.of(), ts));
        if (watershed) p.steps.add(commandStep(p, "watershed", PennyCommands.WATERSHED, Map.of(), Map.of(), ts));
        if (!currentMeasurements().contains("area")) p.steps.add(setMeasurementsStep(p, new LinkedHashSet<>(SEGMENT_MEASUREMENTS), false, d));
        Map<String, Object> user = new LinkedHashMap<>();
        if (minSize != null || maxSize != null) {
            if (minSize != null && maxSize != null && minSize > maxSize) throw new IllegalArgumentException("minSize must not exceed maxSize");
            user.put("size", Params.number(minSize == null ? 0 : minSize) + "-" + (maxSize == null ? "Infinity" : Params.number(maxSize)));
        }
        if (exclude != null) user.put("exclude", exclude);
        if (pixel != null) user.put("pixel", pixel);
        Map<String, Object> recipe = map("size", "0-Infinity", "pixel", false, "circularity", "0.00-1.00", "show", "Nothing", "display", true,
                "exclude", false, "clear", true, "include", false, "summarize", false, "overlay", false, "add", true, "composite", false,
                "record", false, "in_situ", false, "stack", false);
        p.steps.add(commandStep(p, "particles", PennyCommands.ANALYZE_PARTICLES, user, recipe, ts));
        p.finish = (plan, res) -> {
            Map<String, Object> m = particlesResult(plan, res, d, "cells");
            m.put("threshold", map("method", method, "dark", dark));
            m.put("watershed", watershed);
            return m;
        };
        return p;
    }

    /** Count, sizes and highlight of the Analyze Particles step: rows the engine attributed to that action. */
    private Map<String, Object> particlesResult(Plan plan, List<ActionResult> res, DocumentInfo d, String noun) {
        int k = plan.index("particles");
        HistoryRecord rec = res.get(k).record();
        String actionId = rec == null ? null : rec.actionId();
        List<Double> areas = new ArrayList<>();
        int count = 0;
        for (MeasurementRow r : engine.results()) {
            if (actionId != null && actionId.equals(r.actionId())) {
                count++;
                Double v = r.values().get("Area");
                if (v != null) areas.add(v);
            }
        }
        double total = 0;
        for (double v : areas) total += v;
        List<RoiGeometry> rois = engine.rois(d.id());
        List<RoiGeometry> found = rois.subList(Math.max(0, rois.size() - count), rois.size());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count);
        m.put("averageSize", areas.isEmpty() ? "NaN" : total / areas.size());
        m.put("totalArea", areas.isEmpty() ? "NaN" : total);
        m.put("unit", d.unit());
        m.put("rois", rois.size());
        m.put("resultsRows", engine.results().size());
        m.put("document", d.id().value());
        m.put("highlight", Highlight.rois(d.id(), found, count + " " + (count == 1 ? noun.substring(0, noun.length() - 1) : noun), HIGHLIGHT_LIMIT, true));
        return m;
    }

    private Plan analyzeParticles(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        CommandSchema s = schema(PennyCommands.ANALYZE_PARTICLES);
        Map<String, Object> user = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) asMap(PennyJson.toJava(a))).entrySet()) {
            if (s.parameter(e.getKey()).isPresent()) user.put(e.getKey(), e.getValue());
        }
        Double min = PennyJson.num(a, "minSize");
        Double max = PennyJson.num(a, "maxSize");
        if (!user.containsKey("size") && (min != null || max != null))
            user.put("size", Params.number(min == null ? 0 : min) + "-" + (max == null ? "Infinity" : Params.number(max)));
        Double minC = PennyJson.num(a, "minCircularity");
        if (!user.containsKey("circularity") && minC != null) {
            if (minC < 0 || minC > 1) throw new IllegalArgumentException("minCircularity must be in 0..1");
            user.put("circularity", String.format(Locale.ROOT, "%.2f-1.00", minC));
        }
        if (!user.containsKey("add") && PennyJson.has(a, "addToManager")) user.put("add", PennyJson.bool(a, "addToManager"));
        Map<String, Object> recipe = map("size", "0-Infinity", "pixel", false, "circularity", "0.00-1.00", "show", "Nothing", "display", true,
                "exclude", false, "clear", true, "include", false, "summarize", false, "overlay", false, "add", true, "composite", false,
                "record", false, "in_situ", false, "stack", false);
        p.steps.add(commandStep(p, "particles", PennyCommands.ANALYZE_PARTICLES, user, recipe, TargetScope.of(d.id())));
        p.finish = (plan, res) -> particlesResult(plan, res, d, "particles");
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    /** Fiji-protocol measurement names to Set Measurements keys. */
    static String measurementKey(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("-", "_");
        return switch (n) {
            case "area" -> "area";
            case "mean", "mean_gray_value", "intensity", "mean_intensity" -> "mean";
            case "min", "max", "min_max", "minmax", "minimum", "maximum" -> "min";
            case "standard", "std", "sd", "stddev", "standard_deviation" -> "standard";
            case "modal", "mode" -> "modal";
            case "centroid" -> "centroid";
            case "center", "center_of_mass", "centre_of_mass" -> "center";
            case "perimeter", "perim" -> "perimeter";
            case "bounding", "bounding_rectangle", "rect", "bbox", "bounding_box" -> "bounding";
            case "fit", "ellipse", "fit_ellipse" -> "fit";
            case "shape", "circularity", "shape_descriptors", "solidity", "roundness" -> "shape";
            case "feret", "feret's", "ferets", "feret_diameter" -> "feret's";
            case "integrated", "intden", "integrated_density", "rawintden" -> "integrated";
            case "median" -> "median";
            case "skewness", "skew" -> "skewness";
            case "kurtosis", "kurt" -> "kurtosis";
            case "area_fraction", "%area" -> "area_fraction";
            case "stack", "stack_position" -> "stack";
            default -> throw new IllegalArgumentException("unknown measurement '" + name + "' (area, mean, min, max, standard, modal, centroid, center, "
                    + "perimeter, bounding, fit, shape, feret, integrated, median, skewness, kurtosis, area_fraction, stack)");
        };
    }

    /** Results columns of a Set Measurements key. */
    static List<String> columns(String key) {
        return switch (key) {
            case "area" -> List.of("Area");
            case "mean" -> List.of("Mean");
            case "min" -> List.of("Min", "Max");
            case "standard" -> List.of("StdDev");
            case "modal" -> List.of("Mode");
            case "centroid" -> List.of("X", "Y");
            case "center" -> List.of("XM", "YM");
            case "perimeter" -> List.of("Perim.");
            case "bounding" -> List.of("BX", "BY", "Width", "Height");
            case "fit" -> List.of("Major", "Minor", "Angle");
            case "shape" -> List.of("Circ.", "AR", "Round", "Solidity");
            case "feret's" -> List.of("Feret", "FeretX", "FeretY", "FeretAngle", "MinFeret");
            case "integrated" -> List.of("IntDen", "RawIntDen");
            case "median" -> List.of("Median");
            case "skewness" -> List.of("Skew");
            case "kurtosis" -> List.of("Kurt");
            case "area_fraction" -> List.of("%Area");
            case "stack" -> List.of("Ch", "Slice", "Frame");
            default -> List.of();
        };
    }

    private Plan measure(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        String scope = PennyJson.str(a, "scope", "roi").toLowerCase(Locale.ROOT);
        if (scope.equals("manager")) scope = "rois";
        List<Object> names = PennyJson.list(a, "measurements");
        Set<String> wanted = new LinkedHashSet<>();
        if (names != null) for (Object o : names) wanted.add(measurementKey(String.valueOf(o)));
        if (!wanted.isEmpty() && !currentMeasurements().containsAll(wanted)) p.steps.add(setMeasurementsStep(p, wanted, true, d));
        Optional<RoiGeometry> active = engine.activeRoi(d.id());
        switch (scope) {
            case "roi" -> p.steps.add(commandStep(p, "measure", PennyCommands.MEASURE, Map.of(), Map.of(), ts));
            case "all" -> {
                if (active.isPresent()) p.steps.add(directStep(p, "clear", Operations.CLEAR_ROI, "Select None", "Edit>Selection>Select None",
                        Map.of(), Map.of(), "run(\"Select None\");", ts));
                p.steps.add(commandStep(p, "measure", PennyCommands.MEASURE, Map.of(), Map.of(), ts));
                if (active.isPresent()) p.steps.add(directStep(p, "restore", Operations.SET_ROI, "Restore the selection", "Edit>Selection>Restore Selection",
                        map("roi", active.get()), prov("roi", Params.DERIVED), "// restore the selection", ts));
            }
            case "rois" -> {
                List<RoiGeometry> rois = engine.rois(d.id());
                if (rois.isEmpty()) throw new Failure(409, "The ROI list is empty; segment or add regions first.");
                if (rois.size() > 1000) throw new Failure(409, "The ROI list has " + rois.size() + " entries; measure at most 1000 at once.");
                for (int i = 0; i < rois.size(); i++) {
                    Step s = commandStep(p, "measure", PennyCommands.MEASURE, Map.of(), Map.of(), ts.withRoi(i));
                    p.steps.add(new Step(s.role(), "Measure ROI " + (i + 1) + " of " + rois.size(), s.menuPath(), s.request(), s.provenance(), s.preview()));
                }
                if (active.isPresent()) p.steps.add(directStep(p, "restore", Operations.SET_ROI, "Restore the selection", "Edit>Selection>Restore Selection",
                        map("roi", active.get()), prov("roi", Params.DERIVED), "// restore the selection", ts));
                else p.steps.add(directStep(p, "restore", Operations.CLEAR_ROI, "Select None", "Edit>Selection>Select None", Map.of(), Map.of(),
                        "run(\"Select None\");", ts));
            }
            default -> throw new IllegalArgumentException("scope must be roi, all or rois");
        }
        final String sc = scope;
        p.finish = (plan, res) -> {
            Set<String> ids = new LinkedHashSet<>();
            for (int i : plan.indices("measure")) {
                HistoryRecord r = res.get(i).record();
                if (r != null) ids.add(r.actionId());
            }
            List<MeasurementRow> rows = new ArrayList<>();
            for (MeasurementRow r : engine.results()) if (ids.contains(r.actionId())) rows.add(r);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", rows.size());
            m.put("totalRows", engine.results().size());
            m.put("columns", engine.resultColumns().headings());
            m.put("lastRow", rows.isEmpty() ? Map.of() : rowJson(rows.get(rows.size() - 1)));
            List<String> cols = new ArrayList<>();
            if (!wanted.isEmpty()) for (String k : wanted) cols.addAll(columns(k));
            else if (!rows.isEmpty()) for (String h : rows.get(rows.size() - 1).values().keySet()) if (cols.size() < 16) cols.add(h);
            Map<String, Object> summary = new LinkedHashMap<>();
            for (String col : cols) {
                List<Double> v = new ArrayList<>();
                for (MeasurementRow r : rows) if (r.values().containsKey(col)) v.add(r.values().get(col));
                Map<String, Object> st = stats(v);
                if (st != null) {
                    st.remove("median");
                    summary.put(col.toLowerCase(Locale.ROOT).replace(".", ""), st);
                }
            }
            m.put("summary", summary);
            m.put("unit", d.unit());
            m.put("scope", sc);
            if (sc.equals("rois")) m.put("highlight", Highlight.rois(d.id(), engine.rois(d.id()), "Measured " + rows.size() + " regions", HIGHLIGHT_LIMIT, true));
            else if (sc.equals("roi") && active.isPresent()) m.put("highlight", Highlight.rois(d.id(), List.of(active.get()), "Measured the selection", 1, true));
            else m.put("highlight", Highlight.canvas(d.id(), "Measured the whole image"));
            return m;
        };
        return p;
    }

    private Plan threshold(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        Double lower = PennyJson.num(a, "lower");
        Double upper = PennyJson.num(a, "upper");
        String method;
        if (lower != null || upper != null) {
            if (lower == null || upper == null) throw new IllegalArgumentException("give both lower and upper (or neither, for an automatic method)");
            if (lower > upper) throw new IllegalArgumentException("lower must not exceed upper");
            method = "manual";
            p.steps.add(directStep(p, "threshold", Operations.SET_THRESHOLD, "Threshold " + Params.number(lower) + "–" + Params.number(upper),
                    "Image>Adjust>Threshold...", map("lower", lower, "upper", upper), prov("lower", Params.USER, "upper", Params.USER),
                    "setThreshold(" + Params.number(lower) + ", " + Params.number(upper) + ");", ts));
        } else {
            method = method(a, "Otsu");
            Boolean darkArg = PennyJson.bool(a, "dark");
            boolean dark = darkArg != null ? darkArg : darkFor(d);
            p.steps.add(directStep(p, "threshold", Operations.SET_AUTO_THRESHOLD, "Auto threshold " + method + (dark ? " (dark background)" : ""),
                    "Image>Adjust>Threshold...", map("method", method, "dark", dark),
                    prov("method", PennyJson.has(a, "method") ? Params.USER : Params.DERIVED, "dark", darkArg != null ? Params.USER : Params.DERIVED),
                    "setAutoThreshold(\"" + method + (dark ? " dark" : "") + "\");", ts));
        }
        p.finish = (plan, res) -> {
            ImagePlus imp = imp(d.id());
            ImageProcessor ip = imp == null ? null : imp.getProcessor();
            double lo = ip == null ? Double.NaN : ip.getMinThreshold();
            double hi = ip == null ? Double.NaN : ip.getMaxThreshold();
            boolean set = ip != null && lo != ImageProcessor.NO_THRESHOLD;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lower", set ? lo : null);
            m.put("upper", set ? hi : null);
            m.put("method", method);
            Object dk = plan.steps.get(0).request().params().get("dark");
            if (dk != null) m.put("dark", dk);
            m.put("highlight", Highlight.canvas(d.id(), "Thresholded"));
            return m;
        };
        return p;
    }

    private Plan filter(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        String name = PennyJson.str(a, "name", "").toLowerCase(Locale.ROOT).replace(' ', '_');
        Map<String, Object> user = new LinkedHashMap<>();
        Map<String, Object> recipe = new LinkedHashMap<>();
        Boolean stack = PennyJson.bool(a, "stack");
        String id;
        switch (name) {
            case "gaussian", "gaussian_blur", "blur" -> {
                id = PennyCommands.GAUSSIAN;
                Double s = PennyJson.num(a, "sigma");
                if (s == null) s = PennyJson.num(a, "radius");
                if (s != null) user.put("sigma", s);
                else recipe.put("sigma", 2.0);
                recipe.put("scaled", false);
            }
            case "median", "mean" -> {
                id = name.equals("median") ? PennyCommands.MEDIAN : PennyCommands.MEAN;
                Double r = PennyJson.num(a, "radius");
                if (r != null) user.put("radius", r);
                else recipe.put("radius", 2.0);
            }
            case "unsharp", "unsharp_mask" -> {
                id = PennyCommands.UNSHARP;
                Double r = PennyJson.num(a, "radius");
                if (r == null) r = PennyJson.num(a, "sigma");
                if (r != null) user.put("radius", r);
                else recipe.put("radius", 1.0);
                Double w = PennyJson.num(a, "mask");
                if (w == null) w = PennyJson.num(a, "weight");
                if (w != null) user.put("mask", w);
                else recipe.put("mask", 0.6);
            }
            case "subtract_background", "background" -> {
                id = PennyCommands.SUBTRACT_BACKGROUND;
                Double r = PennyJson.num(a, "rolling");
                if (r == null) r = PennyJson.num(a, "radius");
                if (r != null) user.put("rolling", r);
                else recipe.put("rolling", 50.0);
                Boolean light = PennyJson.bool(a, "light");
                if (light != null) user.put("light", light);
                else recipe.put("light", false);
                Boolean sliding = PennyJson.bool(a, "sliding");
                if (sliding != null) user.put("sliding", sliding);
                else recipe.put("sliding", false);
                recipe.put("separate", false);
                recipe.put("create", false);
                recipe.put("disable", false);
            }
            case "enhance_contrast", "contrast" -> {
                id = PennyCommands.ENHANCE_CONTRAST;
                Double s = PennyJson.num(a, "saturated");
                if (s != null) user.put("saturated", s);
                else recipe.put("saturated", 0.35);
                Boolean n = PennyJson.bool(a, "normalize");
                if (n != null) user.put("normalize", n);
                else recipe.put("normalize", false);
                Boolean e = PennyJson.bool(a, "equalize");
                if (e != null) user.put("equalize", e);
                else recipe.put("equalize", false);
                if (stack != null) user.put("process_all", stack);
                else recipe.put("process_all", false);
                recipe.put("use", false);
                stack = null;
            }
            case "invert" -> id = PennyCommands.INVERT;
            case "smooth" -> id = PennyCommands.SMOOTH;
            case "sharpen" -> id = PennyCommands.SHARPEN;
            case "find_edges", "edges" -> id = PennyCommands.FIND_EDGES;
            default -> throw new IllegalArgumentException("name must be gaussian, median, mean, unsharp, subtract_background, enhance_contrast, invert, smooth, sharpen or find_edges");
        }
        CommandSchema s = schema(id);
        if (s.parameter("stack").isPresent()) {
            if (stack != null) user.put("stack", stack);
            else recipe.put("stack", false);
        }
        p.steps.add(commandStep(p, "filter", id, user, recipe, ts));
        p.finish = (plan, res) -> map("step", step(plan.steps.get(0), res.get(0)), "highlight", Highlight.canvas(d.id(), s.label()));
        return p;
    }

    private Plan convert(Call c) {
        DocumentInfo d = requireDoc();
        String to = PennyJson.str(c.args(), "to", "").trim();
        String id = switch (to.toLowerCase(Locale.ROOT)) {
            case "8-bit", "8bit", "8" -> PennyCommands.TO_8_BIT;
            case "16-bit", "16bit", "16" -> PennyCommands.TO_16_BIT;
            case "32-bit", "32bit", "32" -> PennyCommands.TO_32_BIT;
            case "rgb", "rgb color", "rgb_color" -> PennyCommands.TO_RGB;
            default -> throw new IllegalArgumentException("to must be 8-bit, 16-bit, 32-bit or RGB");
        };
        Plan p = new Plan(c, d.id());
        p.steps.add(commandStep(p, "convert", id, Map.of(), Map.of(), TargetScope.of(d.id())));
        p.finish = (plan, res) -> map("step", step(plan.steps.get(0), res.get(0)),
                "document", engine.document(d.id()).map(this::documentJson).orElse(null));
        return p;
    }

    private Plan duplicate(Call c) {
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        Map<String, Object> user = new LinkedHashMap<>();
        String title = PennyJson.str(c.args(), "title", null);
        if (title != null && !title.isBlank()) user.put("title", title);
        Map<String, Object> recipe = map("title", d.title().replaceFirst("\\.[A-Za-z0-9]{2,4}$", "") + "-1", "ignore", false,
                "duplicate", d.isStack(), "range", "1-" + Math.max(1, d.nChannels() * d.nSlices() * d.nFrames()),
                "channels", "1-" + d.nChannels(), "slices", "1-" + d.nSlices(), "frames", "1-" + d.nFrames());
        p.steps.add(commandStep(p, "duplicate", PennyCommands.DUPLICATE, user, recipe, TargetScope.of(d.id())));
        p.finish = (plan, res) -> {
            ActionResult r = res.get(0);
            DocumentId added = r.digest() == null || r.digest().added().isEmpty() ? null : r.digest().added().get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document", added == null ? null : engine.document(added).map(this::documentJson).orElse(null));
            m.put("step", step(plan.steps.get(0), r));
            if (added != null) m.put("highlight", Highlight.canvas(added, "Duplicated"));
            return m;
        };
        return p;
    }

    private Plan selectRoi(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        String type = PennyJson.str(a, "type", "rectangle").toLowerCase(Locale.ROOT);
        String name = PennyJson.str(a, "name", null);
        if (type.equals("none")) {
            p.steps.add(directStep(p, "roi", Operations.CLEAR_ROI, "Select None", "Edit>Selection>Select None", Map.of(), Map.of(),
                    "run(\"Select None\");", ts));
        } else {
            RoiGeometry g;
            if (type.equals("rectangle") || type.equals("rect") || type.equals("oval") || type.equals("ellipse")) {
                Double x = PennyJson.num(a, "x"), y = PennyJson.num(a, "y"), w = PennyJson.num(a, "width"), h = PennyJson.num(a, "height");
                if (x == null || y == null || w == null || h == null) throw new IllegalArgumentException(type + " needs x, y, width and height (image pixels)");
                if (w <= 0 || h <= 0) throw new IllegalArgumentException("width and height must be positive");
                String t = type.startsWith("rect") ? "RECTANGLE" : "OVAL";
                g = new RoiGeometry(t, List.of(x.floatValue(), (float) (x + w)), List.of(y.floatValue(), (float) (y + h)),
                        new double[] {x, y, w, h}, name, null, null);
            } else if (type.equals("polygon")) {
                List<Object> pts = PennyJson.list(a, "points");
                if (pts == null || pts.size() < 3) throw new IllegalArgumentException("polygon needs at least three points [[x,y],…]");
                List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
                for (Object o : pts) {
                    if (!(o instanceof List<?> pt) || pt.size() != 2 || !(pt.get(0) instanceof Number px) || !(pt.get(1) instanceof Number py))
                        throw new IllegalArgumentException("each point must be [x, y]");
                    xs.add(px.floatValue());
                    ys.add(py.floatValue());
                }
                g = new RoiGeometry("POLYGON", xs, ys, null, name, null, null);
            } else throw new IllegalArgumentException("type must be rectangle, oval, polygon or none");
            Rectangle2D b = Highlight.bounds(g);
            String preview;
            if (g.type().equals("POLYGON")) {
                StringBuilder xs = new StringBuilder(), ys = new StringBuilder();
                for (int i = 0; i < g.xs().size(); i++) {
                    xs.append(i == 0 ? "" : ", ").append(Params.number(g.xs().get(i)));
                    ys.append(i == 0 ? "" : ", ").append(Params.number(g.ys().get(i)));
                }
                preview = "makeSelection(\"polygon\", newArray(" + xs + "), newArray(" + ys + "));";
            } else {
                preview = (g.type().equals("OVAL") ? "makeOval(" : "makeRectangle(") + Params.number(b.getX()) + ", " + Params.number(b.getY())
                        + ", " + Params.number(b.getWidth()) + ", " + Params.number(b.getHeight()) + ");";
            }
            p.steps.add(directStep(p, "roi", Operations.SET_ROI, "Select " + g.type().toLowerCase(Locale.ROOT)
                            + String.format(Locale.ROOT, " %.0f×%.0f at %.0f,%.0f", b.getWidth(), b.getHeight(), b.getX(), b.getY()),
                    "Edit>Selection", map("roi", g), prov("roi", Params.USER), preview, ts));
        }
        p.finish = (plan, res) -> {
            Optional<RoiGeometry> roi = engine.activeRoi(d.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roi", roiJson(roi.orElse(null)));
            if (roi.isPresent()) m.put("highlight", Highlight.rois(d.id(), List.of(roi.get()), "Selection", 1, true));
            return m;
        };
        return p;
    }

    private Plan display(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        Double min = PennyJson.num(a, "min"), max = PennyJson.num(a, "max");
        Map<String, String> pv;
        if (PennyJson.bool(a, "auto", false) || (min == null && max == null)) {
            ImagePlus imp = imp(d.id());
            if (imp == null) throw new Failure(409, "The document's image is not available.");
            ij.process.ImageStatistics st = imp.getRawStatistics();
            if (min == null) min = st.min;
            if (max == null) max = st.max;
            pv = prov("min", PennyJson.has(a, "min") ? Params.USER : Params.DERIVED, "max", PennyJson.has(a, "max") ? Params.USER : Params.DERIVED);
        } else {
            if (min == null || max == null) throw new IllegalArgumentException("give min and max, or auto:true");
            pv = prov("min", Params.USER, "max", Params.USER);
        }
        if (!(min < max)) throw new IllegalArgumentException("min must be less than max (the image is flat at " + Params.number(min) + ")");
        Map<String, Object> params = map("min", min, "max", max);
        if (PennyJson.has(a, "channel")) params.put("channel", PennyJson.integer(a, "channel", 1, 1, Math.max(1, d.nChannels())));
        p.steps.add(directStep(p, "display", Operations.SET_DISPLAY_RANGE, "Display range " + Params.number(min) + "–" + Params.number(max),
                "Image>Adjust>Brightness/Contrast...", params, pv, "setMinAndMax(" + Params.number(min) + ", " + Params.number(max) + ");",
                TargetScope.of(d.id())));
        final double lo = min, hi = max;
        p.finish = (plan, res) -> map("min", lo, "max", hi, "step", step(plan.steps.get(0), res.get(0)));
        return p;
    }

    private Plan stack(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, String> pv = new LinkedHashMap<>();
        if (PennyJson.has(a, "channel")) params.put("channel", PennyJson.integer(a, "channel", 1, 1, Math.max(1, d.nChannels())));
        if (PennyJson.has(a, "slice")) params.put("slice", PennyJson.integer(a, "slice", 1, 1, Math.max(1, d.nSlices())));
        if (PennyJson.has(a, "frame")) params.put("frame", PennyJson.integer(a, "frame", 1, 1, Math.max(1, d.nFrames())));
        if (params.isEmpty()) throw new IllegalArgumentException("give slice, channel or frame");
        for (String k : params.keySet()) pv.put(k, Params.USER);
        ImagePlus now = imp(d.id());
        int pc = (int) params.getOrDefault("channel", now == null ? 1 : now.getC());
        int pz = (int) params.getOrDefault("slice", now == null ? 1 : now.getZ());
        int pt = (int) params.getOrDefault("frame", now == null ? 1 : now.getT());
        p.steps.add(directStep(p, "position", Operations.SET_POSITION, "Go to channel " + pc + ", slice " + pz + ", frame " + pt, "Image>Stacks",
                params, pv, "Stack.setPosition(" + pc + ", " + pz + ", " + pt + ");", TargetScope.of(d.id())));
        p.finish = (plan, res) -> {
            ImagePlus imp = imp(d.id());
            return map("position", imp == null ? null : map("channel", imp.getC(), "slice", imp.getZ(), "frame", imp.getT()),
                    "step", step(plan.steps.get(0), res.get(0)));
        };
        return p;
    }

    private Plan undoRedo(Call c, String op) {
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        boolean undo = op.equals(Operations.UNDO);
        p.steps.add(directStep(p, undo ? "undo" : "redo", op, undo ? "Undo" : "Redo", undo ? "Edit>Undo" : "Edit>Redo", Map.of(), Map.of(),
                undo ? "run(\"Undo\");" : "// redo", TargetScope.of(d.id())));
        p.finish = (plan, res) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step(plan.steps.get(0), res.get(0)));
            HistoryRecord r = res.get(0).record();
            if (r != null && r.params().get("undoneActionId") != null) m.put("undoneActionId", r.params().get("undoneActionId"));
            m.put("document", engine.document(d.id()).map(this::documentJson).orElse(null));
            m.put("highlight", Highlight.canvas(d.id(), undo ? "Undone" : "Redone"));
            return m;
        };
        return p;
    }

    private Plan runMacro(Call c) {
        String code = PennyJson.str(c.args(), "code", "");
        if (code.isBlank()) throw new IllegalArgumentException("'code' is required");
        if (code.length() > 32_000) throw new IllegalArgumentException("'code' is longer than 32000 characters");
        Optional<DocumentInfo> d = engine.focused().flatMap(engine::document);
        Plan p = new Plan(c, d.map(DocumentInfo::id).orElse(null));
        Map<String, Object> params = map("language", "ijm", "text", code, "transcriptRef", p.transcriptRef);
        TargetScope ts = p.doc == null ? null : TargetScope.of(p.doc);
        ActionRequest r = new ActionRequest(UUID.randomUUID().toString(), p.origin, ActionKind.CODE, null, "RUN_CODE", params, ts, null, null);
        String[] lines = code.split("\n");
        String preview = lines.length <= 12 ? code : String.join("\n", java.util.Arrays.copyOf(lines, 12)) + "\n// … " + (lines.length - 12) + " more lines";
        p.steps.add(new Step("macro", "Run macro (" + lines.length + (lines.length == 1 ? " line)" : " lines)"), "Plugins>Macros>Run...", r,
                Map.of(), preview));
        // The engine's capability check: the first submit without a token answers AWAITING_CONFIRMATION and issues one.
        ActionResult first;
        try {
            first = engine.submit(r).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new Failure(409, "The engine did not answer the macro check: " + e);
        }
        if (first.state() != OperationState.AWAITING_CONFIRMATION || first.confirmationToken() == null) {
            String msg = first.error() == null ? "the engine answered " + first.state() : first.error().message();
            throw new Failure(409, msg, map("code", first.error() == null ? null : String.valueOf(first.error().code())));
        }
        p.codeToken = first.confirmationToken();
        String caps = first.error() == null ? "" : first.error().context().getOrDefault("capabilities", "");
        p.engineReasons.add(first.error() == null ? "macro code runs with full privileges" : first.error().message());
        if (!caps.isBlank()) p.engineReasons.add("capabilities used: " + caps);
        p.finish = (plan, res) -> {
            ActionResult x = res.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step(plan.steps.get(0), x));
            if (x.digest() != null) {
                m.put("resultsRowsAdded", x.digest().resultsRowsAdded());
                m.put("log", x.digest().newLogLines().size() > 50 ? x.digest().newLogLines().subList(0, 50) : x.digest().newLogLines());
                m.put("documentsAdded", x.digest().added().stream().map(DocumentId::value).toList());
            }
            return m;
        };
        return p;
    }
}
