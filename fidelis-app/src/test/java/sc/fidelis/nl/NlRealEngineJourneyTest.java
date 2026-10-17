package sc.fidelis.nl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.ImagePlus;
import ij.Prefs;
import ij.process.ByteProcessor;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.ActionKind;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.nl.NlService.Proposal;
import sc.fidelis.speech.SpeechService;
import sc.fidelis.speech.SpeechService.Transcript;
import sc.fidelis.speech.WhisperSpeechService;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.PixelHash;
import sc.fidelis.testkit.ProductEngine;

/**
 * The natural-language layer on the product engine (real 953-command catalogue): the ten J-12 demo phrases
 * resolve to the exact D16 requests, executing them in order on Blobs reproduces the pinned-Fiji numbers, and
 * the spoken path never executes before a (simulated) confirmation.
 */
class NlRealEngineJourneyTest {
    private static final String[] J12_PHRASES = {
        "Subtract background with a rolling ball radius of fifty.",
        "Gaussian blur with sigma two pixels.",
        "Threshold using Otsu with dark background.",
        "Convert to mask with black background.",
        "Watershed.",
        "Set measurements area mean min and integrated density.",
        "Measure.",
        "Analyze particles larger than twenty pixels showing masks and adding to the manager.",
        "Invert the image.",
        "Undo.",
    };

    private Engine engine;
    private DefaultNlService nl;
    private DocumentId doc;

    @BeforeEach
    void setUp() throws Exception {
        engine = ProductEngine.get();
        IjState.reset();
        nl = new DefaultNlService(engine);
    }

    @AfterEach
    void tearDown() {
        IjState.reset();
    }

    private DocumentId openBlobs() throws Exception {
        Path blobs = Fixtures.require("FX-003", "blobs.gif");
        ActionResult r = engine.submit(ActionRequest.session(Origin.PANEL, Operations.OPEN, Map.of("path", blobs.toString()), null))
                .get(120, TimeUnit.SECONDS);
        assertTrue(r.succeeded(), String.valueOf(r.error()));
        return r.digest().added().get(0);
    }

