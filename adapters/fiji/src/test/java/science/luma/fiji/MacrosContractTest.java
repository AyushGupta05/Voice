package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.OvalRoi;
import ij.process.ByteProcessor;
import java.awt.Dialog;
import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Windowless ImageJ (NO_SHOW): make_macro, error capture without dialogs, parameters, workflows, run_workflow over a folder, capture_macro. Writes only to a temporary root. */
public final class MacrosContractTest {
    public static void main(String[] ignored) throws Exception {
        new ImageJ(ImageJ.NO_SHOW);
        ij.Prefs.blackBackground = true;
        Path root = Files.createTempDirectory("luma-macros-test");
        try { run(root); } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.out.println("Macros contract (make_macro, errors without dialogs, params, workflows, run_workflow folder, capture_macro): passed");
        System.exit(0);
    }

    static ImagePlus blobs(String title, int columns) {
        var processor = new ByteProcessor(256, 256);
        processor.setValue(20); processor.fill();
        for (int row = 0; row < 3; row++) for (int column = 0; column < columns; column++) {
            int cx = 40 + column * 56, cy = 48 + row * 72, r = (row + column) % 2 == 0 ? 8 : 12;
            processor.setValue(200 + row * 10); processor.fill(new OvalRoi(cx - r, cy - r, 2 * r, 2 * r));
        }
        return new ImagePlus(title, processor);
    }

    static void run(Path root) throws Exception {
        var journal = new Journal();
        var running = new AtomicBoolean();
        var scripting = new Scripting(journal, new UiTree(), running);
        var macros = new Macros(scripting, journal, running, root);
        var imp = blobs("synthetic-blobs.tif", 4);
        WindowManager.setTempCurrentImage(imp);
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(imp));

        // 1. valid macro: threshold + count
        var make = new JsonObject();
        make.addProperty("name", "Count nuclei");
        make.addProperty("description", "Threshold and count bright nuclei");
        make.addProperty("code", "run(\"Convert to Mask\");\nrun(\"Analyze Particles...\", \"display clear\");\nprint(\"counted \" + nResults);");
        var made = macros.makeMacro(make, imp);
        expect(Boolean.TRUE.equals(made.get("ok")), "valid macro ok: " + made);
        expect(((Number) made.get("resultsAdded")).intValue() == 12, "12 nuclei counted: " + made.get("resultsAdded"));
        Path file = Path.of((String) made.get("path"));
        expect(Files.isRegularFile(file) && file.startsWith(root.resolve(".local/workflows")) && file.getFileName().toString().equals("count-nuclei.ijm"), "saved in the workflow folder: " + file);
        expect(Files.readString(file).startsWith("// Penny macro: Count nuclei"), "header written");
        expect(String.valueOf(made.get("log")).contains("counted 12"), "log captured: " + made.get("log"));
        pass("make_macro valid IJM: ok, 12 rows, file " + file.getFileName());

        // 2. syntax error: clean message, line number, no dialog left, previous version kept
        var broken = new JsonObject();
        broken.addProperty("name", "Count nuclei");
        broken.addProperty("code", "x = 1;\nrun(\"Convert to Mask\";\nprint(x);");
        var failed = macros.makeMacro(broken, imp);
        expect(Boolean.FALSE.equals(failed.get("ok")), "syntax error is ok=false: " + failed);
        expect(failed.get("error") instanceof String message && !message.isBlank(), "error message present: " + failed.get("error"));
        expect(failed.get("errorLine") instanceof Integer line && (line == 2 || line == 3), "error line points at the broken statement (2, or 3 where ImageJ notices the missing parenthesis): " + failed.get("errorLine") + " / file line " + failed.get("errorFileLine"));
        expect(noDialogs(), "no dialog windows left open: " + dialogs());
        expect(Files.isRegularFile(root.resolve(".local/workflows/count-nuclei.prev.ijm")), "previous version kept as .prev");
        pass("make_macro syntax error: \"" + failed.get("error") + "\" at line " + failed.get("errorLine") + ", no dialogs");

        // 2b. runtime error from a command (Analyze Particles needs a binary/thresholded image) on a fresh image
        var grey = blobs("grey.tif", 4);
        var runtime = new JsonObject();
        runtime.addProperty("name", "Undefined variable");
        runtime.addProperty("code", "print(\"start\");\ny = undefinedThing + 1;");
        var undefinedRun = macros.makeMacro(runtime, grey);
        expect(Boolean.FALSE.equals(undefinedRun.get("ok")) && Integer.valueOf(2).equals(undefinedRun.get("errorLine")), "undefined variable reported at line 2: " + undefinedRun);
        expect(noDialogs(), "no dialog windows after runtime error: " + dialogs());
        pass("make_macro runtime error: \"" + undefinedRun.get("error") + "\" at line 2, no dialogs");

