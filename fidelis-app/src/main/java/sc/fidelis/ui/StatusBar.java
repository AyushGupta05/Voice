package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;

import sc.fidelis.engine.api.Enums.EngineState;

/**
 * Status and activity bar (docs D12 UI-12): pixel read-out, notices and activity, tool, tracked
 * legacy windows, undo budget, engine state, and the recording indicator.
 */
public final class StatusBar extends JPanel {
    private final UiContext ctx;
    private final JLabel readout = new JLabel(" ");
    private final JLabel notice = new JLabel(" ");
    private final JLabel tool = new JLabel(" ");
    private final JLabel undo = new JLabel(" ");
    private final JLabel engine = new JLabel("Engine: initialising");
    private final JProgressBar progress = new JProgressBar();
    private final JButton windows = new JButton("Fiji windows: 0 ▾");
    private final RecordingIndicator recording = new RecordingIndicator();
    private final Timer noticeFade;
    private String activity = "";

    StatusBar(UiContext ctx) {
        super(new BorderLayout(10, 0));
        this.ctx = ctx;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder()),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        readout.setFont(monospaced(readout.getFont()));
        readout.setPreferredSize(new Dimension(300, 18));
        readout.setToolTipText("Pixel read-out under the cursor (IJ1 formatting)");
        tool.setToolTipText("Current tool");
        tool.setPreferredSize(new Dimension(130, 18));
        undo.setToolTipText("Undo snapshot memory: used / budget");
        windows.setFocusable(false);
        windows.setBorderPainted(false);
        windows.setContentAreaFilled(false);
        windows.setMargin(new java.awt.Insets(0, 4, 0, 4));
        windows.setToolTipText("Fiji (IJ1 and plugin) windows that are open outside the workspace");
        progress.setPreferredSize(new Dimension(90, 10));
        progress.setVisible(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(readout);
        left.add(tool);
        JPanel centre = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        centre.setOpaque(false);
        centre.add(notice);
        centre.add(progress);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(recording);
        right.add(windows);
        right.add(undo);
        right.add(engine);
        add(left, BorderLayout.WEST);
        add(centre, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
        noticeFade = new Timer(9000, e -> showActivityOrBlank());
        noticeFade.setRepeats(false);
    }

    private static Font monospaced(Font base) {
        return new Font(Font.MONOSPACED, Font.PLAIN, base.getSize());
    }

    JButton windowsButton() {
        return windows;
    }

    RecordingIndicator recording() {
        return recording;
    }

    public void setReadout(String s) {
        readout.setText(s == null || s.isEmpty() ? " " : s);
    }

    public String readoutText() {
        return readout.getText().trim();
    }

    public void setTool(String name) {
        tool.setText("Tool: " + name);
    }

    public void setNotice(String text, boolean error) {
        notice.setText(text);
        notice.setForeground(error ? Theme.error() : UIUtil.foreground());
        noticeFade.restart();
    }

    /** Current operation text ("Running: Gaussian Blur... (2.1 s)"), or "" when idle. */
    public void setActivity(String text, int percent, boolean showProgress) {
        activity = text == null ? "" : text;
        progress.setVisible(showProgress);
        if (showProgress) {
            if (percent < 0) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setValue(percent);
            }
        }
        if (!activity.isEmpty()) {
            notice.setText(activity);
            notice.setForeground(UIUtil.foreground());
        } else if (!noticeFade.isRunning()) {
            notice.setText(" ");
        }
    }

    private void showActivityOrBlank() {
        notice.setText(activity.isEmpty() ? " " : activity);
        notice.setForeground(UIUtil.foreground());
    }

    public void setEngineState(EngineState s, String reason) {
        String text = switch (s) {
            case INITIALISING -> "Engine: initialising Fiji";
            case BUILDING_CATALOGUE -> "Engine: building command catalogue";
            case READY -> "Engine: ready";
            case FAILED -> "Engine: failed";
            case QUITTING -> "Engine: quitting";
        };
        if (s == EngineState.READY && reason != null && reason.toLowerCase(Locale.ROOT).contains("stub")) text += " (development stub)";
        engine.setText(text);
        engine.setToolTipText(reason);
        engine.setForeground(s == EngineState.FAILED ? Theme.error() : s == EngineState.READY ? UIUtil.foreground() : Theme.warning());
    }

    public void setUndoBudget(long used, long total) {
        undo.setText(String.format(Locale.ROOT, "Undo %.1f / %.0f MB", used / 1048576.0, total / 1048576.0));
    }

    public void setWindowCount(int n) {
        windows.setText("Fiji windows: " + n + " ▾");
    }

    /** Persistent red dot plus elapsed seconds while recording (docs D14 C-11). */
    static final class RecordingIndicator extends JComponent {
        private boolean on;
        private String text = "";

        RecordingIndicator() {
            setPreferredSize(new Dimension(0, 18));
            setVisible(false);
        }

        void setRecording(boolean recording, String text) {
            on = recording;
            this.text = text;
            setVisible(recording);
            setPreferredSize(recording ? new Dimension(18 + getFontMetrics(getFont()).stringWidth(text) + 4, 18) : new Dimension(0, 18));
            revalidate();
            repaint();
        }

        boolean isRecording() {
            return on;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (!on) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Theme.recording());
            int d = 10;
            g.fillOval(2, (getHeight() - d) / 2, d, d);
            g.setColor(Theme.recording());
            g.setFont(getFont().deriveFont(Font.BOLD));
            g.drawString(text, 18, (getHeight() + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2);
            g.dispose();
        }
    }

    /** Small helper so call sites do not repeat the UIManager lookup. */
    static final class UIUtil {
        private UIUtil() {}

        static Color foreground() {
            Color c = javax.swing.UIManager.getColor("Label.foreground");
            return c != null ? c : Color.BLACK;
        }
    }
}
