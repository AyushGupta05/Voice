package sc.fidelis.penny.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds Penny's state folder ({@code <root>/.local/state}) and its tokens (docs/FIDELIS-PROTOCOL.md). The root is
 * {@code -Dluma.root}, {@code LUMA_ROOT}, or the first folder at or above the checkout, the working directory or Fiji's
 * folder that holds {@code .local/state/companion-token} (a git worktree under {@code .local/worktrees} finds the
 * enclosing checkout this way). Tokens are re-read on every call: the gateway may create them after Fidelis started.
 */
public final class PennyRoot {
    private PennyRoot() {}

    /** The folder holding {@code .local/state}, if one is found. */
    public static Optional<Path> root() {
        for (Path start : starts()) {
            for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
                if (Files.isRegularFile(p.resolve(".local").resolve("state").resolve("companion-token"))
                        || Files.isRegularFile(p.resolve(".local").resolve("state").resolve("bridge-token"))) return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private static List<Path> starts() {
        List<Path> out = new ArrayList<>();
        String explicit = System.getProperty("luma.root");
        if (explicit == null || explicit.isBlank()) explicit = System.getenv("LUMA_ROOT");
        if (explicit != null && !explicit.isBlank()) {
            out.add(Path.of(explicit)); // explicit root wins; nothing else is searched when it is set
            return out;
        }
        for (String prop : new String[] {"fidelis.repo.dir", "user.dir", "fiji.dir"}) {
            String v = System.getProperty(prop);
            if (v != null && !v.isBlank()) out.add(Path.of(v));
        }
        return out;
    }

    /** The gateway's companion token (authorises the {@code /events} websocket). */
    public static Optional<String> companionToken() {
        return token("companion-token");
    }

    /** The Fidelis bridge token (authorises {@code POST /execute} on the in-process bridge). */
    public static Optional<String> bridgeToken() {
        return token("bridge-token");
    }

    static Optional<String> token(String name) {
        return root().flatMap(r -> read(r.resolve(".local").resolve("state").resolve(name)));
    }

    static Optional<String> read(Path file) {
        try {
            String t = Files.readString(file, StandardCharsets.UTF_8).trim();
            return t.isEmpty() ? Optional.empty() : Optional.of(t);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
