package sc.fidelis.testkit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;

/**
 * A deliberately thin {@link Engine} over plain {@code IJ.run}, used ONLY to prove the parity-test machinery
 * (EngineParityTest) end to end while the product engine is not available. It is not the product engine:
 * no validation, no history, no events, no postcondition checks. Enabled with the environment variable
 * {@code FIDELIS_PARITY_SELFCHECK=1}. Unsupported members throw {@link UnsupportedOperationException}.
 */
public final class HarnessSelfCheckEngine implements Engine {
    private final Map<DocumentId, ImagePlus> docs = new LinkedHashMap<>();
    private int ops;

    @Override public EngineState state() { return EngineState.READY; }
    @Override public void addListener(EngineListener listener) {}
    @Override public void removeListener(EngineListener listener) {}
    @Override public void quit(boolean discardUnsaved) { throw new UnsupportedOperationException(); }
    @Override public void cancel(String operationId) { throw new UnsupportedOperationException(); }

    @Override
    public CompletableFuture<ActionResult> submit(ActionRequest request) {
        long t0 = System.nanoTime();
        List<DocumentId> added = new ArrayList<>();
        int rowsBefore = ResultsTable.getResultsTable().size();
        List<Integer> idsBefore = ids();
        try {
            switch (request.kind()) {
                case COMMAND -> {
                    ImagePlus imp = docs.get(request.target().document());
                    String label = request.commandId().substring(request.commandId().lastIndexOf('>') + 1);
                    IJ.run(imp, label, options(request.params()));
                }
                case DIRECT -> {
                    ImagePlus imp = docs.get(request.target().document());
                    if (!Operations.SET_AUTO_THRESHOLD.equals(request.operation())) {
                        throw new UnsupportedOperationException(request.operation());
                    }
                    boolean dark = Boolean.TRUE.equals(request.params().get("dark"));
                    IJ.setAutoThreshold(imp, request.params().get("method") + (dark ? " dark" : ""));
                }
                case SESSION -> {
                    if (Operations.OPEN.equals(request.operation())) {
                        ImagePlus imp = IJ.openImage(String.valueOf(request.params().get("path")));
                        imp.show();
                        DocumentId id = new DocumentId("doc-" + (docs.size() + 1));
                        docs.put(id, imp);
                        added.add(id);
                    } else if (Operations.SAVE_AS.equals(request.operation())) {
                        ImagePlus imp = docs.get(request.target().document());
                        IJ.saveAs(imp, String.valueOf(request.params().get("format")),
                                String.valueOf(request.params().get("path")));
                    } else {
                        throw new UnsupportedOperationException(request.operation());
                    }
                }
                default -> throw new UnsupportedOperationException(request.kind().toString());
            }
            for (int id : ids()) {
                if (idsBefore.contains(id) || docs.values().stream().anyMatch(i -> i.getID() == id)) continue;
                DocumentId doc = new DocumentId("doc-" + (docs.size() + 1));
                docs.put(doc, WindowManager.getImage(id));
                added.add(doc);
            }
            StateDigest digest = new StateDigest(added, List.of(), List.of(),
                    ResultsTable.getResultsTable().size() - rowsBefore, Map.of(), List.of(), List.of(), List.of());
            return CompletableFuture.completedFuture(new ActionResult("op-" + (++ops), OperationState.COMPLETED, null,
                    digest, null, null, (System.nanoTime() - t0) / 1_000_000));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(new ActionResult("op-" + (++ops), OperationState.FAILED,
                    EngineError.of(ErrorCode.EXECUTION_FAILED, e.toString()), null, null, null, 0));
        }
    }

    private static List<Integer> ids() {
        int[] ids = WindowManager.getIDList();
        List<Integer> out = new ArrayList<>();
        if (ids != null) for (int id : ids) out.add(id);
        return out;
    }

