package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.ImagePlus;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/** E3: typed command execution, validation and postconditions. */
class EngineCommandTest {
    DefaultEngine engine;
    DocumentId doc;

    @BeforeEach
    void open() throws Exception {
        engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        doc = engine.documents().get(engine.documents().size() - 1).id();
    }

    @AfterEach
    void close() throws Exception {
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

    String hash() {
        return Pixels.hash(engine.imagePlus(doc), false);
    }

    @Test
    void gaussianBlurChangesPixels() throws Exception {
        String h0 = hash();
        ActionResult r = cmd("ij1:Process>Filters>Gaussian Blur...", m("sigma", 2, "scaled", false, "stack", false));
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertNotEquals(h0, hash());
        assertTrue(r.digest().changed().get(0).pixelsChanged());
        assertEquals("run(\"Gaussian Blur...\", \"sigma=2\");", r.record().macroLines().get(0));
    }

    @Test
    void missingKeyIsRejectedBeforeRunning() throws Exception {
        String h0 = hash();
        ActionResult r = cmd("ij1:Process>Filters>Gaussian Blur...", m("sigma", 2));
        // scaled and stack are invisible for an uncalibrated single image, so only sigma is required
        assertEquals(OperationState.COMPLETED, r.state());
        ActionResult bad = cmd("ij1:Process>Filters>Gaussian Blur...", m("scaled", false));
        assertEquals(OperationState.FAILED, bad.state());
        assertEquals(ErrorCode.VALIDATION_FAILED, bad.error().code());
        assertTrue(bad.error().message().contains("sigma"), bad.error().message());
        ActionResult unknown = cmd("ij1:Process>Filters>Gaussian Blur...", m("sigma", 2, "bogus", 1));
        assertEquals(ErrorCode.VALIDATION_FAILED, unknown.error().code());
        ActionResult range = cmd("ij1:Process>Filters>Gaussian Blur...", m("sigma", -1));
        assertEquals(ErrorCode.VALIDATION_FAILED, range.error().code());
        assertNotEquals(h0, hash());
    }

    @Test
    void invertTwiceRestoresTheOriginalHash() throws Exception {
        String h0 = hash();
        assertEquals(OperationState.COMPLETED, cmd("ij1:Edit>Invert", m()).state());
        assertNotEquals(h0, hash());
        assertEquals(OperationState.COMPLETED, cmd("ij1:Edit>Invert", m()).state());
        assertEquals(h0, hash());
    }

    @Test
    void unknownCommandAndMissingTarget() throws Exception {
        ActionResult r = cmd("ij1:Nope>Nothing", m());
        assertEquals(ErrorCode.UNKNOWN_COMMAND, r.error().code());
        ActionResult noTarget = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Edit>Invert", m(), null)).get(30, TimeUnit.SECONDS);
        assertEquals(ErrorCode.PRECONDITION_FAILED, noTarget.error().code());
    }
}
