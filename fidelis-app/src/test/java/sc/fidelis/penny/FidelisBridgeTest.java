package sc.fidelis.penny;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sc.fidelis.penny.BridgeHttp.args;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IsolationState;
import sc.fidelis.testkit.PixelHash;
import sc.fidelis.testkit.ProductEngine;

/** The bridge's HTTP envelope and policy against the product engine: auth, Origin refusal, approval, context binding, plans. */
class FidelisBridgeTest {
    private Engine engine;
    private FidelisBridge bridge;
    private BridgeHttp http;
    private RecordingUi ui;

    @BeforeEach
    void setUp() {
        engine = ProductEngine.get();
        IsolationState.reset("FidelisBridgeTest");
        ui = new RecordingUi();
        PennyUi.register(ui);
        bridge = FidelisBridge.start(engine, BridgeHttp.TOKEN, 0, PennyOps.ConfirmMode.AUTO);
        http = new BridgeHttp(bridge.port(), BridgeHttp.TOKEN);
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.stop();
        PennyUi.register(null);
        IsolationState.reset("FidelisBridgeTest");
    }

    private String openSquares() throws Exception {
        Path png = Fixtures.repoFixture("two-squares.png");
        JsonObject r = http.change("open_path", args("path", png.toString())).result();
        assertEquals("completed", r.get("status").getAsString(), r.toString());
        return r.getAsJsonObject("document").get("id").getAsString();
    }

    @Test
    void healthAndExecuteNeedTheBearerTokenAndRefuseBrowserOrigins() throws Exception {
        assertEquals(401, http.get("/health", null, null).status());
        assertEquals(401, http.get("/health", "Bearer wrong-token-0123456789abcdef0123456789", null).status());
        BridgeHttp.Resp ok = http.get("/health", "Bearer " + BridgeHttp.TOKEN, null);
        assertEquals(200, ok.status(), ok.body().toString());
        assertEquals("Fidelis", ok.body().get("application").getAsString());
        JsonObject adapter = ok.body().getAsJsonObject("adapter");
        assertEquals(1, adapter.get("protocolVersion").getAsInt());
        assertEquals(PennyOps.OPERATIONS.size(), adapter.getAsJsonArray("operations").size());
        assertEquals(403, http.get("/health", "Bearer " + BridgeHttp.TOKEN, "http://localhost:3000").status());
        assertEquals(403, http.post(Map.of("operation", "context"), "Bearer " + BridgeHttp.TOKEN, "null").status());
        assertEquals(401, http.post(Map.of("operation", "context"), null, null).status());
        assertEquals(404, http.get("/execute", "Bearer " + BridgeHttp.TOKEN, null).status());
        BridgeHttp.Resp unknown = http.exec("delete_everything", null, true, "x");
        assertEquals(400, unknown.status());
        assertTrue(unknown.error().contains("Unknown operation"), unknown.error());
        assertEquals("Fidelis", http.read("context", null).get("application").getAsString());
    }

    @Test
    void changesNeedApprovalAndTheCurrentContext() throws Exception {
        Path png = Fixtures.repoFixture("two-squares.png");
        int before = engine.history().size();
        BridgeHttp.Resp noApproval = http.exec("open_path", args("path", png.toString()), null, http.contextKey());
        assertEquals(409, noApproval.status());
        assertTrue(noApproval.error().contains("approved:true"), noApproval.error());
        BridgeHttp.Resp noContext = http.exec("open_path", args("path", png.toString()), true, null);
        assertEquals(409, noContext.status());
        assertTrue(noContext.error().contains("expectedContext"), noContext.error());
        BridgeHttp.Resp stale = http.exec("open_path", args("path", png.toString()), true, "0".repeat(64));
        assertEquals(409, stale.status());
        assertTrue(stale.error().contains("changed"), stale.error());
        assertEquals(before, engine.history().size(), "a refused request must not reach the engine");
        String doc = openSquares();
        assertEquals(before + 1, engine.history().size());
        // view operations run without approval; a stale expectedContext still refuses them
        assertEquals(409, http.exec("select_roi", args("type", "rectangle", "x", 1, "y", 1, "width", 4, "height", 4), null, "f".repeat(64)).status());
        JsonObject sel = http.exec("select_roi", args("type", "rectangle", "x", 1, "y", 1, "width", 4, "height", 4), null, null).result();
        assertEquals("RECTANGLE", sel.getAsJsonObject("roi").get("type").getAsString());
        assertEquals(doc, http.read("context", null).getAsJsonObject("document").get("id").getAsString());
    }

