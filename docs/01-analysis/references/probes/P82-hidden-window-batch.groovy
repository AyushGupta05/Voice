#@ Context context
// P82 - Hidden-window and batch execution in a GUI-mode (non-headless) Fiji JVM.
// Agent G8, Fiji Modernisation Project, 2026-10-17. Launched by P85-gui-driver.ps1 (GUI mode, --run).
// (A) shown image whose ImageWindow is hidden with setVisible(false); (B) Java-side Interpreter.batchMode=true;
// (B2) macro setBatchMode(true); (C) which commands open their own windows, and whether Java APIs avoid them.
// For every operation the set of newly *showing* AWT windows is logged. Each operation runs on its own thread
// with a 20 s join timeout so that an unexpected modal dialog cannot hang the probe.
// Synthetic images only; nothing is saved. Termination: Runtime.halt(0) (see P85).
import ij.*
import ij.gui.*
import ij.macro.Interpreter
import ij.measure.Measurements
import ij.measure.ResultsTable
import ij.plugin.filter.Analyzer
import ij.plugin.filter.ParticleAnalyzer
import ij.plugin.frame.RoiManager
import java.awt.*

def OUTDIR = new File(System.getProperty("g8.out.dir"))
def OUT = new File(OUTDIR, "P82-hidden-window-batch.out.txt")
OUT.text = ""
long T0 = System.currentTimeMillis()
def log = { String s -> synchronized (OUT) { OUT << String.format("%6d [%s] %s%n", System.currentTimeMillis() - T0, Thread.currentThread().getName(), s) } }
def wd = new Thread({ Thread.sleep(220000); log("WATCHDOG fired -> halt(3)"); Runtime.getRuntime().halt(3) } as Runnable, "G8-watchdog")
wd.setDaemon(true); wd.start()
while (IJ.getInstance() == null) Thread.sleep(50)
Thread.sleep(1500)
log("START ij=" + IJ.getFullVersion() + " headless=" + GraphicsEnvironment.isHeadless())

