package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.plugin.frame.RoiManager;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/**
 * Effects of CODE actions the workspace relies on: the capability scan names only what the macro really calls (the
 * confirmation sheet), Log additions arrive as live {@link EngineEvent.LogLine} events (the editor console and the Log
 * tab), and ROI Manager entries a macro adds reach the document's ROI list as they do after a native command.
 */
class EngineCodeEffectsTest {
    DefaultEngine engine;
    DocumentId doc;
    final List<EngineEvent> seen = new CopyOnWriteArrayList<>();
    final EngineListener listener = seen::add;

    @BeforeEach
    void setUp() throws Exception {
        engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        doc = r.digest().added().get(0);
        engine.focus(doc);
        engine.addListener(listener);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.removeListener(listener);
        RoiManager rm = RoiManager.getRawInstance();
        if (rm != null) rm.reset();
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, Map.of("discard", true), TargetScope.of(d.id())))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    // ---- capability scan ------------------------------------------------------------------------------

    @Test
    void scanNamesOnlyCapabilitiesTheMacroCalls() {
        assertEquals(List.of(), CodeRunner.scan("print(\"hello\");\n"), "a plain print uses nothing");
        assertEquals(List.of(), CodeRunner.scan("// exec(\"rm -rf\") in a comment\nrun(\"Invert\");\n"), "comments do not count");
        assertEquals(List.of(), CodeRunner.scan("print(\"call this eval later, then exec and runMacro\");\n"), "string contents do not count");
        assertEquals(List.of(), CodeRunner.scan("exec = 3;\nmyexec(1);\nx = File.exists(\"p\");\n"), "a variable, another word, another File function");
        assertEquals(List.of("exec"), CodeRunner.scan("exec(\"ls\");\n"));
        assertEquals(List.of("eval"), CodeRunner.scan("eval(\"script\", \"1+1\");\n"));
        assertEquals(List.of("call"), CodeRunner.scan("call(\"ij.IJ.freeMemory\");\n"));
        assertEquals(List.of("runMacro"), CodeRunner.scan("runMacro(\"other.ijm\");\n"));
        assertEquals(List.of("File.delete"), CodeRunner.scan("File.delete(getDirectory(\"temp\") + \"x\");\n"));
        assertEquals(List.of("exec", "File.delete", "File.saveString"),
                CodeRunner.scan("File.saveString(\"a\", p);\nexec(\"ls\");\n// File.copy\nFile.delete(p);\n"),
                "in the fixed order of the capability list, each once");
        assertEquals(List.of(), CodeRunner.scan("run(\"Subtract Background...\", \"rolling=50\");\nsetAutoThreshold(\"Otsu dark\");\nrun(\"Convert to Mask\");\n"),
                "the J-12 macro");
    }

    @Test
    void confirmationListsOnlyRealCapabilities() throws Exception {
        ActionResult plain = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "print(\"exec eval call runMacro\");\n", TargetScope.of(doc), null))
                .get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.AWAITING_CONFIRMATION, plain.state());
        assertEquals(ErrorCode.CAPABILITY_CONFIRMATION_REQUIRED, plain.error().code());
        assertEquals("", plain.error().context().get("capabilities"), String.valueOf(plain.error().context()));
        assertFalse(plain.error().message().contains("uses"), plain.error().message());

        ActionResult shell = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "exec(\"ls\");\n", TargetScope.of(doc), null)).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.AWAITING_CONFIRMATION, shell.state());
        assertEquals("exec", shell.error().context().get("capabilities"));
        assertTrue(shell.error().message().contains("(uses exec)"), shell.error().message());
    }

    // ---- Log lines -------------------------------------------------------------------------------------

    @Test
    void macroPrintsArriveAsLiveLogLinesOnceEachAndNativeLogToo() throws Exception {
        String nonce = Long.toHexString(System.nanoTime());
        String one = "live line one " + nonce;
        String two = "live line two " + nonce;
        ActionResult r = code("print(\"" + one + "\");\nwait(400);\nprint(\"" + two + "\");\n");
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals(List.of(one, two), r.digest().newLogLines(), "the digest still carries the run's lines");
        waitFor("both lines as LogLine events", () -> logLines().contains(one) && logLines().contains(two), 10);
        // the engine flushes the Log before completing the result: both events were sequenced before the run's HistoryAppended
        // (events reach listeners on the EDT, so the record's event is awaited rather than assumed delivered)
        String actionId = r.record().actionId();
        waitFor("the run's HistoryAppended event", () -> seen.stream().anyMatch(e -> e instanceof EngineEvent.HistoryAppended h
                && h.record().actionId().equals(actionId)), 10);
        long resultSeq = seen.stream().filter(e -> e instanceof EngineEvent.HistoryAppended h && h.record().actionId().equals(actionId))
                .mapToLong(EngineEvent::seq).findFirst().orElseThrow();
        for (EngineEvent e : seen) {
            if (e instanceof EngineEvent.LogLine l && (l.line().equals(one) || l.line().equals(two)))
                assertTrue(l.seq() < resultSeq, "LogLine " + l.line() + " (seq " + l.seq() + ") before the result (seq " + resultSeq + ")");
        }
        Thread.sleep(600); // two more polls: nothing is reported twice
        assertEquals(1, logLines().stream().filter(one::equals).count(), String.valueOf(logLines()));
        assertEquals(1, logLines().stream().filter(two::equals).count(), String.valueOf(logLines()));

        String nativeLine = "native log line " + nonce;
        IJ.log(nativeLine); // a plugin or a person outside any engine action
        waitFor("the native line as a LogLine event", () -> logLines().contains(nativeLine), 5);
        IJ.log("\\Update:" + nativeLine + " updated");
        waitFor("the rewritten line reported with its new text", () -> logLines().contains(nativeLine + " updated"), 5);
        IJ.log("\\Clear");
        IJ.log("after clear " + nonce);
        waitFor("a line written after \\Clear", () -> logLines().contains("after clear " + nonce), 5);
        assertEquals(1, logLines().stream().filter(nativeLine::equals).count(), "no line is re-reported after a clear: " + logLines());
        // a cleared Log that is shorter than before is a new generation: a first line equal to the old first line is new
        IJ.log("second " + nonce);
        IJ.log("third " + nonce);
        waitFor("three lines mirrored", () -> logLines().contains("third " + nonce), 5);
        IJ.log("\\Clear");
        IJ.log("after clear " + nonce);
        waitFor("the repeated first line reported again", () -> logLines().stream().filter(("after clear " + nonce)::equals).count() == 2, 5);
    }

    // ---- ROI list after CODE ---------------------------------------------------------------------------

    @Test
    void roiManagerEntriesAddedByAMacroReachTheDocumentsRoiList() throws Exception {
        assertEquals(0, engine.rois(doc).size());
        ActionResult r = code("roiManager(\"reset\");\nmakeRectangle(2, 3, 6, 5);\nroiManager(\"Add\");\n"
                + "makeRectangle(12, 11, 4, 7);\nroiManager(\"Add\");\nrun(\"Select None\");\n");
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        List<RoiGeometry> rois = engine.rois(doc);
        assertEquals(2, rois.size(), "the macro's two manager entries are the document's ROI list");
        assertEquals(List.of(2.0, 3.0, 6.0, 5.0), bounds(rois.get(0)));
        assertEquals(List.of(12.0, 11.0, 4.0, 7.0), bounds(rois.get(1)));
        assertTrue(r.digest().changed().stream().anyMatch(c -> c.id().equals(doc) && c.roiChanged()),
                "the digest reports the ROI change: " + r.digest().changed());
        // a restyle changes no geometry and no count, but IJ1 measures lines with their stroke width: it is a change
        ActionResult restyled = code("roiManager(\"Select\", 0);\nroiManager(\"Set Line Width\", 5);\n");
        assertEquals(OperationState.COMPLETED, restyled.state(), String.valueOf(restyled.error()));
        assertTrue(restyled.digest().changed().stream().anyMatch(c -> c.id().equals(doc) && c.roiChanged()), "restyle reported: " + restyled.digest().changed());
        synchronized (engine.registry.get(doc).roiList) {
            assertEquals(5.0f, engine.registry.get(doc).roiList.get(0).getStrokeWidth(), "the document's ROI carries the new width");
        }
        // a macro that removes them is mirrored too
        ActionResult cleared = code("roiManager(\"reset\");\n");
        assertEquals(OperationState.COMPLETED, cleared.state(), String.valueOf(cleared.error()));
        assertEquals(0, engine.rois(doc).size());
    }

    @Test
    void aManagerSelectionSurvivesIntoTheNextMacroAndAClosedDocumentsEntriesDoNotFlowIntoANewOne() throws Exception {
        ActionResult two = code("makeRectangle(1, 1, 3, 3);\nroiManager(\"Add\");\nmakeRectangle(6, 6, 2, 2);\nroiManager(\"Add\");\n"
                + "run(\"Select None\");\nroiManager(\"Select\", 0);\n");
        assertEquals(OperationState.COMPLETED, two.state(), String.valueOf(two.error()));
        assertEquals(2, engine.rois(doc).size());
        // the manager still mirrors this document: it is not reloaded, so the selection stands and Delete removes one
        ActionResult del = code("roiManager(\"Delete\");\n");
        assertEquals(OperationState.COMPLETED, del.state(), String.valueOf(del.error()));
        assertEquals(1, engine.rois(doc).size(), "Delete removed the selected entry only: " + engine.rois(doc));
        assertEquals(List.of(6.0, 6.0, 2.0, 2.0), bounds(engine.rois(doc).get(0)));
        // close the document: its entry stays in IJ1's manager, but a macro that starts with no document gets an empty one
        engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, Map.of("discard", true), TargetScope.of(doc))).get(30, TimeUnit.SECONDS);
        assertTrue(engine.documents().isEmpty());
        String text = "open(\"" + EngineFixture.repoFile("fixtures/two-squares.png").toString().replace("\\", "/") + "\");\n"
                + "makeRectangle(2, 2, 4, 4);\nroiManager(\"Add\");\nrun(\"Select None\");\n";
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, null, null)).get(60, TimeUnit.SECONDS);
        ActionResult opened = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, null, first.confirmationToken())).get(120, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, opened.state(), String.valueOf(opened.error()));
        assertEquals(1, engine.documents().size(), "the macro's open() produced the document");
        DocumentId b = engine.documents().get(0).id();
        doc = b; // for tearDown
        assertEquals(1, engine.rois(b).size(), "only the macro's own rectangle: " + engine.rois(b));
        assertEquals(List.of(2.0, 2.0, 4.0, 4.0), bounds(engine.rois(b).get(0)));
    }

    @Test
    void unnamedDocumentRoisKeepTheManagerSelectionAcrossRunsAndABatchMacroInAnEmptySessionIsSynced() throws Exception {
        // ROIs the workspace put into the list without names (IJ1 names them when it adds them to its manager)
        Doc d = engine.registry.get(doc);
        synchronized (d.roiList) {
            d.roiList.add(new ij.gui.Roi(1, 1, 3, 3));
            d.roiList.add(new ij.gui.Roi(6, 6, 2, 2));
        }
        ActionResult select = code("roiManager(\"Select\", 0);\n"); // changes no content
        assertEquals(OperationState.COMPLETED, select.state(), String.valueOf(select.error()));
        assertEquals(2, engine.rois(doc).size());
        ActionResult del = code("roiManager(\"Delete\");\n"); // the next run must not reload and lose the selection
        assertEquals(OperationState.COMPLETED, del.state(), String.valueOf(del.error()));
        assertEquals(1, engine.rois(doc).size(), "only the selected entry was deleted: " + engine.rois(doc));
        assertEquals(List.of(6.0, 6.0, 2.0, 2.0), bounds(engine.rois(doc).get(0)));
        // an empty session without any registered manager: a batch-mode macro would write to IJ1's private manager
        engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, Map.of("discard", true), TargetScope.of(doc))).get(30, TimeUnit.SECONDS);
        RoiManager rm = RoiManager.getRawInstance();
        if (rm != null) sc.fidelis.engine.Edt.run(rm::close);
        assertTrue(RoiManager.getRawInstance() == null, "no registered ROI Manager");
        String text = "setBatchMode(true);\nnewImage(\"batch-made\", \"8-bit black\", 20, 20, 1);\nmakeRectangle(1, 1, 5, 5);\n"
                + "roiManager(\"Add\");\nrun(\"Select None\");\nsetBatchMode(false);\n";
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, null, null)).get(60, TimeUnit.SECONDS);
        ActionResult made = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, null, first.confirmationToken())).get(120, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, made.state(), String.valueOf(made.error()));
        assertEquals(1, engine.documents().size(), "the batch macro's image became a document");
        doc = engine.documents().get(0).id(); // for tearDown
        assertEquals(1, engine.rois(doc).size(), "the batch macro's manager entry reached the document: " + engine.rois(doc));
        assertEquals(List.of(1.0, 1.0, 5.0, 5.0), bounds(engine.rois(doc).get(0)));
    }

    @Test
    void recreatedIdenticalRoisKeepTheSelectionAndAClosedManagersEditsReachTheDocument() throws Exception {
        String make = "makeRectangle(1, 1, 3, 3);\nroiManager(\"Add\");\nmakeRectangle(6, 6, 2, 2);\nroiManager(\"Add\");\nrun(\"Select None\");\n";
        assertEquals(OperationState.COMPLETED, code(make).state());
        // the same two ROIs recreated: no content change to report, but the document must take the new objects
        ActionResult again = code("roiManager(\"reset\");\n" + make + "roiManager(\"Select\", 0);\n");
        assertEquals(OperationState.COMPLETED, again.state(), String.valueOf(again.error()));
        assertTrue(again.digest().changed().isEmpty(), "same content, nothing to report: " + again.digest().changed());
        ActionResult del = code("roiManager(\"Delete\");\n");
        assertEquals(OperationState.COMPLETED, del.state(), String.valueOf(del.error()));
        assertEquals(1, engine.rois(doc).size(), "the selection survived into the next run: " + engine.rois(doc));
        // a macro that closes the manager window: its last content still reaches the document
        ActionResult closedAfterReset = code("roiManager(\"reset\");\nclose(\"ROI Manager\");\n");
        assertEquals(OperationState.COMPLETED, closedAfterReset.state(), String.valueOf(closedAfterReset.error()));
        assertEquals(0, engine.rois(doc).size(), "the reset before the close reached the document");
        ActionResult closedAfterAdd = code("makeRectangle(4, 4, 2, 2);\nroiManager(\"Add\");\nrun(\"Select None\");\nclose(\"ROI Manager\");\n");
        assertEquals(OperationState.COMPLETED, closedAfterAdd.state(), String.valueOf(closedAfterAdd.error()));
        assertEquals(1, engine.rois(doc).size(), "the addition before the close reached the document");
        assertEquals(List.of(4.0, 4.0, 2.0, 2.0), bounds(engine.rois(doc).get(0)));
    }

    @Test
    void mirroringTheDocumentIntoTheManagerKeepsUnboundPositionsAndStyle() throws Exception {
        ActionResult n = engine.submit(ActionRequest.session(Origin.PANEL, Operations.NEW_IMAGE, new java.util.LinkedHashMap<>(
                Map.of("title", "stack-for-rois", "type", "8-bit", "width", 16, "height", 16, "fill", "Black", "slices", 3)), null))
                .get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, n.state(), String.valueOf(n.error()));
        DocumentId stack = n.digest().added().get(0);
        engine.focus(stack);
        engine.imagePlus(stack).setSlice(2);
        Doc d = engine.registry.get(stack);
        ij.gui.Roi unbound = new ij.gui.Roi(2, 2, 5, 5);
        unbound.setStrokeWidth(3);
        synchronized (d.roiList) {
            d.roiList.add(unbound);
        }
        ActionResult print = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "print(\"touch nothing\");\n", TargetScope.of(stack), null)).get(60, TimeUnit.SECONDS);
        print = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "print(\"touch nothing\");\n", TargetScope.of(stack), print.confirmationToken())).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, print.state(), String.valueOf(print.error()));
        assertTrue(print.digest().changed().isEmpty(), "a print changes no ROI: " + print.digest().changed());
        synchronized (d.roiList) {
            assertEquals(1, d.roiList.size());
            assertEquals(0, d.roiList.get(0).getPosition(), "an ROI without a plane stays without one (IJ1's add would bind it to slice 2)");
            assertFalse(d.roiList.get(0).hasHyperStackPosition());
            assertEquals(3.0f, d.roiList.get(0).getStrokeWidth(), "the document's stroke width stands");
        }
        assertEquals(List.of(0, 0, 0), List.of(engine.rois(stack).get(0).position()[0], engine.rois(stack).get(0).position()[1], engine.rois(stack).get(0).position()[2]));
    }

    @Test
    void anUntargetedMacroWorksOnTheCurrentDocumentsListNotTheLastLoadedOne() throws Exception {
        DocumentId a = doc;
        ActionResult onA = code("makeRectangle(1, 1, 3, 3);\nroiManager(\"Add\");\nrun(\"Select None\");\n");
        assertEquals(OperationState.COMPLETED, onA.state(), String.valueOf(onA.error()));
        assertEquals(1, engine.rois(a).size());
        ActionResult opened = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, opened.state(), String.valueOf(opened.error()));
        DocumentId b = opened.digest().added().get(0);
        engine.focus(b);
        // no target: the manager is loaded from the document that is current (B, empty), not left with A's entry
        String text = "makeRectangle(5, 5, 2, 2);\nroiManager(\"Add\");\nrun(\"Select None\");\n";
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, null, null)).get(60, TimeUnit.SECONDS);
        ActionResult onB = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, null, first.confirmationToken())).get(120, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, onB.state(), String.valueOf(onB.error()));
        assertEquals(1, engine.rois(b).size(), "B holds only its own rectangle: " + engine.rois(b));
        assertEquals(List.of(5.0, 5.0, 2.0, 2.0), bounds(engine.rois(b).get(0)));
        assertEquals(1, engine.rois(a).size(), "A keeps its list untouched");
        assertEquals(List.of(1.0, 1.0, 3.0, 3.0), bounds(engine.rois(a).get(0)));
    }

    // ---- stale macro interpreter -----------------------------------------------------------------------

    @Test
    void aFinishedInterpreterIjStillNamesIsClearedButARunningMacroKeepsIts() throws Exception {
        // what Fiji's start-up leaves behind: Interpreter.instance set, no macro thread anywhere
        java.lang.reflect.Field f = ij.macro.Interpreter.class.getDeclaredField("instance");
        f.setAccessible(true);
        synchronized (MacroState.class) { // the observer's sweep (clearStale is synchronized on the class) waits for this block
            f.set(null, new ij.macro.Interpreter());
            assertTrue(IJ.isMacro(), "IJ reports a macro while the stale reference stands");
        }
        waitFor("the engine to clear the stale interpreter", () -> ij.macro.Interpreter.getInstance() == null, 5);
        assertFalse(IJ.isMacro());
        // a macro that is running is never touched: its instance stays set until it ends
        java.util.concurrent.Future<ActionResult> running = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "wait(1500);\n", TargetScope.of(doc), null));
        ActionResult first = running.get(60, TimeUnit.SECONDS);
        running = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "wait(1500);\n", TargetScope.of(doc), first.confirmationToken()));
        waitFor("the macro to start", () -> ij.macro.Interpreter.getInstance() != null, 10);
        Thread.sleep(700); // three sweeps while the macro waits
        assertNotNull(ij.macro.Interpreter.getInstance(), "the running macro's interpreter is left alone");
        assertTrue(IJ.isMacro());
        assertEquals(OperationState.COMPLETED, running.get(60, TimeUnit.SECONDS).state());
        waitFor("no interpreter after the macro", () -> ij.macro.Interpreter.getInstance() == null, 5);
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private ActionResult code(String text) throws Exception {
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, TargetScope.of(doc), null)).get(60, TimeUnit.SECONDS);
        assertNotNull(first.confirmationToken(), String.valueOf(first.error()));
        return engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, TargetScope.of(doc), first.confirmationToken())).get(120, TimeUnit.SECONDS);
    }

    private List<String> logLines() {
        return seen.stream().filter(e -> e instanceof EngineEvent.LogLine).map(e -> ((EngineEvent.LogLine) e).line()).toList();
    }

    private static List<Double> bounds(RoiGeometry g) {
        double[] b = g.bounds();
        return List.of(b[0], b[1], b[2], b[3]);
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
