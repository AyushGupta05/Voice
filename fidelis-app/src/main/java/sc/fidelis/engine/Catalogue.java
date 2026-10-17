package sc.fidelis.engine;

import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.MenuShortcut;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ij.Menus;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.CommandSchema.Summary;
import sc.fidelis.engine.api.Enums.ConsequenceClass;
import sc.fidelis.engine.api.Enums.ParameterType;

/**
 * Runtime command catalogue (E2): every label of IJ1's command table (the real runtime), its menu
 * path (walked from the hidden IJ1 menu bar, CSV fallback) and, for the catalogued core commands, the
 * complete parameter schema from {@code commands-core-parameters.csv}.
 */
final class Catalogue {
    private static final String COMMANDS = "/sc/fidelis/engine/data/commands.csv";
    private static final String PARAMETERS = "/sc/fidelis/engine/data/commands-core-parameters.csv";

    /** Validation details the public {@link ParameterSchema} cannot express. */
    record Ext(boolean minExclusive, boolean maxExclusive, boolean dynamicChoices, boolean allowNone,
               boolean stringRange, boolean optionalAlways) {
    }

    /** A catalogue command with the engine-internal flags used by execution. */
    static final class Entry {
        final CommandSchema schema;
        final Summary summary;
        final boolean mayBeNoOp;
        final boolean paramsOnlyForStacks;
        final boolean needsImage;
        final Map<String, Ext> ext;

        Entry(CommandSchema schema, Summary summary, boolean mayBeNoOp, boolean paramsOnlyForStacks, boolean needsImage,
              Map<String, Ext> ext) {
            this.schema = schema;
            this.summary = summary;
            this.mayBeNoOp = mayBeNoOp;
            this.paramsOnlyForStacks = paramsOnlyForStacks;
            this.needsImage = needsImage;
            this.ext = ext;
        }

        String family() {
            return schema.postconditionFamily();
        }

        String label() {
            return schema.label();
        }
    }

    private record MenuHit(String path, String shortcut) {
    }

    private final Map<String, Entry> byId = new HashMap<>();
    private final List<Entry> ordered = new ArrayList<>();
    private final List<Summary> summaries = new ArrayList<>();

    static Catalogue empty() {
        return new Catalogue();
    }

    List<Summary> summaries() {
        return summaries;
    }

    Optional<Entry> find(String commandId) {
        return Optional.ofNullable(byId.get(commandId));
    }

    List<Entry> entries() {
        return ordered;
    }

    // ---- building -----------------------------------------------------------------------------

    /** Build from the live IJ1 runtime (command table and menu bar) plus the shipped CSVs. */
    @SuppressWarnings("unchecked")
    static Catalogue buildFromRuntime() {
        Map<String, String> runtime = new LinkedHashMap<>();
        java.util.Hashtable<String, String> table = Menus.getCommands();
        if (table != null) for (Map.Entry<String, String> e : table.entrySet()) runtime.put(e.getKey(), e.getValue());
        return build(runtime, walkMenus());
    }

    private static Map<String, List<MenuHit>> walkMenus() {
        Map<String, List<MenuHit>> hits = new HashMap<>();
        MenuBar mb = Menus.getMenuBar();
        if (mb == null) return hits;
        for (int i = 0; i < mb.getMenuCount(); i++) {
            Menu m = mb.getMenu(i);
            walk(m, m.getLabel(), hits);
        }
        return hits;
    }

    private static void walk(Menu m, String path, Map<String, List<MenuHit>> hits) {
        for (int j = 0; j < m.getItemCount(); j++) {
            MenuItem it = m.getItem(j);
            if (it instanceof Menu sub) {
                walk(sub, path + ">" + sub.getLabel(), hits);
            } else {
                String label = it.getLabel();
                if (label == null || label.equals("-")) continue;
                hits.computeIfAbsent(label, k -> new ArrayList<>()).add(new MenuHit(path, shortcut(it)));
            }
        }
    }

    private static String shortcut(MenuItem it) {
        MenuShortcut ms = it.getShortcut();
        if (ms == null) return "";
        return "Ctrl+" + (ms.usesShiftModifier() ? "Shift+" : "") + KeyEvent.getKeyText(ms.getKey());
    }

