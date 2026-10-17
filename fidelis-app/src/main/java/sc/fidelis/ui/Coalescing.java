package sc.fidelis.ui;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Keeps at most one request in flight for a live control (a slider or a preview): while one is
 * running, only the latest newer request is remembered and sent when it finishes. EDT only.
 */
final class Coalescing {
    private boolean busy;
    private Supplier<CompletableFuture<?>> pending;

    void post(Supplier<CompletableFuture<?>> request) {
        if (busy) {
            pending = request;
            return;
        }
        run(request);
    }

    private void run(Supplier<CompletableFuture<?>> request) {
        busy = true;
        CompletableFuture<?> f = request.get();
        f.whenComplete((r, t) -> UiContext.edt(() -> {
            busy = false;
            Supplier<CompletableFuture<?>> p = pending;
            pending = null;
            if (p != null) run(p);
        }));
    }
}
