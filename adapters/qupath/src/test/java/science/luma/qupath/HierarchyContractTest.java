package science.luma.qupath;

import java.util.List;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.*;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;

public final class HierarchyContractTest {
    public static void main(String[] ignored) {
        var hierarchy = new PathObjectHierarchy();
        var root = hierarchy.getRootObject();
        root.setName("Image-level findings");
        root.getMetadata().put("fixture","root metadata");
        root.getMeasurementList().put("Image score",42.125);
        var annotation = PathObjects.createAnnotationObject(ROIs.createRectangleROI(1.234567891,2.345678912,31.456789123,25.567891234,ImagePlane.getDefaultPlane()));
        annotation.setPathClass(PathClass.fromString("Test class"),0.9374123456789);
        annotation.getMeasurementList().put("Measured value",0.12345678912345);
        hierarchy.addObject(annotation);
        var core=PathObjects.createTMACoreObject(50,50,10,false);
        hierarchy.setTMAGrid(DefaultTMAGrid.create(List.of(core),1));
        byte[] snapshot=HierarchySnapshot.capture(hierarchy);
        annotation.setPathClass(null); annotation.getMeasurementList().clear(); root.getMeasurementList().clear();
        var restored=HierarchySnapshot.restore(snapshot);
        var recovered=restored.getFlattenedObjectList(null).stream().filter(o->o.getID().equals(annotation.getID())).findFirst().orElseThrow();
        exact(recovered.getROI().getBoundsX(),1.234567891);
        exact(recovered.getROI().getBoundsHeight(),25.567891234);
        exact(recovered.getClassProbability(),0.9374123456789);
        exact(recovered.getMeasurementList().get("Measured value"),0.12345678912345);
        exact(restored.getRootObject().getMeasurementList().get("Image score"),42.125);
        if (!"root metadata".equals(restored.getRootObject().getMetadata().get("fixture"))) throw new AssertionError("Root metadata lost");
        if (!restored.getRootObject().getID().equals(root.getID())) throw new AssertionError("Root identity lost");
        if (restored.getTMAGrid().nCores()!=1 || !restored.getTMAGrid().getTMACoreList().getFirst().getID().equals(core.getID())) throw new AssertionError("TMA grid lost");
        if (!restored.getFlattenedObjectList(null).contains(restored.getTMAGrid().getTMACoreList().getFirst())) throw new AssertionError("TMA core detached from hierarchy");
        restored.addObject(PathObjects.createAnnotationObject(ROIs.createRectangleROI(0,0,1,1,ImagePlane.getDefaultPlane())));
        System.out.println("Native hierarchy recovery preserves ROI precision, confidence, measurements, metadata, IDs and TMA grid: passed");
    }
    private static void exact(double actual,double expected) { if(Double.compare(actual,expected)!=0) throw new AssertionError(actual + " != " + expected); }
}
