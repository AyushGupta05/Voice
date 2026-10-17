package science.luma.qupath;

import java.util.prefs.Preferences;
import qupath.lib.gui.prefs.PathPrefs;

/** Checks the real pinned QuPath override with a memory-only preference backend. */
public final class PreferenceContractTest {
    public static void main(String[] args) throws Exception {
        String expected = System.getProperty("qupath.prefs.name");
        if (expected == null || !expected.startsWith("science.luma/")) throw new AssertionError("Missing isolated node");
        var preferences = PathPrefs.getUserPreferences();
        if (!preferences.absolutePath().equals("/" + expected)) throw new AssertionError("QuPath ignored its preference-node override");
        preferences.put("luma-isolation-fixture", "local test");
        if (Preferences.userRoot().node("io.github.qupath/0.7").get("luma-isolation-fixture", null) != null)
            throw new AssertionError("Stock QuPath node was changed");
        if (!MemoryPreferencesFactory.class.getName().equals(System.getProperty("java.util.prefs.PreferencesFactory")))
            throw new AssertionError("Test must use memory-only preferences");
        System.out.println("Preference override contract passed; no macOS preference files read or written.");
    }
}
