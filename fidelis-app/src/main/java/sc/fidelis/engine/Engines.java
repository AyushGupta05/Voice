package sc.fidelis.engine;

import sc.fidelis.engine.api.Engine;

/** Process-wide access to the running engine (set by {@link DefaultEngine#create}). */
public final class Engines {
    private static volatile Engine current;

    private Engines() {}

    /** The engine created by the bootstrap, or null before {@link DefaultEngine#create} ran. */
    public static Engine current() {
        return current;
    }

    static void set(Engine engine) {
        current = engine;
    }
}
