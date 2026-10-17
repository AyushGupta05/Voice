package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.Menus;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/** Cancellation, rollback, native dialog wait and event ordering. */
class EngineLifecycleTest {
    static final String LOOP = "for (i = 0; i < 2000000000; i++) { x = i; }";
    DefaultEngine engine;
    DocumentId doc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        engine = EngineFixture.engine();
        Menus.getCommands().put("Fidelis Dialog", "sc.fidelis.engine.FailingPlugins$Dialoging");
        engine.refreshCatalogue();
        engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        doc = engine.documents().get(engine.documents().size() - 1).id();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                    TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }

    private static int num(String opId) {
        return Integer.parseInt(opId.substring(3));
    }

    @Test
    void eventsFollowTheDocumentedOrder() throws Exception {
        List<EngineEvent> seen = new CopyOnWriteArrayList<>();
        EngineListener l = seen::add;
        engine.addListener(l);
        ActionResult r = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Edit>Invert", m(), TargetScope.of(doc))).get(60, TimeUnit.SECONDS);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        engine.removeListener(l);
        assertEquals(OperationState.COMPLETED, r.state());
        long last = 0;
        int running = -1;
        int terminal = -1;
        int digest = -1;
        int hist = -1;
        for (int i = 0; i < seen.size(); i++) {
            EngineEvent e = seen.get(i);
            assertTrue(e.seq() > last, "sequence must be strictly increasing");
            last = e.seq();
            if (e instanceof EngineEvent.OperationStateChanged s && s.operationId().equals(r.operationId())) {
                if (s.state() == OperationState.RUNNING) running = i;
                if (s.state() == OperationState.COMPLETED) terminal = i;
            }
            if (e instanceof EngineEvent.StateDigestEvent) digest = i;
            if (e instanceof EngineEvent.HistoryAppended) hist = i;
        }
        assertTrue(running >= 0 && running < terminal && terminal < digest && digest < hist,
                "RUNNING " + running + " < terminal " + terminal + " < digest " + digest + " < history " + hist);
    }

    private DocumentId flatImage() throws Exception {
        ActionResult n = engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.NEW_IMAGE,
                m("title", "flat", "type", "8-bit", "width", 32, "height", 32, "slices", 1, "fill", "White"), null)).get(30, TimeUnit.SECONDS);
        return n.digest().added().get(0);
    }

    @Test
    void failedPostconditionRollsBackFromTheSnapshot() throws Exception {
        // A genuine silent failure: a CommandListener vetoes the command (returns null), so ImageJ never starts it
        // and raises no error. The engine detects "did not run" through its tail listener and rolls back.
        DocumentId flat = flatImage();
        String h0 = Pixels.hash(engine.imagePlus(flat), false);
        ij.CommandListener veto = c -> c.equals("Invert") ? null : c;
        ij.Executer.addCommandListener(veto);
        ActionResult r;
        try {
            r = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Edit>Invert", m(), TargetScope.of(flat))).get(60, TimeUnit.SECONDS);
        } finally {
            ij.Executer.removeCommandListener(veto);
        }
        assertEquals(ErrorCode.POSTCONDITION_FAILED, r.error().code(), String.valueOf(r.error()));
        assertEquals("NOT_RUN", r.error().subcode());
        assertEquals(OperationState.ROLLED_BACK, r.state());
        assertEquals(h0, Pixels.hash(engine.imagePlus(flat), false));
        assertEquals(OperationState.ROLLED_BACK, r.record().outcome());
        assertTrue(engine.exportMacro().contains("not applied (ROLLED_BACK)"));
    }

    @Test
    void identityFiltersAreCompletedNoOpsWithoutAnUndoSnapshot() throws Exception {
        // Blur of a flat image is an identity in Fiji, not failures.
        DocumentId flat = flatImage();
        String h0 = Pixels.hash(engine.imagePlus(flat), false);
        ActionResult r = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Process>Filters>Gaussian Blur...",
                m("sigma", 2), TargetScope.of(flat))).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals(h0, Pixels.hash(engine.imagePlus(flat), false));
        assertTrue(r.record().errorSummary().startsWith("no-op:"), String.valueOf(r.record().errorSummary()));
        assertTrue(r.digest().changed().isEmpty(), "no document change is reported");
        ActionResult u = engine.submit(ActionRequest.direct(Origin.PANEL, ActionRequest.Operations.UNDO, m(), TargetScope.of(flat)))
                .get(30, TimeUnit.SECONDS);
        assertEquals(ErrorCode.PRECONDITION_FAILED, u.error().code(), "a no-op leaves nothing to undo");
        // a command that really changes pixels carries no note
        ActionResult inv = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Edit>Invert", m(), TargetScope.of(flat)))
                .get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, inv.state());
        assertEquals(null, inv.record().errorSummary());
        assertNotEquals(h0, Pixels.hash(engine.imagePlus(flat), false));
    }

    @Test
    void anErrorSignalStillFailsAnUnchangedImage() throws Exception {
        // Smooth with a selection wholly outside the image throws inside the plugin (ArrayIndexOutOfBounds): the
        // Exception window is an error signal, pixels are unchanged, and the action must fail.
        DocumentId flat = flatImage();
        engine.imagePlus(flat).setRoi(new ij.gui.Roi(100, 100, 5, 5));
        ActionResult r = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Process>Smooth", m(), TargetScope.of(flat)))
                .get(60, TimeUnit.SECONDS);
        assertEquals(ErrorCode.EXECUTION_FAILED, r.error().code(), String.valueOf(r.error()));
        assertEquals(OperationState.ROLLED_BACK, r.state());
    }

    @Test
    void runningMacroCanBeCancelled() throws Exception {
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", LOOP, TargetScope.of(doc), null)).get(30, TimeUnit.SECONDS);
        CompletableFuture<ActionResult> f = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", LOOP, TargetScope.of(doc),
                first.confirmationToken()));
        Thread.sleep(1500);
        assertTrue(!f.isDone(), "the macro should still be running");
        engine.cancel("op-" + (num(first.operationId()) + 1));
        ActionResult r = f.get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.CANCELLED, r.state(), String.valueOf(r.error()));
        assertEquals("MACRO_ABORTED", r.error().subcode());
    }

    @Test
    void cancelBeforeStartCancelsQueuedOperation() throws Exception {
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", LOOP, TargetScope.of(doc), null)).get(30, TimeUnit.SECONDS);
        CompletableFuture<ActionResult> blocker = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", LOOP, TargetScope.of(doc),
                first.confirmationToken()));
        CompletableFuture<ActionResult> queued = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Edit>Invert", m(), TargetScope.of(doc)));
        Thread.sleep(500);
        engine.cancel("op-" + (num(first.operationId()) + 2));
        engine.cancel("op-" + (num(first.operationId()) + 1));
        assertEquals(OperationState.CANCELLED, queued.get(60, TimeUnit.SECONDS).state());
        blocker.get(60, TimeUnit.SECONDS);
    }

    @Test
    void nativeDialogIsReportedAndCancellable() throws Exception {
        CountDownLatch pending = new CountDownLatch(1);
        List<EngineEvent> seen = new CopyOnWriteArrayList<>();
        EngineListener l = e -> {
            seen.add(e);
            if (e instanceof EngineEvent.DialogPending) pending.countDown();
        };
        engine.addListener(l);
        CompletableFuture<ActionResult> f = engine.submit(ActionRequest.command(Origin.MENU, "ij1:Unlisted>Fidelis Dialog", m(), TargetScope.of(doc)));
        assertTrue(pending.await(30, TimeUnit.SECONDS), "DialogPending expected");
        EngineEvent.DialogPending dp = (EngineEvent.DialogPending) seen.stream().filter(e -> e instanceof EngineEvent.DialogPending).findFirst().orElseThrow();
        assertEquals("Fidelis test dialog", dp.windowTitle());
        engine.cancel(dp.operationId());
        ActionResult r = f.get(60, TimeUnit.SECONDS);
        engine.removeListener(l);
        assertEquals(OperationState.CANCELLED, r.state(), String.valueOf(r.error()));
        assertTrue(seen.stream().anyMatch(e -> e instanceof EngineEvent.OperationStateChanged s && s.state() == OperationState.WAITING_FOR_DIALOG));
    }
}
