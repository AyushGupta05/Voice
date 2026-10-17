package science.luma.fiji;

import org.scijava.Context;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;

/**
 * Auto-starts the bridge once Fiji's SciJava context exists, without any launcher argument.
 * This class deliberately references no ij.* or bridge classes directly: SciJava instantiates services while
 * imagej-legacy is still patching ImageJ1, and loading those classes early would break the patch. The server is
 * started on a daemon thread, by reflection, after the legacy layer reports ready; initialize() returns at once.
 */
@Plugin(type = Service.class)
public final class LumaBridgeService extends AbstractService {
    private static volatile Context shared;
    static Context sharedContext() { return shared; }

    @Override public void initialize() {
        shared = getContext();
        log("service initialised; scheduling bridge start");
        var thread = new Thread(this::startLater, "luma-bridge-autostart");
        thread.setDaemon(true);
        thread.start();
    }

    private void startLater() {
        try {
            long deadline = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < deadline) {
                var legacy = legacyReady();
                if (legacy != null) { log("legacy layer ready (" + legacy + ")"); break; }
                Thread.sleep(250);
            }
            Thread.sleep(1500);
            var bridge = Class.forName("science.luma.fiji.LumaBridge", true, LumaBridgeService.class.getClassLoader());
            Object token = bridge.getDeclaredMethod("configuredToken").invoke(null);
            if (token == null) { log("no token found (LUMA_BRIDGE_TOKEN, -Dluma.root, LUMA_ROOT or the jar's repository); bridge stays off"); return; }
            var started = bridge.getDeclaredMethod("ensureStarted").invoke(null);
            log("bridge auto-started: " + bridge.getDeclaredMethod("statusMessage").invoke(null) + (started == null ? "" : ""));
        } catch (Throwable error) {
            var cause = error instanceof java.lang.reflect.InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : error;
            log("bridge auto-start failed: " + cause);
        }
    }
    /** The imagej-legacy service once the context can hand it out, else null; looked up by name so this class never links to it. */
    private Object legacyReady() {
        try {
            var context = getContext(); if (context == null) return null;
            var type = Class.forName("net.imagej.legacy.LegacyService", false, LumaBridgeService.class.getClassLoader());
            @SuppressWarnings("unchecked") var service = context.getService((Class<? extends Service>) type);
            if (service == null) return null;
            var helper = type.getMethod("getIJ1Helper").invoke(service);
            return helper == null ? null : "IJ1 helper present";
        } catch (ClassNotFoundException error) { return "no imagej-legacy on the class path"; }
        catch (Throwable error) { return null; }
    }
    static void log(String message) { System.err.println("[Luma] " + message); }
}
