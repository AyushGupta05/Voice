package sc.fidelis.nl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.Enums.ActionKind;
import sc.fidelis.engine.api.Enums.ConsequenceClass;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.nl.NlService.Proposal;

/**
 * Deterministic confirmation rules for a {@link Proposal} (docs D08 section 2.5 policy table, D17, FR-NL-006,
 * FR-SPEECH-004). {@link NlService.Proposal} is a frozen contract record, so the decision is computed from it
 * rather than stored in it; the UI asks {@link DefaultNlService#confirmation(Proposal)} or
 * {@link VoiceToProposal.Result#confirmation()}.
 *
 * <ul>
 *   <li>{@code required}: the user must confirm before anything is submitted. True for every spoken proposal
 *       (nothing executes from speech unconfirmed) and for every typed proposal with a step of consequence AC2 or
 *       higher (D08 preview and confirm). A typed proposal made only of AC0/AC1 steps may auto-execute.</li>
 *   <li>{@code explicit}: stronger. The preview must name the reason and the confirm control must be a distinct,
 *       deliberate act (never a default button or a spoken "yes" alone). True for a step of consequence AC4 or
 *       higher (non-undoable data, global settings, persistent external effects, code), for a {@code black}
 *       parameter (an explicit change of the global BlackBackground preference), for CODE requests, for any
 *       consequential slot that is not USER or DERIVED, and for every proposal that is not READY.</li>
 * </ul>
 */
public final class ConfirmationPolicy {

    /** @param reasons human-readable reasons for the explicit requirement, empty when only {@code required} applies */
    public record Decision(boolean required, boolean explicit, List<String> reasons) {
        public Decision {
            reasons = List.copyOf(reasons);
        }
    }

    private final Function<String, Optional<CommandSchema>> schemas;

    /** @param schemas command id to schema; an unknown command is treated as the most consequential case */
    public ConfirmationPolicy(Function<String, Optional<CommandSchema>> schemas) {
        this.schemas = schemas;
    }

    /** Without a catalogue every command step is treated as explicit (used when the service is not a DefaultNlService). */
    public static ConfirmationPolicy strict() {
        return new ConfirmationPolicy(id -> Optional.empty());
    }

    public Decision assess(Proposal p) {
        List<String> reasons = new ArrayList<>();
        if (p.status() != Proposal.Status.READY) {
            return new Decision(true, true, List.of("nothing can run: " + (p.status() == Proposal.Status.REFUSED
                    ? "the request was refused" : "slots are missing or ambiguous")));
        }
        boolean spoken = false;
        boolean aboveAc1 = false;
        for (int i = 0; i < p.requests().size(); i++) {
            ActionRequest r = p.requests().get(i);
            spoken |= r.origin() == Origin.NL_SPOKEN;
            ConsequenceClass cc = consequence(r);
            if (cc.ordinal() >= ConsequenceClass.AC2_SELECTION.ordinal()) aboveAc1 = true;
            String name = r.commandId() != null ? r.commandId() : r.operation();
            if (r.kind() == ActionKind.CODE) reasons.add("step " + (i + 1) + " (" + name + ") is code");
            else if (cc.ordinal() >= ConsequenceClass.AC4_NON_UNDOABLE_DATA.ordinal())
                reasons.add("step " + (i + 1) + " (" + name + ") is " + cc);
            if (r.params().containsKey("black"))
                reasons.add("step " + (i + 1) + " (" + name + ") changes the global BlackBackground preference");
            Map<String, String> prov = i < p.slotProvenance().size() ? p.slotProvenance().get(i) : Map.of();
            if (r.kind() == ActionKind.COMMAND) {
                Optional<CommandSchema> schema = schemas.apply(r.commandId());
                if (schema.isPresent()) {
                    for (ParameterSchema s : schema.get().parameters()) {
                        if (!s.consequential()) continue;
                        String pv = prov.get(s.key());
                        if (!r.params().containsKey(s.key()) || !("USER".equals(pv) || "DERIVED".equals(pv)))
                            reasons.add("step " + (i + 1) + " (" + name + ") has consequential slot '" + s.key() + "' not set by the user");
                    }
                }
            }
        }
        boolean explicit = !reasons.isEmpty();
        return new Decision(spoken || aboveAc1 || explicit, explicit, reasons);
    }

    private ConsequenceClass consequence(ActionRequest r) {
        switch (r.kind()) {
            case CODE:
                return ConsequenceClass.AC7_CODE;
            case COMMAND:
                return schemas.apply(r.commandId()).map(CommandSchema::consequence).orElse(ConsequenceClass.AC7_CODE);
            default:
                String op = r.operation();
                if (Operations.SAVE_AS.equals(op)) return ConsequenceClass.AC6_PERSISTENT_EXTERNAL;
                if (Operations.CLOSE.equals(op)) return ConsequenceClass.AC4_NON_UNDOABLE_DATA;
                if (Operations.OPEN.equals(op)) return ConsequenceClass.AC0_READ_ONLY;
                return ConsequenceClass.AC2_SELECTION;
        }
    }
}
