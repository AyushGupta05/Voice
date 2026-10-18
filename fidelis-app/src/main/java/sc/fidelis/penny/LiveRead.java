package sc.fidelis.penny;

import java.lang.reflect.Field;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

/**
 * Reads of a document's live IJ1 image that change nothing. {@code ImagePlus.getProcessor()} sets the processor's ROI and
 * calibration table and {@code getStack()} updates the stack, so a bridge thread must not call them while the engine (or
 * a plugin) may be using the image. These reads take the {@code ip} and {@code stack} fields directly (the engine's Guard
 * reads IJ1 internals the same way) and only call plain getters ({@code getPixels}, {@code getMinThreshold}).
 */
final class LiveRead {
    private LiveRead() {}

    private static final Field IP = field("ip");
    private static final Field STACK = field("stack");

    private static Field field(String name) {
        try {
            Field f = ImagePlus.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[fidelis-penny] ImagePlus." + name + " not readable: " + e);
            return null;
        }
    }

    /** The live processor without ImagePlus.getProcessor()'s side effects, or null. */
    static ImageProcessor processor(ImagePlus imp) {
        if (imp == null || IP == null) return null;
        try {
            return (ImageProcessor) IP.get(imp);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** The slice label of one channel's plane (at the current slice and frame) without ImagePlus.getStack(); null when none. */
    static String sliceLabel(ImagePlus imp, int channel) {
        if (imp == null || STACK == null || imp.getStackSize() <= 1) return null;
        ImageStack st;
        try {
            st = (ImageStack) STACK.get(imp);
        } catch (IllegalAccessException e) {
            return null;
        }
        if (st == null) return null;
        int index = imp.getStackIndex(channel, imp.getZ(), imp.getT());
        return index >= 1 && index <= st.size() ? st.getSliceLabel(index) : null;
    }

    /** Pixels of one plane: the given channel (null = current) at the current slice and frame; null when unavailable. */
    static Object planePixels(ImagePlus imp, Integer channel) {
        return planePixels(imp, channel, null, null);
    }

    /** Pixels of the plane (c, z, t) (each null = the current position); null when unavailable. */
    static Object planePixels(ImagePlus imp, Integer channel, Integer slice, Integer frame) {
        if (imp == null) return null;
        if (imp.getStackSize() > 1) {
            if (STACK == null) return null;
            ImageStack st;
            try {
                st = (ImageStack) STACK.get(imp);
            } catch (IllegalAccessException e) {
                return null;
            }
            if (st == null) return null;
            int c = channel == null ? imp.getC() : channel;
            int z = slice == null ? imp.getZ() : slice;
            int t = frame == null ? imp.getT() : frame;
            int index = imp.getStackIndex(c, z, t);
            return index >= 1 && index <= st.size() ? st.getPixels(index) : null;
        }
        ImageProcessor ip = processor(imp);
        return ip == null ? null : ip.getPixels();
    }
}
