package sc.fidelis.penny;

import java.util.List;

/**
 * The catalogue ids the bridge's operations use (exact {@code ij1:<menu path>><label>} ids of the Fidelis catalogue;
 * docs/data/commands-core-parameters.csv). {@code PennyCatalogueTest} checks every id exists at run time and, except
 * the two parameterless legacy filters, has a complete schema.
 */
final class PennyCommands {
    private PennyCommands() {}

    static final String GAUSSIAN = "ij1:Process>Filters>Gaussian Blur...";
    static final String MEDIAN = "ij1:Process>Filters>Median...";
    static final String MEAN = "ij1:Process>Filters>Mean...";
    static final String UNSHARP = "ij1:Process>Filters>Unsharp Mask...";
    static final String SUBTRACT_BACKGROUND = "ij1:Process>Subtract Background...";
    static final String ENHANCE_CONTRAST = "ij1:Process>Enhance Contrast...";
    static final String INVERT = "ij1:Edit>Invert";
    static final String SMOOTH = "ij1:Process>Smooth";
    /** No catalogued options: runs through the engine's legacy route with empty params (no dialog opens). */
    static final String SHARPEN = "ij1:Process>Sharpen";
    /** No catalogued options: runs through the engine's legacy route with empty params (no dialog opens). */
    static final String FIND_EDGES = "ij1:Process>Find Edges";

    static final String TO_8_BIT = "ij1:Image>Type>8-bit";
    static final String TO_16_BIT = "ij1:Image>Type>16-bit";
    static final String TO_32_BIT = "ij1:Image>Type>32-bit";
    static final String TO_RGB = "ij1:Image>Type>RGB Color";
    static final String DUPLICATE = "ij1:Image>Duplicate...";

    static final String CONVERT_TO_MASK = "ij1:Process>Binary>Convert to Mask";
    static final String FILL_HOLES = "ij1:Process>Binary>Fill Holes";
    static final String WATERSHED = "ij1:Process>Binary>Watershed";

    static final String SET_MEASUREMENTS = "ij1:Analyze>Set Measurements...";
    static final String MEASURE = "ij1:Analyze>Measure";
    static final String ANALYZE_PARTICLES = "ij1:Analyze>Analyze Particles...";

    /** Every id above with a complete schema. */
    static final List<String> COMPLETE = List.of(GAUSSIAN, MEDIAN, MEAN, UNSHARP, SUBTRACT_BACKGROUND, ENHANCE_CONTRAST, INVERT,
            SMOOTH, TO_8_BIT, TO_16_BIT, TO_32_BIT, TO_RGB, DUPLICATE, CONVERT_TO_MASK, FILL_HOLES, WATERSHED, SET_MEASUREMENTS,
            MEASURE, ANALYZE_PARTICLES);

    /** Ids the bridge runs with empty params through the engine's legacy route. */
    static final List<String> LEGACY = List.of(SHARPEN, FIND_EDGES);
}
