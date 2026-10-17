package sc.fidelis.penny.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;

import sc.fidelis.penny.PennyUi;
import sc.fidelis.penny.voice.PennyVoiceClient;
import sc.fidelis.ui.Theme;

/**
 * Penny in the workspace (the planned voice panel, #18): a one-line strip under the command bar that shows what Penny is
 * doing (offline · ready · listening · thinking · running · speaking, with an animated mark), the live partial
 * transcript, then the acknowledgement and the final spoken answer. It grows into the plan card when the Fidelis bridge
 * shows a plan ({@link PennyUi#showPlan}): the utterance, the steps with live "Step k of n", the macro preview, the reasons
 * a plan needs confirmation and, while it waits, Cancel and "Confirm and run" (sent to the bridge by {@link BridgeCalls}).
 *
 * <p>A 3 px bar on the left (the dialog banner's language) is the accent while Penny is active, the warning colour while
 * a plan waits, and the border colour when idle. The strip is hidden while Penny's gateway has never been reachable.
 * EDT only; {@link #bind} hops client callbacks to the EDT, {@link #dispose} detaches them.
 */
public final class PennyPanel extends JPanel {
    public enum State { OFFLINE, IDLE, STARTING, LISTENING, THINKING, RUNNING, SPEAKING }

    static final int OFFLINE_HIDE_MS = 8_000;
    static final int DONE_COLLAPSE_MS = 6_000;

    // conversation
    private boolean connected;
    private boolean everConnected;
    private String offlineDetail;
    private PennyVoiceClient.Voice voice = PennyVoiceClient.Voice.OFF;
    private boolean working;
    private boolean speaking;
    private String partial = "";
    private String said;
    private String ack;
    private String answer;
    private boolean answerFailed;
    private String error;
    // plan
    private PennyUi.Plan plan;
    private boolean planRunning;
    private boolean planDone;
    private boolean planFailed;
    private int planStep = -1;
    private String planMessage;
    private boolean confirming;
    private boolean alwaysShown;

    private final StripeBorder stripe = new StripeBorder();
    private final JPanel strip = new JPanel(new BorderLayout(10, 0));
    private final PennyIndicator indicator = new PennyIndicator();
    private final JLabel name = new JLabel("Penny");
    private final JLabel stateLabel = new JLabel();
    private final ConversationLine line = new ConversationLine();
    private final JButton stop = new JButton("Stop");
    private final PlanCard card = new PlanCard();
    private final Timer offlineHide = new Timer(OFFLINE_HIDE_MS, e -> update());
    private final Timer doneCollapse = new Timer(DONE_COLLAPSE_MS, e -> collapseFinished());
    private long offlineSince;

    private PennyVoiceClient client;
    private PennyVoiceClient.Listener clientListener;
    private BridgeCalls bridge = BridgeCalls.local();

