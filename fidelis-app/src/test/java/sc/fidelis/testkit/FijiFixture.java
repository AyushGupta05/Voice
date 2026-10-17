package sc.fidelis.testkit;

import java.util.concurrent.TimeUnit;

import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLightLaf;

import net.imagej.ImageJ;

/**
 * Shared live Fiji context for tests (one per test JVM; docs D18). Tests must obtain the context
 * through {@link #imageJ()} and must never load an {@code ij.*} class before it.
 *
 * <p>The test JVM is launched by fidelis-app/tools/test.py with Fiji's flags and an isolated
 * {@code user.home}, so preferences never touch the developer's profile (NFR-MAINT-004).
 * {@link ExitListener} disposes the context and halts the JVM when the run ends.
 */
public final class FijiFixture {
    private static ImageJ ij;

    static {
        net.imagej.patcher.LegacyInjector.preinit();
    }

    private FijiFixture() {}

    public static synchronized ImageJ imageJ() {
        if (ij == null) {
            try {
                UIManager.setLookAndFeel(new FlatLightLaf());
            } catch (Exception ignored) {
                // Look-and-feel is cosmetic for tests.
            }
            ij = new ImageJ();
        }
        return ij;
    }

    /** Dispose the shared context off the calling thread, waiting at most the given time. */
    public static synchronized void disposeBounded(long time, TimeUnit unit) {
        if (ij == null) return;
        final ImageJ local = ij;
        ij = null;
        Thread t = new Thread(() -> {
            try {
                local.context().dispose();
            } catch (Throwable e) {
                System.err.println("[fidelis-tests] dispose: " + e);
            }
        }, "fidelis-test-dispose");
        t.setDaemon(true);
        t.start();
        try {
            t.join(unit.toMillis(time));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) System.err.println("[fidelis-tests] context dispose timed out; halting anyway");
    }
}
