package sc.fidelis.nl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import sc.fidelis.nl.NlText.Quantity;

class NlTextTest {

    @Test
    void numberWordsBecomeNumerals() {
        assertEquals("blur sigma 2", NlText.normalise("Blur sigma two."));
        assertEquals("sigma 2.5", NlText.normalise("sigma two point five"));
        assertEquals("sigma 0.5", NlText.normalise("sigma zero point five"));
        assertEquals("radius 25", NlText.normalise("radius twenty five"));
        assertEquals("size 120", NlText.normalise("size one hundred and twenty"));
        assertEquals("size 1500", NlText.normalise("size one thousand five hundred"));
        assertEquals("sigma 2.5", NlText.normalise("sigma 2 point 5"));
        assertEquals("sigma 3 point", NlText.normalise("sigma three point"));
        assertEquals("16", NlText.numberWords("sixteen"));
    }

    @Test
    void unitsAreCanonicalised() {
        assertEquals("sigma 2 um", NlText.normalise("sigma 2 µm"));
        assertEquals("sigma 2 um", NlText.normalise("sigma 2 microns"));
        assertEquals("sigma 2 um", NlText.normalise("sigma two micrometres"));
        assertEquals("sigma 2 um", NlText.normalise("sigma 2um"));
        assertEquals("sigma 500 nm", NlText.normalise("sigma 500 nanometers"));
        assertEquals("radius 3 px", NlText.normalise("radius 3 pixels"));
        assertEquals("larger than 50 um2", NlText.normalise("larger than 50 square microns"));
        assertEquals("larger than 50 um2", NlText.normalise("larger than 50 µm²"));
        assertEquals("8 bit", NlText.normalise("8-bit"));
        assertEquals("size 50 to 500", NlText.normalise("size 50-500"));
    }

    @Test
    void sequencesSplitOnMarkers() {
        assertEquals(List.of("invert", "blur sigma 2"), NlText.splitSteps(NlText.normalise("Invert, then blur sigma 2")));
        assertEquals(List.of("invert", "blur"), NlText.splitSteps(NlText.normalise("invert and then blur")));
        assertEquals(List.of("invert", "measure"), NlText.splitSteps(NlText.normalise("invert after that measure")));
        assertEquals(List.of("invert", "measure"), NlText.splitSteps(NlText.normalise("invert and measure")));
        // "and" inside a clause is not a separator
        assertEquals(List.of("set measurements area and mean"), NlText.splitSteps(NlText.normalise("set measurements area and mean")));
        assertEquals(List.of("threshold with otsu and dark background"),
                NlText.splitSteps(NlText.normalise("threshold with otsu and dark background")));
    }

    @Test
    void quantitiesAreFoundNearKeywords() {
        Quantity q = NlText.quantity("blur with sigma 2 um", "sigma", true);
        assertNotNull(q);
        assertEquals(2.0, q.value());
        assertEquals("um", q.unit());
        q = NlText.quantity("blur 2.5", "sigma", true);
        assertEquals(2.5, q.value());
        assertNull(q.unit());
        assertNull(NlText.quantity("blur it", "sigma", true));
        assertNull(NlText.quantity("blur 2 or 3", "sigma", true)); // two bare numbers: ambiguous
    }

    @Test
    void lengthUnitsConvert() {
        assertEquals(1e-6, NlText.metresPer("um"), 1e-20);
        assertEquals(1e-6, NlText.metresPer("µm"), 1e-20);
        assertEquals(1e-6, NlText.metresPer("micron"), 1e-20);
        assertEquals(1e-9, NlText.metresPer("nm"), 1e-20);
        assertEquals(-1, NlText.metresPer("pixel"));
    }
}
