package sc.fidelis.engine.api;

import java.awt.Dimension;

import ij.ImagePlus;
import ij.gui.ImageCanvas;

/**
 * The real ImageJ1 canvas, made hostable (architecture-gate spike EXP-01, strategy S3).
 *
 * <p>IJ1's {@code ImageCanvas} is kept inside a hidden {@code ImageWindow} (which keeps
 * {@code imp.getCanvas()}, {@code WindowManager} registration and the native ROI tools working) and
 * is re-parented into the workspace. Overriding {@code canEnlarge} to return {@code null} stops
 * zoom operations from re-packing the hidden window. The host must drive the viewport itself:
 * {@code setDrawingSize}, then {@code setSourceRect}, then {@code setMagnification} (that order),
 * set the bounds, and forward mouse-wheel events to the hidden window.
 *
 * <p>Lives in the contract package because the engine creates it (per document) and the UI hosts it.
 */
public class HostCanvas extends ImageCanvas {
    /**
     * {@link ImagePlus#setProperty ImagePlus property} a host sets on helper images it registers with IJ1
     * (a before/after copy in its own hidden window, for example): the engine never adopts such an image
     * as a document.
     */
    public static final String TRANSIENT_PROPERTY = "fidelis.transient";

    public HostCanvas(ImagePlus imp) {
        super(imp);
    }

    @Override
    protected Dimension canEnlarge(int newWidth, int newHeight) {
        return null;
    }
}
