package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import net.miginfocom.swing.MigLayout;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.ParameterType;
import sc.fidelis.engine.api.TargetScope;

/**
 * Schema-driven, non-modal parameter form (docs D14 C-03, D12 UI-07). It replaces the modal
 * GenericDialog for commands whose schema is complete, always produces a complete option map (every
 * key explicit), shows the target, the result-affecting settings read live from the engine, and the
 * macro line the action corresponds to. The line is a UI-side preview; History shows the engine's
 * authoritative lines.
 */
public final class ParametersPanel extends JPanel {
    private static final String EMPTY = "empty";
    private static final String FORM = "form";

    private final UiContext ctx;
    private final CardLayout cards = new CardLayout();
    private final JPanel formHost = new JPanel(new BorderLayout());
    private final JLabel title = new JLabel();
    private final JLabel pathLabel = new JLabel();
    private final JLabel targetStrip = new JLabel();
    private final JLabel message = new JLabel(" ");
    private final JPanel fieldsPanel = new JPanel();
    private final JPanel globalsPanel = new JPanel();
    private final JTextArea macro = new JTextArea(2, 24);
    private final JButton run = new JButton("Run");
    private final JButton cancel = new JButton("Cancel");
    private final JButton copy = new JButton("Copy");
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private CommandSchema schema;
    private Origin origin = Origin.MENU;
    private boolean busy;
    private String runningRequestId;
    private String runningOperationId;

    /** One generated control and its provenance. */
    private static final class Field {
        final ParameterSchema ps;
        JComponent component;
        Supplier<Object> value;
        JLabel provenance = new JLabel();
        JLabel error = new JLabel(" ");
        boolean edited;

        Field(ParameterSchema ps) {
            this.ps = ps;
        }
    }

    ParametersPanel(UiContext ctx) {
        this.ctx = ctx;
        setLayout(cards);
        add(emptyState(), EMPTY);
        add(formHost, FORM);
        buildForm();
        cards.show(this, EMPTY);
    }

    private JComponent emptyState() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JTextArea t = new JTextArea("No command selected.\n\nChoose a command from a menu or from the command bar. "
                + "Commands whose parameters are catalogued open here as a form, pre-filled with the Fiji defaults; "
                + "the exact macro line is shown before you run it.");
        t.setEditable(false);
        t.setOpaque(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setForeground(Theme.muted());
        p.add(t, BorderLayout.NORTH);
        return p;
    }

    private void buildForm() {
        JPanel head = new JPanel(new MigLayout("insets 8 10 4 10, fillx, wrap 1", "[grow,fill]", "[]2[]2[]"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        pathLabel.setForeground(Theme.muted());
        targetStrip.setForeground(Theme.muted());
        head.add(title);
        head.add(pathLabel);
        head.add(targetStrip);
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.panelBorder()), BorderFactory.createEmptyBorder(0, 0, 4, 0)));
        formHost.add(head, BorderLayout.NORTH);

