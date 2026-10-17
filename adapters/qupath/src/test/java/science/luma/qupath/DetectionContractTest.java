package science.luma.qupath;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.plugins.workflow.SimplePluginWorkflowStep;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;

/** Runs against the installed QuPath jars; no UI, files, or research data are mutated. */
public final class DetectionContractTest {
    public static void main(String[] ignored) {
        var image = new ImageData<BufferedImage>(new WrappedBufferedImageServer("Synthetic contract fixture",new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB)),ImageData.ImageType.BRIGHTFIELD_H_DAB);
        var parents = new ArrayList<PathObject>();
        var annotation = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0,0,32,32,ImagePlane.getDefaultPlane()));
        parents.add(annotation);
        var plugin = new ApprovedCellDetection(parents);
        parents.clear();
        if (!plugin.getParentObjects(image).contains(annotation)) throw new AssertionError("Approved parent set must be immutable");
        expectRejected(plugin,image,"{\"detectionImageBrightfield\":\"DAB OD\"}");
        expectRejected(plugin,image,"{\"detectionImageBrightfield\":\"Hematoxylin OD\",\"threshold\":\"0.1oops\"}");
        expectRejected(plugin,image,"{\"makeMeasurements\":\"yes\"}");
        expectRejected(plugin,image,"{\"unknownParameter\":1}");
        String parameters="{\"detectionImageBrightfield\":\"Hematoxylin OD\",\"threshold\":0.2,\"makeMeasurements\":true}";
        if (!plugin.parseArgument(image,parameters)) throw new AssertionError("Valid parameters rejected");
        plugin.addWorkflowStep(image,parameters);
        var step=(SimplePluginWorkflowStep)image.getHistoryWorkflow().getLastStep();
        if (!step.getPluginClass().equals("qupath.imagej.detect.cells.WatershedCellDetection")) throw new AssertionError("Workflow must replay upstream plugin");
        if (!step.getParameterMap().get("threshold").equals("0.2") || !step.getParameterMap().containsKey("smoothBoundaries")) throw new AssertionError("Workflow must record executed values and defaults");
        String tiny="{\"detectionImageBrightfield\":\"Hematoxylin OD\",\"threshold\":1e-7}";
        plugin.parseArgument(image,tiny);
        plugin.addWorkflowStep(image,tiny);
        var tinyStep=(SimplePluginWorkflowStep)image.getHistoryWorkflow().getLastStep();
        if (Double.parseDouble(tinyStep.getParameterMap().get("threshold").toString())!=1e-7) throw new AssertionError("Scientific notation changed approved threshold");
        System.out.println("Detection parameter rejection, approved parent capture and replayable workflow: passed");
    }
    private static void expectRejected(ApprovedCellDetection plugin,ImageData<BufferedImage> image,String json) {
        try { plugin.validateParameters(image,JsonParser.parseString(json).getAsJsonObject()); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid parameters were silently accepted: " + json);
    }
}
