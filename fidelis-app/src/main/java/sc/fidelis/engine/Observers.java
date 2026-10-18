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
    private final Map<ImagePlus, String> lastRoi = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** "lo,hi" of the last threshold accounted for per image ("" = none); see {@link #settleThreshold}. */
    private final Map<ImagePlus, String> lastThreshold = java.util.Collections.synchronizedMap(new IdentityHashMap<>());

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
                eng.registry.noteOpened(imp); // show() has stored its window: the image can be re-hosted safely
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
        timer.scheduleWithFixedDelay(this::flushLog, LOG_POLL_MS, LOG_POLL_MS, TimeUnit.MILLISECONDS);
        // a macro interpreter IJ1 still names after it finished (Fiji's start-up leaves one) would make every show()
        // wait 2 s for an activation hidden windows never get; see MacroState
        timer.scheduleWithFixedDelay(() -> {
            try {
                if (MacroState.clearStale()) System.err.println("[fidelis-engine] cleared a stale IJ1 macro interpreter (no macro was running)");
            } catch (Throwable t) {
                System.err.println("[fidelis-engine] macro state: " + t);
            }
        }, LOG_POLL_MS, LOG_POLL_MS, TimeUnit.MILLISECONDS);
        try {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
                try {
                    if (e instanceof java.awt.event.WindowEvent we) onWindow(we);
                } catch (Throwable t) {
                    System.err.println("[fidelis-engine] dialog observer: " + t);
                }
            }, java.awt.AWTEvent.WINDOW_EVENT_MASK);
        } catch (RuntimeException e) {
            System.err.println("[fidelis-engine] native dialogs are not observed: " + e);
        }
    }

    // ---- commands -------------------------------------------------------------------------------------

    /** Tool windows IJ1's own Recorder writes as a comment: opening one changes nothing; its effect is observed separately. */
    static final java.util.Set<String> TOOL_WINDOWS = java.util.Set.of("Threshold...", "Fonts...", "Brightness/Contrast...",
            "Channels Tool...");

    private void onCommand(String label) {
        Op op = Op.CURRENT.get();
        if (op == null) eng.adoptBeforeNative(); // the command must see hosted documents, not a window mid-swap
        String options = Macro.getOptions();
        ImagePlus cur = WindowManager.getCurrentImage();
        Doc doc = cur == null ? null : eng.registry.byImp(cur);
        if (op != null) {
            if (op.request.kind() != sc.fidelis.engine.api.Enums.ActionKind.CODE || op.actionId == null) return; // typed: recorded by its runner
            // a macro's roiManager(...) with no manager registered runs "ROI Manager..." to create one: the engine creates
            // (and remembers) its hidden singleton first, so the manager the macro fills, even one it closes before the
            // end, is the one the CODE runner reads back (RoiLists.noteManagerCommand)
            if ("ROI Manager...".equals(label) && ij.plugin.frame.RoiManager.getRawInstance() == null) eng.roiLists.noteManagerCommand();
            eng.results.noteChildCommand(label, options); // a macro's Clear Results restarts the table it captures
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("parentActionId", op.actionId);
            if (options != null) params.put("options", options.strip());
            eng.recordObserved(History.record(eng.history.nextId(), Origin.MACRO, Provenance.NATIVE, commandId(label), label, params,
                    doc == null ? null : TargetScope.of(doc.id), List.of(), false, Globals.capture(), Map.of(),
                    OperationState.COMPLETED, null));
            return;
        }
        Thread thread = Thread.currentThread();
        NativeCall outer = active.get(thread);
        if (outer != null && nestedCommand()) {
            // a command the running native command calls itself (Analyze Particles opens "ROI Manager...", a plugin
            // runs IJ.run): part of that step, like a macro's commands are part of its CODE step; IJ1's Recorder
            // skips them the same way. Its rows, images and ROIs belong to the outer command.
            eng.results.noteChildCommand(label, options);
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("parentActionId", outer.record.actionId());
            if (options != null) params.put("options", options.strip());
            eng.recordObserved(History.record(eng.history.nextId(), Origin.OBSERVED, Provenance.NATIVE, commandId(label), label,
                    params, doc == null ? null : TargetScope.of(doc.id), List.of(), false, Globals.capture(), Map.of(),
                    OperationState.COMPLETED, null));
            return;
        }
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        if (options != null) params.put("options", options.strip());
        boolean tool = TOOL_WINDOWS.contains(label) && options == null;
        List<String> lines = List.of(tool ? "//" + Options.runLine(label, null)
                : options != null ? Options.runLine(label, options.strip()) : Options.runLine(label, null));
        HistoryRecord rec = History.record(eng.history.nextId(), Origin.OBSERVED, Provenance.NATIVE, commandId(label), label, params,
                doc == null ? null : TargetScope.of(doc.id), lines, options != null || tool, Globals.capture(), Map.of(),
                OperationState.COMPLETED, null);
        eng.recordObserved(rec);
        // rows of the previous native command are captured now; a table restart by this command is noted (#5)
        eng.results.beforeNative(label, options, doc == null ? null : doc.id, rec.actionId());
        ij.plugin.frame.RoiManager rm = ij.plugin.frame.RoiManager.getRawInstance();
        int managerBefore = rm == null ? -1 : rm.getCount();
        NativeCall call = new NativeCall(rec, label, thread, doc);
        latest = call;
        active.put(thread, call);
        // results rows, new images, ROI Manager entries and so on appear after the command finishes. A command IJ1 runs
        // on its own thread (a menu click, a shortcut: an Executer thread named after the command) is followed up when
        // that thread ends, however long its dialog stays open; one run on the caller's thread 600 ms after it started.
        Runnable follow = () -> {
            active.remove(thread, call);
            eng.afterNative(rec.actionId(), doc, managerBefore);
        };
        if (thread.getName().equals(label) || thread.getName().equals("Run$_" + label)) whenFinished(thread, follow);
        else timer.schedule(follow, 600, TimeUnit.MILLISECONDS);
    }

    private void whenFinished(Thread thread, Runnable then) {
        timer.schedule(new Runnable() {
            @Override
            public void run() {
                if (thread.isAlive()) timer.schedule(this, 100, TimeUnit.MILLISECONDS);
                else then.run();
            }
        }, 100, TimeUnit.MILLISECONDS);
    }

    // ---- dialogs of native commands -------------------------------------------------------------------

    /** A native command whose dialog answers are still being observed. */
    private static final class NativeCall {
        final String label;
        final Thread thread;
        final Doc doc;
        final List<NativeDialogs.Field> fields = new ArrayList<>();
        Boolean stack;
        boolean cancelled;
        HistoryRecord record;

        NativeCall(HistoryRecord record, String label, Thread thread, Doc doc) {
            this.record = record;
            this.label = label;
            this.thread = thread;
            this.doc = doc;
        }
    }

    private volatile NativeCall latest;
    /** The top-level native command each thread is running (until its follow-up), to recognise nested commands. */
    private final Map<Thread, NativeCall> active = new java.util.concurrent.ConcurrentHashMap<>();

    /** True when an IJ1 command is already executing further up this thread's stack (Executer.run re-entered). */
    private static boolean nestedCommand() {
        long runs = StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().equals("ij.Executer") && f.getMethodName().equals("run")).count());
        return runs > 1;
    }

    /** Dialogs shown while a native command ran, until they close. */
    private final Map<java.awt.Window, NativeCall> dialogOwners = java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    /** True while {@code t} is still inside an IJ1 command (the dialog it shows belongs to that command). */
    private static boolean inCommand(Thread t) {
        if (!t.isAlive()) return false;
        for (StackTraceElement e : t.getStackTrace()) if (e.getClassName().equals("ij.Executer")) return true;
        return false;
    }

    /**
     * A GenericDialog (or IJ1's "Process all N images?" question) shown while a native command runs belongs to it:
     * when it closes, the answers become the command's recorded options (OK) or the command is recorded as cancelled.
     * Dialogs of typed commands are never shown (macro mode), except a legacy command's, which the worker owns.
     */
    private void onWindow(java.awt.event.WindowEvent we) {
        java.awt.Window w = we.getWindow();
        if (!(w instanceof ij.gui.GenericDialog) && !(w instanceof ij.gui.YesNoCancelDialog)) return;
        if (we.getID() == java.awt.event.WindowEvent.WINDOW_OPENED) {
            NativeCall c = latest;
            if (c == null || eng.busy() || !inCommand(c.thread)) return;
            dialogOwners.put(w, c);
        } else if (we.getID() == java.awt.event.WindowEvent.WINDOW_CLOSED) {
            NativeCall c = dialogOwners.remove(w);
            if (c != null) answered(c, w);
        }
    }

    private void answered(NativeCall c, java.awt.Window w) {
        synchronized (c) {
            if (w instanceof ij.gui.GenericDialog gd) {
                if (gd.wasOKed()) c.fields.addAll(NativeDialogs.read(gd));
                else if (gd.wasCanceled()) c.cancelled = true;
                else return; // answered "No" (a yes/no/cancel GenericDialog): not an option of the command
            } else if (w instanceof ij.gui.YesNoCancelDialog yn) {
                // Cancel aborts the command (IJ1 returns without running it); "Process all N images?" answers the
                // `stack` option; other questions ("Save 3 measurements?") are not options of the command
                if (yn.cancelPressed()) c.cancelled = true;
                else if ("Process Stack?".equals(yn.getTitle())) c.stack = yn.yesPressed();
                else return;
            }
            HistoryRecord old = c.record;
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            List<String> lines;
            boolean exact;
            OperationState outcome = OperationState.COMPLETED;
            String note = null;
            if (c.cancelled) {
                params.putAll(old.params());
                lines = old.macroLines();
                exact = old.macroExact();
                outcome = OperationState.CANCELLED;
                note = c.label + " was cancelled in its dialog";
            } else {
                Catalogue.Entry en = entry(c.label);
                NativeDialogs.Recorded r = NativeDialogs.record(en, c.fields, c.stack, c.doc == null ? null : c.doc.imp, eng.registry);
                params.putAll(r.params());
                params.put("options", r.options());
                if (!r.unmatched().isEmpty()) params.put("unmatchedDialogFields", List.copyOf(r.unmatched()));
                lines = List.of(Options.runLine(c.label, r.options()));
                exact = r.exact();
                if (Results.restartsTable(c.label, r.options())) eng.results.noteRestart(old.actionId());
            }
            HistoryRecord upd = new HistoryRecord(old.actionId(), old.time(), old.origin(), old.provenance(), old.commandId(),
                    old.label(), java.util.Collections.unmodifiableMap(params), old.target(), List.copyOf(lines), exact,
                    old.globalsBefore(), old.globalsDelta(), outcome, note, old.transcriptRef());
            c.record = upd;
            eng.replaceObserved(upd);
        }
    }

    private Catalogue.Entry entry(String label) {
        for (Catalogue.Entry e : eng.cat().entries()) if (e.label().equals(label)) return e;
        return null;
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

    /** Remember the threshold a typed action left (or a document arrived with), so it is not recorded again as native. */
    void noteThreshold(Doc doc) {
        lastThreshold.put(doc.imp, thresholdOf(doc.imp));
    }

    private static String thresholdOf(ImagePlus imp) {
        ij.process.ImageProcessor ip = imp.getProcessor();
        if (ip == null || ip.getMinThreshold() == ij.process.ImageProcessor.NO_THRESHOLD) return "";
        return Options.formatNumber(ip.getMinThreshold()) + "," + Options.formatNumber(ip.getMaxThreshold());
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
                settleThreshold(doc);
                StateDigest digest = new StateDigest(List.of(), List.of(),
                        List.of(new DocumentChange(doc.id, false, false, true, false, false)), 0, Map.of(), List.of(), List.of(), List.of());
                eng.events.emit(seq -> new EngineEvent.StateDigestEvent(seq, null, digest));
            }, COALESCE_MS, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * A threshold set natively (the Threshold adjuster, a plugin) is a result-affecting state change that Convert to
     * Mask and Analyze Particles depend on: record it with the exact numbers IJ1 holds, as {@code setThreshold(lo, hi)}
     * (the method a person chose in the adjuster is not observable; the numbers reproduce the same mask).
     */
    private void settleThreshold(Doc doc) {
        String now = thresholdOf(doc.imp);
        String before = lastThreshold.getOrDefault(doc.imp, "");
        if (now.equals(before)) return;
        lastThreshold.put(doc.imp, now);
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        String opName;
        List<String> lines;
        if (now.isEmpty()) {
            opName = sc.fidelis.engine.api.ActionRequest.Operations.RESET_THRESHOLD;
            lines = List.of("resetThreshold();");
        } else {
            ij.process.ImageProcessor ip = doc.imp.getProcessor();
            opName = sc.fidelis.engine.api.ActionRequest.Operations.SET_THRESHOLD;
            params.put("lower", ip.getMinThreshold());
            params.put("upper", ip.getMaxThreshold());
            lines = List.of("setThreshold(" + now.replace(",", ", ") + ");");
        }
        eng.recordObserved(History.record(eng.history.nextId(), Origin.OBSERVED, Provenance.NATIVE, opName, "Threshold", params,
                TargetScope.of(doc.id), lines, true, Globals.capture(), Map.of(), OperationState.COMPLETED, null));
    }

    // ---- the IJ1 Log -----------------------------------------------------------------------------------

    private static final long LOG_POLL_MS = 200;
    /** Lines of IJ1's Log already reported as {@link EngineEvent.LogLine}. */
    private List<String> logSeen = List.of();

    /**
     * Report what IJ1's Log gained since the last look as {@link EngineEvent.LogLine} events: a macro's {@code print},
     * a plugin's {@code IJ.log}, the engine's own notes. IJ1 offers no listener for Log additions, so the Log text
     * ({@code IJ.getLog()}, null while no Log exists) is compared every {@value #LOG_POLL_MS} ms on the observer timer
     * and at the end of every CODE action. Each line position is compared with the last look: appended lines are
     * reported once each, a line rewritten in place ({@code print("\\Update:...")}, {@code "\\Update3:..."}) again with
     * its new text and nothing else, and after a clear ({@code print("\\Clear")}, the Log window closed) what is written
     * afterwards (a Log shorter than at the last look is taken as cleared, so a repeated first line is reported again; a
     * clear and reprint of the same number of lines between two looks is reported only where the text differs, which is
     * why a CODE result's digest still carries the run's complete {@code newLogLines}).
     */
    synchronized void flushLog() {
        String text;
        try {
            text = ij.IJ.getLog();
        } catch (RuntimeException e) {
            return;
        }
        List<String> now = text == null || text.isEmpty() ? List.of() : List.of(text.split("\\R", -1));
        if (!now.isEmpty() && now.get(now.size() - 1).isEmpty()) now = now.subList(0, now.size() - 1); // trailing line break
        List<String> seen = logSeen;
        logSeen = List.copyOf(now);
        // a shorter Log was cleared (or closed and re-created) since the last look: everything in it is new, including a
        // line that happens to repeat what stood at the same position before
        boolean newGeneration = now.size() < seen.size();
        for (int i = 0; i < now.size(); i++) {
            String line = now.get(i);
            if (line.isBlank() || (!newGeneration && i < seen.size() && line.equals(seen.get(i)))) continue;
            eng.events.emit(seq -> new EngineEvent.LogLine(seq, line));
        }
    }

    /** Drop per-image state of a closed document (the maps are strong, identity-keyed and would pin the pixel data). */
    void forget(ImagePlus imp) {
        synchronized (pendingRoi) {
            ScheduledFuture<?> f = pendingRoi.remove(imp);
            if (f != null) f.cancel(false);
            lastRoi.remove(imp);
        }
        synchronized (pendingUpdate) {
            ScheduledFuture<?> f = pendingUpdate.remove(imp);
            if (f != null) f.cancel(false);
        }
        lastThreshold.remove(imp);
    }

    /** Run {@code r} after {@code ms} milliseconds on the observer timer. */
    void later(Runnable r, long ms) {
        timer.schedule(r, ms, TimeUnit.MILLISECONDS);
    }

    List<ImagePlus> pendingImages() {
        synchronized (pendingRoi) {
            return new ArrayList<>(pendingRoi.keySet());
        }
    }
}
