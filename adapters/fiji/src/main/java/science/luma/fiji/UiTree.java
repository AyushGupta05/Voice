package science.luma.fiji;

import com.google.gson.Gson;
import java.awt.*;
import java.awt.event.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Live AWT/Swing windows and their controls (GenericDialogs, plugin frames) with stable ids and global screen bounds. EDT only. */
final class UiTree {
    private static final Gson JSON = new Gson();
    private static final int MAX_CONTROLS = 400;
    private final WeakHashMap<Component, String> ids = new WeakHashMap<>();
    private final Map<String, WeakReference<Component>> components = new HashMap<>();

    private String id(Component component) {
        String id = ids.computeIfAbsent(component, c -> UUID.randomUUID().toString());
        components.put(id, new WeakReference<>(component));
        return id;
    }
    Component component(String id) {
        var reference = components.get(id); var component = reference == null ? null : reference.get();
        if (component == null || !component.isShowing()) throw new IllegalStateException("Control is no longer visible. Inspect UI again.");
        return component;
    }

    Map<String, Object> windows() {
        components.entrySet().removeIf(entry -> entry.getValue().get() == null);
        var windows = new ArrayList<Map<String, Object>>();
        for (var window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            windows.add(describeWindow(window));
        }
        return Map.of("windows", windows, "note", "AWT/Swing controls of visible Fiji windows. Image canvases are described by fiji_context, not here.");
    }
    Map<String, Object> describeWindow(Window window) {
        var controls = new ArrayList<Map<String, Object>>();
        visit(window, controls);
        var item = new LinkedHashMap<String, Object>();
        item.put("id", id(window)); item.put("title", title(window)); item.put("type", window.getClass().getSimpleName());
        item.put("modal", window instanceof Dialog dialog && dialog.isModal()); item.put("focused", window.isFocused()); item.put("active", window.isActive());
        var b = window.getBounds(); item.put("bounds", Map.of("x", b.x, "y", b.y, "width", b.width, "height", b.height));
        item.put("fingerprint", fingerprint(controls)); item.put("controls", controls);
        return item;
    }
    static String title(Window window) {
        if (window instanceof Frame frame) return Objects.toString(frame.getTitle(), "");
        if (window instanceof Dialog dialog) return Objects.toString(dialog.getTitle(), "");
        return window.getClass().getSimpleName();
    }
    private void visit(Component component, List<Map<String, Object>> controls) {
        if (component == null || !component.isVisible() || controls.size() >= MAX_CONTROLS) return;
        var item = describe(component);
        if (item != null) controls.add(item);
        if (component instanceof Container container) for (var child : container.getComponents()) visit(child, controls);
    }
    private Map<String, Object> describe(Component c) {
        String type; String text = null; Object value = null; List<String> options = null; Boolean selected = null; Boolean editable = null;
        if (c instanceof JPasswordField) { type = "password"; text = "[redacted]"; }
        else if (c instanceof JToggleButton toggle) { type = c instanceof JCheckBox ? "checkbox" : c instanceof JRadioButton ? "radio" : "toggle"; text = toggle.getText(); selected = toggle.isSelected(); }
        else if (c instanceof AbstractButton button) { type = "button"; text = button.getText(); }
        else if (c instanceof Button button) { type = "button"; text = button.getLabel(); }
        else if (c instanceof Checkbox box) { type = "checkbox"; text = box.getLabel(); selected = box.getState(); }
        else if (c instanceof Choice choice) { type = "choice"; value = choice.getSelectedItem(); options = new ArrayList<>(); for (int i = 0; i < Math.min(200, choice.getItemCount()); i++) options.add(choice.getItem(i)); }
        else if (c instanceof JComboBox<?> combo) { type = "choice"; value = Objects.toString(combo.getSelectedItem(), ""); options = new ArrayList<>(); for (int i = 0; i < Math.min(200, combo.getItemCount()); i++) options.add(Objects.toString(combo.getItemAt(i), "")); editable = combo.isEditable(); }
        else if (c instanceof TextComponent field) { type = c instanceof TextArea ? "textarea" : "text"; text = field.getText(); editable = field.isEditable(); }
        else if (c instanceof JTextComponent field) { type = c instanceof JTextField ? "text" : "textarea"; text = field.getText(); editable = field.isEditable(); }
        else if (c instanceof Scrollbar bar) { type = "slider"; value = bar.getValue(); options = List.of(String.valueOf(bar.getMinimum()), String.valueOf(bar.getMaximum() - bar.getVisibleAmount())); }
        else if (c instanceof JSlider slider) { type = "slider"; value = slider.getValue(); options = List.of(String.valueOf(slider.getMinimum()), String.valueOf(slider.getMaximum())); }
        else if (c instanceof JSpinner spinner) { type = "spinner"; value = Objects.toString(spinner.getValue(), ""); }
        else if (c instanceof Label label) { type = "label"; text = label.getText(); }
        else if (c instanceof JLabel label) { type = "label"; text = label.getText(); }
        else if (c instanceof java.awt.List list) { type = "list"; value = list.getSelectedItem(); options = Arrays.asList(list.getItems()).subList(0, Math.min(200, list.getItemCount())); }
        else if (c instanceof JList<?> list) { type = "list"; value = Objects.toString(list.getSelectedValue(), ""); options = new ArrayList<>(); for (int i = 0; i < Math.min(200, list.getModel().getSize()); i++) options.add(Objects.toString(list.getModel().getElementAt(i), "")); }
        else if (c instanceof JTabbedPane tabs) { type = "tabs"; value = tabs.getSelectedIndex() < 0 ? "" : tabs.getTitleAt(tabs.getSelectedIndex()); options = new ArrayList<>(); for (int i = 0; i < tabs.getTabCount(); i++) options.add(tabs.getTitleAt(i)); }
        else return null;
        if (type.equals("label") && (text == null || text.isBlank())) return null;
        var item = new LinkedHashMap<String, Object>();
        item.put("id", id(c)); item.put("type", type); item.put("class", c.getClass().getSimpleName()); item.put("enabled", c.isEnabled());
        if (text != null) item.put("text", text.length() > 2000 ? text.substring(0, 2000) : text);
        if (value != null) item.put("value", value instanceof String s && s.length() > 2000 ? s.substring(0, 2000) : value);
        if (options != null) item.put("options", options);
        if (selected != null) item.put("selected", selected);
        if (editable != null) item.put("editable", editable);
        if (c.isShowing()) { try { var p = c.getLocationOnScreen(); var d = c.getSize(); item.put("bounds", Map.of("x", p.x, "y", p.y, "width", d.width, "height", d.height)); } catch (RuntimeException ignored) { /* not realised */ } }
        return item;
    }
    static String fingerprint(List<Map<String, Object>> controls) {
        var states = controls.stream().map(control -> { var state = new TreeMap<String, Object>(control); state.remove("bounds"); state.remove("id"); return state; }).toList();
        return digest(JSON.toJson(states));
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    /** Dialogs that appeared since a set of windows was observed. */
    static List<Window> newDialogs(Set<Window> before) {
        var result = new ArrayList<Window>();
        for (var window : Window.getWindows()) if (window.isShowing() && !before.contains(window) && (window instanceof Dialog || window instanceof Frame)) result.add(window);
        return result;
    }
    static Set<Window> showing() { var set = new HashSet<Window>(); for (var w : Window.getWindows()) if (w.isShowing()) set.add(w); return set; }

    /** Operates a control the way the researcher would. Buttons fire on the next event-queue cycle so modal dialogs never block the response. */
    Map<String, Object> act(String nodeId, String action, com.google.gson.JsonElement value, com.google.gson.JsonObject expected) {
        var component = component(nodeId);
        if (component instanceof JPasswordField) throw new IllegalStateException("Password fields are not exposed to agents.");
        if (!component.isEnabled()) throw new IllegalStateException("Control is disabled. Inspect the dialog before proceeding.");
        if (expected != null) {
            var window = SwingUtilities.getWindowAncestor(component);
            if (window == null && component instanceof Window w) window = w;
            if (window != null && expected.has("windowFingerprint")) {
                var controls = new ArrayList<Map<String, Object>>(); visit(window, controls);
                if (!fingerprint(controls).equals(expected.get("windowFingerprint").getAsString())) throw new IllegalStateException("Dialog values changed. Review the action again.");
            }
            var current = describe(component);
            if (expected.has("type") && current != null && !Objects.equals(current.get("type"), expected.get("type").getAsString())) throw new IllegalStateException("Control type changed. Review the action again.");
            if (expected.has("text") && current != null && !Objects.equals(Objects.toString(current.get("text"), ""), expected.get("text").getAsString())) throw new IllegalStateException("Control text changed. Review the action again.");
        }
        var described = describe(component);
        String label = described == null ? action : Objects.toString(described.get("text"), Objects.toString(described.get("type"), action));
        var highlight = Viewer.componentHighlight(component, label);
        String text = value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : value.toString();
        switch (action) {
            case "fire" -> {
                if (component instanceof AbstractButton button) EventQueue.invokeLater(button::doClick);
                else if (component instanceof Button button) EventQueue.invokeLater(() -> { for (var listener : button.getActionListeners()) listener.actionPerformed(new ActionEvent(button, ActionEvent.ACTION_PERFORMED, button.getActionCommand())); });
                else throw new IllegalArgumentException("Target is not a button.");
            }
            case "set_text" -> {
                if (component instanceof TextComponent field) { field.setText(text); for (var listener : field.getTextListeners()) listener.textValueChanged(new TextEvent(field, TextEvent.TEXT_VALUE_CHANGED)); }
                else if (component instanceof JTextComponent field) field.setText(text);
                else if (component instanceof Scrollbar bar) { int number = (int) Math.round(Double.parseDouble(text)); bar.setValue(number); for (var listener : bar.getAdjustmentListeners()) listener.adjustmentValueChanged(new AdjustmentEvent(bar, AdjustmentEvent.ADJUSTMENT_VALUE_CHANGED, AdjustmentEvent.TRACK, bar.getValue())); }
                else if (component instanceof JSlider slider) slider.setValue((int) Math.round(Double.parseDouble(text)));
                else if (component instanceof JSpinner spinner) { try { spinner.setValue(Double.parseDouble(text)); } catch (IllegalArgumentException error) { spinner.setValue(text); } }
                else if (component instanceof JComboBox<?> combo && combo.isEditable()) combo.setSelectedItem(text);
                else throw new IllegalArgumentException("Target is not a text input or slider.");
            }
            case "toggle" -> {
                if (component instanceof Checkbox box) { boolean state = text.isBlank() ? !box.getState() : Boolean.parseBoolean(text); box.setState(state); for (var listener : box.getItemListeners()) listener.itemStateChanged(new ItemEvent(box, ItemEvent.ITEM_STATE_CHANGED, box.getLabel(), state ? ItemEvent.SELECTED : ItemEvent.DESELECTED)); }
                else if (component instanceof JToggleButton toggle) { boolean state = text.isBlank() ? !toggle.isSelected() : Boolean.parseBoolean(text); if (state != toggle.isSelected()) toggle.doClick(); }
                else throw new IllegalArgumentException("Target is not a checkbox.");
            }
            case "select" -> {
                if (component instanceof Choice choice) {
                    int index = -1; for (int i = 0; i < choice.getItemCount(); i++) if (choice.getItem(i).equals(text)) index = i;
                    if (index < 0) throw new IllegalArgumentException("Value is not one of the choice options. Inspect UI options again.");
                    choice.select(index); for (var listener : choice.getItemListeners()) listener.itemStateChanged(new ItemEvent(choice, ItemEvent.ITEM_STATE_CHANGED, text, ItemEvent.SELECTED));
                } else if (component instanceof JComboBox<?> combo) {
                    int index = -1; for (int i = 0; i < combo.getItemCount(); i++) if (Objects.toString(combo.getItemAt(i), "").equals(text)) index = i;
                    if (index < 0) throw new IllegalArgumentException("Value is not one of the choice options. Inspect UI options again.");
                    combo.setSelectedIndex(index);
                } else if (component instanceof java.awt.List list) {
                    int index = Arrays.asList(list.getItems()).indexOf(text); if (index < 0) throw new IllegalArgumentException("Value is not in the list.");
                    list.select(index); for (var listener : list.getItemListeners()) listener.itemStateChanged(new ItemEvent(list, ItemEvent.ITEM_STATE_CHANGED, text, ItemEvent.SELECTED));
                } else if (component instanceof JList<?> list) {
                    int index = -1; for (int i = 0; i < list.getModel().getSize(); i++) if (Objects.toString(list.getModel().getElementAt(i), "").equals(text)) index = i;
                    if (index < 0) throw new IllegalArgumentException("Value is not in the list."); list.setSelectedIndex(index);
                } else if (component instanceof JTabbedPane tabs) {
                    int index = tabs.indexOfTab(text); if (index < 0) throw new IllegalArgumentException("Tab not found."); tabs.setSelectedIndex(index);
                } else throw new IllegalArgumentException("Target is not a choice, list or tab control.");
            }
            default -> throw new IllegalArgumentException("Unknown UI action: " + action);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("dispatched", true); result.put("action", action); result.put("control", describe(component)); result.put("highlight", highlight);
        result.put("note", action.equals("fire") ? "The button fires on the event queue; inspect UI and context afterwards." : "Value set; the dialog applies it when OK is pressed.");
        return result;
    }
}
