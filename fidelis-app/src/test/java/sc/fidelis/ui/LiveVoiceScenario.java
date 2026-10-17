package sc.fidelis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.speech.SpeechService;
import sc.fidelis.ui.dev.StubEngine;

/**
 * Scenario for {@link CommandBarLiveVoiceTest} (kept apart so no ij.* class loads before the patcher): the Mic and the
 * typed-instruction rows with a live voice attached (Penny) and without it (local Whisper push-to-talk unchanged).
 */
final class LiveVoiceScenario {
    private static Workspace ws;

    static void start() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            ws = Workspace.create(new StubEngine(null), null, new java.awt.Dimension(1200, 800));
            ws.frame().setVisible(true);
        });
        waitFor("engine ready", () -> ws.engine().state() == EngineState.READY, 30);
    }

    static void stop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (ws != null) ws.frame().dispose();
        });
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    /** A live voice whose phase the test sets; it records what the command bar asks of it. */
    static final class FakeLive implements LiveVoice {
        volatile Phase phase = Phase.UNAVAILABLE;
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile int toggles;
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        void set(Phase p) {
            phase = p;
            for (Runnable r : listeners) r.run(); // called on the EDT by the test
        }

        @Override public Phase phase() { return phase; }
        @Override public String unavailableReason() { return phase == Phase.UNAVAILABLE ? "gateway not reachable" : null; }
        @Override public void toggle() { toggles++; }
        @Override public boolean sendText(String text, boolean spoken) {
            if (phase == Phase.UNAVAILABLE) return false;
            sent.add(text);
            return true;
        }
        @Override public void addChangeListener(Runnable r) { listeners.add(r); }
        @Override public void removeChangeListener(Runnable r) { listeners.remove(r); }
    }

    /** The local speech service, READY, counting recordings. */
    static final class FakeSpeech implements SpeechService {
        volatile int recordings;

        public State state() { return State.READY; }
        public String unavailableReason() { return null; }
        public void addListener(Listener l) { }
        public void removeListener(Listener l) { }
        public void startRecording() { recordings++; }
        public CompletableFuture<Transcript> stopRecording() { return CompletableFuture.completedFuture(null); }
        public CompletableFuture<Transcript> transcribeFile(Path audio) { return CompletableFuture.completedFuture(null); }
        public void setVocabularyPrompt(String p) { }
    }

    static void micFollowsPennyAndFallsBackToLocalSpeech() throws Exception {
        FakeLive live = new FakeLive();
        FakeSpeech speech = new FakeSpeech();
        CommandBar bar = ws.commandBar();
        edt(() -> {
            bar.attachSpeech(speech);
            bar.setLiveVoice(live);
        });
        assertTrue(ws.banners().penny() != null, "the Penny strip is mounted in the banner slot");
        assertFalse(ws.penny().isVisible(), "and hidden while Penny was never connected");

        // Penny unavailable: today's push-to-talk, unchanged
        edt(bar::toggleMic);
        assertEquals(1, speech.recordings, "without Penny the Mic records locally");
        assertEquals(0, live.toggles);
        assertFalse(bar.micButton().getToolTipText().contains("Penny"), bar.micButton().getToolTipText());

        // Penny reachable: the Mic toggles her continuous conversation
        edt(() -> live.set(LiveVoice.Phase.OFF));
        assertTrue(bar.micButton().isEnabled());
        assertTrue(bar.micButton().getToolTipText().contains("Talk to Penny"), bar.micButton().getToolTipText());
        assertFalse(bar.micButton().isSelected());
        edt(bar::toggleMic);
        assertEquals(1, live.toggles);
        assertEquals(1, speech.recordings, "the local recorder is not started when Penny has the Mic");

        edt(() -> live.set(LiveVoice.Phase.LISTENING));
        assertTrue(bar.micButton().isSelected());
        assertEquals("Stop", bar.micButton().getText());
        assertTrue(bar.isRecordingIndicatorVisible(), "the red recording indicator shows while Penny's microphone is live");
        edt(bar::toggleMic);
        assertEquals(2, live.toggles, "pressing again stops the conversation");

        edt(() -> live.set(LiveVoice.Phase.UNAVAILABLE));
        assertFalse(bar.isRecordingIndicatorVisible());
        assertEquals("Mic", bar.micButton().getText());
        edt(() -> bar.setLiveVoice(null));
        assertTrue(live.listeners.isEmpty(), "detaching removes the change listener (#16)");
    }

    private static JTextField field(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JTextField f) return f;
            if (k instanceof Container cc) {
                JTextField f = field(cc);
                if (f != null) return f;
            }
        }
        return null;
    }

    static void typedInstructionsGoToPennyWhenConnected() throws Exception {
        FakeLive live = new FakeLive();
        CommandBar bar = ws.commandBar();
        JTextField field = field(bar);
        assertNotNull(field);
        String text = "segment the cells and count them";

        edt(() -> {
            bar.setLiveVoice(live);
            bar.focusField();
            bar.setText(text);
        });
        waitFor("popup", bar::popupVisible, 10);
        Thread.sleep(150);
        List<String> rows = bar.visibleRowLabels();
        assertFalse(rows.contains("Ask Penny"), "no Penny row while she is unavailable: " + rows);
        assertEquals("Interpret as instruction", rows.get(rows.size() - 1));

        edt(() -> {
            live.set(LiveVoice.Phase.OFF);
            bar.setText("");
            bar.setText(text);
        });
        waitFor("popup with Penny", () -> bar.popupVisible() && bar.visibleRowLabels().contains("Ask Penny"), 10);
        rows = bar.visibleRowLabels();
        assertEquals("Ask Penny", rows.get(rows.size() - 2), rows.toString());
        assertEquals("Interpret as instruction", rows.get(rows.size() - 1), "the local interpreter stays available: " + rows);

        // Enter on instruction-like text: Penny (the default row), and the field clears
        edt(() -> field.dispatchEvent(new KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n')));
        waitFor("sent to Penny", () -> live.sent.size() == 1, 5);
        assertEquals(text, live.sent.get(0));
        assertEquals("", bar.text());
        assertFalse(bar.popupVisible());
        edt(() -> bar.setLiveVoice(null));
    }
}
