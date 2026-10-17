package science.luma.qupath;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.util.*;
import qupath.imagej.detect.cells.WatershedCellDetection;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.parameters.*;
import qupath.lib.plugins.workflow.SimplePluginWorkflowStep;

/** Captures approved targets while preserving QuPath's public workflow replay identity. */
final class ApprovedCellDetection extends WatershedCellDetection {
    private final List<PathObject> parents;
    ApprovedCellDetection(Collection<PathObject> parents) { this.parents = List.copyOf(parents); }
    @Override protected Collection<? extends PathObject> getParentObjects(ImageData<BufferedImage> image) { return parents; }

    void validateParameters(ImageData<BufferedImage> image, JsonObject requested) {
        var parameters = getParameterList(image).getParameters();
        for (var entry : requested.entrySet()) {
            var parameter = parameters.get(entry.getKey());
            if (parameter == null || parameter.isHidden()) throw new IllegalArgumentException("Parameter is unavailable for this image: " + entry.getKey());
            var value = entry.getValue();
            if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Expected a scalar parameter: " + entry.getKey());
            var primitive = value.getAsJsonPrimitive();
            if (parameter instanceof BooleanParameter && !primitive.isBoolean()) throw new IllegalArgumentException("Expected a boolean: " + entry.getKey());
            if (parameter instanceof NumericParameter<?> numeric) {
                if (!primitive.isNumber()) throw new IllegalArgumentException("Expected a number: " + entry.getKey());
                double number = primitive.getAsDouble();
                if (!Double.isFinite(number) || number < numeric.getLowerBound() || number > numeric.getUpperBound() || parameter instanceof IntParameter && number != Math.rint(number)) throw new IllegalArgumentException("Number is outside the parameter range: " + entry.getKey());
            }
            if (parameter instanceof ChoiceParameter<?> && !primitive.isString()) throw new IllegalArgumentException("Expected an exact choice: " + entry.getKey());
            // QuPath's updater only logs rejected values and retains defaults. Refuse them before execution.
            if (!parameter.duplicate().setStringLastValue(Locale.US,primitive.getAsString())) throw new IllegalArgumentException("Unsupported value for " + entry.getKey() + ": " + primitive.getAsString());
        }
    }
    @Override protected boolean parseArgument(ImageData<BufferedImage> image, String argument) {
        validateParameters(image,JsonParser.parseString(argument).getAsJsonObject());
        var normalized = JsonParser.parseString(argument).getAsJsonObject();
        // NumericParameter's Locale.US parser treats lowercase exponent notation as a numeric prefix.
        // Give QuPath plain decimal strings, then verify its parsed values exactly match the request.
        for (var entry : normalized.entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) entry.setValue(new JsonPrimitive(entry.getValue().getAsBigDecimal().toPlainString()));
        }
        boolean parsed = super.parseArgument(image,normalized.toString());
        var requested = JsonParser.parseString(argument).getAsJsonObject();
        for (var entry : requested.entrySet()) {
            if (entry.getValue().getAsJsonPrimitive().isNumber()) {
                var actual = (Number)getParameterList(image).getParameters().get(entry.getKey()).getValueOrDefault();
                if (Double.compare(actual.doubleValue(),entry.getValue().getAsDouble()) != 0) throw new IllegalArgumentException("QuPath did not preserve the approved numeric value: " + entry.getKey());
            }
        }
        return parsed;
    }
    @Override protected void addWorkflowStep(ImageData<BufferedImage> image, String argument) {
        image.getHistoryWorkflow().addStep(new SimplePluginWorkflowStep(getName(),WatershedCellDetection.class,ParameterList.convertToJson(getLoggableParameters(image))));
    }
}
