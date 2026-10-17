package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


import sc.fidelis.engine.api.Engine;
import sc.fidelis.testkit.FijiFixture;
import sc.fidelis.testkit.HarnessSelfCheckEngine;
import sc.fidelis.testkit.ProductEngine;
import sc.fidelis.testkit.IjState;

/**
 * L2/L3 engine parity (docs A30, D18): the typed requests of journey J-12 submitted to the product
 * {@link Engine} must reproduce, exactly, the stored reference outputs of the pinned Fiji (E1 pixels, E5
 * Results as doubles, E4 ROI vertices, E6 globals).
 *
 * <p>The tests are engine-agnostic: the engine is obtained from {@code sc.fidelis.engine.Engines.current()}
 * by reflection (the class is added by the engine agent) and the tests are skipped when it does not exist.
 * With the environment variable {@code FIDELIS_PARITY_SELFCHECK=1} a thin IJ.run adapter
 * ({@link HarnessSelfCheckEngine}) stands in, which proves this machinery but says nothing about the product.
 *
 * <p>The requests below are written independently of the engine's macro generator and carry every key
 * explicitly (booleans decided), so the expected option strings are those of docs/data/commands-core-parameters.csv.
 * BlackBackground is set by the test before the first step, exactly as the reference macro does (D16 J-12
 * step 7 records it as a global).
 */
class EngineParityTest {
    private static Engine engine;
    private static String engineKind;

    @BeforeAll
    static void acquire() {
        FijiFixture.imageJ(); // the live context must exist before any ij.* use
        if ("1".equals(System.getenv("FIDELIS_PARITY_SELFCHECK"))) {
            engine = new HarnessSelfCheckEngine();
            engineKind = "harness self-check adapter (NOT the product engine)";
            return;
        }
        engine = ProductEngine.get();
        engineKind = "product engine " + engine.getClass().getName();
    }

    @ParameterizedTest(name = "J-12 on {0}: engine output equals the pinned-Fiji baseline at every step")
    @ValueSource(strings = {"FX-001", "FX-003", "FX-030", "FX-031"})
    void j12Parity(String fixtureId, @TempDir Path tmp) throws Exception {
        System.out.println("[parity] " + fixtureId + " via " + engineKind);
        J12Session.Run run = J12Session.run(engine, fixtureId, tmp, true, null);
        run.notes().forEach(n -> System.out.println("[parity] note: " + n));
        // reported-only figures (E9): published counts are not pass/fail criteria
        String published = switch (fixtureId) {
            case "FX-030" -> "BBBC001 manual counts 350/362 for this image set";
            case "FX-031" -> "BBBC004 ground truth 300";
            default -> "none";
        };
        System.out.println("[parity] REPORTED " + fixtureId + " particles (Analyze Particles rows) = "
                + engine.results().size() + "; ROI list = " + engine.rois(run.document()).size()
                + "; published: " + published);
        assertEquals(List.of(), run.failures(),
                () -> "engine differs from the pinned-Fiji reference:\n" + String.join("\n", run.failures()));
        IjState.reset();
    }
}
