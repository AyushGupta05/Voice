#@ Context context
// P50 -- Fiji GUI staging script for the A04 UI audit screenshots (agent G4, 2026-10-17).
// Run (GUI, not headless) with:
//   cd C:\Users\arnav\Documents\dev\Fiji
//   fiji-windows-x64.exe -Dimagej.updater.disableAutocheck=true --java-home=<abs path to bundled JDK> --allow-multiple --run "<abs path to this file>"
// (Jaunch passes -D options to the JVM; "--run <file>" is handled by org.scijava.run.console.RunArgument ->
//  ScriptCodeRunner, which runs the Groovy file once the UI is up. An intermediate attempt with
//  -Dsun.java2d.uiScale=1 made no difference to window sizes and was not used for the final run.)
// It opens only synthetic images (no downloads, no file I/O except status/flag files in STATUS_DIR),
// arranges the windows, then waits for flag files written by the capture driver (P51) before opening
// modal/blocking UI. It never saves images, never runs the updater and never changes preferences on purpose.

import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.plugin.frame.Recorder
import ij.plugin.frame.RoiManager
import java.awt.Window
import javax.swing.SwingUtilities

def STATUS_DIR = new File(System.getProperty("p50.status.dir",
    "C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G4/run"))
STATUS_DIR.mkdirs()
def status = new File(STATUS_DIR, "status.txt")
def say = { String s -> status << (new Date().format("HH:mm:ss.SSS") + " " + s + "\n") }
def waitFlag = { String name ->
    def f = new File(STATUS_DIR, name)
    say("waiting for " + name)
    while (!f.exists()) Thread.sleep(250)
    say("got " + name)
}
def place = { String title, int x, int y ->
    Window w = WindowManager.getWindow(title)
    if (w == null) {
        // non-ImageJ windows (Swing) -- search all AWT windows by title
        w = Window.getWindows().find { it.isShowing() && (it.hasProperty("title") && it.title == title) }
    }
    if (w != null) { w.setLocation(x, y); say("placed '" + title + "' at " + x + "," + y + " size " + w.getSize()) }
    else say("NOT FOUND for placement: '" + title + "'")
}

