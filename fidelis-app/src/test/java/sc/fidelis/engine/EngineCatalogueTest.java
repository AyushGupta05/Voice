package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.Enums.ParameterType;

/** E2: the runtime catalogue, menu paths, schemas and search. */
class EngineCatalogueTest {

    @Test
    void catalogueCoversTheRuntimeCommandTable() {
        DefaultEngine engine = EngineFixture.engine();
        List<CommandSchema.Summary> all = engine.catalogue();
        System.out.println("[catalogue] " + all.size() + " commands");
        assertTrue(all.size() > 500, "expected the full IJ1/Fiji command table, got " + all.size());
        long complete = all.stream().filter(s -> "complete".equals(s.notes().get("schema"))).count();
        System.out.println("[catalogue] schema complete: " + complete);
        assertTrue(complete >= 50);
        long unlisted = all.stream().filter(s -> s.menuPath().equals("Unlisted")).count();
        System.out.println("[catalogue] unlisted menu path: " + unlisted);
    }

    @Test
    void gaussianBlurSchemaIsComplete() {
        DefaultEngine engine = EngineFixture.engine();
        Optional<CommandSchema> s = engine.schema("ij1:Process>Filters>Gaussian Blur...");
        assertTrue(s.isPresent());
        CommandSchema cs = s.get();
        assertEquals("Gaussian Blur...", cs.label());
        assertEquals("Process>Filters", cs.menuPath());
        assertTrue(cs.schemaComplete());
        assertEquals("IN_PLACE_PIXELS", cs.postconditionFamily());
        assertEquals(3, cs.parameters().size());
        CommandSchema.ParameterSchema sigma = cs.parameter("sigma").orElseThrow();
        assertEquals(ParameterType.NUMBER, sigma.type());
        assertEquals(0.0, sigma.min());
        assertTrue(cs.parameter("scaled").orElseThrow().type() == ParameterType.BOOLEAN);
        assertNotNull(cs.parameter("stack").orElseThrow().visibleWhen());
    }

    @Test
    void everyCataloguedCoreCommandIsPresentInTheRuntime() {
        DefaultEngine engine = EngineFixture.engine();
        for (String id : List.of("ij1:Edit>Invert", "ij1:Analyze>Measure", "ij1:Analyze>Set Measurements...",
                "ij1:Analyze>Analyze Particles...", "ij1:Image>Duplicate...", "ij1:Image>Stacks>Z Project...",
                "ij1:Process>Image Calculator...", "ij1:Process>Binary>Convert to Mask", "ij1:Image>Type>8-bit",
                "ij1:File>Save As>Tiff...", "ij1:Process>Filters>Median...", "ij1:Process>Subtract Background...",
                "ij1:Process>Binary>Options...", "ij1:Image>Transform>Rotate...", "ij1:File>New>Image...")) {
            Optional<CommandSchema> s = engine.schema(id);
            if (s.isEmpty()) {
                List<CommandSchema.Summary> alt = engine.search(id.substring(id.lastIndexOf('>') + 1), 5);
                System.out.println("[catalogue] MISSING " + id + " ; search gives " + alt.stream().map(CommandSchema.Summary::commandId).toList());
            }
            assertTrue(s.isPresent(), id);
        }
    }

    @Test
    void postconditionFamiliesAreAssigned() {
        DefaultEngine engine = EngineFixture.engine();
        assertEquals("IN_PLACE_PIXELS", engine.schema("ij1:Edit>Invert").orElseThrow().postconditionFamily());
        assertEquals("IN_PLACE_PIXELS", engine.schema("ij1:Image>Transform>Rotate...").orElseThrow().postconditionFamily());
        assertEquals("NEW_IMAGE", engine.schema("ij1:Image>Duplicate...").orElseThrow().postconditionFamily());
        assertEquals("NEW_IMAGE", engine.schema("ij1:Image>Stacks>Z Project...").orElseThrow().postconditionFamily());
        assertEquals("NEW_IMAGE", engine.schema("ij1:Process>Image Calculator...").orElseThrow().postconditionFamily());
        assertEquals("RESULTS_ROWS", engine.schema("ij1:Analyze>Measure").orElseThrow().postconditionFamily());
        assertEquals("RESULTS_ROWS", engine.schema("ij1:Analyze>Analyze Particles...").orElseThrow().postconditionFamily());
        assertEquals("TYPE_CHANGE", engine.schema("ij1:Image>Type>8-bit").orElseThrow().postconditionFamily());
        assertEquals("GLOBALS_ONLY", engine.schema("ij1:Analyze>Set Measurements...").orElseThrow().postconditionFamily());
        assertEquals("GLOBALS_ONLY", engine.schema("ij1:Process>Binary>Options...").orElseThrow().postconditionFamily());
        assertEquals("FILE_WRITTEN", engine.schema("ij1:File>Save As>Tiff...").orElseThrow().postconditionFamily());
        assertEquals(List.of("image.open", "image.binary"), engine.schema("ij1:Process>Binary>Erode").orElseThrow().preconditions());
    }

    @Test
    void searchRanksLabelPrefixFirst() {
        DefaultEngine engine = EngineFixture.engine();
        List<CommandSchema.Summary> r = engine.search("gauss", 5);
        assertFalse(r.isEmpty());
        assertTrue(r.get(0).label().toLowerCase().startsWith("gaussian"), r.get(0).label());
        List<CommandSchema.Summary> m = engine.search("measure", 10);
        assertEquals("Measure", m.get(0).label());
        assertTrue(engine.search("filters blur", 10).stream().anyMatch(s -> s.label().equals("Gaussian Blur...")));
    }
}
