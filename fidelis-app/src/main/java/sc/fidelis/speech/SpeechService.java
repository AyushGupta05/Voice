package sc.fidelis.speech;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Local speech capture and transcription (docs A16, D08; ADR-005). Shared interface: implemented by
 * the Voice & NL workstream, consumed by the Desktop UI workstream.
 *
 * <p>Audio never leaves the machine. Transcripts are returned as text for review; nothing executes
 * from speech directly (FR-SPEECH-004).
 */
public interface SpeechService {

    enum State { UNAVAILABLE, STARTING, READY, RECORDING, TRANSCRIBING }

    State state();

    /** Human-readable reason when {@link #state()} is UNAVAILABLE (missing environment, no microphone…). */
    String unavailableReason();

    void addListener(Listener listener);

    void removeListener(Listener listener);

    /** Begin capturing from the default microphone (explicit user action; auto-stops at 30 s). */
    void startRecording();

    /** Stop capturing and transcribe what was captured. */
    CompletableFuture<Transcript> stopRecording();

    /** Transcribe an audio file (WAV/PCM); the testable path when no microphone is available. */
    CompletableFuture<Transcript> transcribeFile(Path audio);

    /** Vocabulary bias for the next transcription (command labels, image and channel names). */
    void setVocabularyPrompt(String prompt);

    record Transcript(String id, String text, boolean speechDetected, double durationSeconds,
                      List<Segment> segments, String model, String promptUsed, long latencyMs) {
        public record Segment(double start, double end, String text, double avgLogprob, double noSpeechProb) {}
    }

    interface Listener {
        void stateChanged(State state);

        /** Live input level 0..1 while recording (for the recording indicator). */
        default void level(double rms) {}
    }
}
