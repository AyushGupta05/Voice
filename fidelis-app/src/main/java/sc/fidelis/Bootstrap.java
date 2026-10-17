package sc.fidelis;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLightLaf;

import ij.IJ;
import ij.ImagePlus;
import net.imagej.ImageJ;
import net.imagej.legacy.LegacyService;

/**
 * Starts the SciJava context with the legacy (ImageJ1) layer in a non-headless JVM whose Fiji
 * main window is never shown (docs ADR-002, A15 mode M2), installs FlatLaf (EXP-01: M2 gives Metal
 * otherwise), then hands over to the workspace.
 *
 * <p>{@code -Dfidelis.smoke=true} runs a self-test instead: one real ImageJ command on a synthetic
 * image, verified by pixel values, then an orderly exit. Used by M0 to prove the toolchain.
 */
public final class Bootstrap {
    private Bootstrap() {}

    public static void run(String[] args) {
        long t0 = System.nanoTime();
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("[fidelis] refusing to start headless: the engine needs a GUI-capable JVM (ADR-002)");
            Runtime.getRuntime().halt(2);
        }
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (Exception e) {
            System.err.println("[fidelis] FlatLaf unavailable: " + e);
        }
        final ImageJ ij = new ImageJ();
        LegacyService legacy = ij.get(LegacyService.class);
        boolean legacyActive = legacy != null && legacy.isActive();
        double dpiScale = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getDefaultTransform().getScaleX();
        System.out.printf("[fidelis] context ready in %.1fs; legacy active=%s; IJ1 %s; LAF %s; dpiScale=%.2f%n",
                (System.nanoTime() - t0) / 1e9, legacyActive, IJ.getFullVersion(),
                UIManager.getLookAndFeel().getName(), dpiScale);
        if (!legacyActive) {
            System.err.println("[fidelis] LegacyService inactive: ImageJ1 patching failed (FR-SYS-002)");
            Runtime.getRuntime().halt(3);
        }
        if (Boolean.getBoolean("fidelis.smoke")) {
            boolean pennyOk = !Boolean.getBoolean("fidelis.penny") || pennySmoke(ij);
            smoke(ij, pennyOk);
            return;
        }
        // The real engine is created here and handed to the UI through EngineProvider; the UI falls back to
        // its development stub only when nothing was registered (a launch that bypasses Bootstrap).
        sc.fidelis.engine.DefaultEngine engine = sc.fidelis.engine.DefaultEngine.create(ij);
        sc.fidelis.ui.EngineProvider.register(engine);
        // Penny's local bridge (docs/FIDELIS-PROTOCOL.md): only when a Penny bridge token exists or -Dfidelis.penny=true.
        sc.fidelis.penny.FidelisBridge.startIfConfigured(engine);
        SwingUtilities.invokeLater(() -> sc.fidelis.ui.Workspace.launch(ij, args));
    }

    /**
     * Smoke with {@code -Dfidelis.penny=true}: create the product engine, start the Penny bridge as a normal launch would
     * and call its own {@code /health} and {@code context} over HTTP. Never runs in a plain smoke.
     */
    private static boolean pennySmoke(ImageJ ij) {
        try {
            sc.fidelis.engine.DefaultEngine engine = sc.fidelis.engine.DefaultEngine.create(ij);
            boolean ready = engine.awaitReady(120, TimeUnit.SECONDS);
            sc.fidelis.penny.FidelisBridge bridge = sc.fidelis.penny.FidelisBridge.startIfConfigured(engine);
            boolean checked = bridge != null && bridge.selfCheck();
            System.out.println("[fidelis] penny smoke: engine ready=" + ready + "; bridge " + (bridge == null ? "not started"
                    : "on port " + bridge.port() + "; self-check " + (checked ? "OK" : "FAILED")));
            sc.fidelis.penny.FidelisBridge.stopCurrent();
            return ready && checked;
        } catch (Exception e) {
            System.out.println("[fidelis] penny smoke failed: " + e);
            return false;
        }
    }

    /** M0 self-test: a real IJ1 command with verified output, then an orderly exit. */
    private static void smoke(ImageJ ij, boolean pennyOk) {
        ImagePlus imp = IJ.createImage("smoke", "8-bit ramp", 64, 64, 1);
        int before = imp.getProcessor().get(10, 10);
        IJ.run(imp, "Invert", "");
        int after = imp.getProcessor().get(10, 10);
        boolean ok = after == 255 - before;
        System.out.printf("[fidelis] smoke: Invert pixel(10,10) %d -> %d : %s%n", before, after, ok ? "OK" : "WRONG");
        imp.changes = false;
        imp.close();
        if (!pennyOk) System.out.println("[fidelis] smoke: the Penny bridge check failed (exit 5)");
        exit(ij, !ok ? 4 : pennyOk ? 0 : 5);
    }

    /**
     * Orderly exit per spike EXP-04: close every image (discarding, as the caller has already
     * resolved unsaved changes) and its windows on the EDT, then dispose the context off the EDT with
     * a bound, then halt. {@code System.exit} with documents still open deadlocks in the SciJava
     * shutdown hook ({@code IJ1Helper.closeImageWindows} inside {@code invokeAndWait}; P85, EXP-04).
     */
    public static void exit(ImageJ ij, int code) {
        try {
            sc.fidelis.penny.FidelisBridge.stopCurrent();
        } catch (Throwable e) {
            System.err.println("[fidelis] stopping the Penny bridge: " + e);
        }
        try {
            SwingUtilities.invokeAndWait(Bootstrap::closeAllImages);
        } catch (Exception e) {
            System.err.println("[fidelis] closing images: " + e);
        }
        Thread t = new Thread(() -> {
            try {
                ij.context().dispose();
            } catch (Throwable e) {
                System.err.println("[fidelis] dispose: " + e);
            }
        }, "fidelis-dispose");
        t.setDaemon(true);
        t.start();
        try {
            t.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[fidelis] exit " + code + (t.isAlive() ? " (dispose timed out; halting)" : ""));
        System.out.flush();
        Runtime.getRuntime().halt(code);
    }

    private static void closeAllImages() {
        int[] ids = ij.WindowManager.getIDList();
        if (ids == null) return;
        for (int id : ids) {
            ImagePlus imp = ij.WindowManager.getImage(id);
            if (imp == null) continue;
            try {
                imp.changes = false;
                imp.close();
            } catch (Throwable e) {
                System.err.println("[fidelis] close " + imp.getTitle() + ": " + e);
            }
        }
    }
}
