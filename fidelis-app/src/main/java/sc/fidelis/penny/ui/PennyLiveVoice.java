package sc.fidelis.penny.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.SwingUtilities;

import sc.fidelis.penny.voice.PennyVoiceClient;
import sc.fidelis.ui.LiveVoice;

/**
 * The command bar's view of Penny ({@link LiveVoice}): available while the gateway socket is connected, the Mic toggles
 * the continuous conversation, and typed requests are sent as {@code text} and shown in the {@link PennyPanel} strip.
 */
public final class PennyLiveVoice implements LiveVoice {
    private final PennyVoiceClient client;
    private final PennyPanel panel;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final PennyVoiceClient.Listener clientListener = new PennyVoiceClient.Listener() {
        @Override
        public void connection(boolean connected, String detail) {
            fire();
        }

        @Override
        public void voice(PennyVoiceClient.Voice state, String reason) {
            fire();
        }
    };

    public PennyLiveVoice(PennyVoiceClient client, PennyPanel panel) {
        this.client = client;
        this.panel = panel;
        client.addListener(clientListener);
    }

    @Override
    public Phase phase() {
        if (!client.isConnected()) return Phase.UNAVAILABLE;
        return switch (client.voice()) {
            case OFF -> Phase.OFF;
            case STARTING -> Phase.STARTING;
            case LISTENING -> Phase.LISTENING;
        };
    }

    @Override
    public String unavailableReason() {
        return client.isConnected() ? null : client.detail();
    }

    @Override
    public void toggle() {
        client.toggleVoice();
    }

    @Override
    public boolean sendText(String text, boolean spoken) {
        boolean sent = client.sendText(text, spoken);
        if (sent && panel != null) panel.userSaid(text);
        return sent;
    }

    @Override
    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    @Override
    public void removeChangeListener(Runnable r) {
        listeners.remove(r);
    }

    /** Stop following the client (the workspace is closing). */
    public void dispose() {
        client.removeListener(clientListener);
        listeners.clear();
    }

    private void fire() {
        SwingUtilities.invokeLater(() -> {
            for (Runnable r : listeners) r.run();
        });
    }
}
