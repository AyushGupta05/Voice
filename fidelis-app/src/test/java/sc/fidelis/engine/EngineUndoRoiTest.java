package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.ParameterType;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/**
 * Undo of ROI-list and Results changes (18 Oct 2026): a segmentation-like sequence (threshold, Analyze Particles with
 * add + display + clear, Measure) is undone step by step, the ROI list and the Results rows come back, redo re-applies,
 * and the per-action rule keeps another document's rows out of it. Pixel undo is unchanged.
 */
class EngineUndoRoiTest {
    DefaultEngine engine;
    DocumentId doc;

    @BeforeEach
    void open() throws Exception {
        engine = EngineFixture.engine();
        doc = open("fixtures/two-squares.png");
    }

    @AfterEach
    void close() throws Exception {
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, Map.of("discard", true), TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
    }

    private DocumentId open(String file) throws Exception {
        ActionResult r = engine.open(EngineFixture.repoFile(file)).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        DocumentId id = r.digest().added().get(0);
        engine.focus(id);
        return id;
    }

    private ActionResult cmd(DocumentId d, String id, Map<String, Object> params) throws Exception {
        ActionResult r = engine.submit(ActionRequest.command(Origin.PANEL, id, params, TargetScope.of(d))).get(120, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), id + ": " + r.error());
        return r;
    }

    private ActionResult direct(DocumentId d, String op, Map<String, Object> params) throws Exception {
        return engine.submit(ActionRequest.direct(Origin.PANEL, op, params, TargetScope.of(d))).get(60, TimeUnit.SECONDS);
    }

    private ActionResult ok(ActionResult r) {
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        return r;
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }

    private Map<String, Object> setMeasurements(String... on) {
        CommandSchema s = engine.schema("ij1:Analyze>Set Measurements...").orElseThrow();
        List<String> wanted = List.of(on);
        Map<String, Object> p = new LinkedHashMap<>();
        for (CommandSchema.ParameterSchema ps : s.parameters()) if (ps.type() == ParameterType.BOOLEAN) p.put(ps.key(), wanted.contains(ps.key()));
        p.put("redirect", "None");
        p.put("decimal", 3);
        return p;
    }

    private static Map<String, Object> particles(boolean clear) {
        Map<String, Object> p = m("size", "0-Infinity", "pixel", false, "circularity", "0.00-1.00", "show", "Nothing", "display", true,
                "exclude", false, "clear", clear, "include", false, "summarize", false, "overlay", false, "add", true, "composite", false,
                "record", false, "in_situ", false, "stack", false);
        return p;
    }

    private int rowsOf(String actionId) {
        int n = 0;
        for (MeasurementRow r : engine.results()) if (actionId.equals(r.actionId())) n++;
        return n;
    }

    @Test
    void aSegmentationLikeSequenceIsUndoneStepByStepAndRedone() throws Exception {
        cmd(doc, "ij1:Analyze>Set Measurements...", setMeasurements("area", "mean"));
        cmd(doc, "ij1:Analyze>Clear Results", m());
        ok(direct(doc, Operations.SET_AUTO_THRESHOLD, m("method", "Otsu", "dark", true)));
        int rowsBefore = engine.results().size();
        ActionResult ap = cmd(doc, "ij1:Analyze>Analyze Particles...", particles(true));
        int objects = engine.rois(doc).size();
        assertTrue(objects > 0, "particles found: " + ap.digest());
        assertEquals(objects, rowsOf(ap.record().actionId()), "one row per particle, attributed to Analyze Particles");
        int rowsAfterParticles = engine.results().size();
        // Measure appends a row; undo removes exactly that row and leaves the ROI list alone
        ActionResult measure = cmd(doc, "ij1:Analyze>Measure", m());
        assertEquals(rowsAfterParticles + 1, engine.results().size());
        ActionResult u1 = ok(direct(doc, Operations.UNDO, m()));
        assertEquals(measure.record().actionId(), u1.record().params().get("undoneActionId"));
        assertEquals(rowsAfterParticles, engine.results().size(), "the measured row is gone");
        assertEquals(0, rowsOf(measure.record().actionId()));
        assertEquals(objects, engine.rois(doc).size(), "the ROI list is untouched by the Measure undo");
        assertEquals(rowsAfterParticles + 1, u1.record().params().get("resultsRowsBefore"));
        assertEquals(rowsAfterParticles, u1.record().params().get("resultsRowsAfter"));
        // undo Analyze Particles: the objects leave the ROI list and its rows leave the table (it cleared nothing of note)
        ActionResult u2 = ok(direct(doc, Operations.UNDO, m()));
        assertEquals(ap.record().actionId(), u2.record().params().get("undoneActionId"));
        assertEquals(0, engine.rois(doc).size(), "no objects after the undo");
        assertEquals(rowsBefore, engine.results().size(), "the particle rows are gone: " + engine.results());
        assertEquals(objects, u2.record().params().get("roisBefore"));
        assertEquals(0, u2.record().params().get("roisAfter"));
        assertEquals(Boolean.FALSE, u2.record().params().get("pixelsRestored"));
        // redo puts the particles back
        ActionResult redo = ok(direct(doc, Operations.REDO, m()));
        assertEquals(objects, engine.rois(doc).size(), "redo restored the objects: " + redo.record());
        assertEquals(rowsAfterParticles, engine.results().size(), "redo restored the rows");
        assertEquals(objects, rowsOf(redo.record().actionId()));
        // the macro replays neither undone step; the redone one is in
        String macro = engine.exportMacro();
        assertTrue(macro.contains("undone"), macro);
    }

    @Test
    void roiListEditsAndClearResultsAreUndoable() throws Exception {
        cmd(doc, "ij1:Analyze>Set Measurements...", setMeasurements("area"));
        ok(direct(doc, Operations.SET_ROI, m("roi", new RoiGeometry("RECTANGLE", List.of(), List.of(), new double[] {2, 2, 18, 18}, null, null, null))));
        ok(direct(doc, Operations.ROI_LIST_ADD, m()));
        ok(direct(doc, Operations.ROI_LIST_ADD, m()));
        assertEquals(2, engine.rois(doc).size());
        ok(direct(doc, Operations.ROI_LIST_RENAME, m("index", 0, "name", "first")));
        assertEquals("first", engine.rois(doc).get(0).name());
        ActionResult u = ok(direct(doc, Operations.UNDO, m()));
        assertEquals(Operations.ROI_LIST_RENAME, u.record().params().get("undoneOperation"));
        assertNotEquals("first", engine.rois(doc).get(0).name(), "the rename is undone");
        ok(direct(doc, Operations.ROI_LIST_DELETE, m("index", 0)));
        assertEquals(1, engine.rois(doc).size());
        ok(direct(doc, Operations.UNDO, m()));
        assertEquals(2, engine.rois(doc).size(), "the deleted entry is back");
        ok(direct(doc, Operations.UNDO, m()));
        assertEquals(1, engine.rois(doc).size(), "the second add is undone");
        ok(direct(doc, Operations.REDO, m()));
        assertEquals(2, engine.rois(doc).size(), "and redone");
        // Clear Results: the cleared rows come back on undo
        cmd(doc, "ij1:Analyze>Measure", m());
        cmd(doc, "ij1:Analyze>Measure", m());
        int rows = engine.results().size();
        assertTrue(rows >= 2);
        List<MeasurementRow> before = engine.results();
        cmd(doc, "ij1:Analyze>Clear Results", m());
        assertEquals(0, engine.results().size());
        ActionResult uc = ok(direct(doc, Operations.UNDO, m()));
        assertEquals(rows, engine.results().size(), "the cleared rows are back");
        assertEquals(0, uc.record().params().get("resultsRowsBefore"));
        assertEquals(rows, uc.record().params().get("resultsRowsAfter"));
        for (int i = 0; i < rows; i++) {
            assertEquals(before.get(i).values(), engine.results().get(i).values(), "row " + i + " bit for bit");
            assertEquals(before.get(i).actionId(), engine.results().get(i).actionId(), "row " + i + " keeps its attribution");
        }
    }

    @Test
    void undoIsPerDocumentAndPerAction() throws Exception {
        cmd(doc, "ij1:Analyze>Set Measurements...", setMeasurements("area", "mean"));
        cmd(doc, "ij1:Analyze>Clear Results", m());
        DocumentId other = open("fixtures/two-squares.png");
        ActionResult m1 = cmd(doc, "ij1:Analyze>Measure", m());
        ActionResult m2 = cmd(other, "ij1:Analyze>Measure", m());
        ActionResult m3 = cmd(doc, "ij1:Analyze>Measure", m());
        assertEquals(3, engine.results().size());
        // undo on the first document removes its latest row only; the other document's row stays where it is
        ok(direct(doc, Operations.UNDO, m()));
        assertEquals(2, engine.results().size());
        assertEquals(m1.record().actionId(), engine.results().get(0).actionId());
        assertEquals(m2.record().actionId(), engine.results().get(1).actionId());
        assertEquals(other, engine.results().get(1).document());
        ok(direct(other, Operations.UNDO, m()));
        assertEquals(1, engine.results().size());
        assertEquals(m1.record().actionId(), engine.results().get(0).actionId());
        assertEquals(0, rowsOf(m3.record().actionId()));
        // nothing else to undo on the other document
        ActionResult none = direct(other, Operations.UNDO, m());
        assertEquals(OperationState.FAILED, none.state());
    }

    @Test
    void pixelUndoIsUnchanged() throws Exception {
        String h0 = Pixels.hash(engine.imagePlus(doc), false);
        cmd(doc, "ij1:Process>Filters>Gaussian Blur...", m("sigma", 3, "scaled", false, "stack", false));
        assertNotEquals(h0, Pixels.hash(engine.imagePlus(doc), false));
        ActionResult u = ok(direct(doc, Operations.UNDO, m()));
        assertEquals(h0, Pixels.hash(engine.imagePlus(doc), false));
        assertEquals(Boolean.TRUE, u.record().params().get("pixelsRestored"));
        assertTrue(u.record().params().get("roisBefore") == null, "no ROI-list claim for a pixel undo");
    }
}
