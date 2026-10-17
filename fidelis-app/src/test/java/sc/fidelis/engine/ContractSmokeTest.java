package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.ImagePlus;
import net.imagej.legacy.LegacyService;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.testkit.FijiFixture;

/** M0: the toolchain, the contract types and a live Fiji context work in the test JVM. */
class ContractSmokeTest {

    @Test
    void contractRecordsValidate() {
        TargetScope target = TargetScope.of(new DocumentId("doc-1"));
        ActionRequest r = ActionRequest.command(Origin.MENU, "ij1:Edit>Invert", Map.of(), target);
        assertEquals("ij1:Edit>Invert", r.commandId());
        assertTrue(r.params().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new DocumentId(" "));
        assertThrows(IllegalArgumentException.class,
                () -> ActionRequest.direct(Origin.PANEL, "", Map.of(), target));
    }

    @Test
    void liveFijiRunsARealCommand() {
        LegacyService legacy = FijiFixture.imageJ().get(LegacyService.class);
        assertTrue(legacy.isActive(), "ImageJ1 legacy layer must be active (FR-SYS-002)");
        ImagePlus imp = IJ.createImage("t", "8-bit ramp", 32, 32, 1);
        int before = imp.getProcessor().get(5, 5);
        IJ.run(imp, "Invert", "");
        assertEquals(255 - before, imp.getProcessor().get(5, 5));
        assertFalse(java.awt.GraphicsEnvironment.isHeadless());
        imp.changes = false;
        imp.close();
    }
}
