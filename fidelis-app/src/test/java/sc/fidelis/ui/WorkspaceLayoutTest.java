package sc.fidelis.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sc.fidelis.testkit.FijiFixture;

/**
 * Demo layout (issue #15): after the real J-12 journey through the workspace, the canvas with its ROIs, the
 * Results (count and at least six columns), the ROI list and the History are showing at once, without
 * overlap, at 1500x940 and at 1920x1080; dividers resize and "Reset layout" restores the defaults. The bodies
 * live in {@link LayoutScenario} so no ij.* class loads before the ImageJ1 patcher has run.
 */
class WorkspaceLayoutTest {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        LayoutScenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        LayoutScenario.stop();
    }

    @Test
    void demoPanelsVisibleTogetherAtBothResolutions() throws Exception {
        LayoutScenario.demoPanelsVisibleTogetherAtBothResolutions();
    }

    @Test
    void dividersResizeAndResetRestoresDefaults() throws Exception {
        LayoutScenario.dividersResizeAndResetRestoresDefaults();
    }
}
