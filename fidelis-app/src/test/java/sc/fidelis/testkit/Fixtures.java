package sc.fidelis.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resolution of verification fixtures and baselines (docs A29, D18 section 5). The repository root is
 * {@code -Dfidelis.repo.dir} (set by tools/test.py). Licence-restricted fixtures live only in the
 * git-ignored {@code fixtures/cache/}; a test needing one calls {@link #require} and is skipped, never
 * failed, when it is absent. Every use re-verifies the SHA-256 pinned in {@code fixtures/manifest.json}.
 */
public final class Fixtures {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {}

    public static Path repoDir() {
        String prop = System.getProperty("fidelis.repo.dir");
        Path start = prop != null ? Paths.get(prop) : Paths.get("").toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("fixtures").resolve("manifest.json"))) return p;
        }
        throw new IllegalStateException("repository root with fixtures/manifest.json not found from " + start);
    }

    public static Path cacheDir() {
        return repoDir().resolve("fixtures").resolve("cache");
    }

    public static JsonNode manifest() {
        return readJson(repoDir().resolve("fixtures").resolve("manifest.json"));
    }

    public static JsonNode readJson(Path path) {
        try {
            return JSON.readTree(path.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    /** A committed (REPO class) fixture, for example {@code two-squares.png}; its pinned digest is verified. */
    public static Path repoFixture(String name) {
        Path p = repoDir().resolve("fixtures").resolve(name);
        Assumptions.assumeTrue(Files.isRegularFile(p), "repo fixture missing: " + p);
        for (JsonNode fx : manifest().get("fixtures")) {
            JsonNode local = fx.get("local_path");
            if (local != null && !local.isNull() && local.asText().equals("fixtures/" + name)) {
                JsonNode pinned = fx.get("files").get(0).get("sha256");
                if (pinned != null && !pinned.isNull() && !pinned.asText().equals(sha256(p))) {
                    throw new AssertionError("repo fixture " + name + " does not match the manifest digest");
                }
            }
        }
        return p;
    }

    /**
     * A cached fixture file {@code fixtures/cache/<id>/<relative>}, or an abort (skip) when missing.
     * The pinned SHA-256 of the manifest file {@code pinnedFile} (the downloaded archive for extracted
     * members) is verified first.
     */
    public static Path require(String fixtureId, String relative, String pinnedFile) {
        Path file = cacheDir().resolve(fixtureId).resolve(relative);
        Assumptions.assumeTrue(Files.isRegularFile(file),
                "fixture " + fixtureId + " not cached (python verification/fetch_fixtures.py --only " + fixtureId + ")");
        verifyPinned(fixtureId, pinnedFile);
        return file;
    }

    /** Convenience: the single-file fixture {@code name} cached under its own id. */
    public static Path require(String fixtureId, String name) {
        return require(fixtureId, name, name);
    }

    private static void verifyPinned(String fixtureId, String pinnedFile) {
        for (JsonNode fx : manifest().get("fixtures")) {
            if (!fx.get("id").asText().equals(fixtureId)) continue;
            for (JsonNode f : fx.get("files")) {
                if (!f.get("name").asText().equals(pinnedFile)) continue;
                String pinned = f.get("sha256").isNull() ? null : f.get("sha256").asText();
                Assumptions.assumeTrue(pinned != null, fixtureId + "/" + pinnedFile + " has no pinned digest");
                Path file = cacheDir().resolve(fixtureId).resolve(pinnedFile);
                Assumptions.assumeTrue(Files.isRegularFile(file), "pinned file not cached: " + file);
                String actual = sha256(file);
                if (!actual.equals(pinned)) {
                    throw new AssertionError("fixture " + fixtureId + "/" + pinnedFile + " SHA-256 " + actual
                            + " does not match the manifest " + pinned);
                }
                return;
            }
        }
        throw new IllegalStateException("fixture not in manifest: " + fixtureId + "/" + pinnedFile);
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) md.update(buffer, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** First 16 hex digits of SHA-256(external/fiji.lock.json): the baseline key (CR-A30-04). */
    public static String lockKey() {
        try {
            byte[] bytes = Files.readAllBytes(repoDir().resolve("external").resolve("fiji.lock.json"));
            return PixelHash.sha256(bytes).substring(0, 16);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code verification/baselines/<fixture>/<lockKey>/<mode>} for mode gui or headless. */
    public static Optional<Path> baselineDir(String fixtureId, String mode) {
        Path dir = repoDir().resolve("verification").resolve("baselines").resolve(fixtureId)
                .resolve(lockKey()).resolve(mode);
        return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }

    /** A baseline directory or an abort (skip) with the regeneration command. */
    public static Path requireBaseline(String fixtureId, String mode) {
        Optional<Path> dir = baselineDir(fixtureId, mode);
        Assumptions.assumeTrue(dir.isPresent(), "no " + mode + " baseline for " + fixtureId + " under lock "
                + lockKey() + " (pin changed? run: python verification/baseline.py --fixture " + fixtureId + ")");
        return dir.get();
    }
}
