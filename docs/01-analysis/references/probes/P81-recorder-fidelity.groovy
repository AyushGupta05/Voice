#@ Context context
// P81 - Macro Recorder fidelity in a GUI-mode (non-headless) Fiji JVM.
// Agent G8, Fiji Modernisation Project, 2026-10-17. Launched by P85-gui-driver.ps1 (GUI mode, --run).
// Opens the Recorder with IJ.run("Record...") and captures the text it appends for each execution path:
//   (a) IJ.run(imp, cmd, opts)  (b) IJ.run(cmd, opts)  (c) IJ.runMacro("run(cmd, opts);")
//   (d) menu-click simulation: the MenuItem's ActionListeners are invoked on the EDT (ImageJ.actionPerformed ->
//       new Executer(cmd, imp) on a thread named after the command), the GenericDialog that opens is filled in
//       programmatically and OK is "pressed" with gd.actionPerformed(new ActionEvent(okButton, ...)) on the EDT
//   (d') new Executer(cmd, null) + Macro.setOptions(executerThread, opts) set by reflection immediately after start
//   (e) Recorder.recordString / Recorder.record injection, (f) Recorder.suspendRecording,
//   (g) roiManager("Measure") via macro, RoiManager.runCommand and a simulated "Measure" button click.
// Each case is run with Recorder.recordInMacros=false and =true where relevant.
// Synthetic images only; nothing is saved. Termination: Runtime.halt(0) (see P85).
import ij.*
import ij.gui.*
import ij.plugin.frame.Recorder
import ij.plugin.frame.RoiManager
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.SwingUtilities

def OUTDIR = new File(System.getProperty("g8.out.dir"))
def OUT = new File(OUTDIR, "P81-recorder-fidelity.out.txt")
OUT.text = ""
long T0 = System.currentTimeMillis()
def log = { String s -> synchronized (OUT) { OUT << String.format("%6d [%s] %s%n", System.currentTimeMillis() - T0, Thread.currentThread().getName(), s) } }
def wd = new Thread({ Thread.sleep(220000); log("WATCHDOG fired -> halt(3)"); Runtime.getRuntime().halt(3) } as Runnable, "G8-watchdog")
wd.setDaemon(true); wd.start()
while (IJ.getInstance() == null) Thread.sleep(50)
Thread.sleep(1500)
log("START ij=" + IJ.getFullVersion())

// Open the Recorder (from a non-Run$_ thread first, so the script thread name is not yet changed).
def opener = new Thread({ IJ.run("Record...") } as Runnable, "G8-open-recorder"); opener.start(); opener.join(10000)
Thread.sleep(800)
def R = Recorder.getInstance()
log("Recorder.getInstance()=" + R?.getClass()?.getName() + " Recorder.record=" + Recorder.record + " IJ.recording()=" + IJ.recording() +
    " scriptMode=" + Recorder.scriptMode() + " recordInMacros=" + Recorder.recordInMacros)
def text = { Recorder.getInstance() == null ? "" : Recorder.getInstance().getText() }
def esc = { String s -> s.replace("\n", "\\n") }
def cases = []
def rec = { String id, Closure c ->
  String before = text()
  String err = null
  try { c.call() } catch (Throwable t) { err = t.getClass().getName() + ": " + t.getMessage() }
  Thread.sleep(500)
  String after = text()
  String delta = after.startsWith(before) ? after.substring(before.length()) : ("<TEXT REPLACED> " + after)
  log(String.format("CASE %-58s recordInMacros=%-5s thread=%s%n        recorded: [%s]%s", id, Recorder.recordInMacros,
      Thread.currentThread().getName(), esc(delta), err == null ? "" : "\n        THREW " + err))
  cases << [id, Recorder.recordInMacros, delta]
}

def imp = IJ.createImage("p81", "8-bit ramp", 256, 256, 1)
imp.show(); Thread.sleep(500)
def cmds = [
  ["Gaussian Blur...", "sigma=2.5"],
  ["Add...", "value=25"],
  ["Set Measurements...", "area mean min centroid perimeter display redirect=None decimal=3"],
  ["Duplicate...", "title=p81-dup"],
  ["Measure", ""],
]
def closeDup = { def d = WindowManager.getImage("p81-dup"); if (d != null) { d.changes = false; d.close() }; WindowManager.setTempCurrentImage(null); IJ.selectWindow("p81") }

