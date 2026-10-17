package sc.fidelis.penny;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Enums.ParameterType;

/**
 * Complete COMMAND parameters for the engine from a {@link CommandSchema}: keys the caller gave (provenance USER), keys
 * the operation's documented recipe sets (DERIVED), then catalogue defaults (DEFAULT; an off-by-default flag counts as
 * DERIVED when consequential, as in {@code DefaultNlService}). Values are converted to the schema types. Keys that have no
 * usable default are left out: the engine then decides (an invisible stack-only key is optional, a required one fails
 * validation with VALIDATION_FAILED) and {@code ConfirmationPolicy} sees a consequential slot not set by the user.
 * {@code black} is never filled from a default: on a single image it is the global BlackBackground preference.
 */
final class Params {
    private Params() {}

    record Filled(Map<String, Object> params, Map<String, String> provenance) {}

    static final String USER = "USER";
    static final String DERIVED = "DERIVED";
    static final String DEFAULT = "DEFAULT";

    private static final Pattern LEADING_NUMBER = Pattern.compile("^[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern PAREN_TAIL = Pattern.compile("\\s*\\(.*$");

    static Filled fill(CommandSchema schema, Map<String, Object> user, Map<String, Object> recipe, DocumentInfo doc) {
        for (String k : user.keySet()) {
            if (k.equals("transcriptRef") || k.equals("parentActionId"))
                throw new IllegalArgumentException("'" + k + "' is reserved and set by the bridge");
            if (schema.parameter(k).isEmpty())
                throw new IllegalArgumentException("unknown parameter '" + k + "' for " + schema.label()
                        + (schema.parameters().isEmpty() ? " (it takes no parameters)"
                        : "; expected one of " + schema.parameters().stream().map(ParameterSchema::key).toList()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> prov = new LinkedHashMap<>();
        for (ParameterSchema p : schema.parameters()) {
            String k = p.key();
            if (user.containsKey(k)) {
                out.put(k, coerce(p, user.get(k)));
                prov.put(k, USER);
            } else if (recipe.containsKey(k)) {
                out.put(k, coerce(p, recipe.get(k)));
                prov.put(k, DERIVED);
            } else if (!k.equals("black")) {
                Object d = defaultFor(p, doc);
                if (d == null) continue;
                out.put(k, d);
                prov.put(k, p.type() == ParameterType.BOOLEAN && p.consequential() ? DERIVED : DEFAULT);
            }
        }
        return new Filled(out, prov);
    }

    /** A value converted to the parameter's type; IllegalArgumentException names the key and the expected form. */
    static Object coerce(ParameterSchema p, Object v) {
        String k = p.key();
        if (v == null) throw new IllegalArgumentException("parameter '" + k + "' is null");
        switch (p.type()) {
            case BOOLEAN -> {
                if (v instanceof Boolean b) return b;
                String s = String.valueOf(v).trim();
                if (s.equalsIgnoreCase("true")) return true;
                if (s.equalsIgnoreCase("false")) return false;
                throw new IllegalArgumentException("parameter '" + k + "' must be true or false, got " + v);
            }
            case NUMBER, INTEGER -> {
                double d;
                if (v instanceof Number n) d = n.doubleValue();
                else {
                    try {
                        d = Double.parseDouble(String.valueOf(v).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("parameter '" + k + "' must be a number, got '" + v + "'");
                    }
                }
                if (!Double.isFinite(d)) throw new IllegalArgumentException("parameter '" + k + "' must be finite");
                if (p.type() == ParameterType.INTEGER) {
                    if (d != Math.rint(d)) throw new IllegalArgumentException("parameter '" + k + "' must be an integer, got " + v);
                    return (long) d;
                }
                return d;
            }
            case CHOICE -> {
                String s = String.valueOf(v);
                for (String c : p.choices()) if (c.equals(s)) return c;
                for (String c : p.choices()) if (c.equalsIgnoreCase(s)) return c;
                if (p.choices().isEmpty()) return s;
                throw new IllegalArgumentException("parameter '" + k + "' must be one of " + p.choices() + ", got '" + s + "'");
            }
            default -> {
                if (v instanceof Map || v instanceof List) throw new IllegalArgumentException("parameter '" + k + "' must be text");
                return String.valueOf(v);
            }
        }
    }

    /** Off-by-default flag (macro mode: absent = false): empty, "false…", "absent…", "n/a…". */
    static boolean offDefault(ParameterSchema p) {
        String d = p.defaultValue() == null ? "" : p.defaultValue().trim().toLowerCase(Locale.ROOT);
        return d.isEmpty() || d.startsWith("false") || d.startsWith("absent") || d.startsWith("n/a");
    }

    /** The catalogue default as a typed value, placeholders resolved against the document; null when there is none. */
    static Object defaultFor(ParameterSchema p, DocumentInfo doc) {
        String raw = p.defaultValue() == null ? "" : p.defaultValue().trim();
        switch (p.type()) {
            case BOOLEAN -> {
                return offDefault(p) ? Boolean.FALSE : null;
            }
            case NUMBER, INTEGER -> {
                Matcher m = LEADING_NUMBER.matcher(raw);
                if (!m.find()) return null;
                double d = Double.parseDouble(m.group());
                return p.type() == ParameterType.INTEGER ? (Object) (long) Math.rint(d) : (Object) d;
            }
            case CHOICE -> {
                String d = PAREN_TAIL.matcher(raw).replaceFirst("").trim();
                for (String c : p.choices()) if (c.equalsIgnoreCase(d)) return c;
                return p.choices().isEmpty() ? null : p.choices().get(0);
            }
            case STRING, PATH -> {
                String d = PAREN_TAIL.matcher(raw).replaceFirst("").trim();
                if (doc != null) {
                    String base = doc.title().replaceFirst("\\.[A-Za-z0-9]{2,4}$", "");
                    d = d.replace("<title>", base);
                    switch (d) {
                        case "1-N" -> d = "1-" + Math.max(1, doc.nSlices() * doc.nFrames() * doc.nChannels());
                        case "1-C" -> d = "1-" + doc.nChannels();
                        case "1-Z" -> d = "1-" + doc.nSlices();
                        case "1-T" -> d = "1-" + doc.nFrames();
                        default -> { }
                    }
                }
                if (d.isEmpty() || d.startsWith("<") || d.toUpperCase(Locale.ROOT).startsWith("UNKNOWN") || d.matches("1-[NCZT]")) return null;
                return d;
            }
            default -> {
                return null;
            }
        }
    }

    // ---- macro preview (display only: the engine's history holds the authoritative macro lines) -----------

    static String preview(CommandSchema schema, Map<String, Object> params) {
        StringBuilder opts = new StringBuilder();
        for (ParameterSchema p : schema.parameters()) {
            Object v = params.get(p.key());
            if (v == null) continue;
            String token;
            if (p.type() == ParameterType.BOOLEAN) {
                if (!Boolean.TRUE.equals(v)) continue;
                token = p.key();
            } else if (v instanceof Number n) {
                token = p.key() + "=" + number(n.doubleValue());
            } else {
                String s = String.valueOf(v);
                token = p.key() + "=" + (s.contains(" ") || s.isEmpty() ? "[" + s + "]" : s);
            }
            if (opts.length() > 0) opts.append(' ');
            opts.append(token);
        }
        String label = schema.label().replace("\"", "\\\"");
        return opts.length() == 0 ? "run(\"" + label + "\");" : "run(\"" + label + "\", \"" + opts.toString().replace("\"", "\\\"") + "\");";
    }

    static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }
}
