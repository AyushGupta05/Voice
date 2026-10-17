package sc.fidelis.penny.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Penny's live voice for the Fidelis workspace: a client of the gateway's {@code /events} websocket
 * ({@code packages/gateway/src/server.ts}), speaking the same protocol as the Swift companion
 * ({@code apps/companion/Sources/LumaCompanion/CompanionModel.swift}).
 *
 * <ul>
 * <li>Connects with {@code Authorization: Bearer <companion-token>} and no {@code Origin} header, sends {@code hello}
 * with a stable client id (the gateway hands the conversation back after a reconnect), reconnects with backoff
 * (0.5 s doubling to 15 s), and re-arms voice that was on when the socket dropped.</li>
 * <li>{@code start_voice{requestId}} → {@code voice_state connecting{sessionId}} → {@code ready_for_audio}: only then the
 * microphone opens and streams {@code audio{sessionId, audio}} frames of 100 ms mono PCM16 at 24 kHz ({@link MicProcessor}),
 * never faster than real time (the gateway refuses a stream above 48 bytes/ms).</li>
 * <li>Reply audio ({@code audio{audio, sampleRate:24000, epoch, final}}) plays through {@link AudioDevices.Speaker} under the
 * epoch rules of {@link PlaybackGate}; {@code interrupted} stops it. While Penny speaks the microphone is muted (no echo
 * cancellation in Java Sound) except when the user talks over her ({@link EchoGate}): playback then stops locally.</li>
 * </ul>
 *
 * <p>Thread-safe: every public method may be called from any thread (the EDT included) and returns at once; all state
 * lives on one loop thread ({@code penny-voice}), which also delivers listener callbacks. Listeners must not block and
 * must hop to the EDT themselves.
 */
public final class PennyVoiceClient implements AutoCloseable {
    static final String DEFAULT_EVENTS_TEXT = "ws://127.0.0.1:49555/events";
    public static final URI DEFAULT_EVENTS = URI.create(DEFAULT_EVENTS_TEXT);
    /** The gateway's frame bound (speech-session.ts acceptAudio). */
    public static final int MAX_FRAME_BASE64 = 64_000;
    /** PCM bytes per millisecond at 24 kHz mono PCM16: the gateway's rate bound. */
    public static final int BYTES_PER_MS = 48;
    static final long[] BACKOFF_MS = {500, 1_000, 2_000, 4_000, 8_000, 15_000};
    /** Frames of exact digital silence after which the microphone is reported as muted by the system (3 s). */
    static final int SILENT_FRAMES = 30;

    /** The voice session as the workspace shows it. */
    public enum Voice { OFF, STARTING, LISTENING }

    /** Callbacks on the client's loop thread. All default to no-ops. */
    public interface Listener {
        /** The gateway socket opened ({@code connected} received) or closed; {@code detail} says why when closed. */
        default void connection(boolean connected, String detail) {}

        default void voice(Voice state, String reason) {}

        /** Live transcript; {@code addressed} false means the gateway decided it was not for Penny. */
        default void transcript(String text, boolean partial, boolean addressed) {}

        default void acknowledged(String taskId, String text) {}

        default void progress(String taskId, String text) {}

        default void result(String taskId, String text, boolean failed) {}

        /** A task is running ({@code task_state working}) or finished. */
        default void working(boolean working) {}

        /** Penny's reply audio started or ended. */
        default void speaking(boolean speaking) {}

        /** Something needs the user's attention (voice refused, microphone muted, task error). */
        default void error(String message) {}

        /** Microphone RMS after gain (0..1), ten times a second while listening; 0 while muted for playback. */
        default void level(double rms) {}

        /** A tool call Penny made ({@code tool} event); {@code status} is its result status when present. */
        default void tool(String name, String status) {}

        /** Playback stopped because of the gateway ({@code interrupted}) or the user talking over Penny. */
        default void interrupted(boolean bargeIn) {}
    }

