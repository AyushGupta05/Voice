package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.plugin.frame.Recorder;
import java.awt.EventQueue;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.Window;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.module.ModuleService;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptModule;

/** Menu commands, macros and SciJava scripts, with a dialog watcher so a blocking GenericDialog becomes a described UI instead of a hung request. */
final class Scripting {
    private final Journal journal;
    private final UiTree ui;
    private final AtomicBoolean analysisRunning;
    private Map<String, String> menuPaths;

    Scripting(Journal journal, UiTree ui, AtomicBoolean analysisRunning) { this.journal = journal; this.ui = ui; this.analysisRunning = analysisRunning; }

    static Context context() {
        try {
            var legacy = net.imagej.legacy.LegacyService.getInstance();
            if (legacy != null) return legacy.getContext();
        } catch (Throwable ignored) { /* ImageJ1-only runtime */ }
        return LumaBridgeService.sharedContext();
    }

    /** Command label → "File>Open Samples>Blobs" from the live ImageJ1 menu bar. */
    synchronized Map<String, String> menuPaths() {
        if (menuPaths != null) return menuPaths;
        var paths = new HashMap<String, String>();
        MenuBar bar = Menus.getMenuBar();
        if (bar != null) for (int i = 0; i < bar.getMenuCount(); i++) walk(bar.getMenu(i), bar.getMenu(i).getLabel(), paths);
        if (!paths.isEmpty()) menuPaths = paths;
        return paths;
    }
    private static void walk(Menu menu, String prefix, Map<String, String> paths) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem item = menu.getItem(i);
            if (item instanceof Menu child) walk(child, prefix + ">" + child.getLabel(), paths);
            else if (item.getLabel() != null && !item.getLabel().equals("-")) paths.putIfAbsent(item.getLabel(), prefix + ">" + item.getLabel());
        }
    }
    List<Map<String, Object>> commands(JsonObject args, boolean pluginsOnly) {
        String query = Operations.string(args, "query", "").toLowerCase(Locale.ROOT).strip();
        var result = new ArrayList<Map<String, Object>>();
        var commands = Menus.getCommands(); var paths = menuPaths();
        if (commands != null) {
            var names = new ArrayList<String>(); for (var key : commands.keySet()) names.add(String.valueOf(key)); Collections.sort(names);
            for (var name : names) {
                String path = paths.getOrDefault(name, "");
                if (pluginsOnly && !path.startsWith("Plugins>")) continue;
                if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query) && !path.toLowerCase(Locale.ROOT).contains(query)) continue;
                var row = new LinkedHashMap<String, Object>(); row.put("name", name); row.put("menuPath", path); row.put("source", "imagej1"); row.put("className", String.valueOf(commands.get(name)));
                result.add(row);
            }
        }
        var context = context();
        if (context != null && !pluginsOnly) {
            try {
                for (var info : context.getService(CommandService.class).getCommands()) {
                    String title = Objects.toString(info.getTitle(), ""); String path = info.getMenuPath() == null ? "" : info.getMenuPath().getMenuString();
                    if (title.isBlank() || info.getClassName().startsWith("net.imagej.legacy.")) continue;
                    if (!query.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(query) && !path.toLowerCase(Locale.ROOT).contains(query)) continue;
                    var row = new LinkedHashMap<String, Object>(); row.put("name", title); row.put("menuPath", path); row.put("source", "scijava"); row.put("className", info.getClassName());
                    result.add(row);
                }
            } catch (RuntimeException ignored) { /* SciJava list unavailable */ }
        }
        return result.stream().limit(300).toList();
    }
    /** Canonical command label for a requested name: exact, case-insensitive, or with a legacy size suffix such as "Blobs (25K)" dropped. Null when unknown. */
    String resolveCommand(String name) {
        var candidates = new ArrayList<String>(); candidates.add(name);
        String stripped = name.replaceAll("\\s*\\([^)]*\\)\\s*$", "").strip(); if (!stripped.isEmpty() && !stripped.equals(name)) candidates.add(stripped);
        var commands = Menus.getCommands();
        var labels = new ArrayList<String>(); if (commands != null) for (var key : commands.keySet()) labels.add(String.valueOf(key));
        var context = context();
        if (context != null) try { for (var info : context.getService(CommandService.class).getCommands()) if (info.getTitle() != null) labels.add(info.getTitle()); } catch (RuntimeException ignored) { /* no list */ }
        for (var candidate : candidates) { if (labels.contains(candidate)) return candidate; }
        for (var candidate : candidates) for (var label : labels) if (label.equalsIgnoreCase(candidate)) return label;
        return null;
    }

    /** Outcome of watching a worker: finished (value or error), a dialog that stayed open, or still running. */
    record Watched(boolean finished, Object value, Throwable error, Window dialog, long elapsedMs, List<String> autoClosed) {
        Watched(boolean finished, Object value, Throwable error, Window dialog, long elapsedMs) { this(finished, value, error, dialog, elapsedMs, List.of()); }
        /** Text of an auto-closed ImageJ error dialog ("Macro Error", "Exception", …), or null. */
        String errorDialog() { for (var text : autoClosed) if (text.startsWith("error:")) return text.substring(6); return null; }
        boolean cancelledFileDialog() { return autoClosed.stream().anyMatch(text -> text.startsWith("file:")); }
    }
    /** Native file choosers cannot be driven through fiji_ui_action, so a bridge call that spawns one gets it cancelled. */
    static boolean isFileDialog(Window window) {
        if (window instanceof java.awt.FileDialog) return true;
        return window instanceof java.awt.Dialog && hasFileChooser(window);
    }
    private static boolean hasFileChooser(java.awt.Component component) {
        if (component instanceof javax.swing.JFileChooser) return true;
        if (component instanceof java.awt.Container container) for (var child : container.getComponents()) if (hasFileChooser(child)) return true;
        return false;
    }
    /** ImageJ's modal error/message dialogs block every client; they are read, closed and reported as the call's error instead. */
    static boolean isErrorDialog(Window window) {
        String title = UiTree.title(window);
        if (title.equals("Macro Error") || title.equals("Exception") || title.equals("Error") || title.equals("ImageJ Exception")) return true;
        // IJ.error() uses the plain "ImageJ" title; informational showMessage() dialogs keep their own title and are returned for the model to read.
        return window instanceof ij.gui.MessageDialog && title.equals("ImageJ") && Macros.dialogText(window).matches("(?is).*(error|exception|not found|cannot|unable|required|invalid|failed|no image).*");
    }

    /** Runs work off the EDT and watches for new dialogs; returns when done, when a dialog stays open for 400 ms, or after the timeout. */
    Watched watch(String name, ImagePlus imp, java.util.concurrent.Callable<Object> work, long timeoutMs) throws InterruptedException {
        var before = UiTree.showing();
        var holder = new Object[2]; var done = new AtomicBoolean();
        var thread = new Thread(() -> {
            // ImageJ's "current image" is per thread when the image has no window; let the worker see what the EDT saw.
            if (imp != null && imp.getWindow() == null) ij.WindowManager.setTempCurrentImage(imp);
            try { holder[0] = work.call(); } catch (Throwable error) { holder[1] = error; } finally { done.set(true); analysisRunning.set(false); }
        }, "luma-" + name);
        thread.setDaemon(true); analysisRunning.set(true); long start = System.currentTimeMillis(); thread.start();
        Window seen = null; long seenAt = 0; var autoClosed = new ArrayList<String>(); var closing = new HashSet<Window>();
        while (!done.get()) {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > timeoutMs) return new Watched(false, null, null, null, elapsed, List.copyOf(autoClosed));
            var dialogs = UiTree.newDialogs(before).stream().filter(window -> window instanceof java.awt.Dialog && !closing.contains(window)).toList();
            if (!dialogs.isEmpty()) {
                var current = dialogs.getLast();
                if (isFileDialog(current)) { autoClosed.add("file:" + UiTree.title(current)); closing.add(current); EventQueue.invokeLater(current::dispose); seen = null; }
                else if (isErrorDialog(current)) { autoClosed.add("error:" + Macros.dialogText(current)); closing.add(current); EventQueue.invokeLater(current::dispose); seen = null; }
                else if (current != seen) { seen = current; seenAt = System.currentTimeMillis(); }
                else if (System.currentTimeMillis() - seenAt > 400) return new Watched(false, null, null, current, elapsed, List.copyOf(autoClosed));
            } else seen = null;
            TimeUnit.MILLISECONDS.sleep(40);
        }
        return new Watched(true, holder[0], (Throwable) holder[1], null, System.currentTimeMillis() - start, List.copyOf(autoClosed));
    }
    /** Common outcome fields: an auto-closed error dialog fails the call with ImageJ's own text; a cancelled file dialog is reported. */
    private static void applyAutoClosed(Watched watched, Map<String, Object> result, String what) {
        String error = watched.errorDialog();
        if (error != null) throw new IllegalStateException(what + " failed in Fiji: " + error + " (the error dialog was closed automatically)");
        if (watched.cancelledFileDialog()) { result.put("cancelledFileDialog", true); result.put("note", "The command opened a native file chooser, which the bridge cancelled; use fiji_open_path, fiji_save_image or fiji_export_results with an explicit path instead."); }
    }
    Map<String, Object> dialogJson(Window window) throws Exception {
        var holder = new Object[1];
        EventQueue.invokeAndWait(() -> holder[0] = ui.describeWindow(window));
        @SuppressWarnings("unchecked") var described = (Map<String, Object>) holder[0];
        return described;
    }

    /** ImageJ/Bio-Formats commands whose first act is an OS file or folder chooser. */
    static final Set<String> FILE_CHOOSER_COMMANDS = Set.of("Bio-Formats Importer", "Bio-Formats", "Bio-Formats (Windowless)", "Open...", "Open Next", "Image Sequence...", "Raw...", "LUT...", "Text Image...", "Text File...", "Results...", "Table...", "AVI...", "XY Coordinates...", "TIFF Virtual Stack...", "Open as Hyperstack", "Bio-Formats Exporter", "Image Sequence... ", "Save As...", "Save", "Tiff...", "Jpeg...", "PNG...");
    Map<String, Object> runCommand(JsonObject args, ImagePlus imp, long timeoutMs) throws Exception {
        String requested = Operations.string(args, "name", "").strip();
        if (requested.isEmpty()) throw new IllegalArgumentException("Provide the exact command name (see fiji_commands).");
        String resolved = resolveCommand(requested);
        if (resolved == null) throw new IllegalArgumentException("Unknown command \"" + requested + "\". Search with fiji_commands for the exact label.");
        final String name = resolved;
        String options = args.has("options") && !args.get("options").isJsonNull() ? args.get("options").getAsString() : null;
        // Commands that open a native macOS file chooser park the worker on a dialog no bridge or window tree can see;
        // without a path in options they are refused in favour of open_path (fiji_find_images locates the file).
        if (FILE_CHOOSER_COMMANDS.contains(name) && (options == null || !options.matches("(?s).*\\b(open|path|file|dir|directory)=.*")))
            throw new IllegalArgumentException("\"" + name + "\" opens a native file chooser that cannot be driven. Use fiji_open_path with the exact path (fiji_find_images finds it), or pass options like open=[/absolute/path].");
        journal.record("run_command", Journal.runLine(name, options));
        String logBefore = Objects.toString(IJ.getLog(), "");
        var watched = watch("command", imp, () -> { if (imp == null) { if (options == null) IJ.run(name); else IJ.run(name, options); } else IJ.run(imp, name, options); return null; }, timeoutMs);
        var result = new LinkedHashMap<String, Object>();
        result.put("command", name); result.put("requested", requested); result.put("options", options); result.put("elapsedMs", watched.elapsedMs());
        if (watched.error() != null) throw new IllegalStateException("Command failed: " + Objects.toString(watched.error().getMessage(), watched.error().getClass().getSimpleName()));
        applyAutoClosed(watched, result, "Command \"" + name + "\"");
        if (watched.dialog() != null) { result.put("dispatched", true); result.put("completed", false); result.put("dialog", dialogJson(watched.dialog())); result.put("note", "The command opened a dialog and is waiting. Fill it with fiji_ui_action and press its OK button, or run again with options."); }
        else if (!watched.finished()) { result.put("dispatched", true); result.put("completed", false); result.put("running", true); result.put("note", "Still running after " + timeoutMs / 1000 + " s; fiji_context reports analysisRunning until it finishes."); }
        else { result.put("dispatched", true); result.put("completed", true); }
        result.put("log", logDelta(logBefore));
        return result;
    }
    static String logDelta(String before) {
        String after = Objects.toString(IJ.getLog(), "");
        String delta = after.startsWith(before) ? after.substring(before.length()) : after;
        return delta.length() > 4000 ? delta.substring(delta.length() - 4000) : delta;
    }
    Map<String, Object> runMacro(JsonObject args, ImagePlus imp, long timeoutMs) throws Exception {
        String code = Operations.string(args, "code", ""); if (code.isBlank()) throw new IllegalArgumentException("Provide macro code.");
        if (code.length() > 200_000) throw new IllegalArgumentException("Macro code is too long.");
        String argument = Operations.string(args, "argument", "");
        journal.record("run_macro", "// macro (" + code.lines().count() + " lines)\n" + code.strip());
        String logBefore = Objects.toString(IJ.getLog(), "");
        var watched = watch("macro", imp, () -> IJ.runMacro(code, argument), timeoutMs);
        var result = new LinkedHashMap<String, Object>();
        result.put("elapsedMs", watched.elapsedMs());
        if (watched.error() != null) throw new IllegalStateException("Macro failed: " + Objects.toString(watched.error().getMessage(), watched.error().getClass().getSimpleName()));
        applyAutoClosed(watched, result, "Macro");
        if (watched.dialog() != null) { result.put("completed", false); result.put("dialog", dialogJson(watched.dialog())); result.put("note", "The macro opened a dialog and is waiting."); }
        else if (!watched.finished()) { result.put("completed", false); result.put("running", true); }
        else { result.put("completed", true); String value = Objects.toString(watched.value(), ""); result.put("result", value.length() > 4000 ? value.substring(0, 4000) : value); result.put("aborted", "[aborted]".equals(value)); }
        result.put("log", logDelta(logBefore));
        return result;
    }
    Map<String, Object> runScript(JsonObject args, ImagePlus imp, long timeoutMs) throws Exception {
        String language = Operations.string(args, "language", "groovy").toLowerCase(Locale.ROOT);
        String code = Operations.string(args, "code", ""); if (code.isBlank()) throw new IllegalArgumentException("Provide script code.");
        if (language.equals("ijm") || language.equals("macro")) { var result = new LinkedHashMap<>(runMacro(args, imp, timeoutMs)); result.put("language", "ijm"); return result; }
        String extension = switch (language) { case "groovy" -> ".groovy"; case "python", "jython" -> ".py"; case "javascript", "js" -> ".js"; case "beanshell" -> ".bsh"; default -> throw new IllegalArgumentException("language must be groovy, python, javascript or ijm."); };
        var context = context();
        if (context == null) throw new IllegalStateException("The SciJava context is unavailable (ImageJ1-only runtime); use fiji_run_macro.");
        journal.record("run_script", "// " + language + " script (" + code.lines().count() + " lines)");
        var out = new StringWriter(); var err = new StringWriter();
        String logBefore = Objects.toString(IJ.getLog(), "");
        var watched = watch("script", imp, () -> {
            var info = new ScriptInfo(context, "luma-script" + extension, new StringReader(code));
            ScriptModule module = info.createModule(); context.inject(module);
            module.setOutputWriter(out); module.setErrorWriter(err);
            var finished = context.getService(ModuleService.class).run(module, true).get();
            var outputs = new LinkedHashMap<String, Object>();
            for (var entry : finished.getOutputs().entrySet()) { String value = Objects.toString(entry.getValue(), "null"); outputs.put(entry.getKey(), value.length() > 2000 ? value.substring(0, 2000) : value); }
            return Map.of("returnValue", truncate(Objects.toString(module.getReturnValue(), "")), "outputs", outputs);
        }, timeoutMs);
        var result = new LinkedHashMap<String, Object>();
        result.put("language", language); result.put("elapsedMs", watched.elapsedMs()); result.put("stdout", truncate(out.toString())); result.put("stderr", truncate(err.toString()));
        applyAutoClosed(watched, result, "Script");
        if (watched.error() != null) throw new IllegalStateException("Script failed: " + truncate(Objects.toString(watched.error().getCause() == null ? watched.error().getMessage() : watched.error().getCause().getMessage(), watched.error().getClass().getSimpleName())) + (err.toString().isBlank() ? "" : "\n" + truncate(err.toString())));
        if (watched.dialog() != null) { result.put("completed", false); result.put("dialog", dialogJson(watched.dialog())); result.put("note", "The script opened a dialog (probably #@ parameters) and is waiting."); }
        else if (!watched.finished()) { result.put("completed", false); result.put("running", true); }
        else { result.put("completed", true); if (watched.value() instanceof Map<?, ?> map) result.putAll(castMap(map)); }
        result.put("log", logDelta(logBefore));
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Map<?, ?> map) { return (Map<String, Object>) map; }
    static String truncate(String value) { return value.length() > 4000 ? value.substring(0, 4000) + "…" : value; }

    /** Macro Recorder text when the window is open, else the bridge's own journal. */
    Map<String, Object> recorder() {
        var result = new LinkedHashMap<String, Object>();
        Recorder recorder = Recorder.getInstance();
        String text = recorder == null ? null : recorder.getText();
        result.put("source", text != null ? "recorder" : "journal");
        result.put("recorderOpen", recorder != null);
        result.put("macro", text != null && !text.isBlank() ? text : journal.macro());
        result.put("steps", journal.steps(100)); result.put("journalSteps", journal.size());
        return result;
    }
    Map<String, Object> saveWorkflow(JsonObject args) throws IOException {
        var recorded = recorder(); String macro = String.valueOf(recorded.get("macro"));
        if (macro.isBlank()) throw new IllegalStateException("Nothing has been recorded yet; run an analysis step first.");
        String name = Operations.string(args, "name", "Luma workflow").strip(); if (name.isEmpty()) name = "Luma workflow";
        String root = LumaBridge.root();
        Path directory = Files.createDirectories(root != null ? Path.of(root, ".local", "workflows") : Path.of(System.getProperty("user.home"), "Luma workflows"));
        String slug = Operations.slug(name);
        Path path = directory.resolve(slug + ".ijm");
        for (int suffix = 2; Files.exists(path); suffix++) path = directory.resolve(slug + "-" + suffix + ".ijm");
        var imp = ij.WindowManager.getCurrentImage();
        String header = "// " + name + "\n// Generated by Luma from " + recorded.get("source") + " on " + java.time.ZonedDateTime.now() + "\n" + (imp == null ? "" : "// Image: " + imp.getTitle() + "\n") + "// Review parameters before applying to other images. Run with Plugins > Macros > Run... in Fiji.\n\n";
        Files.writeString(path, header + macro + (macro.endsWith("\n") ? "" : "\n"), StandardCharsets.UTF_8);
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name); result.put("path", path.toString()); result.put("steps", journal.size()); result.put("source", recorded.get("source")); result.put("macro", header + macro);
        result.put("howToRun", "Fiji → Plugins → Macros → Run…, or drag the .ijm file onto the Fiji window and press Run.");
        return result;
    }
}