    private static Map<String, Object> flags(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static void assertParams(Map<String, Object> want, ActionRequest r, String where) {
        for (Map.Entry<String, Object> e : want.entrySet()) {
            assertTrue(r.params().containsKey(e.getKey()), where + ": missing " + e.getKey() + " in " + r.params());
            Object actual = r.params().get(e.getKey());
            if (e.getValue() instanceof Number n && actual instanceof Number a) assertEquals(n.doubleValue(), a.doubleValue(), 1e-9, where + " " + e.getKey());
            else assertEquals(e.getValue(), actual, where + " " + e.getKey());
        }
    }

    /** The exact D16 J-12 request for each phrase: command id (or DIRECT operation) and the parameters that carry the intent. */
    private Map<String, Object>[] expectedParams() {
        @SuppressWarnings("unchecked")
        Map<String, Object>[] w = new Map[] {
            flags("rolling", 50, "light", false, "separate", false, "create", false, "sliding", false, "disable", false),
            flags("sigma", 2, "scaled", false, "stack", false),
            flags("method", "Otsu", "dark", true),
            flags("black", true),
            flags(),
            flags("area", true, "mean", true, "min", true, "integrated", true, "standard", false, "modal", false, "centroid", false,
                    "center", false, "perimeter", false, "bounding", false, "fit", false, "shape", false, "median", false,
                    "skewness", false, "kurtosis", false, "area_fraction", false, "stack", false, "limit", false, "display", false,
                    "invert", false, "scientific", false, "add", false, "nan", false, "redirect", "None", "decimal", 9),
            flags(),
            flags("size", "20-Infinity", "circularity", "0.00-1.00", "show", "Masks", "display", true, "clear", true, "include", true,
                    "summarize", true, "add", true, "exclude", false, "overlay", false, "composite", false, "record", false, "in_situ", false),
            flags(),
            flags(),
        };
        return w;
    }

    private static final String[] IDS = {"ij1:Process>Subtract Background...", "ij1:Process>Filters>Gaussian Blur...", null,
        "ij1:Process>Binary>Convert to Mask", "ij1:Process>Binary>Watershed", "ij1:Analyze>Set Measurements...", "ij1:Analyze>Measure",
        "ij1:Analyze>Analyze Particles...", "ij1:Edit>Invert", null};

    @Test
    void j12PhrasesResolveToTheExactRequestsOnTheRealCatalogue() throws Exception {
        doc = openBlobs();
        Map<String, Object>[] want = expectedParams();
        for (int i = 0; i < J12_PHRASES.length; i++) {
            Proposal p = nl.interpret(J12_PHRASES[i], Origin.NL_SPOKEN, doc);
            String where = "\"" + J12_PHRASES[i] + "\" -> " + p.status() + " " + p.questions() + " " + p.refusalReason();
            assertEquals(Proposal.Status.READY, p.status(), where);
            assertEquals(1, p.requests().size(), where);
            ActionRequest r = p.requests().get(0);
            assertEquals(Origin.NL_SPOKEN, r.origin(), where);
            if (i == 2) {
                assertEquals(ActionKind.DIRECT, r.kind(), where);
                assertEquals(Operations.SET_AUTO_THRESHOLD, r.operation(), where);
                assertEquals(Map.of("method", "Otsu", "dark", true), r.params(), where);
            } else if (i == 9) {
                assertEquals(ActionKind.DIRECT, r.kind(), where);
                assertEquals(Operations.UNDO, r.operation(), where);
            } else {
                assertEquals(ActionKind.COMMAND, r.kind(), where);
                assertEquals(IDS[i], r.commandId(), where);
                assertParams(want[i], r, where);
                // full parameters: exactly the keys of the catalogued schema (plus the engine-level 'black' for masks)
                CommandSchema schema = engine.schema(r.commandId()).orElseThrow();
                assertTrue(schema.schemaComplete(), where);
                List<String> schemaKeys = new ArrayList<>(schema.parameters().stream().map(CommandSchema.ParameterSchema::key).toList());
                if (i == 3) schemaKeys.add("black");
                assertEquals(new java.util.TreeSet<>(schemaKeys), new java.util.TreeSet<>(r.params().keySet()), where);
            }
            assertEquals(doc, r.target().document(), where);
        }
        // the executable macro lines of the reference
        assertEquals("run(\"Subtract Background...\", \"rolling=50\");", lastLine(nl.interpret(J12_PHRASES[0], Origin.NL_SPOKEN, doc)));
        assertEquals("run(\"Analyze Particles...\", \"size=20-Infinity show=Masks display clear include summarize add\");",
                lastLine(nl.interpret(J12_PHRASES[7], Origin.NL_SPOKEN, doc)));
        assertEquals("run(\"Set Measurements...\", \"area mean min integrated redirect=None decimal=9\");",
                lastLine(nl.interpret(J12_PHRASES[5], Origin.NL_SPOKEN, doc)));
    }

    private static String lastLine(Proposal p) {
        String[] lines = p.macroPreview().get(0).split("\n");
        return lines[lines.length - 1].replaceAll("  //.*$", "").replace("[", "").replace("]", "");
    }

    @Test
    void j12ProposalsExecutedInOrderReproduceThePinnedFijiNumbers() throws Exception {
        doc = openBlobs();
        Prefs.blackBackground = true; // as the reference macro does; the engine has no operation for this global
        for (int i = 0; i < 8; i++) {
            // interpreted against the document just before each step, as the UI does after a confirmation
            Proposal p = nl.interpret(J12_PHRASES[i], Origin.NL_SPOKEN, doc);
            assertEquals(Proposal.Status.READY, p.status(), J12_PHRASES[i] + " " + p.questions() + " " + p.refusalReason());
            assertTrue(nl.confirmation(p).required(), "spoken proposals need confirmation before submission");
            for (ActionRequest r : p.requests()) {
                ActionResult res = engine.submit(r).get(120, TimeUnit.SECONDS); // after the simulated confirm
                assertTrue(res.succeeded(), "step " + (i + 1) + " \"" + J12_PHRASES[i] + "\" ended in " + res.state() + ": " + res.error());
            }
            if (i == 6) { // Measure
                List<MeasurementRow> rows = engine.results();
                assertEquals(1, rows.size(), "one Measure row");
                assertEquals(65024.0, rows.get(0).values().get("Area"), 0.0, "Area");
                assertEquals(155.9435746801181, rows.get(0).values().get("Mean"), 0.0, "Mean (pinned-Fiji baseline, bit-exact)");
            }
        }
        assertEquals(71, engine.results().size(), "Analyze Particles rows");
        assertEquals(71, engine.rois(doc).size(), "Analyze Particles ROIs");
        System.out.println("[nl-j12] Measure Area=65024 Mean=155.9435746801181; Analyze Particles rows=" + engine.results().size()
                + " rois=" + engine.rois(doc).size());
    }

    @Test
    void realCatalogueConfirmationAndVocabulary() throws Exception {
        doc = openBlobs();
        Proposal mask = nl.interpret(J12_PHRASES[3], Origin.NL_TYPED, doc);
        assertTrue(nl.confirmation(mask).explicit(), nl.confirmation(mask).reasons().toString());
        Proposal measure = nl.interpret(J12_PHRASES[6], Origin.NL_TYPED, doc);
        assertEquals(Proposal.Status.READY, measure.status());
        System.out.println("[nl-confirm] typed Measure on the real catalogue: " + nl.confirmation(measure));
        String v = nl.vocabularyPrompt(engine.documents().stream().map(d -> d.title()).toList());
        System.out.println("[vocab-real] (" + v.length() + " chars) " + v);
        assertTrue(v.length() <= DefaultNlService.VOCABULARY_MAX_CHARS);
        assertTrue(v.contains("blobs.gif") && v.contains("Subtract Background") && v.contains("Watershed"), v);
    }

    /** invert-command.wav, transcript, proposal that needs confirmation, nothing changes, then submit and compare the pixel hash. */
    @Test
    void spokenInvertNeedsConfirmationAndOnlyThenChangesThePixels() throws Exception {
        Path wav = Files.createTempFile("fidelis-fixture", ".wav");
        try (InputStream in = NlRealEngineJourneyTest.class.getResourceAsStream("/sc/fidelis/speech/invert-command.wav")) {
            Files.copy(in, wav, StandardCopyOption.REPLACE_EXISTING);
        }
        try (WhisperSpeechService speech = new WhisperSpeechService()) {
            assumeTrue(speech.awaitReady(Duration.ofSeconds(120)) != SpeechService.State.UNAVAILABLE, "speech unavailable: " + speech.unavailableReason());
            ActionResult created = engine.submit(ActionRequest.session(Origin.PANEL, Operations.NEW_IMAGE,
                    Map.of("title", "ramp", "type", "8-bit", "width", 256, "height", 64, "fill", "Ramp"), null)).get(60, TimeUnit.SECONDS);
            assertTrue(created.succeeded(), String.valueOf(created.error()));
            DocumentId ramp = created.digest().added().get(0);
            String before = PixelHash.sha256(engine.imagePlus(ramp));
            // independent expectation: 255 - v for every pixel of the ramp
            byte[] src = (byte[]) engine.imagePlus(ramp).getProcessor().getPixelsCopy();
            byte[] inv = new byte[src.length];
            for (int i = 0; i < src.length; i++) inv[i] = (byte) (255 - (src[i] & 0xFF));
            String expected = PixelHash.sha256(new ImagePlus("expected", new ByteProcessor(256, 64, inv)));
            assertNotEquals(before, expected);

            speech.setVocabularyPrompt(nl.vocabularyPrompt(engine.documents().stream().map(d -> d.title()).toList()));
            Transcript t = speech.transcribeFile(wav).get(120, TimeUnit.SECONDS);
            VoiceToProposal.Result r = new VoiceToProposal(nl).apply(t, ramp);
            System.out.println("[nl-voice] transcript=\"" + t.text() + "\" -> " + r.proposal().status() + " " + r.proposal().macroPreview()
                    + " confirmation=" + r.confirmation());
            assertEquals(Proposal.Status.READY, r.proposal().status());
            assertEquals("ij1:Edit>Invert", r.proposal().requests().get(0).commandId());
            assertEquals(Origin.NL_SPOKEN, r.proposal().requests().get(0).origin());
            assertTrue(r.requiresConfirmation(), "FR-SPEECH-004");
            assertEquals(before, PixelHash.sha256(engine.imagePlus(ramp)), "nothing may execute before the confirmation");
            assertFalse(engine.history().stream().anyMatch(h -> "Invert".equals(h.label())), "no history before the confirmation");

            // simulated confirm: the UI submits the requests
            for (ActionRequest req : r.proposal().requests()) {
                ActionResult res = engine.submit(req).get(60, TimeUnit.SECONDS);
                assertTrue(res.succeeded(), res.state() + " " + res.error());
            }
            assertEquals(expected, PixelHash.sha256(engine.imagePlus(ramp)), "inverted ramp pixel hash");
            System.out.println("[nl-voice] ramp sha256 before=" + before.substring(0, 12) + " after=" + expected.substring(0, 12) + " (equals independent 255-v)");
        } finally {
            Files.deleteIfExists(wav);
        }
    }
}
