package sc.fidelis.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.MeasurementRow;

/**
 * Snapshot undo (E5): before reversible data actions on non-virtual images the whole stack is
 * duplicated together with display range, calibration and position, within a memory budget (default
 * 25% of the maximum heap). Oldest snapshots are evicted first, across documents.
 *
 * <p>Since 18 Oct 2026 a snapshot can also hold the document's <b>ROI list</b> (before ROI_LIST_ADD / DELETE / RENAME
 * and Analyze Particles with {@code add}) and what an action does to the <b>Results</b> table (Measure, Analyze
 * Particles with {@code display}, Clear Results, and a ROI copy that brings its objects' measurements along): undo
 * removes the rows that action appended and, when it cleared the table, puts the cleared rows back. The Results undo
 * is per action, never a replacement of the whole table, so documents measured in any order undo independently.
 * Pixel snapshots behave as before; a snapshot may hold any combination.
 */
final class Snapshots {
    /** State of an image before one action. */
    static final class Snapshot {
        /** The action the snapshot precedes; set when the action's record is written (direct operations allocate late). */
        volatile String actionId;
        final ActionRequest request;
        /** The pixels before the action, or null when only the ROI list / Results were captured. */
        final ImageStack stack;
        final String title;
        final double min;
        final double max;
        final Calibration calibration;
        final int channels;
        final int slices;
        final int frames;
        final int c;
        final int z;
        final int t;
        /** The document's ROI list before the action (clones), or null when not captured. */
        final List<Roi> rois;
        /** Whether undo removes the Results rows of {@link #resultsAction}. */
        final boolean results;
        /** Rows the action cleared from the table (restored before the surviving rows), or null when it cleared none. */
        final List<MeasurementRow> clearedRows;
        /** The action whose rows undo removes: this action's, or the one a ROI copy named as {@code resultsOf}. */
        volatile String resultsAction;
        final long bytes;
        final Doc doc;

        Snapshot(Doc doc, String actionId, ActionRequest request, ImagePlus imp, List<Roi> rois, boolean results,
                 List<MeasurementRow> clearedRows, String resultsAction, long bytes) {
            this.doc = doc;
            this.actionId = actionId;
            this.request = request;
            if (imp != null) {
                this.stack = imp.getStack().duplicate();
                this.title = imp.getTitle();
                this.min = imp.getDisplayRangeMin();
                this.max = imp.getDisplayRangeMax();
                this.calibration = imp.getCalibration().copy();
                this.channels = imp.getNChannels();
                this.slices = imp.getNSlices();
                this.frames = imp.getNFrames();
                this.c = imp.getChannel();
                this.z = imp.getSlice();
                this.t = imp.getFrame();
            } else {
                this.stack = null;
                this.title = null;
                this.min = 0;
                this.max = 0;
                this.calibration = null;
                this.channels = this.slices = this.frames = this.c = this.z = this.t = 0;
            }
            this.rois = rois;
            this.results = results;
            this.clearedRows = clearedRows;
            this.resultsAction = resultsAction;
            this.bytes = bytes;
        }

        boolean hasPixels() {
            return stack != null;
        }
    }

    /** What an undo put back. */
    record Restored(boolean pixels, boolean rois, int roisBefore, int roisAfter, boolean results, int rowsBefore, int rowsAfter) {}

    private final long budget;
    private long used;
    /** All live snapshots, oldest first. Guarded by this. */
    private final Deque<Snapshot> order = new ArrayDeque<>();

    Snapshots() {
        this(Runtime.getRuntime().maxMemory() / 4);
    }

    Snapshots(long budgetBytes) {
        this.budget = budgetBytes;
    }

    synchronized long[] budget() {
        return new long[] {used, budget};
    }

    static long sizeOf(ImagePlus imp) {
        long bpp = switch (imp.getBitDepth()) {
            case 8 -> 1;
            case 16 -> 2;
            default -> 4;
        };
        return (long) imp.getWidth() * imp.getHeight() * Math.max(1, imp.getStackSize()) * bpp;
    }

    /** Take a pixel snapshot and push it on the document undo stack; null when virtual or over budget. */
    Snapshot take(Doc d, String actionId, ActionRequest request, boolean clearRedo) {
        return take(d, actionId, request, clearRedo, true, false, false, null, null);
    }

