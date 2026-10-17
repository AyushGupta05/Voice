package sc.fidelis.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.TargetScope;

/** Ordered structured operation history (docs D07 §3). Macro text is derived from it, never the reverse. */
final class History {
    private final AtomicInteger seq = new AtomicInteger();
    private final List<HistoryRecord> records = new ArrayList<>();

    String nextId() {
        return "act-" + seq.incrementAndGet();
    }

    synchronized void add(HistoryRecord r) {
        records.add(r);
    }

    synchronized List<HistoryRecord> list() {
        return List.copyOf(records);
    }

    static HistoryRecord record(String actionId, Origin origin, Provenance provenance, String commandId, String label,
                                Map<String, Object> params, TargetScope target, List<String> macroLines,
                                boolean macroExact, Map<String, String> globalsBefore, Map<String, String> globalsDelta,
                                OperationState outcome, String errorSummary) {
        Op op = Op.CURRENT.get();
        Map<String, Object> p = params;
        String transcript = null;
        if (op != null) {
            Object t = op.request.params().get("transcriptRef");
            if (t != null) transcript = String.valueOf(t);
            Object parent = op.request.params().get("parentActionId");
            if (parent != null && (p == null || !p.containsKey("parentActionId"))) {
                p = p == null ? new LinkedHashMap<>() : new LinkedHashMap<>(p);
                p.put("parentActionId", String.valueOf(parent));
            }
        }
        return new HistoryRecord(actionId, Instant.now(), origin, provenance, commandId, label,
                ordered(p), target,
                macroLines == null ? List.of() : List.copyOf(macroLines), macroExact,
                ordered(globalsBefore),
                ordered(globalsDelta), outcome, errorSummary, transcript);
    }

    private static <V> Map<String, V> ordered(Map<String, V> m) {
        return m == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }
}
