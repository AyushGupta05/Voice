package science.luma.fiji;

import org.scijava.Context;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;

/** Auto-starts the bridge when Fiji builds its SciJava context, so no launcher flag or startup macro is required. */
@Plugin(type = Service.class)
public final class LumaBridgeService extends AbstractService {
    private static volatile Context shared;
    static Context sharedContext() { return shared; }

    @Override public void initialize() {
        shared = getContext();
        if (LumaBridge.configuredToken() == null) return;
        try { LumaBridge.ensureStarted(); } catch (RuntimeException error) { System.err.println("Luma bridge did not start: " + error.getMessage()); }
    }
}