    private static String options(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() instanceof Boolean b) {
                if (b) sb.append(e.getKey()).append(' ');
            } else {
                Object v = e.getValue();
                String text = v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                        ? String.valueOf(n.longValue()) : String.valueOf(v);
                sb.append(e.getKey()).append('=').append(text.contains(" ") ? "[" + text + "]" : text).append(' ');
            }
        }
        return sb.toString().trim();
    }

    @Override public List<DocumentInfo> documents() {
        List<DocumentInfo> out = new ArrayList<>();
        docs.forEach((id, imp) -> out.add(info(id, imp)));
        return out;
    }

    private static String typeName(ImagePlus imp) {
        return switch (imp.getType()) {
            case ImagePlus.GRAY8 -> "GRAY8";
            case ImagePlus.GRAY16 -> "GRAY16";
            case ImagePlus.GRAY32 -> "GRAY32";
            case ImagePlus.COLOR_256 -> "COLOR_256";
            default -> "COLOR_RGB";
        };
    }

    private static DocumentInfo info(DocumentId id, ImagePlus imp) {
        return new DocumentInfo(id, imp.getTitle(), imp.getID(), imp.getWidth(), imp.getHeight(), imp.getNChannels(),
                imp.getNSlices(), imp.getNFrames(), typeName(imp), imp.getBitDepth(),
                imp.getCalibration().getUnit(), imp.getCalibration().pixelWidth, imp.getCalibration().pixelHeight,
                null, null, imp.changes, false);
    }

    @Override public Optional<DocumentInfo> document(DocumentId id) {
        return Optional.ofNullable(docs.get(id)).map(i -> info(id, i));
    }

    @Override
    public CompletableFuture<ActionResult> open(Path file) {
        return submit(ActionRequest.session(sc.fidelis.engine.api.Enums.Origin.PANEL, Operations.OPEN,
                Map.of("path", file.toString()), sc.fidelis.engine.api.TargetScope.of(new DocumentId("none"))));
    }

    @Override public Optional<DocumentId> focused() { throw new UnsupportedOperationException(); }
    @Override public void focus(DocumentId id) { throw new UnsupportedOperationException(); }
    @Override public ImagePlus imagePlus(DocumentId id) { return docs.get(id); }
    @Override public ImageWindow hostWindow(DocumentId id) { throw new UnsupportedOperationException(); }

    @Override
    public List<RoiGeometry> rois(DocumentId id) {
        List<RoiGeometry> out = new ArrayList<>();
        RoiManager rm = RoiManager.getInstance2();
        if (rm == null) return out;
        for (Roi roi : rm.getRoisAsArray()) {
            FloatPolygon fp = roi.getFloatPolygon();
            List<Float> xs = new ArrayList<>();
            List<Float> ys = new ArrayList<>();
            for (int i = 0; i < fp.npoints; i++) {
                xs.add(fp.xpoints[i]);
                ys.add(fp.ypoints[i]);
            }
            out.add(new RoiGeometry(roi.getTypeAsString().toUpperCase(), xs, ys, null, roi.getName(), null, null));
        }
        return out;
    }

    @Override public Optional<RoiGeometry> activeRoi(DocumentId id) { throw new UnsupportedOperationException(); }

    @Override
    public List<MeasurementRow> results() {
        ResultsTable rt = Analyzer.getResultsTable();
        List<MeasurementRow> rows = new ArrayList<>();
        for (int r = 0; r < rt.size(); r++) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (String h : rt.getHeadings()) {
                if (!h.equals("Label")) values.put(h, rt.getValueAsDouble(rt.getColumnIndex(h), r));
            }
            rows.add(new MeasurementRow(r, rt.getLabel(r), values, null, null));
        }
        return rows;
    }

    @Override
    public MeasurementRow.Columns resultColumns() {
        List<String> headings = new ArrayList<>(Arrays.asList(Analyzer.getResultsTable().getHeadings()));
        headings.remove("Label");
        return new MeasurementRow.Columns(headings, Analyzer.getPrecision());
    }

    @Override public List<CommandSchema.Summary> catalogue() { throw new UnsupportedOperationException(); }
    @Override public Optional<CommandSchema> schema(String commandId) { throw new UnsupportedOperationException(); }
    @Override public List<CommandSchema.Summary> search(String query, int limit) { throw new UnsupportedOperationException(); }
    @Override public List<HistoryRecord> history() { throw new UnsupportedOperationException(); }
    @Override public String exportMacro() { throw new UnsupportedOperationException(); }

    @Override
    public Map<String, String> globals() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("blackBackground", String.valueOf(Prefs.blackBackground));
        g.put("measurements", String.valueOf(Analyzer.getMeasurements()));
        g.put("precision", String.valueOf(Analyzer.getPrecision()));
        return g;
    }

    @Override public long[] undoBudget() { throw new UnsupportedOperationException(); }
}
