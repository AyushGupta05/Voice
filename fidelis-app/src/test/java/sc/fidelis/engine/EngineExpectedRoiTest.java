package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.TargetScope;

/**
 * The reserved request key {@code expectedRoi} (opt-in, for the Penny bridge): a step approved against one selection is
 * refused (PRECONDITION_FAILED, subcode STATE_CHANGED, nothing run) when the target document's live selection differs
 * when the worker starts it, including a selection drawn on the canvas so recently that the ROI observer has not
 * recorded it yet. Requests without the key behave as before.
 */
class EngineExpectedRoiTest {
    DefaultEngine engine;
    DocumentId doc;
    ImagePlus imp;

    @BeforeEach
    void open() throws Exception {
        engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        doc = r.digest().added().get(0);
        engine.focus(doc);
        imp = engine.imagePlus(doc);
        setRoi(null);
    }

    @AfterEach
    void close() throws Exception {
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, Map.of("discard", true), TargetScope.of(d.id())))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    /** What a canvas drag leaves: the selection set on the EDT; the ROI observer records it 250 ms later. */
    void setRoi(Roi roi) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (roi == null) imp.deleteRoi();
            else imp.setRoi(roi);
        });
    }

    String approvedSignature() {
        return RoiSignature.of(engine.activeRoi(doc).orElse(null));
    }

    ActionResult measure(Map<String, Object> reserved) throws Exception {
        return measureAsync(reserved).get(60, TimeUnit.SECONDS);
    }

    CompletableFuture<ActionResult> measureAsync(Map<String, Object> reserved) {
        return engine.submit(ActionRequest.command(Origin.NL_SPOKEN, "ij1:Analyze>Measure", new LinkedHashMap<>(reserved),
                TargetScope.of(doc)));
    }

    long measures() {
        return engine.history().stream().filter(h -> "Measure".equals(h.label())).count();
    }

    static void assertRefused(ActionResult r, String why) {
        assertEquals(OperationState.FAILED, r.state(), why + ": " + r.error());
        assertEquals(ErrorCode.PRECONDITION_FAILED, r.error().code(), why);
        assertEquals("STATE_CHANGED", r.error().subcode(), why);
    }

    @Test
    void signatureFormat() throws Exception {
        assertEquals("none", approvedSignature());
        assertEquals(engine.roiSignature(doc).orElseThrow(), approvedSignature());
        setRoi(new Roi(10, 20, 30, 40));
        String rect = approvedSignature();
        assertTrue(rect.matches("RECTANGLE:10,20,30,40:[0-9a-f]{16}"), rect);
        assertEquals(engine.roiSignature(doc).orElseThrow(), rect);
        setRoi(new Roi(10.5, 20.25, 30, 40));
        assertTrue(approvedSignature().startsWith("RECTANGLE:10.5,20.25,30,40:"), approvedSignature());
        setRoi(new PolygonRoi(new float[] {5, 25, 15}, new float[] {4, 6, 30}, Roi.POLYGON));
        String poly = approvedSignature();
        assertTrue(poly.matches("POLYGON:5,4,20,26:[0-9a-f]{16}"), poly);
        // same type and bounds, one vertex moved: the hash tells them apart
        setRoi(new PolygonRoi(new float[] {5, 25, 16}, new float[] {4, 6, 30}, Roi.POLYGON));
        String moved = approvedSignature();
        assertTrue(moved.startsWith("POLYGON:5,4,20,26:"), moved);
        assertTrue(!moved.equals(poly), "a moved vertex changes the signature");
        // the name is not part of it
        Roi named = new Roi(10, 20, 30, 40);
        named.setName("cell 1");
        setRoi(named);
        assertEquals(rect, approvedSignature());
        assertEquals("none", RoiSignature.of(null));
    }

    @Test
    void withoutTheKeyAStepRunsWithWhateverSelectionIsLive() throws Exception {
        setRoi(new Roi(0, 0, 8, 8));
        int rows = ResultsTable.getResultsTable().size();
        ActionResult r = measure(Map.of());
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals(rows + 1, ResultsTable.getResultsTable().size());
        assertEquals(64.0, engine.results().get(engine.results().size() - 1).values().get("Area"));
    }

    @Test
    void matchingSelectionRuns() throws Exception {
        setRoi(new Roi(0, 0, 8, 8));
        ActionResult r = measure(Map.of("expectedRoi", approvedSignature()));
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals(64.0, engine.results().get(engine.results().size() - 1).values().get("Area"));
        setRoi(null);
        ActionResult whole = measure(Map.of("expectedRoi", "none"));
        assertEquals(OperationState.COMPLETED, whole.state(), String.valueOf(whole.error()));
        assertEquals(4096.0, engine.results().get(engine.results().size() - 1).values().get("Area"));
    }

    @Test
    void aSelectionDrawnJustBeforeTheStepIsRefusedAndNothingRuns() throws Exception {
        String approved = approvedSignature(); // approved on the whole image
        assertEquals("none", approved);
        setRoi(new Roi(2, 3, 10, 10)); // drawn after approval; the observer has not recorded it yet
        int rows = ResultsTable.getResultsTable().size();
        long before = measures();
        ActionResult r = measure(Map.of("expectedRoi", approved));
        assertRefused(r, "a selection drawn after approval");
        assertEquals("none", r.error().context().get("expectedRoi"));
        assertTrue(r.error().context().get("actualRoi").startsWith("RECTANGLE:2,3,10,10:"), r.error().context().toString());
        assertEquals(doc.value(), r.error().context().get("document"));
        assertEquals(rows, ResultsTable.getResultsTable().size(), "nothing was measured");
        assertEquals(before, measures(), "no Measure in the history");
        assertTrue(r.record() == null, "a refused step leaves no history record");
    }

    @Test
    void aSelectionChangedWhileTheStepWaitsInTheQueueIsRefusedWhenTheWorkerStartsIt() throws Exception {
        setRoi(new Roi(0, 0, 8, 8));
        String approved = approvedSignature();
        // keep the worker busy with a confirmed macro, queue the approved step behind it, then move the selection
        String text = "wait(600);\n";
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, TargetScope.of(doc), null)).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.AWAITING_CONFIRMATION, first.state(), String.valueOf(first.error()));
        CompletableFuture<ActionResult> busy = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, TargetScope.of(doc),
                first.confirmationToken()));
        CompletableFuture<ActionResult> queued = measureAsync(Map.of("expectedRoi", approved));
        setRoi(new Roi(0, 0, 9, 8)); // one pixel wider, while the step waits
        assertEquals(OperationState.COMPLETED, busy.get(30, TimeUnit.SECONDS).state());
        ActionResult r = queued.get(60, TimeUnit.SECONDS);
        assertRefused(r, "selection changed while queued");
        // approved again on the new selection, the step runs
        ActionResult again = measure(Map.of("expectedRoi", approvedSignature()));
        assertEquals(OperationState.COMPLETED, again.state(), String.valueOf(again.error()));
        assertEquals(72.0, engine.results().get(engine.results().size() - 1).values().get("Area"));
    }

    @Test
    void malformedOrUntargetedKeysAreValidationErrors() throws Exception {
        ActionResult number = measure(Map.of("expectedRoi", 3));
        assertEquals(ErrorCode.VALIDATION_FAILED, number.error().code(), String.valueOf(number.error()));
        ActionResult blank = measure(Map.of("expectedRoi", " "));
        assertEquals(ErrorCode.VALIDATION_FAILED, blank.error().code(), String.valueOf(blank.error()));
        Map<String, Object> p = new LinkedHashMap<>(Map.of("title", "untargeted", "type", "8-bit", "width", 8, "height", 8,
                "fill", "White", "expectedRoi", "none"));
        ActionResult session = engine.submit(ActionRequest.session(Origin.PANEL, Operations.NEW_IMAGE, p, null)).get(30, TimeUnit.SECONDS);
        assertEquals(ErrorCode.VALIDATION_FAILED, session.error().code(), String.valueOf(session.error()));
        List<HistoryRecord> h = engine.history();
        assertTrue(h.stream().noneMatch(x -> "untargeted".equals(String.valueOf(x.params().get("title")))), "nothing was created");
    }
}
