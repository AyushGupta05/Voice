package sc.fidelis.penny;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A real HTTP client for the bridge under test (the gateway's request shape: operation, arguments, approval, context). */
final class BridgeHttp {
    static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final Gson GSON = new Gson();

    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final URI base;
    final String token;
    String taskId = "task-penny-test";
    boolean spoken = true;

    BridgeHttp(int port, String token) {
        this.base = URI.create("http://127.0.0.1:" + port);
        this.token = token;
    }

    record Resp(int status, JsonObject body) {
        JsonObject result() {
            assertEquals(200, status, () -> "HTTP " + status + ": " + body);
            return body.getAsJsonObject("result");
        }

        String error() {
            return body.has("error") ? body.get("error").getAsString() : null;
        }
    }

    Resp send(HttpRequest.Builder b) throws Exception {
        HttpResponse<String> r = client.send(b.timeout(Duration.ofSeconds(150)).build(), HttpResponse.BodyHandlers.ofString());
        JsonObject body = r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
        return new Resp(r.statusCode(), body);
    }

    Resp get(String path, String auth, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (auth != null) b.header("Authorization", auth);
        if (origin != null) b.header("Origin", origin);
        return send(b);
    }

    Resp post(Map<String, Object> envelope, String auth, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve("/execute")).POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(envelope)))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        if (origin != null) b.header("Origin", origin);
        return send(b);
    }

    Resp exec(String op, Map<String, Object> args, Boolean approved, String expected) throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("operation", op);
        e.put("arguments", args == null ? Map.of() : args);
        if (approved != null) e.put("approved", approved);
        if (expected != null) e.put("expectedContext", expected);
        e.put("spoken", spoken);
        if (taskId != null) e.put("taskId", taskId);
        e.put("utterance", "test: " + op);
        return post(e, "Bearer " + token, null);
    }

    JsonObject read(String op, Map<String, Object> args) throws Exception {
        return exec(op, args, null, null).result();
    }

    String contextKey() throws Exception {
        return read("context", null).get("contextKey").getAsString();
    }

    /** The gateway's path for a change: read the context, then send the approved operation bound to it. */
    Resp change(String op, Map<String, Object> args) throws Exception {
        return exec(op, args, true, contextKey());
    }

    static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
