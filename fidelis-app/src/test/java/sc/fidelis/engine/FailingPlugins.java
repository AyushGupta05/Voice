package sc.fidelis.engine;

import ij.IJ;
import ij.plugin.PlugIn;

/** Test-only IJ1 plugins that fail in the three ways the engine has to detect. */
public final class FailingPlugins {
    private FailingPlugins() {}

    /** Throws: IJ1 swallows it and opens an "Exception" TextWindow. */
    public static class Throwing implements PlugIn {
        @Override
        public void run(String arg) {
            throw new IllegalStateException("boom from the test plugin");
        }
    }

    /** Opens a modal MessageDialog that would block the worker. */
    public static class Messaging implements PlugIn {
        @Override
        public void run(String arg) {
            IJ.showMessage("Test plugin", "this plugin refuses to run");
        }
    }

    /** Reports through IJ.error (redirected into getErrorMessage). */
    public static class Erroring implements PlugIn {
        @Override
        public void run(String arg) {
            IJ.error("Test plugin error text");
        }
    }

    /** Shows a GenericDialog and waits for the user (a native dialog the engine must report and be able to cancel). */
    public static class Dialoging implements PlugIn {
        @Override
        public void run(String arg) {
            ij.gui.GenericDialog gd = new ij.gui.GenericDialog("Fidelis test dialog");
            gd.addNumericField("value", 1, 0);
            gd.showDialog();
        }
    }
}
