package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HostCanvas;
import sc.fidelis.engine.api.TargetScope;

/** E1: document registry, hidden host windows, focus, close, events. */
class EngineDocumentsTest {

    @Test
    void openRegistersHiddenHostedDocumentAndCloseRemovesIt() throws Exception {
        DefaultEngine engine = EngineFixture.engine();
        int docsBefore = engine.documents().size(); // the engine is shared by the whole test JVM
        int imagesBefore = WindowManager.getImageCount();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        List<DocumentInfo> docs = engine.documents();
        assertEquals(docsBefore + 1, docs.size());
        assertEquals(1, r.digest().added().size());
        DocumentInfo info = docs.stream().filter(d -> d.id().equals(r.digest().added().get(0))).findFirst().orElseThrow();
        assertEquals("two-squares.png", info.title());
        assertEquals(64, info.width());
        assertEquals(64, info.height());
        assertEquals("GRAY8", info.type());
        assertNotNull(info.sourceSha256());
        assertEquals(64, info.sourceSha256().length());
        DocumentId id = info.id();
        assertTrue(engine.hostWindow(id) != null);
        assertFalse(engine.hostWindow(id).isVisible(), "host window must stay hidden");
        assertTrue(engine.imagePlus(id).getCanvas() instanceof HostCanvas);
        assertEquals(id, engine.focused().orElseThrow());
        assertEquals(engine.imagePlus(id), WindowManager.getCurrentImage());

        ActionResult c = engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE,
                Map.of("discard", true), TargetScope.of(id))).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, c.state(), String.valueOf(c.error()));
        assertEquals(docsBefore, engine.documents().size());
        assertEquals(imagesBefore, WindowManager.getImageCount());
    }

    @Test
    void openingAMissingFileIsAnIoError() throws Exception {
        DefaultEngine engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/does-not-exist.png")).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.FAILED, r.state());
        assertEquals(ErrorCode.IO_ERROR, r.error().code());
    }

    @Test
    void transientHelperImagesAreNotAdopted() throws Exception {
        // the UI's before/after copy lives in its own hidden IJ1 window (DocumentView.buildBefore)
        DefaultEngine engine = EngineFixture.engine();
        int before = engine.documents().size();
        ImagePlus helper = IJ.createImage("[before] helper", "8-bit ramp", 40, 30, 1);
        helper.setProperty(HostCanvas.TRANSIENT_PROPERTY, Boolean.TRUE);
        ij.gui.ImageWindow w = Edt.call(() -> {
            ij.gui.ImageWindow x = new ij.gui.ImageWindow(helper, new HostCanvas(helper));
            x.setVisible(false);
            return x;
        });
        try {
            assertTrue(WindowManager.getIDList() != null && java.util.Arrays.stream(WindowManager.getIDList()).anyMatch(i -> i == helper.getID()),
                    "the helper is registered with IJ1 like the UI's copy");
            engine.scheduleReconcile();
            engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                    TargetScope.of(new DocumentId("doc-none")))).get(30, TimeUnit.SECONDS); // barrier behind the reconcile
            assertEquals(before, engine.documents().size(), "a transient helper image must not become a document");

            // never adopted, never counted: not by the synchronous adoption before a native command, not by a native
            // command running while the helper is IJ1's current image, and no event
            java.util.List<sc.fidelis.engine.api.EngineEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            sc.fidelis.engine.api.EngineListener l = events::add;
            engine.addListener(l);
            try {
                WindowManager.setCurrentWindow(w);
                engine.adoptBeforeNative();
                assertEquals(before, engine.documents().size());
                assertTrue(engine.documents().stream().noneMatch(d -> d.title().contains("helper")));
                assertTrue(events.stream().noneMatch(e -> e instanceof sc.fidelis.engine.api.EngineEvent.DocumentsChanged),
                        "adopting nothing must not emit DocumentsChanged: " + events);
            } finally {
                engine.removeListener(l);
            }
        } finally {
            Edt.run(() -> {
                WindowManager.removeWindow(w);
                w.dispose();
            });
            helper.flush();
        }
    }

    @Test
    void imagesCreatedByIj1AreAdoptedAndHidden() throws Exception {
        DefaultEngine engine = EngineFixture.engine();
        ImagePlus imp = IJ.createImage("adopt-me", "8-bit ramp", 40, 30, 1);
        imp.show();
        assertTrue(imp.getWindow().isVisible());
        engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                TargetScope.of(new DocumentId("doc-none")))).get(30, TimeUnit.SECONDS); // fails, but runs on the worker
        engine.scheduleReconcile();
        engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                TargetScope.of(new DocumentId("doc-none")))).get(30, TimeUnit.SECONDS); // barrier behind the reconcile
        DocumentInfo info = engine.documents().stream().filter(d -> d.title().equals("adopt-me")).findFirst().orElseThrow();
        assertFalse(engine.hostWindow(info.id()).isVisible());
        assertTrue(engine.imagePlus(info.id()).getCanvas() instanceof HostCanvas);
        ActionResult c = engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE,
                Map.of("discard", true), TargetScope.of(info.id()))).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, c.state(), String.valueOf(c.error()));
    }
}
