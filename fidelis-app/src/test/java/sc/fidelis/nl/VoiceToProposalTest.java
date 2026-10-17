package sc.fidelis.nl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.nl.NlService.Proposal;
import sc.fidelis.speech.SpeechService;
import sc.fidelis.speech.SpeechService.Transcript;
import sc.fidelis.speech.WhisperSpeechService;

class VoiceToProposalTest {
    private final VoiceToProposal v2p = new VoiceToProposal(new DefaultNlService(new FakeEngine()));
    private final DocumentId doc = new DocumentId("uncal");

    private static Transcript transcript(String id, String text, boolean speech, double logprob, double noSpeech) {
        return new Transcript(id, text, speech, 2.0, List.of(new Transcript.Segment(0, 2, text, logprob, noSpeech)),
                "Systran/faster-whisper-tiny.en", null, 100);
    }

    @Test
    void transcriptBecomesASpokenProposalWithItsReference() {
        VoiceToProposal.Result r = v2p.apply(transcript("s-41", "Gaussian blur with sigma two pixels.", true, -0.2, 0.01), doc);
        assertEquals("s-41", r.transcriptRef());
        assertEquals("Gaussian blur with sigma two pixels.", r.transcriptText());
        assertEquals("gaussian blur with sigma 2 px", r.normalisedText());
        assertFalse(r.lowConfidence());
        Proposal p = r.proposal();
        assertEquals(Proposal.Status.READY, p.status());
        ActionRequest req = p.requests().get(0);
        assertEquals(Origin.NL_SPOKEN, req.origin());
        assertFalse(req.params().containsKey("transcriptRef"), "default proposal carries exactly the schema keys");
        Proposal tagged = r.proposalWithTranscriptRef();
        assertEquals("s-41", tagged.requests().get(0).params().get("transcriptRef"));
        assertEquals(req.params().size() + 1, tagged.requests().get(0).params().size());
        assertEquals(req.commandId(), tagged.requests().get(0).commandId());
        assertEquals(p.macroPreview(), tagged.macroPreview());
    }

    @Test
    void noSpeechNeverProducesRequests() {
        VoiceToProposal.Result r = v2p.apply(transcript("s-1", "", false, 0, 0.99), doc);
        assertEquals(Proposal.Status.NEEDS_CLARIFICATION, r.proposal().status());
        assertTrue(r.proposal().requests().isEmpty());
        assertTrue(r.proposal().questions().get(0).contains("No speech"));
        assertTrue(r.lowConfidence());
    }

    @Test
    void lowConfidenceIsFlaggedButStillValidated() {
        VoiceToProposal.Result r = v2p.apply(transcript("s-2", "invert the image", true, -1.6, 0.02), doc);
        assertTrue(r.lowConfidence());
        assertEquals(Proposal.Status.READY, r.proposal().status());
        r = v2p.apply(transcript("s-3", "invert the image", true, -0.3, 0.8), doc);
        assertTrue(r.lowConfidence());
    }

    @Test
    void misrecognisedTextIsRefusedNotGuessed() {
        VoiceToProposal.Result r = v2p.apply(transcript("s-4", "inside the other eye", true, -0.9, 0.1), doc);
        assertEquals(Proposal.Status.REFUSED, r.proposal().status());
    }

    /** Live path: the real worker transcribes the fixture and the proposal comes out READY (skips if speech is unavailable). */
    @Test
    void fixtureAudioToReadyInvertProposal() throws Exception {
        Path wav = Files.createTempFile("fidelis-fixture", ".wav");
        try (InputStream in = VoiceToProposalTest.class.getResourceAsStream("/sc/fidelis/speech/invert-command.wav")) {
            Files.copy(in, wav, StandardCopyOption.REPLACE_EXISTING);
        }
        try (WhisperSpeechService speech = new WhisperSpeechService()) {
            assumeTrue(speech.awaitReady(Duration.ofSeconds(120)) != SpeechService.State.UNAVAILABLE,
                    "speech unavailable: " + speech.unavailableReason());
            Transcript t = speech.transcribeFile(wav).get(120, TimeUnit.SECONDS);
            VoiceToProposal.Result r = v2p.apply(t, doc);
            System.out.println("[voice-to-proposal] \"" + t.text() + "\" -> " + r.proposal().status() + " " + r.proposal().macroPreview());
            assertEquals(Proposal.Status.READY, r.proposal().status());
            assertEquals("ij1:Edit>Invert", r.proposal().requests().get(0).commandId());
            assertEquals(Origin.NL_SPOKEN, r.proposal().requests().get(0).origin());
            assertEquals(t.id(), r.transcriptRef());
        } finally {
            Files.deleteIfExists(wav);
        }
    }
}
