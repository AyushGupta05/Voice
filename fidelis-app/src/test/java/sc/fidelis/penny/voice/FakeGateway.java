package sc.fidelis.penny.voice;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A minimal in-test stand-in for the gateway's {@code /events} websocket (RFC 6455 over a loopback ServerSocket): it
 * checks the upgrade the way server.ts does (path, {@code Authorization: Bearer <token>}, no Origin), sends
 * {@code connected}, records every client message, and lets a test script replies. No audio hardware, no network.
 */
final class FakeGateway implements AutoCloseable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    record Message(Conn conn, JsonObject json, long nanos) {
        String type() {
            return json.has("type") ? json.get("type").getAsString() : "";
        }
    }

    final String token;
    private final ServerSocket server;
    final List<Message> received = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> handshakes = new CopyOnWriteArrayList<>();
    final List<Conn> connections = new CopyOnWriteArrayList<>();
    volatile int refused;
    /** Called for every client message (on the connection's reader thread). */
    volatile BiConsumer<Conn, JsonObject> onMessage = (c, m) -> {};
    private final Thread acceptor;
    private volatile boolean open = true;

    FakeGateway(String token) throws IOException {
        this.token = token;
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "fake-gateway-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    URI uri() {
        return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/events");
    }

    private void acceptLoop() {
        while (open) {
            try {
                Socket s = server.accept();
                Conn c = new Conn(s);
                Thread t = new Thread(c::run, "fake-gateway-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!open) return;
            }
        }
    }

    Conn current() {
        return connections.isEmpty() ? null : connections.get(connections.size() - 1);
    }

    /** Wait for a client message matching {@code p}, at or after index {@code from}. */
    Message await(Predicate<Message> p, int from, long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) {
            for (int i = from; i < received.size(); i++) if (p.test(received.get(i))) return received.get(i);
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for a client message; received: " + types());
    }

    Message await(String type, long millis) throws InterruptedException {
        return await(m -> m.type().equals(type), 0, millis);
    }

    List<Message> all(String type) {
        List<Message> out = new ArrayList<>();
        for (Message m : received) if (m.type().equals(type)) out.add(m);
        return out;
    }

    List<String> types() {
        List<String> out = new ArrayList<>();
        for (Message m : received) if (!m.type().equals("audio")) out.add(m.type());
        return out;
    }

    @Override
    public void close() throws IOException {
        open = false;
        for (Conn c : connections) c.drop();
        server.close();
    }

    final class Conn {
        private final Socket socket;
        private OutputStream out;
        volatile boolean closed;

        Conn(Socket s) {
            socket = s;
        }

        void run() {
            try {
                InputStream in = socket.getInputStream();
                out = socket.getOutputStream();
                Map<String, String> h = readRequest(in);
                handshakes.add(h);
                String path = h.getOrDefault(":path", "");
                if (!path.equals("/events") || h.containsKey("origin") || !("Bearer " + token).equals(h.get("authorization"))) {
                    refused++;
                    out.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    socket.close();
                    return;
                }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((h.get("sec-websocket-key") + GUID).getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                        + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                connections.add(this);
                JsonObject connected = new JsonObject();
                connected.addProperty("type", "connected");
                connected.addProperty("tools", 3);
                send(connected);
                readFrames(new DataInputStream(in));
            } catch (Exception e) {
                // dropped
            } finally {
                closed = true;
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closed
                }
            }
        }

        private Map<String, String> readRequest(InputStream in) throws IOException {
            Map<String, String> h = new HashMap<>();
            String first = line(in);
            String[] parts = first.split(" ");
            h.put(":path", parts.length > 1 ? parts[1] : "");
            for (String l = line(in); !l.isEmpty(); l = line(in)) {
                int c = l.indexOf(':');
                if (c > 0) h.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
            }
            return h;
        }

        private String line(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int prev = -1;
            for (int c = in.read(); c >= 0; c = in.read()) {
                if (prev == '\r' && c == '\n') {
                    byte[] a = b.toByteArray();
                    return new String(a, 0, a.length - 1, StandardCharsets.US_ASCII);
                }
                b.write(c);
                prev = c;
            }
            throw new IOException("eof in request");
        }

        private void readFrames(DataInputStream in) throws IOException {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (!closed) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                byte[] mask = new byte[4];
                if (masked) in.readFully(mask);
                else throw new IOException("client frames must be masked");
                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                switch (opcode) {
                    case 0x1, 0x0 -> {
                        message.write(payload);
                        if (fin) {
                            String text = message.toString(StandardCharsets.UTF_8);
                            message.reset();
                            JsonObject j = JsonParser.parseString(text).getAsJsonObject();
                            received.add(new Message(this, j, System.nanoTime()));
                            onMessage.accept(this, j);
                        }
                    }
                    case 0x8 -> {
                        frame(0x8, payload);
                        closed = true;
                    }
                    case 0x9 -> frame(0xA, payload);
                    default -> {
                    }
                }
            }
        }

        synchronized void send(JsonObject o) {
            try {
                frame(0x1, o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                closed = true;
            }
        }

        private synchronized void frame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(0x80 | opcode);
            if (payload.length < 126) f.write(payload.length);
            else if (payload.length < 65536) {
                f.write(126);
                f.write(payload.length >> 8);
                f.write(payload.length);
            } else {
                f.write(127);
                for (int i = 7; i >= 0; i--) f.write((int) ((long) payload.length >> (8 * i)));
            }
            f.write(payload);
            out.write(f.toByteArray());
            out.flush();
        }

        /** Drop the TCP connection without a close frame (gateway crash). */
        void drop() {
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }

    static JsonObject event(String type, Object... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String k = String.valueOf(kv[i]);
            if (v instanceof Boolean b) o.addProperty(k, b);
            else if (v instanceof Number n) o.addProperty(k, n);
            else if (v != null) o.addProperty(k, String.valueOf(v));
        }
        return o;
    }
}