        fieldsPanel.setLayout(new MigLayout("insets 8 10 8 10, fillx, wrap 3", "[right]8[grow,fill]8[]", "[]6[]"));
        globalsPanel.setLayout(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]"));
        JPanel center = new JPanel(new BorderLayout());
        center.add(fieldsPanel, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(center);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(14);
        formHost.add(sp, BorderLayout.CENTER);

        macro.setEditable(false);
        macro.setLineWrap(true);
        macro.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        macro.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        macro.setToolTipText("The exact ImageJ macro line this action corresponds to");
        JPanel south = new JPanel(new MigLayout("insets 6 10 8 10, fillx, wrap 1", "[grow,fill]"));
        south.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder()));
        JPanel macroRow = new JPanel(new BorderLayout(6, 0));
        JScrollPane macroScroll = new JScrollPane(macro);
        macroScroll.setBorder(BorderFactory.createLineBorder(Theme.panelBorder()));
        macroRow.add(macroScroll, BorderLayout.CENTER);
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(macro.getText()), null));
        macroRow.add(copy, BorderLayout.EAST);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(run);
        run.putClientProperty("JButton.buttonType", "default");
        run.addActionListener(e -> run());
        cancel.addActionListener(e -> cancelOrClose());
        message.setForeground(Theme.muted());
        south.add(new JLabel("Macro line"));
        south.add(macroRow);
        south.add(message);
        south.add(buttons);
        formHost.add(south, BorderLayout.SOUTH);
    }

    // ---- showing a command --------------------------------------------------------------------

    /** Open the form for {@code schema}, pre-filled from {@code prefill} (a plan) or the Fiji defaults. */
    public void show(CommandSchema schema, Map<String, Object> prefill, Origin origin) {
        this.schema = schema;
        this.origin = origin;
        this.busy = false;
        this.runningRequestId = null;
        this.runningOperationId = null;
        title.setText(schema.label().trim());
        pathLabel.setText(schema.menuPath() + "   ·   " + schema.consequence().name().substring(0, 3));
        fieldsPanel.removeAll();
        fields.clear();
        message.setText(" ");
        message.setForeground(Theme.muted());
        for (ParameterSchema ps : schema.parameters()) addField(ps, prefill);
        if (schema.parameters().isEmpty()) fieldsPanel.add(muted("This command has no options."), "span 3");
        fieldsPanel.add(globalsHeader(), "span 3, gaptop 10");
        rebuildGlobals();
        fieldsPanel.add(globalsPanel, "span 3, growx");
        updateTarget();
        updateMacro();
        updateRunState();
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
        cards.show(this, FORM);
        ctx.docks().showParameters();
    }

    private JComponent globalsHeader() {
        JLabel l = new JLabel("Settings that affect this result");
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JLabel muted(String s) {
        JLabel l = new JLabel(s);
        l.setForeground(Theme.muted());
        return l;
    }

    private void addField(ParameterSchema ps, Map<String, Object> prefill) {
        Field f = new Field(ps);
        fields.put(ps.key(), f);
        boolean planned = prefill != null && prefill.containsKey(ps.key());
        Object initial = planned ? prefill.get(ps.key()) : null;
        String label = humanise(ps.key()) + (ps.consequential() ? " ◆" : "");
        JLabel lab = new JLabel(label);
        lab.setToolTipText(ps.consequential() ? "Affects the scientific result" : null);
        if (ps.consequential()) lab.setForeground(Theme.accent());
        String unit = ps.unit() == null ? "" : ps.unit();
        JComponent unitLabel = muted(unit);
        switch (ps.type()) {
            case BOOLEAN -> {
                JCheckBox box = new JCheckBox();
                boolean b = planned ? Boolean.parseBoolean(String.valueOf(initial)) : "true".equalsIgnoreCase(ps.defaultValue());
                box.setSelected(b);
                box.addActionListener(e -> touched(f));
                f.component = box;
                f.value = box::isSelected;
            }
            case CHOICE -> {
                JComboBox<String> combo = new JComboBox<>(ps.choices().toArray(new String[0]));
                combo.setLightWeightPopupEnabled(false);
                String sel = planned ? String.valueOf(initial) : ps.defaultValue();
                combo.setSelectedItem(sel);
                combo.addActionListener(e -> touched(f));
                f.component = combo;
                f.value = () -> String.valueOf(combo.getSelectedItem());
            }
            case NUMBER, INTEGER -> {
                JTextField tf = new JTextField(planned ? numberText(initial) : ps.defaultValue(), 8);
                tf.getDocument().addDocumentListener(simple(() -> touched(f)));
                f.component = tf;
                boolean integer = ps.type() == ParameterType.INTEGER;
                f.value = () -> {
                    String t = tf.getText().trim();
                    if (t.isEmpty()) throw new NumberFormatException("Choose a value");
                    double d = Double.parseDouble(t);
                    if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException("Not a finite number");
                    return integer ? (Object) Math.round(d) : (Object) d;
                };
            }
            default -> {
                JTextField tf = new JTextField(planned ? String.valueOf(initial) : ps.defaultValue(), 14);
                tf.getDocument().addDocumentListener(simple(() -> touched(f)));
                f.component = tf;
                f.value = tf::getText;
                if (ps.type() == ParameterType.PATH) {
                    JButton browse = new JButton("…");
                    browse.addActionListener(e -> {
                        JFileChooser fc = new JFileChooser();
                        if (fc.showOpenDialog(ctx.frame) == JFileChooser.APPROVE_OPTION) tf.setText(fc.getSelectedFile().getPath());
                    });
                    unitLabel = browse;
                }
            }
        }
        f.provenance.setText(planned ? "from plan" : (ps.defaultValue() == null || ps.defaultValue().isEmpty() ? "" : "Fiji default"));
        f.provenance.setForeground(Theme.muted());
        f.provenance.setFont(f.provenance.getFont().deriveFont(11f));
        f.component.setToolTipText(ps.help() == null || ps.help().isBlank() ? ps.key() : ps.key() + ": " + ps.help());
        fieldsPanel.add(lab);
        fieldsPanel.add(f.component);
        fieldsPanel.add(unitLabel);
        JLabel key = new JLabel("key: " + ps.key());
        key.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        key.setForeground(Theme.muted());
        fieldsPanel.add(key, "skip 1, split 2");
        fieldsPanel.add(f.provenance, "gapleft 8, wrap");
        f.error.setForeground(Theme.error());
        f.error.setFont(f.error.getFont().deriveFont(11f));
        fieldsPanel.add(f.error, "skip 1, span 2, wrap");
    }

    private static String numberText(Object o) {
        if (o instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e9) return Long.toString(d.longValue());
        return String.valueOf(o);
    }

    private static String humanise(String key) {
        String s = key.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static DocumentListener simple(Runnable r) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        };
    }

    private void touched(Field f) {
        if (!f.edited) {
            f.edited = true;
            f.provenance.setText("edited");
        }
        updateMacro();
        updateRunState();
    }

    // ---- values, macro line -------------------------------------------------------------------

    /** The complete option map, or null when a field is invalid (errors are shown beside the fields). */
    Map<String, Object> values() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ok = true;
        for (Field f : fields.values()) {
            try {
                out.put(f.ps.key(), f.value.get());
                f.error.setText(" ");
            } catch (NumberFormatException e) {
                f.error.setText(e.getMessage() != null && e.getMessage().startsWith("Choose") ? "Choose a value" : "Not a number");
                ok = false;
            }
        }
        return ok ? out : null;
    }

    private Map<String, Object> valuesQuiet() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : fields.values()) {
            try {
                out.put(f.ps.key(), f.value.get());
            } catch (NumberFormatException e) {
                out.put(f.ps.key(), "?");
            }
        }
        return out;
    }

    /** The macro line for the given values, following IJ1's option syntax (booleans are present or absent). */
    static String macroLine(String label, Map<String, Object> values) {
        StringBuilder o = new StringBuilder();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean b) {
                if (b) o.append(e.getKey()).append(' ');
                continue;
            }
            String s = v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString(d.longValue()) : String.valueOf(v);
            if (s.isEmpty()) continue;
            o.append(e.getKey()).append('=').append(s.indexOf(' ') >= 0 ? "[" + s + "]" : s).append(' ');
        }
        String opts = o.toString().trim();
        return "run(\"" + label.trim() + "\"" + (opts.isEmpty() ? "" : ", \"" + opts + "\"") + ");";
    }

    private void updateMacro() {
        if (schema == null) return;
        macro.setText(macroLine(schema.label(), valuesQuiet()));
        macro.setCaretPosition(0);
    }

    private void rebuildGlobals() {
        globalsPanel.removeAll();
        List<String> g = schema == null ? List.of() : schema.hiddenGlobals();
        Map<String, String> live = ctx.engine.globals();
        if (g.isEmpty()) {
            globalsPanel.add(muted("None for this command."));
        } else {
            for (String name : g) {
                String v = live.get(name);
                if (v == null) {
                    for (Map.Entry<String, String> e : live.entrySet()) {
                        if (name.contains(e.getKey()) || e.getKey().contains(name)) {
                            v = e.getValue();
                            name = e.getKey();
                            break;
                        }
                    }
                }
                JLabel l = new JLabel(name + (v != null ? " = " + v : ""));
                l.setToolTipText("Read live from the engine; change it with the original Fiji options command");
                globalsPanel.add(l);
            }
        }
        globalsPanel.revalidate();
        globalsPanel.repaint();
    }

    private void updateTarget() {
        Optional<DocumentInfo> info = ctx.focusedInfo();
        if (info.isEmpty()) {
            targetStrip.setText("Target: none (open an image first)");
            targetStrip.setForeground(Theme.warning());
            return;
        }
        DocumentInfo d = info.get();
        String roi = ctx.engine.activeRoi(d.id()).map(r -> r.type().toLowerCase(Locale.ROOT)).orElse("none");
        StringBuilder pos = new StringBuilder();
        var imp = ctx.engine.imagePlus(d.id());
        if (d.nChannels() > 1) pos.append(" c:").append(imp.getChannel()).append('/').append(d.nChannels());
        if (d.nSlices() > 1) pos.append(" z:").append(imp.getSlice()).append('/').append(d.nSlices());
        if (d.nFrames() > 1) pos.append(" t:").append(imp.getFrame()).append('/').append(d.nFrames());
        targetStrip.setText("Target: " + d.title() + " (id " + d.imagePlusId() + ")" + pos + "  ·  ROI: " + roi);
        targetStrip.setForeground(Theme.muted());
    }

    private void updateRunState() {
        boolean ready = ctx.engine.state() == EngineState.READY;
        run.setEnabled(ready && !busy && schema != null);
        run.setToolTipText(ready ? null : "The engine is not ready");
        for (Field f : fields.values()) f.component.setEnabled(!busy);
        cancel.setText(busy ? "Cancel operation" : "Cancel");
    }

    // ---- run ----------------------------------------------------------------------------------

    private void run() {
        if (schema == null || busy) return;
        Map<String, Object> v = values();
        if (v == null) {
            message.setText("Fix the highlighted fields first");
            message.setForeground(Theme.error());
            return;
        }
        TargetScope t = ctx.target();
        if (t != null && (Boolean.TRUE.equals(v.get("stack")) || Boolean.TRUE.equals(v.get("process_all"))))
            t = TargetScope.wholeStack(t.document());
        ActionRequest req = ActionRequest.command(origin, schema.commandId(), v, t);
        runningRequestId = req.requestId();
        busy = true;
        message.setText("Running…");
        message.setForeground(Theme.muted());
        updateRunState();
        ctx.submit(req, schema.label().trim()).whenComplete((r, ex) -> Theme.onEdt(() -> finished(r, ex)));
    }

    private void finished(ActionResult r, Throwable ex) {
        busy = false;
        runningRequestId = null;
        runningOperationId = null;
        if (ex != null) {
            message.setText("Failed: " + ex);
            message.setForeground(Theme.error());
        } else if (r.state() == OperationState.COMPLETED) {
            message.setText("Done in " + r.durationMs() + " ms");
            message.setForeground(Theme.muted());
        } else if (r.state() == OperationState.AWAITING_CONFIRMATION) {
            message.setText("Awaiting confirmation");
            message.setForeground(Theme.warning());
        } else {
            message.setText((r.error() != null ? r.error().message() : r.state().toString()));
            message.setForeground(r.state() == OperationState.CANCELLED ? Theme.muted() : Theme.error());
        }
        updateRunState();
        rebuildGlobals();
        updateTarget();
    }

    private void cancelOrClose() {
        if (busy) {
            if (runningOperationId != null) ctx.engine.cancel(runningOperationId);
            message.setText("Cancelling…");
        } else {
            schema = null;
            fields.clear();
            cards.show(this, EMPTY);
        }
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.OperationStateChanged s && s.requestId() != null && s.requestId().equals(runningRequestId)) {
            runningOperationId = s.operationId();
            if (s.state() == OperationState.RUNNING) message.setText("Running…");
            else if (s.state() == OperationState.WAITING_FOR_DIALOG) message.setText("Waiting for input in a Fiji dialog");
        } else if (e instanceof EngineEvent.EngineStateChanged
                || e instanceof EngineEvent.DocumentFocusChanged
                || e instanceof EngineEvent.DocumentsChanged
                || e instanceof EngineEvent.StateDigestEvent) {
            if (schema != null) {
                updateTarget();
                updateRunState();
                rebuildGlobals();
            }
        }
    }

    // ---- test hooks ---------------------------------------------------------------------------

    CommandSchema currentSchema() {
        return schema;
    }

    String macroText() {
        return macro.getText();
    }

    List<String> fieldKeys() {
        return new ArrayList<>(fields.keySet());
    }
}
