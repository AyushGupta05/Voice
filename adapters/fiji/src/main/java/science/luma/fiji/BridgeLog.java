package science.luma.fiji;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;

/** The bridge's own log: a file under <root>/.local, never stdout/stderr (Fiji opens its Console window for those). */
final class BridgeLog {
    private BridgeLog() {}
    static synchronized void write(String message) {
        String root = LumaBridge.root();
        if (root == null) return;
        try {
            Path file = Path.of(root, ".local", "bridge.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, ZonedDateTime.now().withNano(0) + " " + message + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) { /* logging never breaks the bridge */ }
    }
}
