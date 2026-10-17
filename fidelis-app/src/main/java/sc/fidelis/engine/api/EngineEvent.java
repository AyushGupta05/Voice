package sc.fidelis.engine.api;

import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.OperationState;

/**
 * Events published by the engine (docs D09 §3). Delivered to UI listeners on the AWT event-dispatch
 * thread, in sequence order; listeners must not block (NFR-PERF-005).
 *
 * <p>Ordering guarantee per operation: {@code OperationStateChanged(RUNNING)} precedes any
 * {@code OperationProgress}, which precede the terminal {@code OperationStateChanged}, which precedes
 * the {@code StateDigestEvent}, which precedes {@code HistoryAppended}.
 */
public sealed interface EngineEvent {

    long seq();

    record EngineStateChanged(long seq, EngineState state, String reason) implements EngineEvent {}

    record OperationStateChanged(long seq, String operationId, String requestId, OperationState state,
                                 EngineError error) implements EngineEvent {}

    /** Real progress only: {@code total <= 0} means indeterminate (NFR-USE-002). */
    record OperationProgress(long seq, String operationId, long current, long total, String statusText) implements EngineEvent {}

    record DialogPending(long seq, String operationId, String windowTitle, String dialogClass) implements EngineEvent {}

    record StateDigestEvent(long seq, String operationId, StateDigest digest) implements EngineEvent {}

    record HistoryAppended(long seq, HistoryRecord record) implements EngineEvent {}

    /** A document was opened, closed, or its metadata changed; UI should re-query {@code document(id)}. */
    record DocumentsChanged(long seq) implements EngineEvent {}

    record DocumentFocusChanged(long seq, DocumentId focused) implements EngineEvent {}

    /** A legacy (IJ1 or plugin) window appeared or disappeared (FR-PLUG-002). */
    record LegacyWindowChanged(long seq, String windowId, String title, String windowClass, boolean opened) implements EngineEvent {}

    record ResultsChanged(long seq, int totalRows) implements EngineEvent {}

    /** A line was written to the ImageJ/SciJava log. */
    record LogLine(long seq, String line) implements EngineEvent {}
}
