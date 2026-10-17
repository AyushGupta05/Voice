package sc.fidelis.engine;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.StackWindow;
import ij.measure.Calibration;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.HostCanvas;

/**
 * Document registry (E1). Every registered image owns a hidden {@link ImageWindow} hosting a
 * {@link HostCanvas} for its whole life (EXP-01): images that IJ1 creates behind our back are adopted
 * by re-hosting them with {@code new ImageWindow(imp, hostCanvas)} (IJ1 closes the previous window).
 */
final class Registry {
    private final Events events;
    private final AtomicInteger docSeq = new AtomicInteger();
    private final Map<DocumentId, Doc> byId = new LinkedHashMap<>();
    private final Map<ImagePlus, Doc> byImp = new IdentityHashMap<>();
    private final Set<ImagePlus> ignored = Collections.newSetFromMap(new WeakHashMap<>());

    Registry(Events events) {
        this.events = events;
    }

    /** Result of {@link #reconcile()}: documents adopted from IJ1 and documents that disappeared. */
    record Change(List<Doc> added, List<Doc> removed) {
        boolean isEmpty() { return added.isEmpty() && removed.isEmpty(); }
    }

    synchronized Doc get(DocumentId id) {
        return id == null ? null : byId.get(id);
    }

    synchronized Doc get(String id) {
        return id == null ? null : byId.get(new DocumentId(id));
    }

    synchronized Doc byImp(ImagePlus imp) {
        return byImp.get(imp);
    }

    synchronized List<Doc> all() {
        return new ArrayList<>(byId.values());
    }

    /** Register an image opened or created by the engine; creates its hidden host window on the EDT. */
    Doc register(ImagePlus imp, Path source) {
        String sha = source == null ? null : sha256(source);
        Doc d = new Doc(new DocumentId("doc-" + docSeq.incrementAndGet()), imp, source, sha);
        d.win = Edt.call(() -> host(imp));
        synchronized (this) {
            byId.put(d.id, d);
            byImp.put(imp, d);
        }
        return d;
    }

    private static ImageWindow host(ImagePlus imp) {
        HostCanvas canvas = new HostCanvas(imp);
        ImageWindow.setNextLocation(new Point(-30000, -30000));
        ImageWindow win = imp.getStackSize() > 1 ? new StackWindow(imp, canvas) : new ImageWindow(imp, canvas);
        win.setLayout(null); // EXP-01..05: with canEnlarge()==null, a null layout stops unzoom/pack degenerating the hosted canvas
        win.setVisible(false);
        return win;
    }

    synchronized void remove(Doc d) {
        byId.remove(d.id);
        byImp.remove(d.imp);
    }

    /**
     * Compare the registry with IJ1's window list: adopt new images (new documents), drop documents whose
     * image is gone. Worker thread only (it waits for the EDT).
     */
    Change reconcile() {
        int[] ids = WindowManager.getIDList();
        List<ImagePlus> live = new ArrayList<>();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) live.add(imp);
            }
        }
        Set<ImagePlus> liveSet = Collections.newSetFromMap(new IdentityHashMap<>());
        liveSet.addAll(live);
        List<Doc> removed = new ArrayList<>();
        List<Doc> added = new ArrayList<>();
        repairOverwrittenWindows();
        synchronized (this) {
            for (Doc d : new ArrayList<>(byId.values())) {
                if (!liveSet.contains(d.imp)) {
                    byId.remove(d.id);
                    byImp.remove(d.imp);
                    removed.add(d);
                }
            }
        }
        for (ImagePlus imp : live) {
            if (byImp(imp) != null || ignored.contains(imp)) continue;
            if (imp.getProperty(HostCanvas.TRANSIENT_PROPERTY) != null) {
                ignored.add(imp); // a host's helper image (before/after copy), never a document
                continue;
            }
            ImageWindow w = imp.getWindow();
            Class<?> c = w == null ? null : w.getClass();
            if (c != ImageWindow.class && c != StackWindow.class) {
                ignored.add(imp); // plots, histograms and other special windows are not documents
                continue;
            }
            // ImagePlus.show() registers the window in WindowManager inside the ImageWindow constructor, before the
            // window is shown and before show() stores it in the image: re-hosting it now would let show() overwrite
            // the host afterwards. It is adopted once shown (ImageJ then notifies imageOpened, which reconciles again).
            if (!w.isShowing()) continue;
            Doc d = new Doc(new DocumentId("doc-" + docSeq.incrementAndGet()), imp, null, null);
            try {
                d.win = Edt.call(() -> host(imp));
            } catch (RuntimeException e) {
                System.err.println("[fidelis-engine] could not host " + imp.getTitle() + ": " + e);
                ignored.add(imp);
                continue;
            }
            synchronized (this) {
                byId.put(d.id, d);
                byImp.put(imp, d);
            }
            added.add(d);
        }
        Change ch = new Change(added, removed);
        if (!ch.isEmpty()) events.emit(EngineEvent.DocumentsChanged::new);
        return ch;
    }

    /**
     * Point a document's image back at its host window when an {@code ImagePlus.show()} that was still running on another
     * thread when the image was adopted stored its own (meanwhile closed) window in the image afterwards. Without this
     * {@code imp.close()} closes that stale window and the host stays registered in WindowManager for ever.
     */
    private void repairOverwrittenWindows() {
        for (Doc d : all()) {
            ImageWindow host = d.win;
            ImageWindow now = d.imp.getWindow();
            if (host == null || now == null || now == host || !now.isClosed() || !host.isDisplayable()) continue;
            System.err.println("[fidelis-engine] " + d.id + ": ImageJ replaced the host window of '" + d.imp.getTitle()
                    + "' after adoption; restored");
            Edt.run(() -> {
                d.imp.setWindow(host);
                if (now.isDisplayable()) {
                    now.setVisible(false);
                    now.dispose();
                }
            });
        }
    }

    DocumentInfo info(Doc d) {
        ImagePlus imp = d.imp;
        Calibration cal = imp.getCalibration();
        String unit = cal == null ? "pixel" : cal.getUnit();
        boolean virtual = imp.getStackSize() > 0 && imp.getStack() != null && imp.getStack().isVirtual();
        return new DocumentInfo(d.id, imp.getTitle(), imp.getID(), imp.getWidth(), imp.getHeight(),
                imp.getNChannels(), imp.getNSlices(), imp.getNFrames(), typeName(imp.getType()), imp.getBitDepth(),
                unit == null ? "pixel" : unit, cal == null ? 1.0 : cal.pixelWidth, cal == null ? 1.0 : cal.pixelHeight,
                d.sourcePath, d.sourceSha256, imp.changes, virtual);
    }

    static String typeName(int type) {
        return switch (type) {
            case ImagePlus.GRAY8 -> "GRAY8";
            case ImagePlus.GRAY16 -> "GRAY16";
            case ImagePlus.GRAY32 -> "GRAY32";
            case ImagePlus.COLOR_256 -> "COLOR_256";
            case ImagePlus.COLOR_RGB -> "COLOR_RGB";
            default -> "UNKNOWN";
        };
    }

    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Images present in IJ1's window list right now (IDs), for before/after diffs. */
    static Set<Integer> liveIds() {
        Set<Integer> s = new HashSet<>();
        int[] ids = WindowManager.getIDList();
        if (ids != null) for (int id : ids) s.add(id);
        return s;
    }
}
