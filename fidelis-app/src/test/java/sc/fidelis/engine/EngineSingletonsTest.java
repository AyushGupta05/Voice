package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.WindowManager;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.text.TextWindow;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/**
 * E8 item 6: nothing in the engine may keep a stale reference to an IJ1 singleton (ROI Manager, system Results
 * table and its window, Log) that a macro or the user can close or replace.
 */
class EngineSingletonsTest {
    DefaultEngine engine;
    DocumentId doc;

    static final Map<String, Object> PARTICLES = new LinkedHashMap<>();
    static {
        PARTICLES.put("size", "0-Infinity");
        PARTICLES.put("circularity", "0.00-1.00");
        PARTICLES.put("show", "Nothing");
        PARTICLES.put("display", true);
        PARTICLES.put("exclude", false);
        PARTICLES.put("clear", false);
        PARTICLES.put("include", false);
        PARTICLES.put("summarize", false);
        PARTICLES.put("overlay", false);
        PARTICLES.put("add", true);
        PARTICLES.put("composite", false);
        PARTICLES.put("record", false);
        PARTICLES.put("in_situ", false);
    }

    @BeforeEach
    void open() throws Exception {
        engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        doc = engine.documents().get(engine.documents().size() - 1).id();
        cmd("ij1:Analyze>Clear Results", Map.of());
        engine.submit(ActionRequest.direct(Origin.PANEL, ActionRequest.Operations.SET_THRESHOLD, new LinkedHashMap<>(Map.of("lower", 1, "upper", 255)),
                TargetScope.of(doc))).get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                    TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
        IJ.run("Clear Results");
    }

    ActionResult cmd(String id, Map<String, Object> params) throws Exception {
        return engine.submit(ActionRequest.command(Origin.PANEL, id, new LinkedHashMap<>(params), TargetScope.of(doc))).get(120, TimeUnit.SECONDS);
    }

    @Test
    void roiManagerClosedByTheUserIsReResolvedOnNextUse() throws Exception {
        ActionResult a = cmd("ij1:Analyze>Analyze Particles...", PARTICLES);
        assertEquals(OperationState.COMPLETED, a.state(), String.valueOf(a.error()));
        int n = engine.rois(doc).size();
        assertTrue(n >= 1, "particles found: " + n);
        RoiManager first = RoiManager.getRawInstance();
        assertNotNull(first);
        first.reset(); // empty, so IJ1 does not ask "Save ROIs?"
        first.close(); // what a macro's run("Close") or the user does: IJ1 nulls the singleton
        assertNull(RoiManager.getRawInstance());
        ActionResult b = cmd("ij1:Analyze>Analyze Particles...", PARTICLES);
        assertEquals(OperationState.COMPLETED, b.state(), String.valueOf(b.error()));
        // the engine list is authoritative: the new manager is reloaded from it (n ROIs) and the second run appends n more,
        // exactly what the recorded macro does (roiManager("reset") + re-add + run)
        assertEquals(2 * n, engine.rois(doc).size(), "the new manager received the reloaded list plus the new particles");
        RoiManager second = RoiManager.getRawInstance();
        assertNotNull(second);
        assertTrue(second != first, "a new singleton, resolved afresh");
        assertEquals(2 * n, second.getCount());
        assertEquals(2 * n, engine.roiLists.syncBack(engine.registry.get(doc)));
    }

    @Test
    void resultsMirrorSurvivesTheResultsWindowAndTableBeingClosed() throws Exception {
        assertEquals(OperationState.COMPLETED, cmd("ij1:Analyze>Measure", Map.of()).state());
        assertEquals(OperationState.COMPLETED, cmd("ij1:Analyze>Measure", Map.of()).state());
        assertEquals(2, engine.results().size());
        // the user closes IJ1's Results window (hidden by the engine, but alive): IJ1 drops and recreates its table
        ij.plugin.filter.Analyzer.setUnsavedMeasurements(false); // the user answers "No" to IJ1's save prompt
        java.awt.Frame f = WindowManager.getFrame("Results");
        if (f instanceof TextWindow tw) tw.close();
        ActionResult m = cmd("ij1:Analyze>Measure", Map.of());
        assertEquals(OperationState.COMPLETED, m.state(), String.valueOf(m.error()));
        assertEquals(ResultsTable.getResultsTable().size(), engine.results().size(),
                "engine rows mirror the live IJ1 table: engine " + engine.results().size() + ", IJ1 " + ResultsTable.getResultsTable().size());
        // and a macro that replaces the system table
        ActionResult code = engine.submit(ActionRequest.code(Origin.PANEL, "ijm",
                "run(\"Clear Results\");\nrun(\"Measure\");\nrun(\"Measure\");\nrun(\"Measure\");\n", TargetScope.of(doc), null)).get(60, TimeUnit.SECONDS);
        if (code.confirmationToken() != null)
            code = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "run(\"Clear Results\");\nrun(\"Measure\");\nrun(\"Measure\");\nrun(\"Measure\");\n",
                    TargetScope.of(doc), code.confirmationToken())).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, code.state(), String.valueOf(code.error()));
        assertEquals(3, ResultsTable.getResultsTable().size());
        assertEquals(3, engine.results().size());
    }

    @Test
    void logWindowClosedByAMacroDoesNotBreakTheNextActionsLogCapture() throws Exception {
        IJ.log("before");
        IJ.selectWindow("Log");
        java.awt.Frame log = WindowManager.getFrame("Log");
        if (log instanceof TextWindow tw) tw.close();
        String macro = "print(\"hello from macro\");\n";
        ActionResult code = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", macro, TargetScope.of(doc), null)).get(60, TimeUnit.SECONDS);
        if (code.confirmationToken() != null)
            code = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", macro, TargetScope.of(doc), code.confirmationToken())).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, code.state(), String.valueOf(code.error()));
        assertTrue(code.digest().newLogLines().contains("hello from macro"), String.valueOf(code.digest().newLogLines()));
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof java.awt.Frame fr && "Log".equals(fr.getTitle())) assertTrue(!fr.isShowing(), "a re-created Log stays hidden");
        }
    }
}
