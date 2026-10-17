package sc.fidelis.speech;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@link SpeechService} backed by the persistent faster-whisper worker {@code speech/server.py}
 * (docs D08 section 1; ADR-005). The worker is a child process speaking JSON lines; the model is
 * loaded once and verified against the pinned SHA-256 file before the handshake completes.
 *
 * <p>Environment (system property, then environment variable, then repository default):
 * <ul>
 *   <li>{@code fidelis.stt.python} / {@code FIDELIS_STT_PYTHON}: speech interpreter; default
 *       {@code <repo>/.venv-stt/Scripts/python.exe}, else the click-to-batch checkout's
 *       {@code .venv-stt} (Python 3.11, faster-whisper 1.2.1, CPU int8);</li>
 *   <li>{@code fidelis.stt.model} / {@code FIDELIS_STT_MODEL}: model folder; default
 *       {@code <repo>/external/models/tiny.en}, else the click-to-batch checkout's copy;</li>
 *   <li>{@code fidelis.stt.hashes}: pinned hash file; default {@code <repo>/external/stt-model.sha256.json};</li>
 *   <li>{@code fidelis.stt.server}: worker script; default {@code <repo>/speech/server.py};</li>
 *   <li>{@code fidelis.stt.log}: worker stderr log; default {@code <tmp>/fidelis/speech-worker.log};</li>
 *   <li>{@code fidelis.repo.dir}: repository root (set by tools/test.py and tools/run.py).</li>
 * </ul>
 *
 * <p>If the interpreter, script or model is missing the service is {@link State#UNAVAILABLE} with a
 * reason, and typed NL is unaffected. {@link #startRecording()} throws {@link IllegalStateException}
 * (with a user-readable message) when the service is not READY or no microphone is available.
 * Transcripts from recordings that end by auto-stop are delivered to {@link #onTranscript}.
 */
public final class WhisperSpeechService implements SpeechService, AutoCloseable {

    /** Resolved locations and timeouts. */
    public record Config(Path python, Path server, Path model, Path hashes, Path log,
                         Duration handshakeTimeout, Duration transcribeTimeout) {

        public static Config fromSystemProperties() {
            Path repo = repoDir();
            Path python = pick("fidelis.stt.python", "FIDELIS_STT_PYTHON", repo, ".venv-stt/Scripts/python.exe",
                    WINDOWS_DEFAULT_ROOT + "\\.venv-stt\\Scripts\\python.exe");
            Path model = pick("fidelis.stt.model", "FIDELIS_STT_MODEL", repo, "external/models/tiny.en",
                    WINDOWS_DEFAULT_ROOT + "\\external\\models\\tiny.en");
            Path hashes = pick("fidelis.stt.hashes", "FIDELIS_STT_HASHES", repo, "external/stt-model.sha256.json",
                    WINDOWS_DEFAULT_ROOT + "\\external\\stt-model.sha256.json");
            Path server = pick("fidelis.stt.server", "FIDELIS_STT_SERVER", repo, "speech/server.py",
                    WINDOWS_DEFAULT_ROOT + "\\speech\\server.py");
            String logProp = System.getProperty("fidelis.stt.log");
            Path log = logProp != null ? Paths.get(logProp)
                    : Paths.get(System.getProperty("java.io.tmpdir"), "fidelis", "speech-worker.log");
            return new Config(python, server, model, hashes, log, Duration.ofSeconds(90), Duration.ofSeconds(90));
        }

        private static Path repoDir() {
            String p = System.getProperty("fidelis.repo.dir");
            if (p != null) return Paths.get(p);
            Path d = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            for (; d != null; d = d.getParent()) {
                if (Files.isRegularFile(d.resolve("speech").resolve("server.py"))) return d;
            }
            return null;
        }

        private static Path pick(String prop, String env, Path repo, String repoRelative, String fallback) {
            String v = System.getProperty(prop);
            if (v == null || v.isBlank()) v = System.getenv(env);
            if (v != null && !v.isBlank()) return Paths.get(v);
            if (repo != null) {
                Path c = repo.resolve(repoRelative);
                if (Files.exists(c)) return c;
            }
            return Paths.get(fallback);
        }
    }

    /** Checkout that holds the shared speech venv and model when this worktree has none. */
    private static final String WINDOWS_DEFAULT_ROOT = "C:\\Users\\arnav\\Documents\\dev\\click-to-batch";

    public static final String DEFAULT_PROMPT = "ImageJ commands: Invert, Gaussian Blur, Median, Subtract Background, "
            + "Threshold, Otsu, Convert to Mask, Watershed, Measure, Analyze Particles, sigma, radius, micron";

    /** Raised (inside the transcript future) when the worker answers with an error message. */
    public static final class SpeechException extends RuntimeException {
        private final String code;

        public SpeechException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    private final Config config;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Transcript>> transcriptConsumers = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fidelis-speech");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger activeTranscriptions = new AtomicInteger();
    private final Object lock = new Object();
    private volatile CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Thread shutdownHook = new Thread(this::killNow, "fidelis-speech-shutdown");

    private volatile State state = State.STARTING;
    private volatile String unavailableReason;
    private volatile String vocabularyPrompt = DEFAULT_PROMPT;
    private volatile boolean closing;
    /** Set when the worker died unexpectedly or was killed after a timeout; the next request restarts it once. */
    private volatile boolean crashed;
    /** Test hook: runs right after a request has been written to the worker (used to kill it mid-request). */
    volatile Consumer<Process> afterRequestSent;
    private volatile String modelName = "Systran/faster-whisper-tiny.en";

    private Process process;
    private BufferedWriter toWorker;
    private AudioCapture capture;
    private CompletableFuture<Transcript> recordingResult;

    public WhisperSpeechService() {
        this(Config.fromSystemProperties());
    }

    public WhisperSpeechService(Config config) {
        this(config, true);
    }

    /** @param autostart false leaves the service STARTING until {@link #start()} (tests of the state machine) */
    public WhisperSpeechService(Config config, boolean autostart) {
        this.config = config;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        String problem = checkEnvironment();
        if (problem != null) {
            fail(problem);
        } else if (autostart) {
            start();
        }
    }

    // ---- SpeechService --------------------------------------------------------------------------

    @Override
    public State state() { return state; }

    @Override
    public String unavailableReason() { return state == State.UNAVAILABLE ? unavailableReason : null; }

    @Override
    public void addListener(Listener listener) { listeners.add(listener); }

    @Override
    public void removeListener(Listener listener) { listeners.remove(listener); }

    @Override
    public void setVocabularyPrompt(String prompt) {
        vocabularyPrompt = prompt == null ? "" : prompt.trim();
    }

    @Override
    public void startRecording() {
        synchronized (lock) {
            if (state != State.READY) {
                throw new IllegalStateException(state == State.UNAVAILABLE
                        ? "Speech is unavailable: " + unavailableReason
                        : "Cannot start recording while the speech service is " + state);
            }
            String mic = AudioCapture.unavailableReason();
            if (mic != null) throw new IllegalStateException("No microphone available: " + mic);
            try {
                capture = AudioCapture.open(level -> listeners.forEach(l -> safe(() -> l.level(level))),
                        () -> executor.execute(this::autoStopped));
                recordingResult = new CompletableFuture<>();
                capture.start();
            } catch (Exception e) {
                capture = null;
                recordingResult = null;
                throw new IllegalStateException("Cannot open the microphone: " + e.getMessage(), e);
            }
            setState(State.RECORDING);
        }
    }

    @Override
    public CompletableFuture<Transcript> stopRecording() {
        synchronized (lock) {
            if (capture == null) {
                if (recordingResult != null) return recordingResult; // auto-stopped; transcription already running
                return CompletableFuture.failedFuture(new IllegalStateException("Not recording"));
            }
            return finishRecording();
        }
    }

    @Override
    public CompletableFuture<Transcript> transcribeFile(Path audio) {
        Path abs = audio.toAbsolutePath();
        if (!Files.isRegularFile(abs)) {
            return CompletableFuture.failedFuture(new IOException("Audio file not found: " + abs));
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "transcribe_file");
        msg.put("path", abs.toString());
        return transcribe(msg);
    }

    // ---- extras (not part of the frozen interface) ----------------------------------------------

    /** Receive every transcript produced by a recording (including auto-stopped ones). */
    public void onTranscript(Consumer<Transcript> consumer) { transcriptConsumers.add(consumer); }

    /** Block until the worker handshake has finished; returns the resulting state. */
    public State awaitReady(Duration timeout) {
        try {
            ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // state() tells the caller what happened
        }
        return state;
    }

    /** Null when a microphone can be opened, otherwise a user-readable reason. */
    public String microphoneUnavailableReason() { return AudioCapture.unavailableReason(); }

    public Config config() { return config; }

    /** Start the worker if the constructor was told not to; no-op when already started or failed. */
    public void start() {
        synchronized (lock) {
            if (process != null || state == State.UNAVAILABLE) return;
            setState(State.STARTING);
        }
        executor.execute(this::launchWorker);
    }

    @Override
    public void close() {
        closing = true;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM already shutting down
        }
        Process p = process;
        if (p != null && p.isAlive()) {
            try {
                synchronized (lock) {
                    toWorker.write("{\"type\":\"shutdown\"}\n");
                    toWorker.flush();
                }
                p.waitFor(1500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // fall through to forcible termination
            }
        }
        killNow();
        AudioCapture c;
        synchronized (lock) {
            c = capture;
            capture = null;
        }
        if (c != null) c.stop();
        executor.shutdownNow();
        if (state != State.UNAVAILABLE) {
            unavailableReason = "closed";
            setState(State.UNAVAILABLE);
        }
    }

    // ---- worker lifecycle -----------------------------------------------------------------------

    private String checkEnvironment() {
        if (!Files.isRegularFile(config.python())) {
            return "speech interpreter not found: " + config.python() + " (set -Dfidelis.stt.python)";
        }
        if (!Files.isRegularFile(config.server())) {
            return "speech worker script not found: " + config.server() + " (set -Dfidelis.stt.server)";
        }
        if (!Files.isRegularFile(config.model().resolve("model.bin"))) {
            return "speech model not found: " + config.model() + " (set -Dfidelis.stt.model)";
        }
        if (!Files.isRegularFile(config.hashes())) {
            return "speech model hash file not found: " + config.hashes() + " (set -Dfidelis.stt.hashes)";
        }
        return null;
    }

    private void launchWorker() {
        try {
            Files.createDirectories(config.log().toAbsolutePath().getParent());
            ProcessBuilder pb = new ProcessBuilder(config.python().toString(), "-I", config.server().toString(),
                    "--model", config.model().toString(), "--hashes", config.hashes().toString());
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectError(ProcessBuilder.Redirect.appendTo(config.log().toFile()));
            Process p = pb.start();
            synchronized (lock) {
                process = p;
                toWorker = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            }
            executor.execute(() -> readLoop(p));
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("type", "hello");
            Map<String, Object> reply = request(hello, config.handshakeTimeout()).get();
            if (!"ready".equals(reply.get("type"))) {
                throw new SpeechException(String.valueOf(reply.get("code")), String.valueOf(reply.get("message")));
            }
            if (reply.get("model") instanceof String m) modelName = m;
            synchronized (lock) {
                if (state == State.STARTING) setState(State.READY);
            }
            ready.complete(null);
        } catch (SpeechException e) {
            fail("speech worker refused to start (" + e.code() + "): " + e.getMessage());
            killNow();
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            fail("speech worker did not start: " + c.getMessage() + " (see " + config.log() + ")");
            killNow();
        }
    }

    private void readLoop(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> msg;
                try {
                    msg = Json.parseObject(line);
                } catch (RuntimeException e) {
                    continue; // not protocol output
                }
                Object id = msg.get("id");
                CompletableFuture<Map<String, Object>> f = id == null ? null : pending.remove(String.valueOf(id));
                if (f != null) f.complete(msg);
            }
        } catch (IOException ignored) {
            // treated as worker exit below
        }
        IOException gone = new IOException("speech worker exited");
        pending.values().forEach(f -> f.completeExceptionally(gone));
        pending.clear();
        if (!closing && process == p && state != State.UNAVAILABLE) {
            crashed = true;
            fail("speech worker exited unexpectedly (see " + config.log() + "); it restarts on the next request");
        }
    }

    private CompletableFuture<Map<String, Object>> request(Map<String, Object> message, Duration timeout) {
        String id = message.get("id") instanceof String s ? s : UUID.randomUUID().toString();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.putAll(message);
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            synchronized (lock) {
                toWorker.write(Json.write(m));
                toWorker.write('\n');
                toWorker.flush();
            }
        } catch (IOException | RuntimeException e) {
            pending.remove(id);
            f.completeExceptionally(new IOException("cannot reach the speech worker: " + e.getMessage(), e));
            return f;
        }
        Consumer<Process> hook = afterRequestSent;
        if (hook != null) hook.accept(process);
        return f.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> pending.remove(id));
    }

    private void killNow() {
        Process p = process;
        if (p == null) return;
        // A virtualenv python.exe on Windows is a launcher that runs the real interpreter as a child, which
        // inherits the log file handle: terminate the whole tree, close our end of its stdin, then wait so
        // the log file is released before a caller (a test's temp directory) deletes it.
        List<ProcessHandle> tree = p.toHandle().descendants().toList();
        synchronized (lock) {
            try {
                if (toWorker != null) toWorker.close();
            } catch (IOException ignored) {
                // the pipe may already be broken
            }
        }
        if (p.isAlive()) p.destroyForcibly();
        for (ProcessHandle h : tree) h.destroyForcibly();
        try {
            p.waitFor(3, TimeUnit.SECONDS);
            for (ProcessHandle h : tree) {
                try {
                    h.onExit().get(3, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                    // best effort
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fail(String reason) {
        unavailableReason = reason;
        setState(State.UNAVAILABLE);
        ready.completeExceptionally(new IllegalStateException(reason));
    }

    // ---- transcription --------------------------------------------------------------------------

    /** Replace a crashed worker with a fresh one (one automatic attempt per crash). */
    private void restartIfCrashed() {
        synchronized (lock) {
            Process p = process;
            boolean dead = p != null && !p.isAlive();
            if (closing || !(crashed || (dead && state != State.UNAVAILABLE)) || checkEnvironment() != null) return;
            crashed = false;
            if (p != null && p.isAlive()) p.destroyForcibly();
            process = null;
            toWorker = null;
            unavailableReason = null;
            ready = new CompletableFuture<>();
            setState(State.STARTING);
        }
        executor.execute(this::launchWorker);
    }

    private CompletableFuture<Transcript> transcribe(Map<String, Object> message) {
        restartIfCrashed();
        if (state == State.UNAVAILABLE) {
            return CompletableFuture.failedFuture(new IllegalStateException("Speech is unavailable: " + unavailableReason));
        }
        String prompt = vocabularyPrompt;
        Map<String, Object> msg = new LinkedHashMap<>(message);
        if (!prompt.isEmpty()) msg.put("prompt", prompt);
        long t0 = System.nanoTime();
        return ready.thenCompose(v -> {
            beginTranscribing();
            return request(msg, config.transcribeTimeout());
        }).handle((reply, err) -> {
            endTranscribing();
            if (err != null) {
                Throwable c = err instanceof java.util.concurrent.CompletionException && err.getCause() != null
                        ? err.getCause() : err;
                if (c instanceof java.util.concurrent.TimeoutException) {
                    crashed = true;
                    fail("speech worker timed out; it restarts on the next request");
                    killNow();
                }
                throw new java.util.concurrent.CompletionException(c);
            }
            return toTranscript(reply, (System.nanoTime() - t0) / 1_000_000);
        });
    }

    @SuppressWarnings("unchecked")
    private Transcript toTranscript(Map<String, Object> reply, long latencyMs) {
        if ("error".equals(reply.get("type"))) {
            throw new SpeechException(String.valueOf(reply.get("code")), String.valueOf(reply.get("message")));
        }
        List<Transcript.Segment> segments = new ArrayList<>();
        if (reply.get("segments") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> s = (Map<String, Object>) o;
                segments.add(new Transcript.Segment(num(s.get("start")), num(s.get("end")),
                        String.valueOf(s.get("text")), num(s.get("avgLogprob")), num(s.get("noSpeechProb"))));
            }
        }
        String prompt = reply.get("prompt") instanceof String p ? p : null;
        return new Transcript(String.valueOf(reply.get("id")), String.valueOf(reply.get("text")),
                Boolean.TRUE.equals(reply.get("speech")), num(reply.get("durationSeconds")), segments,
                reply.get("model") instanceof String m ? m : modelName, prompt, latencyMs);
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }

    private void beginTranscribing() {
        activeTranscriptions.incrementAndGet();
        synchronized (lock) {
            if (state == State.READY) setState(State.TRANSCRIBING);
        }
    }

    private void endTranscribing() {
        if (activeTranscriptions.decrementAndGet() > 0) return;
        synchronized (lock) {
            if (state == State.TRANSCRIBING) setState(State.READY);
        }
    }

    // ---- recording ------------------------------------------------------------------------------

    private void autoStopped() {
        synchronized (lock) {
            if (capture != null) finishRecording();
        }
    }

    /** Caller holds {@code lock}. */
    private CompletableFuture<Transcript> finishRecording() {
        AudioCapture c = capture;
        CompletableFuture<Transcript> result = recordingResult;
        capture = null;
        byte[] pcm = c.stop();
        if (pcm.length == 0) {
            result.complete(new Transcript(UUID.randomUUID().toString(), "", false, 0.0, List.of(), modelName, null, 0));
            setState(State.READY);
            return result;
        }
        setState(State.TRANSCRIBING);
        activeTranscriptions.incrementAndGet(); // held until the transcript settles so the state is stable
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "transcribe");
        msg.put("sampleRate", AudioCapture.TARGET_RATE);
        msg.put("pcm16le_b64", Base64.getEncoder().encodeToString(pcm));
        transcribe(msg).whenComplete((t, e) -> {
            activeTranscriptions.decrementAndGet();
            if (e != null) {
                result.completeExceptionally(e);
            } else {
                result.complete(t);
                transcriptConsumers.forEach(tc -> safe(() -> tc.accept(t)));
            }
            synchronized (lock) {
                if (state == State.TRANSCRIBING && activeTranscriptions.get() == 0) setState(State.READY);
            }
        });
        return result;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void setState(State s) {
        if (state == s) return;
        state = s;
        for (Listener l : listeners) safe(() -> l.stateChanged(s));
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // a faulty listener must not break the speech pipeline
        }
    }
}
