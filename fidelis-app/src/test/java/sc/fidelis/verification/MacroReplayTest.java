package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import sc.fidelis.testkit.FijiFixture;
import sc.fidelis.testkit.ProductEngine;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IjState;

/**
 * E7 macro replay (docs A30 section 1, CR-A30-06). Two tests:
 *
 * <ol>
 *   <li>{@link #exportedMacroReplaysTheLiveSession}: after the J-12 session on the workspace's engine, the
 *       engine's {@code exportMacro()} is run in the pinned reference Fiji (GUI mode, a separate JVM, a fresh
 *       copy of the fixture) and its final pixels (E1), Results (E5), ROI list (E4) and globals (E6) must equal
 *       the live session's. Skipped while the export says "Development stub".</li>
 *   <li>{@link #replayMachineryReproducesTheReferenceMacro}: the same replay path fed with the independent
 *       reference macro must equal the stored baseline, proving the machinery (path rewriting, wrapper,
 *       dump, comparison) before any engine export exists.</li>
 * </ol>
 */
class MacroReplayTest {

    @BeforeAll
    static void context() {
        FijiFixture.imageJ();
        ProductEngine.get(); // the exported macro under test is DefaultEngine's
    }

    @BeforeEach
    void workspace() throws Exception {
        IjState.reset();
    }

    @AfterEach
    void close() throws Exception {
        JourneyScenario.stop();
        IjState.reset();
    }

    @Test
    void exportedMacroReplaysTheLiveSession(@TempDir Path tmp) throws Exception {
        String fixtureId = "FX-003";
        J12Reference.Fixture fixture = J12Reference.fixture(fixtureId);
        Path baseline = Fixtures.requireBaseline(fixtureId, "gui");
        JourneyScenario.start();
        int since = JourneyScenario.historySize(); // the engine is shared by the test JVM: replay this journey only
        List<String> uiFailures = new ArrayList<>();
        J12Session.Run run = JourneyScenario.journey(fixtureId, tmp, baseline, uiFailures);
        assertEquals(List.of(), run.failures(), "the live session must itself match the baseline first");

        String exported = JourneyScenario.exportMacroSince(since);
        assumeFalse(exported.contains("Development stub"),
                "the engine's macro export is the development stub's: E7 replay skipped");
        Snapshot live = JourneyScenario.liveSnapshot(run.document());
        MacroReplay.Replayed replay = MacroReplay.replay(exported, fixture.openFile(), run.tiff(), tmp);

        List<String> failures = new ArrayList<>(BaselineCompare.diff(replay.finalState(), live, false, false));
        failures.addAll(BaselineCompare.diffGlobals(replay.finalState(), live.globals(),
                List.of("blackBackground", "measurements", "precision")));
        assertEquals(List.of(), failures, () -> "replay of the exported macro differs from the live session:\n"
                + String.join("\n", failures) + "\n--- exported macro ---\n" + exported);
    }

    @Test
    void replayMachineryReproducesTheReferenceMacro(@TempDir Path tmp) throws Exception {
        String fixtureId = "FX-003";
        J12Reference.Fixture fixture = J12Reference.fixture(fixtureId);
        Path baseline = Fixtures.requireBaseline(fixtureId, "gui");
        Path tiff = tmp.resolve("blobs-mask.tif");
        StringBuilder macro = new StringBuilder("setOption(\"BlackBackground\", true);\n");
        for (J12Reference.Step step : J12Reference.steps(baseline)) {
            macro.append(J12Reference.localise(step.macro(), fixture.openFile(), tiff)).append('\n');
        }
        MacroReplay.Replayed replay = MacroReplay.replay(macro.toString(), fixture.openFile(), tiff, tmp);

        JsonNode last = BaselineCompare.loadStep(baseline, "s10_save_tiff");
        Snapshot replayed = Snapshot.fromDump(replay.finalState());
        List<String> failures = new ArrayList<>(BaselineCompare.diff(last, replayed, false, true));
        failures.addAll(BaselineCompare.diffGlobals(last, replayed.globals(),
                List.of("blackBackground", "measurements", "precision")));
        assertEquals(List.of(), failures, () -> "replay differs from the stored baseline:\n" + String.join("\n", failures));
        // the replay used a copy, not the cached fixture
        assertEquals(Fixtures.sha256(fixture.openFile()), Fixtures.sha256(
                tmp.resolve("fixture-copy").resolve(fixture.openFile().getFileName())));
        System.out.println("[replay] reference macro replayed on a fresh fixture copy: final state equals the stored baseline; "
                + Map.of("images", replayed.images().size(), "resultsRows", replayed.results().rows()));
    }
}
