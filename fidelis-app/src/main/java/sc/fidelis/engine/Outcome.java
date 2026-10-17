package sc.fidelis.engine;

import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.HistoryRecord;

/** Internal result of an operation handler; converted to an ActionResult by the engine. */
record Outcome(OperationState state, EngineError error, StateDigest digest, HistoryRecord record, String token) {

    static Outcome ok(StateDigest digest, HistoryRecord record) {
        return new Outcome(OperationState.COMPLETED, null, digest, record, null);
    }

    static Outcome failed(ErrorCode code, String message) {
        return new Outcome(OperationState.FAILED, EngineError.of(code, message), null, null, null);
    }

    static Outcome failed(EngineError error) {
        return new Outcome(OperationState.FAILED, error, null, null, null);
    }
}
