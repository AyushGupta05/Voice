package sc.fidelis.nl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.ConsequenceClass;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.nl.ConfirmationPolicy.Decision;
import sc.fidelis.nl.NlService.Proposal;
import sc.fidelis.speech.SpeechService.Transcript;

/** The confirmation rules (D08 policy table, FR-NL-006, FR-SPEECH-004) and the speech vocabulary prompt. */
class ConfirmationPolicyTest {
    private final FakeEngine engine = new FakeEngine();
    private final DefaultNlService nl = new DefaultNlService(engine);
    private final DocumentId doc = new DocumentId("uncal");

    private static CommandSchema withConsequence(CommandSchema s, ConsequenceClass cc) {
        return new CommandSchema(s.commandId(), s.label(), s.menuPath(), s.parameters(), s.schemaComplete(), s.headlessOk(), cc,
                s.preconditions(), s.postconditionFamily(), s.createsNewImage(), s.hiddenGlobals());
    }

    private ConfirmationPolicy policyWith(ConsequenceClass cc) {
        return new ConfirmationPolicy(id -> engine.schema(id).map(s -> withConsequence(s, cc)));
    }

    private Proposal ready(String text, Origin origin) {
        Proposal p = nl.interpret(text, origin, doc);
        assertEquals(Proposal.Status.READY, p.status(), text);
        return p;
    }

    @Test
    void everySpokenProposalNeedsConfirmationButOnlyRiskyOnesNeedExplicitConfirmation() {
        Decision d = nl.confirmation(ready("invert the image", Origin.NL_SPOKEN));
        assertTrue(d.required(), "nothing executes from speech unconfirmed");
        assertFalse(d.explicit(), d.reasons().toString());
        Decision measure = policyWith(ConsequenceClass.AC0_READ_ONLY).assess(ready("measure", Origin.NL_SPOKEN));
        assertTrue(measure.required(), "even a read-only command needs confirmation when spoken");
        assertFalse(measure.explicit());
    }

    @Test
    void typedReadOnlyStepsMayAutoExecuteButEditsNeedConfirmation() {
        Proposal measure = ready("measure", Origin.NL_TYPED);
        assertFalse(policyWith(ConsequenceClass.AC0_READ_ONLY).assess(measure).required());
        assertFalse(policyWith(ConsequenceClass.AC1_VIEW).assess(measure).required());
        assertTrue(policyWith(ConsequenceClass.AC3_REVERSIBLE_DATA).assess(measure).required());
        assertTrue(nl.confirmation(ready("invert the image", Origin.NL_TYPED)).required());
    }

    @Test
    void ac4AndAboveAreExplicit() {
        Proposal p = ready("invert the image", Origin.NL_TYPED);
        for (ConsequenceClass cc : List.of(ConsequenceClass.AC4_NON_UNDOABLE_DATA, ConsequenceClass.AC5_GLOBAL_SETTINGS,
                ConsequenceClass.AC6_PERSISTENT_EXTERNAL, ConsequenceClass.AC7_CODE)) {
            Decision d = policyWith(cc).assess(p);
            assertTrue(d.required() && d.explicit(), cc.toString());
            assertTrue(d.reasons().get(0).contains(cc.toString()), d.reasons().toString());
        }
        assertFalse(policyWith(ConsequenceClass.AC3_REVERSIBLE_DATA).assess(p).explicit());
    }

