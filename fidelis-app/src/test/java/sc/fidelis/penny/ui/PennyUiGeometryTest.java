package sc.fidelis.penny.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sc.fidelis.testkit.FijiFixture;

/**
 * The workspace's {@code PennyUi} on a hosted IJ1 canvas: {@code toScreen}/{@code canvasOnScreen} at 100 % and 200 %,
 * viewport clipping, zoom and an in-canvas flash. The body is {@link PennyUiGeometryScenario}.
 */
class PennyUiGeometryTest {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        PennyUiGeometryScenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        PennyUiGeometryScenario.stop();
    }

    @Test
    void geometryAtOneAndTwoHundredPercentAndFlash() throws Exception {
        PennyUiGeometryScenario.geometryAtOneAndTwoHundredPercentAndFlash();
    }
}
