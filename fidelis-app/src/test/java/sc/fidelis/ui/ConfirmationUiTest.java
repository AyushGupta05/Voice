package sc.fidelis.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sc.fidelis.testkit.FijiFixture;

/**
 * Confirmation policy and speech vocabulary in the workspace, on the product engine. Bodies live in
 * {@link ConfirmationScenario} so no ij.* class loads before the ImageJ1 patcher has run.
 */
class ConfirmationUiTest {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        ConfirmationScenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        ConfirmationScenario.stop();
    }

    @Test
    void requiredConfirmationSubmitsNothingUntilConfirm() throws Exception {
        ConfirmationScenario.requiredConfirmationSubmitsNothingUntilConfirm();
    }

    @Test
    void explicitConfirmationShowsReasonsAndNeedsADeliberateAct() throws Exception {
        ConfirmationScenario.explicitConfirmationShowsReasonsAndNeedsADeliberateAct();
    }

    @Test
    void notReadyProposalCannotBeConfirmed() throws Exception {
        ConfirmationScenario.notReadyProposalCannotBeConfirmed();
    }

    @Test
    void vocabularyPromptFollowsDocumentsAndRecording() throws Exception {
        ConfirmationScenario.vocabularyPromptFollowsDocumentsAndRecording();
    }
}