    @Test
    void aBlackBackgroundChangeIsExplicit() {
        Proposal p = ready("convert to mask with black background", Origin.NL_TYPED);
        assertTrue(p.requests().get(0).params().containsKey("black"));
        Decision d = nl.confirmation(p);
        assertTrue(d.explicit(), "Convert to Mask sets the global BlackBackground preference");
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("BlackBackground")), d.reasons().toString());
    }

    @Test
    void codeRequestsAreAlwaysExplicit() {
        ActionRequest code = ActionRequest.code(Origin.NL_TYPED, "ijm", "run(\"Invert\");", TargetScope.of(doc), null);
        Proposal p = new Proposal(Proposal.Status.READY, "x", List.of(code), List.of("-"), List.of("run(\"Invert\");"),
                List.of(Map.of()), List.of(), null);
        Decision d = nl.confirmation(p);
        assertTrue(d.required() && d.explicit());
        assertTrue(d.reasons().get(0).contains("code"));
    }

    @Test
    void anUnfilledConsequentialSlotIsExplicit() {
        Proposal good = ready("gaussian blur sigma 2 pixels", Origin.NL_TYPED);
        String consequential = engine.schema(good.requests().get(0).commandId()).orElseThrow().parameters().stream()
                .filter(s -> s.consequential()).map(s -> s.key()).findFirst().orElseThrow();
        Map<String, String> prov = new LinkedHashMap<>(good.slotProvenance().get(0));
        prov.put(consequential, "DEFAULT");
        Proposal bad = new Proposal(Proposal.Status.READY, good.normalisedText(), good.requests(), good.menuPaths(), good.macroPreview(),
                List.of(prov), List.of(), null);
        Decision d = nl.confirmation(bad);
        assertTrue(d.explicit(), d.reasons().toString());
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("'" + consequential + "'")), d.reasons().toString());
        assertFalse(nl.confirmation(good).explicit(), "the same proposal with user-set slots is not explicit: " + nl.confirmation(good));
    }

    @Test
    void proposalsThatCannotRunAreMarkedExplicitSoNothingIsSubmittedFromThem() {
        Proposal clarify = nl.interpret("invert", Origin.NL_SPOKEN, new DocumentId("stack"));
        assertEquals(Proposal.Status.NEEDS_CLARIFICATION, clarify.status());
        Decision d = nl.confirmation(clarify);
        assertTrue(d.required() && d.explicit());
        Proposal refused = nl.interpret("exec rm -rf now", Origin.NL_SPOKEN, doc);
        assertEquals(Proposal.Status.REFUSED, refused.status());
        assertTrue(nl.confirmation(refused).explicit());
    }

    @Test
    void persistentOutputIsExplicit() {
        ActionRequest save = ActionRequest.session(Origin.NL_TYPED, ActionRequest.Operations.SAVE_AS, Map.of("path", "x.tif"), TargetScope.of(doc));
        Proposal p = new Proposal(Proposal.Status.READY, "save", List.of(save), List.of("File>Save As"), List.of("saveAs"), List.of(Map.of()),
                List.of(), null);
        assertTrue(nl.confirmation(p).explicit());
    }

    private static Transcript transcript(String id, String text) {
        return new Transcript(id, text, true, 1.0, List.of(new Transcript.Segment(0, 1, text, -0.2, 0.01)), "m", null, 10);
    }

    @Test
    void voiceResultCarriesTheDecision() {
        VoiceToProposal v2p = new VoiceToProposal(nl);
        VoiceToProposal.Result r = v2p.apply(transcript("s-1", "Invert the image."), doc);
        assertEquals(Proposal.Status.READY, r.proposal().status());
        assertTrue(r.requiresConfirmation());
        assertFalse(r.requiresExplicitConfirmation());
        VoiceToProposal.Result rb = v2p.apply(transcript("s-2", "Convert to mask with black background."), doc);
        assertTrue(rb.requiresConfirmation() && rb.requiresExplicitConfirmation(), rb.confirmation().toString());
        VoiceToProposal.Result none = v2p.apply(new Transcript("s-3", "", false, 1.0, List.of(), "m", null, 10), doc);
        assertTrue(none.requiresConfirmation() && none.requiresExplicitConfirmation());
        // a service that is not a DefaultNlService gets the strict policy: every command step is explicit
        VoiceToProposal strict = new VoiceToProposal((text, origin, f) -> nl.interpret(text, origin, f));
        assertTrue(strict.apply(transcript("s-1", "Invert the image."), doc).requiresExplicitConfirmation());
    }

    @Test
    void vocabularyPromptIsBoundedAndContainsTheDemoVocabulary() {
        String p = nl.vocabularyPrompt(List.of("blobs.gif", "Mask of blobs.gif", "cells \"quoted\"\nnewline.tif"));
        System.out.println("[vocab] (" + p.length() + " chars) " + p);
        assertTrue(p.length() <= DefaultNlService.VOCABULARY_MAX_CHARS && p.length() <= 800, "length " + p.length());
        assertTrue(p.length() / 3.0 < 224, "token estimate");
        for (String must : new String[] {"blobs.gif", "Subtract Background", "Gaussian Blur", "Convert to Mask", "Watershed",
                "Set Measurements", "Analyze Particles", "Measure", "Otsu", "DAPI"}) {
            assertTrue(p.contains(must), must + " missing from: " + p);
        }
        assertFalse(p.contains("\n") || p.contains("\""), "titles are sanitised");
        assertTrue(p.contains("cells quoted newline.tif"), p);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) many.add("experiment-" + i + "-with-a-rather-long-descriptive-file-name-that-goes-on.tif");
        String q = nl.vocabularyPrompt(many);
        assertTrue(q.length() <= DefaultNlService.VOCABULARY_MAX_CHARS, "length " + q.length());
        assertTrue(q.contains("Gaussian Blur") && q.contains("Watershed"), q);
        assertEquals(q, nl.vocabularyPrompt(many), "deterministic");
        assertTrue(nl.vocabularyPrompt(null).contains("Subtract Background"));
        assertTrue(nl.vocabularyPrompt(List.of()).length() <= DefaultNlService.VOCABULARY_MAX_CHARS);
    }
}
