package science.luma.fiji;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The bridge's own record of what it ran, as ImageJ macro lines, so "save this as a macro" works even when the Macro Recorder is closed. */
final class Journal {
    private static final int LIMIT = 500;
    record Step(long sequence, String operation, String macro, String at) {}
    private final List<Step> steps = new ArrayList<>();
    private long sequence;

    synchronized void record(String operation, String macroLine) {
        if (macroLine == null || macroLine.isBlank()) return;
        steps.add(new Step(++sequence, operation, macroLine.strip(), Instant.now().toString()));
        while (steps.size() > LIMIT) steps.removeFirst();
    }
    synchronized List<Map<String, Object>> steps(int limit) {
        var result = new ArrayList<Map<String, Object>>();
        for (var step : steps.subList(Math.max(0, steps.size() - limit), steps.size())) {
            var row = new LinkedHashMap<String, Object>();
            row.put("sequence", step.sequence()); row.put("operation", step.operation()); row.put("macro", step.macro()); row.put("at", step.at());
            result.add(row);
        }
        return result;
    }
    synchronized String macro() {
        var text = new StringBuilder();
        for (var step : steps) text.append(step.macro()).append('\n');
        return text.toString();
    }
    synchronized int size() { return steps.size(); }

    /** The ImageJ macro call for IJ.run(imp, command, options). */
    static String runLine(String command, String options) {
        if (options == null || options.isBlank()) return "run(\"" + escape(command) + "\");";
        return "run(\"" + escape(command) + "\", \"" + escape(options) + "\");";
    }
    static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
