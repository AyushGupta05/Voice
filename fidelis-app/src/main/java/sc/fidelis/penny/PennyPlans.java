package sc.fidelis.penny;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What the workspace calls back into the bridge: the plan card's <em>Confirm and run</em> and <em>Cancel</em> buttons.
 * Safe to call on the EDT: the plan runs on the bridge's own thread and the future completes off the EDT (finish UI
 * work with {@code SwingUtilities.invokeLater}). The plan keeps its original origin (NL_SPOKEN / NL_TYPED) and
 * transcriptRef; it is refused when the image, selection, results or history changed since the plan was made.
 */
public final class PennyPlans {
    private PennyPlans() {}

    /** Run the plan waiting for confirmation; completes with the operation result, or exceptionally with the refusal. */
    public static CompletableFuture<Map<String, Object>> confirm(String planId) {
        FidelisBridge b = FidelisBridge.current();
        if (b == null) return CompletableFuture.failedFuture(new IllegalStateException("the Penny bridge is not running"));
        return b.ops().confirmFromUi(planId);
    }

    /** Discard the plan waiting for confirmation (no-op when it is not the pending one). */
    public static void cancel(String planId) {
        FidelisBridge b = FidelisBridge.current();
        if (b != null) b.ops().cancelFromUi(planId);
    }

    /** True while a Penny bridge is serving in this JVM. */
    public static boolean bridgeRunning() {
        return FidelisBridge.current() != null;
    }
}
