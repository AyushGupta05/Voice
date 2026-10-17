package sc.fidelis.engine;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;

import javax.swing.SwingUtilities;

/** Run code on the AWT event-dispatch thread and wait for the result. Never call from the EDT's own waits. */
final class Edt {
    private Edt() {}

    static <T> T call(Callable<T> c) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        final Object[] box = new Object[2];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    box[0] = c.call();
                } catch (Throwable t) {
                    box[1] = t;
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
        if (box[1] != null) {
            if (box[1] instanceof RuntimeException re) throw re;
            throw new RuntimeException((Throwable) box[1]);
        }
        @SuppressWarnings("unchecked")
        T t = (T) box[0];
        return t;
    }

    static void run(Runnable r) {
        call(() -> {
            r.run();
            return null;
        });
    }
}
