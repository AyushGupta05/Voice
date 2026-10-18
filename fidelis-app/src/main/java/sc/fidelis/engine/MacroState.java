package sc.fidelis.engine;

import java.lang.reflect.Field;
import java.util.Map;

import ij.macro.Interpreter;

/**
 * IJ1's static {@code Interpreter.instance} is the interpreter of the macro running now; {@code IJ.isMacro()} is true
 * while it is set, and {@code ImagePlus.show()} then waits up to two seconds for a window activation (which a hosted,
 * hidden window never gets) and pins a per-thread temporary current image. The field is kept by save-and-restore in
 * {@code Interpreter.run(String, String)}, {@code eval} and {@code saveGlobals}: when two interpreters overlap, the one
 * that ends last restores a reference to one that has already finished. Fiji's start-up does exactly that (the
 * StartupMacros {@code AutoRun} macro runs on its own thread while the installer saves the macro set's globals), so a
 * fresh Fiji JVM reports a running macro that does not exist until some macro fails or an abort times out.
 *
 * <p>{@link #clearStale()} drops such a reference when no thread is inside the macro interpreter. The engine's observer
 * timer calls it every few hundred milliseconds; test isolation calls it between classes.
 */
public final class MacroState {
    private static final Field INSTANCE = field();

    private MacroState() {}

    private static Field field() {
        try {
            Field f = Interpreter.class.getDeclaredField("instance");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[fidelis-engine] IJ1's Interpreter.instance is not reachable; a stale macro interpreter cannot be cleared: " + e);
            return null;
        }
    }

    /** True while some thread is executing inside IJ1's macro interpreter (its frames are in {@code ij.macro}). */
    public static boolean macroThreadAlive() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().isAlive()) continue;
            for (StackTraceElement frame : e.getValue()) {
                if (frame.getClassName().startsWith("ij.macro.")) return true;
            }
        }
        return false;
    }

    /**
     * Clear {@code Interpreter.instance} if it names an interpreter no thread is running; returns true when it did.
     * A macro that is running keeps its instance: {@code instance = this} is written inside {@code Interpreter.run}
     * on the macro's own thread, so that thread is inside {@code ij.macro} whenever the reference is legitimate.
     */
    public static synchronized boolean clearStale() {
        if (INSTANCE == null || Interpreter.getInstance() == null || macroThreadAlive()) return false;
        try {
            INSTANCE.set(null, null);
            return true;
        } catch (IllegalAccessException | RuntimeException e) {
            return false;
        }
    }
}
