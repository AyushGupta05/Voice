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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import ij.ImagePlus;
import ij.process.AutoThresholder;
import ij.process.ImageProcessor;
import net.miginfocom.swing.MigLayout;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.Origin;

/**
 * Threshold panel (docs D12 UI-15, PAR-014): the semantics of Fiji's Threshold dialog without its
 * window. Method, dark background and the bounds drive the DIRECT operations SET_AUTO_THRESHOLD,
 * SET_THRESHOLD and RESET_THRESHOLD, which only set IJ1's non-destructive threshold (the red
 * overlay is IJ1's own). Pixels change only through the explicit Apply, which opens
 * {@code Convert to Mask} in the Parameters panel so the {@code black} global is visible.
 */
public final class ThresholdPanel extends JPanel {
    static final String CONVERT_TO_MASK = "ij1:Process>Binary>Convert to Mask";

    private final UiContext ctx;
    private final JLabel header = new JLabel();
    private final HistogramStrip strip = new HistogramStrip();
    private final JComboBox<String> method = new JComboBox<>(AutoThresholder.getMethods());
    private final JCheckBox dark = new JCheckBox("Dark background");
    private final JTextField lower = new JTextField(7);
    private final JTextField upper = new JTextField(7);
    private final JButton auto = new JButton("Auto");
    private final JButton reset = new JButton("Reset");
    private final JButton apply = new JButton("Apply → Convert to Mask…");
    private final JLabel status = new JLabel(" ");
    private final Coalescing live = new Coalescing();
    private boolean programmatic;

