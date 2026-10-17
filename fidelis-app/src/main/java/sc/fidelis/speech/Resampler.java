package sc.fidelis.speech;

/**
 * Band-limited sample-rate conversion (windowed-sinc, Hann window) used when the microphone
 * mixer rejects 16 kHz capture and the audio is taken at 44.1 or 48 kHz (docs D08 section 1, V-09).
 * The cut-off is placed at 0.45 of the lower of the two sample rates, so content above roughly
 * 7.2 kHz is attenuated before the rate is reduced.
 */
public final class Resampler {
    private static final int HALF_WIDTH = 16; // zero crossings of the sinc on each side, at the output rate

    private Resampler() {}

    public static float[] resample(float[] in, int inRate, int outRate) {
        if (inRate == outRate || in.length == 0) return in.clone();
        double ratio = (double) inRate / outRate;                  // input samples per output sample
        double cutoff = 0.45 * Math.min(inRate, outRate) / inRate; // cycles per input sample
        double scale = 2 * cutoff;                                 // sinc(2 fc x) normalisation
        int half = (int) Math.ceil(HALF_WIDTH / scale);            // input samples each side
        int outLen = (int) Math.floor(in.length / ratio);
        float[] out = new float[outLen];
        for (int n = 0; n < outLen; n++) {
            double centre = n * ratio;
            int lo = (int) Math.floor(centre) - half + 1;
            int hi = (int) Math.floor(centre) + half;
            double acc = 0;
            for (int k = lo; k <= hi; k++) {
                if (k < 0 || k >= in.length) continue;
                double x = k - centre;
                double sinc = x == 0 ? 1.0 : Math.sin(Math.PI * scale * x) / (Math.PI * scale * x);
                double window = 0.5 * (1 + Math.cos(Math.PI * x / half));
                acc += in[k] * sinc * window * scale;
            }
            out[n] = (float) acc;
        }
        return out;
    }

    /** Root-mean-square of samples normalised to the range -1 to 1. */
    public static double rms(float[] samples) {
        if (samples.length == 0) return 0;
        double sum = 0;
        for (float v : samples) sum += (double) v * v;
        return Math.sqrt(sum / samples.length);
    }

    public static byte[] toPcm16le(float[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * 32767f);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    /** Decode 16-bit little-endian PCM (interleaved) to mono floats, averaging the channels. */
    public static float[] fromPcm16le(byte[] pcm, int channels) {
        int frames = pcm.length / (2 * channels);
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                int idx = 2 * (f * channels + c);
                sum += (short) ((pcm[idx] & 0xff) | (pcm[idx + 1] << 8)) / 32768.0;
            }
            out[f] = (float) (sum / channels);
        }
        return out;
    }
}
