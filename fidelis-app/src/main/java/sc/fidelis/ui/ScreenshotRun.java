package sc.fidelis.ui;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/**
 * The {@code --screenshot <path>} debug mode: three seconds after start it opens the fixture and a
 * synthetic 512 x 512 16-bit image, does a little real work so every panel has content, applies the
 * default layout, writes a PNG of the whole frame and exits. {@code Container.printAll} omits
 * heavyweight components (EXP-01), so each visible hosted canvas is painted in at its location.
 */
final class ScreenshotRun {
    private ScreenshotRun() {}

    static void schedule(Workspace w, Path out, Runnable exit) {
        Timer t = new Timer(3000, e -> new Thread(() -> run(w, out, exit), "fidelis-screenshot").start());
        t.setRepeats(false);
        t.start();
    }

    private static void run(Workspace w, Path out, Runnable exit) {
        try {
            Path repo = Path.of(System.getProperty("fidelis.repo.dir", "."));
            var engine = w.engine();
            UiContext ctx = w.context();
            long deadline = System.currentTimeMillis() + 20_000;
            while (engine.state() != sc.fidelis.engine.api.Enums.EngineState.READY && System.currentTimeMillis() < deadline)
                Thread.sleep(100);
            engine.open(repo.resolve("fixtures").resolve("two-squares.png")).get();
            Map<String, Object> ni = new LinkedHashMap<>();
            ni.put("title", "synthetic-16bit");
            ni.put("type", "16-bit");
            ni.put("fill", "Ramp");
            ni.put("width", 512);
            ni.put("height", 512);
            ni.put("slices", 1);
            engine.submit(ActionRequest.session(Origin.MENU, Operations.NEW_IMAGE, ni, null)).get();
            DocumentId syn = engine.focused().orElseThrow();
            TargetScope t = TargetScope.of(syn);
            Map<String, Object> blur = new LinkedHashMap<>();
            blur.put("sigma", 3.0);
            blur.put("scaled", false);
            blur.put("stack", false);
            engine.submit(ActionRequest.command(Origin.MENU, "ij1:Process>Filters>Gaussian Blur...", blur, t)).get();
            RoiGeometry rect = new RoiGeometry("RECTANGLE", java.util.List.of(), java.util.List.of(),
                    new double[] {120, 140, 240, 150}, "region-1", null, null);
            Map<String, Object> roi = new LinkedHashMap<>();
            roi.put("roi", rect);
            engine.submit(ActionRequest.direct(Origin.CANVAS, Operations.SET_ROI, roi, t)).get();
            engine.submit(ActionRequest.direct(Origin.PANEL, Operations.ROI_LIST_ADD, Map.of(), t)).get();
            engine.submit(ActionRequest.command(Origin.MENU, "ij1:Analyze>Measure", Map.of(), t)).get();
            SwingUtilities.invokeAndWait(() -> {
                w.docks().showHistory();
                ctx.runCommand("ij1:Process>Filters>Median...", Origin.MENU);
            });
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> {
                w.docks().applyDefaultDividers();
                w.frame().toFront();
            });
            Thread.sleep(1800);
            BufferedImage[] img = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> img[0] = capture(w));
            Files.createDirectories(out.toAbsolutePath().getParent());
            ImageIO.write(img[0], "png", out.toFile());
            System.out.println("[fidelis] screenshot written: " + out.toAbsolutePath());
            try {
                Path robotOut = Path.of(out.toString().replaceFirst("\\.png$", "") + "-robot.png");
                BufferedImage shot = robotCapture(w);
                ImageIO.write(shot, "png", robotOut.toFile());
                System.out.println("[fidelis] robot capture written: " + robotOut.toAbsolutePath());
            } catch (Exception | Error ex) {
                System.out.println("[fidelis] robot capture unavailable: " + ex);
            }
        } catch (Throwable ex) {
            System.err.println("[fidelis] screenshot failed: " + ex);
            ex.printStackTrace();
        } finally {
            exit.run();
        }
    }

    /** The whole frame, with every showing hosted canvas composited in (EXP-01 section 5.2). */
    static BufferedImage capture(Workspace w) {
        JRootPane root = w.frame().getRootPane();
        BufferedImage img = new BufferedImage(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        root.printAll(g);
        for (DocumentView v : w.documents().allViews()) {
            if (!v.isShowing() || v.canvas().getWidth() <= 0) continue;
            Point p = SwingUtilities.convertPoint(v.canvas(), 0, 0, root);
            Graphics2D cg = (Graphics2D) g.create(p.x, p.y, v.canvas().getWidth(), v.canvas().getHeight());
            v.canvas().paint(cg);
            cg.dispose();
        }
        g.dispose();
        return img;
    }

    static BufferedImage robotCapture(Workspace w) throws Exception {
        Rectangle r = new Rectangle(w.frame().getLocationOnScreen(), w.frame().getSize());
        Robot robot = new Robot();
        return robot.createScreenCapture(r);
    }
}
