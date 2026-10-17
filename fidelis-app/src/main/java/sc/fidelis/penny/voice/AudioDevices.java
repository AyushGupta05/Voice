package sc.fidelis.penny.voice;

/**
 * The microphone and speaker behind {@link PennyVoiceClient}. Production uses {@link JavaSoundDevices}
 * (javax.sound.sampled); tests use fakes, so no test touches audio hardware.
 */
public interface AudioDevices {
    /** Receives microphone samples on the device's own thread. */
    interface MicrophoneSink {
        /** Mono PCM16 values at {@code sampleRate}; {@code count} of {@code samples} are valid. */
        void samples(short[] samples, int count, int sampleRate);

        /** The device stopped delivering (unplugged, permission revoked). */
        void failed(String message);
    }

    /** An open microphone; closing it stops the device thread. */
    interface Microphone extends AutoCloseable {
        int sampleRate();

        String description();

        @Override
        void close();
    }

    /** Reply playback, 24 kHz mono PCM16 little-endian. Every method is thread-safe and non-blocking. */
    interface Speaker extends AutoCloseable {
        /** Queue a chunk for playback. */
        void play(byte[] pcm);

        /** Drop everything queued or buffered, now. */
        void flush();

        /** Audio is queued, playing, or ended less than a room tail ago. */
        boolean playing();

        /** Loudest RMS (0..1) of the audio audible during the last {@code windowMillis}. */
        double level(long windowMillis);

        @Override
        void close();
    }

    Microphone openMicrophone(MicrophoneSink sink) throws Exception;

    Speaker openSpeaker() throws Exception;
}
