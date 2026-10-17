package sc.fidelis.ui.dev;

import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Window;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Menus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.OvalRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.StackWindow;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.process.FloatPolygon;
import ij.process.ImageConverter;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.EngineEvent.DialogPending;
import sc.fidelis.engine.api.EngineEvent.DocumentFocusChanged;
import sc.fidelis.engine.api.EngineEvent.DocumentsChanged;
import sc.fidelis.engine.api.EngineEvent.EngineStateChanged;
import sc.fidelis.engine.api.EngineEvent.HistoryAppended;
import sc.fidelis.engine.api.EngineEvent.LogLine;
import sc.fidelis.engine.api.EngineEvent.OperationStateChanged;
import sc.fidelis.engine.api.EngineEvent.ResultsChanged;
import sc.fidelis.engine.api.EngineEvent.StateDigestEvent;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.ActionKind;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.HostCanvas;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.ui.MenuModel;

/**
 * DEVELOPMENT STUB. Not the Fidelis scientific engine and not part of the product contract.
 *
 * <p>It exists so the Desktop UI can be built, run and tested while the real {@code DefaultEngine}
 * is developed in parallel. It wraps real ImageJ1 objects (no simulated results): {@code open} is
 * {@code IJ.openImage}, commands run through {@code IJ.run(imp, label, options)} on one worker
 * thread, the catalogue comes from the real {@code Menus.getMenuBar()}, and results come from the
 * real {@code ResultsTable}. Its postconditions are a pixel hash only, its undo is a plain pixel
 * snapshot, and parameter schemas are a loose reading of {@code commands-core-parameters.csv}.
 * {@link sc.fidelis.ui.EngineProvider} replaces it as soon as a real engine is registered.
 */
public final class StubEngine implements Engine {
    private static final String STUB_REASON = "development stub (not the Fiji engine)";

    private final CopyOnWriteArrayList<EngineListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fidelis-stub-engine");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong opCounter = new AtomicLong();
    private final AtomicLong docCounter = new AtomicLong();
    private final AtomicLong actionCounter = new AtomicLong();
    private final Runnable quitAction;
    private volatile EngineState state = EngineState.INITIALISING;
    private final Map<DocumentId, Doc> docs = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile DocumentId focused;
    private final List<HistoryRecord> history = new CopyOnWriteArrayList<>();
    private volatile List<CommandSchema.Summary> catalogue = List.of();
    private volatile Map<String, CommandSchema.Summary> catalogueById = Map.of();
    private final StubSchemas schemas = new StubSchemas();
    private volatile List<MeasurementRow> results = List.of();
    private volatile MeasurementRow.Columns columns = new MeasurementRow.Columns(List.of(), 3);
    private final Map<String, AtomicBoolean> cancelled = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Doc {
        final DocumentId id;
        final ImagePlus imp;
        ImageWindow win;
        HostCanvas canvas;
        final List<RoiGeometry> roiList = new ArrayList<>();
        final java.util.ArrayDeque<Snapshot> undo = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<Snapshot> redo = new java.util.ArrayDeque<>();
        String sourcePath;

        Doc(DocumentId id, ImagePlus imp) {
            this.id = id;
            this.imp = imp;
        }
    }

    /** Pixel snapshot of the whole stack, restored in place so the hosted canvas keeps its arrays. */
    private record Snapshot(String label, List<Object> planes) {}

    public StubEngine(Runnable quitAction) {
        this.quitAction = quitAction;
        fire(new EngineStateChanged(seq.incrementAndGet(), EngineState.INITIALISING, STUB_REASON));
        worker.execute(this::buildCatalogue);
    }

    // ---- lifecycle ------------------------------------------------------------------------

    @Override
    public EngineState state() {
        return state;
    }

    @Override
    public void addListener(EngineListener l) {
        listeners.add(l);
    }

    @Override
    public void removeListener(EngineListener l) {
        listeners.remove(l);
    }

    @Override
    public void quit(boolean discardUnsaved) {
        state = EngineState.QUITTING;
        if (quitAction != null) quitAction.run();
    }

    private void setState(EngineState s, String reason) {
        state = s;
        fire(new EngineStateChanged(seq.incrementAndGet(), s, reason));
    }