    ThresholdPanel(UiContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout());
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new MigLayout("insets 6 10 6 10, fillx, wrap 2", "[left,shrink 0]12[grow,fill]", "[]6[]"));
        body.add(strip, "span 2, growx, h 96!");
        method.setSelectedItem("Default");
        method.setLightWeightPopupEnabled(false);
        method.setToolTipText("IJ1 AutoThresholder method");
        method.addActionListener(e -> {
            if (!programmatic) previewAuto();
        });
        dark.setToolTipText("Objects are brighter than the background (macro: the \"dark\" flag)");
        dark.addActionListener(e -> {
            if (!programmatic) previewAuto();
        });
        body.add(new JLabel("Method"));
        body.add(method);
        body.add(new JLabel(""));
        body.add(dark);
        FocusAdapter commit = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitBounds();
            }
        };
        lower.addActionListener(e -> commitBounds());
        upper.addActionListener(e -> commitBounds());
        lower.addFocusListener(commit);
        upper.addFocusListener(commit);
        body.add(new JLabel("Lower"));
        body.add(lower, "w 100!");
        body.add(new JLabel("Upper"));
        body.add(upper, "w 100!");
        auto.setToolTipText("Compute the threshold with the selected method (display only)");
        auto.addActionListener(e -> previewAuto());
        reset.setToolTipText("Remove the threshold (display only)");
        reset.addActionListener(e -> resetThreshold());
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        row.add(auto);
        row.add(reset);
        body.add(row, "span 2");
        status.setForeground(Theme.muted());
        body.add(status, "span 2, gaptop 6, alignx left");
        JLabel note = new JLabel("<html><div style='width:230px'>The red overlay is IJ1's own and is only a preview: pixels are unchanged until you apply.</div></html>");
        note.setForeground(Theme.muted());
        body.add(note, "span 2, alignx left");
        JPanel center = new WidthTrackingPanel(new BorderLayout());
        center.add(body, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(center, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        add(sp, BorderLayout.CENTER);

        JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 6));
        south.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder()));
        apply.setToolTipText("Opens Convert to Mask in the Parameters panel; this changes pixels");
        apply.addActionListener(e -> applyToMask());
        south.add(apply);
        add(south, BorderLayout.SOUTH);
        refresh();
    }

    // ---- requests -----------------------------------------------------------------------------

    private DocumentId doc() {
        return ctx.focusedId();
    }

    /** Set the threshold with the chosen method (also the entry point for scripted screenshots and tests). */
    void autoThreshold(String methodName, boolean darkBackground) {
        programmatic = true;
        method.setSelectedItem(methodName);
        dark.setSelected(darkBackground);
        programmatic = false;
        previewAuto();
    }

    private void previewAuto() {
        DocumentId id = doc();
        if (id == null) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("method", String.valueOf(method.getSelectedItem()));
        p.put("dark", dark.isSelected());
        ActionRequest r = ActionRequest.direct(Origin.PANEL, Operations.SET_AUTO_THRESHOLD, p, sc.fidelis.engine.api.TargetScope.of(id));
        live.post(() -> ctx.submit(r, "Auto threshold " + p.get("method")));
    }

    private void commitBounds() {
        DocumentId id = doc();
        if (id == null || programmatic) return;
        double lo;
        double hi;
        try {
            lo = Double.parseDouble(lower.getText().trim());
            hi = Double.parseDouble(upper.getText().trim());
        } catch (NumberFormatException e) {
            status.setText("Lower and upper must be numbers");
            status.setForeground(Theme.error());
            return;
        }
        if (lo > hi) {
            status.setText("Lower must not exceed upper");
            status.setForeground(Theme.error());
            return;
        }
        ImagePlus imp = ctx.engine.imagePlus(id);
        ImageProcessor ip = imp == null ? null : imp.getProcessor();
        if (ip != null && ip.getMinThreshold() == lo && ip.getMaxThreshold() == hi) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("lower", lo);
        p.put("upper", hi);
        ActionRequest r = ActionRequest.direct(Origin.PANEL, Operations.SET_THRESHOLD, p, sc.fidelis.engine.api.TargetScope.of(id));
        live.post(() -> ctx.submit(r, "Set threshold"));
    }

    private void resetThreshold() {
        DocumentId id = doc();
        if (id == null) return;
        ActionRequest r = ActionRequest.direct(Origin.PANEL, Operations.RESET_THRESHOLD, Map.of(), sc.fidelis.engine.api.TargetScope.of(id));
        live.post(() -> ctx.submit(r, "Reset threshold"));
    }

    /** Pixels change only here, and only after the user has seen the Convert to Mask form. */
    private void applyToMask() {
        DocumentId id = doc();
        ImagePlus imp = id == null ? null : ctx.engine.imagePlus(id);
        if (imp == null || imp.getProcessor().getMinThreshold() == ImageProcessor.NO_THRESHOLD) {
            ctx.notice("Set a threshold first", false);
            return;
        }
        Map<String, Object> prefill = new LinkedHashMap<>();
        prefill.put("method", String.valueOf(method.getSelectedItem()));
        prefill.put("background", dark.isSelected() ? "Dark" : "Light");
        String black = ctx.engine.globals().get("Prefs.blackBackground");
        if (black != null) prefill.put("black", Boolean.parseBoolean(black));
        ctx.runCommand(CONVERT_TO_MASK, Origin.PANEL, prefill);
    }

    // ---- display ------------------------------------------------------------------------------

    void refresh() {
        DocumentId id = doc();
        ImagePlus imp = id == null ? null : ctx.engine.imagePlus(id);
        boolean ok = imp != null && imp.getType() != ImagePlus.COLOR_RGB;
        for (var c : new javax.swing.JComponent[] {method, dark, lower, upper, auto, reset, apply}) c.setEnabled(ok);
        if (imp == null) {
            header.setText("Threshold — no image");
            strip.setMessage("No image");
            status.setText(" ");
            return;
        }
        header.setText("Threshold — " + ctx.engine.document(id).map(d -> d.title()).orElse(imp.getTitle()));
        if (!ok) {
            strip.setMessage("Threshold needs a 8, 16 or 32-bit image");
            status.setText("RGB images: convert first (Image > Type)");
            return;
        }
        strip.setData(HistogramStrip.statsOf(imp, 0));
        ImageProcessor ip = imp.getProcessor();
        programmatic = true;
        try {
            if (ip.getMinThreshold() == ImageProcessor.NO_THRESHOLD) {
                strip.setMarks(Double.NaN, Double.NaN, null);
                if (!lower.isFocusOwner()) lower.setText("");
                if (!upper.isFocusOwner()) upper.setText("");
                status.setText("No threshold set");
            } else {
                double lo = ip.getMinThreshold();
                double hi = ip.getMaxThreshold();
                strip.setMarks(lo, hi, new Color(0xd62728));
                if (!lower.isFocusOwner()) lower.setText(HistogramStrip.fmt(lo));
                if (!upper.isFocusOwner()) upper.setText(HistogramStrip.fmt(hi));
                status.setText("<html>Threshold " + HistogramStrip.fmt(lo) + " – " + HistogramStrip.fmt(hi) + "<br>(preview, pixels unchanged)</html>");
            }
            status.setForeground(Theme.muted());
        } finally {
            programmatic = false;
        }
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.DocumentFocusChanged || e instanceof EngineEvent.DocumentsChanged
                || e instanceof EngineEvent.StateDigestEvent) refresh();
    }

    String statusText() {
        return status.getText();
    }

    String lowerText() {
        return lower.getText();
    }

    String upperText() {
        return upper.getText();
    }
}
