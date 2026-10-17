package sc.fidelis.penny;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sc.fidelis.penny.BridgeHttp.args;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IsolationState;
import sc.fidelis.testkit.PixelHash;
import sc.fidelis.testkit.ProductEngine;

/**
 * A J-12-style voice journey through the bridge's HTTP interface on Blobs (FX-003): open, segment_cells, measure the
 * whole image and every segmented object, undo, history provenance, and a macro that waits for confirmation. Every
 * number is read back from the engine (Results mirror, ROI list, history), never from the bridge's own claims.
 */
class FidelisBridgeJourneyTest {
    private static final String TASK = "task-j12-voice";
    private Engine engine;
    private FidelisBridge bridge;
    private BridgeHttp http;
    private RecordingUi ui;

    @BeforeEach
    void setUp() {
        engine = ProductEngine.get();
        IsolationState.reset("FidelisBridgeJourneyTest");
        ui = new RecordingUi();
        PennyUi.register(ui);
        bridge = FidelisBridge.start(engine, BridgeHttp.TOKEN, 0, PennyOps.ConfirmMode.AUTO);
        http = new BridgeHttp(bridge.port(), BridgeHttp.TOKEN);
        http.taskId = TASK;
        http.spoken = true;
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.stop();
        PennyUi.register(null);
        IsolationState.reset("FidelisBridgeJourneyTest");
    }

