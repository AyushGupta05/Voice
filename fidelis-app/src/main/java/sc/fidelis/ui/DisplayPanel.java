package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;

import ij.ImagePlus;
import ij.process.ImageStatistics;
import net.miginfocom.swing.MigLayout;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/**
 * Brightness and contrast (docs D12 UI-14, PAR-013). Minimum and maximum drive the DIRECT operation
 * SET_DISPLAY_RANGE, which changes how the image is shown and never its pixels (AC1); composites
 * get a per-channel selector (the optional {@code channel} parameter). Auto follows IJ1's
 * ContrastAdjuster auto-adjust (histogram limit and threshold) as a display range only. Apply is a
 * separate, explicit button that runs {@code Apply LUT}, which does change pixels.
 */
public final class DisplayPanel extends JPanel {
    static final String APPLY_LUT = "ij1:Image>Lookup Tables>Apply LUT";
    private static final int STEPS = 1000;
    private static final int AUTO_THRESHOLD = 5000; // ContrastAdjuster.AUTO_THRESHOLD

    private final UiContext ctx;
    private final JLabel header = new JLabel();
    private final HistogramStrip strip = new HistogramStrip();
    private final JComboBox<String> channel = new JComboBox<>();
    private final JSlider minSlider = new JSlider(0, STEPS, 0);
    private final JSlider maxSlider = new JSlider(0, STEPS, STEPS);
    private final JTextField minField = new JTextField(8);
    private final JTextField maxField = new JTextField(8);
    private final JButton auto = new JButton("Auto");
    private final JButton reset = new JButton("Reset");
    private final JButton apply = new JButton("Apply…");
    private final JLabel status = new JLabel(" ");
    private final JLabel channelLabel = new JLabel("Channel");
    private final Coalescing live = new Coalescing();
    private double lo;
    private double hi = 1;
    private boolean programmatic;