    private void fire(EngineEvent e) {
        Runnable r = () -> {
            for (EngineListener l : listeners) {
                try {
                    l.onEvent(e);
                } catch (RuntimeException ex) {
                    ex.printStackTrace();
                }
            }
        };
        if (EventQueue.isDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private void log(String line) {
        fire(new LogLine(seq.incrementAndGet(), line));
    }

    private void buildCatalogue() {
        setState(EngineState.BUILDING_CATALOGUE, "reading the Fiji menu table");
        List<CommandSchema.Summary> list = new ArrayList<>();
        Map<String, CommandSchema.Summary> byId = new LinkedHashMap<>();
        try {
            List<MenuModel.Node> roots = MenuModel.read(Menus.getMenuBar());
            for (MenuModel.Node n : MenuModel.leaves(roots)) {
                CommandSchema.Summary s = new CommandSchema.Summary(n.commandId(), n.label(), n.path(),
                        n.shortcutText(), "IJ1", "C1", Map.of());
                if (byId.putIfAbsent(s.commandId(), s) == null) list.add(s);
            }
        } catch (Throwable t) {
            log("catalogue: " + t);
        }
        catalogue = Collections.unmodifiableList(list);
        catalogueById = byId;
        log("Development stub engine in use: " + list.size() + " commands read from the Fiji menu bar");
        setState(EngineState.READY, STUB_REASON);
    }

    // ---- actions --------------------------------------------------------------------------

    @Override
    public CompletableFuture<ActionResult> submit(ActionRequest req) {
        String opId = "op-" + opCounter.incrementAndGet();
        cancelled.put(opId, new AtomicBoolean());
        CompletableFuture<ActionResult> f = new CompletableFuture<>();
        if (state != EngineState.READY) {
            f.complete(failed(opId, ErrorCode.ENGINE_NOT_READY, "The engine is not ready yet (" + state + ")", 0));
            return f;
        }
        fire(new OperationStateChanged(seq.incrementAndGet(), opId, req.requestId(), OperationState.QUEUED, null));
        worker.execute(() -> {
            long t0 = System.nanoTime();
            fire(new OperationStateChanged(seq.incrementAndGet(), opId, req.requestId(), OperationState.RUNNING, null));
            ActionResult r;
            try {
                r = execute(opId, req, t0);
            } catch (Throwable t) {
                r = failed(opId, ErrorCode.EXECUTION_FAILED, String.valueOf(t), ms(t0));
            }
            fire(new OperationStateChanged(seq.incrementAndGet(), opId, req.requestId(), r.state(), r.error()));
            if (r.digest() != null) fire(new StateDigestEvent(seq.incrementAndGet(), opId, r.digest()));
            if (r.record() != null) {
                history.add(r.record());
                fire(new HistoryAppended(seq.incrementAndGet(), r.record()));
            }
            cancelled.remove(opId);
            f.complete(r);
        });
        return f;
    }

    @Override
    public void cancel(String operationId) {
        AtomicBoolean c = cancelled.get(operationId);
        if (c != null) c.set(true);
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static ActionResult failed(String opId, ErrorCode code, String msg, long durationMs) {
        return new ActionResult(opId, OperationState.FAILED, EngineError.of(code, msg), null, null, null, durationMs);
    }

    private ActionResult execute(String opId, ActionRequest req, long t0) throws Exception {
        if (cancelled.get(opId) != null && cancelled.get(opId).get())
            return new ActionResult(opId, OperationState.CANCELLED, EngineError.of(ErrorCode.CANCELLED, "Cancelled"), null, null, null, 0);
        return switch (req.kind()) {
            case COMMAND -> runCommand(opId, req, t0);
            case SESSION -> runSession(opId, req, t0);
            case DIRECT -> runDirect(opId, req, t0);
            case CODE -> failed(opId, ErrorCode.CAPABILITY_CONFIRMATION_REQUIRED, "Macro execution is not available in the stub", 0);
        };
    }

    private Doc docFor(TargetScope target) {
        DocumentId id = target != null ? target.document() : focused;
        return id == null ? null : docs.get(id);
    }

    private ActionResult runCommand(String opId, ActionRequest req, long t0) throws Exception {
        CommandSchema.Summary summary = catalogueById.get(req.commandId());
        if (summary == null) return failed(opId, ErrorCode.UNKNOWN_COMMAND, "Unknown command " + req.commandId(), ms(t0));
        Doc doc = docFor(req.target());
        String label = summary.label();
        ImagePlus imp = doc != null ? doc.imp : null;
        String options = optionString(req.params());

        long beforeHash = imp != null ? pixelHash(imp) : 0;
        Snapshot snap = imp != null && !imp.getStack().isVirtual() ? snapshot(label, imp) : null;
        int rowsBefore = results.size();
        int[] idsBefore = WindowManager.getIDList();
        Map<String, String> globalsBefore = globals();
        Thread watcher = startDialogWatcher(opId, label);
        long t1 = System.nanoTime();
        try {
            if (imp != null) IJ.run(imp, label, options);
            else IJ.run(label, options);
        } finally {
            watcher.interrupt();
        }
        boolean pixelsChanged = imp != null && pixelHash(imp) != beforeHash;
        if (doc != null) {
            if (pixelsChanged && snap != null) {
                doc.undo.push(snap);
                doc.redo.clear();
            }
            SwingUtilities.invokeAndWait(() -> {
                imp.updateAndDraw();
                if (doc.canvas != null) doc.canvas.repaint();
            });
        }
        // Images created by the command (for example Duplicate...) become documents too.
        List<DocumentId> added = adoptNewImages(idsBefore);
        int rowsAdded = refreshResults(rowsBefore);
        Map<String, String> globalsAfter = globals();
        Map<String, String> delta = new LinkedHashMap<>();
        globalsAfter.forEach((k, v) -> {
            if (!v.equals(globalsBefore.get(k))) delta.put(k, globalsBefore.get(k) + "=" + v);
        });
        List<StateDigest.DocumentChange> changed = new ArrayList<>();
        if (doc != null) changed.add(new StateDigest.DocumentChange(doc.id, pixelsChanged, false, false, false, false));
        StateDigest digest = new StateDigest(added, List.of(), changed, rowsAdded, delta, List.of(), List.of(), List.of());
        Map<String, Object> params = new LinkedHashMap<>(req.params());
        HistoryRecord rec = new HistoryRecord("act-" + actionCounter.incrementAndGet(), Instant.now(), req.origin(),
                Provenance.TYPED, req.commandId(), label, params, req.target(),
                List.of("run(\"" + label + "\"" + (options.isEmpty() ? "" : ", \"" + options + "\"") + ");"), true,
                globalsBefore, delta, OperationState.COMPLETED, null, null);
        log("Done: " + label + " in " + (System.nanoTime() - t1) / 1_000_000 + " ms"
                + (imp != null ? (pixelsChanged ? " (pixels changed)" : " (no pixel change)") : ""));
        if (!added.isEmpty() || doc != null) fire(new DocumentsChanged(seq.incrementAndGet()));
        if (!added.isEmpty()) {
            focused = added.get(added.size() - 1);
            fire(new DocumentFocusChanged(seq.incrementAndGet(), focused));
        }
        return new ActionResult(opId, OperationState.COMPLETED, null, digest, rec, null, ms(t0));
    }

    private Thread startDialogWatcher(String opId, String label) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(500);
                while (true) {
                    for (Window w : Window.getWindows()) {
                        if (w instanceof Dialog d && d.isShowing() && d.isModal() && w.getOwner() == null
                                || w instanceof Dialog d2 && d2.isShowing() && d2.isModal()
                                && w.getClass().getName().startsWith("ij.")) {
                            Dialog d3 = (Dialog) w;
                            fire(new DialogPending(seq.incrementAndGet(), opId, d3.getTitle(), w.getClass().getName()));
                            Thread.sleep(Long.MAX_VALUE);
                        }
                    }
                    Thread.sleep(150);
                }
            } catch (InterruptedException ignored) {
                // finished
            }
        }, "fidelis-stub-dialog-watch");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private ActionResult runSession(String opId, ActionRequest req, long t0) throws Exception {
        String op = req.operation();
        switch (op) {
            case Operations.OPEN: {
                String path = String.valueOf(req.params().get("path"));
                ImagePlus imp = IJ.openImage(path);
                if (imp == null) return failed(opId, ErrorCode.IO_ERROR, "Could not open " + path, ms(t0));
                Doc d = register(imp, path);
                return sessionResult(opId, req, t0, List.of(d.id), List.of(), "open(\"" + path.replace("\\", "/") + "\");");
            }
            case Operations.NEW_IMAGE: {
                Map<String, Object> p = req.params();
                String title = String.valueOf(p.getOrDefault("title", "Untitled"));
                String type = String.valueOf(p.getOrDefault("type", "8-bit"));
                String fill = String.valueOf(p.getOrDefault("fill", "Black"));
                int w = ((Number) p.getOrDefault("width", 512)).intValue();
                int h = ((Number) p.getOrDefault("height", 512)).intValue();
                int n = ((Number) p.getOrDefault("slices", 1)).intValue();
                int ch = ((Number) p.getOrDefault("channels", 1)).intValue();
                int fr = ((Number) p.getOrDefault("frames", 1)).intValue();
                ImagePlus imp = ch > 1 || fr > 1
                        ? IJ.createImage(title, type + " " + fill, w, h, ch, n, fr)
                        : IJ.createImage(title, type + " " + fill, w, h, n);
                Doc d = register(imp, null);
                return sessionResult(opId, req, t0, List.of(d.id), List.of(),
                        "newImage(\"" + title + "\", \"" + type + " " + fill + "\", " + w + ", " + h + ", " + n + ");");
            }
            case Operations.CLOSE: {
                Doc d = docFor(req.target());
                if (d == null) return failed(opId, ErrorCode.PRECONDITION_FAILED, "No image is open", ms(t0));
                closeDoc(d);
                return sessionResult(opId, req, t0, List.of(), List.of(d.id), "close();");
            }
            default:
                return failed(opId, ErrorCode.UNSUPPORTED_IN_MODE, op + " is not implemented in the development stub", ms(t0));
        }
    }

