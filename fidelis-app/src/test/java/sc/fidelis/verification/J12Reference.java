package sc.fidelis.verification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import sc.fidelis.testkit.Fixtures;

/**
 * The J-12 reference steps as stored with a baseline (verification/j12.py wrote them into run.json).
 * Fixture ids and the files they open are fixed here, mirroring verification/baseline.py.
 */
public final class J12Reference {
    private J12Reference() {}

    /** One reference step: the dump tag, a label, and the independent reference macro line(s). */
    public record Step(String tag, String label, String macro) {}

    public record Fixture(String id, Path openFile) {}

    /** Resolve a J-12 fixture to the file the reference macro opens (skips the test when not cached). */
    public static Fixture fixture(String id) {
        return switch (id) {
            case "FX-001" -> new Fixture(id, Fixtures.repoFixture("two-squares.png"));
            case "FX-003" -> new Fixture(id, Fixtures.require("FX-003", "blobs.gif"));
            case "FX-030" -> new Fixture(id, Fixtures.require("FX-030",
                    "extracted/human_ht29_colon_cancer_1_images/AS_09125_050118150001_A03f00d0.tif",
                    "BBBC001_v1_images_tif.zip"));
            case "FX-031" -> new Fixture(id, Fixtures.require("FX-031", "extracted/synthetic_000_images/1GRAY.tif",
                    "BBBC004_v1_000_images.zip"));
            default -> throw new IllegalArgumentException("no J-12 reference for " + id);
        };
    }

    public static List<Step> steps(Path baselineDir) {
        JsonNode run = Fixtures.readJson(baselineDir.resolve("run.json"));
        List<Step> out = new ArrayList<>();
        for (JsonNode s : run.get("steps")) out.add(new Step(s.get("tag").asText(), s.get("label").asText(), s.get("macro").asText()));
        return out;
    }

    private static final Pattern OPEN = Pattern.compile("open\\(\"[^\"]*\"\\)");
    private static final Pattern SAVE = Pattern.compile("saveAs\\(\"Tiff\", \"[^\"]*\"\\)");

    /** Point the stored open() and saveAs("Tiff", ...) at this machine's paths; every other character is kept. */
    public static String localise(String macro, Path openFile, Path tiff) {
        String m = OPEN.matcher(macro).replaceAll(Matcher.quoteReplacement("open(\"" + slash(openFile) + "\")"));
        return SAVE.matcher(m).replaceAll(Matcher.quoteReplacement("saveAs(\"Tiff\", \"" + slash(tiff) + "\")"));
    }

    private static String slash(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