// --- (a)(b)(c) programmatic paths, both recordInMacros settings
for (boolean rim : [false, true]) {
  Recorder.recordInMacros = rim
  for (def cmd : cmds) {
    String c = cmd[0], o = cmd[1]
    imp.setRoi(new Roi(20, 20, 100, 100))
    rec("(a) IJ.run(imp,'" + c + "','" + o + "')") { IJ.run(imp, c, o) }; closeDup()
    rec("(b) IJ.run('" + c + "','" + o + "')") { IJ.run(c, o) }; closeDup()
    String m = o == "" ? 'run("' + c + '");' : 'run("' + c + '", "' + o + '");'
    rec("(c) IJ.runMacro('" + m + "')") { IJ.runMacro(m) }; closeDup()
  }
}
Recorder.recordInMacros = false

// --- macro built-ins that are not menu commands
for (boolean rim : [false, true]) {
  Recorder.recordInMacros = rim
  rec("(c) IJ.runMacro makeRectangle+setAutoThreshold+Convert to Mask (on dup)") {
    IJ.runMacro('selectImage("p81"); run("Duplicate...", "title=p81-dup"); makeRectangle(5,5,50,50); setAutoThreshold("Otsu dark"); run("Convert to Mask");')
  }; closeDup()
  rec("(a) Java: imp.setRoi(new Roi(..)) + ip.setAutoThreshold + IJ.run(imp,'Convert to Mask')") {
    def d = imp.duplicate(); d.setTitle("p81-dup"); d.show(); Thread.sleep(200)
    d.setRoi(new Roi(5, 5, 50, 50)); d.getProcessor().setAutoThreshold("Otsu dark"); IJ.run(d, "Convert to Mask", "")
  }; closeDup()
}
Recorder.recordInMacros = false

// --- (e) injection APIs
rec("(e) Recorder.recordString('run(\"Gaussian Blur...\", \"sigma=2.5\");\\n')") { Recorder.recordString('run("Gaussian Blur...", "sigma=2.5");\n') }
rec("(e) Recorder.record('run','Invert')") { Recorder.record("run", "Invert") }
rec("(e) Recorder.record('makeRectangle',1,2,3,4)") { Recorder.record("makeRectangle", 1, 2, 3, 4) }
rec("(e) Recorder.recordCall('imp = IJ.getImage();') (macro mode => ignored?)") { Recorder.recordCall("imp = IJ.getImage();") }
rec("(e) setCommand/recordOption/saveCommand from a non-Run\$_ thread") {
  def t = new Thread({ Recorder.setCommand("Gaussian Blur..."); Recorder.recordOption("sigma", "4"); Recorder.saveCommand() } as Runnable, "G8-inject")
  t.start(); t.join(5000)
}
// --- (f) suspendRecording on the current thread
Recorder.recordInMacros = true
rec("(f) suspendRecording(); IJ.run(imp,'Invert',''); resumeRecording()") { Recorder.suspendRecording(); IJ.run(imp, "Invert", ""); Recorder.resumeRecording() }
Recorder.recordInMacros = false

// --- (g) ROI Manager measure
def rm = RoiManager.getRoiManager()
rm.reset()
rm.addRoi(new Roi(10, 10, 40, 40)); rm.addRoi(new OvalRoi(80, 80, 50, 50)); rm.addRoi(new Roi(150, 20, 60, 30))
rm.deselect()
for (boolean rim : [false, true]) {
  Recorder.recordInMacros = rim
  rec("(g) IJ.runMacro('roiManager(\"Measure\");')") { IJ.runMacro('selectImage("p81"); roiManager("Deselect"); roiManager("Measure");') }
  rec("(g) rm.runCommand(imp,'Measure')") { rm.runCommand(imp, "Measure") }
}
Recorder.recordInMacros = false
rec("(g) simulated ROI Manager 'Measure' button click (rm.actionPerformed on EDT)") {
  SwingUtilities.invokeAndWait { rm.deselect(); rm.actionPerformed(new ActionEvent(rm, ActionEvent.ACTION_PERFORMED, "Measure")) }
}
log("EDT thread name after button simulation: " + { def n = null; SwingUtilities.invokeAndWait { n = Thread.currentThread().getName() }; n }.call())

