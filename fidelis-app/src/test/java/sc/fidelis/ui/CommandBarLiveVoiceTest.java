package sc.fidelis.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sc.fidelis.testkit.FijiFixture;

/**
 * Penny in the command bar (docs/INTEGRATION.md "Voice in the workspace"): with a reachable live voice the Mic toggles
 * Penny's conversation and instruction-like text goes to Penny, the local interpreter row staying available; without
 * it the Whisper push-to-talk and the rows are unchanged. The body lives in {@link LiveVoiceScenario}.
 */
class CommandBarLiveVoiceTest {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        LiveVoiceScenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        LiveVoiceScenario.stop();
    }

    @Test
    void micFollowsPennyAndFallsBackToLocalSpeech() throws Exception {
        LiveVoiceScenario.micFollowsPennyAndFallsBackToLocalSpeech();
    }

    @Test
    void typedInstructionsGoToPennyWhenConnected() throws Exception {
        LiveVoiceScenario.typedInstructionsGoToPennyWhenConnected();
    }
}
