package sc.fidelis.engine;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import sc.fidelis.testkit.FijiFixture;

/** One shared engine per test JVM (the engine owns IJ1 global state, so tests share it and clean up after themselves). */
final class EngineFixture {
    private static DefaultEngine engine;

    private EngineFixture() {}

    static synchronized DefaultEngine engine() {
        if (engine == null) engine = (DefaultEngine) sc.fidelis.testkit.ProductEngine.get();
        return engine;
    }

    static Path repoFile(String relative) {
        return Path.of(System.getProperty("fidelis.repo.dir")).resolve(relative);
    }
}