// --- (d) menu-click simulation with programmatic dialog filling
def findItem
findItem = { MenuComponent mc, String label ->
  if (mc instanceof Menu) { for (int i = 0; i < mc.getItemCount(); i++) { def r = findItem(mc.getItem(i), label); if (r != null) return r } ; return null }
  if (mc instanceof MenuItem && mc.getLabel() == label) return mc
  return null
}
def menuItem = { String label ->
  def mb = IJ.getInstance().getMenuBar()
  for (int i = 0; i < mb.getMenuCount(); i++) { def r = findItem(mb.getMenu(i), label); if (r != null) return r }
  return null
}
def waitDialog = { String title, long ms ->
  long t = System.currentTimeMillis()
  while (System.currentTimeMillis() - t < ms) {
    def w = Window.getWindows().find { it instanceof GenericDialog && it.isShowing() && ((Dialog) it).getTitle() == title }
    if (w != null) return w
    Thread.sleep(50)
  }
  return null
}
def clickMenu = { String label ->
  def item = menuItem(label)
  if (item == null) { log("menu item not found: " + label); return false }
  log("menu item '" + label + "' listeners=" + item.getActionListeners().collect { it.getClass().getName() })
  SwingUtilities.invokeAndWait { item.getActionListeners().each { it.actionPerformed(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, item.getActionCommand())) } }
  return true
}
def fillAndOk = { String title, Closure fill ->
  def gd = waitDialog(title, 8000)
  if (gd == null) { log("dialog '" + title + "' NOT found"); return }
  log("dialog '" + title + "' found: class=" + gd.getClass().getName() + " modal=" + gd.isModal() +
      " numeric=" + (gd.getNumericFields()?.collect { it.getText() }) + " checkboxes=" + (gd.getCheckboxes()?.collect { it.getLabel() + "=" + it.getState() }) +
      " choices=" + (gd.getChoices()?.collect { it.getSelectedItem() }))
  SwingUtilities.invokeAndWait { fill.call(gd); gd.actionPerformed(new ActionEvent(gd.getButtons()[0], ActionEvent.ACTION_PERFORMED, "OK")) }
  Thread.sleep(800)
  log("dialog '" + title + "' after OK: wasOKed=" + gd.wasOKed() + " wasCanceled=" + gd.wasCanceled() + " showing=" + gd.isShowing())
}
imp.setRoi(new Roi(20, 20, 100, 100))
for (boolean rim : [false, true]) {
  Recorder.recordInMacros = rim
  rec("(d) menu click 'Gaussian Blur...' + dialog sigma=3.5 + OK") { clickMenu("Gaussian Blur..."); fillAndOk("Gaussian Blur...") { g -> g.getNumericFields()[0].setText("3.5") } }
  rec("(d) menu click 'Add...' + dialog value=7 + OK") { clickMenu("Add..."); fillAndOk("Add") { g -> g.getNumericFields()[0].setText("7") } }
  rec("(d) menu click 'Set Measurements...' + tick Perimeter + OK") {
    clickMenu("Set Measurements..."); fillAndOk("Set Measurements") { g -> g.getCheckboxes().find { it.getLabel().startsWith("Perimeter") }?.setState(true) }
  }
  rec("(d) menu click 'Measure' (no dialog)") { clickMenu("Measure"); Thread.sleep(800) }
}
Recorder.recordInMacros = false
rec("(d') new Executer('Gaussian Blur...', null) + Macro.setOptions(thread,'sigma=1.5') by reflection") {
  def ex = new Executer("Gaussian Blur...", null)
  def f = Executer.class.getDeclaredField("thread"); f.setAccessible(true)
  def th = (Thread) f.get(ex)
  Macro.setOptions(th, "sigma=1.5")
  log("Executer thread name='" + th.getName() + "' Macro options set for it")
  def gd = waitDialog("Gaussian Blur...", 4000)
  log("dialog shown despite Macro.setOptions(thread): " + (gd != null))
  if (gd != null) SwingUtilities.invokeAndWait { gd.getNumericFields()[0].setText("1.5"); gd.actionPerformed(new ActionEvent(gd.getButtons()[0], ActionEvent.ACTION_PERFORMED, "OK")) }
  th.join(5000)
}

log("FINAL Recorder text:\n" + text())
log("DONE -> halt")
for (int id : (WindowManager.getIDList() ?: new int[0])) { def i = WindowManager.getImage(id); if (i != null) i.changes = false }
new File(OUTDIR, "done.flag").text = "done"
Runtime.getRuntime().halt(0)
