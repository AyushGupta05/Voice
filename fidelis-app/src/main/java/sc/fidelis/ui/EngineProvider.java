package sc.fidelis.ui;

import sc.fidelis.engine.api.Engine;

/**
 * Where the workspace gets its {@link Engine}. The real engine registers itself with
 * {@link #register(Engine)} (the lead wires {@code Engines.current()} to it); until then the
 * development stub is used so the UI can be built and run on its own.
 */
public final class EngineProvider {
    private static volatile Engine registered;

    private EngineProvider() {}

    public static void register(Engine engine) {
        registered = engine;
    }

    public static boolean hasRegistered() {
        return registered != null;
    }

    /** The registered engine, or a new development stub whose quit action is {@code onStubQuit}. */
    public static Engine get(Runnable onStubQuit) {
        Engine e = registered;
        if (e != null) return e;
        return new sc.fidelis.ui.dev.StubEngine(onStubQuit);
    }
}
