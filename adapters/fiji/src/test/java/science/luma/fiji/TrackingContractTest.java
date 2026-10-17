package science.luma.fiji;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.OvalRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.ShortProcessor;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic time-lapses (NO_SHOW) for track_objects and intensity_over_time. Never touches research data. */
public final class TrackingContractTest {
    static final Gson GSON = new Gson();

    public static void main(String[] ignored) throws Exception {
        new ImageJ(ImageJ.NO_SHOW);
        try { run(); } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.out.println("Tracking contract (track_objects, intensity_over_time): passed");
        System.exit(0);
    }

    // Three blobs: A and B from frame 1, C appears at frame 6. Velocities in pixels/frame.
    static final double[][] BLOBS = { {20, 20, 2, 1, 1}, {100, 30, -1.5, 2, 1}, {30, 100, 3, 0, 6} };

    static ImagePlus movie(int frames, boolean asSlices) {
        var stack = new ImageStack(128, 128);
        var random = new Random(7);
        for (int t = 1; t <= frames; t++) {
            var ip = new ShortProcessor(128, 128);
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                double v = 100 + random.nextGaussian() * 5;
                for (var b : BLOBS) {
                    if (t < b[4]) continue;
                    double cx = b[0] + b[2] * (t - b[4]), cy = b[1] + b[3] * (t - b[4]);
                    v += 2000 * Math.exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (2 * 2.0 * 2.0));
                }
                ip.set(x, y, (int) Math.round(Math.max(0, v)));
            }
            stack.addSlice(ip);
        }
        var imp = new ImagePlus("synthetic-tracks.tif", stack);
        if (asSlices) imp.setDimensions(1, frames, 1); else imp.setDimensions(1, 1, frames);
        imp.setOpenAsHyperStack(true);
        return imp;
    }

    static void run() throws Exception {
        var journal = new Journal();
        var ops = new Operations(journal);
        var tracking = new Tracking(ops, journal);

        // --- track_objects on a 20-frame movie
        var imp = movie(20, false);
        var result = onEdt(imp, () -> tracking.trackObjects(new JsonObject()));
        System.out.println("track_objects: " + GSON.toJson(withoutHighlight(result)));
        expect(((Number) result.get("tracks")).intValue() == 3, "3 tracks, got " + result.get("tracks"));
        expect(((Number) result.get("frames")).intValue() == 20, "20 frames");
        expect(((Number) result.get("longestTrack")).intValue() == 20, "longest track spans 20 frames");
        @SuppressWarnings("unchecked") var top = (List<Map<String, Object>>) result.get("top");
        double[] expected = { Math.hypot(2, 1), Math.hypot(1.5, 2), 3 };
        int[] lengths = { 20, 20, 15 };
        for (int b = 0; b < 3; b++) {
            boolean matched = false;
            for (var row : top) {
                double speed = ((Number) row.get("meanSpeed")).doubleValue();
                if (((Number) row.get("length")).intValue() == lengths[b] && Math.abs(speed - expected[b]) / expected[b] < 0.10) matched = true;
            }
            expect(matched, "blob " + (char) ('A' + b) + " tracked at " + String.format("%.3f", expected[b]) + " px/frame within 10%");
        }
        var late = top.stream().filter(r -> ((Number) r.get("length")).intValue() == 15).findFirst().orElseThrow();
        expect(((Number) late.get("start")).intValue() == 6, "late blob starts at frame 6");
        long polylines = countPolylines(imp);
        expect(polylines == 3, "overlay has 3 Track polylines, got " + polylines);
        expect(tracking.lastTable.size() == 55 && tracking.lastTable.getColumnIndex("Speed") >= 0, "Tracks table has 55 rows with Speed");
        expect(journal.macro().contains("// Penny track_objects"), "journal records the macro-equivalent");
        System.out.println("PASS track_objects: 3 tracks, speeds within 10%, late track starts at frame 6, 3 Track polylines");

        // --- calibrated rerun replaces only Track rois, keeps the user's overlay, converts units
        imp.getOverlay().add(new Roi(1, 1, 5, 5));
        imp.getOverlay().get(imp.getOverlay().size() - 1).setName("user note");
        imp.getCalibration().pixelWidth = 0.5; imp.getCalibration().pixelHeight = 0.5; imp.getCalibration().setUnit("micron");
        imp.getCalibration().frameInterval = 2; imp.getCalibration().setTimeUnit("sec");
        var calibrated = onEdt(imp, () -> tracking.trackObjects(new JsonObject()));
        expect(countPolylines(imp) == 3, "rerun replaced Track polylines (still 3)");
        expect(imp.getOverlay().getIndex("user note") >= 0, "user's overlay roi kept");
        double rawMean = ((Number) result.get("meanSpeed")).doubleValue(), calMean = ((Number) calibrated.get("meanSpeed")).doubleValue();
        expect(Math.abs(calMean - rawMean * 0.25) < 1e-3, "calibrated speed = px speed x 0.5 um / 2 s: " + calMean + " vs " + rawMean);
        expect("sec".equals(calibrated.get("timeUnit")) && ((String) calibrated.get("speedUnit")).endsWith("/sec"), "time unit reported: " + calibrated.get("speedUnit"));
        System.out.println("PASS track_objects calibrated: " + calibrated.get("meanSpeed") + " " + calibrated.get("speedUnit") + ", user overlay kept");

        // --- slices treated as time
        var slices = movie(20, true);
        var sliceResult = onEdt(slices, () -> tracking.trackObjects(new JsonObject()));
        expect(((Number) sliceResult.get("tracks")).intValue() == 3 && "slices".equals(sliceResult.get("timeAxis")), "z-stack treated as time: " + sliceResult.get("tracks"));
        System.out.println("PASS track_objects on slices-as-time");

        // --- single frame refused
        var single = new ImagePlus("one.tif", new ShortProcessor(32, 32));
        try { onEdt(single, () -> tracking.trackObjects(new JsonObject())); expect(false, "single frame should be refused"); }
        catch (IllegalArgumentException error) { expect(error.getMessage().contains("only one frame"), "single frame message: " + error.getMessage()); }
        System.out.println("PASS single frame refused with a speakable message");

        // --- intensity_over_time: a fixed blob decays by 50%, background 0
        var stack = new ImageStack(64, 64);
        for (int t = 0; t < 20; t++) {
            var ip = new ShortProcessor(64, 64);
            double amplitude = 2000 * (1 - 0.5 * t / 19.0);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) ip.set(x, y, (int) Math.round(amplitude * Math.exp(-((x - 32) * (x - 32) + (y - 32) * (y - 32)) / 18.0)));
            stack.addSlice(ip);
        }
        var frap = new ImagePlus("decay.tif", stack);
        frap.setDimensions(1, 1, 20); frap.setOpenAsHyperStack(true);
        frap.setT(7);
        var roi = new OvalRoi(29, 29, 7, 7); roi.setName("spot");
        frap.setRoi(roi);
        var kinetics = onEdt(frap, () -> tracking.intensityOverTime(new JsonObject()));
        System.out.println("intensity_over_time: " + GSON.toJson(withoutHighlight(kinetics)));
        @SuppressWarnings("unchecked") var series = (List<Map<String, Object>>) kinetics.get("series");
        double change = ((Number) series.getFirst().get("changePercent")).doubleValue();
        expect(Math.abs(change + 50) <= 5, "changePercent about -50, got " + change);
        expect(frap.getT() == 7 && frap.getRoi() != null && "spot".equals(frap.getRoi().getName()), "frame and ROI unchanged");
        expect(((List<?>) series.getFirst().get("values")).size() == 20 && ((Number) series.getFirst().get("peakFrame")).intValue() == 1, "20 values, peak at frame 1");
        System.out.println("PASS intensity_over_time active ROI: " + change + "%");

        // --- manager scope with indices
        var managed = onEdt(frap, () -> {
            var manager = ops.manager();
            manager.reset();
            var a = new OvalRoi(29, 29, 7, 7); a.setName("blob"); manager.addRoi(a);
            var b = new Roi(0, 0, 8, 8); b.setName("background"); manager.addRoi(b);
            var args = new JsonObject(); args.addProperty("scope", "manager"); var idx = new JsonArray(); idx.add(0); idx.add(1); args.add("indices", idx);
            return tracking.intensityOverTime(args);
        });
        expect(((Number) managed.get("rois")).intValue() == 2 && tracking.lastTable.size() == 40, "manager scope measures 2 ROIs x 20 frames");
        System.out.println("PASS intensity_over_time manager scope: 2 ROIs, 40 rows");

        // --- optional real sample
        var sample = Path.of(System.getProperty("luma.root", "."), ".local/data/samples/faketracks/FakeTracks.tif");
        if (Files.isRegularFile(sample)) {
            var fake = IJ.openImage(sample.toString());
            if (fake != null) {
                var fakeResult = onEdt(fake, () -> tracking.trackObjects(new JsonObject()));
                expect(((Number) fakeResult.get("tracks")).intValue() > 0, "FakeTracks yields tracks");
                System.out.println("PASS FakeTracks.tif (" + fake.getNFrames() + " frames, " + fake.getNSlices() + " slices): " + GSON.toJson(withoutHighlight(fakeResult)));
            }
        } else System.out.println("SKIP FakeTracks.tif not present");
    }

    static long countPolylines(ImagePlus imp) {
        long n = 0;
        for (var r : imp.getOverlay().toArray()) if (r instanceof PolygonRoi && r.getType() == Roi.POLYLINE && r.getName() != null && r.getName().startsWith("Track ")) n++;
        return n;
    }
    static Map<String, Object> withoutHighlight(Map<String, Object> result) { var copy = new java.util.LinkedHashMap<>(result); copy.remove("highlight"); copy.remove("top"); return copy; }

    interface Call { Map<String, Object> run() throws Exception; }
    static Map<String, Object> onEdt(ImagePlus imp, Call call) throws Exception {
        var out = new AtomicReference<Map<String, Object>>(); var failure = new AtomicReference<Throwable>();
        EventQueue.invokeAndWait(() -> { try { WindowManager.setTempCurrentImage(imp); out.set(call.run()); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() instanceof Exception e) throw e;
        if (failure.get() != null) throw new RuntimeException(failure.get());
        return out.get();
    }
    static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
