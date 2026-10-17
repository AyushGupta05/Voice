package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.Menus;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/** E3: failures IJ1 does not report through return values (Exception window, MessageDialog, IJ.error). */
class EngineErrorTest {
    DefaultEngine engine;
    DocumentId doc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        engine = EngineFixture.engine();
        Menus.getCommands().put("Fidelis Throw", "sc.fidelis.engine.FailingPlugins$Throwing");
        Menus.getCommands().put("Fidelis Message", "sc.fidelis.engine.FailingPlugins$Messaging");
        Menus.getCommands().put("Fidelis Error", "sc.fidelis.engine.FailingPlugins$Erroring");
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

    ActionResult run(String label) throws Exception {
        return engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Unlisted>" + label, Map.of(), TargetScope.of(doc)))
                .get(60, TimeUnit.SECONDS);
    }

    @Test
    void pluginExceptionIsCapturedFromTheExceptionWindow() throws Exception {
        ActionResult r = run("Fidelis Throw");
        assertEquals(OperationState.FAILED, r.state(), String.valueOf(r.error()));
        assertEquals(ErrorCode.EXECUTION_FAILED, r.error().code());
        assertTrue(r.error().message().contains("boom from the test plugin"), r.error().message());
        assertEquals(OperationState.FAILED, r.record().outcome());
        assertTrue(java.awt.Window.getWindows().length > 0);
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            assertTrue(!(w instanceof java.awt.Frame f && f.getTitle().equals("Exception") && f.isShowing()), "Exception window must be disposed");
        }
    }

    @Test
    void blockingMessageDialogIsCapturedAndDisposed() throws Exception {
        ActionResult r = run("Fidelis Message");
        assertEquals(OperationState.FAILED, r.state(), String.valueOf(r.error()));
        assertEquals(ErrorCode.EXECUTION_FAILED, r.error().code());
        assertTrue(r.error().message().contains("this plugin refuses to run"), r.error().message());
    }

    @Test
    void ijErrorMessagesAreRedirectedAndReported() throws Exception {
        ActionResult r = run("Fidelis Error");
        assertEquals(OperationState.FAILED, r.state(), String.valueOf(r.error()));
        assertTrue(r.error().message().contains("Test plugin error text"), r.error().message());
    }
}
