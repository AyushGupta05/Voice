package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.nl.NlService;

/**
 * The proposal (plan preview) sheet (docs D14 C-08 V1): the interpreted steps with menu paths,
 * parameters with units, the exact macro preview, and any questions, with Confirm, Edit in form
 * and Cancel. The instruction text is editable and re-interprets on Enter. Nothing runs until
 * the user confirms.
 */
final class ProposalSheet extends JPanel {
    private final UiContext ctx;
    private final Origin origin;
    private final JTextField instruction = new JTextField();
    private final JPanel body = new JPanel();
    private final JButton confirm = new JButton("Confirm");
    private final JButton edit = new JButton("Edit in form");
    private final JButton cancel = new JButton("Cancel");
    private final JLabel progress = new JLabel(" ");
    private NlService.Proposal proposal;

    ProposalSheet(UiContext ctx, String text, Origin origin) {
        super(new BorderLayout(0, 4));
        this.ctx = ctx;
        this.origin = origin;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.panelBorder()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        JLabel l = new JLabel(origin == Origin.NL_SPOKEN ? "Instruction (from voice, editable)" : "Instruction (editable)");
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        instruction.setText(text);
        instruction.setToolTipText("Edit and press Enter to interpret again");
        instruction.addActionListener(e -> ctx.commandBar().interpret(instruction.getText().trim(), Origin.NL_TYPED));
        top.add(l, BorderLayout.WEST);
        top.add(instruction, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        add(body, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        progress.setForeground(Theme.muted());
        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(edit);
        right.add(cancel);
        right.add(confirm);
        buttons.add(progress, BorderLayout.CENTER);
        buttons.add(right, BorderLayout.EAST);
        add(buttons, BorderLayout.SOUTH);

        cancel.addActionListener(e -> ctx.banners().clearSheet());
        edit.addActionListener(e -> editInForm());
        confirm.addActionListener(e -> run());
        confirm.setEnabled(false);
        edit.setEnabled(false);
    }

    // ---- states -------------------------------------------------------------------------------

    void showBusy() {
        showBusy(0);
    }

    void showBusy(double seconds) {
        body.removeAll();
        body.add(line(String.format(Locale.ROOT, "Interpreting… %.1f s", seconds), Theme.muted(), false));
        confirm.setEnabled(false);
        edit.setEnabled(false);
        relayout();
    }

    void showUnavailable() {
        body.removeAll();
        body.add(line("Natural-language interpreter not available.", Theme.warning(), true));
        body.add(line("Search for a command above, or use the menus. Typed and spoken instructions need the Voice and NL service,"
                + " which is not registered in this build.", Theme.muted(), false));
        confirm.setEnabled(false);
        edit.setEnabled(false);
        relayout();
    }

    void showError(String message) {
        body.removeAll();
        body.add(line("Interpreter error: " + message, Theme.error(), true));
        confirm.setEnabled(false);
        edit.setEnabled(false);
        relayout();
    }

    void showProposal(NlService.Proposal p, double seconds) {
        this.proposal = p;
        body.removeAll();
        switch (p.status()) {
            case READY -> body.add(line(String.format(Locale.ROOT, "Ready: %d step(s) · interpreted in %.2f s · nothing has run yet",
                    p.requests().size(), seconds), Theme.muted(), false));
            case NEEDS_CLARIFICATION -> body.add(line("Needs clarification before it can run", Theme.warning(), true));
            case REFUSED -> body.add(line("Cannot run: " + (p.refusalReason() == null ? "refused" : p.refusalReason()), Theme.error(), true));
        }
        int n = p.requests() == null ? 0 : p.requests().size();
        for (int i = 0; i < n; i++) body.add(step(p, i, n));
        if (n > 0 && affectedByGlobals(p)) {
            body.add(line("Settings that affect this result (current values)", null, true));
            for (Map.Entry<String, String> g : ctx.engine.globals().entrySet())
                body.add(mono("    " + g.getKey() + " = " + g.getValue()));
        }
        if (p.questions() != null) {
            for (String q : p.questions()) body.add(line("? " + q, Theme.warning(), false));
        }
        boolean ready = p.status() == NlService.Proposal.Status.READY && n > 0;
        boolean irreversible = false;
        for (ActionRequest r : p.requests() == null ? List.<ActionRequest>of() : p.requests()) {
            if (r.commandId() != null && ctx.isIrreversible(r.commandId())) irreversible = true;
        }
        confirm.setText(irreversible ? "Run — cannot be undone" : "Confirm");
        confirm.setEnabled(ready);
        if (ready && !irreversible) setDefaultIfAttached(confirm);
        edit.setEnabled(n > 0 && p.requests().get(0).kind() == sc.fidelis.engine.api.Enums.ActionKind.COMMAND);
        relayout();
    }

    /** True when a step reads result-affecting globals (or is a bare Measure, which uses the current measurement set). */
    private boolean affectedByGlobals(NlService.Proposal p) {
        for (ActionRequest r : p.requests()) {
            if (r.commandId() == null) continue;
            if (r.commandId().endsWith(">Measure") && r.params().isEmpty()) return true;
            if (ctx.engine.schema(r.commandId()).map(s -> !s.hiddenGlobals().isEmpty()).orElse(false)) return true;
        }
        return false;
    }

    private void setDefaultIfAttached(JButton b) {
        if (getRootPane() != null) getRootPane().setDefaultButton(b);
    }

    private JComponent step(NlService.Proposal p, int i, int n) {
        ActionRequest r = p.requests().get(i);
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
        Optional<CommandSchema> schema = r.commandId() == null ? Optional.empty() : ctx.engine.schema(r.commandId());
        String label = schema.map(CommandSchema::label).map(String::trim).orElse(r.commandId() != null ? r.commandId() : r.operation());
        String path = p.menuPaths() != null && i < p.menuPaths().size() ? p.menuPaths().get(i) : schema.map(CommandSchema::menuPath).orElse("");
        String ac = schema.map(s -> s.consequence().name().substring(0, 3)).orElse("");
        box.add(line("Step " + (i + 1) + " of " + n + "   " + label + "     " + path + (ac.isEmpty() ? "" : "     " + ac), null, true));
        Map<String, String> prov = p.slotProvenance() != null && i < p.slotProvenance().size() ? p.slotProvenance().get(i) : Map.of();
        for (Map.Entry<String, Object> e : r.params().entrySet()) {
            Optional<CommandSchema.ParameterSchema> ps = schema.flatMap(s -> s.parameter(e.getKey()));
            String unit = ps.map(CommandSchema.ParameterSchema::unit).filter(u -> u != null && !u.isBlank()).map(u -> " " + u).orElse("");
            String mark = ps.map(CommandSchema.ParameterSchema::consequential).orElse(false) ? "   (affects the result)" : "";
            String src = prov.get(e.getKey());
            box.add(mono("    " + e.getKey() + " = " + e.getValue() + unit + (src != null ? "   [" + src + "]" : "") + mark));
        }
        if (p.macroPreview() != null && i < p.macroPreview().size()) box.add(mono("    " + p.macroPreview().get(i)));
        return box;
    }

    private static JComponent line(String text, Color color, boolean bold) {
        JLabel l = new JLabel(text);
        if (bold) l.setFont(l.getFont().deriveFont(Font.BOLD));
        if (color != null) l.setForeground(color);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent mono(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        l.setForeground(Theme.muted());
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private void relayout() {
        body.revalidate();
        body.repaint();
        revalidate();
        if (getParent() != null) getParent().revalidate();
    }

    // ---- actions ------------------------------------------------------------------------------

    private void editInForm() {
        if (proposal == null || proposal.requests().isEmpty()) return;
        ActionRequest r = proposal.requests().get(0);
        Optional<CommandSchema> schema = ctx.engine.schema(r.commandId());
        if (schema.isEmpty()) return;
        ctx.parameters().show(schema.get(), r.params(), Origin.NL_TYPED);
        ctx.banners().clearSheet();
    }

    /** Submit the proposal's requests in order; stop at the first failure. */
    private void run() {
        if (proposal == null) return;
        confirm.setEnabled(false);
        edit.setEnabled(false);
        List<ActionRequest> reqs = proposal.requests();
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
        for (int i = 0; i < reqs.size(); i++) {
            final int k = i;
            final ActionRequest r = reqs.get(i);
            chain = chain.thenCompose(ok -> {
                if (!ok) return CompletableFuture.completedFuture(false);
                Theme.onEdt(() -> progress.setText("Step " + (k + 1) + " of " + reqs.size() + " running…"));
                String label = r.commandId() != null ? r.commandId().substring(r.commandId().lastIndexOf('>') + 1) : r.operation();
                CompletableFuture<ActionResult> f = ctx.submit(r, label.trim());
                return f.thenApply(res -> res.state() == OperationState.COMPLETED);
            });
        }
        chain.whenComplete((ok, ex) -> Theme.onEdt(() -> {
            if (ex == null && Boolean.TRUE.equals(ok)) ctx.banners().clearSheet();
            else {
                progress.setText("Stopped: a step did not complete (see the status bar and History)");
                progress.setForeground(Theme.error());
                cancel.setText("Close");
            }
        }));
    }

    @Override
    public java.awt.Dimension getMaximumSize() {
        return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    String instructionText() {
        return instruction.getText();
    }

    boolean confirmEnabled() {
        return confirm.isEnabled();
    }
}
