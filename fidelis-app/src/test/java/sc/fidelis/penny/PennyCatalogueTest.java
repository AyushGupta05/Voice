package sc.fidelis.penny;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ij.Prefs;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.nl.ConfirmationPolicy;
import sc.fidelis.nl.NlService.Proposal;
import sc.fidelis.testkit.ProductEngine;

/** Every catalogue id the bridge hard-codes exists in the real runtime catalogue, with the schema the bridge relies on. */
class PennyCatalogueTest {

    @Test
    void everyHardCodedCommandExistsWithACompleteSchema() {
        Engine engine = ProductEngine.get();
        for (String id : PennyCommands.COMPLETE) {
            Optional<CommandSchema> s = engine.schema(id);
            assertTrue(s.isPresent(), "not in the catalogue: " + id);
            assertTrue(s.get().schemaComplete(), "schema not complete: " + id);
            StringBuilder sb = new StringBuilder("[penny-catalogue] " + id + " " + s.get().consequence() + " " + s.get().postconditionFamily() + ":");
            for (ParameterSchema p : s.get().parameters())
                sb.append(' ').append(p.key()).append('=').append(p.type()).append(p.consequential() ? "*" : "").append('[').append(p.defaultValue()).append(']');
            System.out.println(sb);
        }
        for (String id : PennyCommands.LEGACY) {
            assertTrue(engine.schema(id).isPresent(), "not in the catalogue: " + id);
            System.out.println("[penny-catalogue] " + id + " legacy route, schemaComplete=" + engine.schema(id).get().schemaComplete());
        }
    }

    @Test
    void segmentCellsRecipeFillsEveryConsequentialSlot() {
        Engine engine = ProductEngine.get();
        // Each COMMAND the segment_cells recipe uses must leave no consequential slot to a bare catalogue default.
        Map<String, Map<String, Object>> recipes = new LinkedHashMap<>();
        recipes.put(PennyCommands.GAUSSIAN, Map.of("sigma", 1.0, "scaled", false, "stack", false));
        recipes.put(PennyCommands.CONVERT_TO_MASK, Map.of("method", "Default", "background", "Default", "calculate", false, "only", false, "black", true));
        Map<String, Object> ap = new LinkedHashMap<>(Map.of("size", "0-Infinity", "pixel", false, "circularity", "0.00-1.00", "show", "Nothing",
                "display", true, "exclude", false, "clear", true, "include", false, "summarize", false, "overlay", false));
        ap.putAll(Map.of("add", true, "composite", false, "record", false, "in_situ", false, "stack", false));
        recipes.put(PennyCommands.ANALYZE_PARTICLES, ap);
        for (Map.Entry<String, Map<String, Object>> e : recipes.entrySet()) {
            CommandSchema s = engine.schema(e.getKey()).orElseThrow();
            Params.Filled f = Params.fill(s, Map.of(), e.getValue(), null);
            for (ParameterSchema p : s.parameters()) {
                if (!p.consequential()) continue;
                String prov = f.provenance().get(p.key());
                assertTrue("DERIVED".equals(prov) || "USER".equals(prov), e.getKey() + " consequential '" + p.key() + "' is " + prov);
            }
        }
    }

    @Test
    void blackEqualToTheCurrentGlobalIsNotAGlobalChangeButADifferentValueIs() {
        Engine engine = ProductEngine.get();
        boolean before = Prefs.blackBackground;
        try {
            Prefs.blackBackground = true;
            ConfirmationPolicy valueAware = new ConfirmationPolicy(engine::schema, engine::globals);
            ConfirmationPolicy plain = new ConfirmationPolicy(engine::schema);
            Proposal same = mask(engine, true);
            Proposal other = mask(engine, false);
            assertFalse(valueAware.assess(same).explicit(), valueAware.assess(same).reasons().toString());
            assertTrue(valueAware.assess(other).explicit());
            assertTrue(valueAware.assess(other).reasons().stream().anyMatch(r -> r.contains("BlackBackground")));
            assertTrue(plain.assess(same).explicit(), "the original constructor keeps every black parameter explicit");
        } finally {
            Prefs.blackBackground = before;
        }
    }

    private static Proposal mask(Engine engine, boolean black) {
        CommandSchema s = engine.schema(PennyCommands.CONVERT_TO_MASK).orElseThrow();
        Params.Filled f = Params.fill(s, Map.of(), Map.of("method", "Default", "background", "Default", "calculate", false, "only", false,
                "black", black), null);
        ActionRequest r = ActionRequest.command(Origin.NL_TYPED, s.commandId(), f.params(), TargetScope.of(new DocumentId("doc-x")));
        return new Proposal(Proposal.Status.READY, "mask", List.of(r), List.of("Process>Binary"), List.of(""), List.of(f.provenance()),
                List.of(), null);
    }

    @Test
    void operationListFitsTheGatewayReadinessRules() {
        List<String> ops = PennyOps.OPERATIONS;
        assertTrue(ops.size() <= 64);
        assertEquals(ops.size(), ops.stream().distinct().count());
        for (String op : ops) assertTrue(op.matches("^[a-z][a-z0-9_]{0,63}$"), op);
        for (String op : PennyOps.MUTATING) assertTrue(ops.contains(op), op);
        for (String op : PennyOps.READ) assertTrue(ops.contains(op), op);
        String sha = String.valueOf(FidelisBridge.identity().get("sourceSha256"));
        assertTrue(sha.matches("[0-9a-f]{64}"), sha);
    }
}
