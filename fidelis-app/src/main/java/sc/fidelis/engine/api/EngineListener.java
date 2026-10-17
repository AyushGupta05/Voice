package sc.fidelis.engine.api;

/** Receives {@link EngineEvent}s; see the threading note on {@link EngineEvent}. */
@FunctionalInterface
public interface EngineListener {
    void onEvent(EngineEvent event);
}
