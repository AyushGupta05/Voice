package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Line;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/**
 * E8 item 5: the status bar's "Fiji windows: N" counts windows that are showing outside the workspace (the UI's
 * LegacyWindows rule: a showing Frame or Dialog that is not a hosted document window). Hidden host windows and the
 * hidden Results / ROI Manager / Log windows must not count; a Histogram or Plot a plugin really opens must.
 */
class EngineWindowsTest {
    DefaultEngine engine;
    DocumentId doc;

    /** The UI rule: showing Frame/Dialog, not a document's host window. */
    int visibleLegacyWindows() {
        int n = 0;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !(w instanceof Frame || w instanceof Dialog)) continue;
            boolean host = false;
            for (var d : engine.documents()) if (engine.hostWindow(d.id()) == w) host = true;
            if (!host) n++;
        }
        return n;
    }

    List<String> showingTitles() {
        List<String> t = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w instanceof Frame f) t.add(f.getTitle());
            else if (w instanceof Dialog d) t.add(d.getTitle());
        }
        return t;
    }

    @BeforeEach
    void open() throws Exception {
        engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        doc = engine.documents().get(engine.documents().size() - 1).id();
    }

    @AfterEach
    void close() throws Exception {
        for (Window w : Window.getWindows()) { // special image windows are not documents: close them like a user would
            if (w.isShowing() && w instanceof ImageWindow iw && engine.documents().stream().noneMatch(d -> engine.hostWindow(d.id()) == iw)) iw.close();
        }
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                    TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
    }

    ActionResult cmd(String id, Map<String, Object> params) throws Exception {
        return engine.submit(ActionRequest.command(Origin.PANEL, id, params, TargetScope.of(doc))).get(120, TimeUnit.SECONDS);
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }

    @Test
    void hiddenHostResultsAndRoiManagerWindowsAreNotCounted() throws Exception {
        int n0 = visibleLegacyWindows();
        ActionResult r = cmd("ij1:Analyze>Measure", m());
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        engine.submit(ActionRequest.direct(Origin.PANEL, ActionRequest.Operations.SET_THRESHOLD, m("lower", 128, "upper", 255), TargetScope.of(doc)))
                .get(30, TimeUnit.SECONDS);
        ActionResult ap = cmd("ij1:Analyze>Analyze Particles...", m("size", "0-Infinity", "circularity", "0.00-1.00", "show", "Nothing",
                "display", true, "exclude", false, "clear", false, "include", false, "summarize", false, "overlay", false, "add", true,
                "composite", false, "record", false, "in_situ", false));
        assertEquals(OperationState.COMPLETED, ap.state(), String.valueOf(ap.error()));
        // the Results table and the ROI Manager exist in IJ1 but are kept hidden
        assertTrue(ij.measure.ResultsTable.getResultsTable().size() > 0);
        for (var res : List.of(r, ap)) {
            assertFalse(res.digest().windowsOpened().contains("Results"), res.digest().windowsOpened().toString());
            assertFalse(res.digest().windowsOpened().contains("ROI Manager"), res.digest().windowsOpened().toString());
        }
        assertFalse(showingTitles().contains("Results"), showingTitles().toString());
        assertFalse(showingTitles().contains("ROI Manager"), showingTitles().toString());
        assertFalse(showingTitles().contains("Log"));
        assertEquals(n0, visibleLegacyWindows(), "hidden host, Results and ROI Manager windows are not visible legacy windows: " + showingTitles());
    }

    @Test
    void aHistogramAPluginOpensIsCounted() throws Exception {
        int n0 = visibleLegacyWindows();
        ActionResult r = cmd("ij1:Analyze>Histogram", m("bins", 256, "use", false, "x_min", 0, "x_max", 256, "y_max", "Auto", "stack", false));
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals(n0 + 1, visibleLegacyWindows(), showingTitles().toString());
        assertTrue(r.digest().windowsOpened().stream().anyMatch(t -> t.startsWith("Histogram of")), "digest reports it: " + r.digest().windowsOpened());
        assertTrue(showingTitles().stream().anyMatch(t -> t.startsWith("Histogram of")));
    }

    @Test
    void aPlotAPluginOpensIsCounted() throws Exception {
        int n0 = visibleLegacyWindows();
        engine.imagePlus(doc).setRoi(new Line(2, 2, 60, 40));
        ActionResult r = cmd("ij1:Analyze>Plot Profile", m());
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals(n0 + 1, visibleLegacyWindows(), showingTitles().toString());
        assertTrue(r.digest().windowsOpened().stream().anyMatch(t -> t.startsWith("Plot of")), "digest reports it: " + r.digest().windowsOpened());
    }
}
