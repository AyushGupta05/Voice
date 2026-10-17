package sc.fidelis.engine;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Label;
import java.awt.TextArea;
import java.awt.Window;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import ij.IJ;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.MessageDialog;
import ij.gui.MultiLineLabel;
import ij.text.TextWindow;

/**
 * Per-action guard around {@code IJ.run}: error redirection, a dialog watch, and the log/window diff.
 *
 * <p>While an action runs, a poller (every 100 ms) looks for windows that were not showing when the
 * action started: IJ1's "Exception" {@code TextWindow}, {@code MessageDialog}s and "Macro Error"
 * dialogs are error signals (text captured, window disposed, which also unblocks a modal wait); any
 * other {@link Dialog} is a pending native dialog (the engine reports WAITING_FOR_DIALOG). The IJ1 Log,
 * Results and ROI Manager windows are kept hidden (their content reaches the UI through engine events).
 */
final class Guard {
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fidelis-dialog-watch");
        t.setDaemon(true);
        return t;
    });

    /** Notifications for the engine (called on the poller thread). */
    interface Listener {
        void dialogOpened(String title, String dialogClass);

        void dialogsClosed();
    }

    /** What the guard saw while the action ran. */
    record Report(List<String> errors, List<String> newLogLines, List<String> windowsOpened, List<String> windowsClosed,
                  boolean sawDialog) {
        boolean failed() {
            return !errors.isEmpty();
        }

        String errorText() {
            return String.join("\n", errors);
        }
    }

    private final Listener listener;
    private final Set<Window> baseline = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<String> baselineTitles = new ArrayList<>();
    private final Set<Window> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Window> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> opened = new ArrayList<>();
    private final String logBefore;
    private ScheduledFuture<?> task;
    private volatile boolean sawDialog;

    private Guard(Listener listener) {
        this.listener = listener;
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                baseline.add(w);
                baselineTitles.add(titleOf(w));
            }
        }
        this.logBefore = IJ.getLog();
    }

    /** Arm error redirection, clear stale error state and start the dialog watch. */
    static Guard begin(Listener listener) {
        IJ.setKeyUp(IJ.ALL_KEYS);
        IJ.getErrorMessage(); // clears any stale message
        IJ.redirectErrorMessages(true);
        Guard g = new Guard(listener);
        g.task = SCHEDULER.scheduleWithFixedDelay(() -> {
            try {
                g.poll();
            } catch (Throwable t) {
                System.err.println("[fidelis-engine] dialog watch: " + t);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        return g;
    }

    /** Stop watching and report. Also keeps the IJ1 Log, Results and ROI Manager windows hidden. */
    Report end() {
        if (task != null) task.cancel(false);
        try {
            poll(); // catches a window created in the last moments of the action
        } catch (Throwable t) {
            System.err.println("[fidelis-engine] dialog watch: " + t);
        }
        Frame ex = WindowManager.getFrame("Exception");
        if (ex instanceof TextWindow tw && tw.isShowing() && !seen.contains(tw)) capture(tw);
        String ijError = IJ.getErrorMessage();
        IJ.redirectErrorMessages(false);
        if (ijError != null && !ijError.isBlank()) errors.add(ijError);
        List<String> logLines = new ArrayList<>();
        String after = IJ.getLog();
        if (after != null) {
            String fresh = logBefore != null && after.startsWith(logBefore) ? after.substring(logBefore.length()) : (logBefore == null ? after : after);
            for (String line : fresh.split("\\R")) if (!line.isBlank()) logLines.add(line);
        }
        // IJ.error with redirection writes "ImageJ: message" to the log; the message is already captured above
        List<String> closed = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (baseline.contains(w) && !w.isDisplayable() && !(w instanceof ImageWindow)) closed.add(titleOf(w));
        }
        hideTamed();
        Edt.run(() -> { }); // flush the queued dispose and hide calls
        return new Report(List.copyOf(errors), logLines, List.copyOf(opened), closed, sawDialog);
    }

    private synchronized void poll() {
        List<Window> now = new ArrayList<>();
        for (Window w : Window.getWindows()) if (w.isShowing() && !baseline.contains(w)) now.add(w);
        for (Window w : now) {
            if (!seen.add(w)) continue;
            classify(w);
        }
        if (!pending.isEmpty()) {
            pending.removeIf(w -> !w.isShowing());
            if (pending.isEmpty() && listener != null) listener.dialogsClosed();
        }
    }

    private void classify(Window w) {
        String title = titleOf(w);
        if (w instanceof TextWindow tw) {
            if (title.equals("Exception")) capture(tw);
            else if (title.equals("Results") || title.equals("Log")) hide(w);
            else opened.add(title);
        } else if (w instanceof Frame f) {
            if (title.equals("ROI Manager")) hide(w);
            else if (!(w instanceof ImageWindow)) opened.add(title);
        } else if (w instanceof Dialog d) {
            if (w instanceof MessageDialog || title.equals("Macro Error") || title.equals("No Image")) {
                String text = textOf(d);
                errors.add((title.isEmpty() ? "" : title + ": ") + text);
                dispose(w);
            } else {
                sawDialog = true;
                pending.add(w);
                if (listener != null) listener.dialogOpened(title, w.getClass().getName());
            }
        }
    }

    private void capture(TextWindow tw) {
        seen.add(tw);
        String text;
        try {
            text = tw.getTextPanel().getText();
        } catch (Throwable t) {
            text = "(exception text unavailable: " + t + ")";
        }
        errors.add("Exception: " + text.strip());
        SwingUtilities.invokeLater(tw::close); // close() also unregisters it from WindowManager (dispose alone leaves it listed)
    }

    private static void dispose(Window w) {
        SwingUtilities.invokeLater(w::dispose);
    }

    private static void hide(Window w) {
        SwingUtilities.invokeLater(() -> w.setVisible(false));
    }

    private void hideTamed() {
        for (String title : new String[] {"Results", "Log", "ROI Manager"}) {
            Frame f = WindowManager.getFrame(title);
            if (f != null && f.isShowing() && !(f instanceof ImageWindow)) hide(f);
        }
    }

    private static String titleOf(Window w) {
        if (w instanceof Frame f) return f.getTitle() == null ? "" : f.getTitle();
        if (w instanceof Dialog d) return d.getTitle() == null ? "" : d.getTitle();
        return "";
    }

    /** Text shown in a dialog (MessageDialog, GenericDialog message, YesNoCancelDialog...). */
    static String textOf(Container root) {
        StringBuilder sb = new StringBuilder();
        collect(root, sb);
        return sb.toString().strip();
    }

    private static void collect(Component c, StringBuilder sb) {
        String text = null;
        if (c instanceof MultiLineLabel m) {
            try {
                Field f = MultiLineLabel.class.getDeclaredField("lines");
                f.setAccessible(true);
                String[] lines = (String[]) f.get(m);
                int n = 0;
                try {
                    Field nf = MultiLineLabel.class.getDeclaredField("num_lines");
                    nf.setAccessible(true);
                    n = nf.getInt(m);
                } catch (ReflectiveOperationException ignored) {
                    n = lines.length;
                }
                StringBuilder t = new StringBuilder();
                for (int i = 0; i < Math.min(n, lines.length); i++) t.append(i > 0 ? "\n" : "").append(lines[i]);
                text = t.toString();
            } catch (ReflectiveOperationException e) {
                text = null;
            }
        } else if (c instanceof Label l) {
            text = l.getText();
        } else if (c instanceof TextArea ta) {
            text = ta.getText();
        } else if (c instanceof JLabel jl) {
            text = jl.getText();
        }
        if (text != null && !text.isBlank()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        if (c instanceof Container cont) for (Component child : cont.getComponents()) collect(child, sb);
    }

    /** Dispose any dialogs still pending (used to cancel an operation blocked on a native dialog). */
    void disposePending() {
        for (Window w : new ArrayList<>(pending)) dispose(w);
    }
}
