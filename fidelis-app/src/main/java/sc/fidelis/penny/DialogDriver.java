package sc.fidelis.penny;

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.CheckboxGroup;
import java.awt.Choice;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Label;
import java.awt.TextArea;
import java.awt.TextComponent;
import java.awt.TextField;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.TextEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import ij.gui.GenericDialog;
import ij.gui.MessageDialog;
import ij.gui.MultiLineLabel;

/**
 * Reads and operates the original Fiji dialog an engine operation is waiting on (the engine's Guard reports it as
 * {@code DialogPending}): its fields in layout order (number, string, boolean, choice, radio, text) with the label and
 * the macro key ImageJ derives from it, and its buttons. Filling and pressing happen on the EDT through the components'
 * own events, so the plugin reads the values exactly as if the user had typed them; the operation stays the engine's
 * (it is recorded with LEGACY_DIALOG provenance by the engine).
 */
final class DialogDriver {
    private DialogDriver() {}

    /** One control of a dialog. {@code comps} holds the component (or the radio group's checkboxes). */
    record Field(String id, String key, String label, String type, Object value, List<String> choices, List<Component> comps) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("key", key);
            m.put("label", label);
            m.put("type", type);
            m.put("value", value);
            if (choices != null) m.put("choices", choices);
            return m;
        }
    }

    record Described(Window window, String title, String kind, String text, List<Field> fields, List<String> buttons) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            m.put("kind", kind);
            m.put("text", text.length() > 2000 ? text.substring(0, 2000) + "…" : text);
            List<Map<String, Object>> fs = new ArrayList<>();
            for (Field f : fields) fs.add(f.json());
            m.put("fields", fs);
            m.put("buttons", buttons);
            return m;
        }
    }

    /** The EDT did not run the work in time; {@code applied} is unknown when it had already started. */
    static final class EdtTimeout extends IllegalStateException {
        final boolean notApplied;

        EdtTimeout(boolean notApplied) {
            super(notApplied ? "the Fidelis event thread was busy for 20 s; nothing was changed in the dialog"
                    : "the Fidelis event thread was busy for 20 s while answering the dialog; whether it was applied is unknown");
            this.notApplied = notApplied;
        }
    }

    static <T> T onEdt(Callable<T> c) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        FutureTask<T> task = new FutureTask<>(() -> {
            started.set(true);
            return c.call();
        });
        SwingUtilities.invokeLater(task);
        try {
            return task.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException re) throw re;
            throw new IllegalStateException(t);
        } catch (java.util.concurrent.TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // a queued task must not fill or press the dialog later, after the caller was told it failed
            // FutureTask.run skips a cancelled task, so "cancelled and not started" means it will never run
            boolean cancelled = task.cancel(false);
            throw new EdtTimeout(cancelled && !started.get());
        }
    }

    /** Showing dialogs that are not error dialogs (the Guard captures and closes those), oldest first. */
    static List<Window> showing() {
        return onEdt(() -> {
            List<Window> out = new ArrayList<>();
            for (Window w : Window.getWindows()) {
                if (!w.isShowing() || !(w instanceof Dialog d)) continue;
                if (w instanceof MessageDialog) continue;
                String t = d.getTitle() == null ? "" : d.getTitle();
                if (t.equals("Macro Error") || t.equals("No Image")) continue;
                out.add(w);
            }
            return out;
        });
    }

    /** The showing dialog with this title and class (the newest when several match), or null. */
    static Window find(String title, String className) {
        Window hit = null;
        for (Window w : showing()) {
            String t = ((Dialog) w).getTitle() == null ? "" : ((Dialog) w).getTitle();
            if ((title == null || title.equals(t)) && (className == null || className.equals(w.getClass().getName()))) hit = w;
        }
        return hit;
    }

    static String macroKey(String label) {
        String s = label == null ? "" : label.trim();
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp);
        s = s.toLowerCase(Locale.ROOT);
        while (s.endsWith(":")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static Described describe(Window w) {
        return onEdt(() -> describeOnEdt(w));
    }

    private static Described describeOnEdt(Window w) {
        String title = w instanceof Dialog d && d.getTitle() != null ? d.getTitle() : "";
        if (w instanceof FileDialog fd) {
            return new Described(w, title, "file", "An operating-system file chooser (" + (fd.getMode() == FileDialog.LOAD ? "open" : "save")
                    + "); it cannot be filled from Penny: Cancel it and use open_path, or answer it in Fidelis.", List.of(), List.of("Cancel"));
        }
        List<Field> fields = new ArrayList<>();
        List<String> buttons = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<GroupAcc> groups = new ArrayList<>();
        walk(w, new String[] {null}, fields, buttons, text, groups);
        for (GroupAcc g : groups) {
            String value = null;
            List<String> choices = new ArrayList<>();
            for (Component c : g.boxes) {
                String l = label(c instanceof Checkbox cb ? cb.getLabel() : ((AbstractButton) c).getText());
                choices.add(l);
                if (c instanceof Checkbox cb ? cb.getState() : ((AbstractButton) c).isSelected()) value = l;
            }
            fields.add(g.index, new Field("f" + g.index, macroKey(g.label), g.label, "radio", value, choices, List.copyOf(g.boxes)));
        }
        List<Field> numbered = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            numbered.add(new Field("f" + i, f.key(), f.label(), f.type(), f.value(), f.choices(), f.comps()));
        }
        String kind = w instanceof GenericDialog ? "generic" : w instanceof JDialog ? "swing" : "awt";
        return new Described(w, title, kind, text.toString().strip(), numbered, buttons);
    }

    private static final class GroupAcc {
        final Object group;
        final String label;
        final int index;
        final List<Component> boxes = new ArrayList<>();

        GroupAcc(Object group, String label, int index) {
            this.group = group;
            this.label = label == null ? "" : label;
            this.index = index;
        }
    }

    private static void walk(Component c, String[] lastLabel, List<Field> fields, List<String> buttons, StringBuilder text,
                             List<GroupAcc> groups) {
        if (!c.isVisible()) return;
        if (c instanceof MultiLineLabel || c instanceof Label || c instanceof JLabel) {
            String t = c instanceof Label l ? l.getText() : c instanceof JLabel jl ? jl.getText() : multiLine((MultiLineLabel) c);
            if (t != null && !t.isBlank()) {
                lastLabel[0] = t.trim();
                if (text.length() > 0) text.append('\n');
                text.append(t.trim());
            }
        } else if (c instanceof TextField tf) {
            boolean numeric = tf.getParent() != null && isNumeric(tf);
            fields.add(new Field("", macroKey(lastLabel[0]), label(lastLabel[0]), numeric ? "number" : "string", tf.getText(), null, List.of(tf)));
        } else if (c instanceof TextArea ta) {
            fields.add(new Field("", macroKey(lastLabel[0]), label(lastLabel[0]), "text", ta.getText(), null, List.of(ta)));
        } else if (c instanceof Checkbox cb) {
            CheckboxGroup g = cb.getCheckboxGroup();
            if (g == null) fields.add(new Field("", macroKey(cb.getLabel()), label(cb.getLabel()), "boolean", cb.getState(), null, List.of(cb)));
            else group(groups, g, lastLabel[0], fields.size() + groups.size()).boxes.add(cb);
        } else if (c instanceof Choice ch) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < ch.getItemCount(); i++) items.add(ch.getItem(i));
            fields.add(new Field("", macroKey(lastLabel[0]), label(lastLabel[0]), "choice", ch.getSelectedItem(), items, List.of(ch)));
        } else if (c instanceof Button b) {
            if (b.getLabel() != null && !b.getLabel().isBlank()) buttons.add(b.getLabel().trim());
        } else if (c instanceof JRadioButton rb) {
            Object g = rb.getModel() instanceof javax.swing.DefaultButtonModel m ? m.getGroup() : null;
            if (g == null) fields.add(new Field("", macroKey(rb.getText()), label(rb.getText()), "boolean", rb.isSelected(), null, List.of(rb)));
            else group(groups, g, lastLabel[0], fields.size() + groups.size()).boxes.add(rb);
        } else if (c instanceof JCheckBox jc) {
            fields.add(new Field("", macroKey(jc.getText()), label(jc.getText()), "boolean", jc.isSelected(), null, List.of(jc)));
        } else if (c instanceof AbstractButton ab) {
            if (ab.getText() != null && !ab.getText().isBlank()) buttons.add(ab.getText().trim());
        } else if (c instanceof JComboBox<?> cb) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < cb.getItemCount(); i++) items.add(String.valueOf(cb.getItemAt(i)));
            fields.add(new Field("", macroKey(lastLabel[0]), label(lastLabel[0]), "choice", String.valueOf(cb.getSelectedItem()), items, List.of(cb)));
        } else if (c instanceof JTextComponent jt) {
            fields.add(new Field("", macroKey(lastLabel[0]), label(lastLabel[0]), "string", jt.getText(), null, List.of(jt)));
        }
        if (c instanceof Container cont && !(c instanceof JComboBox)) {
            for (Component child : cont.getComponents()) walk(child, lastLabel, fields, buttons, text, groups);
        }
    }

    private static String label(String s) {
        return s == null ? "" : s.trim();
    }

    private static GroupAcc group(List<GroupAcc> groups, Object g, String label, int index) {
        for (GroupAcc a : groups) if (a.group == g) return a;
        GroupAcc a = new GroupAcc(g, label, index);
        groups.add(a);
        return a;
    }

    /** MultiLineLabel keeps its text in private fields (read the same way as the engine's Guard does). */
    private static String multiLine(MultiLineLabel m) {
        try {
            java.lang.reflect.Field f = MultiLineLabel.class.getDeclaredField("lines");
            f.setAccessible(true);
            String[] lines = (String[]) f.get(m);
            int n = lines == null ? 0 : lines.length;
            try {
                java.lang.reflect.Field nf = MultiLineLabel.class.getDeclaredField("num_lines");
                nf.setAccessible(true);
                n = Math.min(n, nf.getInt(m));
            } catch (ReflectiveOperationException ignored) {
                // all lines
            }
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < n; i++) t.append(i > 0 ? "\n" : "").append(lines[i]);
            return t.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isNumeric(TextField tf) {
        for (Container p = tf.getParent(); p != null; p = p.getParent()) {
            if (p instanceof GenericDialog gd) {
                java.util.Vector<?> nums = gd.getNumericFields();
                return nums != null && nums.contains(tf);
            }
        }
        return false;
    }

    /**
     * Fill {@code values} (by field id, key or label, case-insensitively) and press {@code press} (a button label;
     * null presses nothing). Refuses unknown fields, impossible values and unknown buttons before touching anything.
     */
    static Map<String, Object> act(Window w, Map<String, Object> values, String press) {
        return onEdt(() -> {
            if (!w.isShowing()) throw new IllegalStateException("the dialog closed before Penny could answer it");
            Described d = describeOnEdt(w);
            if (w instanceof FileDialog fd) {
                if (press == null || !press.equalsIgnoreCase("Cancel") || !values.isEmpty())
                    throw new IllegalArgumentException("an operating-system file chooser can only be cancelled from Penny (press: \"Cancel\")");
                fd.dispose();
                return Map.of("pressed", "Cancel", "set", List.of());
            }
            List<Runnable> edits = new ArrayList<>();
            List<String> set = new ArrayList<>();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                Field f = match(d.fields(), e.getKey());
                if (f == null) {
                    List<String> names = new ArrayList<>();
                    for (Field x : d.fields()) names.add(x.id() + " " + x.key() + " (" + x.label() + ")");
                    throw new IllegalArgumentException("the dialog '" + d.title() + "' has no field '" + e.getKey() + "'; fields: " + names);
                }
                edits.add(edit(f, e.getValue()));
                set.add(f.id() + "=" + e.getValue());
            }
            Component button = null;
            if (press != null) {
                button = findButton(w, press);
                if (button == null) throw new IllegalArgumentException("the dialog '" + d.title() + "' has no button '" + press + "'; buttons: " + d.buttons());
            }
            for (Runnable r : edits) r.run();
            if (button instanceof Button b) b.dispatchEvent(new ActionEvent(b, ActionEvent.ACTION_PERFORMED, b.getActionCommand()));
            else if (button instanceof AbstractButton ab) ab.doClick(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pressed", press);
            m.put("set", set);
            return m;
        });
    }

    private static Field match(List<Field> fields, String name) {
        for (Field f : fields) if (f.id().equals(name)) return f;
        for (Field f : fields) if (!f.key().isEmpty() && f.key().equalsIgnoreCase(name)) return f;
        for (Field f : fields) if (f.label().equalsIgnoreCase(name) || f.label().replaceAll(":$", "").equalsIgnoreCase(name)) return f;
        return null;
    }

    private static Runnable edit(Field f, Object v) {
        Component c = f.comps().get(0);
        String s = v == null ? "" : v instanceof Double dd && dd == Math.rint(dd) ? String.valueOf(dd.longValue()) : String.valueOf(v);
        switch (f.type()) {
            case "number" -> {
                try {
                    Double.parseDouble(s.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("field '" + f.key() + "' needs a number, got '" + s + "'");
                }
                return () -> setText(c, s);
            }
            case "string", "text" -> {
                return () -> setText(c, s);
            }
            case "boolean" -> {
                boolean b;
                if (v instanceof Boolean bb) b = bb;
                else if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) b = Boolean.parseBoolean(s);
                else throw new IllegalArgumentException("field '" + f.key() + "' needs true or false, got '" + s + "'");
                return () -> {
                    if (c instanceof Checkbox cb) {
                        cb.setState(b);
                        cb.dispatchEvent(new ItemEvent(cb, ItemEvent.ITEM_STATE_CHANGED, cb, b ? ItemEvent.SELECTED : ItemEvent.DESELECTED));
                    } else if (c instanceof AbstractButton ab && ab.isSelected() != b) ab.doClick(0);
                };
            }
            case "choice" -> {
                String hit = null;
                for (String x : f.choices()) if (x.equals(s)) hit = x;
                if (hit == null) for (String x : f.choices()) if (x.equalsIgnoreCase(s)) hit = x;
                if (hit == null) throw new IllegalArgumentException("field '" + f.key() + "' must be one of " + f.choices() + ", got '" + s + "'");
                final String item = hit;
                return () -> {
                    if (c instanceof Choice ch) {
                        ch.select(item);
                        ch.dispatchEvent(new ItemEvent(ch, ItemEvent.ITEM_STATE_CHANGED, item, ItemEvent.SELECTED));
                    } else if (c instanceof JComboBox<?> cb) {
                        for (int i = 0; i < cb.getItemCount(); i++) if (String.valueOf(cb.getItemAt(i)).equals(item)) cb.setSelectedIndex(i);
                    }
                };
            }
            case "radio" -> {
                Component hit = null;
                for (Component box : f.comps()) {
                    String l = label(box instanceof Checkbox cb ? cb.getLabel() : ((AbstractButton) box).getText());
                    if (l.equalsIgnoreCase(s.trim())) hit = box;
                }
                if (hit == null) throw new IllegalArgumentException("field '" + f.key() + "' must be one of " + f.choices() + ", got '" + s + "'");
                final Component box = hit;
                return () -> {
                    if (box instanceof Checkbox cb) {
                        cb.setState(true);
                        cb.dispatchEvent(new ItemEvent(cb, ItemEvent.ITEM_STATE_CHANGED, cb, ItemEvent.SELECTED));
                    } else ((AbstractButton) box).doClick(0);
                };
            }
            default -> throw new IllegalArgumentException("field '" + f.key() + "' cannot be set");
        }
    }

    private static void setText(Component c, String s) {
        if (c instanceof TextComponent t) {
            t.setText(s);
            t.dispatchEvent(new TextEvent(t, TextEvent.TEXT_VALUE_CHANGED));
        } else if (c instanceof JTextComponent jt) {
            jt.setText(s);
        }
    }

    private static Component findButton(Container root, String label) {
        Component[] hit = {null};
        find(root, label, hit);
        return hit[0];
    }

    private static void find(Component c, String label, Component[] hit) {
        if (hit[0] != null || !c.isVisible()) return;
        String want = label.trim();
        if (c instanceof Button b && b.getLabel() != null && want.equalsIgnoreCase(b.getLabel().trim())) hit[0] = b;
        else if (c instanceof AbstractButton ab && !(c instanceof JCheckBox) && !(c instanceof JRadioButton) && ab.getText() != null
                && want.equalsIgnoreCase(ab.getText().trim())) hit[0] = ab;
        if (c instanceof Container cont) for (Component child : cont.getComponents()) find(child, label, hit);
    }

    /** True once the window is gone (closed, hidden or disposed), waiting at most {@code ms}. */
    static boolean awaitClosed(Window w, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (!onEdt(w::isShowing)) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !onEdt(w::isShowing);
    }
}
