package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.ImagePlus;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.ConsequenceClass;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.nl.DefaultNlService;
import sc.fidelis.nl.NlService.Proposal;
import sc.fidelis.nl.VoiceToProposal;
import sc.fidelis.speech.SpeechService;
import sc.fidelis.speech.SpeechService.Transcript;
import sc.fidelis.speech.WhisperSpeechService;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.PixelHash;
import sc.fidelis.testkit.ProductEngine;

/**
 * Speech to execution end to end (docs D08, D18): fixture WAV, the real Whisper worker, {@link DefaultNlService}
 * and {@code Engine.submit} on the product engine, verified by pixel hash. Skipped, with the reason, when the
 * speech environment is unavailable. A proposal above AC3 (reversible data) is never executed here: that would
 * need a user's confirmation, which a test must not supply.
 */
class SpeechToExecutionTest {
    private static Engine engine;

    @BeforeAll
    static void acquire() {
        engine = ProductEngine.get();
    }

    @AfterEach
    void clean() {
        IjState.reset();
    }

    private static Path resource(String name) throws Exception {
        Path wav = Files.createTempFile("fidelis-e2e", ".wav");
        try (InputStream in = SpeechToExecutionTest.class.getResourceAsStream("/sc/fidelis/speech/" + name)) {
            Files.copy(in, wav, StandardCopyOption.REPLACE_EXISTING);
        }
        return wav;
    }

    /** Hash of the 8-bit ramp after inversion, computed here from first principles (255 - v), not by Fiji. */
    private static String expectedInvertedRampHash(int w, int h) {
        byte[] src = (byte[]) IJ.createImage("expected", "8-bit ramp", w, h, 1).getProcessor().getPixels();
        byte[] inv = new byte[src.length];
        for (int i = 0; i < src.length; i++) inv[i] = (byte) (255 - (src[i] & 0xff));
        return PixelHash.sha256(inv);
    }

    private DocumentId newRamp() throws Exception {
        ActionResult n = engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.NEW_IMAGE,
                Map.of("title", "spoken-ramp", "type", "8-bit", "width", 64, "height", 32, "slices", 1, "fill", "Ramp"), null))
                .get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, n.state(), String.valueOf(n.error()));
        DocumentId id = n.digest().added().get(0);
        engine.focus(id);
        return id;
    }

    @Test
    void spokenInvertReachesThePixelsOfTheRealEngine() throws Exception {
        Path wav = resource("invert-command.wav");
        try (WhisperSpeechService speech = new WhisperSpeechService()) {
            assumeTrue(speech.awaitReady(Duration.ofSeconds(120)) != SpeechService.State.UNAVAILABLE,
                    "speech unavailable: " + speech.unavailableReason());
            DocumentId doc = newRamp();
            ImagePlus imp = engine.imagePlus(doc);
            // sanity of the oracle: the ramp is not already its own inverse
            String before = PixelHash.sha256(imp);
            String expected = expectedInvertedRampHash(64, 32);
            assertNotEquals(before, expected);

            Transcript t = speech.transcribeFile(wav).get(120, TimeUnit.SECONDS);
            VoiceToProposal.Result r = new VoiceToProposal(new DefaultNlService(engine)).apply(t, doc);
            System.out.println("[speech-e2e] transcript \"" + t.text() + "\" -> " + r.proposal().status() + " "
                    + r.proposal().macroPreview());
            assertEquals(Proposal.Status.READY, r.proposal().status(), String.valueOf(r.proposal().questions()));
            List<ActionRequest> requests = r.proposal().requests();
            assertEquals(1, requests.size());
            ActionRequest request = requests.get(0);
            assertEquals("ij1:Edit>Invert", request.commandId());
            assertEquals(Origin.NL_SPOKEN, request.origin());
            CommandSchema schema = engine.schema(request.commandId()).orElseThrow();
            assertTrue(schema.consequence().ordinal() <= ConsequenceClass.AC3_REVERSIBLE_DATA.ordinal(),
                    "an AC4+ proposal must never be executed unconfirmed: " + schema.consequence());

            ActionResult result = engine.submit(request).get(60, TimeUnit.SECONDS);
            assertEquals(OperationState.COMPLETED, result.state(), String.valueOf(result.error()));
            assertEquals(expected, PixelHash.sha256(engine.imagePlus(doc)), "pixels of the spoken Invert");
            HistoryRecord last = engine.history().get(engine.history().size() - 1);
            System.out.println("[speech-e2e] pixel SHA-256 before " + before + " after " + expected
                    + "; history origin " + last.origin() + ", label " + last.label());
            assertEquals(Origin.NL_SPOKEN, last.origin());
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    @Test
    void silenceProducesNoRequestAndLeavesThePixelsAlone() throws Exception {
        Path wav = resource("silence-5s.wav");
        try (WhisperSpeechService speech = new WhisperSpeechService()) {
            assumeTrue(speech.awaitReady(Duration.ofSeconds(120)) != SpeechService.State.UNAVAILABLE,
                    "speech unavailable: " + speech.unavailableReason());
            DocumentId doc = newRamp();
            String before = PixelHash.sha256(engine.imagePlus(doc));
            Transcript t = speech.transcribeFile(wav).get(120, TimeUnit.SECONDS);
            VoiceToProposal.Result r = new VoiceToProposal(new DefaultNlService(engine)).apply(t, doc);
            System.out.println("[speech-e2e] silence -> " + r.proposal().status());
            assertTrue(r.proposal().requests().isEmpty(), "silence must not yield an executable request");
            assertEquals(before, PixelHash.sha256(engine.imagePlus(doc)));
        } finally {
            Files.deleteIfExists(wav);
        }
    }
}
