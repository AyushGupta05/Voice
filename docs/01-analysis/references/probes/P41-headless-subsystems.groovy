#@ Context ctx
#@ LegacyService legacy
#@ UIService ui
#@ ConvertService convert
#@ ImageDisplayService ids
#@ OpService ops
#@ ThreadService ts
#@ ScriptService ss
// P41 - Headless subsystem behaviour of the pinned Fiji (Fiji Modernisation Project, agent G3).
// Run: fiji-windows-x64.exe --java-home=<bundled JDK> --allow-multiple --headless --run <this file>
// Writes its report to the OUT path below (scratch), never into the Fiji directory.
import java.awt.GraphicsEnvironment
import java.awt.EventQueue
import ij.*
import ij.gui.*
import ij.macro.Interpreter
import ij.measure.ResultsTable
import ij.plugin.frame.*
import net.imagej.Dataset
import net.imagej.legacy.LegacyService

def OUT = new File(System.getProperty("p41.out", "C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G3/out/P41-headless-subsystems.out.txt"))
OUT.parentFile.mkdirs()
def lines = []
def rep = { String k, Object v -> def s = "${k} = ${v}"; lines << s; println s }
def chk = { String k, Closure c ->
  try { rep(k, c.call()) }
  catch (Throwable t) { rep(k, "THREW " + t.getClass().getName() + ": " + t.getMessage()) }
}
// Runs a closure on a fresh thread whose name starts with "Run$_" (required by ij.Macro.getOptions()).
def onRunThread = { String opts, Closure c ->
  def result = null
  def th = new Thread({
    try { if (opts != null) Macro.setOptions(opts); result = c.call() }
    catch (Throwable t) { result = "THREW " + t.getClass().getName() + ": " + t.getMessage() }
    finally { Macro.setOptions(null) }
  } as Runnable, "Run\$_P41")
  th.start(); th.join(30000)
  return result
}

rep("A1 java.version", System.getProperty("java.version"))
rep("A2 java.awt.headless property", System.getProperty("java.awt.headless"))
rep("A3 GraphicsEnvironment.isHeadless()", GraphicsEnvironment.isHeadless())
rep("A4 script thread name", Thread.currentThread().getName())
rep("A5 script thread is EDT", EventQueue.isDispatchThread())

chk("B1 LegacyService.isActive()") { legacy.isActive() }
chk("B2 LegacyService.isLegacyMode()") { legacy.isLegacyMode() }
chk("B3 LegacyService.isSyncEnabled()") { legacy.isSyncEnabled() }
chk("B4 LegacyService.getVersion()") { legacy.getVersion() }
chk("B5 IJ.getInstance()") { IJ.getInstance() }
chk("B6 Interpreter.isBatchMode()") { Interpreter.isBatchMode() }
chk("B7 GenericDialog superclass") { GenericDialog.class.getSuperclass().getName() }

chk("C1 UIService.isHeadless()") { ui.isHeadless() }
chk("C2 default UI") { def u = ui.getDefaultUI(); u.getClass().getName() }
chk("C3 available UIs") { ui.getAvailableUIs().collect { it.getClass().getName() }.join(", ") }
chk("C4 UIService.isVisible()") { ui.isVisible() }

rep("D1 GenericDialog, Run\$_ thread, no macro options", onRunThread(null) {
  def gd = new GenericDialog("probe"); gd.addNumericField("Sigma", 1.0); gd.showDialog(); "constructed; sigma=" + gd.getNextNumber()
})
rep("D2 GenericDialog with macro options 'sigma=3.5 mode=b flag'", onRunThread("sigma=3.5 mode=b flag") {
  def gd = new GenericDialog("probe")
  gd.addNumericField("Sigma", 1.0); gd.addChoice("Mode", ["a", "b"] as String[], "a"); gd.addCheckbox("Flag", false)
  gd.showDialog()
  "canceled=" + gd.wasCanceled() + " sigma=" + gd.getNextNumber() + " mode=" + gd.getNextChoice() + " flag=" + gd.getNextBoolean()
})
rep("D3 NonBlockingGenericDialog with macro options", onRunThread("sigma=4") {
  def gd = new NonBlockingGenericDialog("probe"); gd.addNumericField("Sigma", 1.0); gd.showDialog(); "sigma=" + gd.getNextNumber()
})

def imp = IJ.createImage("p41", "8-bit ramp", 64, 64, 1)
chk("E1 ImagePlus.show() then getWindow()") { imp.show(); imp.getWindow() }
chk("E2 WindowManager.getCurrentImage() title (script thread)") { WindowManager.getCurrentImage()?.getTitle() }
chk("E3 WindowManager.getImageCount()") { WindowManager.getImageCount() }
chk("E4 WindowManager.getIDList()") { WindowManager.getIDList() }
chk("E5 new ImageWindow(imp)") { new ImageWindow(imp) }
chk("E6 IJ.run(imp, Gaussian Blur..., sigma=2): mean before/after") {
  def before = imp.getStatistics().mean; IJ.run(imp, "Gaussian Blur...", "sigma=2"); "" + before + " -> " + imp.getStatistics().mean
}
chk("E7 IJ.run(Invert) on current image (no imp argument)") {
  def before = imp.getStatistics().mean; IJ.run("Invert"); "" + before + " -> " + imp.getStatistics().mean
}
chk("E8 IJ.setAutoThreshold + Convert to Mask") {
  def m = IJ.createImage("p41mask", "8-bit ramp", 64, 64, 1)
  IJ.setAutoThreshold(m, "Default dark"); IJ.run(m, "Convert to Mask", ""); "isBinary=" + m.getProcessor().isBinary()
}