    private ActionResult sessionResult(String opId, ActionRequest req, long t0, List<DocumentId> added,
                                       List<DocumentId> removed, String macro) {
        HistoryRecord rec = new HistoryRecord("act-" + actionCounter.incrementAndGet(), Instant.now(), req.origin(),
                Provenance.TYPED, req.operation(), req.operation(), req.params(), req.target(), List.of(macro), true,
                Map.of(), Map.of(), OperationState.COMPLETED, null, null);
        StateDigest dg = new StateDigest(added, removed, List.of(), 0, Map.of(), List.of(), List.of(), List.of());
        fire(new DocumentsChanged(seq.incrementAndGet()));
        if (!added.isEmpty()) {
            focused = added.get(0);
            fire(new DocumentFocusChanged(seq.incrementAndGet(), focused));
        } else if (!removed.isEmpty()) {
            focused = docs.isEmpty() ? null : docs.keySet().iterator().next();
            fire(new DocumentFocusChanged(seq.incrementAndGet(), focused));
        }
        return new ActionResult(opId, OperationState.COMPLETED, null, dg, rec, null, ms(t0));
    }

    private ActionResult runDirect(String opId, ActionRequest req, long t0) throws Exception {
        Doc d = docFor(req.target());
        String op = req.operation();
        if (d == null && !Operations.UNDO.equals(op)) return failed(opId, ErrorCode.PRECONDITION_FAILED, "No image is open", ms(t0));
        List<String> macro = new ArrayList<>();
        boolean roiChanged = false;
        boolean pixels = false;
        switch (op) {
            case Operations.SET_POSITION -> {
                int c = intParam(req, "channel", d.imp.getChannel());
                int z = intParam(req, "slice", d.imp.getSlice());
                int t = intParam(req, "frame", d.imp.getFrame());
                SwingUtilities.invokeAndWait(() -> d.imp.setPosition(c, z, t));
                macro.add("Stack.setPosition(" + c + ", " + z + ", " + t + ");");
            }
            case Operations.CLEAR_ROI -> {
                SwingUtilities.invokeAndWait(d.imp::deleteRoi);
                roiChanged = true;
                macro.add("run(\"Select None\");");
            }
            case Operations.SET_ROI -> {
                RoiGeometry g = (RoiGeometry) req.params().get("roi");
                SwingUtilities.invokeAndWait(() -> d.imp.setRoi(fromGeometry(g)));
                roiChanged = true;
            }
            case Operations.ROI_LIST_ADD -> {
                Roi r = d.imp.getRoi();
                if (r == null) return failed(opId, ErrorCode.PRECONDITION_FAILED, "There is no active selection to add", ms(t0));
                RoiGeometry g = toGeometry(r, "r" + (d.roiList.size() + 1));
                d.roiList.add(g);
                roiChanged = true;
            }
            case Operations.ROI_LIST_SELECT -> {
                int i = intParam(req, "index", -1);
                if (i < 0 || i >= d.roiList.size()) return failed(opId, ErrorCode.VALIDATION_FAILED, "No ROI " + i, ms(t0));
                RoiGeometry g = d.roiList.get(i);
                SwingUtilities.invokeAndWait(() -> d.imp.setRoi(fromGeometry(g)));
                roiChanged = true;
            }
            case Operations.ROI_LIST_DELETE -> {
                int i = intParam(req, "index", -1);
                if (i < 0 || i >= d.roiList.size()) return failed(opId, ErrorCode.VALIDATION_FAILED, "No ROI " + i, ms(t0));
                d.roiList.remove(i);
                roiChanged = true;
            }
            case Operations.ROI_LIST_RENAME -> {
                int i = intParam(req, "index", -1);
                if (i < 0 || i >= d.roiList.size()) return failed(opId, ErrorCode.VALIDATION_FAILED, "No ROI " + i, ms(t0));
                RoiGeometry g = d.roiList.get(i);
                d.roiList.set(i, new RoiGeometry(g.type(), g.xs(), g.ys(), g.bounds(),
                        String.valueOf(req.params().get("name")), g.position(), g.encoded()));
                roiChanged = true;
            }
            case Operations.SET_DISPLAY_RANGE -> {
                double min = ((Number) req.params().get("min")).doubleValue();
                double max = ((Number) req.params().get("max")).doubleValue();
                Object chan = req.params().get("channel"); // UI extension: 1-based channel of a composite
                SwingUtilities.invokeAndWait(() -> {
                    if (chan instanceof Number cn && d.imp.isComposite()) {
                        int prev = d.imp.getChannel();
                        ((ij.CompositeImage) d.imp).setC(cn.intValue());
                        d.imp.setDisplayRange(min, max);
                        ((ij.CompositeImage) d.imp).setC(prev);
                    } else {
                        d.imp.setDisplayRange(min, max);
                    }
                    d.imp.updateAndDraw();
                });
                macro.add("setMinAndMax(" + min + ", " + max + ");");
            }
            case Operations.SET_THRESHOLD -> {
                double lo = ((Number) req.params().get("lower")).doubleValue();
                double hi = ((Number) req.params().get("upper")).doubleValue();
                SwingUtilities.invokeAndWait(() -> {
                    d.imp.getProcessor().setThreshold(lo, hi, ij.process.ImageProcessor.RED_LUT);
                    d.imp.updateAndDraw();
                });
                macro.add("setThreshold(" + lo + ", " + hi + ");");
            }
            case Operations.SET_AUTO_THRESHOLD -> {
                String method = String.valueOf(req.params().getOrDefault("method", "Default"));
                boolean dark = Boolean.TRUE.equals(req.params().get("dark"));
                SwingUtilities.invokeAndWait(() -> {
                    d.imp.getProcessor().setAutoThreshold(method + (dark ? " dark" : ""));
                    d.imp.updateAndDraw();
                });
                macro.add("setAutoThreshold(\"" + method + (dark ? " dark" : "") + "\");");
            }
            case Operations.RESET_THRESHOLD -> {
                SwingUtilities.invokeAndWait(() -> {
                    d.imp.getProcessor().resetThreshold();
                    d.imp.updateAndDraw();
                });
                macro.add("resetThreshold();");
            }
            case Operations.UNDO, Operations.REDO -> {
                if (d == null) return failed(opId, ErrorCode.PRECONDITION_FAILED, "No image is open", ms(t0));
                boolean undo = Operations.UNDO.equals(op);
                var from = undo ? d.undo : d.redo;
                var to = undo ? d.redo : d.undo;
                if (from.isEmpty()) return failed(opId, ErrorCode.PRECONDITION_FAILED, "Nothing to " + op.toLowerCase(Locale.ROOT), ms(t0));
                Snapshot s = from.pop();
                to.push(snapshot(s.label(), d.imp));
                restore(d.imp, s);
                SwingUtilities.invokeAndWait(() -> {
                    d.imp.updateAndDraw();
                    if (d.canvas != null) d.canvas.repaint();
                });
                pixels = true;
            }
            default -> {
                return failed(opId, ErrorCode.UNSUPPORTED_IN_MODE, op + " is not implemented in the development stub", ms(t0));
            }
        }
        HistoryRecord rec = new HistoryRecord("act-" + actionCounter.incrementAndGet(), Instant.now(), req.origin(),
                Provenance.TYPED, op, op, req.params(), req.target(), macro, !macro.isEmpty(), Map.of(), Map.of(),
                OperationState.COMPLETED, null, null);
        List<StateDigest.DocumentChange> ch = d == null ? List.of()
                : List.of(new StateDigest.DocumentChange(d.id, pixels, false, false, roiChanged, false));
        fire(new DocumentsChanged(seq.incrementAndGet()));
        return new ActionResult(opId, OperationState.COMPLETED, null,
                new StateDigest(List.of(), List.of(), ch, 0, Map.of(), List.of(), List.of(), List.of()), rec, null, ms(t0));
    }

