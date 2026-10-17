package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import sc.fidelis.testkit.FijiFixture;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.PixelHash;

/**
 * Tests of the verification tooling itself: PixelHash against digests computed independently in Python
 * (hashlib over struct-packed little-endian bytes), the JavaScript dumper's hash of a ramp, and that
 * {@link BaselineCompare} reports a difference when one is injected (a comparator that cannot fail proves nothing).
 */
class HarnessSelfTest {

    @BeforeAll
    static void context() {
        FijiFixture.imageJ();
    }

    @Test
    void pixelHashMatchesPythonForEveryBitDepth() {
        assertEquals("3d1f57c984978ef98a18378c8166c1cb8ede02c03eeb6aee7e2f121dfeee3e56",
                PixelHash.sha256(new byte[] {0, 1, 2, (byte) 255}));

        ImagePlus sixteen = new ImagePlus("u16", new ShortProcessor(2, 2, new short[] {1, 256, 513, (short) 65535}, null));
        assertEquals("b52d15d1f5be3cbe82734976f3aa90b8fcbf768f8260eda0f6fb2e6e632cf988", PixelHash.sha256(sixteen));

        float[] f = {1.5f, Float.intBitsToFloat(0x7fc00000), -0.0f, Float.MAX_VALUE};
        ImagePlus thirtyTwo = new ImagePlus("f32", new FloatProcessor(2, 2, f, null));
        assertEquals("2d99fed000f790e04f0b00acb51f18d952e0d6efd4749af9604a0893fa9b2f87", PixelHash.sha256(thirtyTwo));

        ImagePlus rgb = IJ.createImage("rgb", "RGB black", 2, 2, 1);
        ((ij.process.ColorProcessor) rgb.getProcessor()).setPixels(new int[] {0xFF112233, 0, 0xFFFFFFFF, 0xFF000001});
        assertEquals("52b02ea2da9f78da99caa4a9502b1b4275130af4684d95caaa1836398e8a4a13", PixelHash.sha256(rgb));
    }

    @Test
    void rampHashEqualsTheDumperJavascriptHash() {
        // verification/dumper.js hashed this same image in a launcher-run macro during development
        // (newImage("t", "8-bit ramp", 16, 16, 1) -> ad1d46a4...)
        ImagePlus ramp = IJ.createImage("t", "8-bit ramp", 16, 16, 1);
        assertEquals("ad1d46a4353c2412493e184074c3dfb2e7407c6a93899ff8acee9d946c96048a", PixelHash.sha256(ramp));
    }

    @Test
    void comparatorDetectsInjectedDifferencesAndAcceptsTheBaselineItself() {
        Path gui = Fixtures.requireBaseline("FX-001", "gui");
        JsonNode step = BaselineCompare.loadStep(gui, "s09_analyze_particles");

        // Rebuild a snapshot identical to the stored step, then mutate it.
        List<Snapshot.Img> images = new java.util.ArrayList<>();
        for (JsonNode i : step.get("images")) {
            List<String> planes = new java.util.ArrayList<>();
            i.get("planeSha256").forEach(p -> planes.add(p.asText()));
            images.add(new Snapshot.Img(i.get("title").asText(), i.get("bitDepth").asInt(), i.get("width").asInt(),
                    i.get("height").asInt(), i.get("allPlanesSha256").asText(), planes));
        }
        LinkedHashMap<String, double[]> columns = new LinkedHashMap<>();
        for (JsonNode c : step.get("results").get("columns")) {
            double[] v = new double[step.get("results").get("rows").asInt()];
            for (int r = 0; r < v.length; r++) v[r] = Double.parseDouble(c.get("values").get(r).asText());
            columns.put(c.get("name").asText(), v);
        }
        List<Snapshot.RoiSnap> rois = new java.util.ArrayList<>();
        for (JsonNode r : step.get("roiManager")) {
            float[] xs = new float[r.get("xs").size()];
            float[] ys = new float[xs.length];
            for (int k = 0; k < xs.length; k++) {
                xs[k] = Float.parseFloat(r.get("xs").get(k).asText());
                ys[k] = Float.parseFloat(r.get("ys").get(k).asText());
            }
            rois.add(new Snapshot.RoiSnap(r.get("name").asText(), xs, ys, r.get("maskSha256").asText()));
        }
        Snapshot same = new Snapshot(images, new Snapshot.Table(step.get("results").get("rows").asInt(), columns, List.of()),
                rois, Map.of());
        assertEquals(List.of(), BaselineCompare.diff(step, same, false, true));

        // E1: one pixel hash character
        List<Snapshot.Img> badImages = new java.util.ArrayList<>(images);
        Snapshot.Img first = badImages.get(0);
        badImages.set(0, new Snapshot.Img(first.title(), first.bitDepth(), first.width(), first.height(),
                "0" + first.allPlanesSha256().substring(1), first.planeSha256()));
        assertFalse(BaselineCompare.diff(step, new Snapshot(badImages, same.results(), rois, Map.of()), false, true).isEmpty());

        // E5: one ulp in one Area cell
        LinkedHashMap<String, double[]> badColumns = new LinkedHashMap<>();
        columns.forEach((k, v) -> badColumns.put(k, v.clone()));
        badColumns.get("Area")[0] = Math.nextUp(badColumns.get("Area")[0]);
        List<String> e5 = BaselineCompare.diff(step, new Snapshot(images,
                new Snapshot.Table(same.results().rows(), badColumns, List.of()), rois, Map.of()), false, true);
        assertTrue(e5.stream().anyMatch(s -> s.startsWith("E5 Area[0]")), e5.toString());

        // E4: one ulp in one vertex
        List<Snapshot.RoiSnap> badRois = new java.util.ArrayList<>(rois);
        Snapshot.RoiSnap r0 = badRois.get(0);
        float[] xs = r0.xs().clone();
        xs[0] = Math.nextUp(xs[0]);
        badRois.set(0, new Snapshot.RoiSnap(r0.name(), xs, r0.ys(), r0.maskSha256()));
        List<String> e4 = BaselineCompare.diff(step, new Snapshot(images, same.results(), badRois, Map.of()), false, true);
        assertTrue(e4.stream().anyMatch(s -> s.startsWith("E4 ROI 0")), e4.toString());

        // Retained rows: a longer live table passes only when tolerated
        LinkedHashMap<String, double[]> longer = new LinkedHashMap<>();
        columns.forEach((k, v) -> {
            double[] w = new double[v.length + 1];
            System.arraycopy(v, 0, w, 1, v.length);
            longer.put(k, w);
        });
        Snapshot extra = new Snapshot(images, new Snapshot.Table(same.results().rows() + 1, longer, List.of()), rois, Map.of());
        assertFalse(BaselineCompare.diff(step, extra, false, true).isEmpty());
        assertEquals(List.of(), BaselineCompare.diff(step, extra, true, true));
    }

    @Test
    void lockKeyMatchesThePythonSideAndBaselineDirectoriesExist() {
        assertEquals(16, Fixtures.lockKey().length());
        Optional<Path> dir = Fixtures.baselineDir("FX-001", "gui");
        assertTrue(dir.isPresent(), "FX-001 baseline for lock " + Fixtures.lockKey()
                + " is missing: the Fiji pin changed or baselines were not generated");
    }
}