    @Test
    void contextKeyFollowsDocumentSelectionResultsAndHistory() throws Exception {
        String empty = http.contextKey();
        assertEquals(empty, http.contextKey(), "reading the context must not change it");
        String doc = openSquares();
        String opened = http.contextKey();
        assertNotEquals(empty, opened);
        JsonObject ctx = http.read("context", null);
        assertEquals(doc, ctx.getAsJsonObject("document").get("id").getAsString());
        assertTrue(ctx.get("roi").isJsonNull());
        http.exec("select_roi", args("type", "oval", "x", 2, "y", 3, "width", 10, "height", 8), null, null).result();
        String selected = http.contextKey();
        assertNotEquals(opened, selected);
        JsonObject roi = http.read("context", null).getAsJsonObject("roi");
        assertEquals("OVAL", roi.get("type").getAsString());
        assertEquals(10.0, roi.getAsJsonObject("bounds").get("width").getAsDouble());
        http.exec("select_roi", args("type", "oval", "x", 2, "y", 3, "width", 11, "height", 8), null, null).result();
        String moved = http.contextKey();
        assertNotEquals(selected, moved, "a different selection is a different context");
        int rows = engine.results().size();
        JsonObject m = http.change("measure", args()).result();
        assertEquals(1, m.get("rows").getAsInt(), m.toString());
        assertEquals(rows + 1, engine.results().size());
        assertNotEquals(moved, http.contextKey(), "a new Results row changes the context");
        assertEquals(m.get("contextKey").getAsString(), http.contextKey(), "results carry the context after the change");
        JsonObject counts = http.read("context", null).getAsJsonObject("counts");
        assertEquals(engine.history().size(), counts.get("history").getAsInt());
        assertEquals(engine.results().size(), counts.get("resultsRows").getAsInt());
    }

    @Test
    void autoPlansRunAtOnceAndReportToTheWorkspace() throws Exception {
        String doc = openSquares();
        ui.calls.clear();
        JsonObject r = http.change("filter", args("name", "gaussian", "sigma", 1.5)).result();
        assertEquals("completed", r.get("status").getAsString(), r.toString());
        String planId = r.get("planId").getAsString();
        assertEquals(1, ui.count("showPlan " + planId + " running=true explicit=false"), ui.calls.toString());
        assertEquals(1, ui.count("progress " + planId + " 0 Gaussian Blur..."), ui.calls.toString());
        assertEquals(1, ui.count("finished " + planId + " ok=true"), ui.calls.toString());
        JsonObject step = r.getAsJsonObject("step");
        assertEquals("COMPLETED", step.get("outcome").getAsString());
        HistoryRecord rec = last();
        assertEquals("ij1:Process>Filters>Gaussian Blur...", rec.commandId());
        assertEquals(1.5, ((Number) rec.params().get("sigma")).doubleValue());
        assertEquals(sc.fidelis.engine.api.Enums.Origin.NL_SPOKEN, rec.origin());
        assertEquals(http.taskId, rec.transcriptRef());
        assertEquals(rec.actionId(), step.get("actionId").getAsString());
        // typed (spoken:false) requests are NL_TYPED
        http.spoken = false;
        http.change("filter", args("name", "invert")).result();
        assertEquals(sc.fidelis.engine.api.Enums.Origin.NL_TYPED, last().origin());
        assertEquals(doc, last().target().document().value());
    }

    @Test
    void anEngineFailureIsA409WithTheEngineMessageAndNothingIsReportedAsDone() throws Exception {
        openSquares();
        int before = engine.history().size();
        BridgeHttp.Resp r = http.change("analyze_particles", args());
        assertEquals(409, r.status(), r.body().toString());
        assertTrue(r.error().contains("threshold"), r.error());
        assertEquals("PRECONDITION_FAILED", r.body().get("code").getAsString());
        assertTrue(r.body().get("note").getAsString().contains("refused the failed step before running it"), r.body().toString());
        assertFalse(r.body().has("result"));
        assertEquals(before, engine.history().size(), "a precondition failure records nothing");
        BridgeHttp.Resp missing = http.change("command", args("commandId", "ij1:Process>Filters>Gaussian Blur...", "params", args("sigma", "abc")));
        assertEquals(400, missing.status(), missing.body().toString());
    }

