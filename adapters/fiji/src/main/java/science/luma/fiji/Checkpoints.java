package science.luma.fiji;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private in-memory copies of an image's pixels, ROI, overlay, ROI Manager and Results table. Never restores files or display settings. */
final class Checkpoints {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    static final int MAX_COUNT = 6;
    static final String SCOPE = "pixels, ROI, overlay, this image's ROI Manager entries, analysis parameters and the Results table; not files, windows, display settings or other images";

    record Checkpoint(String id, String label, int imageId, String title, ImageStack stack, int channel, int slice, int frame,
                      Roi roi, Overlay overlay, List<Operations.ManagerRoi> managerRois, ResultsTable results, Map<String, Object> counts, long bytes, String at, Pipeline pipeline) {}

    private final LinkedHashMap<String, Checkpoint> checkpoints = new LinkedHashMap<>();

    static long pixelBytes(ImagePlus imp) {
        int bytesPerPixel = switch (imp.getBitDepth()) { case 8 -> 1; case 16 -> 2; case 24 -> 4; default -> 4; };
        return (long) imp.getWidth() * imp.getHeight() * imp.getStackSize() * bytesPerPixel;
    }

    /** Best effort: an image too large to copy must never block the researcher's action; the result says so. */
    synchronized Map<String, Object> capture(ImagePlus imp, String label, RoiManager manager, ResultsTable results, Map<String, Object> counts) {
        return capture(imp, label, manager, results, counts, null);
    }
    synchronized Map<String, Object> capture(ImagePlus imp, String label, RoiManager manager, ResultsTable results, Map<String, Object> counts, Pipeline pipeline) {
        long bytes = pixelBytes(imp);
        if (bytes > MAX_BYTES) return skipped(label, counts, "image pixels are " + (bytes >> 20) + " MiB, above the 512 MiB checkpoint cap");
        ImageStack stack; Roi roi; Overlay overlay; List<Operations.ManagerRoi> rois; ResultsTable table;
        try {
            stack = imp.getStack().duplicate();
            roi = imp.getRoi() == null ? null : (Roi) imp.getRoi().clone();
            overlay = imp.getOverlay() == null ? null : imp.getOverlay().duplicate();
            rois = Operations.captureManagerRois(manager).stream().filter(entry -> Operations.belongsTo(entry.roi(), imp.getID())).toList();
            table = results == null ? null : (ResultsTable) results.clone();
        } catch (OutOfMemoryError | RuntimeException error) {
            return skipped(label, counts, "copying failed: " + error.getClass().getSimpleName());
        }
        String id = UUID.randomUUID().toString();
        var checkpoint = new Checkpoint(id, label, imp.getID(), imp.getTitle(), stack, imp.getC(), imp.getZ(), imp.getT(), roi, overlay, rois, table, counts, bytes, java.time.Instant.now().toString(), pipeline == null ? null : pipeline.copy());
        checkpoints.put(id, checkpoint);
        while (checkpoints.size() > MAX_COUNT || totalBytes() > MAX_TOTAL_BYTES && checkpoints.size() > 1) checkpoints.remove(checkpoints.keySet().iterator().next());
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("label", label); result.put("imageId", imp.getID()); result.put("title", imp.getTitle()); result.put("bytes", bytes); result.put("counts", counts); result.put("scope", SCOPE);
        return result;
    }
    private static Map<String, Object> skipped(String label, Map<String, Object> counts, String reason) {
        var result = new LinkedHashMap<String, Object>();
        result.put("skipped", true); result.put("label", label); result.put("counts", counts);
        result.put("reason", "Checkpoint skipped (" + reason + "); this step cannot be undone with fiji_restore. Save the image first if you may need to go back.");
        result.put("scope", SCOPE);
        return result;
    }
    private long totalBytes() { long total = 0; for (var c : checkpoints.values()) total += c.bytes(); return total; }

