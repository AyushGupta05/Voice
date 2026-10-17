package sc.fidelis.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;

import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/** FlatLaf set-up: compact density, a theme toggle, and the neutral canvas surround (docs D11). */
public final class Theme {
    /** Neutral grey behind the image in both themes: dark enough for contrast, light enough to see the image edge. */
    public static final Color CANVAS_SURROUND = new Color(0x4a4a4a);
    public static final int UI_FONT_SIZE = 12;

    private static boolean dark;

    private Theme() {}

    public static boolean isDark() {
        return dark;
    }

    /** Install compact defaults and the light theme. Call before creating any component. */
    public static void install() {
        applyDefaults();
        FlatLightLaf.setup();
        dark = false;
        // The IJ1 canvas is a heavyweight AWT component: popups must be heavyweight to stay above it (EXP-01 H5).
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setInitialDelay(500);
    }

    /** Switch theme at run time and refresh every open Swing window. */
    public static void setDark(boolean darkTheme) {
        if (darkTheme == dark) return;
        dark = darkTheme;
        applyDefaults();
        if (darkTheme) FlatDarkLaf.setup();
        else FlatLightLaf.setup();
        FlatLaf.updateUI();
    }

    private static void applyDefaults() {
        String family = Font.SANS_SERIF;
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (f.equals("Segoe UI")) {
                family = f;
                break;
            }
        }
        UIManager.put("defaultFont", new Font(family, Font.PLAIN, UI_FONT_SIZE));
        UIManager.put("Button.margin", new Insets(2, 8, 2, 8));
        UIManager.put("ToggleButton.margin", new Insets(2, 6, 2, 6));
        UIManager.put("TabbedPane.tabInsets", new Insets(3, 10, 3, 10));
        UIManager.put("TabbedPane.tabHeight", 26);
        UIManager.put("Menu.margin", new Insets(2, 4, 2, 4));
        UIManager.put("MenuItem.margin", new Insets(2, 4, 2, 4));
        UIManager.put("Component.arc", 4);
        UIManager.put("Button.arc", 4);
        UIManager.put("TextComponent.arc", 4);
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("SplitPane.dividerSize", 5);
        UIManager.put("Table.rowHeight", 20);
    }

    /** Run on the EDT; convenience for call sites that may be off-thread. */
    public static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    public static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : Color.GRAY;
    }

    public static Color accent() {
        Color c = UIManager.getColor("Component.accentColor");
        return c != null ? c : new Color(0x2f6fdb);
    }

    public static Color warning() {
        return dark ? new Color(0xe5b94a) : new Color(0x9a6700);
    }

    public static Color error() {
        return dark ? new Color(0xff7b72) : new Color(0xc0262d);
    }

    public static Color recording() {
        return new Color(0xe5282d);
    }

    public static Color panelBorder() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : Color.LIGHT_GRAY;
    }
}
