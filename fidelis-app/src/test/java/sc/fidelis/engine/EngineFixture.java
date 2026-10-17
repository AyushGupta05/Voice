package sc.fidelis.engine;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import sc.fidelis.testkit.FijiFixture;

/** One shared engine per test JVM (the engine owns IJ1 global state, so tests share it and clean up after themselves). */
final class EngineFixture {
    private static DefaultEngine engine;

    private EngineFixture() {}

    static synchronized DefaultEngine engine() {
        if (engine == null) {
            engine = DefaultEngine.create(FijiFixture.imageJ());
            try {
                if (!engine.awaitReady(120, TimeUnit.SECONDS)) throw new IllegalStateException("engine not ready: " + engine.state());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return engine;
    }

    static Path repoFile(String relative) {
        return Path.of(System.getProperty("fidelis.repo.dir")).resolve(relative);
    }
}
