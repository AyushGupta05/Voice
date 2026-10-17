// P42 - Non-headless JVM with ImageJ2 + legacy layer started WITHOUT showing any UI
// (the "GUI-capable JVM, hidden windows" mode; comparable to PyImageJ mode="interactive").
// Fiji Modernisation Project, agent G3.
// Body script: evaluated by P42-gui-jvm-hidden-windows.groovy AFTER LegacyInjector.preinit(),
// because importing ij.* before patching makes LegacyService fail ("No _hooks field found in ij.IJ").
// Short-lived AWT windows may appear on screen; every window opened is closed again.
// A watchdog thread calls System.exit after 150 s so that an unexpected modal dialog cannot hang the probe.
// Writes its report to the OUT path (scratch), never into the Fiji directory.
import java.awt.GraphicsEnvironment
import java.awt.EventQueue
import ij.*
import ij.gui.*
import ij.macro.Interpreter
import ij.measure.ResultsTable
import ij.plugin.frame.*

def OUT = new File("C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G3/out/P42-gui-jvm-hidden-windows.out.txt")
OUT.parentFile.mkdirs()
def ERR = new File(OUT.parentFile, "P42-stderr.txt")
System.setErr(new PrintStream(new FileOutputStream(ERR), true))
def lines = []
def t00 = System.currentTimeMillis()
def rep = { String k, Object v -> def s = "${k} = ${v}"; lines << s; OUT.text = lines.join("\n") + "\n" }
def chk = { String k, Closure c ->
  try { rep(k, c.call()) }
  catch (Throwable t) { rep(k, "THREW " + t.getClass().getName() + ": " + t.getMessage()) }
}
def onRunThread = { String opts, Closure c ->
  def result = null
  def th = new Thread({
    try { if (opts != null) Macro.setOptions(opts); result = c.call() }
    catch (Throwable t) { result = "THREW " + t.getClass().getName() + ": " + t.getMessage() }
    finally { Macro.setOptions(null) }
  } as Runnable, "Run\$_P42")
  th.start(); th.join(30000)
  return result
}
def watchdog = new Thread({ Thread.sleep(150000); rep("Z9 watchdog", "fired: forcing exit"); System.exit(3) } as Runnable, "P42-watchdog")
watchdog.setDaemon(true); watchdog.start()

rep("A1 java.awt.headless property", System.getProperty("java.awt.headless"))
rep("A2 GraphicsEnvironment.isHeadless()", GraphicsEnvironment.isHeadless())
rep("A3 IJ.getInstance() before gateway", IJ.getInstance())

def t0 = System.currentTimeMillis()
def ij = new net.imagej.ImageJ()
rep("A4 new net.imagej.ImageJ() ms (no UI shown)", System.currentTimeMillis() - t0)
def legacy = ij.get(net.imagej.legacy.LegacyService.class)
def ui = ij.ui()

chk("B1 LegacyService.isActive()") { legacy.isActive() }
chk("B2 LegacyService.isLegacyMode()") { legacy.isLegacyMode() }
chk("B3 IJ.getInstance() class / isVisible") { def m = IJ.getInstance(); m == null ? null : m.getClass().getName() + " visible=" + m.isVisible() }
chk("B4 default UI / UIService.isVisible()") { ui.getDefaultUI().getClass().getName() + " visible=" + ui.isVisible() }
chk("B5 Interpreter.isBatchMode()") { Interpreter.isBatchMode() }
chk("B6 GenericDialog superclass") { GenericDialog.class.getSuperclass().getName() }
chk("B7 Toolbar.getInstance()") { Toolbar.getInstance()?.getClass()?.getName() }

