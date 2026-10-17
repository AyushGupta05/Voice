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

    /** Pixels of one plane: the given channel (null = current) at the current slice and frame; null when unavailable. */
    static Object planePixels(ImagePlus imp, Integer channel) {
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
            int index = imp.getStackIndex(c, imp.getZ(), imp.getT());
            return index >= 1 && index <= st.size() ? st.getPixels(index) : null;
        }
        ImageProcessor ip = processor(imp);
        return ip == null ? null : ip.getPixels();
    }
}
