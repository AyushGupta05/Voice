package sc.fidelis.speech;

import java.nio.file.Path;
import java.time.Duration;

import javax.sound.sampled.AudioFormat;

/**
 * Command-line checks for the speech subsystem (needs only the JDK; no Fiji).
 *
 * <pre>
 *   --mic-check                records 3 s from the default microphone, prints the capture format and RMS
 *   --transcribe-file FILE     starts the worker and transcribes FILE
 *   --record-transcribe        records 5 s, then transcribes (full live path)
 * </pre>
 *
 * Live-microphone behaviour was NOT verified in the unattended development session; run
 * {@code --mic-check} on a machine with a microphone.
 */
public final class SpeechCli {
    private SpeechCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: SpeechCli --mic-check | --transcribe-file FILE | --record-transcribe");
            System.exit(2);
        }
        switch (args[0]) {
            case "--mic-check" -> System.exit(micCheck());
            case "--transcribe-file" -> System.exit(transcribeFile(Path.of(args[1])));
            case "--record-transcribe" -> System.exit(recordTranscribe());
            default -> {
                System.err.println("unknown option " + args[0]);
                System.exit(2);
            }
        }
    }

    static int micCheck() throws Exception {
        String reason = AudioCapture.unavailableReason();
        if (reason != null) {
            System.out.println("MIC-CHECK UNAVAILABLE: " + reason);
            return 1;
        }
        double[] peak = {0};
        AudioCapture cap = AudioCapture.open(level -> peak[0] = Math.max(peak[0], level), null);
        AudioFormat f = cap.nativeFormat();
        System.out.printf("capturing 3 s: %.0f Hz, %d ch, 16-bit%s%n", f.getSampleRate(), f.getChannels(),
                f.getSampleRate() == AudioCapture.TARGET_RATE ? "" : " (will be resampled to 16 kHz)");
        cap.start();
        Thread.sleep(3000);
        byte[] pcm = cap.stop();
        float[] samples = Resampler.fromPcm16le(pcm, 1);
        double rms = Resampler.rms(samples);
        System.out.printf("MIC-CHECK samples=%d (%.2f s at 16 kHz) rms=%.5f peakChunkRms=%.5f%n", samples.length,
                samples.length / (double) AudioCapture.TARGET_RATE, rms, peak[0]);
        System.out.println(rms > 0.0005 ? "MIC-CHECK OK: signal present" : "MIC-CHECK WARNING: near-silent input");
        return 0;
    }

    static int transcribeFile(Path file) throws Exception {
        try (WhisperSpeechService s = new WhisperSpeechService()) {
            if (s.awaitReady(Duration.ofSeconds(120)) == SpeechService.State.UNAVAILABLE) {
                System.out.println("UNAVAILABLE: " + s.unavailableReason());
                return 1;
            }
            SpeechService.Transcript t = s.transcribeFile(file).get();
            System.out.printf("text=\"%s\" speechDetected=%b duration=%.2fs latency=%dms model=%s%n", t.text(),
                    t.speechDetected(), t.durationSeconds(), t.latencyMs(), t.model());
            return 0;
        }
    }

    static int recordTranscribe() throws Exception {
        try (WhisperSpeechService s = new WhisperSpeechService()) {
            if (s.awaitReady(Duration.ofSeconds(120)) == SpeechService.State.UNAVAILABLE) {
                System.out.println("UNAVAILABLE: " + s.unavailableReason());
                return 1;
            }
            s.startRecording();
            System.out.println("recording 5 s: speak now");
            Thread.sleep(5000);
            SpeechService.Transcript t = s.stopRecording().get();
            System.out.printf("text=\"%s\" speechDetected=%b latency=%dms%n", t.text(), t.speechDetected(), t.latencyMs());
            return 0;
        }
    }
}
