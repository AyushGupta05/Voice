package sc.fidelis.nl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ij.ImagePlus;
import ij.gui.ImageWindow;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.ConsequenceClass;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.ParameterType;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;

/**
 * Minimal engine for NL tests: serves catalogue schemas parsed from the real catalogue CSVs
 * ({@code sc/fidelis/engine/data/commands.csv} and {@code commands-core-parameters.csv}) and a few
 * synthetic documents. Everything that would execute throws. The NL tests only use the {@link Engine}
 * interface, so they run unchanged against A1's engine once it lands.
 */
final class FakeEngine implements Engine {

    private static final String DATA = "/sc/fidelis/engine/data/";

    private final Map<String, CommandSchema> schemas = new LinkedHashMap<>();
    private final List<CommandSchema.Summary> summaries = new ArrayList<>();
    private final Map<DocumentId, DocumentInfo> docs = new LinkedHashMap<>();
    private final Map<DocumentId, RoiGeometry> activeRois = new LinkedHashMap<>();

    FakeEngine() {
        try {
            loadCatalogue();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        addDocument(doc("uncal", "blobs.tif", 256, 256, 1, 1, 1, "GRAY8", 8, "pixel", 1.0));
        addDocument(doc("cal", "cells.tif", 512, 512, 1, 1, 1, "GRAY8", 8, "µm", 0.65));
        addDocument(doc("stack", "stack.tif", 256, 256, 1, 5, 1, "GRAY8", 8, "µm", 0.5));
        addDocument(doc("rgb", "photo.tif", 256, 256, 1, 1, 1, "COLOR_RGB", 24, "pixel", 1.0));
        addDocument(doc("roi", "withroi.tif", 256, 256, 1, 1, 1, "GRAY8", 8, "pixel", 1.0));
        addDocument(doc("comp", "composite.tif", 256, 256, 3, 1, 1, "GRAY16", 16, "µm", 0.325));
        activeRois.put(new DocumentId("roi"), new RoiGeometry("RECTANGLE", List.of(10f, 60f), List.of(10f, 60f),
                new double[] {10, 10, 50, 50}, null, null, null));
    }

    private static DocumentInfo doc(String id, String title, int w, int h, int c, int z, int t, String type, int bits,
                                    String unit, double pw) {
        return new DocumentInfo(new DocumentId(id), title, -1, w, h, c, z, t, type, bits, unit, pw, pw, null, null, false, false);
    }

    private void addDocument(DocumentInfo d) {
        docs.put(d.id(), d);
    }

    // ---- catalogue loading ----------------------------------------------------------------------

    private void loadCatalogue() throws IOException {
        List<List<String>> commands = csv(DATA + "commands.csv");
        List<String> head = commands.get(0);
        int iId = head.indexOf("command_id"), iLabel = head.indexOf("label"), iPath = head.indexOf("menu_path"),
                iKey = head.indexOf("shortcut"), iFw = head.indexOf("framework");
        Map<String, CommandSchema.Summary> byId = new LinkedHashMap<>();
        for (List<String> r : commands.subList(1, commands.size())) {
            if (r.size() <= iFw || !r.get(iId).startsWith("ij1:") || !"IJ1".equals(r.get(iFw))) continue;
            CommandSchema.Summary s = new CommandSchema.Summary(r.get(iId), r.get(iLabel), r.get(iPath), r.get(iKey), r.get(iFw), "", Map.of());
            byId.put(s.commandId(), s);
        }
        summaries.addAll(byId.values());

        List<List<String>> params = csv(DATA + "commands-core-parameters.csv");
        List<String> ph = params.get(0);
        int pId = ph.indexOf("command_id"), pKey = ph.indexOf("parameter_key"), pType = ph.indexOf("type"),
                pDef = ph.indexOf("default"), pRange = ph.indexOf("range_or_choices"), pCons = ph.indexOf("consequential"),
                pUnit = ph.indexOf("native_unit");
        Map<String, List<ParameterSchema>> grouped = new LinkedHashMap<>();
        for (List<String> r : params.subList(1, params.size())) {
            if (r.size() <= pUnit) continue;
            List<ParameterSchema> list = grouped.computeIfAbsent(r.get(pId), k -> new ArrayList<>());
            String key = r.get(pKey);
            if (key.startsWith("(")) continue; // "(none)" and "(implicit)" markers
            ParameterType type = type(r.get(pType));
            List<String> choices = type == ParameterType.CHOICE ? choices(r.get(pRange)) : List.of();
            Double[] mm = type == ParameterType.NUMBER ? minMax(r.get(pRange)) : new Double[] {null, null};
            list.add(new ParameterSchema(key, type, r.get(pDef), choices, mm[0], mm[1], "yes".equals(r.get(pCons)),
                    r.get(pUnit), false, null, null, "commands-core-parameters.csv"));
        }
        for (CommandSchema.Summary s : summaries) {
            List<ParameterSchema> ps = grouped.get(s.commandId());
            boolean complete = ps != null;
            schemas.put(s.commandId(), new CommandSchema(s.commandId(), s.label(), s.menuPath(),
                    ps == null ? List.of() : ps, complete, false, ConsequenceClass.AC3_REVERSIBLE_DATA, List.of(), "IN_PLACE_PIXELS",
                    "no", List.of()));
        }
    }

    private static ParameterType type(String t) {
        String l = t.toLowerCase(Locale.ROOT);
        if (l.startsWith("boolean")) return ParameterType.BOOLEAN;
        if (l.startsWith("choice")) return ParameterType.CHOICE;
        if (l.startsWith("number")) return ParameterType.NUMBER;
        if (l.startsWith("path")) return ParameterType.PATH;
        return ParameterType.STRING;
    }

    private static List<String> choices(String range) {
        String r = range.replaceAll("\\s*\\([^)]*\\)\\s*$", "");
        List<String> out = new ArrayList<>();
        for (String c : r.split("\\|")) {
            String v = c.replace("[", "").replace("]", "").trim();
            if (!v.isEmpty() && !v.startsWith("<") && !v.startsWith("\"")) out.add(v);
        }
        return out;
    }

    private static Double[] minMax(String range) {
        Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)?)-(\\d+(?:\\.\\d+)?)").matcher(range.trim());
        if (m.find()) return new Double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        return new Double[] {null, null};
    }

    /** RFC 4180 CSV (quoted fields, doubled quotes, embedded newlines). */
    static List<List<String>> csv(String resource) throws IOException {
        String text;
        try (InputStream in = FakeEngine.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing classpath resource " + resource + " (run tools/build.py)");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        boolean pending = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; } else quoted = false;
                } else f.append(ch);
            } else if (ch == '"') { quoted = true; pending = true; }
            else if (ch == ',') { row.add(f.toString()); f.setLength(0); pending = true; }
            else if (ch == '\n') { row.add(f.toString()); f.setLength(0); rows.add(row); row = new ArrayList<>(); pending = false; }
            else if (ch != '\r') { f.append(ch); pending = true; }
        }
        if (pending) { row.add(f.toString()); rows.add(row); }
        return rows;
    }

    // ---- catalogue (the part the NL layer uses) -------------------------------------------------

    @Override public List<CommandSchema.Summary> catalogue() { return List.copyOf(summaries); }

    @Override public Optional<CommandSchema> schema(String commandId) { return Optional.ofNullable(schemas.get(commandId)); }

    @Override
    public List<CommandSchema.Summary> search(String query, int limit) {
        List<String> q = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (!t.isEmpty()) q.add(t);
        record Scored(CommandSchema.Summary s, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (CommandSchema.Summary s : summaries) {
            int score = 0;
            String label = s.label().toLowerCase(Locale.ROOT);
            for (String t : q) {
                if (t.length() < 3) continue;
                for (String lt : label.split("[^a-z0-9]+")) if (lt.equals(t)) score += 4;
                if (s.menuPath().toLowerCase(Locale.ROOT).contains(t)) score += 1;
            }
            if (score > 0) scored.add(new Scored(s, score));
        }
        scored.sort(Comparator.comparingInt((Scored x) -> -x.score()).thenComparing(x -> x.s().label().length()));
        List<CommandSchema.Summary> out = new ArrayList<>();
        for (Scored x : scored) {
            if (out.size() >= limit) break;
            out.add(x.s());
        }
        return out;
    }

    // ---- documents ------------------------------------------------------------------------------

    @Override public List<DocumentInfo> documents() { return List.copyOf(docs.values()); }

    @Override public Optional<DocumentInfo> document(DocumentId id) { return Optional.ofNullable(docs.get(id)); }

    @Override public Optional<DocumentId> focused() { return Optional.empty(); }

    @Override public Optional<RoiGeometry> activeRoi(DocumentId id) { return Optional.ofNullable(activeRois.get(id)); }

    @Override public List<RoiGeometry> rois(DocumentId id) { return activeRoi(id).map(List::of).orElse(List.of()); }

    // ---- everything else is out of scope for NL tests -------------------------------------------

    private static UnsupportedOperationException no() { return new UnsupportedOperationException("FakeEngine"); }

    @Override public EngineState state() { return EngineState.READY; }
    @Override public void addListener(EngineListener listener) {}
    @Override public void removeListener(EngineListener listener) {}
    @Override public void quit(boolean discardUnsaved) { throw no(); }
    @Override public CompletableFuture<ActionResult> submit(ActionRequest request) { throw no(); }
    @Override public void cancel(String operationId) { throw no(); }
    @Override public CompletableFuture<ActionResult> open(Path file) { throw no(); }
    @Override public void focus(DocumentId id) { throw no(); }
    @Override public ImagePlus imagePlus(DocumentId id) { throw no(); }
    @Override public ImageWindow hostWindow(DocumentId id) { throw no(); }
    @Override public List<MeasurementRow> results() { throw no(); }
    @Override public MeasurementRow.Columns resultColumns() { throw no(); }
    @Override public List<HistoryRecord> history() { throw no(); }
    @Override public String exportMacro() { throw no(); }
    @Override public Map<String, String> globals() { return Map.of(); }
    @Override public long[] undoBudget() { throw no(); }
}
