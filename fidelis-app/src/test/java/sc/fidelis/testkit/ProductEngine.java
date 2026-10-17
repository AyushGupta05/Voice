package sc.fidelis.testkit;

import java.util.concurrent.TimeUnit;

import sc.fidelis.engine.DefaultEngine;
import sc.fidelis.engine.Engines;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.ui.EngineProvider;

/**
 * The one product engine of the test JVM, created the way {@code Bootstrap} creates it and registered with
 * the UI's {@link EngineProvider}, so workspace tests run on {@link DefaultEngine} rather than the stub.
 * The engine owns IJ1 global state; tests share it and clean up after themselves.
 */
public final class ProductEngine {
    private ProductEngine() {}

    public static synchronized Engine get() {
        Engine e = Engines.current();
        if (e == null) {
            DefaultEngine d = DefaultEngine.create(FijiFixture.imageJ());
            try {
                if (!d.awaitReady(120, TimeUnit.SECONDS)) throw new IllegalStateException("engine not ready: " + d.state());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
            e = d;
        }
        if (!EngineProvider.hasRegistered()) EngineProvider.register(e);
        return e;
    }
}
