package sc.fidelis.engine.api;

import java.nio.file.Path;

/**
 * Immutable snapshot of an image document's metadata (docs D09 §4 {@code listDocuments}).
 * Pixel data is never exposed through this record (NFR-PERF-004).
 *
 * @param imagePlusId   the IJ1 {@code ImagePlus.getID()} (negative, session-scoped)
 * @param type          IJ1 type name: GRAY8, GRAY16, GRAY32, COLOR_256, COLOR_RGB
 * @param bitDepth      8, 16, 24 (RGB) or 32
 * @param unit          calibration unit ("pixel" when uncalibrated)
 * @param sourcePath    the file the document was opened from, or {@code null} for new/derived images
 * @param sourceSha256  hex digest of the source file, or {@code null}
 * @param changed       the IJ1 {@code changes} flag (unsaved modifications)
 * @param virtualStack  true if the stack is disk-backed (edits cannot be snapshot-undone)
 */
public record DocumentInfo(
        DocumentId id,
        String title,
        int imagePlusId,
        int width,
        int height,
        int nChannels,
        int nSlices,
        int nFrames,
        String type,
        int bitDepth,
        String unit,
        double pixelWidth,
        double pixelHeight,
        Path sourcePath,
        String sourceSha256,
        boolean changed,
        boolean virtualStack) {

    public boolean isStack() { return nSlices > 1 || nFrames > 1 || nChannels > 1; }
}
