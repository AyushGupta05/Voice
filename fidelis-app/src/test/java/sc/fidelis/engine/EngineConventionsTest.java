package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/** E8 item 1: the NEW_IMAGE channels/frames and SET_DISPLAY_RANGE channel parameter conventions. */
class EngineConventionsTest {
    final DefaultEngine engine = EngineFixture.engine();

    @AfterEach
    void closeAll() throws Exception {
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                    TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }

    ActionResult create(Map<String, Object> p) throws Exception {
        return engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.NEW_IMAGE, p, null)).get(30, TimeUnit.SECONDS);
    }

    @Test
    void newImageWithChannelsAndFramesIsAHyperstackAndRecordsTheSevenArgumentForm() throws Exception {
        ActionResult r = create(m("title", "hs", "type", "16-bit", "fill", "Ramp", "width", 64, "height", 32, "channels", 3, "slices", 2, "frames", 4));
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        ImagePlus imp = engine.imagePlus(r.digest().added().get(0));
        assertEquals(64, imp.getWidth());
        assertEquals(32, imp.getHeight());
        assertEquals(3, imp.getNChannels());
        assertEquals(2, imp.getNSlices());
        assertEquals(4, imp.getNFrames());
        assertEquals(24, imp.getStackSize());
        assertEquals(16, imp.getBitDepth());
        assertTrue(imp instanceof CompositeImage, "more than one channel gives a composite");
        // Ramp: first row increases from 0 across the width (16-bit ramp spans 0..65535 over the width).
        imp.setPosition(1, 1, 1);
        assertEquals(0, imp.getProcessor().get(0, 0));
        assertTrue(imp.getProcessor().get(63, 0) > 60000, "ramp reaches the top at the right edge");
        String line = r.record().macroLines().get(0);
        assertEquals("newImage(\"hs\", \"16-bit ramp\", 64, 32, 3, 2, 4);", line);
        // The recorded form must be valid IJ1 macro and reproduce the same dimensions.
        int before = WindowCount.images();
        IJ.runMacro(line);
        ImagePlus replay = IJ.getImage();
        assertEquals(3, replay.getNChannels());
        assertEquals(2, replay.getNSlices());
        assertEquals(4, replay.getNFrames());
        assertEquals(16, replay.getBitDepth());
        replay.changes = false;
        replay.close();
        assertEquals(before, WindowCount.images());
    }

    @Test
    void newImageWithoutChannelsKeepsTheFiveArgumentFormAndRejectsBadValues() throws Exception {
        ActionResult r = create(m("title", "plain", "type", "8-bit", "fill", "White", "width", 10, "height", 10, "slices", 3));
        assertEquals("newImage(\"plain\", \"8-bit white\", 10, 10, 3);", r.record().macroLines().get(0));
        ImagePlus imp = engine.imagePlus(r.digest().added().get(0));
        assertFalse(imp instanceof CompositeImage);
        assertEquals(3, imp.getStackSize());
        assertEquals(255, imp.getProcessor().get(5, 5));
        ActionResult bad = create(m("title", "x", "type", "8-bit", "fill", "White", "width", 10, "height", 10, "channels", 0));
        assertEquals(ErrorCode.VALIDATION_FAILED, bad.error().code());
    }

    @Test
    void setDisplayRangeOnAChannelChangesOnlyThatChannelAndRestoresThePosition() throws Exception {
        ActionResult r = create(m("title", "comp", "type", "16-bit", "fill", "Ramp", "width", 32, "height", 32, "channels", 3, "slices", 1, "frames", 1));
        DocumentId id = r.digest().added().get(0);
        CompositeImage ci = (CompositeImage) engine.imagePlus(id);
        ci.setC(1);
        double min1 = ci.getChannelLut(1).min, max1 = ci.getChannelLut(1).max;
        String pix = Pixels.hash(ci, true);
        ActionResult d = engine.submit(ActionRequest.direct(Origin.PANEL, ActionRequest.Operations.SET_DISPLAY_RANGE,
                m("min", 100, "max", 5000, "channel", 3), TargetScope.of(id))).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, d.state(), String.valueOf(d.error()));
        assertEquals(100.0, ci.getChannelLut(3).min);
        assertEquals(5000.0, ci.getChannelLut(3).max);
        assertEquals(min1, ci.getChannelLut(1).min);
        assertEquals(max1, ci.getChannelLut(1).max);
        assertEquals(1, ci.getChannel(), "the original channel is restored");
        assertEquals(pix, Pixels.hash(ci, true), "display range never changes pixels");
        assertEquals(List.of("Stack.setChannel(3);", "setMinAndMax(100, 5000);", "Stack.setChannel(1);"), d.record().macroLines());

        // Replaying the recorded lines in IJ1 on an identical composite gives the same result.
        ImagePlus other = IJ.createImage("rep", "16-bit ramp", 32, 32, 3, 1, 1);
        other.show();
        IJ.selectWindow("rep");
        IJ.runMacro(String.join("\n", d.record().macroLines()));
        CompositeImage oc = (CompositeImage) IJ.getImage();
        assertEquals(100.0, oc.getChannelLut(3).min);
        assertEquals(5000.0, oc.getChannelLut(3).max);
        assertEquals(1, oc.getChannel());
        oc.changes = false;
        oc.close();
    }

    @Test
    void channelParameterIsValidated() throws Exception {
        ActionResult r = create(m("title", "c2", "type", "8-bit", "fill", "Black", "width", 8, "height", 8, "channels", 2));
        DocumentId id = r.digest().added().get(0);
        ActionResult out = engine.submit(ActionRequest.direct(Origin.PANEL, ActionRequest.Operations.SET_DISPLAY_RANGE,
                m("min", 0, "max", 10, "channel", 5), TargetScope.of(id))).get(30, TimeUnit.SECONDS);
        assertEquals(ErrorCode.VALIDATION_FAILED, out.error().code());
        ActionResult single = create(m("title", "s", "type", "8-bit", "fill", "Black", "width", 8, "height", 8));
        ActionResult out2 = engine.submit(ActionRequest.direct(Origin.PANEL, ActionRequest.Operations.SET_DISPLAY_RANGE,
                m("min", 0, "max", 10, "channel", 1), TargetScope.of(single.digest().added().get(0)))).get(30, TimeUnit.SECONDS);
        assertEquals(ErrorCode.VALIDATION_FAILED, out2.error().code());
    }

    /** Tiny helper: number of IJ1 images. */
    static final class WindowCount {
        static int images() {
            return ij.WindowManager.getImageCount();
        }
    }
}
