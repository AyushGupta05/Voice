package sc.fidelis.penny.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Microphone samples at the device rate to the gateway's transport format: mono PCM16 little-endian at 24 kHz, in frames
 * of {@value #FRAME_SAMPLES} samples (100 ms; 4800 bytes, 6400 base64 characters, well under the 64000-character frame
 * bound of speech-session.ts). Pure and single-threaded (the microphone thread owns one instance), so it is unit-tested
 * without audio hardware.
 *
 * <p>The automatic gain follows the Swift companion (AudioIO.swift): the Mac microphone is very quiet (speech peaks
 * ~0.003–0.05), so speech is brought towards an RMS of 0.07 with a slow gain (0.15 up, 0.5 down per frame, at most
 * 40×) that only adapts while the frame carries more than the noise floor, so silence is not pumped up to noise. While
 * Penny is speaking the gain is frozen ({@link #setFrozen}) so the echo of her voice does not turn the user down.
 */
public final class MicProcessor {
    public static final int OUTPUT_RATE = 24_000;
    public static final int FRAME_SAMPLES = 2_400;
    static final float TARGET_RMS = 0.07f;
    static final float NOISE_FLOOR_RMS = 0.0015f;
    static final float MAX_GAIN = 40f;

    /** One transport frame: little-endian PCM16 and its RMS after gain (0..1). */
    public record Frame(byte[] pcm, double rms, boolean silentInput) {}

    private final Resampler resampler;
    private final float[] frame = new float[FRAME_SAMPLES];
    private int filled;
    private float gain;
    private volatile boolean frozen;
    private boolean anyNonZero;

    public MicProcessor(int inputRate) {
        this(inputRate, 6f);
    }

    MicProcessor(int inputRate, float initialGain) {
        this.resampler = new Resampler(inputRate, OUTPUT_RATE);
        this.gain = initialGain;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public float gain() {
        return gain;
    }

    /** Feed device samples (PCM16 values, mono); returns the completed 100 ms frames. */
    public List<Frame> accept(short[] samples, int count) {
        float[] in = new float[count];
        for (int i = 0; i < count; i++) {
            in[i] = samples[i] / 32768f;
            if (samples[i] != 0) anyNonZero = true;
        }
        return acceptFloat(resampler.process(in));
    }

    private List<Frame> acceptFloat(float[] resampled) {
        List<Frame> out = new ArrayList<>(1);
        for (float s : resampled) {
            frame[filled++] = s;
            if (filled == FRAME_SAMPLES) {
                out.add(finish());
                filled = 0;
            }
        }
        return out;
    }

    private Frame finish() {
        double energy = 0;
        for (int i = 0; i < FRAME_SAMPLES; i++) energy += frame[i] * (double) frame[i];
        float rms = (float) Math.sqrt(energy / FRAME_SAMPLES);
        if (!frozen && rms > NOISE_FLOOR_RMS) {
            float wanted = Math.min(MAX_GAIN, Math.max(1f, TARGET_RMS / rms));
            gain += (wanted - gain) * (wanted < gain ? 0.5f : 0.15f);
        }
        byte[] pcm = new byte[FRAME_SAMPLES * 2];
        double outEnergy = 0;
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            float v = Math.max(-1f, Math.min(1f, frame[i] * gain));
            outEnergy += v * (double) v;
            int s = Math.round(v * 32767f);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        boolean silent = !anyNonZero;
        anyNonZero = false;
        return new Frame(pcm, Math.sqrt(outEnergy / FRAME_SAMPLES), silent);
    }

    /** RMS of little-endian PCM16 (0..1). */
    public static double rms(byte[] pcm, int offset, int length) {
        int n = length / 2;
        if (n == 0) return 0;
        double e = 0;
        for (int i = 0; i < n; i++) {
            int lo = pcm[offset + 2 * i] & 0xff;
            int hi = pcm[offset + 2 * i + 1];
            double v = (short) ((hi << 8) | lo) / 32768.0;
            e += v * v;
        }
        return Math.sqrt(e / n);
    }

    /**
     * Streaming sample-rate converter: a running-average low-pass (as long as the decimation ratio) when downsampling,
     * then linear interpolation. Good enough for a speech transcriber; not a mastering-grade filter.
     */
    static final class Resampler {
        private final double step;
        private final float[] box;
        private int boxIndex;
        private int boxFill;
        private double boxSum;
        private double t;
        private float prev;
        private boolean primed;

        Resampler(int inRate, int outRate) {
            if (inRate <= 0 || outRate <= 0) throw new IllegalArgumentException("sample rates must be positive");
            step = inRate / (double) outRate;
            box = new float[Math.max(1, (int) Math.round(step))];
        }

        float[] process(float[] in) {
            float[] out = new float[(int) Math.ceil(in.length / step) + 2];
            int n = 0;
            for (float x : in) {
                float f = lowPass(x);
                if (!primed) {
                    prev = f;
                    primed = true;
                    continue;
                }
                // output samples at positions prev + t, t in [0, 1)
                while (t < 1.0) {
                    if (n == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    out[n++] = (float) (prev + (f - prev) * t);
                    t += step;
                }
                t -= 1.0;
                prev = f;
            }
            return java.util.Arrays.copyOf(out, n);
        }

        private float lowPass(float x) {
            if (box.length == 1) return x;
            boxSum -= box[boxIndex];
            box[boxIndex] = x;
            boxSum += x;
            boxIndex = (boxIndex + 1) % box.length;
            if (boxFill < box.length) boxFill++;
            return (float) (boxSum / boxFill);
        }
    }
}
