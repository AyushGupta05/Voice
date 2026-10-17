package sc.fidelis.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.MeasurementRow;

/** Copies rows of the IJ1 system Results table at full double precision into engine-owned records (E4). */
final class Results {
    private final Events events;
    private final List<MeasurementRow> rows = new ArrayList<>();
    private int lastSeen;

    Results(Events events) {
        this.events = events;
    }

    /** Current row count of the IJ1 system table. */
    static int tableSize() {
        ResultsTable rt = ResultsTable.getResultsTable();
        return rt == null ? 0 : rt.size();
    }

    /** Capture table rows {@code [from, size())}; returns how many were captured. */
    int capture(int from, DocumentId doc, String actionId) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null) return 0;
        int n = rt.size();
        if (n <= from) return 0;
        String[] headings = rt.getHeadings();
        List<MeasurementRow> fresh = new ArrayList<>();
        for (int r = Math.max(0, from); r < n; r++) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (String h : headings) {
                if (h == null || h.equals("Label")) continue;
                int col = rt.getColumnIndex(h);
                if (col == ResultsTable.COLUMN_NOT_FOUND) continue;
                values.put(h, rt.getValueAsDouble(col, r));
            }
            fresh.add(new MeasurementRow(r, rt.getLabel(r), values, doc, actionId));
        }
        int total;
        synchronized (this) {
            rows.addAll(fresh);
            total = rows.size();
            lastSeen = n;
        }
        events.emit(seq -> new EngineEvent.ResultsChanged(seq, total));
        return fresh.size();
    }

    /** Note the current table size as already accounted for (after typed actions, including Clear Results). */
    synchronized void markSeen() {
        lastSeen = tableSize();
    }

    /** After a native command: capture rows it added to the system table. */
    int captureNative(DocumentId doc, String actionId) {
        int from;
        synchronized (this) {
            from = lastSeen;
            if (tableSize() < from) {
                lastSeen = tableSize();
                return 0;
            }
        }
        return capture(from, doc, actionId);
    }

    synchronized List<MeasurementRow> all() {
        return List.copyOf(rows);
    }

    MeasurementRow.Columns columns() {
        ResultsTable rt = ResultsTable.getResultsTable();
        List<String> h = new ArrayList<>();
        if (rt != null) for (String s : rt.getHeadings()) if (s != null && !s.equals("Label")) h.add(s);
        return new MeasurementRow.Columns(h, Analyzer.getPrecision());
    }
}