        // 3. parameters: default minSize 300 keeps the 6 large discs; run_workflow with minSize 50 counts all 12
        var param = new JsonObject();
        param.addProperty("name", "Count nuclei");
        param.addProperty("code", "run(\"Convert to Mask\");\nrun(\"Analyze Particles...\", \"size=\" + minSize + \"-Infinity display clear\");");
        var defaults = new JsonObject(); defaults.addProperty("minSize", 300); param.add("params", defaults);
        var paramImage = blobs("params.tif", 4);
        var withParams = macros.makeMacro(param, paramImage);
        expect(Boolean.TRUE.equals(withParams.get("ok")) && ((Number) withParams.get("resultsAdded")).intValue() == 6, "default minSize=300 keeps 6: " + withParams);
        expect(Files.readString(file).contains("// @param minSize = 300"), "@param header written");
        var replay = new JsonObject(); replay.addProperty("name", "count-nuclei"); replay.addProperty("target", "active");
        var override = new JsonObject(); override.addProperty("minSize", 50); replay.add("params", override);
        var replayed = macros.runWorkflow(replay, blobs("replay.tif", 4));
        expect(((Number) replayed.get("processed")).intValue() == 1, "active replay processed: " + replayed);
        @SuppressWarnings("unchecked") var first = ((List<Map<String, Object>>) replayed.get("perImage")).getFirst();
        expect(((Number) first.get("resultsRows")).intValue() == 12, "minSize=50 override counts 12: " + first);
        // the getArgument() prologue also works when Fiji runs the saved file directly
        IJ.log("\\Clear");
        var direct = blobs("direct.tif", 4);
        WindowManager.setTempCurrentImage(direct);
        IJ.runMacroFile(file.toString(), "minSize=300");
        expect(ij.plugin.filter.Analyzer.getResultsTable().size() == 6, "runMacroFile with minSize=300 argument counts 6: " + ij.plugin.filter.Analyzer.getResultsTable().size());
        WindowManager.setTempCurrentImage(imp);
        pass("params: default 300 → 6, override 50 → 12, runMacroFile argument → 6");

