package sc.fidelis.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

import com.fasterxml.jackson.databind.JsonNode;

import sc.fidelis.testkit.Fixtures;

/**
 * E7 helper: run an exported macro in the pinned reference Fiji (GUI mode, a separate JVM) through
 * verification/replay.py and return the dumped final state. The macro text is used verbatim except for
 * file paths: the fixture is copied (a fresh copy, so replay cannot touch the cached original) and every
 * occurrence of the original fixture path and of the live save path is pointed at the copy and at a replay-only
 * file.
 */
final class MacroReplay {
    private MacroReplay() {}

    /** Result of a replay: the dumper JSON of the final state and the macro text that was run. */
    record Replayed(JsonNode finalState, String macroText, Path runDir) {}

    static Replayed replay(String exported, Path fixture, Path liveTiff, Path tmp) throws Exception {
        Path python = python();
        Path copyDir = Files.createDirectories(tmp.resolve("fixture-copy"));
        Path copy = copyDir.resolve(fixture.getFileName());
        Files.copy(fixture, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path replayTiff = Files.createDirectories(tmp.resolve("replay-saved")).resolve(liveTiff.getFileName());
        String text = swap(swap(exported, fixture, copy), liveTiff, replayTiff);
        Path macro = tmp.resolve("exported.ijm");
        Files.writeString(macro, text, StandardCharsets.UTF_8);
        Path runDir = tmp.resolve("replay-run");
        Path script = Fixtures.repoDir().resolve("verification").resolve("replay.py");
        Process p = new ProcessBuilder(python.toString(), "-I", script.toString(), "--macro", macro.toString(),
                "--run-dir", runDir.toString(), "--timeout", "120")
                .directory(Fixtures.repoDir().toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("replay did not finish: " + output);
        }
        if (p.exitValue() != 0) throw new AssertionError("replay failed (exit " + p.exitValue() + "): " + output);
        System.out.println("[replay] " + output.trim());
        Path finalJson = runDir.resolve("out").resolve("final.json");
        return new Replayed(Fixtures.readJson(finalJson), text, runDir);
    }

    private static String swap(String text, Path from, Path to) {
        String f = from.toAbsolutePath().toString();
        String t = to.toAbsolutePath().toString().replace('\\', '/');
        return text.replace(f.replace('\\', '/'), t).replace(f, t).replace(f.replace("\\", "\\\\"), t);
    }

    /** A Python 3 interpreter: FIDELIS_PYTHON, a repo or sibling virtualenv, or python on the PATH. */
    static Path python() {
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("FIDELIS_PYTHON");
        if (env != null) candidates.add(Path.of(env));
        Path repo = Fixtures.repoDir();
        candidates.add(repo.resolve(".venv/Scripts/python.exe"));
        candidates.add(repo.resolve(".venv/bin/python"));
        if (repo.getParent() != null) {
            candidates.add(repo.getParent().resolve("click-to-batch/.venv/Scripts/python.exe"));
        }
        for (Path c : candidates) if (Files.isRegularFile(c)) return c;
        try {
            Process p = new ProcessBuilder("python", "-I", "-c", "pass").redirectErrorStream(true).start();
            if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) return Path.of("python");
        } catch (IOException | InterruptedException ignored) {
            // fall through
        }
        Assumptions.assumeTrue(false, "no Python interpreter found (set FIDELIS_PYTHON); replay skipped");
        return null;
    }
}
