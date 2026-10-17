package sc.fidelis.engine;

import java.awt.Checkbox;
import java.awt.CheckboxGroup;
import java.awt.Choice;
import java.awt.Component;
import java.awt.Container;
import java.awt.Label;
import java.awt.TextField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import ij.ImagePlus;
import ij.Macro;
import ij.gui.GenericDialog;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.Enums.ParameterType;

/**
 * The answers a person gave in the original dialog of a command run through Fiji itself (#5 interactive parity),
 * turned into the macro options IJ1 would have recorded, so an observed command replays exactly.
 *
 * <p>IJ1 derives an option key from a field's label with {@code Macro.trimKey} (first word, cut at ':', lower
 * case), but the dialog shows the label with every underscore replaced by a space ("Area_fraction" is shown as
 * "Area fraction" and keyed {@code area_fraction}). A shown label therefore only narrows the key down to one of its
 * word prefixes joined by underscores; the command's catalogued schema decides which (the schema lists every key
 * the command accepts). The options string is then built by {@link Options#validate}, the same builder the typed
 * path uses. A dialog field that matches no schema key, or a command without a complete schema, gives a
 * best-effort options string that is marked inexact.
 */
final class NativeDialogs {
    private NativeDialogs() {}

    enum Kind { CHECKBOX, NUMBER, STRING, CHOICE, RADIO }

    /** One answered field: its kind, the label shown, and the value (text, selected item, or checkbox state). */
    record Field(Kind kind, String label, String text, boolean state) {
        Object value() {
            return kind == Kind.CHECKBOX ? (Object) state : text;
        }
    }

    /** The options of one observed command: the macro options string, the typed values, and whether it is exact. */
    record Recorded(String options, Map<String, Object> params, boolean exact, List<String> unmatched) {
    }

    // ---- reading a dialog -------------------------------------------------------------------------

    /** The fields of an OK'ed GenericDialog in the order IJ1 adds them (the preview checkbox is not an option). */
    @SuppressWarnings("unchecked")
    static List<Field> read(GenericDialog gd) {
        List<Component> order = new ArrayList<>();
        walk(gd, order);
        Map<Component, Integer> at = new IdentityHashMap<>();
        for (int i = 0; i < order.size(); i++) at.put(order.get(i), i);
        Checkbox preview = gd.getPreviewCheckbox();
        List<Field> out = new ArrayList<>();
        List<Component> fields = new ArrayList<>();
        for (Object o : orEmpty(gd.getCheckboxes())) if (o != preview) fields.add((Component) o);
        for (Object o : orEmpty(gd.getNumericFields())) fields.add((Component) o);
        for (Object o : orEmpty(gd.getStringFields())) fields.add((Component) o);
        for (Object o : orEmpty(gd.getChoices())) fields.add((Component) o);
        fields.sort(Comparator.comparingInt(c -> at.getOrDefault(c, Integer.MAX_VALUE)));
        Vector<?> numeric = orEmpty(gd.getNumericFields());
        for (Component c : fields) {
            if (c instanceof Checkbox cb) {
                out.add(new Field(Kind.CHECKBOX, cb.getLabel(), null, cb.getState()));
            } else if (c instanceof TextField tf) {
                out.add(new Field(numeric.contains(tf) ? Kind.NUMBER : Kind.STRING, precedingLabel(order, at, tf), tf.getText(), false));
            } else if (c instanceof Choice ch) {
                out.add(new Field(Kind.CHOICE, precedingLabel(order, at, ch), ch.getSelectedItem(), false));
            }
        }
        for (Object o : orEmpty(gd.getRadioButtonGroups())) {
            if (!(o instanceof CheckboxGroup group)) continue;
            Checkbox selected = group.getSelectedCheckbox();
            Component first = null;
            for (Component c : order) if (c instanceof Checkbox cb && cb.getCheckboxGroup() == group) { first = c; break; }
            String label = first == null ? "" : precedingLabel(order, at, first);
            out.add(new Field(Kind.RADIO, label, selected == null ? "" : selected.getLabel(), false));
        }
        return out;
    }

    private static Vector<?> orEmpty(Vector<?> v) {
        return v == null ? new Vector<>() : v;
    }

    private static void walk(Container c, List<Component> out) {
        for (Component k : c.getComponents()) {
            out.add(k);
            if (k instanceof Container kc && !(k instanceof Choice)) walk(kc, out);
        }
    }

    /** GenericDialog adds a field's Label right before the field (or before the panel holding it). */
    private static String precedingLabel(List<Component> order, Map<Component, Integer> at, Component field) {
        Integer i = at.get(field);
        if (i == null) return "";
        for (int k = i - 1; k >= 0; k--) if (order.get(k) instanceof Label l) return l.getText();
        return "";
    }

    // ---- keys -----------------------------------------------------------------------------------------

