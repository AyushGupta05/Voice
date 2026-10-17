package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import net.imagej.ImageJ;
import sc.fidelis.Bootstrap;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.EngineState;

/**
 * The Fidelis workspace (docs D12 W-1, hybrid B): one FlatLaf frame with the Fiji menu bar, the
 * command bar, the tool palette, tabbed documents hosting the real IJ1 canvases, right and bottom
 * docks and a status bar. The window appears at once and its actions are enabled when the engine
 * reports READY. All behaviour goes through the {@link Engine} interface.
 */
public final class Workspace {
    private final Engine engine;
    private final JFrame frame;
    private final UiContext ctx;
    private final MenuBuilder menus;
    private final ToolPalette tools;
    private final CommandBar commandBar;
    private final DocumentArea documents;
    private final ParametersPanel parameters;
    private final RoiPanel rois;
    private final ResultsPanel results;
    private final HistoryPanel history;
    private final LogPanel log;
    private final ActivityPanel activity;
    private final Banners banners;
    private final StatusBar status;
    private final Docks docks;
    private final LegacyWindows legacy;
    private final Runnable quitAction;

    /** Entry point called by {@code Bootstrap} on the EDT. */
    public static void launch(ImageJ ij, String[] args) {
        Options o = Options.parse(args);
        Theme.install();
        System.out.println("[fidelis] display scale (default transform) = "
                + java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                        .getDefaultConfiguration().getDefaultTransform().getScaleX());
        Runnable quit = () -> new Thread(() -> Bootstrap.exit(ij, 0), "fidelis-quit").start();
        Engine engine = EngineProvider.get(quit);
        Workspace w = create(engine, quit, o.size());
        w.frame.setVisible(true);
        for (String p : o.open()) w.ctx.openPath(Path.of(p));
        if (o.screenshot() != null) ScreenshotRun.schedule(w, o.screenshot(), () -> Bootstrap.exit(ij, 0));
    }

    /** Build a workspace around {@code engine} without showing it (tests show it themselves). */
    public static Workspace create(Engine engine, Runnable quitAction, Dimension size) {
        return new Workspace(engine, quitAction, size == null ? new Dimension(1440, 900) : size);
    }

