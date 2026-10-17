package sc.fidelis.engine.api;

import java.util.Objects;

/**
 * Explicit target of an action (docs D06 §2; FR-IMG-002). The engine never relies on IJ1's
 * implicit current image for typed actions.
 *
 * @param document  the document to act on
 * @param channel   1-based channel, or {@code null} for the document's current position
 * @param slice     1-based slice (Z), or {@code null}
 * @param frame     1-based frame (T), or {@code null}
 * @param allPlanes true to process the whole stack ("stack" option in IJ1 dialogs)
 * @param roiIndex  index into the document's ROI list, {@code -1} for the active selection, or
 *                  {@code null} when the action does not involve a ROI
 */
public record TargetScope(DocumentId document, Integer channel, Integer slice, Integer frame,
                          boolean allPlanes, Integer roiIndex) {
    public TargetScope {
        Objects.requireNonNull(document, "document");
    }

    /** Target the document at its current position, no explicit ROI. */
    public static TargetScope of(DocumentId document) {
        return new TargetScope(document, null, null, null, false, null);
    }

    public static TargetScope wholeStack(DocumentId document) {
        return new TargetScope(document, null, null, null, true, null);
    }

    public TargetScope withRoi(int roiIndex) {
        return new TargetScope(document, channel, slice, frame, allPlanes, roiIndex);
    }
}