    @Test
    void blobsVoiceJourneyThroughTheBridge() throws Exception {
        Path blobs = Fixtures.require("FX-003", "blobs.gif");
        int h0 = engine.history().size();

        // open
        JsonObject opened = http.change("open_path", args("path", blobs.toString())).result();
        JsonObject docJson = opened.getAsJsonObject("document");
        DocumentId doc = new DocumentId(docJson.get("id").getAsString());
        assertEquals(256, docJson.get("width").getAsInt());
        assertEquals(254, docJson.get("height").getAsInt());
        assertEquals(doc, engine.focused().orElseThrow());

        // segment the cells
        JsonObject seg = http.change("segment_cells", args()).result();
        assertEquals("completed", seg.get("status").getAsString(), seg.toString());
        int count = seg.get("count").getAsInt();
        double average = seg.get("averageSize").getAsDouble();
        List<String> labels = new ArrayList<>();
        for (JsonElement e : seg.getAsJsonArray("steps")) labels.add(e.getAsJsonObject().get("label").getAsString());
        System.out.println("[penny-journey] segment_cells: count=" + count + " averageSize=" + average + " totalArea="
                + seg.get("totalArea").getAsDouble() + " unit=" + seg.get("unit").getAsString() + " threshold=" + seg.get("threshold")
                + " steps=" + labels);
        assertTrue(count > 0, seg.toString());
        assertEquals(count, engine.rois(doc).size(), "every particle is in the document's ROI list");
        String paAction = null;
        for (JsonElement e : seg.getAsJsonArray("steps")) {
            JsonObject s = e.getAsJsonObject();
            assertEquals("COMPLETED", s.get("outcome").getAsString(), s.toString());
            if (s.get("label").getAsString().equals("Analyze Particles...")) paAction = s.get("actionId").getAsString();
        }
        final String pa = paAction;
        List<MeasurementRow> paRows = engine.results().stream().filter(r -> pa != null && pa.equals(r.actionId())).toList();
        assertEquals(count, paRows.size(), "count = Results rows the engine attributed to Analyze Particles");
        double sum = 0;
        for (MeasurementRow r : paRows) sum += r.values().get("Area");
        assertEquals(sum / count, average, 1e-9);
        assertEquals(List.of("Gaussian Blur...", "Auto threshold Otsu", "Convert to Mask", "Fill Holes", "Watershed", "Analyze Particles..."),
                labels, "plan on the focused 8-bit Blobs (inverting LUT: objects dark on light, so not 'dark'), no Duplicate");
        JsonObject hl = seg.getAsJsonObject("highlight");
        assertEquals(Math.min(count, PennyOps.HIGHLIGHT_LIMIT), hl.getAsJsonArray("targets").size(), hl.toString());
        assertEquals(count, hl.get("total").getAsInt());
        assertEquals(Math.min(count, PennyOps.HIGHLIGHT_LIMIT), ui.flashed);

        // history: every engine step came from speech and carries the task id
        List<HistoryRecord> segSteps = engine.history().subList(h0 + 1, engine.history().size());
        assertEquals(labels.size(), segSteps.size());
        for (HistoryRecord r : segSteps) {
            assertEquals(Origin.NL_SPOKEN, r.origin(), r.toString());
            assertEquals(TASK, r.transcriptRef(), r.toString());
            assertEquals(OperationState.COMPLETED, r.outcome());
        }
        JsonObject hist = http.read("history", args("limit", 6));
        for (JsonElement e : hist.getAsJsonArray("steps")) {
            assertEquals("NL_SPOKEN", e.getAsJsonObject().get("origin").getAsString());
            assertEquals(TASK, e.getAsJsonObject().get("transcriptRef").getAsString());
        }

        // measure the whole image (area and mean are in Fiji's default measurement set: no Set Measurements step)
        JsonObject whole = http.change("measure", args("measurements", List.of("area", "mean"), "scope", "all")).result();
        assertEquals(1, whole.get("rows").getAsInt(), whole.toString());
        assertEquals(256.0 * 254.0, whole.getAsJsonObject("summary").getAsJsonObject("area").get("mean").getAsDouble(), 1e-9);
        assertEquals(1, whole.getAsJsonArray("steps").size(), "no selection, so no Select None/restore and no Set Measurements");

        // measure every segmented object: one row per ROI, the same areas Analyze Particles reported
        int before = engine.results().size();
        JsonObject each = http.change("measure", args("scope", "rois", "measurements", List.of("area"))).result();
        assertEquals(count, each.get("rows").getAsInt(), each.toString());
        assertEquals(before + count, engine.results().size());
        double roiMean = each.getAsJsonObject("summary").getAsJsonObject("area").get("mean").getAsDouble();
        System.out.println("[penny-journey] measure rois: rows=" + each.get("rows").getAsInt() + " mean area=" + roiMean
                + " (Analyze Particles " + average + ")");
        assertEquals(average, roiMean, 1e-9, "ROI-list areas equal the particle areas");

        // undo restores the pixels of the last data step
        String beforeInvert = PixelHash.sha256(engine.imagePlus(doc));
        http.change("filter", args("name", "invert")).result();
        assertNotEquals(beforeInvert, PixelHash.sha256(engine.imagePlus(doc)));
        JsonObject undone = http.change("undo", args()).result();
        assertEquals("COMPLETED", undone.getAsJsonObject("step").get("outcome").getAsString());
        assertEquals(beforeInvert, PixelHash.sha256(engine.imagePlus(doc)), "undo restored the pixels");
        HistoryRecord undoRec = engine.history().get(engine.history().size() - 1);
        assertEquals("UNDO", undoRec.commandId());
        assertEquals(Origin.NL_SPOKEN, undoRec.origin());
        assertEquals(TASK, undoRec.transcriptRef());

        // a macro waits for confirmation, then runs
        int rows = engine.results().size();
        int hist0 = engine.history().size();
        JsonObject held = http.change("run_macro", args("code", "run(\"Measure\");")).result();
        assertEquals("awaiting_confirmation", held.get("status").getAsString(), held.toString());
        assertEquals(hist0, engine.history().size(), "nothing ran before confirm");
        assertEquals(rows, engine.results().size());
        JsonObject ran = http.change("confirm", args("planId", held.get("planId").getAsString())).result();
        assertEquals("completed", ran.get("status").getAsString(), ran.toString());
        assertEquals(rows + 1, engine.results().size(), "the confirmed macro measured once");
        Set<Origin> origins = new java.util.HashSet<>();
        for (HistoryRecord r : engine.history().subList(hist0, engine.history().size())) origins.add(r.origin());
        assertTrue(origins.contains(Origin.NL_SPOKEN), origins.toString());

        // the session macro is generated from history and contains the voice steps
        String macro = http.read("macro", null).get("macro").getAsString();
        assertTrue(macro.contains("Gaussian Blur..."), macro);
        assertTrue(macro.contains("Analyze Particles..."), macro);
        System.out.println("[penny-journey] history steps=" + engine.history().size() + " results rows=" + engine.results().size());
    }

