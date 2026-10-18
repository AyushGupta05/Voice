package sc.fidelis.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import ij.ImagePlus;
import ij.Macro;
import ij.macro.Interpreter;
import ij.macro.MacroConstants;
import ij.macro.Program;
import ij.macro.Symbol;
import ij.macro.Tokenizer;
import ij.plugin.frame.RoiManager;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;

/**
 * CODE actions: user macro text run through an owned {@link Interpreter} (mirrors
 * {@code Macro_Runner.runMacro(String, String)} so that {@code wasError()} and the error message stay
 * readable, D07 section 2), behind a capability confirmation token keyed to the text hash.
 */
final class CodeRunner {
    /** Capability tokens recognised by the scan: functions the user must consent to by name. */
    private static final List<String> CAPABILITIES = List.of("exec", "eval", "call", "runMacro", "File.delete", "File.rename",
            "File.copy", "File.append", "File.saveString", "File.openUrlAsString", "File.makeDirectory", "getURL");

    /** Statements that map one-to-one onto a generated (TYPED-equivalent) macro line. */
    private static final Pattern PURE_LINE = Pattern.compile(
            "^(run|setAutoThreshold|setThreshold|resetThreshold|makeRectangle|makeOval|makePolygon|makeLine|makePoint|makeSelection"
                    + "|setMinAndMax|setSlice|setOption)\\(.*\\);\\s*$");

    private final DefaultEngine eng;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    CodeRunner(DefaultEngine eng) {
        this.eng = eng;
    }

    /**
     * Capabilities the macro text really calls, in the order of {@link #CAPABILITIES}. The text is tokenized by IJ1's
     * own {@link Tokenizer} (comments vanish, string constants are opaque tokens), and a capability counts only when
     * its name token is followed by {@code (}: {@code exec("ls")} and {@code File.delete(p)} do, while
     * {@code print("call this eval later")}, {@code // exec(...)}, {@code exec = 3} and {@code myexec(1)} do not.
     * The symbol table is <em>not</em> consulted: it lists every built-in function whether the text uses it or not.
     * Text IJ1 cannot tokenize at all reports no capabilities; the interpreter then reports the syntax error.
     */
    static List<String> scan(String text) {
        List<String> names = new ArrayList<>();
        try {
            Program pgm = new Tokenizer().tokenize(text);
            int[] code = pgm.getCode();
            Symbol[] table = pgm.getSymbolTable();
            for (int i = 0; code != null && i < code.length; i++) {
                int tok = code[i] & MacroConstants.TOK_MASK;
                if (tok == MacroConstants.EOF) break;
                String name = identifier(tok, code[i] >> MacroConstants.TOK_SHIFT, table);
                if (name == null) continue;
                // File.delete: WORD/function "File", '.', WORD "delete"
                if (i + 2 < code.length && (code[i + 1] & MacroConstants.TOK_MASK) == '.') {
                    String member = identifier(code[i + 2] & MacroConstants.TOK_MASK, code[i + 2] >> MacroConstants.TOK_SHIFT, table);
                    if (member != null && isCall(code, i + 3)) names.add(name + "." + member);
                    continue;
                }
                if (isCall(code, i + 1)) names.add(name);
            }
        } catch (RuntimeException e) {
            // IJ1 could not tokenize the text: the interpreter reports the syntax error; no capability can be named
        }
        Set<String> found = new LinkedHashSet<>();
        for (String cap : CAPABILITIES) if (names.contains(cap)) found.add(cap);
        return new ArrayList<>(found);
    }

    private static boolean isCall(int[] code, int at) {
        return at < code.length && (code[at] & MacroConstants.TOK_MASK) == '(';
    }

    /** The name of an identifier token (a user word or a built-in function), else null (operators, constants, keywords). */
    private static String identifier(int tok, int address, Symbol[] table) {
        boolean named = tok == MacroConstants.WORD || tok == MacroConstants.PREDEFINED_FUNCTION || tok == MacroConstants.NUMERIC_FUNCTION
                || tok == MacroConstants.STRING_FUNCTION || tok == MacroConstants.ARRAY_FUNCTION || tok == MacroConstants.USER_FUNCTION
                || tok == MacroConstants.VARIABLE_FUNCTION;
        if (!named || table == null || address < 0 || address >= table.length || table[address] == null) return null;
        return table[address].str;
    }

