package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.WindowManager;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/**
 * E8 item 3: {@code Engine.quit} per its contract (api/Engine.java): refuse with unsaved changes unless
 * {@code discardUnsaved}, otherwise close every document with {@code changes=false}, stop the workers and terminate.
 * Stopping the workers and terminating the JVM are replaced by counters so the test JVM survives.
 */
class EngineQuitTest {
    final DefaultEngine engine = EngineFixture.engine();
    final AtomicInteger stopped = new AtomicInteger();
    final AtomicInteger terminated = new AtomicInteger();

    DocumentId make(String title) throws Exception {
        ActionResult n = engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.NEW_IMAGE,
                new LinkedHashMap<>(Map.of("title", title, "type", "8-bit", "width", 16, "height", 16, "fill", "White")), null))
                .get(30, TimeUnit.SECONDS);
        return n.digest().added().get(0);
    }

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                    TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
        engine.resumeAfterQuitForTest();
    }

    @Test
    void quitRefusesWithUnsavedChangesAndGoesAheadWhenDiscarding() throws Exception {
        DocumentId a = make("quit-a");
        DocumentId b = make("quit-b");
        engine.imagePlus(b).changes = true; // unsaved edit
        IJ.createImage("never-hosted", "8-bit white", 8, 8, 1).show(); // an image IJ1 holds that nobody asked to host
        Thread.sleep(300);

        boolean went = engine.quit(false, stopped::incrementAndGet, terminated::incrementAndGet);
        assertFalse(went, "quit(false) must refuse with unsaved changes");
        assertEquals(EngineState.READY, engine.state(), "a refused quit leaves the engine running");
        assertEquals(0, stopped.get());
        assertEquals(0, terminated.get());
        assertTrue(engine.documents().size() >= 2, "nothing was closed");
        assertTrue(engine.document(a).isPresent());
        assertTrue(engine.document(b).isPresent());

        went = engine.quit(true, stopped::incrementAndGet, terminated::incrementAndGet);
        assertTrue(went);
        assertEquals(1, stopped.get());
        assertEquals(1, terminated.get());
        assertEquals(EngineState.QUITTING, engine.state());
        assertTrue(engine.documents().isEmpty(), "the registry is empty: " + engine.documents());
        assertNull(WindowManager.getIDList(), "no IJ1 image remains");
        assertEquals(0, WindowManager.getImageCount());
    }

    @Test
    void quitWithoutUnsavedChangesClosesEverything() throws Exception {
        make("quit-clean");
        assertTrue(engine.quit(false, stopped::incrementAndGet, terminated::incrementAndGet));
        assertTrue(engine.documents().isEmpty());
        assertNull(WindowManager.getIDList());
        assertEquals(1, terminated.get());
    }
}