try {
def showing = { Window.getWindows().findAll { it.isShowing() }.collect { w -> ((w instanceof Frame) ? ((Frame) w).getTitle() : (w instanceof Dialog) ? ((Dialog) w).getTitle() : "") + "<" + w.getClass().getSimpleName() + ">" } as Set }
def rtRows = { ResultsTable.getResultsTable()?.size() }
def rmCount = { RoiManager.getRawInstance() == null ? "none" : (RoiManager.getRawInstance().getCount() + (RoiManager.getRawInstance().isShowing() ? " (showing)" : " (not showing)")) }
def blobs = { String title ->
  def ip = new ij.process.ByteProcessor(256, 256)
  ip.setColor(255)
  def rnd = new Random(42)
  for (int i = 0; i < 14; i++) ip.fillOval(10 + rnd.nextInt(220), 10 + rnd.nextInt(220), 10 + rnd.nextInt(16), 10 + rnd.nextInt(16))
  new ImagePlus(title, ip)
}
def op = { String label, Closure c ->
  def before = showing()
  def res = null; def err = null
  def th = new Thread({ try { res = c.call() } catch (Throwable t) { err = t.getClass().getName() + ": " + t.getMessage() } } as Runnable, "G8-op")
  long t = System.currentTimeMillis()
  th.start(); th.join(20000)
  boolean stuck = th.isAlive()
  Thread.sleep(600)
  def opened = showing() - before
  def closed = before - showing()
  log(String.format("OP %-62s %5d ms%s%n      result=%s%s%n      newWindowsShowing=%s closedWindows=%s Results rows=%s RoiManager=%s",
      label, System.currentTimeMillis() - t - 600, stuck ? " STUCK(>20 s)" : "", res, err == null ? "" : " THREW " + err, opened, closed, rtRows(), rmCount()))
  if (stuck) {
    Window.getWindows().findAll { it instanceof GenericDialog && it.isShowing() }.each { gd -> log("      cancelling stuck dialog '" + gd.getTitle() + "'"); java.awt.EventQueue.invokeAndWait { gd.actionPerformed(new java.awt.event.ActionEvent(gd.getButtons()[1], 1001, "Cancel")) } }
    th.join(5000)
  }
}
def state = { String label, ImagePlus imp ->
  def ids = WindowManager.getIDList() as java.util.List
  log("STATE " + label + ": id=" + imp.getID() + " inIDList=" + (ids != null && ids.contains(imp.getID())) + " getCurrentImage=" + WindowManager.getCurrentImage()?.getTitle() +
      " getImage(title)=" + (WindowManager.getImage(imp.getTitle()) != null) + " window=" + imp.getWindow()?.getClass()?.getSimpleName() +
      " winVisible=" + imp.getWindow()?.isVisible() + " isVisible()=" + imp.isVisible() + " batchMode=" + Interpreter.isBatchMode() + " imageCount=" + WindowManager.getImageCount())
}
def suite = { String tag, ImagePlus imp ->
  op(tag + " IJ.run(imp,'Measure','') with rect ROI") { imp.setRoi(new Roi(30, 30, 80, 80)); IJ.run(imp, "Measure", ""); "mean=" + ResultsTable.getResultsTable().getValue("Mean", ResultsTable.getResultsTable().size() - 1) }
  op(tag + " IJ.run(imp,'Histogram','')") { imp.deleteRoi(); IJ.run(imp, "Histogram", ""); "current=" + WindowManager.getCurrentImage()?.getTitle() }
  op(tag + " IJ.run(imp,'Plot Profile','') with line ROI") { imp.setRoi(new Line(0, 128, 255, 128)); IJ.run(imp, "Plot Profile", ""); "current=" + WindowManager.getCurrentImage()?.getTitle() }
  op(tag + " threshold 128-255 + IJ.run(imp,'Analyze Particles...','size=10-Infinity display clear add')") {
    imp.deleteRoi(); imp.getProcessor().setThreshold(128, 255, ij.process.ImageProcessor.NO_LUT_UPDATE)
    IJ.run(imp, "Analyze Particles...", "size=10-Infinity display clear add"); "rows=" + ResultsTable.getResultsTable().size()
  }
  op(tag + " IJ.run(imp,'Gaussian Blur...','sigma=2')") { imp.getProcessor().resetThreshold(); IJ.run(imp, "Gaussian Blur...", "sigma=2"); "pixel(128,128)=" + imp.getProcessor().get(128, 128) }
}

// ---------------- (A) hidden ImageWindow
def impA = blobs("p82-hidden")
impA.show(); Thread.sleep(500)
state("A after show", impA)
java.awt.EventQueue.invokeAndWait { impA.getWindow().setVisible(false) }
Thread.sleep(300)
state("A after getWindow().setVisible(false)", impA)
suite("(A hidden)", impA)
state("A after suite", impA)
// close the windows the suite opened (they belong to the visible desktop)
def closeAux = {
  for (String t : ["Histogram of p82-hidden", "Plot of p82-hidden", "Histogram of p82-batch", "Plot of p82-batch", "Histogram of p82-unshown", "Plot of p82-unshown"]) { def w = WindowManager.getWindow(t); if (w != null) java.awt.EventQueue.invokeAndWait { if (w instanceof ImageWindow) { w.getImagePlus().changes = false; w.close() } else w.dispose() } }
}
closeAux()
RoiManager.getRawInstance()?.reset()

// ---------------- (B) Java-side batch mode
Interpreter.batchMode = true
def impB = blobs("p82-batch")
impB.show()
state("B after show() with Interpreter.batchMode=true", impB)
suite("(B batch)", impB)
state("B after suite", impB)
log("B batch-mode image titles: " + (Interpreter.getBatchModeImageIDs().collect { WindowManager.getImage(it)?.getTitle() }))
def impU = blobs("p82-unshown")
state("B2 never-shown image (no show())", impU)
suite("(B never-shown)", impU)
Interpreter.batchMode = false
state("B after Interpreter.batchMode=false", impB)
log("B after batchMode=false: WindowManager.getImage('Histogram of p82-batch')=" + WindowManager.getImage("Histogram of p82-batch"))
closeAux()
RoiManager.getRawInstance()?.reset()

// ---------------- (B3) macro batch mode
op("(B3) IJ.runMacro setBatchMode(true) newImage+Measure+Histogram+Analyze Particles(add)") {
  IJ.runMacro('''
setBatchMode(true);
newImage("p82-macro", "8-bit black", 256, 256, 1);
setColor(255); fillOval(20,20,30,30); fillOval(120,120,40,40); fillOval(200,40,20,20);
makeRectangle(10,10,100,100); run("Measure");
run("Select None"); run("Histogram");
selectImage("p82-macro"); setThreshold(128,255);
run("Analyze Particles...", "size=10-Infinity display clear add");
call("java.lang.System.setProperty", "g8.b3", "roiCount=" + roiManager("count") + " nImages=" + nImages + " titles=" + String.join(getList("image.titles")));
''')
  System.getProperty("g8.b3")
}
log("B3 after macro end: WindowManager.getImage('p82-macro')=" + WindowManager.getImage("p82-macro") + " RoiManager=" + rmCount())

// ---------------- (C) suppression via Java APIs
def impC = blobs("p82-api")
op("(C) new Analyzer(imp, AREA|MEAN, privateRt).measure()") { def rt = new ResultsTable(); impC.setRoi(new Roi(30, 30, 80, 80)); new Analyzer(impC, Measurements.AREA | Measurements.MEAN, rt).measure(); "privateRt rows=" + rt.size() + " mean=" + rt.getValue("Mean", 0) }
op("(C) ParticleAnalyzer + new RoiManager(true) + private ResultsTable") {
  def rm = new RoiManager(true); def rt = new ResultsTable()
  impC.deleteRoi(); impC.getProcessor().setThreshold(128, 255, ij.process.ImageProcessor.NO_LUT_UPDATE)
  ParticleAnalyzer.setRoiManager(rm)
  def pa = new ParticleAnalyzer(ParticleAnalyzer.ADD_TO_MANAGER | ParticleAnalyzer.SHOW_NONE, Measurements.AREA, rt, 10, Double.POSITIVE_INFINITY)
  boolean ok = pa.analyze(impC)
  ParticleAnalyzer.setRoiManager(null)
  "ok=" + ok + " privateRm.count=" + rm.getCount() + " privateRm.isShowing=" + rm.isShowing() + " RoiManager.getInstance()=" + RoiManager.getInstance() + " rt rows=" + rt.size()
}
op("(C) histogram via imp.getStatistics() (no window)") { impC.getProcessor().resetThreshold(); def h = impC.getStatistics().histogram; "bins=" + h.length + " h[255]=" + h[255] }
op("(C) profile via new ProfilePlot(imp).getProfile() (no window)") { impC.setRoi(new Line(0, 128, 255, 128)); def p = new ProfilePlot(impC).getProfile(); "n=" + p.length }
op("(C) hide Results window: setVisible(false), then IJ.run(imp,'Measure','') again") {
  def w = WindowManager.getWindow("Results")
  if (w != null) java.awt.EventQueue.invokeAndWait { w.setVisible(false) }
  impC.setRoi(new Roi(30, 30, 80, 80)); IJ.run(impC, "Measure", "")
  "Results window visible after Measure=" + WindowManager.getWindow("Results")?.isVisible()
}
op("(C) hide ROI Manager: setVisible(false), then Analyze Particles add") {
  def rm = RoiManager.getRawInstance()
  if (rm != null) java.awt.EventQueue.invokeAndWait { rm.setVisible(false) }
  impC.deleteRoi(); impC.getProcessor().setThreshold(128, 255, ij.process.ImageProcessor.NO_LUT_UPDATE)
  IJ.run(impC, "Analyze Particles...", "size=10-Infinity clear add")
  "RoiManager visible after=" + RoiManager.getRawInstance()?.isVisible() + " count=" + RoiManager.getRawInstance()?.getCount()
}
op("(C) ResultsTable.show suppression: rt.show() of a private table titled 'P82 table'") { def rt = new ResultsTable(); rt.addValue("x", 1); rt.show("P82 table"); "shown=" + (WindowManager.getWindow("P82 table") != null) }

} catch (Throwable t) {
  def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); log("SCRIPT THREW " + sw.toString().readLines().take(12).join(" | "))
}
log("FINAL showing windows: " + Window.getWindows().findAll { it.isShowing() }.collect { it.getClass().getSimpleName() })
log("DONE -> halt")
for (int id : (WindowManager.getIDList() ?: new int[0])) { def i = WindowManager.getImage(id); if (i != null) i.changes = false }
new File(OUTDIR, "done.flag").text = "done"
Runtime.getRuntime().halt(0)
