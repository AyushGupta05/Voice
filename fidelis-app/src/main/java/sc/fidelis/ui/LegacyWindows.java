package sc.fidelis.ui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;

/**
 * Tracks IJ1 and plugin windows that are visible outside the workspace (docs D12 UI-16, section 8):
 * they stay OS windows, are never re-parented, and are listed with a Show button. Windows are found
 * by an AWT event listener (works with any engine) and by the engine's {@code LegacyWindowChanged}.
 */
public final class LegacyWindows {
    /** A tracked window: the AWT window when known, else only the engine's description. */
    record Entry(String title, String kind, Window window) {}

    private final UiContext ctx;
    private final Map<Window, Entry> awt = new LinkedHashMap<>();
    private final Map<String, Entry> fromEngine = new LinkedHashMap<>();

    LegacyWindows(UiContext ctx) {
        this.ctx = ctx;
        Toolkit.getDefaultToolkit().addAWTEventListener(this::onAwtEvent, AWTEvent.WINDOW_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK);
        ctx.status().windowsButton().addActionListener(e -> showPopup());
    }

    private void onAwtEvent(AWTEvent e) {
        if (!(e.getSource() instanceof Window w)) return;
        int id = e.getID();
        boolean appeared = id == WindowEvent.WINDOW_OPENED || id == ComponentEvent.COMPONENT_SHOWN;
        boolean gone = id == WindowEvent.WINDOW_CLOSED || id == ComponentEvent.COMPONENT_HIDDEN;
        if (!appeared && !gone) return;
        SwingUtilities.invokeLater(() -> {
            if (appeared && w.isShowing() && isCandidate(w)) awt.put(w, new Entry(titleOf(w), kindOf(w), w));
            else if (gone || !w.isShowing()) awt.remove(w);
            update();
        });
    }

    private boolean isCandidate(Window w) {
        if (!(w instanceof Frame) && !(w instanceof Dialog)) return false;
        if (w == ctx.frame) return false;
        for (Window o = w.getOwner(); o != null; o = o.getOwner()) if (o == ctx.frame) return false;
        for (var info : ctx.engine.documents()) {
            DocumentId id = info.id();
            if (ctx.engine.hostWindow(id) == w) return false;
        }
        return !"ij.ImageJ".equals(w.getClass().getName()) || w.isShowing();
    }

    private static String titleOf(Window w) {
        String t = w instanceof Frame f ? f.getTitle() : w instanceof Dialog d ? d.getTitle() : "";
        return t == null || t.isEmpty() ? w.getClass().getSimpleName() : t;
    }

    private static String kindOf(Window w) {
        if (w instanceof Dialog d && d.isModal()) return "dialog (modal)";
        String n = w.getClass().getName();
        if (n.startsWith("ij.gui.ImageWindow") || n.startsWith("ij.gui.StackWindow")) return "image";
        if (n.startsWith("ij.text")) return "text";
        if (n.startsWith("ij.")) return "ImageJ";
        return "plugin";
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.LegacyWindowChanged l) {
            if (l.opened()) fromEngine.put(l.windowId(), new Entry(l.title(), l.windowClass(), null));
            else fromEngine.remove(l.windowId());
            update();
        }
    }

    List<Entry> entries() {
        List<Entry> out = new ArrayList<>(awt.values());
        for (Entry e : fromEngine.values()) {
            boolean dup = out.stream().anyMatch(x -> x.title().equals(e.title()));
            if (!dup) out.add(e);
        }
        return out;
    }

    public int count() {
        return entries().size();
    }

    private void update() {
        ctx.status().setWindowCount(count());
    }

    /** Bring a tracked window (or one with this title) to the front. */
    public boolean show(String title) {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && title != null && title.equals(titleOf(w)) && w != ctx.frame) {
                bringToFront(w);
                return true;
            }
        }
        return false;
    }

    private static void bringToFront(Window w) {
        if (w instanceof Frame f && f.getState() == Frame.ICONIFIED) f.setState(Frame.NORMAL);
        w.toFront();
        w.requestFocus();
    }

    private void showPopup() {
        JPopupMenu menu = new JPopupMenu();
        List<Entry> es = entries();
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JLabel head = new JLabel("Fiji windows");
        head.setFont(head.getFont().deriveFont(java.awt.Font.BOLD));
        panel.add(head);
        panel.add(Box.createVerticalStrut(4));
        if (es.isEmpty()) {
            JLabel none = new JLabel("No Fiji windows are open.");
            none.setForeground(Theme.muted());
            panel.add(none);
        }
        for (Entry en : es) {
            JPanel row = new JPanel(new java.awt.BorderLayout(10, 0));
            row.setOpaque(false);
            JLabel l = new JLabel(en.title() + "  ·  " + en.kind());
            JButton show = new JButton("Show");
            show.addActionListener(a -> {
                if (en.window() != null) bringToFront(en.window());
                else show(en.title());
                menu.setVisible(false);
            });
            row.add(l, java.awt.BorderLayout.CENTER);
            row.add(show, java.awt.BorderLayout.EAST);
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
            panel.add(row);
            panel.add(Box.createVerticalStrut(3));
        }
        menu.add(panel);
        Component b = ctx.status().windowsButton();
        menu.show(b, 0, -menu.getPreferredSize().height);
    }
}
