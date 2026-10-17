package sc.fidelis.penny.voice;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * javax.sound.sampled microphone and speaker. The microphone opens the first mono (else stereo, first channel) PCM16
 * format the default input accepts, preferring 24 kHz (no resampling), then 48, 16 and 44.1 kHz; it reads 20 ms chunks
 * on its own thread. The speaker plays 24 kHz mono PCM16 (or 48 kHz with 2× upsampling where 24 kHz is refused) from a
 * queue on its own thread and only writes what the line can take without blocking, so {@link #flush} never waits.
 *
 * <p>On macOS a JVM without microphone permission reads silence rather than failing; {@link PennyVoiceClient} reports
 * that case. Nothing here runs on the EDT.
 */
public final class JavaSoundDevices implements AudioDevices {
    static final int[] INPUT_RATES = {24_000, 48_000, 16_000, 44_100};
    /** How long after the last audible sample the room still counts as "Penny is playing". */
    static final long TAIL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    @Override
    public Microphone openMicrophone(MicrophoneSink sink) throws LineUnavailableException {
        LineUnavailableException last = null;
        for (int channels : new int[] {1, 2}) {
            for (int rate : INPUT_RATES) {
                AudioFormat f = new AudioFormat(rate, 16, channels, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, f);
                if (!AudioSystem.isLineSupported(info)) continue;
                try {
                    TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                    line.open(f, rate * channels * 2 / 5); // 200 ms device buffer
                    line.start();
                    return new Mic(line, f, sink);
                } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                    last = e instanceof LineUnavailableException l ? l : new LineUnavailableException(e.toString());
                }
            }
        }
        throw last != null ? last : new LineUnavailableException("No microphone accepts 16-bit PCM");
    }

    @Override
    public Speaker openSpeaker() throws LineUnavailableException {
        LineUnavailableException last = null;
        for (int factor : new int[] {1, 2}) {
            AudioFormat f = new AudioFormat(24_000f * factor, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, f);
            if (!AudioSystem.isLineSupported(info)) continue;
            try {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(f, 24_000 * factor * 2 * 3 / 10); // 300 ms
                line.start();
                return new Out(line, factor);
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                last = e instanceof LineUnavailableException l ? l : new LineUnavailableException(e.toString());
            }
        }
        throw last != null ? last : new LineUnavailableException("No speaker accepts 24 kHz PCM16");
    }

    private static final class Mic implements Microphone {
        private final TargetDataLine line;
        private final AudioFormat format;
        private final Thread thread;
        private volatile boolean open = true;

        Mic(TargetDataLine line, AudioFormat format, MicrophoneSink sink) {
            this.line = line;
            this.format = format;
            int channels = format.getChannels();
            int rate = (int) format.getSampleRate();
            thread = new Thread(() -> {
                byte[] buf = new byte[rate / 50 * 2 * channels]; // 20 ms
                short[] mono = new short[rate / 50];
                try {
                    while (open) {
                        int n = line.read(buf, 0, buf.length);
                        if (n <= 0) {
                            if (!open || !line.isOpen()) break;
                            continue;
                        }
                        int frames = n / (2 * channels);
                        for (int i = 0; i < frames; i++) {
                            int o = i * 2 * channels; // first channel only (a downmix of a multi-channel array mic is mush)
                            mono[i] = (short) ((buf[o] & 0xff) | (buf[o + 1] << 8));
                        }
                        sink.samples(mono, frames, rate);
                    }
                } catch (RuntimeException e) {
                    if (open) sink.failed("Microphone stopped: " + e.getMessage());
                }
            }, "penny-mic");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public int sampleRate() {
            return (int) format.getSampleRate();
        }

        @Override
        public String description() {
            return format.toString();
        }

        @Override
        public void close() {
            open = false;
            try {
                line.stop();
                line.close();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
    }

    private static final class Out implements Speaker {
        private static final int LEVELS = 64;
        private final SourceDataLine line;
        private final int factor;
        private final LinkedBlockingDeque<byte[]> queue = new LinkedBlockingDeque<>();
        private final Object lock = new Object();
        private final Thread thread;
        private final long[] levelStart = new long[LEVELS];
        private final long[] levelEnd = new long[LEVELS];
        private final double[] levelRms = new double[LEVELS];
        private int levelHead;
        private volatile long generation;
        private volatile long audibleUntil = System.nanoTime();
        private volatile boolean open = true;

        Out(SourceDataLine line, int factor) {
            this.line = line;
            this.factor = factor;
            thread = new Thread(this::run, "penny-playback");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            int blockIn = Math.min(4_800, Math.max(2, line.getBufferSize() / factor / 2) & ~1); // ≤ 100 ms of 24 kHz input
            try {
                while (open) {
                    byte[] chunk = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (chunk == null) continue;
                    long gen = generation;
                    for (int off = 0; off < chunk.length && open && gen == generation; ) {
                        int n = Math.min(blockIn, chunk.length - off) & ~1;
                        if (n == 0) break;
                        byte[] out = factor == 1 ? java.util.Arrays.copyOfRange(chunk, off, off + n) : upsample(chunk, off, n);
                        while (open && gen == generation && line.available() < out.length) Thread.sleep(8);
                        synchronized (lock) {
                            if (!open || gen != generation) break;
                            line.write(out, 0, out.length);
                            long now = System.nanoTime();
                            long start = Math.max(now, audibleUntil);
                            long end = start + n / 2 * 1_000_000_000L / 24_000;
                            audibleUntil = end;
                            int i = levelHead++ % LEVELS;
                            levelStart[i] = start;
                            levelEnd[i] = end;
                            levelRms[i] = MicProcessor.rms(chunk, off, n);
                        }
                        off += n;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                System.err.println("[penny] playback stopped: " + e);
            }
        }

        private static byte[] upsample(byte[] in, int off, int n) {
            int samples = n / 2;
            byte[] out = new byte[n * 2];
            short prev = sample(in, off);
            for (int i = 0; i < samples; i++) {
                short cur = sample(in, off + 2 * i);
                short mid = (short) ((prev + cur) / 2);
                put(out, 4 * i, mid);
                put(out, 4 * i + 2, cur);
                prev = cur;
            }
            return out;
        }

        private static short sample(byte[] b, int o) {
            return (short) ((b[o] & 0xff) | (b[o + 1] << 8));
        }

        private static void put(byte[] b, int o, short s) {
            b[o] = (byte) s;
            b[o + 1] = (byte) (s >> 8);
        }

        @Override
        public void play(byte[] pcm) {
            if (open && pcm != null && pcm.length >= 2) queue.add(pcm);
        }

        @Override
        public void flush() {
            synchronized (lock) {
                generation++;
                queue.clear();
                line.flush();
                audibleUntil = System.nanoTime();
                java.util.Arrays.fill(levelEnd, 0L);
            }
        }

        @Override
        public boolean playing() {
            return !queue.isEmpty() || System.nanoTime() < audibleUntil + TAIL_NANOS;
        }

        @Override
        public double level(long windowMillis) {
            long now = System.nanoTime();
            long from = now - TimeUnit.MILLISECONDS.toNanos(windowMillis);
            double max = 0;
            synchronized (lock) {
                if (now >= audibleUntil + TAIL_NANOS) return 0;
                for (int i = 0; i < LEVELS; i++) {
                    if (levelEnd[i] >= from && levelStart[i] <= now) max = Math.max(max, levelRms[i]);
                }
            }
            return max;
        }

        @Override
        public void close() {
            open = false;
            synchronized (lock) {
                queue.clear();
                try {
                    line.stop();
                    line.flush();
                    line.close();
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
        }
    }
}
