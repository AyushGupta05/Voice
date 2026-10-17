package sc.fidelis.engine.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import ij.ImagePlus;
import ij.gui.ImageWindow;
import sc.fidelis.engine.api.Enums.EngineState;

/**
 * The Fidelis scientific engine facade (docs D09 IF-01/IF-02/IF-03; component CMP-20).
 *
 * <p>Threading: all methods are safe to call from any thread. Mutating actions are executed in
 * submission order on the engine's single worker thread (never the AWT EDT); the returned future
 * completes when the operation reaches a terminal state (docs D06 §6). Queries answer from committed
 * state. {@link #imagePlus(DocumentId)} hands the UI the live IJ1 object only so that it can host
 * the real {@code ij.gui.ImageCanvas} (architecture-gate hybrid B / EXP-01 S3); UI code must not
 * mutate pixels through it.
 */
public interface Engine {

    // ---- lifecycle --------------------------------------------------------------------------

    EngineState state();

    void addListener(EngineListener listener);

    void removeListener(EngineListener listener);

    /**
     * Orderly shutdown (docs D03 §6 as revised by EXP-04): closes documents per {@code discardUnsaved},
     * flushes the history journal, stops workers, then terminates the JVM through the path EXP-04
     * validated. Returns only if termination is refused (unsaved changes and {@code discardUnsaved}
     * false).
     */
    void quit(boolean discardUnsaved);

    // ---- actions (IF-01) --------------------------------------------------------------------

    /** Queue an action; see {@link ActionRequest}. */
    CompletableFuture<ActionResult> submit(ActionRequest request);

    /** Cooperative cancellation of a running or queued operation. */
    void cancel(String operationId);

    // ---- documents --------------------------------------------------------------------------

    List<DocumentInfo> documents();

    Optional<DocumentInfo> document(DocumentId id);

    /** Convenience for {@link ActionRequest.Operations#OPEN}. */
    CompletableFuture<ActionResult> open(Path file);

    /** The focused document (the one native tools and NL deixis act on), if any. */
    Optional<DocumentId> focused();

    /**
     * Make {@code id} the focused document. Under the hybrid design the IJ1 current image follows
     * this call ({@code WindowManager.setCurrentWindow}); it is not a history action.
     */
    void focus(DocumentId id);

    /** Live IJ1 image for canvas hosting only. */
    ImagePlus imagePlus(DocumentId id);

    /** The hidden host {@code ImageWindow} that keeps the IJ1 canvas functional (EXP-01 S3). */
    ImageWindow hostWindow(DocumentId id);

    // ---- ROIs and results -------------------------------------------------------------------

    /** The document's ROI list (engine-owned; mirrored into the IJ1 ROI Manager singleton around actions). */
    List<RoiGeometry> rois(DocumentId id);

    /** The active selection of a document, if any. */
    Optional<RoiGeometry> activeRoi(DocumentId id);

    /** All measurement rows captured so far (full precision), newest last. */
    List<MeasurementRow> results();

    MeasurementRow.Columns resultColumns();

    // ---- catalogue --------------------------------------------------------------------------

    /** Every command known to the runtime (menus, search, NL grounding). */
    List<CommandSchema.Summary> catalogue();

    Optional<CommandSchema> schema(String commandId);

    /** Relevance-ranked search over labels, menu paths and synonyms. */
    List<CommandSchema.Summary> search(String query, int limit);

    // ---- history and globals ----------------------------------------------------------------

    List<HistoryRecord> history();

    /** Generate a classic-Fiji macro for the whole session (docs D07 §4 rules). */
    String exportMacro();

    /** Result-affecting global settings (docs A11 §6) as name → current value. */
    Map<String, String> globals();

    /** Snapshot-undo budget in bytes: used and total (NFR-PERF-003). */
    long[] undoBudget();
}
