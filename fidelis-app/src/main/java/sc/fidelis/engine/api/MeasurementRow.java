package sc.fidelis.engine.api;

import java.util.List;
import java.util.Map;

/**
 * One row of measurement results at full double precision (FR-MEAS-003). Values come from the IJ1
 * {@code ResultsTable} via {@code getValueAsDouble}; rounding is presentation only.
 *
 * @param rowIndex   0-based index in the IJ1 system Results table at capture time
 * @param label      the IJ1 "Label" column (may be null)
 * @param values     column name → value, in table column order
 * @param document   document measured, when known
 * @param actionId   history action that produced the row, when known
 */
public record MeasurementRow(int rowIndex, String label, Map<String, Double> values, DocumentId document, String actionId) {

    /** Column metadata as reported by the IJ1 table. */
    public record Columns(List<String> headings, int decimalPlaces) {}
}