    DisplayPanel(UiContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout());
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new MigLayout("insets 6 10 6 10, fillx, wrap 3", "[left,shrink 0]12[grow,fill]8[]", "[]6[]"));
        JLabel banner = new JLabel("DISPLAY ONLY: pixel values are unchanged");
        banner.setFont(banner.getFont().deriveFont(Font.BOLD, 11f));
        banner.setForeground(Theme.muted());
        body.add(banner, "span 3, alignx left");
        body.add(strip, "span 3, growx, h 96!");
        channel.setLightWeightPopupEnabled(false);
        channel.addActionListener(e -> {
            if (!programmatic) refresh();
        });
        body.add(channelLabel);
        body.add(channel, "span 2");
        body.add(new JLabel("Minimum"));
        body.add(minSlider);
        body.add(minField, "w 80!");
        body.add(new JLabel("Maximum"));
        body.add(maxSlider);
        body.add(maxField, "w 80!");
        minSlider.addChangeListener(e -> sliderMoved(true));
        maxSlider.addChangeListener(e -> sliderMoved(false));
        minField.addActionListener(e -> fieldEdited());
        maxField.addActionListener(e -> fieldEdited());
        FocusAdapter commit = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                fieldEdited();
            }
        };
        minField.addFocusListener(commit);
        maxField.addFocusListener(commit);
        auto.setToolTipText("IJ1 auto-adjust: choose the display range from the histogram (display only)");
        auto.addActionListener(e -> autoAdjust());
        reset.setToolTipText("Show the full range of the data (display only)");
        reset.addActionListener(e -> resetRange());
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        row.add(auto);
        row.add(reset);
        body.add(row, "skip 1, span 2");
        status.setForeground(Theme.muted());
        body.add(status, "span 3, gaptop 6, alignx left");
        JPanel center = new WidthTrackingPanel(new BorderLayout());
        center.add(body, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(center, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        add(sp, BorderLayout.CENTER);

        JPanel south = new JPanel(new MigLayout("insets 6 10 8 10, fillx, wrap 1", "[grow,fill]"));
        south.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder()));
        JLabel warn = new JLabel("<html><div style='width:260px'><b>Apply changes pixels:</b> it rewrites the pixel values using the current display range.</div></html>");
        warn.setForeground(Theme.warning());
        apply.setToolTipText("Runs Apply LUT: this modifies the image data (undoable while a snapshot fits the budget)");
        apply.addActionListener(e -> applyLut());
        JPanel btn = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        btn.add(apply);
        south.add(warn);
        south.add(btn);
        add(south, BorderLayout.SOUTH);
        refresh();
    }

    // ---- model --------------------------------------------------------------------------------

    private ImagePlus imp() {
        DocumentId id = ctx.focusedId();
        return id == null ? null : ctx.engine.imagePlus(id);
    }

    private int selectedChannel(ImagePlus imp) {
        return imp != null && imp.isComposite() ? Math.max(1, channel.getSelectedIndex() + 1) : 0;
    }

    private double valueOf(JSlider s) {
        return lo + (hi - lo) * s.getValue() / STEPS;
    }

    private int stepOf(double v) {
        return (int) Math.max(0, Math.min(STEPS, Math.round((v - lo) / (hi - lo) * STEPS)));
    }

    void refresh() {
        if (minSlider.getValueIsAdjusting() || maxSlider.getValueIsAdjusting()) return;
        ImagePlus imp = imp();
        boolean ok = imp != null && imp.getType() != ImagePlus.COLOR_RGB;
        for (JComponent c : new JComponent[] {minSlider, maxSlider, minField, maxField, auto, reset, apply, channel}) c.setEnabled(ok);
        boolean composite = imp != null && imp.isComposite() && imp.getNChannels() > 1;
        channel.setVisible(composite);
        channelLabel.setVisible(composite);
        if (imp == null) {
            header.setText("Display — no image");
            strip.setMessage("No image");
            status.setText(" ");
            return;
        }
        header.setText("Display (B&C) — " + ctx.engine.document(ctx.focusedId()).map(d -> d.title()).orElse(imp.getTitle()));
        if (!ok) {
            strip.setMessage("Display range needs a 8, 16 or 32-bit image");
            status.setText("RGB images have no display range");
            return;
        }
        programmatic = true;
        try {
            if (composite && channel.getItemCount() != imp.getNChannels()) {
                channel.removeAllItems();
                for (int c = 1; c <= imp.getNChannels(); c++) channel.addItem("c" + c);
            }
            int ch = selectedChannel(imp);
            ImageStatistics st = HistogramStrip.statsOf(imp, ch);
            strip.setData(st);
            double[] dr = HistogramStrip.displayRange(imp, ch);
            if (imp.getBitDepth() == 8) {
                lo = 0;
                hi = 255;
            } else {
                lo = Math.min(st.min, dr[0]);
                hi = Math.max(st.max, dr[1]);
                if (hi <= lo) hi = lo + 1;
            }
            minSlider.setValue(stepOf(dr[0]));
            maxSlider.setValue(stepOf(dr[1]));
            minField.setText(HistogramStrip.fmt(dr[0]));
            maxField.setText(HistogramStrip.fmt(dr[1]));
            strip.setMarks(dr[0], dr[1], new Color(0x2f6fdb));
            status.setText("<html>Display range " + HistogramStrip.fmt(dr[0]) + " – " + HistogramStrip.fmt(dr[1])
                    + "<br>Data range " + HistogramStrip.fmt(st.min) + " – " + HistogramStrip.fmt(st.max) + "</html>");
            status.setForeground(Theme.muted());
        } finally {
            programmatic = false;
        }
    }

    // ---- display-range requests (AC1) ----------------------------------------------------------

    private void send(double min, double max) {
        ImagePlus imp = imp();
        DocumentId id = ctx.focusedId();
        if (imp == null || id == null) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("min", min);
        p.put("max", max);
        int ch = selectedChannel(imp);
        if (ch > 0) p.put("channel", ch);
        ActionRequest r = ActionRequest.direct(Origin.PANEL, Operations.SET_DISPLAY_RANGE, p, TargetScope.of(id));
        live.post(() -> ctx.submit(r, "Display range"));
        strip.setMarks(min, max, new Color(0x2f6fdb));
    }

    private void sliderMoved(boolean minMoved) {
        if (programmatic) return;
        double a = valueOf(minSlider);
        double b = valueOf(maxSlider);
        programmatic = true;
        try {
            if (a > b) {
                if (minMoved) maxSlider.setValue(minSlider.getValue());
                else minSlider.setValue(maxSlider.getValue());
                a = valueOf(minSlider);
                b = valueOf(maxSlider);
            }
            minField.setText(HistogramStrip.fmt(a));
            maxField.setText(HistogramStrip.fmt(b));
        } finally {
            programmatic = false;
        }
        send(a, b);
    }

    private void fieldEdited() {
        if (programmatic) return;
        try {
            double a = Double.parseDouble(minField.getText().trim());
            double b = Double.parseDouble(maxField.getText().trim());
            if (a > b) {
                status.setText("Minimum must not exceed maximum");
                status.setForeground(Theme.error());
                return;
            }
            ImagePlus imp = imp();
            if (imp != null) {
                double[] cur = HistogramStrip.displayRange(imp, selectedChannel(imp));
                if (cur[0] == a && cur[1] == b) return;
            }
            send(a, b);
        } catch (NumberFormatException e) {
            status.setText("Minimum and maximum must be numbers");
            status.setForeground(Theme.error());
        }
    }

    /** IJ1 ContrastAdjuster.autoAdjust: ignore bins above 1/10 of the pixels, keep those above 1/5000. */
    static double[] autoRange(ImageStatistics st) {
        int[] h = st.histogram;
        int limit = st.pixelCount / 10;
        int threshold = st.pixelCount / AUTO_THRESHOLD;
        int i = -1;
        boolean found;
        do {
            i++;
            int count = h[i];
            if (count > limit) count = 0;
            found = count > threshold;
        } while (!found && i < 255);
        int hmin = i;
        i = 256;
        do {
            i--;
            int count = h[i];
            if (count > limit) count = 0;
            found = count > threshold;
        } while (!found && i > 0);
        int hmax = i;
        if (hmax < hmin) return new double[] {st.min, st.max};
        double min = st.histMin + hmin * st.binSize;
        double max = st.histMin + hmax * st.binSize;
        if (min == max) {
            min = st.min;
            max = st.max;
        }
        return new double[] {min, max};
    }

    private void autoAdjust() {
        ImagePlus imp = imp();
        if (imp == null) return;
        double[] r = autoRange(HistogramStrip.statsOf(imp, selectedChannel(imp)));
        send(r[0], r[1]);
    }

    private void resetRange() {
        ImagePlus imp = imp();
        if (imp == null) return;
        ImageStatistics st = HistogramStrip.statsOf(imp, selectedChannel(imp));
        if (imp.getBitDepth() == 8) send(0, 255);
        else send(st.min, st.max);
    }

    // ---- apply (changes pixels) -----------------------------------------------------------------

    private void applyLut() {
        if (imp() == null) return;
        boolean hasForm = ctx.engine.schema(APPLY_LUT).map(s -> s.schemaComplete() && !s.parameters().isEmpty()).orElse(false);
        if (!hasForm) {
            int c = JOptionPane.showConfirmDialog(ctx.frame,
                    "Apply LUT rewrites the pixel values of this image using the current display range.\nThis changes the data.",
                    "Apply display range to pixels", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (c != JOptionPane.OK_OPTION) return;
        }
        ctx.runCommand(APPLY_LUT, Origin.PANEL, null);
    }

    /** Dev and test hook: set the range as the sliders would. */
    void setRange(double min, double max) {
        send(min, max);
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.DocumentFocusChanged || e instanceof EngineEvent.DocumentsChanged
                || e instanceof EngineEvent.StateDigestEvent) refresh();
    }

    String statusText() {
        return status.getText();
    }
}
