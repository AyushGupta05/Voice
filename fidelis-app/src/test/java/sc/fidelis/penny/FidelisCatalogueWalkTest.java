package sc.fidelis.penny;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sc.fidelis.penny.BridgeHttp.args;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ij.gui.ImageWindow;
import ij.text.TextWindow;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IsolationState;
import sc.fidelis.testkit.PixelHash;
import sc.fidelis.testkit.ProductEngine;

/**
 * Any Fiji command through Penny: a diverse sample of the live catalogue (File, Edit, Image, Process, Analyze, Plugins)
 * run through the bridge's {@code command} on Blobs. Each one must complete, stop at its original dialog (which
 * {@code dialog_action} Cancel then closes), or be refused with the engine's own error, or (file choosers) by the bridge;
 * never hang, and never leave a dialog open. Frames a command opens on purpose (Brightness/Contrast, Recorder…) are
 * reported and closed by the test.
 */
class FidelisCatalogueWalkTest {
    /** Menu paths in the live catalogue; resolved at run time (by id, else by label). */
    private static final List<String> SAMPLE = List.of(
            "File>New>Hyperstack...", "File>New>Text Window", "File>Import>Image Sequence...",
            "Edit>Selection>Select All", "Edit>Selection>Enlarge...", "Edit>Selection>Make Inverse", "Edit>Options>Appearance...",
            "Edit>Draw",
            "Image>Adjust>Brightness/Contrast...", "Image>Adjust>Canvas Size...", "Image>Rename...", "Image>Properties...",
            "Image>Show Info...", "Image>Transform>Flip Horizontally", "Image>Transform>Rotate 90 Degrees Right",
            "Image>Lookup Tables>Fire", "Image>Overlay>Add Selection...",
            "Process>Noise>Add Noise", "Process>Noise>Remove Outliers...", "Process>Shadows>North", "Process>Find Edges",
            "Process>Math>Gamma...", "Process>FFT>FFT", "Process>Filters>Variance...", "Process>Binary>Skeletonize",
            "Analyze>Histogram", "Analyze>Surface Plot...", "Analyze>Calibrate...", "Analyze>Tools>Scale Bar...",
            "Analyze>Distribution...",
            "Plugins>Macros>Record...", "Plugins>Bio-Formats>Bio-Formats Importer", "Analyze>Colocalization>Coloc 2",
            "Analyze>3D Objects Counter", "Analyze>Directionality", "Plugins>Tracking>TrackMate");

    private Engine engine;
    private FidelisBridge bridge;
    private BridgeHttp http;

    @BeforeEach
    void setUp() {
        engine = ProductEngine.get();
        IsolationState.reset("FidelisCatalogueWalkTest");
        PennyUi.register(null);
        bridge = FidelisBridge.start(engine, BridgeHttp.TOKEN, 0, PennyOps.ConfirmMode.AUTO);
        http = new BridgeHttp(bridge.port(), BridgeHttp.TOKEN);
        http.taskId = "task-walk";
    }

    @AfterEach
    void tearDown() {
        // a failed assertion can leave an original dialog open, blocking the engine: cancel it before resetting
        for (Window w : DialogDriver.showing()) {
            try {
                DialogDriver.act(w, Map.of(), "Cancel");
            } catch (RuntimeException e) {
                try {
                    SwingUtilities.invokeAndWait(w::dispose);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
        if (bridge != null) bridge.stop();
        IsolationState.reset("FidelisCatalogueWalkTest");
    }

    private DocumentId openBlobs() throws Exception {
        Path blobs = Fixtures.require("FX-003", "blobs.gif");
        return new DocumentId(http.change("open_path", args("path", blobs.toString())).result().getAsJsonObject("document").get("id").getAsString());
    }

    private Optional<String> resolve(String menuPath) {
        String id = "ij1:" + menuPath;
        if (engine.schema(id).isPresent()) return Optional.of(id);
        String label = menuPath.substring(menuPath.lastIndexOf('>') + 1);
        for (CommandSchema.Summary s : engine.catalogue()) if (s.label().trim().equals(label.trim())) return Optional.of(s.commandId());
        return Optional.empty();
    }

    private static Set<Window> showingWindows() throws Exception {
        Set<Window> out = Collections.newSetFromMap(new IdentityHashMap<>());
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) if (w.isShowing()) out.add(w);
        });
        return out;
    }

