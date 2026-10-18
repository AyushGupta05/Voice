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
            for (Roi r : d.roiList) {
                int before = rm.getCount();
                rm.addRoi((Roi) r.clone());
                if (rm.getCount() != before + 1) continue;
                // IJ1's add binds an ROI without a position to the current plane of a stack and may apply the manager's
                // default stroke and colour: the document's ROI is the truth, so the manager's copy keeps the document's
                // position and style (the stroke object itself, so dashes and width scaling survive). The entry's label
                // stays IJ1's own (an unnamed ROI gets the slice-prefixed label of the plane shown when it is first
                // mirrored, as it would when added in Fiji); only the position decides the plane the engine measures on.
                Roi added = rm.getRoi(before);
                if (added == null) continue;
                if (r.getPosition() == 0 && !r.hasHyperStackPosition()) added.setPosition(0);
                if (added.getScaleStrokeWidth() != r.getScaleStrokeWidth()) {
                    // the float overload marks the width as scalable, the double one as fixed (IJ1's two setters)
                    if (r.getScaleStrokeWidth()) added.setStrokeWidth(r.getStrokeWidth());
                    else added.setUnscalableStrokeWidth(r.getStrokeWidth());
                }
                if (!java.util.Objects.equals(added.getStroke(), r.getStroke())) added.setStroke(r.getStroke());
                if (!java.util.Objects.equals(added.getStrokeColor(), r.getStrokeColor())) added.setStrokeColor(r.getStrokeColor());
            }
        }
    }

    /**
     * True when the singleton holds exactly the document's ROI objects, in order: the state every {@link #syncBack}
     * leaves (the document list then <em>is</em> the manager's content), until either side changes.
     */
    boolean mirrors(Doc d) {
        return mirrors(d, RoiManager.getRawInstance());
    }

    /** {@link #mirrors(Doc)} against a given manager (one a macro closed is no longer the singleton but still holds its list). */
    boolean mirrors(Doc d, RoiManager rm) {
        if (rm == null) return false;
        Roi[] arr = rm.getRoisAsArray();
        synchronized (d.roiList) {
            if (arr.length != d.roiList.size()) return false;
            List<String> synced = namesAtSync.get(d);
            if (synced == null || synced.size() != arr.length) return false;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] != d.roiList.get(i)) return false;
                // the manager keeps its labels apart from the ROI objects: a workspace rename since the last sync
                // (ROI_LIST_RENAME changes the shared object's name) must reach the label too, which only a reload does.
                // IJ1's own labels may differ from the names ("1-cellA" for an overlay entry named "cellA"), so the
                // comparison is with the name the object had when it was last synchronised, not with the label.
                if (!java.util.Objects.equals(synced.get(i), arr[i].getName())) return false;
            }
            return true;
        }
    }

    /** The name each ROI of a document had when its list was last taken from the manager (see {@link #mirrors}). */
    private final java.util.Map<Doc, List<String>> namesAtSync = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Managers created by IJ1 while an action ran (a macro's roiManager("Add") after it closed the previous one), strongly held. */
    private final List<RoiManager> createdDuringAction = new ArrayList<>();

    /** Forget the managers noted for the previous action. */
    synchronized void beginAction() {
        createdDuringAction.clear();
    }

    /**
     * Called by the command observer when IJ1 is about to run "ROI Manager..." with no manager registered: the hidden
     * singleton is created here first (IJ1's plugin then finds and keeps it), so the manager a macro creates and later
     * closes is known and strongly held until the action's sync, whatever the window inventory still lists.
     */
    synchronized RoiManager noteManagerCommand() {
        RoiManager rm = manager();
        if (!createdDuringAction.contains(rm)) createdDuringAction.add(rm);
        return rm;
    }

    /** Note the manager registered right now (the guard's poll every 100 ms): one a command created directly. */
    synchronized void noteManagerSeen() {
        RoiManager rm = RoiManager.getRawInstance();
        if (rm != null && !createdDuringAction.contains(rm)) createdDuringAction.add(rm);
    }

    /** The newest manager noted during the action, or null. */
    synchronized RoiManager latestCreatedDuringAction() {
        return createdDuringAction.isEmpty() ? null : createdDuringAction.get(createdDuringAction.size() - 1);
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
        return syncBack(d, manager());
    }

    /** {@link #syncBack(Doc)} from a given manager (one a macro closed is no longer the singleton but still holds its list). */
    int syncBack(Doc d, RoiManager rm) {
        Roi[] arr = rm.getRoisAsArray();
        List<String> names = new ArrayList<>();
        synchronized (d.roiList) {
            d.roiList.clear();
            for (Roi r : arr) {
                d.roiList.add(r);
                names.add(r.getName());
            }
            namesAtSync.put(d, names);
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