    private static Catalogue build(Map<String, String> runtime, Map<String, List<MenuHit>> menu) {
        List<Map<String, String>> commandRows = Csv.readResource(COMMANDS);
        List<Map<String, String>> paramRows = Csv.readResource(PARAMETERS);

        Map<String, Map<String, String>> csvById = new HashMap<>();
        Map<String, List<Map<String, String>>> csvByLabel = new HashMap<>();
        for (Map<String, String> r : commandRows) {
            String id = r.get("command_id");
            if (id == null || !id.startsWith("ij1:")) continue;
            csvById.put(id, r);
            csvByLabel.computeIfAbsent(r.get("label"), k -> new ArrayList<>()).add(r);
        }
        Map<String, List<Map<String, String>>> paramsById = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> paramsByLabel = new HashMap<>();
        for (Map<String, String> r : paramRows) {
            String id = r.get("command_id");
            paramsById.computeIfAbsent(id, k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Map<String, String>>> e : paramsById.entrySet()) {
            String id = e.getKey();
            String label = id.substring(id.lastIndexOf('>') + 1);
            paramsByLabel.putIfAbsent(label, e.getValue());
        }

        Catalogue cat = new Catalogue();
        List<String> labels = new ArrayList<>(runtime.keySet());
        labels.sort(Comparator.naturalOrder());
        for (String label : labels) {
            String impl = runtime.get(label);
            List<MenuHit> hits = menu.get(label);
            List<MenuHit> usable = new ArrayList<>();
            if (hits != null) usable.addAll(hits);
            if (usable.isEmpty()) {
                List<Map<String, String>> csv = csvByLabel.get(label);
                if (csv != null) {
                    Set<String> seen = new LinkedHashSet<>();
                    for (Map<String, String> r : csv) {
                        String p = r.get("menu_path");
                        if (p != null && !p.isBlank() && seen.add(p)) usable.add(new MenuHit(p, r.get("shortcut")));
                    }
                }
            }
            if (usable.isEmpty()) usable.add(new MenuHit("Unlisted", ""));
            Set<String> donePaths = new LinkedHashSet<>();
            for (MenuHit h : usable) {
                if (!donePaths.add(h.path())) continue;
                String id = "ij1:" + h.path() + ">" + label;
                Map<String, String> csvRow = csvById.get(id);
                if (csvRow == null) {
                    List<Map<String, String>> byLabel = csvByLabel.get(label);
                    if (byLabel != null && byLabel.size() == 1) csvRow = byLabel.get(0);
                }
                List<Map<String, String>> params = paramsById.get(id);
                if (params == null && csvRow != null) params = paramsById.get(csvRow.get("command_id"));
                if (params == null) params = paramsByLabel.get(label);
                Entry en = makeEntry(id, label, h.path(), h.shortcut(), impl, csvRow, params);
                cat.add(en);
                if (csvRow != null && !csvRow.get("command_id").equals(id)) cat.byId.putIfAbsent(csvRow.get("command_id"), en);
            }
        }
        cat.ordered.sort(Comparator.comparing((Entry e) -> e.schema.menuPath()).thenComparing(e -> e.schema.label()));
        for (Entry e : cat.ordered) cat.summaries.add(e.summary);
        return cat;
    }

    private void add(Entry e) {
        byId.put(e.schema.commandId(), e);
        ordered.add(e);
    }

    // ---- one entry ----------------------------------------------------------------------------

    private static final Set<String> BINARY_ONLY = Set.of("Erode", "Dilate", "Open", "Close-", "Outline", "Fill Holes",
            "Skeletonize", "Watershed");
    private static final Set<String> NO_IMAGE_NEEDED = Set.of("Clear Results", "Image...", "Open...", "Summarize",
            "Merge Channels...", "Image Calculator...", "Set Measurements...", "Options...");
    private static final Set<String> LOOSE_STRICT = Set.of("Invert", "Gaussian Blur...", "Unsharp Mask...", "Smooth",
            "Sharpen", "Find Edges", "Subtract Background...");
    private static final Set<String> TYPE_LABELS = Set.of("8-bit", "16-bit", "32-bit", "8-bit Color", "RGB Color");
    private static final Set<String> IN_PLACE_LABELS = Set.of("Invert", "Smooth", "Sharpen", "Find Edges", "Enhance Contrast...",
            "Subtract Background...", "Fill", "Clear", "Clear Outside", "Draw", "Apply LUT", "Convert to Mask", "Make Binary",
            "Rotate... ", "Flip Horizontally", "Flip Vertically", "Rotate 90 Degrees Right", "Rotate 90 Degrees Left",
            "Translate...");
    private static final Set<String> NEW_IMAGE_LABELS = Set.of("Duplicate...", "Z Project...", "Image Calculator...",
            "Make Substack...", "Make Montage...", "Scale...", "Image...", "Merge Channels...", "Split Channels");
    private static final Set<String> RESULT_LABELS = Set.of("Measure", "Summarize", "Analyze Particles...");
    private static final Set<String> GLOBAL_LABELS = Set.of("Set Measurements...", "Options...", "Appearance...",
            "Conversions...", "Colors...", "Line Width...", "Input/Output...", "Rounded Rect Tool...", "Point Tool...",
            "Wand Tool...", "Fonts...", "Profile Plot Options...", "Plots...", "Proxy Settings...", "Memory & Threads...",
            "Misc...", "Arrow Tool...", "Roi Defaults...");

    static String familyOf(String label, String menuPath) {
        if (RESULT_LABELS.contains(label)) return "RESULTS_ROWS";
        if (NEW_IMAGE_LABELS.contains(label)) return "NEW_IMAGE";
        if (TYPE_LABELS.contains(label)) return "TYPE_CHANGE";
        if (GLOBAL_LABELS.contains(label)) return "GLOBALS_ONLY";
        if (menuPath.startsWith("File>Save As") || label.equals("Save")) return "FILE_WRITTEN";
        if (IN_PLACE_LABELS.contains(label)) return "IN_PLACE_PIXELS";
        if (menuPath.startsWith("Process>Filters") || menuPath.startsWith("Process>Math") || menuPath.startsWith("Process>Noise")
                || menuPath.startsWith("Process>Shadows") || menuPath.startsWith("Process>Binary")) return "IN_PLACE_PIXELS";
        if (menuPath.startsWith("Image>Lookup Tables")) return "DISPLAY_ONLY";
        return null;
    }

    private static boolean mayBeNoOp(String label, String menuPath, String family) {
        if (!"IN_PLACE_PIXELS".equals(family)) return false;
        return !LOOSE_STRICT.contains(label);
    }

    private static Entry makeEntry(String id, String label, String menuPath, String shortcut, String impl,
                                   Map<String, String> csv, List<Map<String, String>> paramRows) {
        String family = familyOf(label, menuPath);
        List<ParameterSchema> params = new ArrayList<>();
        Map<String, Ext> ext = new LinkedHashMap<>();
        boolean onlyStacks = false;
        boolean interactive = false;
        Set<String> hidden = new LinkedHashSet<>();
        boolean catalogued = paramRows != null && !paramRows.isEmpty();
        if (catalogued) {
            for (Map<String, String> r : paramRows) {
                String key = r.get("parameter_key").trim();
                if (key.startsWith("(")) {
                    if (key.contains("none for single images")) onlyStacks = true;
                    if (key.contains("interactive")) interactive = true;
                    continue;
                }
                if (r.get("type").isBlank()) continue;
                ParameterSchema ps = parseParameter(key, r, ext);
                if (ps != null) params.add(ps);
                String hg = r.getOrDefault("hidden_global_dependency", "").trim();
                if (!hg.isEmpty() && !hg.equalsIgnoreCase("none") && !hg.equalsIgnoreCase("UNKNOWN")) hidden.add(hg);
            }
        }
        boolean schemaComplete = catalogued && !interactive;

        List<String> pre = new ArrayList<>();
        boolean needsImage = !NO_IMAGE_NEEDED.contains(label) && !"GLOBALS_ONLY".equals(family) && schemaComplete;
        if (needsImage) pre.add("image.open");
        String csvPre = csv == null ? "" : csv.getOrDefault("preconditions", "").toLowerCase(Locale.ROOT);
        if (BINARY_ONLY.contains(label) || csvPre.startsWith("8-bit binary")) pre.add("image.binary");
        if (csvPre.startsWith("stack required") || csvPre.startsWith("stack (")) pre.add("image.stack");
        if (label.equals("Analyze Particles...")) pre.add("threshold.or.binary");
        if (label.equals("Crop") || label.equals("Clear Outside")) pre.add("roi.required");

        String creates = csv == null ? "" : csv.getOrDefault("creates_new_image", "");
        if (creates.isBlank()) creates = "NEW_IMAGE".equals(family) ? "yes" : (family == null ? "unknown" : "no");
        if (label.equals("Image Calculator...") || label.equals("Scale...")) creates = "conditional";

        ConsequenceClass cc;
        if (family == null) cc = ConsequenceClass.AC4_NON_UNDOABLE_DATA;
        else cc = switch (family) {
            case "IN_PLACE_PIXELS", "TYPE_CHANGE" -> ConsequenceClass.AC3_REVERSIBLE_DATA;
            case "GLOBALS_ONLY" -> ConsequenceClass.AC5_GLOBAL_SETTINGS;
            case "FILE_WRITTEN" -> ConsequenceClass.AC6_PERSISTENT_EXTERNAL;
            case "DISPLAY_ONLY" -> ConsequenceClass.AC1_VIEW;
            default -> ConsequenceClass.AC0_READ_ONLY;
        };
        boolean headlessOk = csv != null && "pass".equals(csv.get("headless_probe"));
        CommandSchema schema = new CommandSchema(id, label, menuPath, List.copyOf(params), schemaComplete, headlessOk, cc,
                List.copyOf(pre), family, creates, List.copyOf(hidden));
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("implementation", impl == null ? "" : impl);
        notes.put("schema", schemaComplete ? "complete" : "none");
        if (family != null) notes.put("postcondition", family);
        if (csv != null) notes.put("headlessProbe", csv.getOrDefault("headless_probe", ""));
        Summary summary = new Summary(id, label, menuPath, shortcut == null ? "" : shortcut, "IJ1", "", Map.copyOf(notes));
        return new Entry(schema, summary, mayBeNoOp(label, menuPath, family), onlyStacks, needsImage, ext);
    }

    // ---- parameters ---------------------------------------------------------------------------

    private static final Set<String> INTEGER_KEYS = Set.of("iterations", "count", "bins", "decimal", "columns", "rows", "first",
            "last", "increment", "border", "font", "width", "height", "slices", "start", "stop", "depth", "time", "grid");
    private static final Pattern PAREN_TAIL = Pattern.compile("\\s+\\([^()]*\\)\\s*$");
    private static final String NUM = "(-?\\d+(?:\\.\\d+)?)";

    private static ParameterSchema parseParameter(String key, Map<String, String> r, Map<String, Ext> extOut) {
        String typeCol = r.get("type").trim();
        String range = r.getOrDefault("range_or_choices", "").trim();
        String def = r.getOrDefault("default", "").trim();
        String lowerRange = range.toLowerCase(Locale.ROOT);
        ParameterType type;
        List<String> choices = List.of();
        Double min = null;
        Double max = null;
        boolean minEx = false;
        boolean maxEx = false;
        boolean dynamic = false;
        boolean allowNone = false;
        boolean stringRange = false;
        if (typeCol.startsWith("boolean")) {
            type = ParameterType.BOOLEAN;
        } else if (typeCol.startsWith("choice")) {
            if (lowerRange.startsWith("open image titles")) {
                type = ParameterType.IMAGE_REF;
                dynamic = true;
                allowNone = true;
            } else {
                type = ParameterType.CHOICE;
                String c = PAREN_TAIL.matcher(range).replaceFirst("");
                List<String> list = new ArrayList<>();
                for (String s : c.split("\\|")) {
                    String t = s.trim();
                    if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
                    if (t.equalsIgnoreCase("<open image titles>")) {
                        dynamic = true;
                        continue;
                    }
                    // trailing prose such as "channel|slice|frame (hyperstacks only)" has been removed above
                    if (!t.isEmpty()) list.add(t);
                }
                choices = List.copyOf(list);
            }
        } else if (typeCol.startsWith("number")) {
            type = INTEGER_KEYS.contains(key) ? ParameterType.INTEGER : ParameterType.NUMBER;
            String rr = PAREN_TAIL.matcher(range).replaceFirst("");
            Matcher m;
            if ((m = Pattern.compile("^>=\\s*" + NUM).matcher(rr)).find()) {
                min = Double.parseDouble(m.group(1));
            } else if ((m = Pattern.compile("^>\\s*" + NUM).matcher(rr)).find()) {
                min = Double.parseDouble(m.group(1));
                minEx = true;
            } else if ((m = Pattern.compile("^" + NUM + "-" + NUM + "$").matcher(rr)).find()) {
                min = Double.parseDouble(m.group(1));
                max = Double.parseDouble(m.group(2));
            } else if ((m = Pattern.compile("^" + NUM + "-[A-Za-z_]+$").matcher(rr)).find()) {
                min = Double.parseDouble(m.group(1));
            } else if ((m = Pattern.compile("^" + NUM + "\\.\\.([0-9.]+|N)").matcher(rr)).find()) {
                min = Double.parseDouble(m.group(1));
                if (!m.group(2).equals("N")) max = Double.parseDouble(m.group(2));
            }
        } else if (typeCol.equals("path")) {
            type = ParameterType.PATH;
        } else if (typeCol.startsWith("string")) {
            type = ParameterType.STRING;
            stringRange = typeCol.contains("range");
        } else {
            return null;
        }

        String visible = visibleWhen(lowerRange);
        boolean optionalAlways = lowerRange.startsWith("conditional") || (visible != null && visible.equals("optional"));
        boolean remembered = Arrays.stream(new String[] {"static", "last", "prefs", "remembered"})
                .anyMatch(def.toLowerCase(Locale.ROOT)::contains);
        String unit = r.getOrDefault("native_unit", "");
        ParameterSchema ps = new ParameterSchema(key, type, def.isEmpty() ? null : def, choices, min, max,
                "yes".equalsIgnoreCase(r.getOrDefault("consequential", "")), unit.isBlank() ? null : unit, remembered,
                visible == null || visible.equals("optional") ? null : visible, range.isEmpty() ? null : range,
                r.getOrDefault("source", ""));
        extOut.put(key, new Ext(minEx, maxEx, dynamic, allowNone, stringRange, optionalAlways));
        return ps;
    }

    /** Visibility predicate derived from the catalogue notes, or null when the key is always shown. */
    private static String visibleWhen(String lowerNote) {
        if (lowerNote.contains("shown only if image has a spatial scale") || lowerNote.contains("shown only for scaled images"))
            return "image.hasSpatialScale";
        if (lowerNote.contains("hyperstack")) return "image.hyperstack";
        if (lowerNote.startsWith("stacks") || lowerNote.contains("stacks only")) return "image.stackSize>1";
        if (lowerNote.contains("rgb only")) return "image.rgb";
        if (lowerNote.contains("not shown for rgb or composite")) return "!image.rgbOrComposite";
        if (lowerNote.contains("only when a selection exists")) return "roi.exists";
        if (lowerNote.contains("only if a threshold")) return "threshold.set";
        if (lowerNote.startsWith("conditional")) return "optional";
        return null;
    }

    // ---- search -------------------------------------------------------------------------------

    List<Summary> search(String query, int limit) {
        if (query == null) return List.of();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();
        String[] tokens = q.split("[\\s>]+");
        record Scored(int score, Summary s) {
        }
        List<Scored> hits = new ArrayList<>();
        for (Summary s : summaries) {
            String label = s.label().toLowerCase(Locale.ROOT).trim();
            String path = s.menuPath().toLowerCase(Locale.ROOT);
            String hay = label + " " + path;
            boolean all = true;
            for (String t : tokens) if (!hay.contains(t)) { all = false; break; }
            if (!all) continue;
            int score;
            if (label.equals(q)) score = 0;
            else if (label.startsWith(q)) score = 1;
            else if (label.contains(q)) score = 2;
            else if (Arrays.stream(tokens).allMatch(label::contains)) score = 3;
            else score = 4;
            hits.add(new Scored(score, s));
        }
        hits.sort(Comparator.<Scored>comparingInt(Scored::score).thenComparing(h -> h.s().label().length())
                .thenComparing(h -> h.s().label()));
        List<Summary> out = new ArrayList<>();
        for (Scored h : hits) {
            if (limit > 0 && out.size() >= limit) break;
            out.add(h.s());
        }
        return out;
    }
}
