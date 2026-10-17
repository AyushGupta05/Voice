package sc.fidelis.testkit;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.SwingUtilities;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.text.TextWindow;

/**
 * Helpers for tests that drive the shared in-JVM ImageJ: a clean slate before a scenario and macro
 * execution that cannot hang the JVM on a modal "Macro Error" dialog (GUI mode shows one for every
 * macro error, and the test JVM is not headless).
 */
public final class IjState {
    private IjState() {}

    /** Close every image without saving, clear Results, the Summary window and the ROI Manager. */
    public static void reset() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) {
                    imp.changes = false;
                    imp.close();
                }
            }
        }
        RoiManager rm = RoiManager.getInstance2();
        if (rm != null) {
            rm.reset();
            rm.close();
        }
        ResultsTable.getResultsTable().reset();
        for (String title : new String[] {"Results", "Summary", "Log"}) {
            Frame f = WindowManager.getFrame(title);
            if (f instanceof TextWindow tw) tw.close(false); // close(), not dispose(): clears IJ's Results text panel
            else if (f != null) f.dispose();
        }
        // A Results window unregistered by someone else (the engine hides and adopts legacy windows) would still
        // make IJ.isResultsWindow() true, and Set Measurements... then writes headings into the empty table.
        if (IJ.isResultsWindow()) {
            Window w = SwingUtilities.getWindowAncestor(IJ.getTextPanel());
            if (w instanceof TextWindow tw) tw.close(false);
            else IJ.setTextPanel(null);
        }
        Prefs.blackBackground = false;
    }

    /**
     * Run macro text with {@link IJ#runMacro(String)} on a worker thread with a timeout. A timeout (almost
     * always a modal macro-error dialog) disposes any "Macro Error" dialog and fails with its text.
     */
    public static String runMacro(String code, long timeoutSeconds) {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fidelis-test-macro");
            t.setDaemon(true);
            return t;
        });
        Future<String> future = pool.submit((Callable<String>) () -> IJ.runMacro(code));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            List<String> dialogs = new ArrayList<>();
            for (Window w : Window.getWindows()) {
                if (w instanceof Dialog d && d.isShowing()) {
                    dialogs.add(d.getTitle());
                    d.dispose();
                }
            }
            future.cancel(true);
            throw new AssertionError("macro did not finish in " + timeoutSeconds + " s; dialogs shown: " + dialogs
                    + "\n" + code);
        } catch (Exception e) {
            throw new AssertionError("macro failed: " + e + "\n" + code, e);
        } finally {
            pool.shutdown();
        }
    }
}