    synchronized Checkpoint require(String id) {
        var checkpoint = checkpoints.get(id);
        if (checkpoint == null) throw new IllegalArgumentException("Checkpoint expired or not found.");
        return checkpoint;
    }
    synchronized Checkpoint latestFor(int imageId) {
        Checkpoint latest = null;
        for (var checkpoint : checkpoints.values()) if (checkpoint.imageId() == imageId) latest = checkpoint;
        return latest;
    }
    synchronized void remove(String id) { checkpoints.remove(id); }

    synchronized List<Map<String, Object>> list(ImagePlus active) {
        var result = new ArrayList<Map<String, Object>>();
        for (var c : checkpoints.values()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", c.id()); row.put("label", c.label()); row.put("imageId", c.imageId()); row.put("title", c.title()); row.put("counts", c.counts());
            row.put("bytes", c.bytes()); row.put("at", c.at()); row.put("currentImage", active != null && active.getID() == c.imageId());
            result.add(row);
        }
        return result;
    }

    /** Puts the copied state back into the live image; the caller has already captured a "Before restore" checkpoint. */
    static void restore(Checkpoint checkpoint, ImagePlus imp, RoiManager manager) {
        if (imp.getID() != checkpoint.imageId()) throw new IllegalStateException("Checkpoint belongs to another image (" + checkpoint.title() + ").");
        var stack = checkpoint.stack().duplicate();
        if (stack.size() == imp.getStackSize()) imp.setStack(stack); else imp.setStack(stack, imp.getNChannels(), stack.size() / Math.max(1, imp.getNChannels() * imp.getNFrames()), imp.getNFrames());
        try { imp.setPosition(checkpoint.channel(), checkpoint.slice(), checkpoint.frame()); } catch (RuntimeException ignored) { /* position outside the restored stack */ }
        if (checkpoint.roi() == null) imp.deleteRoi(); else imp.setRoi((Roi) checkpoint.roi().clone());
        imp.setOverlay(checkpoint.overlay() == null ? null : checkpoint.overlay().duplicate());
        if (manager != null) {
            // Only this image's entries are replaced; other images' ROIs stay in the manager untouched.
            var others = Operations.captureManagerRois(manager).stream().filter(entry -> !Operations.belongsTo(entry.roi(), imp.getID())).toList();
            manager.reset();
            for (var entry : others) entry.appendTo(manager);
            for (var entry : checkpoint.managerRois()) entry.appendTo(manager);
        }
        if (checkpoint.results() != null) {
            var table = (ResultsTable) checkpoint.results().clone();
            ij.plugin.filter.Analyzer.setResultsTable(table);
            if (ij.IJ.isResultsWindow()) table.show("Results");
        }
        imp.updateAndDraw();
    }

    /** Fraction of pixels that differ between the checkpoint and the live image on the current stack size; sampled when large. */
    static Map<String, Object> pixelDiff(Checkpoint checkpoint, ImagePlus imp) {
        var result = new LinkedHashMap<String, Object>();
        var saved = checkpoint.stack(); var live = imp.getStack();
        if (saved.size() != live.size() || saved.getWidth() != live.getWidth() || saved.getHeight() != live.getHeight()) { result.put("comparable", false); result.put("reason", "stack dimensions changed"); return result; }
        long compared = 0, changed = 0; int step = (int) Math.max(1, Checkpoints.pixelBytes(imp) / (16L * 1024 * 1024));
        for (int s = 1; s <= saved.size(); s++) {
            var a = saved.getProcessor(s); var b = live.getProcessor(s); int n = a.getPixelCount();
            for (int i = 0; i < n; i += step) { compared++; if (a.getf(i) != b.getf(i)) changed++; }
        }
        result.put("comparable", true); result.put("sampledPixels", compared); result.put("changedFraction", compared == 0 ? 0.0 : (double) changed / compared); result.put("sampleStep", step);
        return result;
    }
}
