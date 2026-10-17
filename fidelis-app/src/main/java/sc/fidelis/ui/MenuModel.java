package sc.fidelis.ui;

import java.awt.CheckboxMenuItem;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.MenuShortcut;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.KeyStroke;

/**
 * A plain snapshot of the Fiji menu hierarchy, read from {@code ij.Menus.getMenuBar()} (docs D12 M-01,
 * M-02). Nothing is renamed, moved or hidden; order, separators and labels are Fiji's own.
 */
public final class MenuModel {
    /** One node: a submenu (children non-null), a command, or a separator (label "-"). */
    public record Node(String label, String path, String shortcutText, KeyStroke accelerator, boolean checkbox,
                       List<Node> children) {
        public boolean isSeparator() { return "-".equals(label); }
        public boolean isMenu() { return children != null; }
        /** The catalogue id this item maps to: {@code ij1:<menu path>><label>}. */
        public String commandId() { return "ij1:" + (path.isEmpty() ? "" : path + ">") + label; }
    }

    private MenuModel() {}

    /** Top-level menus in Fiji order. Empty when ImageJ1 has no menu bar (should not happen in M2). */
    public static List<Node> read(MenuBar bar) {
        List<Node> out = new ArrayList<>();
        if (bar == null) return out;
        for (int i = 0; i < bar.getMenuCount(); i++) out.add(readMenu(bar.getMenu(i), ""));
        return out;
    }

    private static Node readMenu(Menu menu, String parentPath) {
        String label = menu.getLabel();
        String path = parentPath.isEmpty() ? label : parentPath + ">" + label;
        List<Node> kids = new ArrayList<>();
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem item = menu.getItem(i);
            if (item instanceof Menu sub) {
                kids.add(readMenu(sub, path));
            } else {
                String l = item.getLabel();
                if ("-".equals(l)) {
                    kids.add(new Node("-", path, null, null, false, null));
                    continue;
                }
                MenuShortcut sc = item.getShortcut();
                KeyStroke ks = null;
                String text = null;
                if (sc != null) {
                    int mods = java.awt.event.InputEvent.CTRL_DOWN_MASK
                            | (sc.usesShiftModifier() ? java.awt.event.InputEvent.SHIFT_DOWN_MASK : 0);
                    ks = KeyStroke.getKeyStroke(sc.getKey(), mods);
                    text = "Ctrl+" + (sc.usesShiftModifier() ? "Shift+" : "") + KeyEvent.getKeyText(sc.getKey());
                }
                kids.add(new Node(l, path, text, ks, item instanceof CheckboxMenuItem, null));
            }
        }
        return new Node(label, parentPath, null, null, false, kids);
    }

    /** All command leaves (depth-first), used by the stub catalogue and tests. */
    public static List<Node> leaves(List<Node> roots) {
        List<Node> out = new ArrayList<>();
        for (Node n : roots) collect(n, out);
        return out;
    }

    private static void collect(Node n, List<Node> out) {
        if (n.isSeparator()) return;
        if (n.isMenu()) {
            for (Node k : n.children()) collect(k, out);
        } else {
            out.add(n);
        }
    }
}
