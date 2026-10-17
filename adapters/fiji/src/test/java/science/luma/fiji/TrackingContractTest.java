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

        // --- z-stack time-lapse with two channels: blobs only in z 2 of channel 1 (plus 3x3 specks), channel 2 flickering noise blobs.
        var zstack = zMovie(20);
        var zResult = onEdt(zstack, () -> tracking.trackObjects(new JsonObject()));
        System.out.println("track_objects z-stack: " + GSON.toJson(withoutHighlight(zResult)));
        expect(Boolean.TRUE.equals(zResult.get("zProjected")), "z slices max-projected per frame");
        expect(((Number) zResult.get("channel")).intValue() == 1, "auto channel is the one with the steadiest object count: " + zResult.get("channelChoice"));
        expect(((Number) zResult.get("tracks")).intValue() == 3, "3 tracks after projection and the robust minSize (specks dropped), got " + zResult.get("tracks"));
        expect(zResult.get("stability") instanceof Number cv && cv.doubleValue() < 0.5 && !zResult.containsKey("warning"), "stable count, no warning: " + zResult.get("stability") + " " + zResult.get("warning"));
        @SuppressWarnings("unchecked") var zParams = (Map<String, Object>) zResult.get("parameters");
        expect(((Number) zParams.get("minSize")).doubleValue() > 9 && String.valueOf(zParams.get("minSizeFrom")).startsWith("0.3 x size mode"), "minSize above 2x2 specks: " + zParams);
        expect(((Number) zParams.get("minLength")).intValue() == 4, "minLength default max(3, 20% of 20 frames) = 4");
        var nonsense = new JsonObject(); nonsense.addProperty("channel", 2); nonsense.addProperty("minSize", 0.5); nonsense.addProperty("minLength", 1);
        var flicker = onEdt(zstack, () -> tracking.trackObjects(nonsense));
        System.out.println("track_objects nonsense parameters on the noise channel: " + GSON.toJson(withoutHighlight(flicker)));
        expect(flicker.get("warning") instanceof String w && !w.isBlank() && ((List<?>) flicker.get("adjusted")).size() == 2, "nonsense parameters clamped and an unstable channel warned: " + flicker.get("adjusted") + " / " + flicker.get("warning"));
        var onePlane = new JsonObject(); onePlane.addProperty("channel", 1); onePlane.addProperty("projectZ", false);
        zstack.setPosition(1, 2, 1);
        var plane = onEdt(zstack, () -> tracking.trackObjects(onePlane));
        expect(Boolean.FALSE.equals(plane.get("zProjected")) && ((Number) plane.get("tracks")).intValue() == 3, "projectZ false tracks the current z plane: " + plane.get("tracks"));
        System.out.println("PASS track_objects z-stack: projected, channel 1 chosen, specks dropped, 3 tracks; nonsense parameters clamped and warned");

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

        // --- optional real samples
        var sample = Path.of(System.getProperty("luma.root", "."), ".local/data/samples/faketracks/FakeTracks.tif");
        if (Files.isRegularFile(sample)) {
            var fake = IJ.openImage(sample.toString());
            if (fake != null) {
                var fakeResult = onEdt(fake, () -> tracking.trackObjects(new JsonObject()));
                int n = ((Number) fakeResult.get("tracks")).intValue();
                // Synthetic truth: at most 4 spots per frame, 2 splits, 1 merge, 1 gap, so 4-8 tracks.
                expect(n >= 4 && n <= 8 && !fakeResult.containsKey("warning"), "FakeTracks gives 4-8 tracks without a warning, got " + n + " " + fakeResult.get("warning"));
                System.out.println("SAMPLE FakeTracks.tif (" + fake.getNFrames() + " frames, " + fake.getNSlices() + " slices): " + GSON.toJson(withoutHighlight(fakeResult)));
            }
        } else System.out.println("SKIP FakeTracks.tif not present");
        var mitosisPath = Path.of(System.getProperty("luma.root", "."), ".local/data/samples/mitosis/mitosis.tif");
        if (Files.isRegularFile(mitosisPath)) {
            var mitosis = IJ.openImage(mitosisPath.toString());
            if (mitosis != null) {
                var mitosisResult = onEdt(mitosis, () -> tracking.trackObjects(new JsonObject()));
                int n = ((Number) mitosisResult.get("tracks")).intValue();
                expect(Boolean.TRUE.equals(mitosisResult.get("zProjected")) && n >= 1 && n <= 8, "mitosis (one dividing cell) gives a handful of tracks, got " + n);
                System.out.println("SAMPLE mitosis.tif defaults (" + mitosis.getNChannels() + " ch, " + mitosis.getNSlices() + " z, " + mitosis.getNFrames() + " frames): " + GSON.toJson(withoutHighlight(mitosisResult)));
                // The QA battery's arguments (Q16r): ch1, Otsu, minSize 0.5, maxDistance 10, maxGap 2, minLength 1 gave 83 tracks before.
                var qa = new JsonObject(); qa.addProperty("channel", 1); qa.addProperty("method", "Otsu"); qa.addProperty("minSize", 0.5); qa.addProperty("maxDistance", 10); qa.addProperty("maxGap", 2); qa.addProperty("minLength", 1);
                var qaResult = onEdt(mitosis, () -> tracking.trackObjects(qa));
                expect(((Number) qaResult.get("tracks")).intValue() <= 8, "QA arguments no longer give dozens of tracks: " + qaResult.get("tracks"));
                System.out.println("SAMPLE mitosis.tif QA arguments: " + GSON.toJson(withoutHighlight(qaResult)));
            }
        } else System.out.println("SKIP mitosis.tif not present");
    }

    /** 2 channels x 3 z x frames. Channel 1: the three BLOBS in z 2 only, plus four random 2x2 specks per frame. Channel 2: 12 random blobs every third frame, 1 otherwise. */
    static ImagePlus zMovie(int frames) {
        var stack = new ImageStack(128, 128);
        var random = new Random(11);
        for (int t = 1; t <= frames; t++) for (int z = 1; z <= 3; z++) for (int c = 1; c <= 2; c++) {
            var ip = new ShortProcessor(128, 128);
            double[][] flicker = new double[c == 2 && z == 2 ? (t % 3 == 0 ? 12 : 1) : 0][];
            for (int k = 0; k < flicker.length; k++) flicker[k] = new double[]{8 + random.nextInt(112), 8 + random.nextInt(112)};
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                double v = 100 + random.nextGaussian() * 5;
                if (c == 1 && z == 2) for (var b : BLOBS) {
                    if (t < b[4]) continue;
                    double cx = b[0] + b[2] * (t - b[4]), cy = b[1] + b[3] * (t - b[4]);
                    v += 2000 * Math.exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (2 * 2.0 * 2.0));
                }
                for (var f : flicker) v += 2000 * Math.exp(-((x - f[0]) * (x - f[0]) + (y - f[1]) * (y - f[1])) / (2 * 2.0 * 2.0));
                ip.set(x, y, (int) Math.round(Math.max(0, v)));
            }
            if (c == 1 && z == 2) for (int k = 0; k < 4; k++) { ip.setValue(2100); ip.setRoi(10 + random.nextInt(108), 10 + random.nextInt(108), 2, 2); ip.fill(); ip.resetRoi(); }
            stack.addSlice(ip);
        }
        var imp = new ImagePlus("synthetic-zstack-tracks.tif", stack);
        imp.setDimensions(2, 3, frames); imp.setOpenAsHyperStack(true);
        return imp;
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
