package sc.fidelis.speech;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.function.DoubleConsumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * One microphone capture (Java Sound {@link TargetDataLine}). Requests 16 kHz mono 16-bit signed
 * little-endian; if the mixer rejects that, captures at 48/44.1 kHz (or stereo) and converts to
 * 16 kHz mono with {@link Resampler}. Audio is held in memory only. Capture ends at
 * {@link #MAX_SECONDS} (auto-stop) or on {@link #stop()}.
 *
 * <p>Live-microphone behaviour is hardware dependent and is not covered by automated tests; use
 * {@code SpeechCli --mic-check}.
 */
public final class AudioCapture {
    public static final int TARGET_RATE = 16_000;
    public static final int MAX_SECONDS = 30;

    /** Capture cap in seconds; tests shorten it to exercise auto-stop. */
    private static volatile double maxSeconds = MAX_SECONDS;

    static void setMaxSecondsForTest(double seconds) { maxSeconds = seconds; }

    private static final int[] RATES = {16_000, 48_000, 44_100, 32_000, 22_050};
    private static final int[] CHANNELS = {1, 2};

    private final TargetDataLine line;
    private final AudioFormat format;
    private final DoubleConsumer levelSink;
    private final Runnable onAutoStop;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private volatile boolean stopRequested;
    private volatile boolean autoStopped;
    private Thread thread;

    private AudioCapture(TargetDataLine line, AudioFormat format, DoubleConsumer levelSink, Runnable onAutoStop) {
        this.line = line;
        this.format = format;
        this.levelSink = levelSink;
        this.onAutoStop = onAutoStop;
    }

    /** The first supported capture format on the default mixer, or {@code null} if there is none. */
    public static AudioFormat probeFormat() {
        for (int rate : RATES) {
            for (int ch : CHANNELS) {
                AudioFormat f = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, 2 * ch, rate, false);
                try {
                    if (AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, f))) return f;
                } catch (RuntimeException ignored) {
                    // a misbehaving mixer provider must not break the probe
                }
            }
        }
        return null;
    }

    /** Null when a microphone line can be opened with some supported format, else the reason. */
    public static String unavailableReason() {
        try {
            return probeFormat() == null ? "no audio input device supports 16-bit PCM capture" : null;
        } catch (Throwable t) {
            return "audio subsystem unavailable: " + t;
        }
    }

    public static AudioCapture open(DoubleConsumer levelSink, Runnable onAutoStop) throws LineUnavailableException {
        AudioFormat f = probeFormat();
        if (f == null) throw new LineUnavailableException("no audio input device supports 16-bit PCM capture");
        TargetDataLine l = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, f));
        l.open(f, (int) f.getFrameRate() * f.getFrameSize() / 2); // 0.5 s device buffer
        return new AudioCapture(l, f, levelSink == null ? v -> {} : levelSink, onAutoStop);
    }

    public AudioFormat nativeFormat() { return format; }

    public boolean autoStopped() { return autoStopped; }

    public void start() {
        line.start();
        thread = new Thread(this::pump, "fidelis-audio-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private void pump() {
        int frameSize = format.getFrameSize();
        long maxBytes = (long) (maxSeconds * format.getFrameRate()) * frameSize;
        byte[] buf = new byte[(int) (format.getFrameRate() / 20) * frameSize]; // 50 ms
        try {
            while (!stopRequested) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;
                synchronized (captured) {
                    captured.write(buf, 0, n);
                    if (captured.size() >= maxBytes) autoStopped = true;
                }
                byte[] chunk = n == buf.length ? buf : Arrays.copyOf(buf, n);
                levelSink.accept(Math.min(1.0, Resampler.rms(Resampler.fromPcm16le(chunk, format.getChannels()))));
                if (autoStopped) break;
            }
        } finally {
            line.stop();
            line.close();
        }
        if (autoStopped && onAutoStop != null) onAutoStop.run();
    }

    /** Stop capturing and return the audio as 16 kHz mono 16-bit signed little-endian PCM. */
    public byte[] stop() {
        stopRequested = true;
        if (thread != null && Thread.currentThread() != thread) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return pcm16k();
    }

    /** The audio captured so far, converted to 16 kHz mono PCM (also valid after auto-stop). */
    public byte[] pcm16k() {
        byte[] raw;
        synchronized (captured) {
            raw = captured.toByteArray();
        }
        float[] mono = Resampler.fromPcm16le(raw, format.getChannels());
        int rate = (int) format.getSampleRate();
        float[] out = rate == TARGET_RATE ? mono : Resampler.resample(mono, rate, TARGET_RATE);
        int max = (int) (TARGET_RATE * maxSeconds);
        if (out.length > max) out = Arrays.copyOf(out, max);
        return Resampler.toPcm16le(out);
    }
}
