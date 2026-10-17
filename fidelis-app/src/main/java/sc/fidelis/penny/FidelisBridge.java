package sc.fidelis.penny;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import sc.fidelis.engine.api.Engine;

/**
 * Penny's local bridge inside the Fidelis JVM ({@code docs/FIDELIS-PROTOCOL.md}): {@code GET /health} and
 * {@code POST /execute} on 127.0.0.1 (default port 49558, {@code -Dfidelis.penny.port}), bearer token from
 * {@code <root>/.local/state/bridge-token} (or {@code LUMA_BRIDGE_TOKEN}), requests with an {@code Origin} header refused.
 * Data, session and code operations need {@code approved:true} and an {@code expectedContext} equal to the current
 * {@code contextKey}. All changes go through {@link Engine#submit} ({@link PennyOps}); the bridge never touches Swing.
 */
public final class FidelisBridge {
    public static final int DEFAULT_PORT = 49558;
    public static final String ADAPTER_VERSION = "fidelis-bridge-1";
    private static final int MAX_BODY = 1024 * 1024;
    private static final Object LOCK = new Object();
    private static FidelisBridge current;

    private final Engine engine;
    private final String token;
    private final PennyOps ops;
    private HttpServer server;
    private ExecutorService pool;
    private int port;

    private FidelisBridge(Engine engine, String token, PennyOps.ConfirmMode mode) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.token = Objects.requireNonNull(token, "token");
        this.ops = new PennyOps(engine, mode);
    }

    // =============================================================================================
    // lifecycle
    // =============================================================================================

    /**
     * Bootstrap's hook: starts the bridge when a token is found, or when {@code -Dfidelis.penny=true} (which then
     * requires a token, else it logs why it stays off). {@code -Dfidelis.penny=false} keeps it off. Never throws.
     */
    public static FidelisBridge startIfConfigured(Engine engine) {
        String flag = System.getProperty("fidelis.penny");
        if ("false".equalsIgnoreCase(flag)) return null;
        String tok = configuredToken();
        if (tok == null) {
            if ("true".equalsIgnoreCase(flag))
                System.err.println("[fidelis-penny] -Dfidelis.penny=true but no bridge token: set LUMA_BRIDGE_TOKEN or create <root>/.local/state/bridge-token (root "
                        + root() + ")");
            return null;
        }
        try {
            return start(engine, tok, configuredPort(), configuredMode());
        } catch (RuntimeException e) {
            System.err.println("[fidelis-penny] bridge not started: " + e.getMessage());
            return null;
        }
    }

    /** Start on {@code port} (0 = any free port); the started bridge becomes {@link #current()}. */
    public static FidelisBridge start(Engine engine, String token, int port, PennyOps.ConfirmMode mode) {
        if (token == null || token.strip().length() < 32) throw new IllegalArgumentException("bridge token must have at least 32 characters");
        synchronized (LOCK) {
            if (current != null) current.stop();
            FidelisBridge b = new FidelisBridge(engine, token.strip(), mode);
            b.listen(port);
            current = b;
            return b;
        }
    }

    public static FidelisBridge current() {
        synchronized (LOCK) {
            return current;
        }
    }

    /** Stop the running bridge, if any (Bootstrap.exit). */
    public static void stopCurrent() {
        FidelisBridge b = current();
        if (b != null) b.stop();
    }

    private void listen(int wanted) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", wanted), 16);
        } catch (IOException e) {
            throw new IllegalStateException("cannot listen on 127.0.0.1:" + wanted + ": " + e.getMessage(), e);
        }
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "fidelis-penny-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
        System.out.println("[fidelis-penny] bridge listening on 127.0.0.1:" + port + " (confirm=" + ops.mode().name().toLowerCase(Locale.ROOT) + ")");
    }

    public void stop() {
        synchronized (LOCK) {
            if (current == this) current = null;
        }
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
        ops.shutdown();
    }

    public int port() {
        return port;
    }

    /** Smoke check over real HTTP: {@code GET /health} and {@code context} with the token, an Origin request refused. */
    public boolean selfCheck() {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var base = java.net.URI.create("http://127.0.0.1:" + port);
            var health = client.send(java.net.http.HttpRequest.newBuilder(base.resolve("/health")).header("Authorization", "Bearer " + token)
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            var context = client.send(java.net.http.HttpRequest.newBuilder(base.resolve("/execute")).header("Authorization", "Bearer " + token)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"operation\":\"context\"}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            var browser = client.send(java.net.http.HttpRequest.newBuilder(base.resolve("/health")).header("Authorization", "Bearer " + token)
                    .header("Origin", "http://example.invalid").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            System.out.println("[fidelis-penny] self-check: /health " + health.statusCode() + ", context " + context.statusCode()
                    + " (" + context.body().length() + " bytes), Origin request " + browser.statusCode());
            return health.statusCode() == 200 && context.statusCode() == 200 && context.body().contains("\"contextKey\"")
                    && browser.statusCode() == 403;
        } catch (IOException | RuntimeException e) {
            System.out.println("[fidelis-penny] self-check failed: " + e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    PennyOps ops() {
        return ops;
    }

    // =============================================================================================
    // configuration
    // =============================================================================================

    static int configuredPort() {
        String p = System.getProperty("fidelis.penny.port");
        if (p == null || p.isBlank()) return DEFAULT_PORT;
        try {
            return Integer.parseInt(p.trim());
        } catch (NumberFormatException e) {
            System.err.println("[fidelis-penny] bad -Dfidelis.penny.port=" + p + "; using " + DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

    static PennyOps.ConfirmMode configuredMode() {
        String m = System.getProperty("fidelis.penny.confirm", "auto").trim().toLowerCase(Locale.ROOT);
        if (m.equals("always")) return PennyOps.ConfirmMode.ALWAYS;
        if (!m.equals("auto")) System.err.println("[fidelis-penny] unknown -Dfidelis.penny.confirm=" + m + "; using auto");
        return PennyOps.ConfirmMode.AUTO;
    }

    /**
     * The Penny root: {@code -Dluma.root}, {@code LUMA_ROOT}, else the first directory holding
     * {@code .local/state/bridge-token} walking up from this jar, {@code -Dfidelis.repo.dir} and the working directory
     * (a git worktree under {@code <root>/.local/worktrees} finds the main checkout). Null when none is found.
     */
    static Path root() {
        String prop = System.getProperty("luma.root");
        if (prop != null && !prop.isBlank()) return Path.of(prop).toAbsolutePath().normalize();
        String env = System.getenv("LUMA_ROOT");
        if (env != null && !env.isBlank()) return Path.of(env).toAbsolutePath().normalize();
        List<Path> starts = new ArrayList<>();
        try {
            var src = FidelisBridge.class.getProtectionDomain().getCodeSource();
            if (src != null) starts.add(Path.of(src.getLocation().toURI()).toAbsolutePath());
        } catch (URISyntaxException | RuntimeException ignored) {
            // no code source
        }
        String repo = System.getProperty("fidelis.repo.dir");
        if (repo != null && !repo.isBlank()) starts.add(Path.of(repo).toAbsolutePath());
        starts.add(Path.of("").toAbsolutePath());
        for (Path s : starts) {
            for (Path p = s; p != null; p = p.getParent()) {
                if (Files.isRegularFile(p.resolve(".local").resolve("state").resolve("bridge-token"))) return p;
            }
        }
        return null;
    }

    /** {@code LUMA_BRIDGE_TOKEN} (32+ characters), else the root's {@code .local/state/bridge-token}; null when neither. */
    static String configuredToken() {
        String env = System.getenv("LUMA_BRIDGE_TOKEN");
        if (env != null && env.strip().length() >= 32) return env.strip();
        Path r = root();
        if (r == null) return null;
        try {
            String t = Files.readString(r.resolve(".local").resolve("state").resolve("bridge-token"), StandardCharsets.UTF_8).strip();
            return t.length() >= 32 ? t : null;
        } catch (IOException e) {
            return null;
        }
    }

    // =============================================================================================
    // identity
    // =============================================================================================

    private static final List<String> OWN_CLASSES = List.of("FidelisBridge", "PennyOps", "Params", "Highlight", "PennyJson",
            "PennyCommands", "PennyPlans", "PennyUi");
    private static volatile String sourceSha;

    /** {@code {adapterVersion, protocolVersion:1, operations, sourceSha256}}; the digest covers the loaded bridge classes. */
    static Map<String, Object> identity() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adapterVersion", ADAPTER_VERSION);
        m.put("protocolVersion", 1);
        m.put("operations", PennyOps.OPERATIONS);
        m.put("sourceSha256", classesSha());
        m.put("sourceShaCovers", "the loaded sc.fidelis.penny classes (" + String.join(", ", OWN_CLASSES) + ")");
        return m;
    }

    private static String classesSha() {
        String s = sourceSha;
        if (s != null) return s;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String c : OWN_CLASSES) {
                try (InputStream in = FidelisBridge.class.getResourceAsStream(c + ".class")) {
                    if (in != null) md.update(in.readAllBytes());
                }
            }
            s = HexFormat.of().formatHex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            s = null;
        }
        sourceSha = s;
        return s;
    }

    // =============================================================================================
    // HTTP
    // =============================================================================================

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (ex.getRequestHeaders().containsKey("Origin")) {
                respond(ex, 403, Map.of("error", "Browser origins are not permitted"));
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                respond(ex, 401, Map.of("error", "Unauthorized"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("GET".equals(method) && "/health".equals(path)) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("application", "Fidelis");
                h.put("version", ij.IJ.getFullVersion());
                h.put("engine", String.valueOf(engine.state()));
                h.put("confirmPolicy", ops.mode().name().toLowerCase(Locale.ROOT));
                h.put("adapter", identity());
                respond(ex, 200, h);
                return;
            }
            if (!"POST".equals(method) || !"/execute".equals(path)) {
                respond(ex, 404, Map.of("error", "Not found"));
                return;
            }
            byte[] bytes = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                respond(ex, 413, Map.of("error", "Request too large"));
                return;
            }
            JsonObject req;
            try {
                JsonElement e = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (!e.isJsonObject()) throw new IllegalArgumentException("body must be a JSON object");
                req = e.getAsJsonObject();
            } catch (RuntimeException e) {
                respond(ex, 400, Map.of("error", "Malformed JSON: " + e.getMessage()));
                return;
            }
            respond(ex, 200, Map.of("result", dispatch(req)));
        } catch (PennyOps.Failure f) {
            respond(ex, f.status, f.body);
        } catch (IllegalArgumentException e) {
            respond(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            System.err.println("[fidelis-penny] operation failed: " + e);
            e.printStackTrace();
            respond(ex, 409, Map.of("error", Objects.toString(e.getMessage(), e.getClass().getSimpleName())));
        } finally {
            ex.close();
        }
    }

    /** Envelope and policy: known operation, approval and context binding for changes, then the operation. */
    Map<String, Object> dispatch(JsonObject req) {
        String op = PennyJson.str(req, "operation", null);
        if (op == null || op.isBlank()) throw new IllegalArgumentException("Missing operation");
        if (!PennyOps.OPERATIONS.contains(op)) throw new IllegalArgumentException("Unknown operation: " + op);
        JsonObject args = PennyJson.object(req, "arguments");
        String expected = PennyJson.str(req, "expectedContext", null);
        if (PennyOps.MUTATING.contains(op)) {
            if (!PennyJson.bool(req, "approved", false))
                throw new PennyOps.Failure(409, "This operation requires the recorded decision path (approved:true).");
            if (expected == null || expected.isBlank())
                throw new PennyOps.Failure(409, "An approved operation must be bound to application context (expectedContext).");
        }
        // the context binding is checked by PennyOps under its admission lock, and again just before the plan runs
        PennyOps.Call call = new PennyOps.Call(op, args, PennyJson.bool(req, "spoken", false), PennyJson.str(req, "taskId", null),
                PennyJson.str(req, "utterance", null), PennyOps.READ.contains(op) || expected == null || expected.isBlank() ? null : expected);
        Map<String, Object> out = ops.execute(call);
        return out == null ? Map.of() : out;
    }

    private static void respond(HttpExchange ex, int code, Object value) throws IOException {
        byte[] body = PennyJson.write(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
    }
}
