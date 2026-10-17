package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * Right and bottom docks (docs D12 section 2): fixed regions with tabs, collapsible, no third-party
 * docking library (DP-D12-02). Collapsing the right dock leaves a thin restore strip; collapsing the
 * bottom dock leaves its tab strip.
 */
final class Docks {
    static final int RIGHT_WIDTH = 380;
    static final int BOTTOM_HEIGHT = 230;
    private static final int STRIP = 24;

    final JTabbedPane right = new JTabbedPane();
    final JTabbedPane bottom = new JTabbedPane();
    private final JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private final JPanel rightStrip = new JPanel(new BorderLayout());
    private boolean rightVisible = true;
    private boolean bottomCollapsed;
    private int lastBottomHeight = BOTTOM_HEIGHT;
    private int lastBottomTab = 0;
    private int lastRightWidth = RIGHT_WIDTH;

    JComponent build(JComponent documents, JComponent parameters, JComponent roi, JComponent results,
                     JComponent history, JComponent log, JComponent activity) {
        right.addTab("Parameters", parameters);
        right.addTab("ROI list", roi);
        right.addTab("Results", results);
        bottom.addTab("History & macro", history);
        bottom.addTab("Log", log);
        bottom.addTab("Activity", activity);

        right.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, collapseButton(UiIcons.Kind.RIGHT, "Collapse the right dock", () -> setRightVisible(false)));
        bottom.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, collapseButton(UiIcons.Kind.DOWN, "Collapse the bottom dock", () -> setBottomCollapsed(true)));
        bottom.addChangeListener(e -> {
            if (bottomCollapsed && bottom.getSelectedIndex() >= 0) setBottomCollapsed(false);
            else if (bottom.getSelectedIndex() >= 0) lastBottomTab = bottom.getSelectedIndex();
        });

        JButton restore = new JButton(UiIcons.of(UiIcons.Kind.LEFT));
        restore.setToolTipText("Show the right dock");
        restore.setMargin(new Insets(2, 2, 2, 2));
        restore.setFocusable(false);
        restore.addActionListener(e -> setRightVisible(true));
        rightStrip.add(restore, BorderLayout.NORTH);
        rightStrip.setPreferredSize(new Dimension(STRIP, 100));
        rightStrip.setMinimumSize(new Dimension(STRIP, 0));

        right.setMinimumSize(new Dimension(220, 100));
        bottom.setMinimumSize(new Dimension(100, 28));

        horizontal.setLeftComponent(documents);
        horizontal.setRightComponent(right);
        horizontal.setResizeWeight(1.0);
        horizontal.setContinuousLayout(true);
        horizontal.setBorder(null);
        vertical.setTopComponent(horizontal);
        vertical.setBottomComponent(bottom);
        vertical.setResizeWeight(1.0);
        vertical.setContinuousLayout(true);
        vertical.setBorder(null);
        return vertical;
    }

    private static JComponent collapseButton(UiIcons.Kind glyph, String tip, Runnable r) {
        JButton b = new JButton(UiIcons.of(glyph));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setMargin(new Insets(0, 6, 0, 6));
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.addActionListener(e -> r.run());
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        p.add(b);
        return p;
    }

    /** Default positions: right dock 380 px wide, bottom dock 230 px high. Call after the frame has a size. */
    void resetLayout() {
        setRightVisible(true);
        setBottomCollapsed(false);
        SwingUtilities.invokeLater(() -> {
            horizontal.setDividerLocation(Math.max(200, horizontal.getWidth() - RIGHT_WIDTH - horizontal.getDividerSize()));
            vertical.setDividerLocation(Math.max(200, vertical.getHeight() - BOTTOM_HEIGHT - vertical.getDividerSize()));
            lastRightWidth = RIGHT_WIDTH;
            lastBottomHeight = BOTTOM_HEIGHT;
        });
    }

    /** Apply the default divider positions immediately (the caller has validated the frame). */
    void applyDefaultDividers() {
        horizontal.setDividerLocation(Math.max(200, horizontal.getWidth() - RIGHT_WIDTH - horizontal.getDividerSize()));
        vertical.setDividerLocation(Math.max(200, vertical.getHeight() - BOTTOM_HEIGHT - vertical.getDividerSize()));
    }

    void setRightVisible(boolean visible) {
        if (visible == rightVisible) return;
        if (!visible) lastRightWidth = Math.max(220, horizontal.getWidth() - horizontal.getDividerLocation() - horizontal.getDividerSize());
        rightVisible = visible;
        horizontal.setRightComponent(visible ? right : rightStrip);
        int w = visible ? lastRightWidth : STRIP;
        SwingUtilities.invokeLater(() -> horizontal.setDividerLocation(horizontal.getWidth() - w - horizontal.getDividerSize()));
    }

    boolean isRightVisible() {
        return rightVisible;
    }

    void setBottomCollapsed(boolean collapsed) {
        if (collapsed == bottomCollapsed) return;
        bottomCollapsed = collapsed;
        if (collapsed) {
            lastBottomHeight = Math.max(120, vertical.getHeight() - vertical.getDividerLocation() - vertical.getDividerSize());
            bottom.setSelectedIndex(-1);
            SwingUtilities.invokeLater(() -> vertical.setDividerLocation(vertical.getHeight() - 30 - vertical.getDividerSize()));
        } else {
            if (bottom.getSelectedIndex() < 0) bottom.setSelectedIndex(Math.min(lastBottomTab, bottom.getTabCount() - 1));
            SwingUtilities.invokeLater(() -> vertical.setDividerLocation(vertical.getHeight() - lastBottomHeight - vertical.getDividerSize()));
        }
    }

    boolean isBottomCollapsed() {
        return bottomCollapsed;
    }

    void collapseAll() {
        setRightVisible(false);
        setBottomCollapsed(true);
    }

    private void showRight(int i) {
        setRightVisible(true);
        right.setSelectedIndex(i);
    }

    private void showBottom(int i) {
        if (bottomCollapsed) setBottomCollapsed(false);
        bottom.setSelectedIndex(i);
    }

    void showParameters() {
        showRight(0);
    }

    void showRoi() {
        showRight(1);
    }

    void showResults() {
        showRight(2);
    }

    void showHistory() {
        showBottom(0);
    }

    void showLog() {
        showBottom(1);
    }

    void showActivity() {
        showBottom(2);
    }

    JSplitPane horizontalSplit() {
        return horizontal;
    }

    JSplitPane verticalSplit() {
        return vertical;
    }
}