def imp = IJ.createImage("p42", "8-bit ramp", 128, 96, 1)
chk("C1 imp.show(): window class / visible / showing") {
  imp.show(); Thread.sleep(500); def w = imp.getWindow()
  w == null ? "window=null" : w.getClass().getName() + " visible=" + w.isVisible() + " showing=" + w.isShowing()
}
chk("C2 WindowManager.getCurrentImage()") { WindowManager.getCurrentImage()?.getTitle() }
chk("C3 hide window: setVisible(false) then visible / current image") {
  imp.getWindow()?.setVisible(false); Thread.sleep(300)
  "visible=" + imp.getWindow()?.isVisible() + " current=" + WindowManager.getCurrentImage()?.getTitle() + " count=" + WindowManager.getImageCount()
}
chk("C4 IJ.run on hidden-window image + ROI + Measure") {
  imp.setRoi(new Roi(10, 10, 30, 20)); IJ.run(imp, "Set Measurements...", "area mean redirect=None decimal=3"); IJ.run(imp, "Measure", "")
  def rt = ResultsTable.getResultsTable(); "rows=" + rt.size() + " Area[last]=" + rt.getValue("Area", rt.size() - 1)
}
chk("C5 ImageCanvas present on hidden window") { def c = imp.getCanvas(); c == null ? null : c.getClass().getName() + " mag=" + c.getMagnification() }

chk("D1 batch mode: Interpreter.batchMode=true; show(); window / current") {
  Interpreter.batchMode = true
  def b = IJ.createImage("p42batch", "8-bit black", 32, 32, 1); b.show()
  def r = "window=" + b.getWindow() + " current=" + WindowManager.getCurrentImage()?.getTitle()
  Interpreter.batchMode = false; r
}

rep("E1 GenericDialog with macro options", onRunThread("sigma=3.5 mode=b") {
  def gd = new GenericDialog("probe"); gd.addNumericField("Sigma", 1.0); gd.addChoice("Mode", ["a", "b"] as String[], "a"); gd.showDialog()
  "canceled=" + gd.wasCanceled() + " sigma=" + gd.getNextNumber() + " mode=" + gd.getNextChoice() + " visible=" + gd.isVisible()
})
rep("E2 NonBlockingGenericDialog with macro options", onRunThread("sigma=4") {
  def gd = new NonBlockingGenericDialog("probe"); gd.addNumericField("Sigma", 1.0); gd.showDialog(); "sigma=" + gd.getNextNumber()
})

chk("F1 new RoiManager(false): visible / add / count / getInstance") {
  def rm = new RoiManager(false); rm.addRoi(new Roi(1, 1, 5, 5)); rm.addRoi(new OvalRoi(2, 2, 9, 9))
  def r = "visible=" + rm.isVisible() + " count=" + rm.getCount() + " getInstance=" + RoiManager.getInstance(); rm.close(); r
}
chk("F2 new RoiManager(): visible") { def rm = new RoiManager(); Thread.sleep(300); def r = "visible=" + rm.isVisible(); rm.close(); r }
chk("F3 macro batch mode roiManager count") {
  IJ.runMacro('setBatchMode(true); newImage("m", "8-bit black", 32, 32, 1); makeRectangle(2,2,10,10); roiManager("add"); n = roiManager("count"); close(); setBatchMode(false); return "" + n;')
}

chk("G1 ResultsTable.show(Results): TextWindow visible") {
  ResultsTable.getResultsTable().show("Results"); Thread.sleep(300)
  def w = WindowManager.getWindow("Results"); def r = w == null ? "no window" : w.getClass().getName() + " visible=" + w.isVisible()
  if (w != null) w.dispose(); r
}

chk("H1 new Recorder(false): visible; IJ.run(Invert) recorded?") {
  def rec = new Recorder(false)
  IJ.run(imp, "Invert", "")
  def t1 = rec.getText()
  Recorder.recordInMacros = true
  IJ.run(imp, "Invert", "")
  def t2 = rec.getText()
  Recorder.record = false; Recorder.recordInMacros = false; rec.dispose()
  "visible=" + rec.isVisible() + " textAfterIJrun=[" + t1.trim() + "] textWithRecordInMacros=[" + t2.trim() + "]"
}
chk("H2 new ContrastAdjuster(): visible") { def ca = new ContrastAdjuster(); Thread.sleep(300); def r = "visible=" + ca.isVisible(); ca.close(); r }

chk("J1 ThreadService.invoke -> isDispatchThread") { def r = null; ij.thread().invoke({ r = EventQueue.isDispatchThread() } as Runnable); r }
chk("J2 elapsed ms total") { System.currentTimeMillis() - t00 }

imp.changes = false
try { imp.close() } catch (Throwable t) { rep("Z1 close", t.toString()) }
rep("Z0 done", "exiting via System.exit(0)")
System.exit(0)
