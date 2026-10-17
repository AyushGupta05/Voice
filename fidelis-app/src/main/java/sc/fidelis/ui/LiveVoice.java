package sc.fidelis.ui;

/**
 * A live voice assistant connected to the workspace (Penny, docs/INTEGRATION.md "Voice in the workspace"). When one is
 * attached and available, the command bar's Mic button toggles its continuous conversation instead of the local
 * Whisper push-to-talk, and typed instructions get an "Ask Penny" row. Every method is called on the EDT; change
 * listeners are notified on the EDT.
 */
public interface LiveVoice {
    enum Phase {
        /** Not reachable (gateway down, no token): the Mic keeps the local speech service. */
        UNAVAILABLE,
        /** Reachable, voice off. */
        OFF,
        /** Voice requested; the session or the microphone is starting. */
        STARTING,
        /** The microphone is live and the assistant is listening. */
        LISTENING
    }

    Phase phase();

    /** Why the assistant is unavailable (tooltip text), or null. */
    String unavailableReason();

    /** Start or stop the continuous conversation. */
    void toggle();

    /** Hand a typed request to the assistant; false when it could not be sent. */
    boolean sendText(String text, boolean spoken);

    void addChangeListener(Runnable r);

    void removeChangeListener(Runnable r);
}
