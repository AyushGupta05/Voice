package sc.fidelis.penny.voice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps Penny from transcribing her own voice and still lets the user talk over her (barge-in). Java Sound has no echo
 * cancellation, so while reply audio plays the microphone frames are held back for {@value #HOLD} frames (300 ms) and
 * then sent as silence, like the Swift companion's muted fallback. A frame is the user, not the echo, when it is louder
 * than both an absolute floor and {@value #RATIO}× the echo expected from what is playing right now
 * ({@code reference} = the loudest reply audio of the last few hundred milliseconds, times a learned coupling). After
 * {@value #SUSTAIN} such frames in a row (300 ms) the gate fires: the held frames are sent as they were, so the
 * transcriber hears the start of the sentence, playback stops locally and the gate stays open until playback ends.
 *
 * <p>The coupling (echo level per unit of playback level) is learned from frames that are not the user and decays
 * slowly, so a quiet headset converges to "any speech barges in" and loud laptop speakers to "speak up to interrupt".
 * Pure logic; one instance per voice session, called from the client's loop thread.
 */
public final class EchoGate {
    static final int HOLD = 3;
    static final int SUSTAIN = 3;
    /** Post-gain RMS below which a frame is never treated as the user. */
    static final double MIN_LEVEL = 0.02;
    static final double RATIO = 2.0;
    /** Coupling assumed before anything was learned: echo at the playback level. */
    static final double INITIAL_COUPLING = 1.0;
    /** Frames at the start of the first playback that only learn the coupling. */
    static final int WARMUP = 3;

    /** What to send now, and whether the user started talking over Penny with this frame. */
    public record Result(List<byte[]> send, boolean bargeIn) {}

    private final ArrayDeque<byte[]> held = new ArrayDeque<>();
    private double coupling = INITIAL_COUPLING;
    private boolean learned;
    private int learnedSamples;
    private int loud;
    private int playingFrames;
    private boolean open;

    /**
     * @param frame     the microphone frame (PCM16 LE)
     * @param rms       its RMS after gain
     * @param reference RMS of the reply audio played around this frame's time (0 when nothing is audible)
     * @param playing   whether reply audio is playing (or just finished, including the room's tail)
     */
    public Result accept(byte[] frame, double rms, double reference, boolean playing) {
        List<byte[]> send = new ArrayList<>(HOLD + 1);
        if (!playing) {
            // anything still held was captured while Penny was audible: it goes out as silence
            while (!held.isEmpty()) send.add(new byte[held.poll().length]);
            send.add(frame);
            loud = 0;
            playingFrames = 0;
            open = false;
            return new Result(send, false);
        }
        if (open) {
            send.add(frame);
            return new Result(send, false);
        }
        playingFrames++;
        boolean warming = !learned && playingFrames <= WARMUP;
        boolean user = !warming && rms > MIN_LEVEL && rms > RATIO * coupling * reference;
        if (user) loud++;
        else {
            loud = 0;
            if (reference > 0.005) {
                double observed = Math.min(rms / reference, 4.0);
                if (!learned) {
                    coupling = learnedSamples == 0 ? observed : Math.max(coupling, observed);
                    if (++learnedSamples >= WARMUP) learned = true;
                } else {
                    coupling = Math.max(coupling * 0.98, observed);
                }
            }
        }
        held.add(frame);
        while (held.size() > HOLD) send.add(new byte[held.poll().length]); // older than the hold: echo, sent as silence
        if (loud >= SUSTAIN) {
            open = true;
            while (!held.isEmpty()) send.add(held.poll()); // the held frames are the user's first words
            return new Result(send, true);
        }
        return new Result(send, false);
    }

    /** Drop held audio (session reset or stop). */
    public void reset() {
        held.clear();
        loud = 0;
        playingFrames = 0;
        open = false;
    }

    /** The user is talking over Penny: frames pass through until playback ends. */
    public boolean passing() {
        return open;
    }

    double coupling() {
        return coupling;
    }

    int heldFrames() {
        return held.size();
    }
}
