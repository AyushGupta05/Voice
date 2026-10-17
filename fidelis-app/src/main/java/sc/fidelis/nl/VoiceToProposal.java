package sc.fidelis.nl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.nl.NlService.Proposal;
import sc.fidelis.speech.SpeechService.Transcript;

/**
 * Transcript to proposal (docs D08 section 4): runs a speech {@link Transcript} through the NL
 * interpreter as {@link Origin#NL_SPOKEN} (the interpreter applies its own normaliser) and keeps the
 * link to the transcript so history can record where the action came from.
 *
 * <p><b>transcriptRef.</b> {@link Result#transcriptRef()} is the transcript id. The default
 * {@link Result#proposal()} is the interpreter's proposal unchanged, with exactly the schema keys in each
 * request's {@code params} (the engine rejects unknown option keys). {@link Result#proposalWithTranscriptRef()}
 * adds {@code "transcriptRef"} to every request's {@code params} for engines that treat it as a reserved,
 * non-option key and record it in history; do not submit that variant to an engine that validates
 * parameters strictly.
 *
 * <p>Nothing is executed. Low-confidence transcripts are flagged for the UI to highlight; the plan is
 * still validated normally.
 */
public final class VoiceToProposal {
    /** PROPOSED thresholds (D08 section 4), to be tuned on FX-061. */
    public static final double LOW_AVG_LOGPROB = -1.0;
    public static final double HIGH_NO_SPEECH_PROB = 0.6;

    public static final String TRANSCRIPT_REF_KEY = "transcriptRef";

    private final NlService nl;
    private final ConfirmationPolicy policy;

    public VoiceToProposal(NlService nl) {
        this.nl = Objects.requireNonNull(nl);
        this.policy = nl instanceof DefaultNlService d ? d.confirmationPolicy() : ConfirmationPolicy.strict();
    }

    public Result apply(Transcript transcript, DocumentId focused) {
        Objects.requireNonNull(transcript, "transcript");
        String text = transcript.text() == null ? "" : transcript.text().trim();
        boolean low = lowConfidence(transcript);
        if (!transcript.speechDetected() || text.isEmpty()) {
            Proposal none = new Proposal(Proposal.Status.NEEDS_CLARIFICATION, "", List.of(), List.of(), List.of(), List.of(),
                    List.of("No speech was detected, so nothing will run. Try again, or type the instruction."), null);
            return new Result(none, transcript.id(), text, true, policy.assess(none));
        }
        Proposal proposal = nl.interpret(text, Origin.NL_SPOKEN, focused);
        return new Result(proposal, transcript.id(), text, low, policy.assess(proposal));
    }

    static boolean lowConfidence(Transcript t) {
        if (t.segments() == null || t.segments().isEmpty()) return false;
        for (Transcript.Segment s : t.segments()) {
            if (s.avgLogprob() < LOW_AVG_LOGPROB || s.noSpeechProb() > HIGH_NO_SPEECH_PROB) return true;
        }
        return false;
    }

    /**
     * @param transcriptRef  the transcript id (history reference)
     * @param transcriptText the recognised text exactly as transcribed (shown for review and editing)
     * @param lowConfidence  true when a segment's avgLogprob or noSpeechProb passes the thresholds above
     * @param confirmation   what the user must confirm before the requests are submitted; for a spoken READY
     *                       proposal {@code required()} is always true (FR-SPEECH-004), and {@code explicit()}
     *                       marks AC4 and above, a global BlackBackground change, code, or an unfilled consequential slot
     */
    public record Result(Proposal proposal, String transcriptRef, String transcriptText, boolean lowConfidence,
                         ConfirmationPolicy.Decision confirmation) {

        /** Nothing may be submitted until the user has confirmed. */
        public boolean requiresConfirmation() { return confirmation.required(); }

        /** The stronger, deliberate confirmation (see {@link ConfirmationPolicy}). */
        public boolean requiresExplicitConfirmation() { return confirmation.explicit(); }

        /** The interpreter's normalised form of the transcript. */
        public String normalisedText() { return proposal.normalisedText(); }

        /** Same proposal with {@code "transcriptRef"} added to each request's params (see class comment). */
        public Proposal proposalWithTranscriptRef() {
            List<ActionRequest> out = new ArrayList<>();
            for (ActionRequest r : proposal.requests()) {
                Map<String, Object> p = new LinkedHashMap<>(r.params());
                p.put(TRANSCRIPT_REF_KEY, transcriptRef);
                out.add(new ActionRequest(r.requestId(), r.origin(), r.kind(), r.commandId(), r.operation(), p, r.target(),
                        r.confirmationToken(), r.replayOf()));
            }
            return new Proposal(proposal.status(), proposal.normalisedText(), List.copyOf(out), proposal.menuPaths(),
                    proposal.macroPreview(), proposal.slotProvenance(), proposal.questions(), proposal.refusalReason());
        }
    }
}
