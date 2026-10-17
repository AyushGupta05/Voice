package sc.fidelis.penny.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * {@link PennyVoiceClient} against {@link FakeGateway} (the /events protocol of server.ts and speech-session.ts) with
 * {@link FakeDevices}: no live gateway, no AWS session, no audio hardware.
 */
class PennyVoiceClientTest {
    private FakeGateway gateway;
    private FakeDevices devices;
    private PennyVoiceClient client;
    private final List<String> log = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        gateway = new FakeGateway("test-token-" + UUID.randomUUID());
        devices = new FakeDevices(48_000, 0.05);
        client = new PennyVoiceClient(gateway.uri(), () -> Optional.of(gateway.token), devices);
        client.addListener(new PennyVoiceClient.Listener() {
            @Override public void connection(boolean connected, String detail) { log.add("connection:" + connected); }
            @Override public void voice(PennyVoiceClient.Voice state, String reason) { log.add("voice:" + state); }
            @Override public void transcript(String text, boolean partial, boolean addressed) { log.add("transcript:" + partial + ":" + addressed + ":" + text); }
            @Override public void acknowledged(String taskId, String text) { log.add("ack:" + taskId + ":" + text); }
            @Override public void result(String taskId, String text, boolean failed) { log.add("result:" + taskId + ":" + failed + ":" + text); }
            @Override public void working(boolean working) { log.add("working:" + working); }
            @Override public void speaking(boolean speaking) { log.add("speaking:" + speaking); }
            @Override public void error(String message) { log.add("error:" + message); }
            @Override public void interrupted(boolean bargeIn) { log.add("interrupted:" + bargeIn); }
        });
        client.start();
        try {
            waitFor("connected", () -> client.isConnected(), 10);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + ": " + client.detail() + "; handshakes " + gateway.handshakes + "; log " + log, e);
        }
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
        client.awaitClosed(3000);
        gateway.close();
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    /** start_voice → connecting → ready_for_audio → listening, as speech-session.ts emits them. */
    private String[] startSession() throws Exception {
        int from = gateway.received.size();
        client.startVoice();
        FakeGateway.Message start = gateway.await(m -> m.type().equals("start_voice"), from, 5000);
        String requestId = start.json().get("requestId").getAsString();
        assertTrue(requestId.matches("[a-f0-9-]{36}"), "start_voice needs a UUID request id (server.ts): " + requestId);
        String sessionId = UUID.randomUUID().toString();
        FakeGateway.Conn c = start.conn();
        c.send(FakeGateway.event("voice_state", "state", "connecting", "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("voice_state", "state", "ready_for_audio", "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("voice_state", "state", "listening", "sessionId", sessionId, "requestId", requestId));
        return new String[] {requestId, sessionId, String.valueOf(System.nanoTime())};
    }

    @Test
    void connectsWithTheCompanionTokenAndNoOriginAndSaysHello() throws Exception {
        var h = gateway.handshakes.get(0);
        assertEquals("Bearer " + gateway.token, h.get("authorization"));
        assertFalse(h.containsKey("origin"), "the gateway refuses upgrades that carry an Origin header");
        FakeGateway.Message hello = gateway.await("hello", 3000);
        String id = hello.json().get("clientId").getAsString();
        assertTrue(id.matches("[A-Za-z0-9-]{8,64}"), "hello needs a stable clientId of 8-64 letters, digits or dashes: " + id);
        assertEquals(client.clientId(), id);
        assertEquals(false, hello.json().get("desktop").getAsBoolean());
        assertTrue(log.contains("connection:true"), log.toString());
    }

    @Test
    void voiceSessionStreamsBoundedAudioFramesAndReportsTranscriptAckAndResult() throws Exception {
        String[] s = startSession();
        String requestId = s[0];
        String sessionId = s[1];
        long connectingSent = Long.parseLong(s[2]);
        waitFor("listening", () -> client.voice() == PennyVoiceClient.Voice.LISTENING, 5);
        assertEquals(1, devices.micOpened.get(), "the microphone opens only at ready_for_audio");
        waitFor("one second of audio", () -> gateway.all("audio").size() >= 10, 10);

        List<FakeGateway.Message> audio = gateway.all("audio");
        long total = 0;
        for (FakeGateway.Message m : audio) {
            assertEquals(sessionId, m.json().get("sessionId").getAsString());
            String b64 = m.json().get("audio").getAsString();
            assertTrue(b64.length() <= PennyVoiceClient.MAX_FRAME_BASE64, "frame bound of speech-session.ts");
            byte[] pcm = Base64.getDecoder().decode(b64);
            assertEquals(MicProcessor.FRAME_SAMPLES * 2, pcm.length, "100 ms of 24 kHz PCM16 per frame");
            assertEquals(0, pcm.length % 2, "whole samples");
            total += pcm.length;
            long elapsedMs = (m.nanos() - connectingSent) / 1_000_000L;
            assertTrue(total <= (elapsedMs + 1000) * PennyVoiceClient.BYTES_PER_MS,
                    "never faster than the gateway's rate bound: " + total + " bytes after " + elapsedMs + " ms");
        }
        byte[] first = Base64.getDecoder().decode(audio.get(audio.size() - 1).json().get("audio").getAsString());
        assertTrue(MicProcessor.rms(first, 0, first.length) > 0.02, "the microphone signal (with gain) reaches the gateway");

        FakeGateway.Conn c = gateway.current();
        c.send(FakeGateway.event("voice_transcript", "text", "count the", "partial", true, "addressed", true, "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("voice_transcript", "text", "Count the cells.", "partial", false, "addressed", true, "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("task_state", "state", "working", "taskId", "t1"));
        c.send(FakeGateway.event("text_ack", "taskId", "t1", "text", "Counting the cells now."));
        c.send(FakeGateway.event("text_result", "taskId", "t1", "text", "There are 64 cells."));
        c.send(FakeGateway.event("task_state", "state", "idle", "taskId", "t1"));
        // another companion's transcript is not ours
        c.send(FakeGateway.event("voice_transcript", "text", "not mine", "partial", false, "sessionId", "other", "requestId", requestId));
        waitFor("result", () -> log.contains("result:t1:false:There are 64 cells."), 5);
        waitFor("idle", () -> log.contains("working:false"), 5);
        assertTrue(log.contains("transcript:true:true:count the"), log.toString());
        assertTrue(log.contains("transcript:false:true:Count the cells."), log.toString());
        assertTrue(log.contains("ack:t1:Counting the cells now."), log.toString());
        assertTrue(log.contains("working:true"), log.toString());
        assertFalse(log.toString().contains("not mine"), log.toString());

        int from = gateway.received.size();
        client.stopVoice();
        FakeGateway.Message stop = gateway.await(m -> m.type().equals("stop_voice"), from, 3000);
        assertEquals(requestId, stop.json().get("requestId").getAsString());
        waitFor("microphone closed", () -> devices.micClosed.get() == 1, 3);
        waitFor("voice off", () -> client.voice() == PennyVoiceClient.Voice.OFF, 3);
    }

    @Test
    void replyAudioFollowsEpochsAndInterruptions() throws Exception {
        String[] s = startSession();
        String requestId = s[0];
        String sessionId = s[1];
        waitFor("listening", () -> client.voice() == PennyVoiceClient.Voice.LISTENING, 5);
        FakeGateway.Conn c = gateway.current();
        String chunk = Base64.getEncoder().encodeToString(new byte[4800]);
        c.send(FakeGateway.event("voice_speaking", "epoch", 1, "queued", false, "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("audio", "audio", chunk, "sampleRate", 24000, "epoch", 1, "final", false, "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("audio", "audio", chunk, "sampleRate", 24000, "epoch", 1, "final", true, "sessionId", sessionId, "requestId", requestId));
        waitFor("two chunks played", () -> devices.speaker.played.size() == 2, 5);
        waitFor("speaking", () -> client.isSpeaking(), 3);
        int flushes = devices.speaker.flushes.get();
        c.send(FakeGateway.event("interrupted", "epoch", 2, "sessionId", sessionId, "requestId", requestId));
        waitFor("interrupted", () -> log.contains("interrupted:false"), 3);
        assertTrue(devices.speaker.flushes.get() > flushes, "interrupted stops playback at once");
        assertFalse(client.isSpeaking());
        // late audio of the interrupted reply, a wrong sample rate and another session: all ignored
        c.send(FakeGateway.event("audio", "audio", chunk, "sampleRate", 24000, "epoch", 1, "final", true, "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("audio", "audio", chunk, "sampleRate", 16000, "epoch", 2, "final", true, "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("audio", "audio", chunk, "sampleRate", 24000, "epoch", 2, "final", true, "sessionId", "other", "requestId", requestId));
        // a new reply plays
        c.send(FakeGateway.event("voice_speaking", "epoch", 3, "queued", false, "sessionId", sessionId, "requestId", requestId));
        c.send(FakeGateway.event("audio", "audio", chunk, "sampleRate", 24000, "epoch", 3, "final", true, "sessionId", sessionId, "requestId", requestId));
        waitFor("third chunk", () -> devices.speaker.played.size() == 3, 5);
        Thread.sleep(200);
        assertEquals(3, devices.speaker.played.size(), "only the current epoch plays");
        assertEquals(3, client.playbackEpoch());
        waitFor("speaking ends when the last chunk has played", () -> !client.isSpeaking(), 5);
        assertTrue(log.indexOf("speaking:true") >= 0 && log.lastIndexOf("speaking:false") > log.lastIndexOf("speaking:true"), log.toString());
    }

    @Test
    void reconnectsAfterTheGatewayDropsAndReArmsVoice() throws Exception {
        String[] s = startSession();
        waitFor("listening", () -> client.voice() == PennyVoiceClient.Voice.LISTENING, 5);
        String clientId = gateway.await("hello", 3000).json().get("clientId").getAsString();
        int starts = gateway.all("start_voice").size();
        gateway.current().drop();
        waitFor("disconnected", () -> !client.isConnected(), 5);
        waitFor("microphone closed on disconnect", () -> devices.micClosed.get() == 1, 3);
        waitFor("reconnected", () -> client.isConnected() && gateway.connections.size() == 2, 10);
        List<FakeGateway.Message> hellos = gateway.all("hello");
        assertEquals(2, hellos.size());
        assertEquals(clientId, hellos.get(1).json().get("clientId").getAsString(), "the same clientId lets the gateway reattach the conversation");
        waitFor("voice re-armed", () -> gateway.all("start_voice").size() == starts + 1, 5);
        assertTrue(gateway.all("start_voice").get(starts).conn() == gateway.current());
        assertFalse(s[0].equals(gateway.all("start_voice").get(starts).json().get("requestId").getAsString()), "a fresh request id");
    }

    @Test
    void typedTextStopTaskAndDesktopRequests() throws Exception {
        assertTrue(client.sendText("  measure the selection  ", false));
        FakeGateway.Message text = gateway.await("text", 3000);
        assertEquals("measure the selection", text.json().get("text").getAsString());
        assertFalse(text.json().has("spoken"));
        client.stopTask();
        gateway.await("stop_task", 3000);
        gateway.current().send(FakeGateway.event("desktop_request", "requestId", "d-1", "op", "inspect"));
        FakeGateway.Message r = gateway.await("desktop_response", 3000);
        assertEquals("d-1", r.json().get("requestId").getAsString());
        assertTrue(r.json().has("error"), "the workspace declines desktop control instead of letting the gateway time out");
        gateway.current().send(FakeGateway.event("voice_error", "message", "Voice is already active in another companion.", "requestId", "someone-else"));
        gateway.current().send(FakeGateway.event("error", "message", "Text task failed."));
        waitFor("error", () -> log.contains("error:Text task failed."), 3);
        assertFalse(log.toString().contains("already active"), "another request's voice error is not ours");
    }

    @Test
    void refusedVoiceStartIsReportedAndStopsWanting() throws Exception {
        int from = gateway.received.size();
        client.startVoice();
        FakeGateway.Message start = gateway.await(m -> m.type().equals("start_voice"), from, 3000);
        String rid = start.json().get("requestId").getAsString();
        start.conn().send(FakeGateway.event("voice_error", "message", "Voice is already active in another companion.", "requestId", rid));
        waitFor("error", () -> log.contains("error:Voice is already active in another companion."), 3);
        waitFor("voice off", () -> client.voice() == PennyVoiceClient.Voice.OFF, 3);
        assertEquals(0, devices.micOpened.get());
    }

    @Test
    void wrongTokenIsRefusedAndRetried() throws Exception {
        client.close();
        client.awaitClosed(3000);
        List<String> details = new ArrayList<>();
        PennyVoiceClient bad = new PennyVoiceClient(gateway.uri(), () -> Optional.of("wrong"), devices);
        bad.addListener(new PennyVoiceClient.Listener() {
            @Override public void connection(boolean connected, String detail) { details.add(connected + ":" + detail); }
        });
        try {
            bad.start();
            waitFor("two refused attempts (backoff retry)", () -> gateway.refused >= 2, 10);
            assertFalse(bad.isConnected());
            assertNotNull(bad.detail());
            assertTrue(bad.detail().contains("refused the companion token"), bad.detail());
        } finally {
            bad.close();
            bad.awaitClosed(3000);
        }
    }

    @Test
    void gatewayEndedReasonsMatchTheSwiftCompanion() {
        assertTrue(PennyVoiceClient.gatewayEnded("Voice session reached its time limit."));
        assertTrue(PennyVoiceClient.gatewayEnded("Speech stream closed."));
        assertTrue(PennyVoiceClient.gatewayEnded("Gateway closing."));
        assertTrue(PennyVoiceClient.gatewayEnded("Companion disconnected."));
        assertFalse(PennyVoiceClient.gatewayEnded("Voice stopped."));
        assertFalse(PennyVoiceClient.gatewayEnded("Invalid microphone stream."));
    }

    static JsonObject json(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }
}