// In-process capture (method B): paints a window's own component tree into an off-screen image.
// Used because the interactive desktop was behind the Windows screen saver during the session
// (OpenInputDesktop returned "Screen-saver"), so screen-based capture was impossible. No screen pixels are read.
def SHOT_DIR = new File(STATUS_DIR, "inproc")
SHOT_DIR.mkdirs()
def snapWin = { Window w, String name ->
    try {
        SwingUtilities.invokeAndWait {
            w.validate()
            def ins = w.getInsets()
            def img = new java.awt.image.BufferedImage(w.width, w.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            def g = img.createGraphics()
            g.setColor(w.getBackground() ?: java.awt.Color.LIGHT_GRAY)
            g.fillRect(0, 0, w.width, w.height)
            w.printAll(g)
            g.dispose()
            int top = ins.top
            if (w instanceof ij.gui.ImageWindow) {
                // keep the subtitle line, which ImageWindow.drawInfo paints inside its enlarged top inset
                def fld = ij.gui.ImageWindow.class.getDeclaredField("textGap")
                fld.setAccessible(true)
                top = ((ij.gui.ImageWindow) w).getInsets(false).top - (int) fld.getInt(w)
            }
            def cw = Math.max(1, w.width - ins.left - ins.right), ch = Math.max(1, w.height - top - ins.bottom)
            def sub = img.getSubimage(ins.left, top, cw, ch)
            javax.imageio.ImageIO.write(sub, "png", new File(SHOT_DIR, name + ".png"))
        }
        say("inproc snap '" + name + "' " + w.width + "x" + w.height)
    } catch (Throwable t) { say("inproc snap FAILED '" + name + "': " + t) }
}
def snapAll = { String prefix ->
    for (Window w : Window.getWindows()) {
        if (!w.isShowing()) continue
        def t = (w.hasProperty("title") ? w.title : "") ?: w.getClass().getSimpleName()
        snapWin(w, prefix + t.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-\$", "").toLowerCase())
    }
}

say("P50 start; java=" + System.getProperty("java.version") + " ij=" + IJ.getFullVersion()
    + " autocheckDisabled=" + Boolean.getBoolean("imagej.updater.disableAutocheck"))
while (IJ.getInstance() == null) Thread.sleep(250)
Thread.sleep(2000)
say("ImageJ instance present: " + IJ.getInstance().getTitle())

// 1. Recorder first, with recordInMacros so the staged commands appear in it.
IJ.run("Record...")
Thread.sleep(500)
Recorder.recordInMacros = true

// 2. Synthetic 2D image ("synthetic-blobs"), 8-bit, deterministic.
IJ.runMacro('''
newImage("synthetic-blobs", "8-bit black", 256, 254, 1);
random("seed", 42);
for (i=0; i<30; i++) {
  x = random*236; y = random*234; d = 12 + random*16;
  setColor(150 + random*100);
  fillOval(x, y, d, d);
}
run("Select None");
run("Gaussian Blur...", "sigma=2");
run("Add Specified Noise...", "standard=6");
run("Set Measurements...", "area mean min centroid display redirect=None decimal=3");
''')

// 3. ROIs into the ROI Manager + Measure -> Results table.
IJ.runMacro('''
selectImage("synthetic-blobs");
makeOval(30, 30, 60, 60);        roiManager("Add");
makeRectangle(120, 40, 70, 50);  roiManager("Add");
makePolygon(40,150, 110,140, 130,220, 50,230); roiManager("Add");
roiManager("Show All with labels");
roiManager("Measure");
run("Select None");
''')

// 4. Histogram and Plot Profile of the 2D image.
IJ.runMacro('''
selectImage("synthetic-blobs");
run("Select None");
run("Histogram");
selectImage("synthetic-blobs");
makeLine(5, 127, 250, 127);
run("Plot Profile");
selectImage("synthetic-blobs");
''')

// 5. Duplicate for thresholding (keeps the ROI view clean).
IJ.runMacro('''
selectImage("synthetic-blobs");
run("Select None");
run("Duplicate...", "title=synthetic-blobs-threshold");
setAutoThreshold("Default dark");
''')

// 6. Hyperstack: 3 channels x 8 slices x 5 frames, composite mode, label content.
IJ.runMacro('''
newImage("hyperstack-demo", "8-bit composite-mode label", 320, 240, 3, 8, 5);
Stack.setPosition(2, 4, 3);
''')

// 7. Log window.
IJ.log("P50 staging log line 1: synthetic images only")
IJ.log("P50 staging log line 2: images open = " + WindowManager.getImageCount())

Recorder.recordInMacros = false

// 8. Tool windows that follow the active image.
IJ.runMacro('selectImage("hyperstack-demo");')
IJ.run("Channels Tool...")
IJ.runMacro('selectImage("synthetic-blobs-threshold");')
IJ.run("Threshold...")
IJ.runMacro('selectImage("synthetic-blobs");')
IJ.run("Brightness/Contrast...")

// 9. Script Editor (SciJava TextEditor) with a short IJ1 macro.
SwingUtilities.invokeAndWait {
    def ed = new org.scijava.ui.swing.script.TextEditor(context)
    ed.newTab('// Example macro shown in the Fiji Script Editor\nrun("Gaussian Blur...", "sigma=2");\nsetAutoThreshold("Default dark");\nrun("Convert to Mask");\nrun("Measure");\n', "ijm")
    ed.setVisible(true)
    ed.setSize(640, 420)
    ed.setLocation(560, 600)
}
Thread.sleep(1500)

// 10. Arrange windows (logical coordinates; screen is 1707x1067 logical at 150 %).
place("hyperstack-demo", 0, 150)
place("synthetic-blobs", 360, 150)
place("synthetic-blobs-threshold", 640, 150)
place("ROI Manager", 920, 150)
place("Results", 0, 560)
place("Histogram of synthetic-blobs", 360, 520)
place("Plot of synthetic-blobs", 1150, 560)
place("B&C", 1180, 120)
place("Threshold", 1350, 120)
place("Channels", 1180, 450)
place("Log", 0, 860)
place("Recorder", 560, 860)

for (Window w : Window.getWindows()) {
    if (w.isShowing()) {
        def t = w.hasProperty("title") ? w.title : w.getClass().getName()
        say("window: '" + t + "' class=" + w.getClass().getName() + " bounds=" + w.getBounds())
    }
}
Thread.sleep(1500)
snapAll("p1-")
say("phase1-ready")

// Phase 2: Command Finder (non-modal JFrame), opened like a menu click (new thread).
waitFlag("go-phase2.flag")
IJ.runMacro('selectImage("synthetic-blobs");')
IJ.doCommand("Find Commands...")
Thread.sleep(2500)
Window.getWindows().findAll { it.isShowing() && it.hasProperty("title") && it.title == "Command Finder" }.each { snapWin(it, "p2-command-finder") }
say("phase2-ready")

// Phase 3: Gaussian Blur GenericDialog with Preview, opened like a menu click (new thread, no macro options).
waitFlag("go-phase3.flag")
IJ.runMacro('selectImage("synthetic-blobs"); run("Select None");')
IJ.doCommand("Gaussian Blur...")
Thread.sleep(2500)
Window.getWindows().findAll { it.isShowing() && it instanceof java.awt.Dialog && it.hasProperty("title") && (it.title ?: "").startsWith("Gaussian") }.each { snapWin(it, "p3-gaussian-blur-dialog") }
say("phase3-ready")

// The driver kills this Fiji process after capture; nothing is saved.
waitFlag("go-end.flag")
say("end")
