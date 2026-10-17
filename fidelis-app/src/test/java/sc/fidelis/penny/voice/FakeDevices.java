package sc.fidelis.penny.voice;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Audio devices without hardware: a microphone thread that produces a sine at {@code rate} in real time (20 ms chunks,
 * paced by the clock like a sound card), and a speaker that records what it is asked to play.
 */
final class FakeDevices implements AudioDevices {
    final int rate;
    final double amplitude;
    final AtomicInteger micOpened = new AtomicInteger();
    final AtomicInteger micClosed = new AtomicInteger();
    final FakeSpeaker speaker = new FakeSpeaker();

    FakeDevices(int rate, double amplitude) {
        this.rate = rate;
        this.amplitude = amplitude;
    }

    @Override
    public Microphone openMicrophone(MicrophoneSink sink) {
        micOpened.incrementAndGet();
        return new Microphone() {
            private volatile boolean open = true;
            private final Thread t = new Thread(() -> {
                int chunk = rate / 50;
                short[] buf = new short[chunk];
                long n = 0;
                long start = System.nanoTime();
                while (open) {
                    for (int i = 0; i < chunk; i++, n++) buf[i] = (short) Math.round(Math.sin(2 * Math.PI * 440 * n / rate) * amplitude * 32767);
                    long due = start + n * 1_000_000_000L / rate;
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (open) sink.samples(buf, chunk, rate);
                }
            }, "fake-mic");

            {
                t.setDaemon(true);
                t.start();
            }

            @Override
            public int sampleRate() {
                return rate;
            }

            @Override
            public String description() {
                return "fake " + rate + " Hz";
            }

            @Override
            public void close() {
                if (open) micClosed.incrementAndGet();
                open = false;
            }
        };
    }

    @Override
    public Speaker openSpeaker() {
        return speaker;
    }

    static final class FakeSpeaker implements Speaker {
        final List<byte[]> played = new CopyOnWriteArrayList<>();
        final AtomicInteger flushes = new AtomicInteger();
        private volatile long until;

        @Override
        public void play(byte[] pcm) {
            played.add(pcm);
            long now = System.nanoTime();
            until = Math.max(until, now) + pcm.length / 2 * 1_000_000_000L / 24_000;
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
            until = System.nanoTime();
        }

        @Override
        public boolean playing() {
            return System.nanoTime() < until;
        }

        @Override
        public double level(long windowMillis) {
            return playing() ? 0.1 : 0;
        }

        @Override
        public void close() {
            until = 0;
        }
    }
}
