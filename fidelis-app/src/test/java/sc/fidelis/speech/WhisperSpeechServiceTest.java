package sc.fidelis.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import sc.fidelis.speech.SpeechService.State;
import sc.fidelis.speech.SpeechService.Transcript;

/** Speech tests. They need the speech venv and pinned model (see WhisperSpeechService); they skip, with a reason, if absent. */
class WhisperSpeechServiceTest {

    private static WhisperSpeechService service;
    private static Path dir;

    @BeforeAll
    static void start() throws IOException {
        dir = Files.createTempDirectory("fidelis-speech-test");
        WhisperSpeechService.Config cfg = WhisperSpeechService.Config.fromSystemProperties();
        service = new WhisperSpeechService(cfg, false);
        service.start();
        service.awaitReady(Duration.ofSeconds(120));
    }

    @AfterAll
    static void stop() {
        if (service != null) service.close();
    }

    private static Path resource(String name, Path target) throws IOException {
        try (InputStream in = WhisperSpeechServiceTest.class.getResourceAsStream("/sc/fidelis/speech/" + name)) {
            assertNotNull(in, "missing test resource " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void requireReady() {
        assumeTrue(service.state() != State.UNAVAILABLE, "speech unavailable: " + service.unavailableReason());
    }

    @Test
    void transcribesTheInvertFixture() throws Exception {
        requireReady();
        Path wav = resource("invert-command.wav", dir.resolve("invert-command.wav"));
        Transcript t = service.transcribeFile(wav).get(120, TimeUnit.SECONDS);
        assertTrue(t.speechDetected(), "speech must be detected");
        assertTrue(t.text().toLowerCase().contains("invert"), "transcript was: " + t.text());
        assertTrue(t.text().toLowerCase().contains("invert the image"), "transcript was: " + t.text());
        assertFalse(t.segments().isEmpty());
        assertTrue(t.durationSeconds() > 1.0);
        assertTrue(t.model().contains("tiny.en"));
        assertTrue(t.latencyMs() > 0);
        assertEquals(State.READY, service.state());
        System.out.println("[speech] fixture transcript: \"" + t.text() + "\" latency=" + t.latencyMs() + " ms");
    }

    @Test
    void silenceHasNoSpeech() throws Exception {
        requireReady();
        Path wav = writeSilence(dir.resolve("silence-5s.wav"), 5);
        Transcript t = service.transcribeFile(wav).get(120, TimeUnit.SECONDS);
        assertFalse(t.speechDetected());
        assertEquals("", t.text());
        assertEquals(5.0, t.durationSeconds(), 0.01);
    }

    @Test
    void committedSilenceResourceIsFiveSecondsOfZeros() throws Exception {
        Path wav = resource("silence-5s.wav", dir.resolve("committed-silence.wav"));
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            assertEquals(16000f, in.getFormat().getSampleRate());
            assertEquals(5 * 16000L, in.getFrameLength());
            byte[] all = in.readAllBytes();
            for (byte b : all) assertEquals(0, b);
        }
    }

    @Test
    void missingFileFailsTheFutureWithoutKillingTheWorker() throws Exception {
        requireReady();
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> service.transcribeFile(dir.resolve("nope.wav")).get(10, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof IOException);
        assertTrue(service.state() == State.READY || service.state() == State.TRANSCRIBING);
    }

    @Test
    void stateMachineReportsStartingReadyTranscribingReady() throws Exception {
        requireReady();
        List<State> seen = new CopyOnWriteArrayList<>();
        try (WhisperSpeechService s = new WhisperSpeechService(service.config(), false)) {
            assertEquals(State.STARTING, s.state());
            seen.add(s.state());
            s.addListener(seen::add);
            s.start();
            assertEquals(State.READY, s.awaitReady(Duration.ofSeconds(120)));
            s.transcribeFile(writeSilence(dir.resolve("sm-silence.wav"), 1)).get(60, TimeUnit.SECONDS);
            assertEquals(List.of(State.STARTING, State.READY, State.TRANSCRIBING), seen.subList(0, 3));
            assertEquals(State.READY, s.state());
            assertNull(s.unavailableReason());
        }
    }

    @Test
    void startRecordingRequiresReadyAndMicrophone() {
        requireReady();
        if (service.microphoneUnavailableReason() == null) return; // covered by the microphone test
        IllegalStateException e = assertThrows(IllegalStateException.class, service::startRecording);
        assertTrue(e.getMessage().contains("microphone"), e.getMessage());
        assertEquals(State.READY, service.state());
    }

    @Test
    void stopWithoutStartFails() {
        requireReady();
        ExecutionException e = assertThrows(ExecutionException.class, () -> service.stopRecording().get());
        assertTrue(e.getCause() instanceof IllegalStateException);
    }

    @Test
    void unavailableWhenInterpreterPathIsWrong() {
        WhisperSpeechService.Config good = WhisperSpeechService.Config.fromSystemProperties();
        WhisperSpeechService.Config bad = new WhisperSpeechService.Config(dir.resolve("no-such-python.exe"),
                good.server(), good.model(), good.hashes(), good.log(), good.handshakeTimeout(), good.transcribeTimeout());
        try (WhisperSpeechService s = new WhisperSpeechService(bad)) {
            assertEquals(State.UNAVAILABLE, s.state());
            assertTrue(s.unavailableReason().contains("speech interpreter not found"), s.unavailableReason());
            assertTrue(s.unavailableReason().contains("no-such-python.exe"));
            assertThrows(IllegalStateException.class, s::startRecording);
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> s.transcribeFile(dir.resolve("x.wav")).get(5, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof IOException || e.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void unavailableWhenModelHashesDoNotMatch(@TempDir Path tmp) throws Exception {
        requireReady();
        WhisperSpeechService.Config good = service.config();
        Path hashes = tmp.resolve("hashes.json");
        String text = Files.readString(good.hashes());
        // flip the first hex digit of the first digest
        int i = text.indexOf(": \"") + 3;
        char c = text.charAt(i);
        Files.writeString(hashes, text.substring(0, i) + (c == '0' ? '1' : '0') + text.substring(i + 1));
        WhisperSpeechService.Config bad = new WhisperSpeechService.Config(good.python(), good.server(), good.model(),
                hashes, tmp.resolve("worker.log"), Duration.ofSeconds(60), good.transcribeTimeout());
        try (WhisperSpeechService s = new WhisperSpeechService(bad)) {
            assertEquals(State.UNAVAILABLE, s.awaitReady(Duration.ofSeconds(60)));
            assertTrue(s.unavailableReason().contains("MODEL_HASH_MISMATCH"), s.unavailableReason());
        }
    }

    @Test
    void microphoneRecordingProducesSixteenKilohertzPcm() throws Exception {
        String mic = AudioCapture.unavailableReason();
        assumeTrue(mic == null, "no TargetDataLine available: " + mic + " (live microphone use is unverified)");
        AudioCapture cap = AudioCapture.open(null, null);
        cap.start();
        Thread.sleep(500);
        byte[] pcm = cap.stop();
        double seconds = pcm.length / 2.0 / AudioCapture.TARGET_RATE;
        assertTrue(seconds > 0.2 && seconds < 1.0, "captured " + seconds + " s");
    }

    /** Writes a 16 kHz mono 16-bit PCM WAV of zeros. */
    static Path writeSilence(Path target, int seconds) throws IOException {
        byte[] zeros = new byte[seconds * 16000 * 2];
        AudioFormat f = new AudioFormat(16000f, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new java.io.ByteArrayInputStream(zeros), f, seconds * 16000L)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, target.toFile());
        }
        return target;
    }
}