    /**
     * Keys IJ1 may have derived from a shown label: the original label had an underscore or a space at each shown
     * space, so the key is one of the word prefixes joined by underscores, trimmed as {@code Macro.trimKey} does.
     */
    static List<String> candidateKeys(String shown) {
        List<String> out = new ArrayList<>();
        if (shown == null) return out;
        String[] words = shown.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append('_');
            sb.append(words[i]);
            String k = Macro.trimKey(sb.toString());
            if (!k.isEmpty() && !out.contains(k)) out.add(k);
            if (words[i].contains(":")) break; // the key never extends past a colon
        }
        return out;
    }

    private static boolean compatible(ParameterType t, Kind k) {
        return switch (t) {
            case BOOLEAN -> k == Kind.CHECKBOX;
            case NUMBER, INTEGER -> k == Kind.NUMBER || k == Kind.STRING;
            case CHOICE, IMAGE_REF -> k == Kind.CHOICE || k == Kind.RADIO;
            case STRING, PATH -> k == Kind.STRING || k == Kind.NUMBER || k == Kind.CHOICE;
        };
    }

    /**
     * Assign schema keys to dialog fields: each key to a field whose candidate keys contain it, keys with the fewest
     * possible fields first (so "area_fraction" takes "Area fraction" before "area" chooses between "Area" and it).
     * Returns field index to key; fields that no key claims are absent from the map.
     */
    static Map<Integer, String> assign(List<ParameterSchema> params, List<Field> fields) {
        List<List<String>> cands = new ArrayList<>();
        for (Field f : fields) cands.add(candidateKeys(f.label()));
        record Option(ParameterSchema p, List<Integer> fields) {
        }
        List<Option> options = new ArrayList<>();
        for (ParameterSchema p : params) {
            List<Integer> fit = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                if (compatible(p.type(), fields.get(i).kind()) && cands.get(i).contains(p.key())) fit.add(i);
            }
            if (!fit.isEmpty()) options.add(new Option(p, fit));
        }
        options.sort(Comparator.comparingInt(o -> o.fields().size()));
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Option o : options) {
            Integer best = null;
            for (Integer i : o.fields()) {
                if (out.containsKey(i)) continue;
                if (best == null || cands.get(i).size() < cands.get(best).size()) best = i;
            }
            if (best != null) out.put(best, o.p().key());
        }
        return out;
    }

    // ---- options --------------------------------------------------------------------------------------

    /**
     * The options IJ1 would have recorded for these answers. {@code stack} is the answer to IJ1's "Process all
     * N images?" question (null when it was not asked).
     */
    static Recorded record(Catalogue.Entry entry, List<Field> fields, Boolean stack, ImagePlus imp, Registry registry) {
        CommandSchema schema = entry == null ? null : entry.schema;
        if (schema == null || !schema.schemaComplete()) return bestEffort(fields, stack);
        Map<Integer, String> keys = assign(schema.parameters(), fields);
        List<String> unmatched = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            String key = keys.get(i);
            Field f = fields.get(i);
            if (key == null) {
                unmatched.add(f.label());
                continue;
            }
            ParameterSchema p = schema.parameter(key).orElseThrow();
            Object v = f.value();
            if ((p.type() == ParameterType.NUMBER || p.type() == ParameterType.INTEGER) && v instanceof String s) {
                double d = ij.util.Tools.parseDouble(s.trim());
                if (!Double.isNaN(d)) v = d;
            }
            params.put(key, v);
        }
        // keys the dialog did not show are absent in a recorded macro: booleans are false, the rest optional
        for (ParameterSchema p : schema.parameters()) {
            if (params.containsKey(p.key())) continue;
            if (p.key().equals("stack") && stack != null) params.put("stack", stack);
            else if (p.type() == ParameterType.BOOLEAN) params.put(p.key(), false);
        }
        try {
            Options.Validated v = Options.validate(entry, params, imp, registry);
            Map<String, Object> typed = new LinkedHashMap<>(v.typed());
            return new Recorded(v.options(), typed, unmatched.isEmpty(), unmatched);
        } catch (Options.Invalid e) {
            Recorded r = bestEffort(fields, stack);
            List<String> why = new ArrayList<>(unmatched);
            why.add("invalid: " + e.getMessage());
            return new Recorded(r.options(), r.params(), false, why);
        }
    }

    /** Options from the shown labels alone (no schema): IJ1's key of each label as shown; never claimed exact. */
    static Recorded bestEffort(List<Field> fields, Boolean stack) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        for (Field f : fields) {
            String key = Macro.trimKey(f.label() == null ? "" : f.label().trim());
            if (key.isEmpty()) continue;
            params.put(key, f.value());
            String token;
            if (f.kind() == Kind.CHECKBOX) {
                if (!f.state()) continue;
                token = key;
            } else {
                String t = f.text() == null ? "" : f.text();
                token = key + "=" + (t.isEmpty() ? "[]" : t.contains(" ") ? "[" + t + "]" : t);
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(token);
        }
        if (Boolean.TRUE.equals(stack)) sb.append(sb.length() > 0 ? " " : "").append("stack");
        return new Recorded(sb.toString(), params, false, List.of());
    }
}
