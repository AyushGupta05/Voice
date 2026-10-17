package sc.fidelis.engine.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import sc.fidelis.engine.api.Enums.ConsequenceClass;
import sc.fidelis.engine.api.Enums.ParameterType;

/**
 * Typed description of one catalogue command (docs D06 §1; generated from the runtime IJ1 command
 * table plus {@code docs/data/commands-core-parameters.csv}).
 *
 * @param commandId          stable id, e.g. {@code ij1:Process>Filters>Gaussian Blur...}
 * @param label              exact IJ1 label (whitespace preserved) used with {@code IJ.run}
 * @param menuPath           e.g. {@code Process>Filters}
 * @param parameters         complete parameter list; empty if the command takes no options
 * @param schemaComplete     false when the command is known but its parameters are not catalogued
 *                           (the engine then surfaces the original dialog, FR-PROC-007)
 * @param headlessOk         A08 headless probe passed (informational; the engine always runs in a GUI JVM)
 * @param consequence        default consequence class (may be raised at run time)
 * @param preconditions      predicates checked before execution (docs D06 §3), e.g. {@code image.open},
 *                           {@code image.type in {GRAY8,GRAY16,GRAY32}}, {@code roi.required}, {@code threshold.set}
 * @param postconditionFamily IN_PLACE_PIXELS, NEW_IMAGE, RESULTS_ROWS, ROIS_ADDED, TYPE_CHANGE,
 *                           GLOBALS_ONLY, FILE_WRITTEN, DISPLAY_ONLY
 * @param createsNewImage    yes, no or conditional
 * @param hiddenGlobals      result-affecting globals the command reads (e.g. Prefs.blackBackground)
 */
public record CommandSchema(
        String commandId,
        String label,
        String menuPath,
        List<ParameterSchema> parameters,
        boolean schemaComplete,
        boolean headlessOk,
        ConsequenceClass consequence,
        List<String> preconditions,
        String postconditionFamily,
        String createsNewImage,
        List<String> hiddenGlobals) {

    public Optional<ParameterSchema> parameter(String key) {
        return parameters.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    /** One macro option key (docs D06 §1 ParameterSchema). */
    public record ParameterSchema(
            String key,
            ParameterType type,
            String defaultValue,
            List<String> choices,
            Double min,
            Double max,
            boolean consequential,
            String unit,
            boolean defaultIsRemembered,
            String visibleWhen,
            String help,
            String source) {
    }

    /** Minimal view used by menus and search (docs D09 §4 {@code catalogue}). */
    public record Summary(String commandId, String label, String menuPath, String shortcut,
                          String framework, String compatCategory, Map<String, String> notes) {
    }
}
