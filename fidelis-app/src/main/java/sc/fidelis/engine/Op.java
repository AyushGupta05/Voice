package sc.fidelis.engine;

import sc.fidelis.engine.api.ActionRequest;

/** One submitted operation as tracked by the engine worker. */
final class Op {
    /** The operation running on the current (worker) thread, so observers can tell engine actions from native ones. */
    static final ThreadLocal<Op> CURRENT = new ThreadLocal<>();

    final String id;
    final ActionRequest request;
    volatile boolean cancelRequested;
    /** True once the engine actually signalled the running operation (Escape key or Interpreter.abort). */
    volatile boolean abortIssued;
    /** Set while this operation is a CODE action; nested commands record this as {@code parentActionId}. */
    volatile String actionId;
    /** For commands nested under a CODE action: the CODE action id. */
    volatile String parentActionId;

    /** Set by a tail CommandListener when ImageJ really started the command (no earlier listener vetoed it). */
    volatile boolean commandRan;

    Op(String id, ActionRequest request) {
        this.id = id;
        this.request = request;
    }
}
