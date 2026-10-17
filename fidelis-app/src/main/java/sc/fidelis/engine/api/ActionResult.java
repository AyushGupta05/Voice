package sc.fidelis.engine.api;

import java.util.List;
import java.util.Map;

import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;

/**
 * Outcome of an {@link ActionRequest} (docs D06 §6–§8, D09 §3).
 *
 * @param operationId   engine-assigned id, e.g. {@code op-12}
 * @param state         terminal state (COMPLETED, FAILED, CANCELLED, ROLLED_BACK, PARTIAL) or
 *                      AWAITING_CONFIRMATION with {@code confirmationToken} set
 * @param error         present when the state is FAILED/CANCELLED/PARTIAL
 * @param digest        what changed (present for COMPLETED and PARTIAL)
 * @param record        the history record appended (present for COMPLETED/PARTIAL/observed)
 * @param confirmationToken token to resubmit with when confirmation is required
 * @param durationMs    wall time of execution
 */
public record ActionResult(
        String operationId,
        OperationState state,
        EngineError error,
        StateDigest digest,
        HistoryRecord record,
        String confirmationToken,
        long durationMs) {

    public boolean succeeded() {
        return state == OperationState.COMPLETED;
    }

    /** Structured error envelope (docs D09 §1). */
    public record EngineError(ErrorCode code, String message, String subcode, Map<String, String> context,
                              boolean recoverable, List<String> suggestions) {
        public static EngineError of(ErrorCode code, String message) {
            return new EngineError(code, message, null, Map.of(), false, List.of());
        }
    }

    /** Post-action state delta (docs D09 §3 {@code StateDigest}; FR-IMG-001). */
    public record StateDigest(
            List<DocumentId> added,
            List<DocumentId> removed,
            List<DocumentChange> changed,
            int resultsRowsAdded,
            Map<String, String> globalsDelta,
            List<String> newLogLines,
            List<String> windowsOpened,
            List<String> windowsClosed) {

        public record DocumentChange(DocumentId id, boolean pixelsChanged, boolean typeChanged,
                                     boolean displayChanged, boolean roiChanged, boolean dimensionsChanged) {
        }
    }
}
