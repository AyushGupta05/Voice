package sc.fidelis.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sc.fidelis.testkit.FijiFixture;

/**
 * U2: threshold and display-range panels leave pixels unchanged, Compare shows two real canvases with
 * linked zoom, and the C/Z/T sliders drive SET_POSITION. The bodies live in {@link U2Scenario} so no
 * ij.* class loads before the ImageJ1 patcher has run.
 */
class WorkspaceU2Test {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        U2Scenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        U2Scenario.stop();
    }

    @Test
    void thresholdPreviewLeavesPixelsUnchanged() throws Exception {
        U2Scenario.thresholdPreviewLeavesPixelsUnchanged();
    }

    @Test
    void displayRangeIsDisplayOnly() throws Exception {
        U2Scenario.displayRangeIsDisplayOnly();
    }

    @Test
    void compareShowsTwoRealCanvases() throws Exception {
        U2Scenario.compareShowsTwoRealCanvases();
    }

    @Test
    void slidersDriveSetPositionAndRepaint() throws Exception {
        U2Scenario.slidersDriveSetPositionAndRepaint();
    }
}
