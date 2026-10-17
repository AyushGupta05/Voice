package sc.fidelis.engine;

import java.util.LinkedHashMap;
import java.util.Map;

import ij.Prefs;
import ij.measure.Measurements;
import ij.plugin.filter.Analyzer;
import ij.process.ImageConverter;

/** Result-affecting global settings of IJ1 (docs A11 §6): captured, decoded, never set silently. */
final class Globals {
    private Globals() {}

    /** Set Measurements... option keys in IJ1 dialog order, paired with the Measurements bit. */
    private static final Object[][] MEASUREMENT_KEYS = {
        {"area", Measurements.AREA}, {"mean", Measurements.MEAN}, {"standard", Measurements.STD_DEV},
        {"modal", Measurements.MODE}, {"min", Measurements.MIN_MAX}, {"centroid", Measurements.CENTROID},
        {"center", Measurements.CENTER_OF_MASS}, {"perimeter", Measurements.PERIMETER},
        {"bounding", Measurements.RECT}, {"fit", Measurements.ELLIPSE}, {"shape", Measurements.SHAPE_DESCRIPTORS},
        {"feret's", Measurements.FERET}, {"integrated", Measurements.INTEGRATED_DENSITY},
        {"median", Measurements.MEDIAN}, {"skewness", Measurements.SKEWNESS}, {"kurtosis", Measurements.KURTOSIS},
        {"area_fraction", Measurements.AREA_FRACTION}, {"stack", Measurements.STACK_POSITION},
        {"limit", Measurements.LIMIT}, {"display", Measurements.LABELS}, {"invert", Measurements.INVERT_Y},
        {"scientific", Measurements.SCIENTIFIC_NOTATION}, {"add", Measurements.ADD_TO_OVERLAY},
        {"nan", Measurements.NaN_EMPTY_CELLS},
    };

    static Map<String, String> capture() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("blackBackground", String.valueOf(Prefs.blackBackground));
        g.put("measurements", measurementKeys(Analyzer.getMeasurements()));
        g.put("precision", String.valueOf(Analyzer.getPrecision()));
        g.put("doScaling", String.valueOf(ImageConverter.getDoScaling()));
        g.put("calibrateConversions", String.valueOf(Prefs.calibrateConversions));
        g.put("weightedColor", String.valueOf(Prefs.weightedColor));
        return g;
    }

    /** Space-separated Set Measurements... keys for the given bit mask. */
    static String measurementKeys(int bits) {
        StringBuilder sb = new StringBuilder();
        for (Object[] kv : MEASUREMENT_KEYS) {
            if ((bits & (Integer) kv[1]) != 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(kv[0]);
            }
        }
        return sb.toString();
    }

    /** Changed entries as {@code key -> "old=new"} (HistoryRecord.globalsDelta). */
    static Map<String, String> delta(Map<String, String> before, Map<String, String> after) {
        Map<String, String> d = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String old = before.get(e.getKey());
            if (old != null && !old.equals(e.getValue())) d.put(e.getKey(), old + "=" + e.getValue());
        }
        return d;
    }
}