    /** Close (on the EDT) the windows that appeared since {@code before}; returns their titles. Dialogs are reported apart. */
    private static List<String> closeNew(Set<Window> before, List<String> strayDialogs) throws Exception {
        List<String> closed = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing() || before.contains(w) || w instanceof ImageWindow) continue;
                String title = w instanceof Frame f ? f.getTitle() : w instanceof Dialog d ? d.getTitle() : w.getClass().getSimpleName();
                if (w instanceof Dialog d && d.isModal()) strayDialogs.add(title); // a non-modal tool window (B&C) is a result
                closed.add(title);
                if (w instanceof TextWindow tw) tw.close(false);
                else w.dispose();
            }
        });
        return closed;
    }

    /** Run one command the way the gateway would: confirm when explicit, cancel any dialog; returns a one-line outcome. */
    private String run(String id, List<String> problems) throws Exception {
        BridgeHttp.Resp r = http.change("command", args("commandId", id));
        for (int round = 0; round < 6; round++) {
            if (r.status() == 409 && r.body().has("running") && r.body().get("running").getAsBoolean()) {
                problems.add(id + ": still running after 115 s (hang)");
                return "HANG " + r.error();
            }
            if (r.status() != 200) {
                String code = r.body().has("code") && !r.body().get("code").isJsonNull() ? r.body().get("code").getAsString() : "";
                return "refused (" + r.status() + (code.isEmpty() ? "" : " " + code) + "): " + r.error().replace('\n', ' ');
            }
            JsonObject res = r.result();
            String status = res.get("status").getAsString();
            switch (status) {
                case "awaiting_confirmation" -> r = http.change("confirm", args("planId", res.get("planId").getAsString()));
                case "dialog" -> {
                    JsonObject d = res.getAsJsonObject("dialog");
                    String what = "dialog '" + d.get("title").getAsString() + "' (" + d.getAsJsonArray("fields").size() + " fields, buttons "
                            + d.getAsJsonArray("buttons") + ")";
                    List<String> buttons = new ArrayList<>();
                    for (JsonElement b : d.getAsJsonArray("buttons")) buttons.add(b.getAsString());
                    String press = buttons.stream().filter(b -> b.equalsIgnoreCase("Cancel")).findFirst()
                            .orElse(buttons.stream().filter(b -> b.equalsIgnoreCase("No")).findFirst().orElse(buttons.isEmpty() ? "OK" : buttons.get(0)));
                    BridgeHttp.Resp after = http.change("dialog_action", args("dialogId", res.get("dialogId").getAsString(), "press", press));
                    String next = after.status() == 200 ? after.result().get("status").getAsString() : after.status() + " " + after.error();
                    if (after.status() == 200 && "dialog".equals(next)) {
                        r = after;
                        continue;
                    }
                    return what + " -> " + press + " -> " + next.replace('\n', ' ');
                }
                case "completed" -> {
                    String wins = res.has("windowsOpened") ? " windows " + res.get("windowsOpened") : "";
                    return "completed (" + (res.has("provenance") ? res.get("provenance").getAsString() : "?") + ")" + wins;
                }
                default -> {
                    return "status " + status;
                }
            }
        }
        problems.add(id + ": more than five dialogs in a row");
        return "too many dialogs";
    }

    @Test
    void diverseCatalogueCommandsCompleteOrStopAtTheirDialogAndCancelCleanly() throws Exception {
        assertTrue(engine.catalogue().size() > 900, "catalogue size " + engine.catalogue().size());
        List<String> problems = new ArrayList<>();
        Map<String, String> outcomes = new LinkedHashMap<>();
        int ran = 0;
        for (String path : SAMPLE) {
            Optional<String> id = resolve(path);
            if (id.isEmpty()) {
                outcomes.put(path, "not in this Fiji's catalogue");
                continue;
            }
            if (engine.documents().isEmpty() || engine.focused().isEmpty()) openBlobs();
            Set<Window> before = showingWindows();
            String outcome = run(id.get(), problems);
            ran++;
            List<String> strayDialogs = new ArrayList<>();
            List<String> closed = closeNew(before, strayDialogs);
            if (!strayDialogs.isEmpty()) problems.add(id.get() + ": dialog left open " + strayDialogs);
            outcomes.put(id.get(), outcome + (closed.isEmpty() ? "" : " | closed by the test: " + closed));
            assertTrue(DialogDriver.showing().isEmpty(), id.get() + " left a dialog: " + DialogDriver.showing());
            if (outcome.contains("409") && outcome.contains("anceled")) problems.add(id.get() + ": a cancelled dialog was reported as a failure");
            if (engine.focused().isPresent() && engine.document(engine.focused().get()).map(d -> d.isStack()).orElse(false)) {
                IsolationState.reset("FidelisCatalogueWalkTest " + id.get()); // a stack from Hyperstack/FFT: start the next one on Blobs
            }
        }
        System.out.println("[penny-walk] catalogue " + engine.catalogue().size() + " commands; sample of " + SAMPLE.size() + ", ran " + ran);
        outcomes.forEach((k, v) -> System.out.println("[penny-walk] " + k + " => " + v));
        assertTrue(ran >= 30, "ran " + ran);
        assertTrue(problems.isEmpty(), "problems: " + problems);
        assertTrue(outcomes.values().stream().filter(o -> o.contains("FILE_CHOOSER")).count() >= 2, "file choosers are refused: " + outcomes);
        assertTrue(outcomes.values().stream().anyMatch(o -> o.startsWith("dialog") && o.contains("-> Cancel -> cancelled")),
                "some commands stopped at their dialog and were cancelled");
        assertTrue(outcomes.values().stream().anyMatch(o -> o.startsWith("completed")), "some commands completed");
    }

    @Test
    void commandsFindsPluginsAcrossTheWholeCatalogueWithTheirMenuPaths() throws Exception {
        Map<String, String> want = new LinkedHashMap<>();
        want.put("TrackMate", "TrackMate");
        want.put("Bio-Formats importer", "Bio-Formats Importer");
        want.put("coloc 2", "Coloc 2");
        want.put("3D objects counter", "3D Objects Counter");
        want.put("skeletonize", "Skeletonize");
        want.put("directionality", "Directionality");
        want.put("analyze skeleton", "Analyze Skeleton (2D/3D)");
        for (Map.Entry<String, String> e : want.entrySet()) {
            JsonObject r = http.read("commands", args("query", e.getKey(), "limit", 10));
            String found = null;
            for (JsonElement c : r.getAsJsonArray("commands")) {
                JsonObject o = c.getAsJsonObject();
                if (o.get("label").getAsString().trim().equalsIgnoreCase(e.getValue())) found = o.get("menuPath").getAsString() + " > " + o.get("label").getAsString()
                        + " [" + o.get("commandId").getAsString() + (o.has("route") ? ", " + o.get("route").getAsString() : "") + "]";
            }
            System.out.println("[penny-walk] commands \"" + e.getKey() + "\" -> " + found);
            assertTrue(found != null, e.getKey() + " not found: " + r);
        }
    }

    @Test
    void aLegacyDialogIsFilledAndPressedThroughTheBridgeAndRecordedByTheEngine() throws Exception {
        DocumentId doc = openBlobs();
        String id = resolve("Process>Filters>Variance...").orElseThrow();
        assertTrue(!engine.schema(id).orElseThrow().schemaComplete(), "Variance... has no catalogued schema here");
        String before = PixelHash.sha256(engine.imagePlus(doc));
        int h0 = engine.history().size();
        JsonObject first = http.change("command", args("commandId", id)).result();
        if (first.get("status").getAsString().equals("awaiting_confirmation")) {
            System.out.println("[penny-walk] Variance... is explicit: " + first.get("reasons"));
            first = http.change("confirm", args()).result();
        }
        assertEquals("dialog", first.get("status").getAsString(), first.toString());
        JsonObject dialog = first.getAsJsonObject("dialog");
        System.out.println("[penny-walk] Variance dialog: " + dialog);
        JsonObject read = http.read("dialog", null);
        assertTrue(read.get("pending").getAsBoolean());
        assertEquals(dialog.get("title").getAsString(), read.getAsJsonObject("dialog").get("title").getAsString());
        assertEquals(409, http.change("filter", args("name", "invert")).status(), "nothing else runs while the dialog waits");
        String dialogId = first.get("dialogId").getAsString();
        assertEquals(400, http.change("dialog_action", args("press", "OK")).status(), "dialogId is required");
        assertEquals(409, http.change("dialog_action", args("dialogId", "dlg-0", "press", "OK")).status(), "a stale dialogId is refused");
        BridgeHttp.Resp bad = http.change("dialog_action", args("dialogId", dialogId, "fields", args("nonexistent", 1), "press", "OK"));
        assertEquals(400, bad.status(), bad.body().toString());
        JsonObject done = http.change("dialog_action", args("dialogId", dialogId, "fields", args("radius", 3), "press", "OK")).result();
        assertEquals("completed", done.get("status").getAsString(), done.toString());
        assertNotEquals(before, PixelHash.sha256(engine.imagePlus(doc)), "Variance changed the pixels");
        HistoryRecord rec = null;
        for (HistoryRecord h : engine.history().subList(h0, engine.history().size())) if (id.equals(h.commandId())) rec = h;
        assertTrue(rec != null, "the engine recorded the command: " + engine.history().subList(h0, engine.history().size()));
        System.out.println("[penny-walk] Variance record: provenance " + rec.provenance() + ", origin " + rec.origin() + ", macro " + rec.macroLines());
        assertEquals(Origin.NL_SPOKEN, rec.origin());
        assertEquals(Provenance.LEGACY_DIALOG, rec.provenance());
        assertEquals("task-walk", rec.transcriptRef(), "legacy steps carry the task id too");
        assertTrue(DialogDriver.showing().isEmpty());
        assertTrue(!http.read("dialog", null).get("pending").getAsBoolean());
    }
}
