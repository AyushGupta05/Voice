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
        long resultSeq = seen.stream().filter(e -> e instanceof EngineEvent.HistoryAppended h && h.record().actionId().equals(r.record().actionId()))
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
        // a macro that removes them is mirrored too
        ActionResult cleared = code("roiManager(\"reset\");\n");
        assertEquals(OperationState.COMPLETED, cleared.state(), String.valueOf(cleared.error()));
        assertEquals(0, engine.rois(doc).size());
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
