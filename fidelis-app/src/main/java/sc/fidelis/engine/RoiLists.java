package sc.fidelis.engine;

import java.util.ArrayList;
import java.util.List;

import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import sc.fidelis.engine.api.RoiGeometry;

/**
 * Engine-owned ROI list per document, mirrored into the IJ1 ROI Manager singleton around actions that
 * use it (docs D04 section 4). The singleton is created once, hidden and kept registered so IJ1 plugins
 * that look for the "ROI Manager" frame (Analyze Particles with add) find it.
 */
final class RoiLists {
    private RoiManager manager;

    /** The hidden ROI Manager singleton (created on first use, on the EDT). */
    synchronized RoiManager manager() {
        // Always resolve the live singleton: IJ1 nulls it on close (a macro's roiManager("close"), a user) and
        // Analyze Particles creates a new one through "ROI Manager..."; a cached reference would then be a
        // detached frame that no plugin writes to.
        RoiManager existing = RoiManager.getRawInstance();
        if (existing != null) {
            manager = existing;
        } else {
            manager = Edt.call(() -> {
                RoiManager rm = new RoiManager();
                rm.setVisible(false);
                return rm;
            });
        }
        return manager;
    }

    /** Replace the singleton content with the document list (before an action that uses the manager). */
    void load(Doc d) {
        RoiManager rm = manager();
        rm.reset();
        synchronized (d.roiList) {
            for (Roi r : d.roiList) rm.addRoi((Roi) r.clone());
        }
    }

    /**
     * True when the singleton holds exactly the document's ROI objects, in order: the state every {@link #syncBack}
     * leaves (the document list then <em>is</em> the manager's content), until either side changes.
     */
    boolean mirrors(Doc d) {
        RoiManager rm = RoiManager.getRawInstance();
        if (rm == null) return false;
        Roi[] arr = rm.getRoisAsArray();
        synchronized (d.roiList) {
            if (arr.length != d.roiList.size()) return false;
            for (int i = 0; i < arr.length; i++) if (arr[i] != d.roiList.get(i)) return false;
            return true;
        }
    }

    /**
     * Make the singleton mirror the document before a macro: nothing when it already does (a selection the previous
     * macro left stands, as in Fiji), else load the list and take the manager's objects back so the two are one from
     * now on (IJ1 names unnamed entries as it adds them; the document keeps those names).
     */
    void ensureMirrors(Doc d) {
        if (mirrors(d)) return;
        load(d);
        syncBack(d);
    }

    /** Read the singleton back into the document list (after the action); returns the new size. */
    int syncBack(Doc d) {
        RoiManager rm = manager();
        Roi[] arr = rm.getRoisAsArray();
        synchronized (d.roiList) {
            d.roiList.clear();
            for (Roi r : arr) d.roiList.add(r);
            return d.roiList.size();
        }
    }

    static List<RoiGeometry> geometries(Doc d) {
        List<RoiGeometry> out = new ArrayList<>();
        synchronized (d.roiList) {
            for (Roi r : d.roiList) out.add(RoiCodec.toGeometry(r));
        }
        return out;
    }
}