chk("F1 Measure into system ResultsTable") {
  IJ.run(imp, "Set Measurements...", "area mean redirect=None decimal=3"); IJ.run(imp, "Measure", "")
  def rt = ResultsTable.getResultsTable(); "rows=" + rt.size() + " Mean[0]=" + rt.getValue("Mean", 0)
}
chk("F2 ResultsTable.show(\"Results\")") { ResultsTable.getResultsTable().show("Results"); "returned normally" }
chk("F3 new ResultsTable().show(\"Custom\")") { def t = new ResultsTable(); t.incrementCounter(); t.addValue("A", 1); t.show("Custom"); "returned normally" }
chk("F4 IJ.getTextPanel()") { IJ.getTextPanel() }

chk("G1 new RoiManager(false)") { new RoiManager(false) }
chk("G2 new RoiManager()") { new RoiManager() }
chk("G3 RoiManager.getInstance()") { RoiManager.getInstance() }
chk("G4 IJ.runMacro using roiManager in batch mode") {
  IJ.runMacro('setBatchMode(true); newImage("m", "8-bit black", 32, 32, 1); makeRectangle(2,2,10,10); roiManager("add"); n = roiManager("count"); return "" + n;')
}
chk("G5 ij.gui.Overlay as headless ROI container") {
  def ov = new Overlay(); ov.add(new Roi(1, 1, 5, 5)); ov.add(new OvalRoi(3, 3, 8, 8)); imp.setOverlay(ov); "overlay size=" + imp.getOverlay().size()
}

chk("H1 Recorder.record") { ij.plugin.frame.Recorder.record }
chk("H2 IJ.recording()") { IJ.recording() }
chk("H3 new Recorder(false)") { new ij.plugin.frame.Recorder(false) }
chk("H4 new ContrastAdjuster()") { new ContrastAdjuster() }
chk("H5 new ThresholdAdjuster()") { new ThresholdAdjuster() }
chk("H6 Toolbar.getInstance()") { Toolbar.getInstance() }
chk("H7 IJ.setTool(oval) then IJ.getToolName()") { IJ.setTool("oval"); IJ.getToolName() }

chk("I1 convert ImagePlus->Dataset") { def ds = convert.convert(imp, Dataset.class); ds == null ? null : ds.getClass().getName() + " dims=" + ds.dimensionsAsLongArray() }
chk("I2 pixel sharing ImagePlus->Dataset") {
  def ds = convert.convert(imp, Dataset.class)
  imp.getProcessor().set(0, 0, 77)
  def ra = ds.getImgPlus().randomAccess(); ra.setPosition([0L, 0L] as long[])
  "imp(0,0)=77 -> dataset(0,0)=" + ra.get().getRealDouble()
}
chk("I3 ImageDisplayService.getActiveImageDisplay()") { ids.getActiveImageDisplay()?.getName() }
chk("I4 LegacyImageMap size after I3") { legacy.getImageMap()?.getImageDisplays()?.size() }
chk("I5 ops stats.mean on Dataset") { def ds = convert.convert(imp, Dataset.class); ops.run("stats.mean", ds.getImgPlus()) }
chk("I6 script with '#@ Dataset' and '#@ ImagePlus' inputs (preprocessor injection)") {
  def code = "#@ Dataset d\n#@ ImagePlus ip\n#@output String r\nr = d.getName() + ' | ' + ip.getTitle() + ' | ' + Thread.currentThread().getName()\n"
  ss.run("p41inner.groovy", code, true).get().getOutput("r")
}
chk("I7 ThreadService.run(...) thread name") { ts.run({ Thread.currentThread().getName() } as java.util.concurrent.Callable).get() }
chk("I8 ThreadService.invoke on EDT (headless)") { def r = null; ts.invoke({ r = EventQueue.isDispatchThread() } as Runnable); r }

chk("J1 wrap cost: 1024x1024x20 16-bit ImagePlus->Dataset, 3 runs (ms)") {
  def big = IJ.createImage("big", "16-bit ramp", 1024, 1024, 20)
  def t = []
  3.times { def t0 = System.nanoTime(); def d = convert.convert(big, Dataset.class); t << String.format("%.1f", (System.nanoTime() - t0) / 1e6) }
  t.join(", ")
}

OUT.text = lines.join("\n") + "\n"
println "P41 report written to " + OUT
