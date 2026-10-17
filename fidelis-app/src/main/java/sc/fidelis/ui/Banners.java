package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

import sc.fidelis.engine.api.EngineEvent;

/**
 * Workspace banners under the command bar (docs D14 C-07, C-09): the Penny strip (when Penny is attached, see
 * {@link #setPenny}), the dialog-pending banner and the slot for the proposal sheet. Banners never auto-dismiss while
 * their condition holds.
 */
public final class Banners extends JPanel {
    private final UiContext ctx;
    private final JPanel dialogBanner = new JPanel(new BorderLayout(8, 0));
    private final JLabel dialogText = new JLabel();
    private JComponent sheet;
    private JComponent penny;
    private String pendingOperation;
    private String pendingTitle;

    Banners(UiContext ctx) {
        this.ctx = ctx;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        dialogBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.warning()),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        dialogBanner.add(dialogText, BorderLayout.CENTER);
        JButton show = new JButton("Show");
        show.setToolTipText("Bring the Fiji dialog to the front. Answer it to continue, or press Cancel in it to stop.");
        show.addActionListener(e -> {
            if (!ctx.legacyWindows().show(pendingTitle)) ctx.notice("The dialog could not be found; look for it behind other windows", true);
        });
        dialogBanner.add(show, BorderLayout.EAST);
        dialogBanner.setVisible(false);
        dialogBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(dialogBanner);
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.DialogPending d) {
            pendingOperation = d.operationId();
            pendingTitle = d.windowTitle();
            dialogText.setText("Waiting for input in a Fiji dialog: \"" + d.windowTitle() + "\" — answer it to continue.");
            Color bg = UIManager.getColor("Panel.background");
            dialogBanner.setBackground(bg != null ? tint(bg) : Color.YELLOW);
            dialogBanner.setVisible(true);
            revalidate();
        } else if (e instanceof EngineEvent.OperationStateChanged s && s.operationId().equals(pendingOperation)
                && UiContext.terminal(s.state())) {
            dialogBanner.setVisible(false);
            pendingOperation = null;
            revalidate();
        }
    }

    private static Color tint(Color bg) {
        boolean dark = bg.getRed() + bg.getGreen() + bg.getBlue() < 384;
        return dark ? new Color(0x4a3f1a) : new Color(0xfff4d6);
    }

    /** Mount the Penny strip (the voice panel, #18) first, directly under the command bar; it manages its own visibility. */
    void setPenny(JComponent c) {
        if (penny != null) remove(penny);
        penny = c;
        if (c != null) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(c, 0);
        }
        revalidate();
        repaint();
    }

    JComponent penny() {
        return penny;
    }

    boolean isDialogPendingVisible() {
        return dialogBanner.isVisible();
    }

    /** Show a sheet (the proposal) below the banners; replaces any current sheet. */
    void setSheet(JComponent c) {
        clearSheet();
        sheet = c;
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(c);
        revalidate();
        repaint();
    }

    JComponent sheet() {
        return sheet;
    }

    void clearSheet() {
        if (sheet != null) {
            remove(sheet);
            sheet = null;
            revalidate();
            repaint();
        }
    }
}
