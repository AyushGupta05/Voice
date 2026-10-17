package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/** The focused document's ROI list (docs D12 UI-09): select, add the active selection, delete, rename. */
public final class RoiPanel extends JPanel {
    private final UiContext ctx;
    private final JLabel header = new JLabel();
    private final DefaultTableModel model = new DefaultTableModel(new String[] {"#", "Name", "Type", "x, y, w, h"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JButton add = new JButton("Add selection");
    private final JButton delete = new JButton("Delete");
    private final JButton rename = new JButton("Rename…");
    private boolean programmatic;

    RoiPanel(UiContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        add(header, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.getColumnModel().getColumn(0).setMaxWidth(36);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || programmatic) return;
            int i = table.getSelectedRow();
            DocumentId id = ctx.focusedId();
            if (i >= 0 && id != null)
                ctx.submit(ActionRequest.direct(Origin.PANEL, Operations.ROI_LIST_SELECT, Map.of("index", i), TargetScope.of(id)), "Select ROI " + i);
            updateButtons();
        });
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.panelBorder()));
        add(sp, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        add.setToolTipText("Add the active selection of this image to its ROI list (ROI Manager: Add [t])");
        add.addActionListener(e -> {
            DocumentId id = ctx.focusedId();
            if (id != null) ctx.submit(ActionRequest.direct(Origin.PANEL, Operations.ROI_LIST_ADD, Map.of(), TargetScope.of(id)), "Add ROI");
        });
        delete.addActionListener(e -> {
            int i = table.getSelectedRow();
            DocumentId id = ctx.focusedId();
            if (i >= 0 && id != null)
                ctx.submit(ActionRequest.direct(Origin.PANEL, Operations.ROI_LIST_DELETE, Map.of("index", i), TargetScope.of(id)), "Delete ROI " + i);
        });
        rename.addActionListener(e -> {
            int i = table.getSelectedRow();
            DocumentId id = ctx.focusedId();
            if (i < 0 || id == null) return;
            String current = String.valueOf(model.getValueAt(i, 1));
            String name = (String) JOptionPane.showInputDialog(ctx.frame, "ROI name:", "Rename ROI", JOptionPane.PLAIN_MESSAGE, null, null, current);
            if (name != null && !name.isBlank())
                ctx.submit(ActionRequest.direct(Origin.PANEL, Operations.ROI_LIST_RENAME, Map.of("index", i, "name", name.trim()), TargetScope.of(id)), "Rename ROI " + i);
        });
        buttons.add(add);
        buttons.add(delete);
        buttons.add(rename);
        add(buttons, BorderLayout.SOUTH);
        refresh();
    }

    void refresh() {
        Optional<DocumentInfo> info = ctx.focusedInfo();
        int keep = table.getSelectedRow();
        programmatic = true;
        try {
            model.setRowCount(0);
            if (info.isEmpty()) {
                header.setText("ROI list — no image");
            } else {
                header.setText("ROI list — " + info.get().title());
                List<RoiGeometry> rois = ctx.engine.rois(info.get().id());
                for (int i = 0; i < rois.size(); i++) {
                    RoiGeometry g = rois.get(i);
                    model.addRow(new Object[] {i + 1, g.name() == null ? "" : g.name(), g.type().toLowerCase(Locale.ROOT), bounds(g)});
                }
                if (keep >= 0 && keep < model.getRowCount()) table.setRowSelectionInterval(keep, keep);
            }
        } finally {
            programmatic = false;
        }
        updateButtons();
    }

    private static String bounds(RoiGeometry g) {
        if (g.bounds() != null) {
            double[] b = g.bounds();
            return fmt(b[0]) + ", " + fmt(b[1]) + ", " + fmt(b[2]) + ", " + fmt(b[3]);
        }
        if (g.xs().isEmpty()) return "";
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < g.xs().size(); i++) {
            minX = Math.min(minX, g.xs().get(i));
            maxX = Math.max(maxX, g.xs().get(i));
            minY = Math.min(minY, g.ys().get(i));
            maxY = Math.max(maxY, g.ys().get(i));
        }
        return fmt(minX) + ", " + fmt(minY) + ", " + fmt(maxX - minX) + ", " + fmt(maxY - minY);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    private void updateButtons() {
        boolean doc = ctx.focusedId() != null;
        add.setEnabled(doc);
        delete.setEnabled(doc && table.getSelectedRow() >= 0);
        rename.setEnabled(doc && table.getSelectedRow() >= 0);
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.DocumentFocusChanged || e instanceof EngineEvent.DocumentsChanged
                || e instanceof EngineEvent.StateDigestEvent) refresh();
    }

    int rowCount() {
        return model.getRowCount();
    }
}
