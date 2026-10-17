package sc.fidelis.penny.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import sc.fidelis.penny.PennyUi;
import sc.fidelis.penny.voice.PennyVoiceClient;
import sc.fidelis.ui.Theme;

/**
 * The Penny strip and plan card render each state of the conversation and the plan, and Confirm/Cancel reach the
 * Fidelis bridge exactly as docs/FIDELIS-PROTOCOL.md specifies (against an in-test HTTP server, not the real bridge).
 */
class PennyPanelTest {
    private static JFrame frame;
    private static PennyPanel panel;
    private static HttpServer bridge;
    private static final List<JsonObject> bodies = new CopyOnWriteArrayList<>();
    private static final List<String> auth = new CopyOnWriteArrayList<>();
    private static final List<Boolean> origin = new CopyOnWriteArrayList<>();
    private static volatile int confirmStatus = 200;

    @BeforeAll
    static void start() throws Exception {
        bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/execute", ex -> {
            JsonObject body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            bodies.add(body);
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            origin.add(ex.getRequestHeaders().containsKey("Origin"));
            String op = body.get("operation").getAsString();
            int status = 200;
            String reply = switch (op) {
                case "context" -> "{\"result\":{\"contextKey\":\"ctx-42\",\"application\":\"Fidelis\"}}";
                case "confirm" -> {
                    status = confirmStatus;
                    yield status == 200 ? "{\"result\":{\"count\":64}}" : "{\"error\":\"Analyze Particles failed: no threshold\",\"code\":\"EXECUTION_FAILED\"}";
                }
                default -> "{\"result\":{}}";
            };
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        bridge.start();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            frame = new JFrame("penny panel test");
            frame.setSize(1100, 300);
            frame.setVisible(true);
        });
    }

    /** A fresh panel per test: each starts never connected, with no plan. */
    @BeforeEach
    void freshPanel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (panel != null) {
                panel.dispose();
                frame.getContentPane().remove(panel);
            }
            panel = new PennyPanel();
            panel.setBridge(new BridgeCalls(URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/execute"), () -> Optional.of("bridge-secret")));
            frame.getContentPane().add(panel, java.awt.BorderLayout.NORTH);
            frame.getContentPane().revalidate();
        });
    }

    @AfterAll
    static void stop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.dispose();
            frame.dispose();
        });
        bridge.stop(0);
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T read(java.util.concurrent.Callable<T> c) throws Exception {
        Object[] out = new Object[1];
        Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                out[0] = c.call();
            } catch (Exception e) {
                err[0] = e;
            }
        });
        if (err[0] != null) throw err[0];
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    private static void waitFor(String what, java.util.concurrent.Callable<Boolean> c, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (Boolean.TRUE.equals(read(c))) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void conversationStates() throws Exception {
        edt(() -> panel.setConnected(false, "Penny's gateway is not reachable"));
        // never connected: the strip stays out of the way
        assertFalse(read(panel::isVisible), "hidden while Penny's gateway has never been reachable");
        assertEquals(PennyPanel.State.OFFLINE, read(panel::displayState));

        edt(() -> panel.setConnected(true, null));
        assertTrue(read(panel::isVisible));
        assertEquals("Ready", read(panel::stateText));
        assertTrue(read(panel::lineText).contains("Press Mic"), read(panel::lineText));
        assertEquals(PennyIndicator.Mode.IDLE, read(() -> panel.indicator().mode()));

        edt(() -> panel.setVoice(PennyVoiceClient.Voice.STARTING));
        assertEquals("Starting…", read(panel::stateText));
        edt(() -> panel.setVoice(PennyVoiceClient.Voice.LISTENING));
        assertEquals("Listening", read(panel::stateText));
        assertEquals(PennyIndicator.Mode.LISTENING, read(() -> panel.indicator().mode()));
        waitFor("listening animation runs", () -> panel.indicator().animating(), 3);

        edt(() -> panel.heard("count the", true, true));
        assertEquals("“count the”", read(panel::lineText));
        assertEquals(ConversationLine.Style.PARTIAL, read(() -> panel.lineRuns().get(0).style()));
        edt(() -> panel.heard("someone else talking", true, false));
        assertFalse(read(panel::lineText).contains("someone else"), "speech the gateway did not address to Penny is not shown");

        edt(() -> {
            panel.heard("Count the cells.", false, true);
            panel.setWorking(true);
        });
        assertEquals("Thinking", read(panel::stateText));
        assertEquals("“Count the cells.”  ·  working on it…", read(panel::lineText));
        assertTrue(read(panel::stopShowing));
        edt(() -> panel.acknowledged("Counting the cells now."));
        assertEquals("“Count the cells.”  ·  Counting the cells now.", read(panel::lineText));

        edt(() -> {
            panel.answered("There are 64 cells.", false);
            panel.setWorking(false);
            panel.setSpeaking(true);
        });
        assertEquals("Speaking", read(panel::stateText));
        assertEquals("“Count the cells.”  →  There are 64 cells.", read(panel::lineText));
        assertEquals(ConversationLine.Style.ANSWER, read(() -> panel.lineRuns().get(2).style()));
        assertEquals(PennyIndicator.Mode.SPEAKING, read(() -> panel.indicator().mode()));

        edt(() -> {
            panel.setSpeaking(false);
            panel.failed("Voice is already active in another companion.");
        });
        assertEquals(ConversationLine.Style.ERROR, read(() -> panel.lineRuns().get(panel.lineRuns().size() - 1).style()));
        assertFalse(read(panel::stopShowing));

        edt(() -> panel.userSaid("measure the selection"));
        assertEquals("“measure the selection”", read(panel::lineText), "a new request clears the last answer and error");

        // the gateway went away: "reconnecting" for a while, then out of the way
        edt(() -> panel.setConnected(false, "Penny's gateway disconnected"));
        assertEquals("Reconnecting…", read(panel::stateText));
        assertTrue(read(panel::isVisible));
        assertEquals(PennyIndicator.Mode.OFFLINE, read(() -> panel.indicator().mode()));
        waitFor("indicator animation stops when nothing animates", () -> !panel.indicator().animating(), 3);
    }

    private static PennyUi.Plan plan(String id, boolean explicit) {
        return new PennyUi.Plan(id, "segment the cells", true, explicit,
                explicit ? List.of("step 2 (Convert to Mask) changes the global BlackBackground preference") : List.of(),
                List.of("8-bit", "Convert to Mask", "Analyze Particles"),
                List.of("run(\"8-bit\");", "run(\"Convert to Mask\");", "run(\"Analyze Particles...\", \"display clear add\");"),
                Map.of("document", "blobs.gif"));
    }

    @Test
    void automaticPlanShowsLiveStepProgressThenDone() throws Exception {
        edt(() -> {
            panel.setConnected(true, null);
            panel.userSaid("segment the cells");
            panel.showPlan(plan("p-auto", false), true);
        });
        assertTrue(read(panel::planShowing));
        assertFalse(read(panel::confirmShowing), "an automatic plan runs at once: no Confirm");
        assertEquals("Step 1 of 3", read(panel::planStatusText));
        assertEquals(List.of("current:8-bit", "pending:Convert to Mask", "pending:Analyze Particles"), read(panel::planStepTexts));
        assertEquals("Running", read(panel::stateText));

        edt(() -> panel.planProgress("p-auto", 1, "Convert to Mask"));
        assertEquals("Step 2 of 3", read(panel::planStatusText));
        assertEquals(List.of("done:8-bit", "current:Convert to Mask", "pending:Analyze Particles"), read(panel::planStepTexts));
        edt(() -> panel.planProgress("other-plan", 2, "x"));
        assertEquals("Step 2 of 3", read(panel::planStatusText), "progress of another plan is ignored");

        edt(() -> panel.planFinished("p-auto", true, null));
        assertEquals("Done · 3 steps · undo in History", read(panel::planStatusText));
        assertEquals(List.of("done:8-bit", "done:Convert to Mask", "done:Analyze Particles"), read(panel::planStepTexts));

        edt(() -> {
            panel.showPlan(plan("p-fail", false), true);
            panel.planProgress("p-fail", 2, "Analyze Particles");
            panel.planFinished("p-fail", false, "Analyze Particles failed: no threshold");
        });
        assertEquals("Stopped at step 3 of 3", read(panel::planStatusText));
        assertEquals(List.of("done:8-bit", "done:Convert to Mask", "failed:Analyze Particles"), read(panel::planStepTexts));
        edt(() -> panel.clearPlan("p-fail"));
        assertFalse(read(panel::planShowing));
    }

    @Test
    void explicitPlanWaitsAndConfirmGoesToTheBridgeBoundToTheContext() throws Exception {
        bodies.clear();
        auth.clear();
        origin.clear();
        confirmStatus = 200;
        edt(() -> {
            panel.setConnected(true, null);
            panel.showPlan(plan("p-explicit", true), false);
        });
        assertTrue(read(panel::confirmShowing));
        assertTrue(read(panel::confirmEnabled));
        assertEquals("Needs your confirmation", read(panel::planStatusText));
        assertEquals(List.of("pending:8-bit", "pending:Convert to Mask", "pending:Analyze Particles"), read(panel::planStepTexts));

        edt(panel::pressConfirm);
        waitFor("bridge answered", () -> panel.planStatusText().startsWith("Done"), 10);
        assertEquals(2, bodies.size(), "context, then confirm");
        assertEquals("context", bodies.get(0).get("operation").getAsString());
        JsonObject confirm = bodies.get(1);
        assertEquals("confirm", confirm.get("operation").getAsString());
        assertEquals("p-explicit", confirm.getAsJsonObject("arguments").get("planId").getAsString());
        assertEquals("ctx-42", confirm.get("expectedContext").getAsString(), "the approval is bound to the state the user saw");
        assertTrue(confirm.get("approved").getAsBoolean());
        assertFalse(confirm.get("spoken").getAsBoolean());
        assertEquals(List.of("Bearer bridge-secret", "Bearer bridge-secret"), auth);
        assertEquals(List.of(false, false), origin, "no Origin header (the bridge refuses browser requests)");
        assertFalse(read(panel::confirmShowing));
    }

    @Test
    void failedConfirmKeepsThePlanAndCancelDiscardsIt() throws Exception {
        bodies.clear();
        confirmStatus = 409;
        edt(() -> {
            panel.setConnected(true, null);
            panel.showPlan(plan("p-409", true), false);
            panel.pressConfirm();
        });
        waitFor("error shown", () -> panel.confirmEnabled(), 10);
        assertEquals("Needs your confirmation", read(panel::planStatusText), "nothing ran: the plan still waits");
        assertEquals("Could not confirm: Analyze Particles failed: no threshold", read(panel::planMessageText));

        bodies.clear();
        edt(panel::pressCancel);
        waitFor("cleared", () -> !panel.planShowing(), 10);
        assertEquals(1, bodies.size());
        assertEquals("cancel_plan", bodies.get(0).get("operation").getAsString());
        assertEquals("p-409", bodies.get(0).getAsJsonObject("arguments").get("planId").getAsString());
        assertNull(bodies.get(0).get("approved"), "cancelling needs no approval");
        confirmStatus = 200;
    }

    @Test
    void longLinesShortenWhatWasSaidFirst() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            List<String> fitted = read(() -> {
                ConversationLine l = new ConversationLine();
                l.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
                l.set(List.of(new ConversationLine.Run("“" + "a very long request ".repeat(10) + "”", ConversationLine.Style.SAID),
                        new ConversationLine.Run("  →  ", ConversationLine.Style.SEPARATOR),
                        new ConversationLine.Run("There are 64 cells.", ConversationLine.Style.ANSWER)));
                g.setFont(l.getFont());
                return l.fit(400, g);
            });
            assertTrue(fitted.get(0).endsWith("…"), fitted.get(0));
            assertEquals("There are 64 cells.", fitted.get(2), "the answer stays whole");
        } finally {
            g.dispose();
        }
    }
}
