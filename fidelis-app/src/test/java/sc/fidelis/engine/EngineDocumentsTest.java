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
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        List<DocumentInfo> docs = engine.documents();
        assertEquals(1, docs.size());
        DocumentInfo info = docs.get(0);
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
        assertTrue(engine.documents().isEmpty());
        assertEquals(0, WindowManager.getImageCount());
    }

    @Test
    void openingAMissingFileIsAnIoError() throws Exception {
        DefaultEngine engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/does-not-exist.png")).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.FAILED, r.state());
        assertEquals(ErrorCode.IO_ERROR, r.error().code());
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
