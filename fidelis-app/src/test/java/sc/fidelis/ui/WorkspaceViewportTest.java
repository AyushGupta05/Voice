package sc.fidelis.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sc.fidelis.testkit.FijiFixture;

/**
 * Hosts the real IJ1 canvas in the workspace with the development stub engine, zooms through IJ1's
 * ladder and checks a native rectangle-tool drag at magnification 2. The body lives in
 * {@link ViewportScenario} so that no ij.* class loads before the ImageJ1 patcher has run.
 */
class WorkspaceViewportTest {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        ViewportScenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        ViewportScenario.stop();
    }

    @Test
    void zoomLadderAndNativeRectangleDragAtMagnificationTwo() throws Exception {
        ViewportScenario.zoomLadderAndNativeRectangleDragAtMagnificationTwo();
    }
}
