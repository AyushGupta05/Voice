package science.luma.qupath;

import java.util.*;
import qupath.lib.objects.PathObject;

/** Full-scope descriptive statistics over existing stored measurements; never a biological inference. */
final class MeasurementSummary {
    private static final int MAX_OBJECTS = 1_000_000;
    private record Row(String classification,double[] values) {}
    static final class Snapshot {
        private final List<Row> rows;
        private final List<String> names;
        private Snapshot(List<Row> rows,List<String> names){this.rows=List.copyOf(rows);this.names=List.copyOf(names);}
    }
    static Snapshot capture(List<PathObject> objects,List<String> names) {
        if (objects.size() > MAX_OBJECTS || (long)objects.size()*names.size()>5_000_000) throw new IllegalArgumentException("Summary exceeds one million objects or five million numeric values; narrow objects or measurements.");
        if (names.isEmpty() || names.size() > 20 || new HashSet<>(names).size() != names.size() || names.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Choose 1–20 distinct measurement names.");
        var rows=new ArrayList<Row>();boolean[] found=new boolean[names.size()];
        for(var object:objects){double[] values=new double[names.size()];
            for(int i=0;i<names.size();i++){found[i]|=object.getMeasurementList().containsKey(names.get(i));values[i]=object.getMeasurementList().get(names.get(i));}
            rows.add(new Row(object.getPathClass()==null?null:object.getPathClass().toString(),values));
        }
        if(!objects.isEmpty())for(int i=0;i<names.size();i++)if(!found[i])throw new IllegalArgumentException("Measurement is absent from this scope: "+names.get(i));
        return new Snapshot(rows,names);
    }
    static Map<String,Object> summarize(List<PathObject> objects,List<String> names,int bins,boolean byClass) { return summarize(capture(objects,names),bins,byClass); }
    static Map<String,Object> summarize(Snapshot snapshot,int bins,boolean byClass) {
        if (bins < 1 || bins > 50) throw new IllegalArgumentException("Use 1–50 histogram bins.");
        var objects=snapshot.rows;var names=snapshot.names;
        var result = new LinkedHashMap<String,Object>();
        result.put("total", objects.size()); result.put("measurements", summarizeGroup(objects,names,bins));
        result.put("sampled",false); result.put("semantics","Existing finite stored values only; missing/non-finite values excluded explicitly. Sample standard deviation uses n−1. Histogram final bin includes its upper edge.");
        if (byClass) {
            var groups = new TreeMap<String,List<Row>>();
            for (var object : objects) groups.computeIfAbsent(object.classification()==null ? "0" : "1"+object.classification(), key -> new ArrayList<>()).add(object);
            var rows = new ArrayList<Map<String,Object>>();
            for (var entry : groups.entrySet().stream().limit(50).toList()) {
                var row = new LinkedHashMap<String,Object>(); row.put("classification",entry.getKey().equals("0") ? null : entry.getKey().substring(1));
                row.put("total",entry.getValue().size()); row.put("measurements",summarizeGroup(entry.getValue(),names,bins)); rows.add(row);
            }
            result.put("groups",rows); result.put("groupCount",groups.size()); result.put("groupsTruncated",groups.size()>50);
        }
        return result;
    }
    private static Map<String,Object> summarizeGroup(List<Row> objects,List<String> names,int bins) {
        var results = new LinkedHashMap<String,Object>();
        for (int measurement=0;measurement<names.size();measurement++) {
            String name=names.get(measurement);
            int n=0; double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for (var object : objects) {
                double value=object.values()[measurement]; if (!Double.isFinite(value)) continue;
                n++; min=Math.min(min,value); max=Math.max(max,value);
            }
            // Scale before Welford accumulation so opposite-signed finite extremes do not overflow subtraction.
            double scale=n==0?1:Math.max(Math.abs(min),Math.abs(max));if(scale==0)scale=1;
            double mean=0,m2=0;int observed=0;
            for(var object:objects){double value=object.values()[measurement];if(!Double.isFinite(value))continue;
                double normalized=value/scale;observed++;double delta=normalized-mean;mean+=delta/observed;m2+=delta*(normalized-mean);
            }
            var stats = new LinkedHashMap<String,Object>(); stats.put("count",n); stats.put("missingOrNonFinite",objects.size()-n);
            stats.put("min",n==0?null:min); stats.put("max",n==0?null:max); stats.put("mean",n==0?null:finite(mean*scale)); stats.put("sampleStandardDeviation",n<2?null:finite(Math.sqrt(Math.max(0,m2/(n-1)))*scale));
            var histogram = new ArrayList<Map<String,Object>>();
            if (n>0) {
                int count=min==max ? 1 : bins; long[] counts=new long[count];
                for (var object : objects) { double value=object.values()[measurement]; if (!Double.isFinite(value)) continue;
                    // Normalize by a common scale before subtracting, avoiding overflow for opposite-signed finite extremes.
                    double position=count==1?0:(value/scale-min/scale)/(max/scale-min/scale);
                    counts[Math.min(count-1,Math.max(0,(int)(position*count)))]++;
                }
                for (int i=0;i<count;i++) histogram.add(Map.of("lower",interpolate(min,max,i/(double)count),"upper",interpolate(min,max,(i+1)/(double)count),"upperInclusive",i==count-1,"count",counts[i]));
            }
            stats.put("histogram",histogram); results.put(name,stats);
        }
        return results;
    }
    private static double interpolate(double min,double max,double fraction) { return (1-fraction)*min+fraction*max; }
    private static Double finite(double value) { return Double.isFinite(value)?value:null; }
}