    private final URI uri;
    private final Supplier<Optional<String>> token;
    private final AudioDevices devices;
    private final HttpClient http;
    private final ScheduledExecutorService loop;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final String clientId = "fidelis-" + UUID.randomUUID();
    private final AtomicLong pendingAudioBytes = new AtomicLong();

    // ---- loop-confined state ----
    private WebSocket ws;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private boolean connected;
    private boolean closed;
    private boolean connecting;
    private int backoff;
    private ScheduledFuture<?> reconnect;
    private boolean voiceWanted;
    private String requestId;
    private String sessionId;
    private boolean providerListening;
    private boolean capturing;
    private Voice voice = Voice.OFF;
    private AudioDevices.Microphone mic;
    private MicSink micSink;
    private AudioDevices.Speaker speaker;
    private final PlaybackGate playback = new PlaybackGate();
    private final EchoGate echo = new EchoGate();
    private long sessionStartNanos;
    private long sessionAudioBytes;
    private long droppedFrames;
    private int silentFrames;
    private boolean silenceReported;
    private boolean speaking;
    private boolean working;
    private ScheduledFuture<?> drainPoll;
    private String lastDetail = "not connected yet";

    // ---- published mirrors for other threads ----
    private volatile boolean connectedView;
    private volatile Voice voiceView = Voice.OFF;
    private volatile boolean speakingView;
    private volatile boolean workingView;
    private volatile String detailView = "not connected yet";

