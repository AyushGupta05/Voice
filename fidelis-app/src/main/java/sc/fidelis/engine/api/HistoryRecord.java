package sc.fidelis.engine.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.Provenance;

/**
 * One entry of the structured operation history (docs D07 §3, D06 §8). The history is the source
 * of reproducibility; macro text is <em>derived</em> from these records (ADR-003).
 *
 * @param actionId     monotonic id, e.g. {@code act-7}
 * @param commandId    catalogue id for COMMAND actions; operation name for DIRECT/SESSION; null for CODE
 * @param label        human-readable label (IJ1 label, operation name, or "macro")
 * @param params       the complete typed parameter map actually applied
 * @param target       explicit target (may be null for observed actions whose target is unknown)
 * @param macroLines   exact ImageJ macro lines reproducing this step, or empty when not macro-expressible
 * @param macroExact   true if replaying {@code macroLines} in classic Fiji reproduces the result
 *                     (false for NATIVE actions recorded approximately and for LEGACY_DIALOG without captured values)
 * @param globalsBefore result-affecting globals captured before the step (docs A11 §6)
 * @param globalsDelta  globals changed by the step (old=new text), empty when none
 * @param outcome      terminal state
 * @param errorSummary short error text when the outcome is not COMPLETED
 * @param transcriptRef speech transcript id when the action came from voice, else null
 */
public record HistoryRecord(
        String actionId,
        Instant time,
        Origin origin,
        Provenance provenance,
        String commandId,
        String label,
        Map<String, Object> params,
        TargetScope target,
        List<String> macroLines,
        boolean macroExact,
        Map<String, String> globalsBefore,
        Map<String, String> globalsDelta,
        OperationState outcome,
        String errorSummary,
        String transcriptRef) {
}
