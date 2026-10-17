package sc.fidelis.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.Enums.ParameterType;

/**
 * Parameter validation (D06 §3 step 4) and the single options-string builder (D06 §5).
 *
 * <p>Escaping limits, from {@code ij.Macro.getValue}: a value is read as {@code key=[...]} with nested
 * bracket counting, or {@code key='...'} up to the next single quote, or up to the next space. So a
 * value can be bracketed when its brackets are balanced and never close early; otherwise it can be
 * single-quoted when it has no single quote; control characters (newlines) cannot be represented at all.
 * Anything else is rejected with VALIDATION_FAILED instead of being altered.
 */
final class Options {
    private Options() {}

    /**
     * Metadata keys any request may carry in {@code params}: {@code transcriptRef} (speech transcript id,
     * copied into HistoryRecord.transcriptRef) and {@code parentActionId} (nesting under a CODE action,
     * copied into the record params), {@code expectedHistorySize} (the history size the action was approved against) and
     * {@code expectedRoi} (the {@link RoiSignature} of the target document's active selection at approval); both are
     * checked when the worker starts the action, see {@code DefaultEngine.runOp}. They are accepted for every kind and
     * never reach the options string.
     */
    static final java.util.Set<String> RESERVED = java.util.Set.of("transcriptRef", "parentActionId", "expectedHistorySize",
            "expectedRoi");

    /** Raised for a request that fails validation; {@code key} names the offending parameter (may be null). */
    static final class Invalid extends Exception {
        final String key;

        Invalid(String key, String message) {
            super(message);
            this.key = key;
        }
    }

    /** Canonicalised parameter values plus the IJ1 options string they produce. */
    record Validated(Map<String, Object> typed, String options) {
    }

    private static final Pattern STRING_RANGE = Pattern.compile("^[0-9.eE+]+(-([0-9.eE+]+|Infinity))?$");

    /** Validate {@code params} completely against the schema; the document supplies target-dependent predicates. */
    static Validated validate(Catalogue.Entry en, Map<String, Object> params, ImagePlus imp, Registry registry) throws Invalid {
        CommandSchema schema = en.schema;
        for (String key : params.keySet()) {
            if (RESERVED.contains(key)) continue;
            if (schema.parameter(key).isEmpty()) {
                throw new Invalid(key, "unknown parameter '" + key + "' for " + schema.label()
                        + (schema.parameters().isEmpty() ? " (this command takes no parameters)"
                        : "; expected one of " + schema.parameters().stream().map(ParameterSchema::key).toList()));
            }
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        StringBuilder opts = new StringBuilder();
        boolean stackImage = imp != null && imp.getStackSize() > 1;
        for (ParameterSchema p : schema.parameters()) {
            Catalogue.Ext ext = en.ext.get(p.key());
            boolean present = params.containsKey(p.key());
            if (!present) {
                boolean visible = p.visibleWhen() == null || visible(p.visibleWhen(), imp);
                boolean optional = !visible || ext.optionalAlways() || (en.paramsOnlyForStacks && !stackImage);
                if (optional) continue;
                throw new Invalid(p.key(), "missing parameter '" + p.key() + "' for " + schema.label()
                        + " (every key must be given explicitly; " + describe(p) + ")");
            }
            Object v = params.get(p.key());
            if (v == null) throw new Invalid(p.key(), "parameter '" + p.key() + "' is null");
            switch (p.type()) {
                case BOOLEAN -> {
                    boolean b = asBoolean(p.key(), v);
                    typed.put(p.key(), b);
                    if (b) append(opts, p.key());
                }
                case NUMBER, INTEGER -> {
                    double d = asNumber(p.key(), v);
                    if (p.type() == ParameterType.INTEGER && d != Math.rint(d))
                        throw new Invalid(p.key(), "parameter '" + p.key() + "' must be an integer, got " + v);
                    if (p.min() != null && (ext.minExclusive() ? d <= p.min() : d < p.min()))
                        throw new Invalid(p.key(), "parameter '" + p.key() + "' must be " + (ext.minExclusive() ? ">" : ">=") + " "
                                + formatNumber(p.min()) + ", got " + formatNumber(d));
                    if (p.max() != null && d > p.max())
                        throw new Invalid(p.key(), "parameter '" + p.key() + "' must be <= " + formatNumber(p.max()) + ", got " + formatNumber(d));
                    typed.put(p.key(), p.type() == ParameterType.INTEGER ? (Object) Long.valueOf((long) d) : (Object) Double.valueOf(d));
                    append(opts, p.key() + "=" + formatNumber(d));
                }
                case CHOICE -> {
                    String s = asString(p.key(), v);
                    String canonical = null;
                    for (String c : p.choices()) if (c.equals(s)) { canonical = c; break; }
                    if (canonical == null) for (String c : p.choices()) if (c.equalsIgnoreCase(s)) { canonical = c; break; }
                    if (canonical == null && ext.dynamicChoices()) canonical = s;
                    if (canonical == null)
                        throw new Invalid(p.key(), "parameter '" + p.key() + "' must be one of " + p.choices() + ", got '" + s + "'");
                    typed.put(p.key(), canonical);
                    append(opts, p.key() + "=" + quote(p.key(), canonical));
                }
                case IMAGE_REF -> {
                    String s = asString(p.key(), v);
                    String title = resolveImage(p.key(), s, ext.allowNone(), registry);
                    typed.put(p.key(), s);
                    append(opts, p.key() + "=" + quote(p.key(), title));
                }
                case STRING, PATH -> {
                    String s = asString(p.key(), v);
                    if (p.type() == ParameterType.STRING && ext.stringRange() && !STRING_RANGE.matcher(s).matches())
                        throw new Invalid(p.key(), "parameter '" + p.key() + "' must be a range like 0-Infinity, got '" + s + "'");
                    if (p.type() == ParameterType.PATH && s.isBlank())
                        throw new Invalid(p.key(), "parameter '" + p.key() + "' needs a path");
                    typed.put(p.key(), s);
                    append(opts, p.key() + "=" + quote(p.key(), s));
                }
            }
        }
        return new Validated(typed, opts.toString());
    }

    private static String describe(ParameterSchema p) {
        String t = p.type().name().toLowerCase(Locale.ROOT);
        if (!p.choices().isEmpty()) t += " " + p.choices();
        if (p.min() != null || p.max() != null) t += " range " + (p.min() == null ? "" : formatNumber(p.min())) + ".."
                + (p.max() == null ? "" : formatNumber(p.max()));
        return t;
    }

    private static void append(StringBuilder sb, String token) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(token);
    }