    /**
     * Independent check of the count: the macro Fidelis generates from the voice session's history, run by plain ImageJ
     * in this JVM ({@code IJ.runMacro}, as InJvmCrossCheckTest does), finds the same particles with the same areas.
     */
    @Test
    void theExportedMacroOfTheVoiceSessionReproducesTheCountInPlainImageJ() throws Exception {
        Path blobs = Fixtures.require("FX-003", "blobs.gif");
        int h0 = engine.history().size();
        http.change("open_path", args("path", blobs.toString())).result();
        JsonObject seg = http.change("segment_cells", args()).result();
        int count = seg.get("count").getAsInt();
        double total = seg.get("totalArea").getAsDouble();
        String macro = ((sc.fidelis.engine.DefaultEngine) engine).exportMacroSince(h0);
        IsolationState.reset("FidelisBridgeJourneyTest replay");
        ij.measure.ResultsTable.getResultsTable().reset();
        sc.fidelis.testkit.IjState.runMacro(macro, 120);
        ij.measure.ResultsTable rt = ij.measure.ResultsTable.getResultsTable();
        double replayTotal = 0;
        int col = rt.getColumnIndex("Area");
        for (int r = 0; r < rt.size(); r++) replayTotal += rt.getValueAsDouble(col, r);
        ij.plugin.frame.RoiManager rm = ij.plugin.frame.RoiManager.getRawInstance();
        System.out.println("[penny-journey] exported-macro replay: rows=" + rt.size() + " totalArea=" + replayTotal + " roiManager="
                + (rm == null ? "none" : rm.getCount()) + " (bridge count=" + count + " totalArea=" + total + ")");
        assertEquals(count, rt.size(), "replayed macro:\n" + macro);
        assertEquals(total, replayTotal, 1e-9);
        assertTrue(macro.contains("setAutoThreshold(\"Otsu\");"), macro);
    }

    @Test
    void undoWithTheReportedUndoStepsRestoresTheOriginalPixels() throws Exception {
        Path blobs = Fixtures.require("FX-003", "blobs.gif");
        JsonObject opened = http.change("open_path", args("path", blobs.toString())).result();
        DocumentId doc = new DocumentId(opened.getAsJsonObject("document").get("id").getAsString());
        String original = PixelHash.sha256(engine.imagePlus(doc));
        JsonObject seg = http.change("segment_cells", args()).result();
        int steps = seg.get("undoSteps").getAsInt();
        assertTrue(steps >= 2 && steps <= 4, seg.toString());
        assertNotEquals(original, PixelHash.sha256(engine.imagePlus(doc)));
        JsonObject undone = http.change("undo", args("steps", steps)).result();
        System.out.println("[penny-journey] undo steps=" + steps + " undid " + undone.get("undoneActionIds"));
        assertEquals(steps, undone.getAsJsonArray("undoneActionIds").size());
        assertEquals(original, PixelHash.sha256(engine.imagePlus(doc)), "segment_cells fully reverted");
        BridgeHttp.Resp more = http.change("undo", args());
        assertEquals(409, more.status(), "nothing left to undo: " + more.body());
        assertTrue(more.error().contains("nothing to undo"), more.error());
    }

    @Test
    void analyzeParticlesAgainReplacesOrExtendsTheRoiListHonestly() throws Exception {
        Path blobs = Fixtures.require("FX-003", "blobs.gif");
        JsonObject opened = http.change("open_path", args("path", blobs.toString())).result();
        DocumentId doc = new DocumentId(opened.getAsJsonObject("document").get("id").getAsString());
        int first = http.change("segment_cells", args("minSize", 20)).result().get("count").getAsInt();
        JsonObject again = http.change("analyze_particles", args("size", "20-Infinity")).result();
        int second = again.get("count").getAsInt();
        System.out.println("[penny-journey] segment_cells(minSize 20)=" + first + ", analyze_particles again=" + second + ", ROI list="
                + engine.rois(doc).size() + " (bridge reports rois=" + again.get("rois").getAsInt() + ")");
        assertEquals(first, second, "the same mask gives the same particles");
        assertEquals(engine.rois(doc).size(), again.get("rois").getAsInt());
        JsonObject stats = http.read("object_stats", null);
        assertEquals(second, stats.get("n").getAsInt(), stats.toString());
    }
}