    @Test
    void legacyFiltersRunAndParticleCountsDoNotDependOnTheResultsTable() throws Exception {
        String doc = openSquares();
        sc.fidelis.engine.api.DocumentId id = new sc.fidelis.engine.api.DocumentId(doc);
        String before = PixelHash.sha256(engine.imagePlus(id));
        JsonObject sharp = http.change("filter", args("name", "sharpen")).result();
        assertEquals("completed", sharp.get("status").getAsString(), sharp.toString());
        assertNotEquals(before, PixelHash.sha256(engine.imagePlus(id)), "Sharpen changed the pixels");
        assertEquals("ij1:Process>Sharpen", last().commandId());
        assertEquals(sc.fidelis.engine.api.Enums.Origin.NL_SPOKEN, last().origin());
        assertEquals(http.taskId, last().transcriptRef(), "the legacy route accepts the reserved transcriptRef");
        assertEquals(0, sharp.get("undoSteps").getAsInt(), "the legacy route keeps no undo snapshot");
        String sharpened = PixelHash.sha256(engine.imagePlus(id));
        BridgeHttp.Resp undo = http.change("undo", args());
        assertEquals(409, undo.status(), undo.body().toString());
        assertTrue(undo.error().contains("nothing to undo"), undo.error());
        assertEquals(sharpened, PixelHash.sha256(engine.imagePlus(id)), "a refused undo changes nothing");
        JsonObject t = http.change("threshold", args("method", "Otsu", "dark", true)).result();
        assertTrue(t.get("lower").getAsDouble() <= t.get("upper").getAsDouble(), t.toString());
        JsonObject shown = http.change("analyze_particles", args()).result();
        JsonObject quiet = http.change("analyze_particles", args("display", false)).result();
        System.out.println("[penny-bridge] two-squares: threshold " + t.get("lower") + "-" + t.get("upper") + ", particles " + shown.get("count")
                + " (" + shown.get("countedFrom").getAsString() + "), display off " + quiet.get("count") + " (" + quiet.get("countedFrom").getAsString() + ")");
        assertTrue(shown.get("count").getAsInt() > 0, shown.toString());
        assertEquals(shown.get("count").getAsInt(), quiet.get("count").getAsInt());
        assertEquals(engine.rois(id).size(), quiet.get("count").getAsInt());
        // without add the ROI list is not this run's: the highlight shows the image, never older objects
        JsonObject noAdd = http.change("analyze_particles", args("add", false, "size", "0-Infinity")).result();
        assertEquals(shown.get("count").getAsInt(), noAdd.get("count").getAsInt());
        assertFalse(noAdd.getAsJsonObject("highlight").get("flash").getAsBoolean(), noAdd.toString());
        // display auto reads the plane's raw range
        JsonObject disp = http.exec("display", args("auto", true), null, null).result();
        ij.process.ImageProcessor ip = engine.imagePlus(id).getProcessor();
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int y = 0; y < ip.getHeight(); y++) for (int x = 0; x < ip.getWidth(); x++) { double v = ip.getf(x, y); lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (lo < hi) {
            assertEquals(lo, disp.get("min").getAsDouble());
            assertEquals(hi, disp.get("max").getAsDouble());
        }
    }

