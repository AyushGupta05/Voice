package science.luma.fiji;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.MultiLineLabel;
import ij.macro.Interpreter;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import java.awt.Button;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Label;
import java.awt.TextComponent;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Macros the voice agent authors, tests, saves and replays: make_macro (write + test once on the active image),
 * workflows (list), run_workflow (active image, every open image, or a folder of images opened hidden) and
 * capture_macro (turn what Penny just did into a parameterised macro). Files are written only inside
 * {@code <root>/.local/workflows} and {@code <root>/.local/exports}; source images are opened read-only and never saved.
 * Macro errors and stray dialogs are captured as text and dismissed, so nothing modal is left open.
 */
final class Macros {
    static final List<String> EXTENSIONS = List.of("ijm", "groovy", "py", "js");
    static final String IMAGE_GLOB = "*.{tif,tiff,TIF,TIFF,png,PNG,jpg,jpeg,JPG,JPEG,gif,GIF,czi,CZI,nd2,ND2,lif,LIF,svs,SVS}";
    private static final String BEGIN = "---- Penny parameters", END = "---- end Penny parameters ----";
    private static final Pattern PARAM_LINE = Pattern.compile("^\\s*(?://|#)\\s*@param\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Pattern LINE_IN_MESSAGE = Pattern.compile("(?i)line\\s*:?\\s*(\\d+)");
    private static final long DEFAULT_TEST_MS = 60_000, DEFAULT_RUN_MS = 600_000;

    private final Scripting scripting;
    private final Journal journal;
    private final AtomicBoolean analysisRunning;
    private final Path root;

    /** Production: the workflow and export folders live under LUMA_ROOT (else the user's home, like save_workflow). */
    Macros(Scripting scripting, Journal journal, AtomicBoolean analysisRunning) { this(scripting, journal, analysisRunning, null); }
    /** {@code root} null means LumaBridge.root(); tests pass a scratch folder. */
    Macros(Scripting scripting, Journal journal, AtomicBoolean analysisRunning, Path root) {
        this.scripting = scripting; this.journal = journal; this.analysisRunning = analysisRunning; this.root = root;
    }

    Path workflowDirectory() throws IOException {
        Path base = root != null ? root : LumaBridge.root() != null ? Path.of(LumaBridge.root()) : null;
        return Files.createDirectories(base != null ? base.resolve(".local").resolve("workflows") : Path.of(System.getProperty("user.home"), "Luma workflows"));
    }
    Path exportDirectory() throws IOException {
        Path base = root != null ? root : LumaBridge.root() != null ? Path.of(LumaBridge.root()) : null;
        return Files.createDirectories(base != null ? base.resolve(".local").resolve("exports") : Path.of(System.getProperty("user.home"), "Luma exports"));
    }

    // =====================================================================================================
    // make_macro
    // =====================================================================================================

    Map<String, Object> makeMacro(JsonObject args) throws Exception { return makeMacro(args, currentImage()); }

    /** Writes (overwriting, previous kept as .prev) and, unless test:false, runs it once on {@code imp}. The dispatcher checkpoints first. */
    Map<String, Object> makeMacro(JsonObject args, ImagePlus imp) throws Exception {
        String name = Operations.string(args, "name", "").strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Provide a name for the macro.");
        String code = Operations.string(args, "code", "");
        if (code.isBlank()) throw new IllegalArgumentException("Provide the macro code.");
        if (code.length() > 200_000) throw new IllegalArgumentException("Macro code is too long.");
        String language = language(Operations.string(args, "language", "ijm"));
        String description = oneLine(Operations.string(args, "description", ""));
        var params = params(args.get("params"));
        boolean test = Operations.bool(args, "test", true);
        long timeoutMs = timeout(args, DEFAULT_TEST_MS);
        var saved = save(name, description, language, params, code, "Penny");
        var result = savedResult(saved, name, language, params);
        if (!test) { result.put("tested", false); result.put("ok", true); result.put("note", "Saved without running. Run it with run_workflow."); return result; }
        journal.record("make_macro", runMacroLine(saved.path(), language, argument(params)));
        var run = runOnce(saved.text(), language, imp, argument(params), false, timeoutMs);
        result.put("tested", true);
        putRun(result, run, saved.codeOffset());
        result.put("testedOn", imp == null ? null : imp.getTitle());
        if (!run.ok()) result.put("note", "Fix the code and call make_macro again with the same name; the previous version is kept as " + saved.slug() + ".prev." + language + ".");
        return result;
    }

    record Saved(Path path, Path previous, String slug, String text, int codeOffset, int lines) {}

    private Saved save(String name, String description, String language, LinkedHashMap<String, JsonPrimitive> params, String code, String createdBy) throws IOException {
        Path directory = workflowDirectory();
        String slug = slug(name);
        Path path = inside(directory, slug + "." + language);
        Path previous = null;
        if (Files.exists(path)) { previous = inside(directory, slug + ".prev." + language); Files.copy(path, previous, StandardCopyOption.REPLACE_EXISTING); }
        String header = header(name, description, language, params, createdBy, path);
        String prologue = prologue(language, params, params);
        String body = stripPennyHeader(code, language);
        String text = header + prologue + body + (body.endsWith("\n") ? "" : "\n");
        Path temporary = inside(directory, "." + slug + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        int offset = (int) (header + prologue).lines().count();
        return new Saved(path, previous, slug, text, offset, (int) text.lines().count());
    }
    private Map<String, Object> savedResult(Saved saved, String name, String language, Map<String, JsonPrimitive> params) {
        var result = new LinkedHashMap<String, Object>();
        result.put("path", saved.path().toString()); result.put("name", name); result.put("slug", saved.slug()); result.put("language", language);
        result.put("lines", saved.lines()); result.put("codeStartsAtLine", saved.codeOffset() + 1);
        result.put("params", plainParams(params));
        if (saved.previous() != null) result.put("previous", saved.previous().toString());
        return result;
    }

    // =====================================================================================================
    // workflows
    // =====================================================================================================

    Map<String, Object> workflows(JsonObject args) throws IOException {
        String query = Operations.string(args, "query", "").toLowerCase(Locale.ROOT).strip();
        Path directory = workflowDirectory();
        var rows = new ArrayList<Map<String, Object>>();
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile).filter(p -> EXTENSIONS.contains(extension(p))).filter(p -> !p.getFileName().toString().startsWith("."))
                .sorted(Comparator.comparing((Path p) -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; } }).reversed()).toList();
        }
        int total = 0;
        for (var file : files) {
            String text;
            try { text = Files.readString(file, StandardCharsets.UTF_8); } catch (IOException error) { continue; }
            var info = describe(file, text);
            String haystack = (info.get("name") + " " + info.get("file") + " " + info.get("description")).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            total++;
            if (rows.size() < 100) rows.add(info);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("folder", directory.toString()); result.put("workflows", rows); result.put("total", total); result.put("truncated", total > rows.size());
        return result;
    }
    private Map<String, Object> describe(Path file, String text) throws IOException {
        String fileName = file.getFileName().toString();
        String stem = fileName.substring(0, fileName.length() - extension(file).length() - 1);
        String name = null, description = "";
        var params = new LinkedHashMap<String, Object>();
        int scanned = 0;
        for (var line : text.lines().limit(80).toList()) {
            scanned++;
            String comment = line.strip().replaceFirst("^(//|#)\\s?", "");
            if (comment.startsWith("Penny macro:")) name = comment.substring("Penny macro:".length()).strip();
            else if (comment.startsWith("Description:")) description = comment.substring("Description:".length()).strip();
            else if (description.isEmpty() && name == null && scanned <= 3 && (line.strip().startsWith("//") || line.strip().startsWith("#")) && !comment.isBlank() && !comment.startsWith("@")) description = comment;
            var matcher = PARAM_LINE.matcher(line);
            if (matcher.matches()) { var value = parseValue(matcher.group(2)); if (value != null) params.put(matcher.group(1), plain(value)); }
        }
        var info = new LinkedHashMap<String, Object>();
        info.put("name", name == null ? stem : name); info.put("file", fileName); info.put("slug", stem); info.put("language", extension(file));
        info.put("description", description); info.put("params", params);
        info.put("modified", Files.getLastModifiedTime(file).toInstant().toString()); info.put("lines", text.lines().count());
        info.put("previousVersion", stem.endsWith(".prev"));
        return info;
    }

    // =====================================================================================================
    // run_workflow
    // =====================================================================================================

    Map<String, Object> runWorkflow(JsonObject args) throws Exception { return runWorkflow(args, currentImage()); }

    Map<String, Object> runWorkflow(JsonObject args, ImagePlus active) throws Exception {
        long started = System.currentTimeMillis();
        Path file = resolveWorkflow(Operations.string(args, "name", ""));
        String language = extension(file);
        String stored = Files.readString(file, StandardCharsets.UTF_8);
        var declared = declaredParams(stored);
        var overrides = params(args.get("params"));
        var effective = new LinkedHashMap<>(declared); effective.putAll(overrides);
        var unknown = overrides.keySet().stream().filter(key -> !declared.containsKey(key)).toList();
        String code = withParams(stored, language, declared, effective);
        String argument = argument(effective);
        int offset = codeOffset(code);
        String target = Operations.string(args, "target", "active").toLowerCase(Locale.ROOT);
        int limit = Operations.integer(args, "limit", 50, 1, 500);
        boolean saveResults = Operations.bool(args, "saveResults", true);
        long deadline = started + timeout(args, DEFAULT_RUN_MS);
        String slug = file.getFileName().toString().replaceFirst("\\.[A-Za-z]+$", "");

        var perImage = new ArrayList<Map<String, Object>>();
        var failed = new ArrayList<Map<String, Object>>();
        var csvRows = new ArrayList<Map<String, String>>();
        int processed = 0, matched; boolean timedOut = false; String folderText = null;
        switch (target) {
            case "active", "open" -> {
                List<ImagePlus> images;
                if (target.equals("active")) {
                    if (active == null) throw new IllegalArgumentException("No image is open; open one or use target:\"folder\".");
                    images = List.of(active);
                } else images = openImages(active);
                if (images.isEmpty()) throw new IllegalArgumentException("No images are open.");
                matched = images.size();
                journal.record("run_workflow", runMacroLine(file, language, argument) + (target.equals("open") ? " // on each of " + images.size() + " open images" : ""));
                for (var imp : images.subList(0, Math.min(limit, images.size()))) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) { timedOut = true; break; }
                    var run = runOnce(code, language, imp, argument, false, remaining);
                    if (run.timedOut()) timedOut = true;
                    collect(imp.getTitle(), run, offset, perImage, failed, csvRows);
                    if (run.ok()) processed++;
                    if (timedOut) break;
                }
            }
            case "folder" -> {
                Path folder = readableFolder(Operations.string(args, "folder", ""));
                folderText = folder.toString();
                String pattern = Operations.string(args, "pattern", IMAGE_GLOB);
                boolean recursive = Operations.bool(args, "recursive", false);
                var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                List<Path> files;
                try (var stream = recursive ? Files.walk(folder, 6) : Files.list(folder)) {
                    files = stream.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith(".")).filter(p -> matcher.matches(p.getFileName())).sorted().toList();
                }
                matched = files.size();
                if (files.isEmpty()) throw new IllegalArgumentException("No files in " + folder + " match " + pattern + ".");
                journal.record("run_workflow", "// run " + file.getFileName() + " on " + Math.min(limit, files.size()) + " images in " + folder + (argument.isEmpty() ? "" : " with \"" + Journal.escape(argument) + "\""));
                boolean wasBatch = Interpreter.batchMode;
                try {
                    for (var image : files.subList(0, Math.min(limit, files.size()))) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) { timedOut = true; break; }
                        String label = folder.relativize(image).toString();
                        ImagePlus imp;
                        try { imp = openHidden(image); }
                        catch (RuntimeException error) { failed.add(failure(label, Objects.toString(error.getMessage(), error.getClass().getSimpleName()), null)); continue; }
                        var run = runOnce(code, language, imp, argument, true, remaining);
                        if (run.timedOut()) timedOut = true;
                        collect(label, run, offset, perImage, failed, csvRows);
                        if (run.ok()) processed++;
                        if (timedOut) break;
                    }
                } finally { if (!wasBatch) Interpreter.batchMode = false; }
            }
            default -> throw new IllegalArgumentException("target must be active, open or folder.");
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("workflow", file.getFileName().toString()); result.put("path", file.toString()); result.put("language", language); result.put("target", target);
        if (folderText != null) result.put("folder", folderText);
        result.put("matched", matched); result.put("processed", processed); result.put("failed", failed.stream().limit(100).toList());
        result.put("perImage", perImage.stream().limit(100).toList());
        result.put("params", plainParams(effective));
        if (!unknown.isEmpty()) result.put("unknownParams", unknown);
        result.put("totalResultRows", csvRows.size());
        if (saveResults && !csvRows.isEmpty()) result.put("csv", writeCsv(slug, csvRows).toString());
        result.put("timedOut", timedOut);
        if (timedOut) result.put("note", "Stopped at the time limit; results above are partial.");
        if (matched > limit) result.put("limited", "Processed the first " + limit + " of " + matched + " matching images (limit).");
        result.put("durationMs", System.currentTimeMillis() - started);
        return result;
    }
    private void collect(String label, Run run, int offset, List<Map<String, Object>> perImage, List<Map<String, Object>> failed, List<Map<String, String>> csvRows) {
        var row = new LinkedHashMap<String, Object>();
        row.put("image", label); row.put("ok", run.ok()); row.put("resultsRows", run.rows().size());
        var log = run.log().lines().filter(line -> !line.isBlank()).toList();
        row.put("logTail", log.subList(Math.max(0, log.size() - 5), log.size()));
        row.put("summary", summary(run.rows()));
        if (!run.imagesOpened().isEmpty()) row.put("imagesOpened", run.imagesOpened());
        if (!run.ok()) { row.put("error", run.error()); failed.add(failure(label, run.error(), lineInCode(run.errorLine(), offset))); }
        perImage.add(row);
        for (var values : run.rows()) { var csv = new LinkedHashMap<String, String>(); csv.put("Source", label); csv.putAll(values); csvRows.add(csv); }
    }
    private static Map<String, Object> failure(String image, String error, Integer line) {
        var row = new LinkedHashMap<String, Object>(); row.put("image", image); row.put("error", error); if (line != null) row.put("errorLine", line); return row;
    }
    /** Mean of the first six numeric columns of the rows this image added. */
    static Map<String, Object> summary(List<Map<String, String>> rows) {
        var means = new LinkedHashMap<String, Object>();
        if (rows.isEmpty()) return means;
        for (var column : rows.getFirst().keySet()) {
            if (means.size() >= 6) break;
            double sum = 0; int count = 0; boolean numeric = true;
            for (var row : rows) {
                String value = row.get(column); if (value == null || value.isBlank()) continue;
                try { double number = Double.parseDouble(value); if (Double.isFinite(number)) { sum += number; count++; } } catch (NumberFormatException error) { numeric = false; break; }
            }
            if (numeric && count > 0) means.put(column, round(sum / count));
        }
        return means;
    }
    private Path writeCsv(String slug, List<Map<String, String>> rows) throws IOException {
        var columns = new LinkedHashSet<String>(); for (var row : rows) columns.addAll(row.keySet());
        var text = new StringBuilder();
        text.append(String.join(",", columns.stream().map(Macros::csv).toList())).append('\n');
        for (var row : rows) text.append(String.join(",", columns.stream().map(column -> csv(row.getOrDefault(column, ""))).toList())).append('\n');
        Path directory = exportDirectory();
        String base = slug + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path path = inside(directory, base + ".csv");
        for (int suffix = 2; Files.exists(path); suffix++) path = inside(directory, base + "-" + suffix + ".csv");
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }
    private static String csv(String value) { return value.matches(".*[,\"\\n\\r].*") || value.startsWith(" ") || value.endsWith(" ") ? "\"" + value.replace("\"", "\"\"") + "\"" : value; }

    // =====================================================================================================
    // capture_macro
    // =====================================================================================================

    Map<String, Object> macroFromRecorder(JsonObject args) throws Exception { return macroFromRecorder(args, currentImage()); }

    Map<String, Object> macroFromRecorder(JsonObject args, ImagePlus imp) throws Exception {
        String name = Operations.string(args, "name", "").strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Provide a name for the macro.");
        String raw;
        String source;
        if (args.has("lastSteps") && !args.get("lastSteps").isJsonNull()) {
            int last = Operations.integer(args, "lastSteps", 10, 1, 500);
            var lines = new StringBuilder();
            for (var step : journal.steps(last)) lines.append(step.get("macro")).append('\n');
            raw = lines.toString(); source = "journal (last " + last + " steps)";
        } else {
            var recorded = scripting.recorder();
            raw = String.valueOf(recorded.get("macro")); source = String.valueOf(recorded.get("source"));
        }
        if (raw.isBlank()) throw new IllegalStateException("Nothing has been recorded yet; run an analysis step first.");
        var cleaned = clean(raw);
        if (cleaned.code().isBlank()) throw new IllegalStateException("The recorded steps only opened, selected or closed images; there is nothing to replay.");
        var params = new LinkedHashMap<>(cleaned.params());
        params.putAll(params(args.get("params")));
        String description = oneLine(Operations.string(args, "description", "Captured from what Penny just did (" + source + ")."));
        var saved = save(name, description, "ijm", params, cleaned.code(), "Penny (captured from " + source + ")");
        var result = savedResult(saved, name, "ijm", params);
        result.put("source", source); result.put("removedLines", cleaned.removed()); result.put("code", cleaned.code());
        if (Operations.bool(args, "test", false)) {
            journal.record("capture_macro", runMacroLine(saved.path(), "ijm", argument(params)));
            var run = runOnce(saved.text(), "ijm", imp, argument(params), false, timeout(args, DEFAULT_TEST_MS));
            result.put("tested", true); putRun(result, run, saved.codeOffset());
        } else { result.put("tested", false); result.put("ok", true); }
        return result;
    }

    record Cleaned(String code, LinkedHashMap<String, JsonPrimitive> params, List<String> removed) {}
    private static final Pattern IMAGE_BOUND = Pattern.compile("^\\s*(open|selectImage|selectWindow|close|saveAs|save)\\s*\\(.*|^\\s*run\\(\"(Bio-Formats Importer|Blobs \\(25K\\)|Blobs|Close All)\".*|^\\s*//\\s*(restored|macro \\(|run_workflow|run .* on \\d+ images).*");
    private static final Pattern NUMERIC_OPTION = Pattern.compile("(?<![A-Za-z_])(sigma|radius|rolling|prominence|noise|circularity|size)=([0-9.]+)(?:-([0-9.]+|Infinity))?");

    /** Drops image-bound lines (open, selectImage, close, save) and lifts common numeric options into named parameters. */
    static Cleaned clean(String recorded) {
        var params = new LinkedHashMap<String, JsonPrimitive>();
        var removed = new ArrayList<String>();
        var out = new StringBuilder();
        for (var line : recorded.split("\n")) {
            String strip = line.strip();
            if (strip.isEmpty()) continue;
            if (IMAGE_BOUND.matcher(strip).matches()) { removed.add(strip); continue; }
            if (!strip.startsWith("run(") || !strip.contains("\", \"")) { out.append(line.stripTrailing()).append('\n'); continue; }
            var matcher = NUMERIC_OPTION.matcher(line);
            var rewritten = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1);
                String replacement;
                if (key.equals("size")) {
                    String min = param(params, "minSize", matcher.group(2)), upper = matcher.group(3);
                    if (upper == null) replacement = "size=\" + " + min + " + \"";
                    else if (upper.equals("Infinity")) replacement = "size=\" + " + min + " + \"-Infinity";
                    else replacement = "size=\" + " + min + " + \"-\" + " + param(params, "maxSize", upper) + " + \"";
                } else if (key.equals("circularity")) { replacement = matcher.group(0); }
                else replacement = key + "=\" + " + param(params, key, matcher.group(2)) + " + \"";
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(rewritten);
            out.append(rewritten.toString().replace(" + \"\"", "").replace("\"\" + ", "").stripTrailing()).append('\n');
        }
        return new Cleaned(out.toString(), params, removed);
    }
    private static String param(Map<String, JsonPrimitive> params, String name, String value) {
        double number = Double.parseDouble(value);
        var primitive = new JsonPrimitive(number == Math.rint(number) && Math.abs(number) < 1e12 ? (Number) (long) number : (Number) number);
        if (!params.containsKey(name)) { params.put(name, primitive); return name; }
        if (params.get(name).equals(primitive)) return name;
        for (int i = 2; ; i++) { String candidate = name + i; if (!params.containsKey(candidate)) { params.put(candidate, primitive); return candidate; } if (params.get(candidate).equals(primitive)) return candidate; }
    }

    // =====================================================================================================
    // Running: one image, errors and dialogs captured, nothing modal left open
    // =====================================================================================================

    record Run(boolean ok, boolean timedOut, String error, Integer errorLine, String log, List<Map<String, String>> rows, int resultsAdded, List<String> imagesOpened, String dialog, long elapsedMs) {}

    /**
     * Runs {@code code} once with {@code imp} as the worker's current image. In batch mode (folder runs) the image and anything the
     * code opens stay hidden and are closed without saving afterwards; interactive runs leave new images open.
     */
    Run runOnce(String code, String language, ImagePlus imp, String argument, boolean batch, long timeoutMs) throws Exception {
        String logBefore = Objects.toString(IJ.getLog(), "");
        var resultsBefore = ResultsState.of(Analyzer.getResultsTable());
        var imagesBefore = imageIds();
        if (imp != null) imagesBefore.remove(imp.getID());
        var before = UiTree.showing();
        String error = null; Integer errorLine = null; boolean timedOut = false; String dialog = null;
        long start = System.currentTimeMillis();
        try {
            if (language.equals("ijm")) {
                var interpreter = new Interpreter();
                var watched = watch(language, imp, interpreter, () -> batch ? interpreter.runBatchMacro(code, imp) : interpreter.run(code, argument), timeoutMs);
                timedOut = watched.timedOut(); dialog = watched.dialog();
                String message = interpreter.getErrorMessage();
                if (timedOut) error = "Timed out after " + timeoutMs / 1000 + " s; the macro was aborted.";
                else if (message != null && !message.isBlank()) { error = message.strip(); errorLine = lineFrom(dialog); if (errorLine == null && interpreter.getLineNumber() > 0) errorLine = interpreter.getLineNumber(); }
                else if (dialog != null) { error = "The macro stopped at a dialog, which was closed automatically (pass options to run() or remove the prompt): " + dialog; errorLine = lineFrom(dialog); }
                else if (watched.error() != null && !Objects.equals(watched.error().getMessage(), ij.Macro.MACRO_CANCELED)) error = describe(watched.error());
                else if ("[aborted]".equals(watched.value()) || watched.error() != null) error = "The macro was aborted" + (dialog == null ? "." : ": " + dialog);
            } else {
                var scriptArgs = new JsonObject();
                scriptArgs.addProperty("language", switch (language) { case "py" -> "python"; case "js" -> "javascript"; default -> language; });
                scriptArgs.addProperty("code", code);
                try {
                    var outcome = scripting.runScript(scriptArgs, imp, timeoutMs);
                    if (outcome.get("dialog") != null) { dialog = dismissNewDialogs(before); error = "The script opened a dialog that needed input and was cancelled: " + dialog; }
                    else if (Boolean.TRUE.equals(outcome.get("running"))) { timedOut = true; error = "Timed out after " + timeoutMs / 1000 + " s."; }
                    String stderr = Objects.toString(outcome.get("stderr"), "").strip();
                    if (!stderr.isEmpty()) IJ.log(stderr);
                } catch (IllegalStateException failure) { error = Objects.toString(failure.getMessage(), "Script failed").replaceFirst("^Script failed: ", ""); errorLine = lineFrom(error); }
            }
        } finally {
            String leftover = dismissNewDialogs(before);
            if (leftover != null && dialog == null) dialog = leftover;
        }
        var table = Analyzer.getResultsTable();
        var rows = resultsBefore.added(table);
        var opened = new ArrayList<String>();
        for (int id : imageIds()) if (!imagesBefore.contains(id) && (imp == null || id != imp.getID())) { var image = WindowManager.getImage(id); if (image != null) opened.add(image.getTitle()); }
        if (batch) closeBatchImages(imp, imagesBefore);
        String log = logDelta(logBefore);
        return new Run(error == null, timedOut, error, errorLine, log, rows, rows.size(), opened.stream().limit(50).toList(), dialog, System.currentTimeMillis() - start);
    }
    private void putRun(Map<String, Object> result, Run run, int offset) {
        result.put("ok", run.ok());
        if (!run.ok()) {
            result.put("error", run.error());
            Integer line = lineInCode(run.errorLine(), offset);
            if (line != null) result.put("errorLine", line);
            if (run.errorLine() != null) result.put("errorFileLine", run.errorLine());
        }
        if (run.timedOut()) result.put("timedOut", true);
        var log = run.log().lines().toList();
        result.put("log", String.join("\n", log.subList(Math.max(0, log.size() - 40), log.size())));
        result.put("resultsAdded", run.resultsAdded());
        result.put("results", Map.of("rows", run.rows().size(), "summary", summary(run.rows())));
        result.put("imagesOpened", run.imagesOpened());
        if (run.dialog() != null) result.put("dialogDismissed", run.dialog());
        result.put("elapsedMs", run.elapsedMs());
    }
    private static Integer lineInCode(Integer fileLine, int offset) { return fileLine == null ? null : fileLine - offset > 0 ? fileLine - offset : null; }

    record Watched(Object value, Throwable error, boolean timedOut, String dialog) {}

    /** Runs work on a daemon thread; a dialog that stays up 300 ms is read, the macro is aborted and the dialog is cancelled/closed. */
    private Watched watch(String label, ImagePlus imp, Interpreter interpreter, Callable<Object> work, long timeoutMs) throws InterruptedException {
        var before = UiTree.showing();
        var holder = new Object[2]; var done = new AtomicBoolean();
        var thread = new Thread(() -> {
            if (imp != null) WindowManager.setTempCurrentImage(imp);
            try { holder[0] = work.call(); } catch (Throwable error) { holder[1] = error; }
            finally { if (imp != null) WindowManager.setTempCurrentImage(null); done.set(true); }
        }, "luma-macros-" + label);
        thread.setDaemon(true); analysisRunning.set(true);
        long start = System.currentTimeMillis();
        String dialog = null; boolean timedOut = false;
        try {
            thread.start();
            Window seen = null; long seenAt = 0; long graceUntil = Long.MAX_VALUE;
            while (!done.get()) {
                long now = System.currentTimeMillis();
                if (now > graceUntil) break;
                if (!timedOut && now - start > timeoutMs) {
                    timedOut = true; abort(interpreter); thread.interrupt();
                    String closed = dismissNewDialogs(before); if (dialog == null) dialog = closed;
                    graceUntil = now + 3000;
                }
                var dialogs = UiTree.newDialogs(before).stream().filter(window -> window instanceof Dialog).toList();
                if (!dialogs.isEmpty()) {
                    var current = dialogs.getLast();
                    if (current != seen) { seen = current; seenAt = now; }
                    else if (now - seenAt > 300) {
                        String text = dialogText(current);
                        if (dialog == null) dialog = text;
                        abort(interpreter);
                        dismiss(current);
                        if (graceUntil == Long.MAX_VALUE) graceUntil = now + 5000;
                        seen = null;
                    }
                } else seen = null;
                TimeUnit.MILLISECONDS.sleep(30);
            }
        } finally { analysisRunning.set(false); }
        return new Watched(holder[0], (Throwable) holder[1], timedOut, dialog);
    }
    private static void abort(Interpreter interpreter) {
        // abortMacro() also aborts every other macro thread; flag only this interpreter as done so it stops at the next statement.
        if (interpreter == null) return;
        try { var done = Interpreter.class.getDeclaredField("done"); done.setAccessible(true); done.setBoolean(interpreter, true); }
        catch (ReflectiveOperationException | RuntimeException ignored) { /* stops when the dialog closes */ }
    }
    /** Cancels (GenericDialog) or closes every dialog that appeared since {@code before}; returns the first one's text. */
    static String dismissNewDialogs(Set<Window> before) {
        String first = null;
        for (int round = 0; round < 3; round++) {
            var dialogs = UiTree.newDialogs(before).stream().filter(window -> window instanceof Dialog).toList();
            if (dialogs.isEmpty()) break;
            for (var window : dialogs) { if (first == null) first = dialogText(window); dismiss(window); }
            try { TimeUnit.MILLISECONDS.sleep(60); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        return first;
    }
    private static void dismiss(Window window) {
        Runnable close = () -> {
            if (window instanceof GenericDialog generic) {
                Button cancel = findButton(window, "Cancel");
                if (cancel != null) { try { generic.actionPerformed(new ActionEvent(cancel, ActionEvent.ACTION_PERFORMED, "Cancel")); } catch (RuntimeException ignored) { /* fall through to dispose */ } }
            }
            if (window.isShowing()) window.dispose();
        };
        try { if (EventQueue.isDispatchThread()) close.run(); else EventQueue.invokeAndWait(close); }
        catch (Exception error) { window.dispose(); }
    }
    private static Button findButton(Container container, String label) {
        for (var child : container.getComponents()) {
            if (child instanceof Button button && label.equalsIgnoreCase(button.getLabel())) return button;
            if (child instanceof Container inner) { var found = findButton(inner, label); if (found != null) return found; }
        }
        return null;
    }
    static String dialogText(Window window) {
        var parts = new ArrayList<String>();
        String title = UiTree.title(window); if (!title.isBlank()) parts.add(title + ":");
        collectText(window, parts);
        String text = String.join(" ", parts).replaceAll("\\s+", " ").strip();
        return text.length() > 1000 ? text.substring(0, 1000) + "…" : text;
    }
    private static void collectText(Component component, List<String> parts) {
        if (component instanceof MultiLineLabel label) {
            try { var field = MultiLineLabel.class.getDeclaredField("lines"); field.setAccessible(true); var lines = (String[]) field.get(label); if (lines != null) for (var line : lines) if (line != null && !line.isBlank()) parts.add(line.strip()); }
            catch (ReflectiveOperationException | RuntimeException ignored) { /* text unavailable */ }
        } else if (component instanceof Label label && label.getText() != null && !label.getText().isBlank()) parts.add(label.getText().strip());
        else if (component instanceof javax.swing.JLabel label && label.getText() != null && !label.getText().isBlank()) parts.add(label.getText().replaceAll("<[^>]+>", " ").strip());
        else if (component instanceof TextComponent field && !field.getText().isBlank()) parts.add(field.getText().strip());
        else if (component instanceof javax.swing.text.JTextComponent field && !field.getText().isBlank()) parts.add(field.getText().strip());
        if (component instanceof Container container) for (var child : container.getComponents()) collectText(child, parts);
    }
    private static Integer lineFrom(String text) {
        if (text == null) return null;
        var matcher = LINE_IN_MESSAGE.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }
    private static String describe(Throwable error) {
        var cause = error.getCause() != null ? error.getCause() : error;
        return Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
    }

    /**
     * Snapshot of the Results table, to tell rows a run appended from a table it cleared and rewrote. Row contents alone cannot
     * tell them apart: with Set Measurements limited to e.g. Area/Mean/Min/Max (an ImageJ preference the live Fiji persists),
     * equal-sized particles give identical rows, so a cleared table whose first and last rows happen to match looked appended and
     * only the rows past the old size were reported. ResultsTable.reset() (Analyze Particles "clear", run("Clear Results"))
     * drops every column array, while appending writes into the same arrays until they grow past maxRows, so the identity of the
     * column arrays decides; row contents are only the fallback when that is not observable.
     */
    record ResultsState(ResultsTable table, int size, int capacity, Object[] columns, int contentHash) {
        static ResultsState of(ResultsTable table) {
            int size = table == null ? 0 : table.size();
            if (table == null) return new ResultsState(null, 0, 0, null, 0);
            synchronized (table) { return new ResultsState(table, size, capacity(table), columnArrays(table), contentHash(table, size)); }
        }
        /** 1 = the table kept every earlier row (appended), 0 = it was cleared/rewritten, -1 = cannot tell from array identity. */
        private int sameStorage(ResultsTable now) {
            if (columns == null) return -1;
            Object[] current = columnArrays(now);
            if (current == null) return -1;
            boolean any = false;
            for (int c = 0; c < columns.length; c++) {
                if (columns[c] == null) continue;
                any = true;
                if (c < current.length && current[c] == columns[c]) return 1;
            }
            if (!any) return -1;
            // Every old column array was replaced: a reset, unless the table grew past its capacity (growth copies the arrays).
            return now.size() > capacity && capacity > 0 ? -1 : 0;
        }
        List<Map<String, String>> added(ResultsTable now) {
            if (now == null || now.size() == 0) return List.of();
            boolean appended;
            if (now != table || now.size() < size) appended = false;
            else if (size == 0) appended = true;
            else {
                int storage; synchronized (now) { storage = sameStorage(now); }
                appended = storage == 1 || storage == -1 && contentHash(now, size) == contentHash;
            }
            int from = appended ? size : 0;
            var headings = Arrays.stream(now.getHeadings()).filter(h -> h != null && !h.isBlank()).toList();
            var rows = new ArrayList<Map<String, String>>();
            for (int r = from; r < now.size() && rows.size() < 100_000; r++) {
                var row = new LinkedHashMap<String, String>();
                for (var heading : headings) {
                    String value;
                    if (heading.equals("Label")) value = Objects.toString(now.getLabel(r), "");
                    else { try { value = Objects.toString(now.getStringValue(heading, r), ""); } catch (RuntimeException error) { value = ""; } }
                    row.put(heading, value);
                }
                rows.add(row);
            }
            return rows;
        }
        private static String rowText(ResultsTable table, int row) { try { return Objects.toString(table.getRowAsString(row), ""); } catch (RuntimeException error) { return ""; } }
        private static int contentHash(ResultsTable table, int rows) {
            int hash = 1;
            for (int r = 0; r < Math.min(rows, table.size()); r++) hash = 31 * hash + rowText(table, r).hashCode();
            return hash;
        }
        private static Object[] columnArrays(ResultsTable table) {
            try { var field = ResultsTable.class.getDeclaredField("columns"); field.setAccessible(true); var arrays = (Object[]) field.get(table); return arrays == null ? null : arrays.clone(); }
            catch (ReflectiveOperationException | RuntimeException error) { return null; }
        }
        private static int capacity(ResultsTable table) {
            try { var field = ResultsTable.class.getDeclaredField("maxRows"); field.setAccessible(true); return field.getInt(table); }
            catch (ReflectiveOperationException | RuntimeException error) { return 0; }
        }
    }

    // =====================================================================================================
    // Images
    // =====================================================================================================

    private static ImagePlus currentImage() throws Exception { return LumaBridge.edt(WindowManager::getCurrentImage); }
    private static Set<Integer> imageIds() {
        var ids = new HashSet<Integer>();
        var list = WindowManager.getIDList(); if (list != null) for (int id : list) ids.add(id);
        try { for (int id : Interpreter.getBatchModeImageIDs()) ids.add(id); } catch (RuntimeException ignored) { /* no batch images */ }
        return ids;
    }
    private static List<ImagePlus> openImages(ImagePlus active) {
        var images = new ArrayList<ImagePlus>();
        var list = WindowManager.getIDList();
        if (list != null) for (int id : list) { var imp = WindowManager.getImage(id); if (imp != null) images.add(imp); }
        if (active != null && !images.contains(active)) images.addFirst(active);
        return images;
    }
    /** Opens a source image without a window (ImageJ reader, then Bio-Formats when installed). The file itself is never written. */
    static ImagePlus openHidden(Path path) {
        ImagePlus imp = null;
        try { imp = IJ.openImage(path.toString()); } catch (RuntimeException ignored) { /* try Bio-Formats */ }
        if (imp == null) {
            try {
                var bf = Class.forName("loci.plugins.BF", true, IJ.getClassLoader());
                var images = (ImagePlus[]) bf.getMethod("openImagePlus", String.class).invoke(null, path.toString());
                if (images != null && images.length > 0) { imp = images[0]; for (int i = 1; i < images.length; i++) images[i].close(); }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException error) { /* reported below */ }
        }
        if (imp == null) throw new IllegalStateException("Neither ImageJ nor Bio-Formats could open " + path.getFileName() + ".");
        return imp;
    }
    /** Closes, without saving, the folder image and everything the macro opened while it ran. */
    private static void closeBatchImages(ImagePlus imp, Set<Integer> before) {
        var targets = new LinkedHashSet<ImagePlus>();
        if (imp != null) targets.add(imp);
        for (int id : imageIds()) if (!before.contains(id)) { var image = WindowManager.getImage(id); if (image != null) targets.add(image); }
        for (var image : targets) {
            try { Interpreter.removeBatchModeImage(image); } catch (RuntimeException ignored) { /* not a batch image */ }
            image.changes = false;
            try { if (image.getWindow() != null) { var window = image.getWindow(); EventQueue.invokeAndWait(() -> { image.changes = false; window.close(); }); } else image.close(); }
            catch (Exception error) { image.close(); }
        }
    }

    // =====================================================================================================
    // Files, headers and parameters
    // =====================================================================================================

    static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 60) slug = slug.substring(0, 60).replaceAll("-+$", "");
        return slug.isEmpty() ? "macro" : slug;
    }
    private static Path inside(Path directory, String fileName) {
        Path path = directory.resolve(fileName).normalize();
        if (!path.getParent().equals(directory.normalize())) throw new IllegalArgumentException("Workflow files stay inside " + directory + ".");
        return path;
    }
    Path resolveWorkflow(String requested) throws IOException {
        String name = requested.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Provide the workflow name (see workflows).");
        if (name.contains("/") || name.contains("\\") || name.contains("..")) throw new IllegalArgumentException("Give the workflow's name or file name, not a path.");
        Path directory = workflowDirectory();
        if (EXTENSIONS.contains(extension(Path.of(name)))) { Path path = inside(directory, name); if (Files.isRegularFile(path)) return path; }
        String slug = slug(name.replaceFirst("\\.(ijm|groovy|py|js)$", ""));
        for (var extension : EXTENSIONS) { Path path = inside(directory, slug + "." + extension); if (Files.isRegularFile(path)) return path; }
        throw new IllegalArgumentException("No saved workflow called \"" + name + "\" in " + directory + ". List them with workflows.");
    }
    private static Path readableFolder(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Provide folder: an absolute path to a folder of images.");
        Path path = Path.of(value);
        if (!path.isAbsolute() || !Files.isDirectory(path) || !Files.isReadable(path)) throw new IllegalArgumentException("folder must be an existing readable absolute folder path.");
        try { return path.toRealPath(); } catch (IOException error) { throw new IllegalArgumentException("Cannot resolve folder: " + error.getMessage()); }
    }
    static String extension(Path path) { String name = path.getFileName().toString(); int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    static String language(String value) {
        return switch (value.toLowerCase(Locale.ROOT).strip()) {
            case "ijm", "macro", "imagej", "imagej macro", "" -> "ijm";
            case "groovy" -> "groovy";
            case "python", "jython", "py" -> "py";
            default -> throw new IllegalArgumentException("language must be ijm (ImageJ macro), groovy or python.");
        };
    }
    private static String comment(String language) { return language.equals("py") ? "#" : "//"; }
    private static String oneLine(String value) { String line = value.replaceAll("[\\r\\n]+", " ").strip(); return line.length() > 200 ? line.substring(0, 200) : line; }
    private static long timeout(JsonObject args, long fallback) { return Operations.integer(args, "timeoutSeconds", (int) (fallback / 1000), 1, 3600) * 1000L; }

    static LinkedHashMap<String, JsonPrimitive> params(JsonElement element) {
        var params = new LinkedHashMap<String, JsonPrimitive>();
        if (element == null || element.isJsonNull()) return params;
        if (!element.isJsonObject()) throw new IllegalArgumentException("params must be an object of name → default value.");
        for (var entry : element.getAsJsonObject().entrySet()) {
            if (!IDENTIFIER.matcher(entry.getKey()).matches()) throw new IllegalArgumentException("Parameter names must be identifiers (letters, digits, _): " + entry.getKey());
            var value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("Parameter " + entry.getKey() + " must be a number, string or boolean.");
            var primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber() && !Double.isFinite(primitive.getAsDouble())) throw new IllegalArgumentException("Parameter " + entry.getKey() + " must be finite.");
            if (primitive.isString() && primitive.getAsString().matches(".*[\\]\\r\\n].*")) throw new IllegalArgumentException("Parameter " + entry.getKey() + " may not contain ] or line breaks.");
            params.put(entry.getKey(), primitive);
        }
        if (params.size() > 40) throw new IllegalArgumentException("At most 40 parameters.");
        return params;
    }
    private static Map<String, Object> plainParams(Map<String, JsonPrimitive> params) { var out = new LinkedHashMap<String, Object>(); params.forEach((k, v) -> out.put(k, plain(v))); return out; }
    private static Object plain(JsonPrimitive value) {
        if (value.isBoolean()) return value.getAsBoolean();
        if (value.isNumber()) { double number = value.getAsDouble(); return number == Math.rint(number) && Math.abs(number) < 1e15 ? (Object) (long) number : (Object) number; }
        return value.getAsString();
    }
    private static String text(JsonPrimitive value) {
        if (value.isNumber()) return Operations.plain(value.getAsDouble());
        return value.getAsString();
    }
    private static JsonPrimitive parseValue(String literal) {
        try { var element = JsonParser.parseString(literal.strip()); return element.isJsonPrimitive() ? element.getAsJsonPrimitive() : null; }
        catch (RuntimeException error) { return null; }
    }
    /** "minSize=50 method=[Otsu dark]" — the argument string a saved macro parses with getArgument(). */
    static String argument(Map<String, JsonPrimitive> params) {
        var parts = new ArrayList<String>();
        params.forEach((key, value) -> { String text = text(value); parts.add(key + "=" + (text.isEmpty() || text.contains(" ") ? "[" + text + "]" : text)); });
        return String.join(" ", parts);
    }
    private static String runMacroLine(Path path, String language, String argument) {
        if (!language.equals("ijm")) return "// run " + language + " script " + path.getFileName();
        return argument.isEmpty() ? "runMacro(\"" + Journal.escape(path.toString()) + "\");" : "runMacro(\"" + Journal.escape(path.toString()) + "\", \"" + Journal.escape(argument) + "\");";
    }

    private static String header(String name, String description, String language, Map<String, JsonPrimitive> params, String createdBy, Path path) {
        String c = comment(language);
        var text = new StringBuilder();
        text.append(c).append(" Penny macro: ").append(oneLine(name)).append('\n');
        if (!description.isBlank()) text.append(c).append(" Description: ").append(description).append('\n');
        text.append(c).append(" Created by ").append(createdBy).append(" on ").append(ZonedDateTime.now().withNano(0)).append('\n');
        text.append(c).append(" Language: ").append(language.equals("ijm") ? "ImageJ macro" : language.equals("py") ? "python (Jython)" : language).append('\n');
        for (var entry : params.entrySet()) text.append(c).append(" @param ").append(entry.getKey()).append(" = ").append(entry.getValue().toString()).append('\n');
        if (language.equals("ijm")) text.append(c).append(" Run: runMacro(\"").append(Journal.escape(path.toString())).append("\"").append(params.isEmpty() ? "" : ", \"" + Journal.escape(argument(params)) + "\"").append(");\n");
        text.append('\n');
        return text.toString();
    }
    /** The parameter block. {@code declared} fixes the line layout; {@code values} supplies the defaults written into it. */
    static String prologue(String language, Map<String, JsonPrimitive> declared, Map<String, JsonPrimitive> values) {
        if (declared.isEmpty()) return "";
        String c = comment(language);
        var text = new StringBuilder();
        if (language.equals("ijm")) {
            text.append(c).append(' ').append(BEGIN).append(": pass key=value (or key=[text with spaces]) as the macro argument ----\n");
            for (var key : declared.keySet()) {
                var value = values.getOrDefault(key, declared.get(key));
                String call = "pennyParam(\"" + key + "\", \"" + Journal.escape(text(value)) + "\")";
                text.append(key).append(" = ").append(value.isNumber() ? "parseFloat(" + call + ")" : value.isBoolean() ? call + " == \"true\"" : call).append(";\n");
            }
            text.append("function pennyParam(key, fallback) { a = \" \" + getArgument() + \" \"; i = indexOf(a, \" \" + key + \"=\"); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, \"[\")) return substring(v, 1, indexOf(v, \"]\")); return substring(v, 0, indexOf(v, \" \")); }\n");
        } else {
            text.append(c).append(' ').append(BEGIN).append(": run_workflow params override these defaults ----\n");
            for (var key : declared.keySet()) {
                var value = values.getOrDefault(key, declared.get(key));
                String literal = value.isBoolean() ? (language.equals("py") ? (value.getAsBoolean() ? "True" : "False") : String.valueOf(value.getAsBoolean()))
                    : value.isNumber() ? Operations.plain(value.getAsDouble()) : "\"" + Journal.escape(value.getAsString()) + "\"";
                text.append(key).append(" = ").append(literal).append('\n');
            }
        }
        text.append(c).append(' ').append(END).append("\n\n");
        return text.toString();
    }
    static LinkedHashMap<String, JsonPrimitive> declaredParams(String text) {
        var params = new LinkedHashMap<String, JsonPrimitive>();
        for (var line : text.lines().limit(80).toList()) { var matcher = PARAM_LINE.matcher(line); if (matcher.matches()) { var value = parseValue(matcher.group(2)); if (value != null) params.put(matcher.group(1), value); } }
        return params;
    }
    /** Rewrites the stored parameter block with run-time values (same line count, so error lines stay valid). */
    static String withParams(String stored, String language, Map<String, JsonPrimitive> declared, Map<String, JsonPrimitive> values) {
        if (declared.isEmpty()) return stored;
        int begin = stored.indexOf(comment(language) + " " + BEGIN);
        int end = stored.indexOf(END, Math.max(0, begin));
        if (begin < 0 || end < 0) return stored;
        int after = stored.indexOf('\n', end); after = after < 0 ? stored.length() : after + 1;
        if (after < stored.length() && stored.charAt(after) == '\n') after++;
        return stored.substring(0, begin) + prologue(language, declared, values) + stored.substring(after);
    }
    private static int codeOffset(String text) {
        int index = text.indexOf(END);
        if (index >= 0) return (int) text.substring(0, index).lines().count() + 2;
        int count = 0;
        for (var line : text.lines().toList()) { String strip = line.strip(); if (strip.startsWith("//") || strip.startsWith("#") || strip.isEmpty()) count++; else break; }
        return count;
    }
    /** When the agent resubmits a whole file it read back, keep only the code after Penny's header and parameter block. */
    private static String stripPennyHeader(String code, String language) {
        String c = comment(language);
        if (!code.stripLeading().startsWith(c + " Penny macro:")) return code;
        int end = code.indexOf(END);
        if (end >= 0) { int after = code.indexOf('\n', end); return after < 0 ? "" : code.substring(after + 1).replaceFirst("^\\n", ""); }
        var lines = code.lines().toList(); int i = 0;
        while (i < lines.size() && (lines.get(i).strip().startsWith(c) || lines.get(i).isBlank())) i++;
        return String.join("\n", lines.subList(i, lines.size()));
    }

    private static String logDelta(String before) {
        String after = Objects.toString(IJ.getLog(), "");
        String delta = after.startsWith(before) ? after.substring(before.length()) : after;
        return delta.length() > 8000 ? delta.substring(delta.length() - 8000) : delta;
    }
    private static double round(double value) { return Math.round(value * 10_000d) / 10_000d; }
}
