package sc.fidelis.penny.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The pure audio logic of Penny's workspace voice: resampling and framing, gain, playback epochs and barge-in. */
class VoiceAudioLogicTest {
    private static short[] sine(int rate, double hz, double amplitude, int n, long offset) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) Math.round(Math.sin(2 * Math.PI * hz * (i + offset) / rate) * amplitude * 32767);
        return s;
    }

    private static List<MicProcessor.Frame> feed(MicProcessor p, int rate, double hz, double amplitude, double seconds) {
        List<MicProcessor.Frame> out = new ArrayList<>();
        int chunk = rate / 50;
        long n = 0;
        while (n < rate * seconds) {
            out.addAll(p.accept(sine(rate, hz, amplitude, chunk, n), chunk));
            n += chunk;
        }
        return out;
    }

    private static short[] samples(byte[] pcm) {
        short[] s = new short[pcm.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
        return s;
    }

    @Test
    void framesAreHundredMillisecondsOf24kHzPcm16FromAnyDeviceRate() {
        for (int rate : new int[] {48_000, 44_100, 24_000, 16_000}) {
            MicProcessor p = new MicProcessor(rate, 1f);
            List<MicProcessor.Frame> frames = feed(p, rate, 440, 0.1, 2.0);
            // two seconds in, 20 frames of 100 ms out (the resampler holds back at most one sample)
            assertTrue(frames.size() == 20 || frames.size() == 19, rate + " Hz gave " + frames.size() + " frames");
            for (MicProcessor.Frame f : frames) {
                assertEquals(4800, f.pcm().length);
                assertTrue(Base64.getEncoder().encodeToString(f.pcm()).length() <= PennyVoiceClient.MAX_FRAME_BASE64);
            }
            // the 440 Hz tone survives: ~88 zero crossings per 100 ms at 24 kHz
            short[] s = samples(frames.get(10).pcm());
            int crossings = 0;
            for (int i = 1; i < s.length; i++) if ((s[i - 1] < 0) != (s[i] < 0)) crossings++;
            assertTrue(Math.abs(crossings - 88) <= 3, rate + " Hz: " + crossings + " zero crossings");
        }
    }

    @Test
    void gainBringsQuietSpeechUpButNotSilenceAndFreezesWhilePennySpeaks() {
        MicProcessor quiet = new MicProcessor(48_000, 6f);
        List<MicProcessor.Frame> f = feed(quiet, 48_000, 300, 0.004, 3.0); // the quiet Mac microphone (~0.003 peak)
        assertTrue(quiet.gain() > 20, "gain " + quiet.gain());
        assertTrue(f.get(f.size() - 1).rms() > 0.05, "speech is brought towards the transcriber's level: " + f.get(f.size() - 1).rms());

        MicProcessor loud = new MicProcessor(48_000, 6f);
        f = feed(loud, 48_000, 300, 0.5, 2.0);
        assertTrue(loud.gain() < 1.2, "a loud microphone is not amplified into clipping: " + loud.gain());

        MicProcessor silent = new MicProcessor(48_000, 6f);
        f = feed(silent, 48_000, 300, 0.0005, 2.0); // below the noise floor: the gain must not chase it
        assertEquals(6f, silent.gain(), 1e-6);

        MicProcessor frozen = new MicProcessor(48_000, 6f);
        frozen.setFrozen(true);
        feed(frozen, 48_000, 300, 0.5, 1.0);
        assertEquals(6f, frozen.gain(), 1e-6, "Penny's echo must not turn the user down");

        MicProcessor zero = new MicProcessor(48_000, 6f);
        f = zero.accept(new short[9600], 9600);
        assertTrue(f.get(0).silentInput(), "exact digital silence (no microphone permission on macOS) is flagged");
    }

    @Test
    void playbackFollowsTheGatewaysEpochs() {
        PlaybackGate g = new PlaybackGate();
        assertFalse(g.accept(1, false), "no audio before voice_speaking");
        assertEquals(PlaybackGate.Effect.FLUSH, g.speaking(1, false), "a new reply replaces current playback");
        assertTrue(g.accept(1, false));
        assertTrue(g.isSpeaking());
        assertEquals(PlaybackGate.Effect.NONE, g.speaking(2, true), "a queued final answer follows its acknowledgement");
        assertFalse(g.accept(1, false), "the acknowledgement's late chunks stop once the answer's epoch begins");
        assertTrue(g.accept(2, true));
        assertTrue(g.drained(), "the reply whose last chunk arrived ends when the speaker drains");
        assertFalse(g.isSpeaking());
        assertEquals(PlaybackGate.Effect.NONE, g.speaking(1, false), "an older epoch is ignored");
        assertEquals(2, g.epoch());

        g.interrupted(3);
        assertFalse(g.isSpeaking());
        assertFalse(g.accept(2, true), "an interrupted reply never resumes");
        assertEquals(3, g.epoch());

        g.speaking(4, false);
        assertTrue(g.accept(4, false));
        g.suppress(); // the user's Stop or barge-in
        assertFalse(g.accept(4, true), "the rest of a locally stopped reply is dropped");
        assertTrue(g.isSuppressed());
        g.speaking(5, false);
        assertFalse(g.isSuppressed(), "the next reply plays again");
        assertTrue(g.accept(5, true));
        assertTrue(g.drained(), "speaking ends when the last chunk has played");
        assertFalse(g.isSpeaking());
        g.reset();
        assertEquals(0, g.epoch());
    }

    private static byte[] frame(int value) {
        byte[] b = new byte[4800];
        for (int i = 0; i < b.length; i += 2) {
            b[i] = (byte) value;
            b[i + 1] = (byte) (value >> 8);
        }
        return b;
    }

    private static boolean silent(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    @Test
    void echoIsMutedButTheUserTalkingOverPennyBargesIn() {
        EchoGate g = new EchoGate();
        // not playing: frames pass through untouched
        EchoGate.Result r = g.accept(frame(1000), 0.03, 0, false);
        assertEquals(1, r.send().size());
        assertFalse(silent(r.send().get(0)));

        // Penny speaking at 0.2; the room's echo at ~0.06 (coupling 0.3): muted, nothing barges in
        int sent = 0;
        for (int i = 0; i < 20; i++) {
            r = g.accept(frame(2000), 0.06 + 0.01 * Math.sin(i), 0.2, true);
            assertFalse(r.bargeIn(), "echo is not the user (frame " + i + ")");
            for (byte[] b : r.send()) assertTrue(silent(b), "echo goes out as silence");
            sent += r.send().size();
        }
        assertEquals(20 - EchoGate.HOLD, sent, "frames are held back " + EchoGate.HOLD + " frames, never dropped");
        assertTrue(Math.abs(g.coupling() - 0.35) < 0.1, "coupling learned: " + g.coupling());

        // the user speaks over Penny, clearly louder than the echo: after SUSTAIN frames the held real audio goes out
        byte[] user = frame(9000);
        for (int i = 0; i < EchoGate.SUSTAIN - 1; i++) assertFalse(g.accept(user, 0.4, 0.2, true).bargeIn());
        r = g.accept(user, 0.4, 0.2, true);
        assertTrue(r.bargeIn());
        List<byte[]> out = r.send();
        assertTrue(out.size() >= EchoGate.HOLD);
        for (int i = 0; i < out.size(); i++) {
            if (i < out.size() - EchoGate.HOLD) assertTrue(silent(out.get(i)), "older held echo stays silent");
            else assertArrayEquals(user, out.get(i), "the start of the sentence is not lost");
        }
        assertTrue(g.passing());
        r = g.accept(user, 0.4, 0.2, true);
        assertEquals(1, r.send().size());
        assertFalse(silent(r.send().get(0)), "the gate stays open while playback ends");

        // playback ended: the gate resets
        r = g.accept(frame(100), 0.01, 0, false);
        assertFalse(g.passing());
        assertEquals(1, r.send().size());
    }

    @Test
    void speechInAPauseOfPennysReplyAlsoBargesIn() {
        EchoGate g = new EchoGate();
        for (int i = 0; i < 6; i++) g.accept(frame(500), 0.05, 0.2, true); // learn ~0.25 coupling
        boolean barged = false;
        for (int i = 0; i < EchoGate.SUSTAIN; i++) barged |= g.accept(frame(3000), 0.08, 0.0, true).bargeIn(); // Penny between words
        assertTrue(barged, "with nothing audible the echo estimate is ~0, so plain speech wins");
    }

    @Test
    void heldEchoIsFlushedAsSilenceWhenPlaybackEnds() {
        EchoGate g = new EchoGate();
        for (int i = 0; i < EchoGate.HOLD; i++) assertTrue(g.accept(frame(2000), 0.05, 0.2, true).send().isEmpty());
        EchoGate.Result r = g.accept(frame(700), 0.02, 0, false);
        assertEquals(EchoGate.HOLD + 1, r.send().size());
        for (int i = 0; i < EchoGate.HOLD; i++) assertTrue(silent(r.send().get(i)));
        assertFalse(silent(r.send().get(EchoGate.HOLD)));
    }
}