    private Workspace(Engine engine, Runnable quitAction, Dimension size) {
        this.engine = engine;
        this.quitAction = quitAction;
        frame = new JFrame("Fidelis");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        ctx = new UiContext(engine, frame);

        status = new StatusBar(ctx);
        ctx.setStatus(status);
        docks = new Docks();
        ctx.setDocks(docks);
        banners = new Banners(ctx);
        ctx.setBanners(banners);
        documents = new DocumentArea(ctx);
        ctx.setDocuments(documents);
        parameters = new ParametersPanel(ctx);
        ctx.setParameters(parameters);
        rois = new RoiPanel(ctx);
        results = new ResultsPanel(ctx);
        history = new HistoryPanel(ctx);
        log = new LogPanel(ctx);
        activity = new ActivityPanel(ctx);
        commandBar = new CommandBar(ctx);
        ctx.setCommandBar(commandBar);
        legacy = new LegacyWindows(ctx);
        ctx.setLegacyWindows(legacy);
        tools = new ToolPalette(ctx);
        menus = new MenuBuilder(ctx, this);

        frame.setJMenuBar(menus.build());
        JPanel north = new JPanel(new BorderLayout());
        north.add(commandBar, BorderLayout.NORTH);
        north.add(banners, BorderLayout.CENTER);
        north.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.panelBorder()));
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(north, BorderLayout.NORTH);
        frame.getContentPane().add(tools, BorderLayout.WEST);
        frame.getContentPane().add(docks.build(documents, parameters, rois, results, history, log, activity), BorderLayout.CENTER);
        frame.getContentPane().add(status, BorderLayout.SOUTH);

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(size.width, screen.width), Math.min(size.height, screen.height));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestQuit();
            }
        });

        engine.addListener(this::onEvent);
        applyEngineState(engine.state(), null);
        documents.sync();
        history.reload();
        // The default divider positions need a laid-out frame
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean first = true;

            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                if (first) {
                    first = false;
                    SwingUtilities.invokeLater(docks::applyDefaultDividers);
                }
            }
        });
    }

    // ---- events -------------------------------------------------------------------------------

    private void onEvent(EngineEvent e) {
        if (e instanceof EngineEvent.EngineStateChanged s) applyEngineState(s.state(), s.reason());
        banners.handle(e);
        documents.handle(e);
        parameters.handle(e);
        rois.handle(e);
        results.handle(e);
        history.handle(e);
        log.handle(e);
        activity.handle(e);
        legacy.handle(e);
        if (e instanceof EngineEvent.HistoryAppended || e instanceof EngineEvent.StateDigestEvent
                || e instanceof EngineEvent.DocumentsChanged) {
            long[] b = engine.undoBudget();
            status.setUndoBudget(b[0], b[1]);
        }
    }

    private void applyEngineState(EngineState s, String reason) {
        status.setEngineState(s, reason);
        boolean ready = s == EngineState.READY;
        menus.setScientificEnabled(ready);
        commandBar.setEngineState(s);
    }

    // ---- quitting -----------------------------------------------------------------------------

    /** Ask the engine to quit; it returns only if termination is refused (unsaved changes). */
    void requestQuit() {
        new Thread(() -> {
            engine.quit(false);
            SwingUtilities.invokeLater(() -> {
                int c = JOptionPane.showConfirmDialog(frame, "Some images have unsaved changes. Quit and discard them?",
                        "Quit Fidelis", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (c == JOptionPane.OK_OPTION) {
                    new Thread(() -> {
                        engine.quit(true);
                        quitAction.run();
                    }, "fidelis-quit-discard").start();
                }
            });
        }, "fidelis-quit-request").start();
    }

    /** Restore the default dock sizes and visibility. */
    public void resetLayout() {
        docks.resetLayout();
    }

    // ---- accessors for tests and the screenshot -------------------------------------------------

    public JFrame frame() {
        return frame;
    }

    public Engine engine() {
        return engine;
    }

    public DocumentArea documents() {
        return documents;
    }

    public UiContext context() {
        return ctx;
    }

    CommandBar commandBar() {
        return commandBar;
    }

    ToolPalette tools() {
        return tools;
    }

    ParametersPanel parameters() {
        return parameters;
    }

    StatusBar status() {
        return status;
    }

    Docks docks() {
        return docks;
    }

    HistoryPanel history() {
        return history;
    }

    RoiPanel rois() {
        return rois;
    }

    ResultsPanel results() {
        return results;
    }

    LogPanel log() {
        return log;
    }

    ActivityPanel activity() {
        return activity;
    }

    MenuBuilder menus() {
        return menus;
    }

    Banners banners() {
        return banners;
    }

    LegacyWindows legacy() {
        return legacy;
    }

    /** Command-line options of the workspace (handled in {@link #launch}). */
    record Options(Path screenshot, java.util.List<String> open, Dimension size) {
        static Options parse(String[] args) {
            Path shot = null;
            java.util.List<String> open = new java.util.ArrayList<>();
            Dimension size = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--screenshot" -> {
                        if (i + 1 < args.length) shot = Path.of(args[++i]);
                    }
                    case "--open" -> {
                        if (i + 1 < args.length) open.add(args[++i]);
                    }
                    case "--size" -> {
                        if (i + 1 < args.length) {
                            String[] wh = args[++i].toLowerCase(java.util.Locale.ROOT).split("x");
                            if (wh.length == 2) size = new Dimension(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
                        }
                    }
                    default -> {
                    }
                }
            }
            if (shot != null && !shot.isAbsolute()) {
                // the JVM runs in the Fiji directory, which must never receive output files
                shot = Path.of(System.getProperty("fidelis.repo.dir", System.getProperty("java.io.tmpdir"))).resolve(shot);
            }
            if (shot != null && size == null) size = new Dimension(1500, 940);
            return new Options(shot, open, size);
        }
    }
}
