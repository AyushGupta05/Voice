package sc.fidelis.verification;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.databind.JsonNode;

import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.ui.DocumentView;
import sc.fidelis.ui.EngineProvider;
import sc.fidelis.ui.Theme;
import sc.fidelis.ui.Workspace;

/**
 * Scenario for {@link WorkspaceJourneyTest}, kept out of the test class so that no ij.* class is loaded
 * before FijiFixture has run the ImageJ1 patcher (the ContractSmokeTest rule).
 *
 * <p>The workspace is built exactly as the application builds it, around {@link EngineProvider#get}: the
 * registered product engine when there is one, else the UI's development stub (which runs real
 * {@code IJ.run} on a worker). Swapping in the product engine therefore needs no change here.
 */
final class JourneyScenario {
    private static Workspace ws;
    private static boolean productEngine;

    private JourneyScenario() {}

    static void start() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            productEngine = EngineProvider.hasRegistered();
            Engine engine = EngineProvider.get(null);
            ws = Workspace.create(engine, null, new Dimension(1280, 800));
            ws.frame().setVisible(true);
        });
        waitFor("engine ready", () -> ws.engine().state() == EngineState.READY, 60);
    }

    static void stop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (ws != null) ws.frame().dispose();
        });
        ws = null;
    }

    static String engineName() {
        return ws.engine().getClass().getName() + (productEngine ? " (registered product engine)" : " (UI development stub)");
    }

    static String exportMacro() {
        return ws.engine().exportMacro();
    }

    /** Number of history records so far (the shared engine carries every earlier test's steps). */
    static int historySize() {
        return ws.engine().history().size();
    }

    /** The macro of the records added since {@code since}: the product engine exports a session slice. */
    static String exportMacroSince(int since) {
        Engine e = ws.engine();
        return e instanceof sc.fidelis.engine.DefaultEngine d ? d.exportMacroSince(since) : e.exportMacro();
    }

    /** The live session as the engine reports it, with the IJ1 globals read directly. */
    static Snapshot liveSnapshot(sc.fidelis.engine.api.DocumentId original) {
        Snapshot s = J12Session.snapshot(ws.engine(), original, List.of(), productEngine);
        return new Snapshot(s.images(), s.results(), s.rois(), Snapshot.capture().globals());
    }

    /** Run J-12 on the workspace's engine, checking the UI's Results panel after Measure and Analyze Particles. */
    static J12Session.Run journey(String fixtureId, Path tmp, Path baseline, List<String> uiFailures) throws Exception {
        return J12Session.run(ws.engine(), fixtureId, tmp, productEngine, tag -> {
            try {
                if (tag.equals("s01_open")) {
                    waitFor("document view for the opened image", () -> ws.documents().focusedView() != null, 30);
                    DocumentView view = ws.documents().focusedView();
                    if (!SwingUtilities.isDescendingFrom(view.canvas(), ws.frame())) {
                        uiFailures.add("the hosted IJ1 canvas is not inside the workspace frame");
                    }
                    String want = BaselineCompare.loadStep(baseline, "s01_open").get("images").get(0)
                            .get("allPlanesSha256").asText();
                    String got = sc.fidelis.testkit.PixelHash.sha256(view.imagePlus());
                    if (!want.equals(got)) uiFailures.add("the viewer's ImagePlus differs from the baseline after open");
                }
                if (tag.equals("s08_measure") || tag.equals("s09_analyze_particles")) {
                    checkResultsPanel(tag, baseline, uiFailures);
                }
            } catch (Exception e) {
                uiFailures.add(tag + " UI check failed: " + e);
            }
        });
    }

    // ---- Results panel: copy-as-TSV must reproduce the doubles exactly ---------------------------

    private static void checkResultsPanel(String tag, Path baseline, List<String> out) throws Exception {
        JsonNode results = BaselineCompare.loadStep(baseline, tag).get("results");
        int rows = results.get("rows").asInt();
        Object panel = findResultsPanel(ws.frame());
        if (panel == null) {
            out.add(tag + " ResultsPanel not found in the workspace");
            return;
        }
        Method tsv = panel.getClass().getDeclaredMethod("tsv"); // package-private in sc.fidelis.ui
        tsv.setAccessible(true);
        String[] lines = null;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < end) {
            String[] text = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    text[0] = (String) tsv.invoke(panel);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            lines = text[0].split("\n");
            if (lines.length - 1 >= rows) break;
            Thread.sleep(50);
        }
        if (lines == null || lines.length - 1 != rows) {
            out.add(tag + " Results panel TSV has " + (lines == null ? 0 : lines.length - 1) + " rows, baseline " + rows
                    + " (the panel is allowed no extra rows here: the table is cleared by Analyze Particles)");
            return;
        }
        String[] header = lines[0].split("\t", -1);
        for (JsonNode col : results.get("columns")) {
            if (col.has("labels")) continue;
            String name = col.get("name").asText();
            int idx = List.of(header).indexOf(name);
            if (idx < 0) {
                out.add(tag + " TSV column '" + name + "' missing; header " + List.of(header));
                continue;
            }
            for (int r = 0; r < rows; r++) {
                String cell = lines[r + 1].split("\t", -1)[idx];
                double got = Double.parseDouble(cell);
                double want = Double.parseDouble(col.get("values").get(r).asText());
                if (Double.doubleToLongBits(got) != Double.doubleToLongBits(want)) {
                    out.add(tag + " TSV " + name + "[" + r + "]: '" + cell + "' parses to " + got + ", baseline " + want);
                    break;
                }
            }
        }
        System.out.println("[journey] " + tag + ": Results panel TSV header " + List.of(header) + ", " + rows
                + " row(s) round-trip exactly through Double.parseDouble");
    }

    private static Object findResultsPanel(Container root) {
        List<Component> stack = new ArrayList<>(List.of(root.getComponents()));
        while (!stack.isEmpty()) {
            Component c = stack.remove(stack.size() - 1);
            if (c.getClass().getName().equals("sc.fidelis.ui.ResultsPanel")) return c;
            if (c instanceof Container k) stack.addAll(List.of(k.getComponents()));
        }
        return null;
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
