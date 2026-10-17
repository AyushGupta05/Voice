package sc.fidelis.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import ij.CommandListener;
import ij.Executer;
import ij.ImageListener;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.gui.Roi;
import ij.gui.RoiListener;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.ActionResult.StateDigest.DocumentChange;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/**
 * Observation of native interactions (E4): commands IJ1 runs for the user ({@link CommandListener}),
 * selections drawn on the hosted canvas ({@link RoiListener}) and image changes ({@link ImageListener}).
 * They are recorded with provenance NATIVE; engine-initiated actions are recognised through
 * {@link Op#CURRENT} and not recorded twice.
 */
final class Observers {
    private static final long COALESCE_MS = 250;

    private final DefaultEngine eng;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fidelis-observer");
        t.setDaemon(true);
        return t;
    });
    private final Map<ImagePlus, ScheduledFuture<?>> pendingRoi = new IdentityHashMap<>();
    private final Map<ImagePlus, ScheduledFuture<?>> pendingUpdate = new IdentityHashMap<>();
    private final Map<ImagePlus, String> lastRoi = new IdentityHashMap<>();

    Observers(DefaultEngine eng) {
        this.eng = eng;
    }

    void install() {
        Executer.addCommandListener(new CommandListener() {
            @Override
            public String commandExecuting(String command) {
                try {
                    onCommand(command);
                } catch (Throwable t) {
                    System.err.println("[fidelis-engine] command observer: " + t);
                }
                return command;
            }
        });
        Roi.addRoiListener(new RoiListener() {
            @Override
            public void roiModified(ImagePlus imp, int id) {
                try {
                    onRoi(imp, id);
                } catch (Throwable t) {
                    System.err.println("[fidelis-engine] roi observer: " + t);
                }
            }
        });
        ImagePlus.addImageListener(new ImageListener() {
            @Override
            public void imageOpened(ImagePlus imp) {
                eng.scheduleReconcile();
            }

            @Override
            public void imageClosed(ImagePlus imp) {
                // IJ1 also notifies CLOSED when it swaps a window for the hosted one, so reconcile decides
                eng.scheduleReconcile();
            }

            @Override
            public void imageUpdated(ImagePlus imp) {
                onUpdated(imp);
            }
        });
    }

    // ---- commands -------------------------------------------------------------------------------------

    private void onCommand(String label) {
        Op op = Op.CURRENT.get();
        String options = Macro.getOptions();
        ImagePlus cur = WindowManager.getCurrentImage();
        Doc doc = cur == null ? null : eng.registry.byImp(cur);
        if (op != null) {
            if (op.request.kind() != sc.fidelis.engine.api.Enums.ActionKind.CODE || op.actionId == null) return; // typed: recorded by its runner
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("parentActionId", op.actionId);
            if (options != null) params.put("options", options.strip());
            eng.recordObserved(History.record(eng.history.nextId(), Origin.MACRO, Provenance.NATIVE, commandId(label), label, params,
                    doc == null ? null : TargetScope.of(doc.id), List.of(), false, Globals.capture(), Map.of(),
                    OperationState.COMPLETED, null));
            return;
        }
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        if (options != null) params.put("options", options.strip());
        List<String> lines = List.of(options != null ? Options.runLine(label, options.strip()) : Options.runLine(label, null));
        HistoryRecord rec = History.record(eng.history.nextId(), Origin.OBSERVED, Provenance.NATIVE, commandId(label), label, params,
                doc == null ? null : TargetScope.of(doc.id), lines, options != null, Globals.capture(), Map.of(),
                OperationState.COMPLETED, null);
        eng.recordObserved(rec);
        // results rows, new images and so on appear after the command finishes
        timer.schedule(() -> eng.afterNative(rec.actionId(), doc), 600, TimeUnit.MILLISECONDS);
    }

    private String commandId(String label) {
        for (Catalogue.Entry e : eng.cat().entries()) if (e.label().equals(label)) return e.schema.commandId();
        return null;
    }

    // ---- selections -----------------------------------------------------------------------------------

    private void onRoi(ImagePlus imp, int id) {
        if (Op.CURRENT.get() != null) return; // engine-initiated
        if (id != RoiListener.COMPLETED && id != RoiListener.MODIFIED && id != RoiListener.MOVED && id != RoiListener.EXTENDED
                && id != RoiListener.DELETED) return;
        Doc doc = eng.registry.byImp(imp);
        if (doc == null) return;
        synchronized (pendingRoi) {
            ScheduledFuture<?> prev = pendingRoi.remove(imp);
            if (prev != null) prev.cancel(false);
            pendingRoi.put(imp, timer.schedule(() -> settleRoi(doc, id == RoiListener.DELETED), COALESCE_MS, TimeUnit.MILLISECONDS));
        }
    }

    private void settleRoi(Doc doc, boolean deleted) {
        synchronized (pendingRoi) {
            pendingRoi.remove(doc.imp);
        }
        Roi roi = doc.imp.getRoi();
        List<String> lines;
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        String opName;
        boolean exact = true;
        String sig;
        if (roi == null) {
            if (!deleted && lastRoi.get(doc.imp) == null) return;
            sig = "";
            opName = sc.fidelis.engine.api.ActionRequest.Operations.CLEAR_ROI;
            lines = List.of("run(\"Select None\");");
        } else {
            RoiGeometry g = RoiCodec.toGeometry(roi);
            sig = g.type() + g.xs() + g.ys() + (g.bounds() == null ? "" : java.util.Arrays.toString(g.bounds()));
            opName = sc.fidelis.engine.api.ActionRequest.Operations.SET_ROI;
            params.put("roi", g);
            lines = RoiCodec.macroLines(g);
            exact = !lines.isEmpty();
        }
        if (sig.equals(lastRoi.getOrDefault(doc.imp, ""))) return;
        lastRoi.put(doc.imp, sig);
        eng.recordObserved(History.record(eng.history.nextId(), Origin.CANVAS, Provenance.NATIVE, opName, "ROI", params,
                TargetScope.of(doc.id), lines, exact, Globals.capture(), Map.of(), OperationState.COMPLETED, null));
        StateDigest digest = new StateDigest(List.of(), List.of(), List.of(new DocumentChange(doc.id, false, false, false, true, false)),
                0, Map.of(), List.of(), List.of(), List.of());
        eng.events.emit(seq -> new EngineEvent.StateDigestEvent(seq, null, digest));
    }

    /** Remember the ROI state a typed action left, so the observer does not record it again as native. */
    void noteRoi(Doc doc) {
        Roi roi = doc.imp.getRoi();
        if (roi == null) {
            lastRoi.remove(doc.imp);
        } else {
            RoiGeometry g = RoiCodec.toGeometry(roi);
            lastRoi.put(doc.imp, g.type() + g.xs() + g.ys() + (g.bounds() == null ? "" : java.util.Arrays.toString(g.bounds())));
        }
    }

    // ---- image updates --------------------------------------------------------------------------------

    private void onUpdated(ImagePlus imp) {
        if (Op.CURRENT.get() != null) return;
        Doc doc = eng.registry.byImp(imp);
        if (doc == null) return;
        synchronized (pendingUpdate) {
            if (pendingUpdate.containsKey(imp)) return; // coalesced
            pendingUpdate.put(imp, timer.schedule(() -> {
                synchronized (pendingUpdate) {
                    pendingUpdate.remove(imp);
                }
                StateDigest digest = new StateDigest(List.of(), List.of(),
                        List.of(new DocumentChange(doc.id, false, false, true, false, false)), 0, Map.of(), List.of(), List.of(), List.of());
                eng.events.emit(seq -> new EngineEvent.StateDigestEvent(seq, null, digest));
            }, COALESCE_MS, TimeUnit.MILLISECONDS));
        }
    }

    List<ImagePlus> pendingImages() {
        synchronized (pendingRoi) {
            return new ArrayList<>(pendingRoi.keySet());
        }
    }
}