    private static int intParam(ActionRequest req, String key, int dflt) {
        Object v = req.params().get(key);
        return v instanceof Number n ? n.intValue() : dflt;
    }

    private static String optionString(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof Boolean b) {
                if (b) sb.append(e.getKey()).append(' ');
                continue;
            }
            String s = v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString(d.longValue()) : String.valueOf(v);
            if (s.isEmpty()) continue;
            sb.append(e.getKey()).append('=').append(s.indexOf(' ') >= 0 ? "[" + s + "]" : s).append(' ');
        }
        return sb.toString().trim();
    }

    // ---- documents ------------------------------------------------------------------------

    private Doc register(ImagePlus imp, String sourcePath) throws Exception {
        DocumentId id = new DocumentId("doc-" + docCounter.incrementAndGet());
        Doc d = new Doc(id, imp);
        d.sourcePath = sourcePath;
        SwingUtilities.invokeAndWait(() -> {
            d.canvas = new HostCanvas(imp);
            d.win = imp.getStackSize() > 1 ? new StackWindow(imp, d.canvas) : new ImageWindow(imp, d.canvas);
            d.win.setVisible(false);
            d.win.setLayout(null); // EXP-01 H2 trap (b): keeps unzoom() from collapsing the canvas
        });
        docs.put(id, d);
        focused = id;
        return d;
    }

    /** Wrap images that a command created (they already have a visible window) as hidden-window documents. */
    private List<DocumentId> adoptNewImages(int[] idsBefore) throws Exception {
        List<DocumentId> added = new ArrayList<>();
        int[] now = WindowManager.getIDList();
        if (now == null) return added;
        java.util.Set<Integer> before = new java.util.HashSet<>();
        if (idsBefore != null) for (int i : idsBefore) before.add(i);
        for (int i : now) {
            if (before.contains(i)) continue;
            ImagePlus imp = WindowManager.getImage(i);
            if (imp == null || known(imp)) continue;
            DocumentId id = new DocumentId("doc-" + docCounter.incrementAndGet());
            Doc d = new Doc(id, imp);
            d.win = imp.getWindow();
            if (d.win != null) {
                d.canvas = null; // a plain IJ1 canvas: fine for hosting, the native zoom tool is not host-safe here
                SwingUtilities.invokeAndWait(() -> {
                    d.win.setVisible(false);
                    d.win.setLayout(null);
                });
            }
            docs.put(id, d);
            added.add(id);
        }
        return added;
    }

    private boolean known(ImagePlus imp) {
        synchronized (docs) {
            return docs.values().stream().anyMatch(d -> d.imp == imp);
        }
    }

    private void closeDoc(Doc d) throws Exception {
        docs.remove(d.id);
        d.roiList.clear();
        SwingUtilities.invokeAndWait(() -> {
            d.imp.changes = false;
            if (d.win != null) d.win.setVisible(false);
            d.imp.close();
        });
    }

    private DocumentInfo info(Doc d) {
        ImagePlus imp = d.imp;
        String type = switch (imp.getType()) {
            case ImagePlus.GRAY8 -> "GRAY8";
            case ImagePlus.GRAY16 -> "GRAY16";
            case ImagePlus.GRAY32 -> "GRAY32";
            case ImagePlus.COLOR_256 -> "COLOR_256";
            default -> "COLOR_RGB";
        };
        var cal = imp.getCalibration();
        return new DocumentInfo(d.id, imp.getTitle(), imp.getID(), imp.getWidth(), imp.getHeight(),
                imp.getNChannels(), imp.getNSlices(), imp.getNFrames(), type, imp.getBitDepth(),
                cal.getUnit(), cal.pixelWidth, cal.pixelHeight,
                d.sourcePath == null ? null : Path.of(d.sourcePath), null, imp.changes, imp.getStack().isVirtual());
    }

    @Override
    public List<DocumentInfo> documents() {
        synchronized (docs) {
            List<DocumentInfo> out = new ArrayList<>();
            for (Doc d : docs.values()) out.add(info(d));
            return out;
        }
    }

    @Override
    public Optional<DocumentInfo> document(DocumentId id) {
        Doc d = docs.get(id);
        return d == null ? Optional.empty() : Optional.of(info(d));
    }

    @Override
    public CompletableFuture<ActionResult> open(Path file) {
        return submit(ActionRequest.session(Origin.MENU, Operations.OPEN, Map.of("path", file.toString()), null));
    }

    @Override
    public Optional<DocumentId> focused() {
        return Optional.ofNullable(focused);
    }

    @Override
    public void focus(DocumentId id) {
        Doc d = docs.get(id);
        if (d == null) return;
        boolean changed = !id.equals(focused);
        focused = id;
        if (d.win != null) WindowManager.setCurrentWindow(d.win);
        if (changed) fire(new DocumentFocusChanged(seq.incrementAndGet(), id));
    }

    @Override
    public ImagePlus imagePlus(DocumentId id) {
        Doc d = docs.get(id);
        return d == null ? null : d.imp;
    }

    @Override
    public ImageWindow hostWindow(DocumentId id) {
        Doc d = docs.get(id);
        return d == null ? null : d.win;
    }

    // ---- ROIs and results -----------------------------------------------------------------

    @Override
    public List<RoiGeometry> rois(DocumentId id) {
        Doc d = docs.get(id);
        return d == null ? List.of() : List.copyOf(d.roiList);
    }

    @Override
    public Optional<RoiGeometry> activeRoi(DocumentId id) {
        Doc d = docs.get(id);
        Roi r = d == null ? null : d.imp.getRoi();
        return r == null ? Optional.empty() : Optional.of(toGeometry(r, r.getName()));
    }

    static RoiGeometry toGeometry(Roi r, String name) {
        int t = r.getType();
        String type = switch (t) {
            case Roi.RECTANGLE -> "RECTANGLE";
            case Roi.OVAL -> "OVAL";
            case Roi.POLYGON -> "POLYGON";
            case Roi.FREEROI -> "FREEHAND";
            case Roi.TRACED_ROI -> "TRACED";
            case Roi.LINE -> "LINE";
            case Roi.POLYLINE -> "POLYLINE";
            case Roi.FREELINE -> "FREELINE";
            case Roi.ANGLE -> "ANGLE";
            case Roi.POINT -> "POINT";
            default -> "COMPOSITE";
        };
        FloatPolygon fp = r.getFloatPolygon();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        if (fp != null) {
            for (int i = 0; i < fp.npoints; i++) {
                xs.add(fp.xpoints[i]);
                ys.add(fp.ypoints[i]);
            }
        }
        java.awt.geom.Rectangle2D b = r.getFloatBounds();
        double[] bounds = t == Roi.RECTANGLE || t == Roi.OVAL ? new double[] {b.getX(), b.getY(), b.getWidth(), b.getHeight()} : null;
        return new RoiGeometry(type, xs, ys, bounds, name != null ? name : r.getName(), null, null);
    }

    static Roi fromGeometry(RoiGeometry g) {
        if (g.bounds() != null) {
            double[] b = g.bounds();
            return "OVAL".equals(g.type()) ? new OvalRoi(b[0], b[1], b[2], b[3]) : new Roi(b[0], b[1], b[2], b[3]);
        }
        float[] xs = new float[g.xs().size()];
        float[] ys = new float[xs.length];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = g.xs().get(i);
            ys[i] = g.ys().get(i);
        }
        int type = switch (g.type()) {
            case "FREEHAND" -> Roi.FREEROI;
            case "TRACED" -> Roi.TRACED_ROI;
            case "POLYLINE" -> Roi.POLYLINE;
            case "FREELINE" -> Roi.FREELINE;
            case "POINT" -> Roi.POINT;
            default -> Roi.POLYGON;
        };
        return new PolygonRoi(xs, ys, type);
    }

    @Override
    public List<MeasurementRow> results() {
        return results;
    }

    @Override
    public MeasurementRow.Columns resultColumns() {
        return columns;
    }

    private int refreshResults(int rowsBefore) {
        ResultsTable rt = ResultsTable.getResultsTable();
        List<MeasurementRow> rows = new ArrayList<>();
        String[] heads = rt.getHeadings();
        List<String> numeric = new ArrayList<>();
        for (String h : heads) if (!"Label".equals(h) && rt.columnExists(rt.getColumnIndex(h))) numeric.add(h);
        for (int i = 0; i < rt.size(); i++) {
            Map<String, Double> vals = new LinkedHashMap<>();
            for (String h : numeric) vals.put(h, rt.getValueAsDouble(rt.getColumnIndex(h), i));
            rows.add(new MeasurementRow(i, rt.getLabel(i), vals, focused, null));
        }
        results = Collections.unmodifiableList(rows);
        columns = new MeasurementRow.Columns(numeric, Analyzer.getPrecision());
        int added = Math.max(0, rows.size() - rowsBefore);
        if (rows.size() != rowsBefore) fire(new ResultsChanged(seq.incrementAndGet(), rows.size()));
        return added;
    }

    // ---- catalogue ------------------------------------------------------------------------

    @Override
    public List<CommandSchema.Summary> catalogue() {
        return catalogue;
    }

    @Override
    public Optional<CommandSchema> schema(String commandId) {
        CommandSchema.Summary s = catalogueById.get(commandId);
        if (s == null) return Optional.empty();
        return Optional.of(schemas.schemaFor(s));
    }

    @Override
    public List<CommandSchema.Summary> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();
        String[] tokens = q.split("\\s+");
        record Scored(CommandSchema.Summary s, int score) {}
        List<Scored> hits = new ArrayList<>();
        for (CommandSchema.Summary s : catalogue) {
            String label = s.label().toLowerCase(Locale.ROOT);
            String path = (s.menuPath() + ">" + s.label()).toLowerCase(Locale.ROOT);
            int score;
            if (label.equals(q) || label.equals(q + "...")) score = 0;
            else if (label.startsWith(q)) score = 1;
            else if (label.contains(q)) score = 2;
            else if (path.contains(q)) score = 3;
            else if (Arrays.stream(tokens).allMatch(path::contains)) score = 4;
            else continue;
            hits.add(new Scored(s, score));
        }
        hits.sort(Comparator.<Scored>comparingInt(Scored::score).thenComparingInt(h -> h.s().label().length())
                .thenComparing(h -> h.s().label()));
        List<CommandSchema.Summary> out = new ArrayList<>();
        for (Scored h : hits) {
            if (out.size() >= limit) break;
            out.add(h.s());
        }
        return out;
    }

    // ---- history and globals ----------------------------------------------------------------

    @Override
    public List<HistoryRecord> history() {
        return List.copyOf(history);
    }

    @Override
    public String exportMacro() {
        StringBuilder sb = new StringBuilder("// Development stub macro: generated from the stub's own history, not the D07 generator\n");
        for (HistoryRecord r : history) {
            if (r.outcome() != OperationState.COMPLETED) continue;
            for (String l : r.macroLines()) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    @Override
    public Map<String, String> globals() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("Prefs.blackBackground", String.valueOf(Prefs.blackBackground));
        g.put("Measurements", Integer.toHexString(Analyzer.getMeasurements()));
        g.put("Decimal places", String.valueOf(Analyzer.getPrecision()));
        g.put("Scale when converting", String.valueOf(ImageConverter.getDoScaling()));
        return g;
    }

    @Override
    public long[] undoBudget() {
        long used = 0;
        synchronized (docs) {
            for (Doc d : docs.values()) {
                for (Snapshot s : d.undo) used += size(s);
                for (Snapshot s : d.redo) used += size(s);
            }
        }
        return new long[] {used, Runtime.getRuntime().maxMemory() / 4};
    }

    private static long size(Snapshot s) {
        long n = 0;
        for (Object o : s.planes()) {
            if (o instanceof byte[] b) n += b.length;
            else if (o instanceof short[] b) n += 2L * b.length;
            else if (o instanceof float[] b) n += 4L * b.length;
            else if (o instanceof int[] b) n += 4L * b.length;
        }
        return n;
    }

    // ---- pixel helpers ----------------------------------------------------------------------

    private static long pixelHash(ImagePlus imp) {
        ImageStack st = imp.getStack();
        if (st.isVirtual()) return 0;
        long h = 1;
        for (int i = 1; i <= st.getSize(); i++) {
            Object p = st.getPixels(i);
            h = 31 * h + (p instanceof byte[] b ? Arrays.hashCode(b) : p instanceof short[] s ? Arrays.hashCode(s)
                    : p instanceof float[] f ? Arrays.hashCode(f) : p instanceof int[] n ? Arrays.hashCode(n) : 0);
        }
        return h;
    }

    private static Snapshot snapshot(String label, ImagePlus imp) {
        ImageStack st = imp.getStack();
        List<Object> planes = new ArrayList<>();
        for (int i = 1; i <= st.getSize(); i++) {
            Object p = st.getPixels(i);
            planes.add(p instanceof byte[] b ? b.clone() : p instanceof short[] s ? s.clone()
                    : p instanceof float[] f ? f.clone() : p instanceof int[] n ? n.clone() : null);
        }
        return new Snapshot(label, planes);
    }

    private static void restore(ImagePlus imp, Snapshot s) {
        ImageStack st = imp.getStack();
        for (int i = 1; i <= st.getSize() && i <= s.planes().size(); i++) {
            Object dst = st.getPixels(i);
            Object src = s.planes().get(i - 1);
            if (dst != null && src != null && dst.getClass() == src.getClass())
                System.arraycopy(src, 0, dst, 0, java.lang.reflect.Array.getLength(src));
        }
    }
}
