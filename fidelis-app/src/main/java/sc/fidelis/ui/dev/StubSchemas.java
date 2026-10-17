package sc.fidelis.ui.dev;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.Enums.ConsequenceClass;
import sc.fidelis.engine.api.Enums.ParameterType;

/**
 * DEVELOPMENT STUB helper: a loose reading of {@code commands-core-parameters.csv} so the Parameters
 * panel can be exercised against realistic schemas. The real engine owns the authoritative schemas.
 */
final class StubSchemas {
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)");
    private final Map<String, List<String[]>> rowsById = new LinkedHashMap<>();

    StubSchemas() {
        try (InputStream in = StubSchemas.class.getResourceAsStream("/sc/fidelis/engine/data/commands-core-parameters.csv")) {
            if (in == null) return;
            List<String[]> rows = parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
            for (int i = 1; i < rows.size(); i++) {
                String[] r = rows.get(i);
                if (r.length < 10) continue;
                rowsById.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r);
            }
        } catch (IOException e) {
            System.err.println("[ui-stub] could not read the parameter catalogue: " + e);
        }
    }

    CommandSchema schemaFor(CommandSchema.Summary s) {
        List<String[]> rows = rowsById.get(s.commandId());
        List<ParameterSchema> params = new ArrayList<>();
        List<String> globals = new ArrayList<>();
        boolean complete = rows != null;
        boolean consequential = false;
        if (rows != null) {
            for (String[] r : rows) {
                String key = r[1].trim();
                if (key.isEmpty() || key.startsWith("(")) continue; // "(none for single images)"
                ParameterType type = typeOf(r[2]);
                List<String> choices = List.of();
                Double min = null;
                if (type == ParameterType.CHOICE) choices = choices(r[4]);
                boolean cons = r[7].trim().equalsIgnoreCase("yes");
                consequential |= cons;
                String unit = r[8].trim();
                params.add(new ParameterSchema(key, type, defaultOf(type, r[3], choices), choices, min, null, cons,
                        shortUnit(unit), false, null,
                        r[4], r[6]));
                String g = r[9].trim();
                if (!g.isEmpty() && !g.equalsIgnoreCase("none") && !globals.contains(g)) globals.add(g);
            }
        }
        boolean pixelCommand = s.menuPath().startsWith("Process") || s.menuPath().startsWith("Edit");
        return new CommandSchema(s.commandId(), s.label(), s.menuPath(), params, complete, false,
                pixelCommand ? ConsequenceClass.AC3_REVERSIBLE_DATA : ConsequenceClass.AC1_VIEW,
                List.of("image.open"), "IN_PLACE_PIXELS", "no", globalsFor(globals, params));
    }

    private static List<String> globalsFor(List<String> g, List<ParameterSchema> params) {
        List<String> out = new ArrayList<>();
        for (String x : g) {
            // keep only ones that name a Fiji setting, the CSV also carries prose
            if (x.contains("Prefs") || x.contains("Analyzer") || x.contains("Measurements") || x.contains("blackBackground"))
                out.add(x);
        }
        return out;
    }

    private static String shortUnit(String unit) {
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.startsWith("pixels")) return "px";
        if (u.startsWith("percent")) return "%";
        return "";
    }

    private static ParameterType typeOf(String t) {
        String w = t.trim().split("[ (]")[0].toLowerCase(Locale.ROOT);
        return switch (w) {
            case "boolean" -> ParameterType.BOOLEAN;
            case "number" -> ParameterType.NUMBER;
            case "integer" -> ParameterType.INTEGER;
            case "choice" -> ParameterType.CHOICE;
            case "path" -> ParameterType.PATH;
            default -> ParameterType.STRING;
        };
    }

    private static List<String> choices(String s) {
        String body = s.replaceAll("\\s*\\(.*$", "");
        List<String> out = new ArrayList<>();
        for (String c : body.split("\\|")) {
            c = c.trim();
            if (!c.isEmpty() && !c.equals("...")) out.add(c);
        }
        return out;
    }

    private static String defaultOf(ParameterType type, String raw, List<String> choices) {
        raw = raw.trim();
        switch (type) {
            case BOOLEAN:
                return raw.toLowerCase(Locale.ROOT).startsWith("true") ? "true" : "false";
            case NUMBER:
            case INTEGER: {
                Matcher m = LEADING_NUMBER.matcher(raw);
                return m.find() ? m.group(1) : "";
            }
            case CHOICE: {
                String first = raw.split("[ (]")[0];
                for (String c : choices) if (c.equalsIgnoreCase(first)) return c;
                return choices.isEmpty() ? "" : choices.get(0);
            }
            default:
                return "";
        }
    }

    /** Minimal RFC 4180 reader (quoted fields, doubled quotes, embedded newlines). */
    static List<String[]> parse(BufferedReader in) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int c;
        while ((c = in.read()) >= 0) {
            if (quoted) {
                if (c == '"') {
                    in.mark(1);
                    int n = in.read();
                    if (n == '"') cur.append('"');
                    else {
                        quoted = false;
                        if (n >= 0) in.reset();
                    }
                } else cur.append((char) c);
            } else if (c == '"') quoted = true;
            else if (c == ',') {
                row.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    in.mark(1);
                    int n = in.read();
                    if (n != '\n' && n >= 0) in.reset();
                }
                row.add(cur.toString());
                cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row.toArray(new String[0]));
                row.clear();
            } else cur.append((char) c);
        }
        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }
}