    public PennyVoiceClient(URI uri, Supplier<Optional<String>> token, AudioDevices devices) {
        this.uri = Objects.requireNonNull(uri);
        this.token = Objects.requireNonNull(token);
        this.devices = Objects.requireNonNull(devices);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "penny-voice");
            t.setDaemon(true);
            return t;
        });
    }

    /** The workspace's client: {@code -Dfidelis.penny.gateway} (default {@value #DEFAULT_EVENTS_TEXT}), Penny's token, Java Sound. */
    public static PennyVoiceClient create() {
        String g = System.getProperty("fidelis.penny.gateway");
        URI u = g == null || g.isBlank() ? DEFAULT_EVENTS : URI.create(g);
        return new PennyVoiceClient(u, PennyRoot::companionToken, new JavaSoundDevices());
    }

    // ---- public API (any thread) -------------------------------------------------------------

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Start connecting (and keep reconnecting until {@link #close}). */
    public void start() {
        post(this::connect);
    }

    public boolean isConnected() {
        return connectedView;
    }

    public Voice voice() {
        return voiceView;
    }

    public boolean isSpeaking() {
        return speakingView;
    }

    public boolean isWorking() {
        return workingView;
    }

    /** Why the client is not connected (for tooltips). */
    public String detail() {
        return detailView;
    }

    public String clientId() {
        return clientId;
    }

    /** Continuous conversation on: Penny listens until {@link #stopVoice}. Re-armed after reconnects. */
    public void startVoice() {
        post(this::startVoiceNow);
    }

    public void stopVoice() {
        post(this::stopVoiceNow);
    }

    public void toggleVoice() {
        post(() -> {
            if (voiceWanted || requestId != null || sessionId != null) stopVoiceNow();
            else startVoiceNow();
        });
    }

    /** Send a typed request; false when the gateway is not connected (nothing is sent then). */
    public boolean sendText(String text, boolean spoken) {
        if (text == null || text.isBlank() || !connectedView) return false;
        String t = text.strip();
        if (t.length() > 4000) t = t.substring(0, 4000);
        final String body = t;
        post(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("type", "text");
            o.addProperty("text", body);
            if (spoken) o.addProperty("spoken", true);
            send(o, 0);
        });
        return true;
    }

    /** Stop the running task and Penny's speech (the Swift companion's Stop). */
    public void stopTask() {
        post(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("type", "stop_task");
            send(o, 0);
            if (playback.suppress() == PlaybackGate.Effect.FLUSH && speaker != null) speaker.flush();
            setSpeaking(false);
            setWorking(false);
        });
    }

    /** Stop voice, close the socket and end the loop thread. Returns at once (safe on the EDT). */
    @Override
    public void close() {
        post(this::shutdown);
        loop.shutdown();
    }

    /** Whether {@link #close} has finished (test hook). */
    boolean awaitClosed(long millis) throws InterruptedException {
        return loop.awaitTermination(millis, TimeUnit.MILLISECONDS);
    }

    // ---- connection ----------------------------------------------------------------------------

    private void post(Runnable r) {
        try {
            loop.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    System.err.println("[penny] voice client: " + e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
    }

    private void connect() {
        if (closed || ws != null || connecting) return;
        Optional<String> t = token.get();
        if (t.isEmpty()) {
            setDisconnected("Penny's gateway token (.local/state/companion-token) was not found");
            scheduleReconnect();
            return;
        }
        connecting = true;
        Socket listener = new Socket();
        http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + t.get())
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, listener)
                .whenComplete((socket, ex) -> post(() -> {
                    // onOpen (posted earlier) already made the socket current; here only failures and a close race remain
                    connecting = false;
                    if (ex != null) {
                        Integer status = handshakeStatus(ex);
                        setDisconnected(status != null && (status == 401 || status == 403)
                                ? "Penny's gateway refused the companion token (HTTP " + status + ")"
                                : "Penny's gateway is not reachable at " + uri + " (" + rootCause(ex) + ")");
                        scheduleReconnect();
                    } else if (closed) {
                        socket.abort();
                    } else if (ws == null && !socket.isInputClosed()) {
                        opened(socket);
                    }
                }));
    }

    private void opened(WebSocket socket) {
        if (ws == socket) return;
        if (closed) {
            socket.abort();
            return;
        }
        connecting = false;
        ws = socket;
        sendChain = CompletableFuture.completedFuture(null);
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("clientId", clientId);
        // Additive fields (the gateway reads only clientId today): this client is the Fidelis workspace and does not
        // answer desktop_request (Accessibility control stays with the Swift companion).
        hello.addProperty("client", "fidelis-workspace");
        hello.addProperty("desktop", false);
        send(hello, 0);
    }

    private void dropped(WebSocket socket, String why) {
        if (socket != ws) return;
        ws = null;
        boolean was = connected;
        connected = false;
        resetVoice(false);
        updateVoice("disconnected");
        setWorking(false);
        setDisconnected(was ? "Penny's gateway disconnected (" + why + "); reconnecting" : why);
        scheduleReconnect();
    }

    private void setDisconnected(String detail) {
        lastDetail = detail;
        detailView = detail;
        boolean changed = connectedView;
        connectedView = false;
        if (changed || !detail.equals(reportedDetail)) {
            reportedDetail = detail;
            for (Listener l : listeners) safely(() -> l.connection(false, detail));
        }
    }

    private String reportedDetail;

    private void scheduleReconnect() {
        if (closed) return;
        if (reconnect != null) reconnect.cancel(false);
        long delay = BACKOFF_MS[Math.min(backoff, BACKOFF_MS.length - 1)];
        backoff++;
        reconnect = loop.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void shutdown() {
        if (closed) return;
        if (requestId != null && ws != null) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "stop_voice");
            o.addProperty("requestId", requestId);
            send(o, 0);
        }
        closed = true;
        voiceWanted = false;
        if (reconnect != null) reconnect.cancel(false);
        if (drainPoll != null) drainPoll.cancel(false);
        resetVoice(false);
        updateVoice("closed");
        if (speaker != null) speaker.close();
        speaker = null;
        WebSocket s = ws;
        ws = null;
        connected = false;
        connectedView = false;
        if (s != null) {
            CompletableFuture<?> c = sendChain.handle((v, e) -> null)
                    .thenCompose(v -> s.sendClose(WebSocket.NORMAL_CLOSURE, "workspace closed"));
            try {
                c.get(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // abort below
            }
            s.abort();
        }
    }

    /** One message at a time on the socket (java.net.http refuses overlapping sends). */
    private boolean send(JsonObject o, int audioBytes) {
        WebSocket s = ws;
        if (s == null) return false;
        String text = o.toString();
        pendingAudioBytes.addAndGet(audioBytes);
        sendChain = sendChain.handle((v, e) -> null)
                .thenCompose(v -> s.sendText(text, true))
                .whenComplete((v, ex) -> {
                    pendingAudioBytes.addAndGet(-audioBytes);
                    if (ex != null) post(() -> {
                        if (s == ws) {
                            s.abort();
                            dropped(s, "send failed: " + rootCause(ex));
                        }
                    });
                });
        return true;
    }

    /** java.net.http listener: whole text messages are handed to the loop in order. */
    private final class Socket implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            // posted before any message of this socket, so the loop knows the socket when "connected" arrives
            post(() -> opened(webSocket));
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > 32 * 1024 * 1024) {
                partial.setLength(0);
                webSocket.abort();
                return null;
            }
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                post(() -> receive(webSocket, message));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            post(() -> dropped(webSocket, "closed " + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason)));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            post(() -> dropped(webSocket, rootCause(error)));
        }
    }

    // ---- events --------------------------------------------------------------------------------

    private void receive(WebSocket socket, String message) {
        if (socket != ws) return;
        JsonObject e;
        try {
            JsonElement el = JsonParser.parseString(message);
            if (!el.isJsonObject()) return;
            e = el.getAsJsonObject();
        } catch (RuntimeException ex) {
            System.err.println("[penny] ignored unparseable gateway event (" + message.length() + " chars)");
            return; // a malformed event never drops the connection
        }
        String type = str(e, "type");
        if (type == null) return;
        switch (type) {
            case "connected" -> {
                connected = true;
                backoff = 0;
                connectedView = true;
                lastDetail = "connected";
                detailView = "connected to " + uri;
                reportedDetail = null;
                for (Listener l : listeners) safely(() -> l.connection(true, null));
                armVoiceIfWanted();
            }
            case "desktop_request" -> {
                String id = str(e, "requestId");
                if (id == null) return;
                JsonObject r = new JsonObject();
                r.addProperty("type", "desktop_response");
                r.addProperty("requestId", id);
                r.addProperty("error", "The Fidelis workspace does not operate the desktop; desktop control needs the Luma companion app.");
                send(r, 0);
            }
            case "voice_state" -> voiceState(e);
            case "voice_transcript" -> {
                if (!mine(e)) return;
                String text = str(e, "text");
                boolean partial = !Boolean.FALSE.equals(bool(e, "partial"));
                boolean addressed = !Boolean.FALSE.equals(bool(e, "addressed"));
                for (Listener l : listeners) safely(() -> l.transcript(text == null ? "" : text, partial, addressed));
            }
            case "voice_speaking" -> {
                if (!mine(e)) return;
                Integer ep = integer(e, "epoch");
                if (ep == null) return;
                if (playback.speaking(ep, Boolean.TRUE.equals(bool(e, "queued"))) == PlaybackGate.Effect.FLUSH && speaker != null) speaker.flush();
                setSpeaking(playback.isSpeaking());
            }
            case "audio" -> audio(e);
            case "interrupted" -> {
                if (!mine(e)) return;
                Integer ep = integer(e, "epoch");
                playback.interrupted(ep == null ? playback.epoch() : ep);
                if (speaker != null) speaker.flush();
                setSpeaking(false);
                for (Listener l : listeners) safely(() -> l.interrupted(false));
            }
            case "voice_error" -> {
                String rid = str(e, "requestId");
                if (rid != null && !rid.equals(requestId)) return;
                String msg = Optional.ofNullable(str(e, "message")).orElse("Speech is unavailable.");
                voiceWanted = false;
                if (sessionId == null) {
                    resetVoice(false);
                    updateVoice(msg);
                }
                error(msg);
            }
            case "error" -> {
                String rid = str(e, "requestId");
                String msg = Optional.ofNullable(str(e, "message")).orElse("Unknown error");
                if (rid != null && !rid.equals(requestId)) return;
                if (rid != null) {
                    voiceWanted = false;
                    resetVoice(false);
                    updateVoice(msg);
                }
                error(msg);
            }
            case "text_ack" -> {
                String text = str(e, "text");
                if (text != null && !text.isBlank()) for (Listener l : listeners) safely(() -> l.acknowledged(str(e, "taskId"), text));
            }
            case "text_progress" -> {
                String text = str(e, "text");
                if (text != null) for (Listener l : listeners) safely(() -> l.progress(str(e, "taskId"), text));
            }
            case "text_result" -> {
                String text = str(e, "text");
                boolean failed = "failed".equals(str(e, "outcome")) || Boolean.TRUE.equals(bool(e, "isError"));
                if (text != null) for (Listener l : listeners) safely(() -> l.result(str(e, "taskId"), text, failed));
            }
            case "text_error" -> {
                setWorking(false);
                error(Optional.ofNullable(str(e, "message")).orElse("The task stopped."));
            }
            case "task_state" -> setWorking("working".equals(str(e, "state")));
            case "tool" -> {
                String name = Optional.ofNullable(str(e, "name")).orElse("tool");
                String status = null;
                if (e.has("result") && e.get("result").isJsonObject()) {
                    JsonObject r = e.getAsJsonObject("result");
                    status = str(r, "status");
                    if (status == null && r.has("result") && r.get("result").isJsonObject()) status = str(r.getAsJsonObject("result"), "status");
                }
                final String st = status;
                for (Listener l : listeners) safely(() -> l.tool(name, st));
            }
            default -> {
                // reattached, action, voice_ignored: nothing to show in the workspace
            }
        }
    }

    private boolean mine(JsonObject e) {
        return requestId != null && requestId.equals(str(e, "requestId")) && sessionId != null && sessionId.equals(str(e, "sessionId"));
    }

    private void voiceState(JsonObject e) {
        String rid = str(e, "requestId");
        if (rid == null || !rid.equals(requestId)) return;
        String state = str(e, "state");
        String sid = str(e, "sessionId");
        if (state == null) return;
        switch (state) {
            case "connecting" -> {
                if (sid == null) return;
                resetVoice(true);
                sessionId = sid;
                sessionStartNanos = System.nanoTime();
                sessionAudioBytes = 0;
                updateVoice(null);
            }
            case "ready_for_audio" -> {
                if (sid != null && sid.equals(sessionId)) openMicrophone(sid);
            }
            case "listening" -> {
                if (sid != null && sid.equals(sessionId)) {
                    providerListening = true;
                    updateVoice(null);
                }
            }
            case "idle" -> {
                if (sid != null && !sid.equals(sessionId)) return;
                String reason = Optional.ofNullable(str(e, "reason")).orElse("");
                resetVoice(false);
                updateVoice(reason);
                if (voiceWanted && gatewayEnded(reason)) startVoiceNow();
            }
            default -> {
            }
        }
    }

    /** The gateway closed the session itself (time limit, stream closed, restart): not the user pressing Stop. */
    static boolean gatewayEnded(String reason) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return r.contains("time limit") || r.contains("speech stream closed") || r.contains("gateway closing") || r.contains("disconnected");
    }

    private void audio(JsonObject e) {
        if (!mine(e)) return;
        Integer ep = integer(e, "epoch");
        Integer rate = integer(e, "sampleRate");
        String b64 = str(e, "audio");
        if (ep == null || rate == null || rate != 24_000 || b64 == null) return;
        boolean last = Boolean.TRUE.equals(bool(e, "final"));
        if (!playback.accept(ep, last)) return;
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (pcm.length < 2 || pcm.length % 2 != 0) return;
        AudioDevices.Speaker s = speaker();
        if (s != null) s.play(pcm);
        watchDrain();
    }

    private AudioDevices.Speaker speaker() {
        if (speaker == null) {
            try {
                speaker = devices.openSpeaker();
            } catch (Exception ex) {
                error("Penny's reply cannot be played: " + ex.getMessage());
                return null;
            }
        }
        return speaker;
    }

    private void watchDrain() {
        if (drainPoll != null && !drainPoll.isDone()) return;
        drainPoll = loop.scheduleWithFixedDelay(() -> {
            if (speaker == null || !speaker.playing()) {
                if (playback.drained()) setSpeaking(false);
                if (!playback.isSpeaking() && drainPoll != null) drainPoll.cancel(false);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    // ---- voice ---------------------------------------------------------------------------------

    private void startVoiceNow() {
        voiceWanted = true;
        if (!connected || ws == null || requestId != null || closed) {
            updateVoice(null);
            return;
        }
        requestId = UUID.randomUUID().toString();
        JsonObject o = new JsonObject();
        o.addProperty("type", "start_voice");
        o.addProperty("requestId", requestId);
        send(o, 0);
        updateVoice(null);
    }

    private void armVoiceIfWanted() {
        if (voiceWanted && sessionId == null && requestId == null) startVoiceNow();
    }

    private void stopVoiceNow() {
        voiceWanted = false;
        if (requestId != null) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "stop_voice");
            o.addProperty("requestId", requestId);
            send(o, 0);
        }
        resetVoice(false);
        updateVoice("stopped");
    }

    private void resetVoice(boolean keepRequest) {
        if (!keepRequest) requestId = null;
        sessionId = null;
        providerListening = false;
        capturing = false;
        closeMicrophone();
        if (speaker != null) speaker.flush();
        playback.reset();
        echo.reset();
        silentFrames = 0;
        silenceReported = false;
        setSpeaking(false);
    }

    private void updateVoice(String reason) {
        Voice v = sessionId != null && providerListening && capturing ? Voice.LISTENING
                : requestId != null || (voiceWanted && connected) ? Voice.STARTING : Voice.OFF;
        if (v == voice && reason == null) return;
        voice = v;
        voiceView = v;
        for (Listener l : listeners) safely(() -> l.voice(v, reason));
    }

    private void openMicrophone(String sid) {
        if (micSink != null && sid.equals(micSink.session)) return;
        closeMicrophone();
        MicSink sink = new MicSink(sid);
        micSink = sink;
        Thread t = new Thread(() -> {
            try {
                AudioDevices.Microphone m = devices.openMicrophone(sink);
                post(() -> {
                    if (micSink != sink || !sid.equals(sessionId)) {
                        m.close();
                        return;
                    }
                    mic = m;
                    capturing = true;
                    updateVoice(null);
                });
            } catch (Exception ex) {
                post(() -> {
                    if (micSink != sink) return;
                    micSink = null;
                    error("Microphone unavailable: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                    stopVoiceNow();
                });
            }
        }, "penny-mic-open");
        t.setDaemon(true);
        t.start();
    }

    private void closeMicrophone() {
        if (mic != null) {
            AudioDevices.Microphone m = mic;
            mic = null;
            Thread t = new Thread(m::close, "penny-mic-close"); // closing a line can take a moment
            t.setDaemon(true);
            t.start();
        }
        micSink = null;
        capturing = false;
    }

    /** Runs on the microphone's thread: processes samples and hands whole frames to the loop. */
    private final class MicSink implements AudioDevices.MicrophoneSink {
        final String session;
        private MicProcessor processor;
        volatile boolean frozen;

        MicSink(String session) {
            this.session = session;
        }

        @Override
        public void samples(short[] samples, int count, int sampleRate) {
            if (processor == null) processor = new MicProcessor(sampleRate);
            processor.setFrozen(frozen);
            for (MicProcessor.Frame f : processor.accept(samples, count)) post(() -> frame(this, f));
        }

        @Override
        public void failed(String message) {
            post(() -> {
                if (micSink != this) return;
                error(message);
                stopVoiceNow();
            });
        }
    }

    private void frame(MicSink sink, MicProcessor.Frame f) {
        if (sink != micSink || sessionId == null || !sessionId.equals(sink.session) || ws == null) return;
        if (f.silentInput()) {
            if (++silentFrames >= SILENT_FRAMES && !silenceReported) {
                silenceReported = true;
                error("The microphone delivers only silence. Allow microphone access for the app that started Fidelis "
                        + "(System Settings > Privacy & Security > Microphone) and check the input device.");
            }
        } else {
            silentFrames = 0;
        }
        boolean playing = playback.isSpeaking() || (speaker != null && speaker.playing());
        sink.frozen = playing;
        double reference = speaker == null ? 0 : speaker.level(400);
        EchoGate.Result r = echo.accept(f.pcm(), f.rms(), reference, playing);
        if (r.bargeIn()) {
            if (playback.suppress() == PlaybackGate.Effect.FLUSH && speaker != null) speaker.flush();
            setSpeaking(false);
            for (Listener l : listeners) safely(() -> l.interrupted(true));
        }
        double level = playing && !echo.passing() ? 0 : f.rms();
        for (Listener l : listeners) safely(() -> l.level(level));
        for (byte[] b : r.send()) sendAudio(b);
    }

    private void sendAudio(byte[] pcm) {
        long elapsedMs = (System.nanoTime() - sessionStartNanos) / 1_000_000L;
        // stay under the gateway's bound (total <= (elapsed + 1000 ms) * 48) measured from our later start, with margin
        if (sessionAudioBytes + pcm.length > (elapsedMs + 500) * BYTES_PER_MS || pendingAudioBytes.get() > 48_000) {
            droppedFrames++;
            if (droppedFrames % 50 == 1) System.err.println("[penny] dropped microphone frame(s): " + droppedFrames);
            return;
        }
        String b64 = Base64.getEncoder().encodeToString(pcm);
        if (b64.length() > MAX_FRAME_BASE64) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "audio");
        o.addProperty("sessionId", sessionId);
        o.addProperty("audio", b64);
        if (send(o, pcm.length)) sessionAudioBytes += pcm.length;
    }

    // ---- small helpers -------------------------------------------------------------------------

    private void setSpeaking(boolean b) {
        if (b == speaking) return;
        speaking = b;
        speakingView = b;
        for (Listener l : listeners) safely(() -> l.speaking(b));
    }

    private void setWorking(boolean b) {
        if (b == working) return;
        working = b;
        workingView = b;
        for (Listener l : listeners) safely(() -> l.working(b));
    }

    private void error(String msg) {
        for (Listener l : listeners) safely(() -> l.error(msg));
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.err.println("[penny] listener failed: " + e);
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() || !v.isJsonPrimitive() ? null : v.getAsString();
    }

    private static Boolean bool(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean() ? null : v.getAsBoolean();
    }

    private static Integer integer(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return null;
        try {
            return v.getAsInt();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer handshakeStatus(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.net.http.WebSocketHandshakeException h && h.getResponse() != null) return h.getResponse().statusCode();
        }
        return null;
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    // ---- test hooks ----------------------------------------------------------------------------

    /** Frames dropped to respect the gateway's rate and backlog bounds (test hook). */
    long droppedFrames() {
        try {
            return loop.submit(() -> droppedFrames).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Current playback epoch (test hook). */
    int playbackEpoch() {
        try {
            return loop.submit(playback::epoch).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return -1;
        }
    }
}