    public PennyPanel() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(stripe);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        getAccessibleContext().setAccessibleName("Penny");
        offlineHide.setRepeats(false);
        doneCollapse.setRepeats(false);

        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 6));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        name.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
        stateLabel.setForeground(Theme.muted());
        stateLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 3)); // room for the last glyph in every renderer
        left.add(indicator);
        left.add(Box.createHorizontalStrut(6));
        left.add(name);
        left.add(Box.createHorizontalStrut(6));
        left.add(stateLabel);
        strip.add(left, BorderLayout.WEST);
        strip.add(line, BorderLayout.CENTER);
        stop.putClientProperty("JButton.buttonType", "toolBarButton");
        stop.setFocusable(false);
        stop.setMargin(new Insets(0, 6, 0, 6));
        stop.setToolTipText("Stop Penny's current task and speech (completed steps stay; undo them in History)");
        stop.addActionListener(e -> {
            if (client != null) client.stopTask();
        });
        stop.setVisible(false);
        strip.add(stop, BorderLayout.EAST);
        add(strip, BorderLayout.NORTH);
        add(card, BorderLayout.CENTER);
        card.setVisible(false);
        update();
    }

    // ---- wiring --------------------------------------------------------------------------------

    /** Follow a voice client; every callback is re-posted to the EDT. Replaces any earlier client. */
    public void bind(PennyVoiceClient c) {
        unbind();
        client = c;
        if (c == null) return;
        clientListener = new PennyVoiceClient.Listener() {
            @Override public void connection(boolean on, String detail) { edt(() -> setConnected(on, detail)); }
            @Override public void voice(PennyVoiceClient.Voice state, String reason) { edt(() -> setVoice(state)); }
            @Override public void transcript(String text, boolean p, boolean addressed) { edt(() -> heard(text, p, addressed)); }
            @Override public void acknowledged(String taskId, String text) { edt(() -> PennyPanel.this.acknowledged(text)); }
            @Override public void progress(String taskId, String text) { edt(() -> PennyPanel.this.acknowledged(text)); }
            @Override public void result(String taskId, String text, boolean failed) { edt(() -> answered(text, failed)); }
            @Override public void working(boolean w) { edt(() -> setWorking(w)); }
            @Override public void speaking(boolean s) { edt(() -> setSpeaking(s)); }
            @Override public void error(String message) { edt(() -> failed(message)); }
            @Override public void level(double rms) { edt(() -> indicator.setLevel(rms)); }
            @Override public void interrupted(boolean bargeIn) { edt(() -> setSpeaking(false)); }
        };
        c.addListener(clientListener);
        connected = c.isConnected();
        everConnected |= connected;
        update();
    }

    public void unbind() {
        if (client != null && clientListener != null) client.removeListener(clientListener);
        client = null;
        clientListener = null;
    }

    /** Detach from the client and stop every timer (the workspace is closing). */
    public void dispose() {
        unbind();
        offlineHide.stop();
        doneCollapse.stop();
    }

    /** Where Confirm and Cancel are sent (tests use a fake). */
    public void setBridge(BridgeCalls b) {
        bridge = Objects.requireNonNull(b);
    }

    /** Keep the strip visible even while offline (screenshots and demos). */
    public void setAlwaysShown(boolean b) {
        alwaysShown = b;
        update();
    }

    private static void edt(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    // ---- conversation state (EDT) --------------------------------------------------------------

    public void setConnected(boolean on, String detail) {
        connected = on;
        if (on) everConnected = true;
        offlineDetail = detail;
        if (!on) {
            offlineSince = System.currentTimeMillis();
            offlineHide.restart();
            partial = "";
            working = false;
            speaking = false;
            voice = PennyVoiceClient.Voice.OFF;
        }
        update();
    }

    public void setVoice(PennyVoiceClient.Voice v) {
        voice = v == null ? PennyVoiceClient.Voice.OFF : v;
        if (voice != PennyVoiceClient.Voice.LISTENING) {
            partial = "";
            indicator.setLevel(0);
        }
        update();
    }

    /** A live transcript from the gateway. */
    public void heard(String text, boolean isPartial, boolean addressed) {
        if (!addressed) partial = "";
        else if (isPartial) partial = text == null ? "" : text.strip();
        else userSaid(text);
        update();
    }

    /** A request the user typed or said: it starts a new exchange in the line. */
    public void userSaid(String text) {
        partial = "";
        said = text == null || text.isBlank() ? null : text.strip();
        ack = null;
        answer = null;
        answerFailed = false;
        error = null;
        update();
    }

    public void acknowledged(String text) {
        ack = text;
        error = null;
        update();
    }

    public void answered(String text, boolean failed) {
        answer = text;
        answerFailed = failed;
        error = null;
        update();
    }

    public void setWorking(boolean w) {
        working = w;
        update();
    }

    public void setSpeaking(boolean s) {
        speaking = s;
        update();
    }

    public void failed(String message) {
        error = message;
        update();
    }

    public void setLevel(double rms) {
        indicator.setLevel(rms);
    }

    // ---- plan (EDT) ----------------------------------------------------------------------------

    public void showPlan(PennyUi.Plan p, boolean running) {
        doneCollapse.stop();
        plan = p;
        planRunning = running;
        planDone = false;
        planFailed = false;
        planStep = running ? 0 : -1;
        planMessage = null;
        confirming = false;
        card.rebuild();
        update();
    }

    public void planProgress(String planId, int step, String label) {
        if (!matches(planId)) return;
        planRunning = true;
        confirming = false;
        planStep = Math.max(0, step);
        if (label != null && plan.stepLabels() != null && planStep < plan.stepLabels().size()
                && !label.equals(plan.stepLabels().get(planStep))) {
            List<String> labels = new ArrayList<>(plan.stepLabels());
            labels.set(planStep, label);
            plan = new PennyUi.Plan(plan.planId(), plan.utterance(), plan.spoken(), plan.explicit(), plan.reasons(), labels,
                    plan.macroPreview(), plan.context());
        }
        card.rebuild();
        update();
    }

    public void planFinished(String planId, boolean ok, String message) {
        if (!matches(planId)) return;
        planRunning = false;
        confirming = false;
        planDone = ok;
        planFailed = !ok;
        planMessage = message;
        card.rebuild();
        if (ok) doneCollapse.restart();
        update();
    }

    public void clearPlan(String planId) {
        if (plan == null || (planId != null && !planId.equals(plan.planId()))) return;
        plan = null;
        doneCollapse.stop();
        card.rebuild();
        update();
    }

    private boolean matches(String planId) {
        return plan != null && (planId == null || planId.equals(plan.planId()));
    }

    private void collapseFinished() {
        if (plan != null && planDone) clearPlan(plan.planId());
    }

    private boolean waiting() {
        return plan != null && !planRunning && !planDone && !planFailed;
    }

    private void confirmPressed() {
        if (plan == null || confirming) return;
        String id = plan.planId();
        confirming = true;
        planMessage = null;
        card.rebuild();
        update();
        bridge.confirm(id).whenComplete((r, ex) -> edt(() -> {
            if (!matches(id)) return;
            confirming = false;
            if (ex != null) {
                Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                if (planRunning || planStep >= 0) { // it started: the engine said why it stopped
                    planRunning = false;
                    planFailed = true;
                    planMessage = c.getMessage();
                } else {
                    planMessage = "Could not confirm: " + c.getMessage();
                }
            } else if (!planRunning && !planDone && !planFailed) {
                // the bridge answered with the plan's result and no progress hook reported it: the plan ran
                planDone = true;
                doneCollapse.restart();
            } // while progress hooks are reporting, planFinished decides the outcome
            card.rebuild();
            update();
        }));
    }

    private void cancelPressed() {
        if (plan == null) return;
        String id = plan.planId();
        confirming = true;
        card.rebuild();
        bridge.cancel(id).whenComplete((r, ex) -> edt(() -> {
            if (!matches(id)) return;
            confirming = false;
            if (ex == null) clearPlan(id);
            else {
                planMessage = "Could not cancel: " + (ex.getCause() != null ? ex.getCause() : ex).getMessage();
                card.rebuild();
                update();
            }
        }));
    }

    // ---- rendering -----------------------------------------------------------------------------

    State state() {
        if (!connected) return State.OFFLINE;
        if (speaking) return State.SPEAKING;
        if (planRunning) return State.RUNNING;
        if (working || confirming) return State.THINKING;
        if (voice == PennyVoiceClient.Voice.LISTENING) return State.LISTENING;
        if (voice == PennyVoiceClient.Voice.STARTING) return State.STARTING;
        return State.IDLE;
    }

    private void update() {
        State s = state();
        indicator.setMode(switch (s) {
            case OFFLINE -> PennyIndicator.Mode.OFFLINE;
            case IDLE -> PennyIndicator.Mode.IDLE;
            case STARTING -> PennyIndicator.Mode.STARTING;
            case LISTENING -> PennyIndicator.Mode.LISTENING;
            case THINKING, RUNNING -> PennyIndicator.Mode.THINKING;
            case SPEAKING -> PennyIndicator.Mode.SPEAKING;
        });
        stateLabel.setText(switch (s) {
            case OFFLINE -> everConnected ? "Reconnecting…" : "Offline";
            case IDLE -> "Ready";
            case STARTING -> "Starting…";
            case LISTENING -> "Listening";
            case THINKING -> "Thinking";
            case RUNNING -> "Running";
            case SPEAKING -> "Speaking";
        });
        line.set(runs(s));
        stop.setVisible(connected && (working || speaking));
        stop.setText(speaking ? "Stop speaking" : "Stop task");
        Color bar;
        if (plan != null && planFailed) bar = Theme.error();
        else if (waiting()) bar = Theme.warning();
        else if (s == State.OFFLINE || s == State.IDLE) bar = Theme.panelBorder();
        else bar = Theme.accent();
        stripe.color = bar;
        card.setVisible(plan != null);
        boolean show = alwaysShown || connected || plan != null
                || (everConnected && System.currentTimeMillis() - offlineSince < OFFLINE_HIDE_MS);
        if (show != isVisible()) setVisible(show);
        revalidate();
        repaint();
        if (getParent() != null) getParent().revalidate();
    }

    private List<ConversationLine.Run> runs(State s) {
        List<ConversationLine.Run> r = new ArrayList<>();
        if (s == State.OFFLINE) {
            r.add(new ConversationLine.Run(everConnected ? "Penny's gateway went away; reconnecting." : offlineHint(), ConversationLine.Style.HINT));
            return r;
        }
        if (!partial.isEmpty()) {
            r.add(new ConversationLine.Run("“" + partial + "”", ConversationLine.Style.PARTIAL));
            return r;
        }
        if (said != null || ack != null || answer != null || error != null) {
            if (said != null) r.add(new ConversationLine.Run("“" + said + "”", ConversationLine.Style.SAID));
            String sep = r.isEmpty() ? "" : "   ";
            if (error != null) {
                if (!sep.isEmpty()) r.add(new ConversationLine.Run(sep, ConversationLine.Style.SEPARATOR));
                r.add(new ConversationLine.Run(error, ConversationLine.Style.ERROR));
            } else if (answer != null) {
                if (!sep.isEmpty()) r.add(new ConversationLine.Run("  →  ", ConversationLine.Style.SEPARATOR));
                r.add(new ConversationLine.Run(answer, answerFailed ? ConversationLine.Style.ERROR : ConversationLine.Style.ANSWER));
            } else if (ack != null) {
                if (!sep.isEmpty()) r.add(new ConversationLine.Run("  ·  ", ConversationLine.Style.SEPARATOR));
                r.add(new ConversationLine.Run(ack, ConversationLine.Style.ACK));
            } else if (s == State.THINKING) {
                r.add(new ConversationLine.Run("  ·  ", ConversationLine.Style.SEPARATOR));
                r.add(new ConversationLine.Run("working on it…", ConversationLine.Style.ACK));
            }
            return r;
        }
        String hint = switch (s) {
            case LISTENING -> "Just talk. Steps run at once and every one is undoable in History.";
            case STARTING -> "Starting the microphone…";
            case IDLE -> "Press Mic to talk to Penny, or type a request and choose Ask Penny.";
            default -> "";
        };
        if (!hint.isEmpty()) r.add(new ConversationLine.Run(hint, ConversationLine.Style.HINT));
        return r;
    }

    private String offlineHint() {
        return offlineDetail == null || offlineDetail.isBlank() ? "Penny's gateway is not running." : offlineDetail;
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (stateLabel != null && card != null) {
            stateLabel.setForeground(Theme.muted());
            card.themeChanged();
            update();
        }
    }

    // ---- test and demo hooks -------------------------------------------------------------------

    public State displayState() {
        return state();
    }

    public String stateText() {
        return stateLabel.getText();
    }

    public String lineText() {
        return line.text();
    }

    List<ConversationLine.Run> lineRuns() {
        return line.runs();
    }

    public String planStatusText() {
        return card.status.getText();
    }

    public String planMessageText() {
        return card.message.isVisible() ? card.message.getText() : "";
    }

    public List<String> planStepTexts() {
        List<String> out = new ArrayList<>();
        for (StepTrail.Step s : card.trail.steps()) out.add(s.mark().name().toLowerCase(Locale.ROOT) + ":" + s.label());
        return out;
    }

    public boolean confirmShowing() {
        return card.isVisible() && card.confirmRow.isVisible() && card.confirm.isVisible();
    }

    public boolean confirmEnabled() {
        return confirmShowing() && card.confirm.isEnabled();
    }

    public void pressConfirm() {
        if (card.confirm.isEnabled()) card.confirm.doClick();
    }

    public void pressCancel() {
        if (card.cancel.isEnabled()) card.cancel.doClick();
    }

    public boolean planShowing() {
        return plan != null && card.isVisible();
    }

    public boolean stopShowing() {
        return stop.isVisible();
    }

    public PennyIndicator indicator() {
        return indicator;
    }

    // ---- pieces --------------------------------------------------------------------------------

    /** The 3 px state bar on the left of strip and card. */
    private static final class StripeBorder extends AbstractBorder {
        Color color = Color.GRAY;

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            g.setColor(color);
            g.fillRect(x, y, 3, h);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(0, 3, 0, 0);
            return insets;
        }
    }

    /**
     * The plan card: "Plan · image" and the step trail on one row with the status on the right, then the macro preview,
     * the request when it differs from the strip's, and, while the plan waits, the reasons beside Cancel and Confirm.
     */
    private final class PlanCard extends JPanel {
        final JLabel title = new JLabel("Plan");
        final JLabel where = new JLabel();
        final JLabel status = new JLabel(" ");
        final JButton close = new JButton("×");
        final StepTrail trail = new StepTrail();
        final ConversationLine macro = new ConversationLine();
        final JLabel utterance = new JLabel();
        final JLabel message = new JLabel();
        final JPanel reasons = new JPanel();
        final JPanel confirmRow = new JPanel(new BorderLayout(12, 0));
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        final JButton cancel = new JButton("Cancel");
        final JButton confirm = new JButton("Confirm and run");

        PlanCard() {
            super(new BorderLayout(0, 1));
            setOpaque(false);
            themeChanged();
            JPanel top = new JPanel(new BorderLayout(10, 0));
            top.setOpaque(false);
            JPanel titleBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            titleBox.setOpaque(false);
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            titleBox.add(rowHeight(title));
            titleBox.add(rowHeight(where));
            JPanel titleCol = new JPanel(new BorderLayout());
            titleCol.setOpaque(false);
            titleCol.add(titleBox, BorderLayout.NORTH);
            top.add(titleCol, BorderLayout.WEST);
            top.add(trail, BorderLayout.CENTER);
            JPanel statusBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            statusBox.setOpaque(false);
            status.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4)); // room for the last glyph in every renderer
            statusBox.add(rowHeight(status));
            close.putClientProperty("JButton.buttonType", "toolBarButton");
            close.setMargin(new Insets(0, 4, 0, 4));
            close.setFocusable(false);
            close.setToolTipText("Hide this plan (History keeps every step)");
            close.addActionListener(e -> clearPlan(plan == null ? null : plan.planId()));
            statusBox.add(close);
            JPanel statusCol = new JPanel(new BorderLayout());
            statusCol.setOpaque(false);
            statusCol.add(statusBox, BorderLayout.NORTH);
            top.add(statusCol, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            for (javax.swing.JComponent c : new javax.swing.JComponent[] {utterance, macro, message}) {
                c.setAlignmentX(LEFT_ALIGNMENT);
                body.add(c);
            }
            utterance.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
            message.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 4));
            macro.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
            add(body, BorderLayout.CENTER);

            reasons.setOpaque(false);
            reasons.setLayout(new BoxLayout(reasons, BoxLayout.Y_AXIS));
            buttons.setOpaque(false);
            cancel.setToolTipText("Discard this plan: nothing runs");
            confirm.setToolTipText("Run the plan now; every step is recorded in History and the macro");
            cancel.addActionListener(e -> cancelPressed());
            confirm.addActionListener(e -> confirmPressed());
            buttons.add(cancel);
            buttons.add(confirm);
            confirmRow.setOpaque(false);
            confirmRow.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
            confirmRow.add(reasons, BorderLayout.CENTER);
            JPanel buttonCol = new JPanel(new BorderLayout());
            buttonCol.setOpaque(false);
            buttonCol.add(buttons, BorderLayout.SOUTH);
            confirmRow.add(buttonCol, BorderLayout.EAST);
            add(confirmRow, BorderLayout.SOUTH);
        }

        /** Pad a header label to the trail's 20 px row, so title, first trail row and status share a baseline. */
        private static JLabel rowHeight(JLabel l) {
            java.awt.Insets in = l.getInsets();
            int h = l.getFontMetrics(l.getFont()).getHeight() + in.top + in.bottom; // the text height, also for an empty label
            int pad = Math.max(0, 20 - h);
            l.setBorder(BorderFactory.createCompoundBorder(l.getBorder(), BorderFactory.createEmptyBorder(pad / 2, 0, pad - pad / 2, 0)));
            return l;
        }

        void themeChanged() {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 8, 0, 6),
                            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder())),
                    BorderFactory.createEmptyBorder(3, 0, 5, 0)));
            where.setForeground(Theme.muted());
            utterance.setForeground(Theme.muted());
        }

        void rebuild() {
            if (plan == null) {
                trail.set(List.of());
                return;
            }
            String doc = where(plan.context());
            where.setText(doc == null ? "" : "  ·  " + doc);
            where.setVisible(doc != null);
            title.setToolTipText(doc == null ? null : "Plan for " + doc);
            String u = plan.utterance() == null ? "" : plan.utterance().strip();
            boolean showU = !u.isEmpty() && !u.equals(said);
            utterance.setText(showU ? "“" + u + "”" : "");
            utterance.setFont(title.getFont().deriveFont(Font.ITALIC));
            utterance.setVisible(showU);

            List<String> labels = plan.stepLabels() == null ? List.of() : plan.stepLabels();
            int n = labels.size();
            List<StepTrail.Step> steps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                StepTrail.Mark m;
                if (planDone) m = StepTrail.Mark.DONE;
                else if (planFailed && planStep >= 0) m = i < planStep ? StepTrail.Mark.DONE : i == planStep ? StepTrail.Mark.FAILED : StepTrail.Mark.PENDING;
                else if (planRunning) m = i < planStep ? StepTrail.Mark.DONE : i == planStep ? StepTrail.Mark.CURRENT : StepTrail.Mark.PENDING;
                else m = StepTrail.Mark.PENDING;
                steps.add(new StepTrail.Step(labels.get(i).strip(), m));
            }
            trail.set(steps);

            List<String> lines = plan.macroPreview() == null ? List.of() : plan.macroPreview();
            macro.set(lines.isEmpty() ? List.of() : List.of(new ConversationLine.Run(String.join("  ", lines), ConversationLine.Style.CODE)));
            macro.setToolTipText(lines.isEmpty() ? null : "<html><pre>" + escape(String.join("\n", lines)) + "</pre></html>");
            macro.setVisible(!lines.isEmpty());

            boolean wait = waiting();
            reasons.removeAll();
            List<String> rs = plan.reasons() == null ? List.of() : plan.reasons();
            if (wait) {
                if (rs.isEmpty()) reasons.add(reason("Nothing has run yet. Confirm to run the plan, or cancel it."));
                for (String r : rs) reasons.add(reason("•  " + r));
            }
            confirmRow.setVisible(wait);

            boolean hasMessage = planMessage != null && !planMessage.isBlank();
            message.setText(hasMessage ? planMessage : "");
            message.setToolTipText(hasMessage ? planMessage : null);
            message.setForeground(planFailed || (hasMessage && planMessage.startsWith("Could not")) ? Theme.error() : Theme.muted());
            message.setVisible(hasMessage);

            String st;
            Color sc = Theme.muted();
            if (planDone) st = "Done · " + n + (n == 1 ? " step" : " steps") + " · undo in History";
            else if (planFailed) {
                st = planStep >= 0 && n > 0 ? "Stopped at step " + Math.min(planStep + 1, n) + " of " + n : "Stopped";
                sc = Theme.error();
            } else if (planRunning) {
                st = n == 0 ? "Running" : "Step " + Math.min(planStep + 1, n) + " of " + n;
                sc = UIManager.getColor("Label.foreground");
            } else if (confirming) st = "Starting…";
            else {
                st = "Needs your confirmation";
                sc = Theme.warning();
            }
            status.setText(st);
            status.setForeground(sc);
            status.setFont(title.getFont().deriveFont(wait ? Font.BOLD : Font.PLAIN));
            confirm.setEnabled(wait && !confirming);
            cancel.setEnabled(wait && !confirming);
            close.setVisible(!wait);
            revalidate();
            repaint();
        }

        private JLabel reason(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(Theme.warning());
            l.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
            l.setAlignmentX(LEFT_ALIGNMENT);
            l.setToolTipText(text);
            return l;
        }

        private String where(Map<String, String> ctx) {
            if (ctx == null) return null;
            for (String k : new String[] {"document", "title", "image"}) {
                String v = ctx.get(k);
                if (v != null && !v.isBlank()) return v;
            }
            return null;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
