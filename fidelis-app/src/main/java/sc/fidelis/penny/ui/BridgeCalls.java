package sc.fidelis.penny.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sc.fidelis.penny.voice.PennyRoot;

/**
 * The plan card's Confirm and Cancel go through the Fidelis bridge exactly as the gateway's do
 * (docs/FIDELIS-PROTOCOL.md): {@code POST http://127.0.0.1:49558/execute} with the bridge token and no Origin header.
 * Confirm is the user's own approval in the workspace, so it reads {@code context} first and sends
 * {@code {operation:"confirm", arguments:{planId}, expectedContext:<contextKey>, approved:true, spoken:false}}: the plan
 * runs only against the state the user saw. Cancel sends {@code {operation:"cancel_plan"}}. Asynchronous; never on the EDT.
 */
public class BridgeCalls {
    private final URI execute;
    private final Supplier<Optional<String>> token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public BridgeCalls(URI execute, Supplier<Optional<String>> token) {
        this.execute = execute;
        this.token = token;
    }

    /** The in-process bridge: {@code -Dfidelis.penny.port} (default 49558) and {@code <root>/.local/state/bridge-token}. */
    public static BridgeCalls local() {
        int port = Integer.getInteger("fidelis.penny.port", 49558);
        return new BridgeCalls(URI.create("http://127.0.0.1:" + port + "/execute"), PennyRoot::bridgeToken);
    }

    /** Run the pending plan the user confirmed in the card. Completes with the bridge's {@code result}. */
    public CompletableFuture<JsonObject> confirm(String planId) {
        return call("context", new JsonObject(), null, false).thenCompose(ctx -> {
            String key = ctx.has("contextKey") && ctx.get("contextKey").isJsonPrimitive() ? ctx.get("contextKey").getAsString() : null;
            JsonObject args = new JsonObject();
            if (planId != null) args.addProperty("planId", planId);
            return call("confirm", args, key, true);
        });
    }

    public CompletableFuture<JsonObject> cancel(String planId) {
        JsonObject args = new JsonObject();
        if (planId != null) args.addProperty("planId", planId);
        return call("cancel_plan", args, null, false);
    }

    CompletableFuture<JsonObject> call(String operation, JsonObject arguments, String expectedContext, boolean approved) {
        Optional<String> t = token.get();
        if (t.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("the Fidelis bridge token (.local/state/bridge-token) was not found"));
        JsonObject body = new JsonObject();
        body.addProperty("operation", operation);
        body.add("arguments", arguments);
        if (expectedContext != null) body.addProperty("expectedContext", expectedContext);
        if (approved) body.addProperty("approved", true);
        body.addProperty("spoken", false);
        HttpRequest req = HttpRequest.newBuilder(execute)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + t.get())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            JsonObject o = parse(r.body());
            if (r.statusCode() / 100 != 2) {
                String msg = o.has("error") && o.get("error").isJsonPrimitive() ? o.get("error").getAsString() : "HTTP " + r.statusCode();
                throw new IllegalStateException(msg);
            }
            JsonElement result = o.get("result");
            return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
        });
    }

    private static JsonObject parse(String s) {
        try {
            JsonElement e = JsonParser.parseString(s == null || s.isBlank() ? "{}" : s);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }
}
