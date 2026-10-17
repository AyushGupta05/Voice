package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sc.fidelis.testkit.FijiFixture;
import sc.fidelis.testkit.ProductEngine;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IjState;

/**
 * UI-path parity (verification brief T6): the workspace is built as the application builds it, around
 * {@code EngineProvider.get(...)} (the UI's development stub until the product engine registers itself), and
 * the typed J-12 requests are submitted to that engine. After every step the engine's documents, Results
 * and ROIs are compared exactly with the pinned-Fiji GUI baseline (E1/E3/E4/E5/E6), and the Results panel's
 * copy-as-TSV text is parsed back to doubles and compared exactly after Measure and Analyze Particles.
 *
 * <p>This proves the UI to engine to Fiji path reproduces the reference before the product engine lands. It is
 * independent of agent A1; the engine under test changes only through {@code EngineProvider}. Requests the
 * stub rejects (SET_AUTO_THRESHOLD, SAVE_AS) are performed through the same IJ1 API calls and printed as notes.
 */
class WorkspaceJourneyTest {

    @BeforeAll
    static void context() {
        FijiFixture.imageJ(); // before any ij.* class loads
        ProductEngine.get(); // the workspace is then built around DefaultEngine, as in the application
    }

    @BeforeEach
    void workspace() throws Exception {
        IjState.reset();
        JourneyScenario.start();
    }

    @AfterEach
    void close() throws Exception {
        JourneyScenario.stop();
        IjState.reset();
    }

    @ParameterizedTest(name = "J-12 on {0} through the workspace: engine, UI and Results panel equal the baseline")
    @ValueSource(strings = {"FX-001", "FX-003"})
    void j12ThroughTheWorkspace(String fixtureId, @TempDir Path tmp) throws Exception {
        Path baseline = Fixtures.requireBaseline(fixtureId, "gui");
        List<String> uiFailures = new ArrayList<>();
        System.out.println("[journey] " + fixtureId + " via " + JourneyScenario.engineName());
        J12Session.Run run = JourneyScenario.journey(fixtureId, tmp, baseline, uiFailures);
        run.notes().forEach(n -> System.out.println("[journey] note: " + n));
        List<String> all = new ArrayList<>(run.failures());
        all.addAll(uiFailures);
        assertEquals(List.of(), all, () -> "workspace journey differs from the pinned-Fiji reference:\n" + String.join("\n", all));
    }
}