    private static String sha(String text) {
        try {
            return Registry.hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Outcome run(Op op) {
        ActionRequest r = op.request;
        Object t = r.params().get("text");
        if (!(t instanceof String text) || text.isBlank()) return Outcome.failed(ErrorCode.VALIDATION_FAILED, "CODE needs params.text");
        String lang = String.valueOf(r.params().getOrDefault("language", "ijm")).toLowerCase(Locale.ROOT);
        if (!Set.of("ijm", "macro", "imagej macro", "ij1 macro", "ijm macro").contains(lang))
            return Outcome.failed(ErrorCode.UNSUPPORTED_IN_MODE, "only the ImageJ 1.x macro language is supported, not '" + lang + "'");
        String hash = sha(text);
        List<String> caps = scan(text);
        String token = r.confirmationToken();
        if (token == null || !hash.equals(tokens.get(token))) {
            String fresh = "conf-" + UUID.randomUUID();
            tokens.put(fresh, hash);
            Map<String, String> ctx = new LinkedHashMap<>();
            ctx.put("sha256", hash);
            ctx.put("capabilities", String.join(",", caps));
            EngineError err = new EngineError(ErrorCode.CAPABILITY_CONFIRMATION_REQUIRED,
                    "macro code runs with full privileges; confirm to run" + (caps.isEmpty() ? "" : " (uses " + String.join(", ", caps) + ")"),
                    null, ctx, true, List.of("resubmit with the returned confirmationToken"));
            return new Outcome(OperationState.AWAITING_CONFIRMATION, err, null, null, fresh);
        }

        String actionId = eng.history.nextId();
        op.actionId = actionId;
        Map<String, String> gBefore = Globals.capture();
        eng.results.beginTyped(); // rows a native command left are settled under it before the macro runs
        int resultsBefore = Results.tableSize();
        // a targeted macro runs on its document: typed actions never rely on IJ1's implicit current image
        Doc target = r.target() == null || r.target().document() == null ? null : eng.registry.get(r.target().document());
        ij.WindowManager.setTempCurrentImage(null);
        if (target != null) eng.makeCurrent(target);
        // The ROI Manager singleton mirrors one document's ROI list while the macro runs (as it does for a typed Analyze
        // Particles with add): the macro's target, else the document current when it starts. The macro sees exactly that
        // document's ROIs, a batch-mode macro writes to this registered manager instead of IJ1's private one, and what
        // the macro leaves there (roiManager("Add"), a reset, an Analyze Particles with add) is taken over into that
        // document's list afterwards when its content changed. Like a native command (DefaultEngine.afterNative), a macro
        // that moves to another image keeps its manager entries attributed to the document it started on: IJ1's one
        // manager carries no per-entry image identity, so the engine cannot split a multi-image macro's additions.
        Doc roiOwner = target;
        if (roiOwner == null) {
            ImagePlus cur = ij.WindowManager.getCurrentImage();
            roiOwner = cur == null ? null : eng.registry.byImp(cur);
        }
        if (roiOwner != null) {
            // loaded only when the manager does not already hold this document's objects, so a selection the previous
            // macro left (roiManager("Select", 0), then roiManager("Delete") in the next run) survives as it does in Fiji
            eng.roiLists.ensureMirrors(roiOwner);
        } else {
            // no document to own them: the registered manager exists (a batch-mode macro writes to it rather than to
            // IJ1's private one) and is empty, so a closed document's leftovers never flow into one the macro opens
            eng.roiLists.manager().reset();
        }
        List<String> managerBefore = managerContent();
        eng.setRunning(op);
        eng.emitState(op, OperationState.RUNNING, null);
        Guard guard = Guard.begin(new Guard.Listener() {
            @Override
            public void dialogOpened(String title, String cls) {
                eng.events.emit(seq -> new EngineEvent.DialogPending(seq, op.id, title, cls));
                eng.emitState(op, OperationState.WAITING_FOR_DIALOG, null);
            }

            @Override
            public void dialogsClosed() {
                eng.emitState(op, OperationState.RUNNING, null);
            }
        });
        eng.setGuard(guard);
        Interpreter interp = new Interpreter();
        eng.setInterpreter(interp);
        String ret = null;
        Throwable thrown = null;
        boolean cancelledBeforeStart;
        synchronized (op) {
            // Interpreter.run resets the interpreter's abort flag when it starts, so a cancel that arrived before
            // this point would be lost: honour it here, and let cancel() abort only an interpreter that has started.
            cancelledBeforeStart = op.cancelRequested;
            if (cancelledBeforeStart) op.abortIssued = true;
            else op.interpreterStarted = true;
        }
        try {
            ret = cancelledBeforeStart ? "[aborted]" : interp.run(text, null);
        } catch (Throwable e) {
            thrown = e;
            interp.abortMacro();
            ImagePlus cur = ij.WindowManager.getCurrentImage();
            if (cur != null) cur.unlock();
        } finally {
            op.interpreterFinished = true;
            eng.setInterpreter(null);
            eng.setRunning(null);
            eng.setGuard(null);
            ij.IJ.resetEscape();
            ij.IJ.setKeyUp(ij.IJ.ALL_KEYS);
            Macro.setOptions(null);
        }
        Guard.Report rep = guard.end();
        eng.observers.flushLog(); // the macro's last Log lines reach listeners before its result does
        showBatchImages();
        Registry.Change change = eng.reconcile();
        eng.rehideWindows();
        List<StateDigest.DocumentChange> changed = List.of();
        List<String> managerAfter = managerContent();
        if (managerAfter != null && !managerAfter.equals(managerBefore)) {
            Doc roiDoc = roiOwner;
            if (roiDoc == null) {
                // no document when the macro started: the one it left current (an image it opened or selected)
                ImagePlus cur = ij.WindowManager.getCurrentImage();
                roiDoc = cur == null ? null : eng.registry.byImp(cur);
            }
            if (roiDoc != null && eng.registry.all().contains(roiDoc)) {
                eng.roiLists.syncBack(roiDoc);
                changed = List.of(new StateDigest.DocumentChange(roiDoc.id, false, false, false, true, false));
            }
        }
        boolean cancelled = op.cancelRequested && op.abortIssued;
        boolean macroError = interp.wasError() || (ret != null && ret.equals("[aborted]"));
        String message = null;
        if (rep.failed()) message = rep.errorText();
        else if (macroError || thrown != null) {
            String m = interp.getErrorMessage();
            message = m != null ? m : (thrown != null && !Macro.MACRO_CANCELED.equals(thrown.getMessage()) ? String.valueOf(thrown) : "macro aborted");
        }

        Map<String, String> gAfter = Globals.capture();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("language", "ijm");
        params.put("text", text);
        params.put("sha256", hash);
        params.put("capabilities", caps);
        if (!change.added().isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Doc d : change.added()) ids.add(d.id.value());
            params.put("createdDocuments", ids);
        }
        boolean pure = isPure(text);
        List<String> lines = pure ? pureLines(text) : List.of();
        Provenance prov = pure ? Provenance.TYPED : Provenance.UNRECORDABLE;

        OperationState state;
        EngineError error = null;
        if (cancelled) {
            state = OperationState.CANCELLED;
            error = new EngineError(ErrorCode.CANCELLED, "macro was cancelled", "MACRO_ABORTED", Map.of(), false, List.of());
        } else if (message != null) {
            state = OperationState.FAILED;
            error = new EngineError(ErrorCode.EXECUTION_FAILED, "macro failed: " + message, "MACRO_ERROR", Map.of(), false, List.of());
        } else {
            state = OperationState.COMPLETED;
        }
        HistoryRecord rec = History.record(actionId, r.origin(), prov, null, "macro", params, r.target(), lines, pure, gBefore,
                Globals.delta(gBefore, gAfter), state, error == null ? null : error.message());
        eng.history.add(rec);
        int rows = Results.tableSize() - resultsBefore;
        // rows from resultsBefore on are the macro's (from row 0 when it ran Clear Results: noted by the observer)
        eng.results.capture(resultsBefore, null, actionId);
        List<DocumentId> added = new ArrayList<>();
        for (Doc d : change.added()) added.add(d.id);
        List<DocumentId> removed = new ArrayList<>();
        for (Doc d : change.removed()) removed.add(d.id);
        StateDigest digest = new StateDigest(added, removed, changed, Math.max(0, rows), Globals.delta(gBefore, gAfter),
                rep.newLogLines(), rep.windowsOpened(), rep.windowsClosed());
        return new Outcome(state, error, digest, rec, null);
    }

    /**
     * The ROI Manager singleton's content as comparable text, or null when no manager exists. Each entry is IJ1's own
     * serialisation ({@code RoiEncoder}: type, coordinates, name, position, stroke width and colours, fill, properties)
     * so a macro that resets the manager and adds the same number of other ROIs, moves, renames or restyles one
     * ({@code roiManager("Set Line Width", 5)}) is seen as a change although the count is unchanged.
     */
    private static List<String> managerContent() {
        RoiManager rm = RoiManager.getRawInstance();
        return rm == null ? null : content(List.of(rm.getRoisAsArray()));
    }

    private static List<String> content(List<ij.gui.Roi> rois) {
        List<String> out = new ArrayList<>();
        for (ij.gui.Roi r : rois) {
            byte[] bytes;
            try {
                bytes = ij.io.RoiEncoder.saveAsByteArray(r);
            } catch (RuntimeException e) {
                bytes = null;
            }
            out.add(bytes != null ? java.util.Base64.getEncoder().encodeToString(bytes) : String.valueOf(RoiCodec.toGeometry(r)));
        }
        return out;
    }

    /** Images a macro left in batch mode are shown (as setBatchMode(false) would) so that the registry can adopt them. */
    private static void showBatchImages() {
        if (!Interpreter.isBatchMode() && Interpreter.getBatchModeImageCount() == 0) return;
        List<ImagePlus> imgs = new ArrayList<>();
        int[] ids = Interpreter.getBatchModeImageIDs();
        if (ids != null) for (int id : ids) {
            ImagePlus imp = Interpreter.getBatchModeImage(id);
            if (imp != null) imgs.add(imp);
        }
        Interpreter.batchMode = false;
        for (ImagePlus imp : imgs) imp.show();
    }

    static boolean isPure(String text) {
        boolean any = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (!PURE_LINE.matcher(line).matches()) return false;
            any = true;
        }
        return any;
    }

    private static List<String> pureLines(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty() && !line.startsWith("//")) out.add(line);
        }
        return out;
    }
}
