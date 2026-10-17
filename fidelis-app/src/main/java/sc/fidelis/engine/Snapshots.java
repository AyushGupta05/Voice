package sc.fidelis.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import sc.fidelis.engine.api.ActionRequest;

/**
 * Snapshot undo (E5): before reversible data actions on non-virtual images the whole stack is
 * duplicated together with display range, calibration and position, within a memory budget (default
 * 25% of the maximum heap). Oldest snapshots are evicted first, across documents.
 */
final class Snapshots {
    /** State of an image before one action. */
    static final class Snapshot {
        final String actionId;
        final ActionRequest request;
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
        final long bytes;
        final Doc doc;

        Snapshot(Doc doc, String actionId, ActionRequest request, ImagePlus imp, long bytes) {
            this.doc = doc;
            this.actionId = actionId;
            this.request = request;
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
            this.bytes = bytes;
        }
    }

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

    /** Take a snapshot and push it on the document undo stack; null when virtual or over budget. */
    Snapshot take(Doc d, String actionId, ActionRequest request, boolean clearRedo) {
        ImagePlus imp = d.imp;
        if (imp.getStackSize() > 0 && imp.getStack().isVirtual()) return null;
        long bytes = sizeOf(imp);
        if (bytes > budget) return null;
        synchronized (this) {
            while (used + bytes > budget && !order.isEmpty()) evictOldest();
        }
        Snapshot s = new Snapshot(d, actionId, request, imp, bytes);
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

    /** Restore the image to the snapshot state. */
    static void restore(Snapshot s) {
        ImagePlus imp = s.doc.imp;
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
}