    static boolean visible(String predicate, ImagePlus imp) {
        if (imp == null) return false;
        return switch (predicate) {
            case "image.hasSpatialScale" -> imp.getCalibration().scaled();
            case "image.hyperstack" -> imp.isHyperStack() || imp.getNChannels() > 1 || imp.getNFrames() > 1;
            case "image.stackSize>1" -> imp.getStackSize() > 1;
            case "image.rgb" -> imp.getType() == ImagePlus.COLOR_RGB;
            case "!image.rgbOrComposite" -> imp.getType() != ImagePlus.COLOR_RGB && !imp.isComposite();
            case "roi.exists" -> imp.getRoi() != null;
            case "threshold.set" -> imp.getProcessor().getMinThreshold() != ImageProcessor.NO_THRESHOLD;
            default -> true;
        };
    }

    private static boolean asBoolean(String key, Object v) throws Invalid {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) return Boolean.parseBoolean(s);
        throw new Invalid(key, "parameter '" + key + "' must be true or false, got " + v);
    }

    private static double asNumber(String key, Object v) throws Invalid {
        double d;
        if (v instanceof Number n) d = n.doubleValue();
        else if (v instanceof String s) {
            try {
                d = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new Invalid(key, "parameter '" + key + "' must be a number, got '" + s + "'");
            }
        } else throw new Invalid(key, "parameter '" + key + "' must be a number, got " + v);
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new Invalid(key, "parameter '" + key + "' must be finite");
        return d;
    }

    private static String asString(String key, Object v) throws Invalid {
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        throw new Invalid(key, "parameter '" + key + "' must be text, got " + v);
    }

    /** Title of the image a reference names: a DocumentId value (doc-N) or an existing document title. */
    private static String resolveImage(String key, String s, boolean allowNone, Registry registry) throws Invalid {
        if (allowNone && (s.equals("*None*") || s.equalsIgnoreCase("none"))) return "*None*";
        Doc d = registry.get(s);
        if (d != null) {
            String title = d.imp.getTitle();
            requireUniqueTitle(key, title, registry);
            return title;
        }
        for (Doc o : registry.all()) {
            if (o.imp.getTitle().equals(s)) {
                requireUniqueTitle(key, s, registry);
                return s;
            }
        }
        throw new Invalid(key, "parameter '" + key + "' names no open image: '" + s + "'");
    }

    private static void requireUniqueTitle(String key, String title, Registry registry) throws Invalid {
        int n = 0;
        for (Doc o : registry.all()) if (o.imp.getTitle().equals(title)) n++;
        if (n > 1) throw new Invalid(key, "image title '" + title + "' is ambiguous (" + n + " open images); rename one first");
    }

    // ---- value formatting (the single escaping function) ----------------------------------------

    /** Locale-independent decimal, no trailing ".0" for integers, never exponent notation. */
    static String formatNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    /** Quote a text value for an IJ1 options string; see the class comment for what is representable. */
    static String quote(String key, String v) throws Invalid {
        for (int i = 0; i < v.length(); i++) {
            if (Character.isISOControl(v.charAt(i)))
                throw new Invalid(key, "parameter '" + key + "' contains a control character that cannot be passed to ImageJ");
        }
        if (bracketSafe(v)) return "[" + v + "]";
        if (v.indexOf('\'') < 0) return "'" + v + "'";
        throw new Invalid(key, "parameter '" + key + "' has unbalanced brackets and a quote; ImageJ options cannot represent it");
    }

    private static boolean bracketSafe(String v) {
        int depth = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth < 0) return false;
        }
        return depth == 0;
    }

    /** Escape text for inclusion in a double-quoted ImageJ macro string literal. */
    static String macroString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The macro line {@code run("label", "options");} (options omitted when empty). */
    static String runLine(String label, String options) {
        if (options == null || options.isEmpty()) return "run(\"" + macroString(label) + "\");";
        return "run(\"" + macroString(label) + "\", \"" + macroString(options) + "\");";
    }

    static List<String> list(String... s) {
        return new ArrayList<>(List.of(s));
    }
}
