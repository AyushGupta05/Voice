package science.luma.fiji;

import org.scijava.Context;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.SciJavaService;
import org.scijava.service.Service;

/**
 * Auto-starts the bridge once Fiji's SciJava context exists, without any launcher argument.
 * It implements SciJavaService because net.imagej.ImageJ creates its context from SciJavaService/ImageJService only;
 * a plain Service is indexed but never instantiated.
 * This class deliberately references no ij.* or bridge classes directly: SciJava instantiates services while
 * imagej-legacy is still patching ImageJ1, and loading those classes early would break the patch. The server is
 * started on a daemon thread, by reflection, after the legacy layer reports ready; initialize() returns at once.
 */
@Plugin(type = Service.class)
public final class LumaBridgeService extends AbstractService implements SciJavaService {
    private static volatile Context shared;
    static Context sharedContext() { return shared; }

    @Override public void initialize() {
        shared = getContext();
        // Headless and batch-mode Fiji processes (tests, recipe runs) must not start a bridge: they would fight the
        // interactive instance for its port and each one flashes an ImageJ window. An explicit -Dluma.bridgePort opts in.
        boolean headless = Boolean.getBoolean("java.awt.headless") || java.awt.GraphicsEnvironment.isHeadless() || "true".equals(System.getProperty("ij.batch"))
            || System.getProperty("sun.java.command", "").matches(".*\\s(-batch|--headless|-macro|-eval|--run)(\\s.*|$)");
        if (headless && System.getProperty("luma.bridgePort") == null) { log("headless/batch process: bridge auto-start skipped"); return; }
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
    /** Fiji opens its Console window for anything written to stdout or stderr, so the bridge logs to <root>/.local/bridge.log instead. */
    static void log(String message) { BridgeLog.write(message); }
}