    @Test
    void theEngineRefusesAStepWhenAnythingElseEnteredTheHistoryAfterItsApproval() throws Exception {
        String doc = openSquares();
        sc.fidelis.engine.api.DocumentId id = new sc.fidelis.engine.api.DocumentId(doc);
        // engine level: expectedHistorySize is checked when the worker dequeues the request
        int size = engine.history().size();
        sc.fidelis.engine.api.ActionResult stale = engine.submit(sc.fidelis.engine.api.ActionRequest.command(sc.fidelis.engine.api.Enums.Origin.NL_TYPED,
                "ij1:Edit>Invert", Map.of("expectedHistorySize", size - 1), sc.fidelis.engine.api.TargetScope.of(id))).get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(sc.fidelis.engine.api.Enums.OperationState.FAILED, stale.state());
        assertEquals("STATE_CHANGED", stale.error().subcode());
        assertEquals(size, engine.history().size(), "nothing was recorded");
        assertTrue(engine.submit(sc.fidelis.engine.api.ActionRequest.command(sc.fidelis.engine.api.Enums.Origin.NL_TYPED, "ij1:Edit>Invert",
                Map.of("expectedHistorySize", size), sc.fidelis.engine.api.TargetScope.of(id))).get(30, java.util.concurrent.TimeUnit.SECONDS).succeeded());
        // bridge level: a workspace action queued just before step 3 of a plan stops the plan there
        RecordingUi intruder = new RecordingUi() {
            @Override
            public void planProgress(String planId, int step, String label) {
                super.planProgress(planId, step, label);
                if (step == 2) engine.submit(sc.fidelis.engine.api.ActionRequest.direct(sc.fidelis.engine.api.Enums.Origin.PANEL,
                        sc.fidelis.engine.api.ActionRequest.Operations.CLEAR_ROI, Map.of(), sc.fidelis.engine.api.TargetScope.of(id)));
            }
        };
        PennyUi.register(intruder);
        int h0 = engine.history().size();
        BridgeHttp.Resp r = http.change("segment_cells", args("watershed", false));
        assertEquals(409, r.status(), r.body().toString());
        assertEquals("STATE_CHANGED", r.body().get("subcode").getAsString(), r.body().toString());
        assertEquals(3, r.body().get("failedStep").getAsInt());
        assertTrue(r.body().get("note").getAsString().contains("refused the failed step before running it"), r.body().toString());
        List<String> labels = new java.util.ArrayList<>();
        for (HistoryRecord h : engine.history().subList(h0, engine.history().size())) labels.add(h.label() + "/" + h.origin());
        System.out.println("[penny-bridge] interleaved plan history: " + labels);
        assertEquals(List.of("Gaussian Blur.../NL_SPOKEN", "SET_AUTO_THRESHOLD/NL_SPOKEN", "CLEAR_ROI/PANEL"), labels,
                "the workspace action ran, the plan's later steps did not");
    }

    @Test
    void macrosWaitForConfirmationAndRunOnlyAfterIt() throws Exception {
        String doc = openSquares();
        String hash = PixelHash.sha256(engine.imagePlus(new sc.fidelis.engine.api.DocumentId(doc)));
        int before = engine.history().size();
        JsonObject held = http.change("run_macro", args("code", "run(\"Invert\");")).result();
        assertEquals("awaiting_confirmation", held.get("status").getAsString(), held.toString());
        String planId = held.get("planId").getAsString();
        assertTrue(held.getAsJsonArray("reasons").size() > 0);
        assertTrue(held.getAsJsonArray("reasons").toString().contains("code"), held.toString());
        assertEquals(before, engine.history().size(), "nothing ran");
        assertEquals(hash, PixelHash.sha256(engine.imagePlus(new sc.fidelis.engine.api.DocumentId(doc))));
        assertEquals(1, ui.count("showPlan " + planId + " running=false explicit=true"), ui.calls.toString());
        assertEquals(planId, http.read("context", null).getAsJsonObject("pending").get("planId").getAsString());
        // confirm is a change: approval and the current context are required
        assertEquals(409, http.exec("confirm", args(), null, http.contextKey()).status());
        assertEquals(409, http.change("confirm", args("planId", "plan-nope")).status());
        JsonObject ran = http.change("confirm", args("planId", planId)).result();
        assertEquals("completed", ran.get("status").getAsString(), ran.toString());
        assertTrue(ran.get("confirmed").getAsBoolean());
        assertNotEquals(hash, PixelHash.sha256(engine.imagePlus(new sc.fidelis.engine.api.DocumentId(doc))), "the macro inverted the image");
        boolean code = false;
        for (HistoryRecord h : engine.history().subList(before, engine.history().size())) {
            if (h.commandId() == null && "macro".equalsIgnoreCase(h.label())) {
                code = true;
                assertEquals(sc.fidelis.engine.api.Enums.Origin.NL_SPOKEN, h.origin());
                assertEquals(http.taskId, h.transcriptRef());
            }
        }
        assertTrue(code, "a CODE record: " + engine.history().subList(before, engine.history().size()));
        assertEquals(409, http.change("confirm", args()).status(), "the plan ran once");
        assertTrue(http.read("context", null).get("pending").isJsonNull());
    }

