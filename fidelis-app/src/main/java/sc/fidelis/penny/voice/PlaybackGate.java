package sc.fidelis.penny.voice;

/**
 * Which reply audio may play, by epoch (speech-session.ts; the same rules as the Swift companion's
 * {@code playbackEpoch}/{@code playbackSuppressed}): every spoken reply and every interruption advances the gateway's
 * epoch. {@code voice_speaking{epoch, queued}} with an epoch at least the current one starts that reply (a non-queued
 * reply replaces what is playing; a queued final answer follows its acknowledgement); {@code audio} plays only for the
 * current epoch while not suppressed; {@code interrupted{epoch}} stops playback and moves to its epoch. A local stop
 * (the user's Stop, or barge-in) suppresses the current epoch until the gateway starts a newer one.
 * Pure state, used from the client's loop thread.
 */
public final class PlaybackGate {
    /** What the speaker must do for an event. */
    public enum Effect { NONE, FLUSH }

    private int epoch;
    private boolean suppressed;
    private boolean speaking;
    private boolean finalQueued;

    /** {@code voice_speaking}: returns FLUSH when the reply replaces current playback. Older epochs are ignored. */
    public Effect speaking(int e, boolean queued) {
        if (e < epoch) return Effect.NONE;
        epoch = e;
        suppressed = false;
        speaking = true;
        finalQueued = false;
        return queued ? Effect.NONE : Effect.FLUSH;
    }

    /** {@code audio}: whether the chunk of epoch {@code e} plays. Records that the reply's last chunk arrived. */
    public boolean accept(int e, boolean last) {
        if (e != epoch || suppressed) return false;
        if (last) finalQueued = true;
        return true;
    }

    /** {@code interrupted}: playback stops and the gate moves to the gateway's epoch. */
    public Effect interrupted(int e) {
        epoch = Math.max(epoch, e);
        suppressed = false;
        speaking = false;
        finalQueued = false;
        return Effect.FLUSH;
    }

    /** A local stop (Stop button, barge-in): the rest of the current reply is dropped. */
    public Effect suppress() {
        suppressed = true;
        speaking = false;
        finalQueued = false;
        return Effect.FLUSH;
    }

    /** The speaker drained: a reply whose last chunk has arrived is over. Returns true when speaking ended now. */
    public boolean drained() {
        if (speaking && finalQueued) {
            speaking = false;
            finalQueued = false;
            return true;
        }
        return false;
    }

    /** New voice session: epochs restart at 0. */
    public void reset() {
        epoch = 0;
        suppressed = false;
        speaking = false;
        finalQueued = false;
    }

    public int epoch() {
        return epoch;
    }

    public boolean isSpeaking() {
        return speaking;
    }

    public boolean isSuppressed() {
        return suppressed;
    }
}
