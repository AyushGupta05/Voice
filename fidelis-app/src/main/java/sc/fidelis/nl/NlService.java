package sc.fidelis.nl;

import java.util.List;
import java.util.Map;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.Origin;

/**
 * Deterministic natural-language command resolution (docs A17, D08; ADR-007). Shared interface:
 * implemented by the Voice & NL workstream, consumed by the Desktop UI workstream.
 *
 * <p>Never executes anything. Produces a {@link Proposal} that the UI shows for review; execution
 * happens only when the user confirms and the UI submits the proposal's requests to the engine.
 */
public interface NlService {

    /**
     * @param text     typed or transcribed instruction
     * @param origin   NL_TYPED or NL_SPOKEN
     * @param focused  the focused document (deixis target), or null
     */
    Proposal interpret(String text, Origin origin, DocumentId focused);

    /**
     * A reviewed plan. {@code requests} are complete, validated {@link ActionRequest}s in order when
     * {@code status} is READY; otherwise {@code questions} explain what is missing or ambiguous.
     *
     * @param menuPaths     the Fiji menu path of each step (for the "teaching" preview)
     * @param macroPreview  the exact macro line(s) each step will produce
     * @param slotProvenance per step, key → USER | DERIVED | DEFAULT (non-consequential only)
     */
    record Proposal(Status status, String normalisedText, List<ActionRequest> requests, List<String> menuPaths,
                    List<String> macroPreview, List<Map<String, String>> slotProvenance,
                    List<String> questions, String refusalReason) {
        public enum Status { READY, NEEDS_CLARIFICATION, REFUSED }
    }
}