        // 4. workflows lists it
        var listed = macros.workflows(new JsonObject());
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) listed.get("workflows");
        var entry = rows.stream().filter(row -> "count-nuclei.ijm".equals(row.get("file"))).findFirst().orElse(null);
        expect(entry != null && "Count nuclei".equals(entry.get("name")) && ((Map<?, ?>) entry.get("params")).containsKey("minSize"), "workflows lists count-nuclei with params: " + rows);
        var query = new JsonObject(); query.addProperty("query", "nothing-matches-this");
        expect(((List<?>) macros.workflows(query).get("workflows")).isEmpty(), "query filters");
        pass("workflows: " + rows.size() + " files, count-nuclei has params " + entry.get("params"));

        // 5. folder run over three generated TIFFs; sources untouched, CSV with Source column
        Path folder = Files.createDirectories(root.resolve("images"));
        var sizes = new ArrayList<Long>();
        for (int i = 1; i <= 3; i++) { Path tif = folder.resolve("blobs-" + i + ".tif"); IJ.saveAsTiff(blobs("blobs-" + i, i + 1), tif.toString()); sizes.add(Files.size(tif)); }
        Files.writeString(folder.resolve("notes.txt"), "not an image");
        long modifiedBefore = Files.getLastModifiedTime(folder.resolve("blobs-1.tif")).toMillis();
        var batch = new JsonObject(); batch.addProperty("name", "Count nuclei"); batch.addProperty("target", "folder"); batch.addProperty("folder", folder.toString());
        var batchParams = new JsonObject(); batchParams.addProperty("minSize", 50); batch.add("params", batchParams);
        int imagesBefore = WindowManager.getImageCount();
        var folderRun = macros.runWorkflow(batch, imp);
        expect(((Number) folderRun.get("processed")).intValue() == 3 && ((List<?>) folderRun.get("failed")).isEmpty(), "folder processed 3: " + folderRun);
        @SuppressWarnings("unchecked") var perImage = (List<Map<String, Object>>) folderRun.get("perImage");
        expect(((Number) perImage.get(0).get("resultsRows")).intValue() == 6 && ((Number) perImage.get(2).get("resultsRows")).intValue() == 12, "per-image counts 6/9/12: " + perImage);
        Path csv = Path.of((String) folderRun.get("csv"));
        var csvLines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        expect(Files.isRegularFile(csv) && csv.startsWith(root.resolve(".local/exports")) && csvLines.getFirst().startsWith("Source,") && csvLines.size() == 1 + 6 + 9 + 12, "csv with Source column and 27 rows: " + csv + " " + csvLines.size());
        expect(csvLines.get(1).startsWith("blobs-1.tif,"), "rows tagged with source image: " + csvLines.get(1));
        for (int i = 1; i <= 3; i++) expect(Files.size(folder.resolve("blobs-" + i + ".tif")) == sizes.get(i - 1), "source image " + i + " untouched");
        expect(Files.getLastModifiedTime(folder.resolve("blobs-1.tif")).toMillis() == modifiedBefore, "source not rewritten");
        expect(WindowManager.getImageCount() == imagesBefore && !ij.macro.Interpreter.isBatchMode(), "no images left open and batch mode off");
        expect(noDialogs(), "no dialogs after folder run: " + dialogs());
        pass("run_workflow folder: processed 3, rows 6/9/12, csv " + csv.getFileName() + " (" + (csvLines.size() - 1) + " rows), sources untouched, " + folderRun.get("durationMs") + " ms");

        // 6. folder run with a macro that fails on one image reports partial results
        var picky = new JsonObject(); picky.addProperty("name", "Picky"); picky.addProperty("test", false);
        picky.addProperty("code", "if (getWidth() > 0 && nImages > 0 && getTitle() == \"blobs-2.tif\") exit(\"cannot handle \" + getTitle());\nrun(\"Convert to Mask\");\nrun(\"Analyze Particles...\", \"display clear\");");
        expect(Boolean.TRUE.equals(macros.makeMacro(picky, imp).get("ok")), "picky saved without test");
        var pickyRun = new JsonObject(); pickyRun.addProperty("name", "picky"); pickyRun.addProperty("target", "folder"); pickyRun.addProperty("folder", folder.toString());
        var partial = macros.runWorkflow(pickyRun, imp);
        expect(((Number) partial.get("processed")).intValue() == 2 && ((List<?>) partial.get("failed")).size() == 1, "one image failed, two processed: " + partial);
        expect(noDialogs(), "exit() dialog dismissed: " + dialogs());
        pass("run_workflow partial failure: processed 2, failed " + partial.get("failed"));

        // 7. capture_macro from the journal
        journal.record("open_path", "open(\"/tmp/some.tif\");");
        journal.record("filter", "run(\"Gaussian Blur...\", \"sigma=2\");");
        journal.record("analyze_particles", "run(\"Analyze Particles...\", \"size=40-Infinity show=Nothing display clear\");");
        var capture = new JsonObject(); capture.addProperty("name", "Captured steps"); capture.addProperty("lastSteps", 3);
        var captured = macros.macroFromRecorder(capture, imp);
        String code = (String) captured.get("code");
        expect(Boolean.TRUE.equals(captured.get("ok")) && Files.isRegularFile(Path.of((String) captured.get("path"))), "captured macro saved: " + captured);
        expect(!code.contains("open(") && code.contains("\"sigma=\" + sigma") && code.contains("\"size=\" + minSize + \"-Infinity"), "image-bound lines removed and options parameterised: " + code);
        expect(((Map<?, ?>) captured.get("params")).get("sigma").equals(2L) && ((Map<?, ?>) captured.get("params")).get("minSize").equals(40L), "params lifted: " + captured.get("params"));
        pass("capture_macro: params " + captured.get("params") + ", removed " + captured.get("removedLines"));

        // 8. bad names never escape the workflow folder
        var escape = new JsonObject(); escape.addProperty("name", "../../etc/passwd");
        try { macros.runWorkflow(escape, imp); throw new AssertionError("path name accepted"); } catch (IllegalArgumentException expected) { /* refused */ }
        var weird = new JsonObject(); weird.addProperty("name", "../../Evil Name!"); weird.addProperty("code", "print(1);"); weird.addProperty("test", false);
        var weirdSaved = macros.makeMacro(weird, imp);
        expect(Path.of((String) weirdSaved.get("path")).getParent().equals(root.resolve(".local/workflows")) && weirdSaved.get("slug").equals("evil-name"), "slugged into the workflow folder: " + weirdSaved.get("path"));
        pass("paths: traversal refused, names slugged");
    }

    static List<String> dialogs() { var list = new ArrayList<String>(); for (Window window : Window.getWindows()) if (window.isShowing() && window instanceof Dialog dialog) list.add(dialog.getTitle()); return list; }
    static boolean noDialogs() { return dialogs().isEmpty(); }
    static void pass(String message) { System.out.println("PASS " + message); }
    static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
