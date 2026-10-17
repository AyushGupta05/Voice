package sc.fidelis.ui;

import java.awt.LayoutManager;
import java.awt.Rectangle;

import javax.swing.JPanel;
import javax.swing.Scrollable;

/** A panel inside a scroll pane that always takes the viewport width (no horizontal scrolling for forms). */
final class WidthTrackingPanel extends JPanel implements Scrollable {
    WidthTrackingPanel(LayoutManager layout) {
        super(layout);
    }

    @Override
    public java.awt.Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
        return 14;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
        return 80;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
