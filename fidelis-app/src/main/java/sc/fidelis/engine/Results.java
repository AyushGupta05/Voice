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

/**
 * Copies rows of the IJ1 system Results table at full double precision into engine-owned records (E4).
 * The engine list mirrors the live table: rows are attributed to the action that produced them, and
 * when IJ1 restarts the table (Analyze Particles with {@code clear}, Clear Results, a shrink seen after
 * a native command) the list is rebuilt from row 0, so the Results panel shows what Fiji's window shows.
 */
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

    /**
     * Capture table rows {@code [from, size())}; returns how many were captured. {@code from == 0}, a list
     * longer than {@code from} or a table shorter than the list all mean the table restarted: the list is
     * rebuilt from row 0 under the given attribution.
     */
    int capture(int from, DocumentId doc, String actionId) {
        ResultsTable rt = ResultsTable.getResultsTable();
        int n = rt == null ? 0 : rt.size();
        int start;
        boolean rebuilt;
        synchronized (this) {
            rebuilt = from <= 0 || rows.size() > from || n < rows.size();
            if (rebuilt) rows.clear();
            start = rebuilt ? 0 : Math.min(from, rows.size());
        }
        List<MeasurementRow> fresh = new ArrayList<>();
        if (rt != null && n > start) {
            String[] headings = rt.getHeadings();
            for (int r = start; r < n; r++) {
                Map<String, Double> values = new LinkedHashMap<>();
                for (String h : headings) {
                    if (h == null || h.equals("Label")) continue;
                    int col = rt.getColumnIndex(h);
                    if (col == ResultsTable.COLUMN_NOT_FOUND) continue;
                    values.put(h, rt.getValueAsDouble(col, r));
                }
                fresh.add(new MeasurementRow(r, rt.getLabel(r), values, doc, actionId));
            }
        }
        int total;
        synchronized (this) {
            rows.addAll(fresh);
            total = rows.size();
            lastSeen = n;
        }
        if (rebuilt || !fresh.isEmpty()) events.emit(seq -> new EngineEvent.ResultsChanged(seq, total));
        return fresh.size();
    }

    /** Note the current table size as accounted for (after typed actions); a shrunken table rebuilds the list. */
    void markSeen() {
        int n = tableSize();
        boolean shrunk;
        synchronized (this) {
            shrunk = n < rows.size();
            lastSeen = n;
        }
        if (shrunk) capture(0, null, null);
    }

    /** After a native command: capture rows it added to the system table (or rebuild after a clear). */
    int captureNative(DocumentId doc, String actionId) {
        int from;
        synchronized (this) {
            from = tableSize() < lastSeen ? 0 : lastSeen;
        }
        if (from > 0 && tableSize() <= from) return 0; // nothing new
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