    @Test
    void aPendingPlanIsDiscardedWhenTheContextChangesOrOnCancel() throws Exception {
        openSquares();
        JsonObject held = http.change("run_macro", args("code", "run(\"Invert\");")).result();
        String planId = held.get("planId").getAsString();
        http.exec("select_roi", args("type", "rectangle", "x", 0, "y", 0, "width", 5, "height", 5), null, null).result();
        BridgeHttp.Resp stale = http.change("confirm", args("planId", planId));
        assertEquals(409, stale.status());
        assertTrue(stale.error().contains("changed"), stale.error());
        assertEquals(1, ui.count("clear " + planId), ui.calls.toString());
        JsonObject again = http.change("run_macro", args("code", "run(\"Invert\");")).result();
        String id2 = again.get("planId").getAsString();
        JsonObject cancelled = http.exec("cancel_plan", args(), null, null).result();
        assertTrue(cancelled.get("cancelled").getAsBoolean());
        assertEquals(id2, cancelled.get("planId").getAsString());
        assertEquals(409, http.change("confirm", args()).status());
    }

    @Test
    void confirmAlwaysHoldsEveryPlan() throws Exception {
        bridge.stop();
        bridge = FidelisBridge.start(engine, BridgeHttp.TOKEN, 0, PennyOps.ConfirmMode.ALWAYS);
        http = new BridgeHttp(bridge.port(), BridgeHttp.TOKEN);
        Path png = Fixtures.repoFixture("two-squares.png");
        JsonObject held = http.change("open_path", args("path", png.toString())).result();
        assertEquals("awaiting_confirmation", held.get("status").getAsString());
        assertTrue(engine.documents().isEmpty(), "nothing opened before confirmation");
        assertEquals("always", http.read("context", null).get("confirmPolicy").getAsString());
        JsonObject ran = http.change("confirm", args()).result();
        assertEquals("completed", ran.get("status").getAsString());
        assertEquals(1, engine.documents().size());
    }

    @Test
    void highlightUsesTheWorkspaceGeometry() throws Exception {
        String doc = openSquares();
        http.exec("select_roi", args("type", "rectangle", "x", 10, "y", 20, "width", 30, "height", 40), null, null).result();
        JsonObject h = http.exec("highlight", args("rois", "active", "label", "this one"), null, null).result().getAsJsonObject("highlight");
        JsonArray t = h.getAsJsonArray("targets");
        assertEquals(1, t.size(), h.toString());
        JsonObject target = t.get(0).getAsJsonObject();
        assertEquals(RecordingUi.CANVAS_X + 10 * RecordingUi.MAG, target.get("x").getAsDouble());
        assertEquals(RecordingUi.CANVAS_Y + 20 * RecordingUi.MAG, target.get("y").getAsDouble());
        assertEquals(60.0, target.get("width").getAsDouble());
        assertEquals(80.0, target.get("height").getAsDouble());
        assertEquals("rect", target.get("shape").getAsString());
        assertEquals(RecordingUi.CANVAS_X + 25 * RecordingUi.MAG, h.getAsJsonObject("pointer").get("screenX").getAsDouble());
        assertEquals(RecordingUi.CANVAS_X, h.getAsJsonObject("viewer").get("x").getAsInt());
        assertEquals("this one", h.get("label").getAsString());
        assertTrue(ui.count("flash " + doc + " 1 this one") >= 1, ui.calls.toString());
        assertEquals(409, http.exec("highlight", args("rois", "all"), null, null).status(), "the ROI list is empty");
        JsonObject z = http.exec("zoom", args("direction", "in"), null, null).result();
        assertEquals("in", z.get("zoom").getAsString());
        assertEquals(1, ui.count("zoom " + doc + " in"));
        // without a workspace the payload keeps its shape with no targets
        PennyUi.register(null);
        JsonObject none = http.exec("highlight", args("rois", "active"), null, null).result().getAsJsonObject("highlight");
        assertEquals(0, none.getAsJsonArray("targets").size());
        assertTrue(none.get("viewer").isJsonNull());
    }

    private HistoryRecord last() {
        List<HistoryRecord> h = engine.history();
        assertNotNull(h);
        return h.get(h.size() - 1);
    }

    @SuppressWarnings("unused")
    private static String str(JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