    /**
     * Take a snapshot of the parts asked for: {@code pixels} (skipped for a virtual stack or over budget), {@code rois}
     * (the document's ROI list) and {@code results} (undo removes the rows of {@code resultsAction}, or of this action
     * when null; {@code clearedRows} are put back when the action clears the table). Null when nothing could be taken.
     */
    Snapshot take(Doc d, String actionId, ActionRequest request, boolean clearRedo, boolean pixels, boolean rois, boolean results,
                  List<MeasurementRow> clearedRows, String resultsAction) {
        ImagePlus imp = d.imp;
        if (pixels && imp.getStackSize() > 0 && imp.getStack().isVirtual()) pixels = false;
        long bytes = pixels ? sizeOf(imp) : 0;
        if (pixels && bytes > budget) {
            pixels = false;
            bytes = 0;
        }
        if (!pixels && !rois && !results) return null;
        List<Roi> roiCopies = null;
        if (rois) {
            roiCopies = new ArrayList<>();
            synchronized (d.roiList) {
                for (Roi r : d.roiList) roiCopies.add((Roi) r.clone());
            }
            bytes += 256L * roiCopies.size();
        }
        if (clearedRows != null) for (MeasurementRow r : clearedRows) bytes += 16L * (r.values().size() + 1);
        synchronized (this) {
            while (used + bytes > budget && !order.isEmpty()) evictOldest();
        }
        Snapshot s = new Snapshot(d, actionId, request, pixels ? imp : null, roiCopies, results, clearedRows, resultsAction, bytes);
        synchronized (this) {
            used += bytes;
            order.addLast(s);
        }
        synchronized (d.undo) {
            d.undo.addFirst(s);
            if (clearRedo) d.redo.clear();
        }
        return s;
    }

    private void evictOldest() {
        Snapshot old = order.pollFirst();
        used -= old.bytes;
        synchronized (old.doc.undo) {
            old.doc.undo.remove(old);
        }
    }

    /** Forget a snapshot (after it was used or its action is moot). */
    synchronized void release(Snapshot s) {
        if (order.remove(s)) used -= s.bytes;
        synchronized (s.doc.undo) {
            s.doc.undo.remove(s);
        }
    }

    /** Forget every snapshot of a document (it was closed). */
    synchronized void releaseAll(Doc d) {
        for (Iterator<Snapshot> it = order.iterator(); it.hasNext(); ) {
            Snapshot s = it.next();
            if (s.doc == d) {
                used -= s.bytes;
                it.remove();
            }
        }
        synchronized (d.undo) {
            d.undo.clear();
            d.redo.clear();
        }
    }

    /** Restore the image to the snapshot state (pixels), the ROI list and the Results rows it covers. */
    static Restored restore(Snapshot s, Results results) {
        Doc doc = s.doc;
        if (s.hasPixels()) {
            ImagePlus imp = doc.imp;
            boolean unlocked = imp.lockSilently();
            try {
                imp.setStack(s.title, s.stack);
                imp.setDimensions(s.channels, s.slices, s.frames);
                imp.setCalibration(s.calibration);
                imp.setPosition(s.c, s.z, s.t);
                if (!(imp instanceof CompositeImage) && imp.getBitDepth() != 24) imp.setDisplayRange(s.min, s.max);
                imp.updateAndDraw();
                imp.changes = true;
            } finally {
                if (unlocked) imp.unlock();
            }
        }
        int roisBefore = 0, roisAfter = 0;
        if (s.rois != null) {
            synchronized (doc.roiList) {
                roisBefore = doc.roiList.size();
                doc.roiList.clear();
                for (Roi r : s.rois) doc.roiList.add((Roi) r.clone());
                roisAfter = doc.roiList.size();
            }
        }
        int rowsBefore = 0, rowsAfter = 0;
        if (s.results && results != null) {
            String action = s.resultsAction != null ? s.resultsAction : s.actionId;
            int[] rows = results.undoAction(action, s.clearedRows);
            rowsBefore = rows[0];
            rowsAfter = rows[1];
        }
        return new Restored(s.hasPixels(), s.rois != null, roisBefore, roisAfter, s.results, rowsBefore, rowsAfter);
    }
}
