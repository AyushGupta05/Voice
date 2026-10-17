package sc.fidelis.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ResamplerTest {

    private static float[] sine(double freq, int rate, int seconds) {
        float[] s = new float[rate * seconds];
        for (int i = 0; i < s.length; i++) s[i] = (float) (0.5 * Math.sin(2 * Math.PI * freq * i / rate));
        return s;
    }

    /** RMS ignoring the filter's edge transients. */
    private static double centreRms(float[] x) {
        int a = x.length / 10, b = x.length - a;
        double sum = 0;
        for (int i = a; i < b; i++) sum += (double) x[i] * x[i];
        return Math.sqrt(sum / (b - a));
    }

    @Test
    void passbandToneKeepsItsAmplitude() {
        float[] out = Resampler.resample(sine(1000, 48000, 1), 48000, 16000);
        assertEquals(16000, out.length, 1);
        assertEquals(0.5 / Math.sqrt(2), centreRms(out), 0.01);
    }

    @Test
    void toneAboveTheNewNyquistIsAttenuated() {
        // 12 kHz would alias to 4 kHz at 16 kHz without an anti-alias filter
        float[] out = Resampler.resample(sine(12000, 48000, 1), 48000, 16000);
        assertTrue(centreRms(out) < 0.02, "aliasing residue rms " + centreRms(out));
    }

    @Test
    void worksFor44100() {
        float[] out = Resampler.resample(sine(2000, 44100, 1), 44100, 16000);
        assertEquals(16000, out.length, 1);
        assertEquals(0.5 / Math.sqrt(2), centreRms(out), 0.01);
    }

    @Test
    void pcmRoundTripAndStereoMixdown() {
        float[] mono = {0f, 0.5f, -0.5f, 1f};
        float[] back = Resampler.fromPcm16le(Resampler.toPcm16le(mono), 1);
        for (int i = 0; i < mono.length; i++) assertEquals(mono[i], back[i], 1e-3);
        byte[] stereo = Resampler.toPcm16le(new float[] {0.5f, -0.5f, 0.25f, 0.25f});
        float[] mixed = Resampler.fromPcm16le(stereo, 2);
        assertEquals(0f, mixed[0], 1e-3);
        assertEquals(0.25f, mixed[1], 1e-3);
    }

    @Test
    void jsonRoundTrip() {
        Map<String, Object> m = Json.parseObject("{\"a\":[1,2.5,\"x\\u00b5\"],\"b\":{\"c\":true,\"d\":null}}");
        assertEquals(List.of(1L, 2.5, "xµ"), m.get("a"));
        assertEquals("{\"a\":[1,2.5,\"x\\u00b5\"],\"b\":{\"c\":true,\"d\":null}}", Json.write(m));
    }
}
