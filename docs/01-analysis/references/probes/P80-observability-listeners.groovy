#@ Context context
// P80 - Observability of a GUI-mode (non-headless) Fiji JVM: which listener fires for which execution path.
// Agent G8, Fiji Modernisation Project, 2026-10-17. Launched by P85-gui-driver.ps1 (GUI mode, --run).
// Registers: ImagePlus.addImageListener, Roi.addRoiListener, Executer.addCommandListener,
// IJ.addEventListener, and SciJava EventService subscriptions (Module*Event, Display*Event, Dataset*Event).
// Every event is logged with a timestamp, thread name and EDT flag. Only synthetic images; nothing is saved.
// Termination: Runtime.halt(0) (System.exit blocks in a shutdown hook in GUI mode, see P85).
import ij.*
import ij.gui.*
import ij.measure.ResultsTable
import ij.plugin.frame.Recorder
import java.awt.EventQueue
import java.util.concurrent.TimeUnit
import org.scijava.event.EventHandler
import org.scijava.event.EventService
import org.scijava.module.event.*
import org.scijava.display.event.*
import net.imagej.event.*

def OUTDIR = new File(System.getProperty("g8.out.dir"))
def OUT = new File(OUTDIR, "P80-observability-listeners.out.txt")
OUT.text = ""
long T0 = System.currentTimeMillis()
def lock = new Object()
def log = { String s ->
  synchronized (lock) {
    OUT << String.format("%6d %-28s edt=%-5s %s%n", System.currentTimeMillis() - T0,
      "[" + Thread.currentThread().getName() + "]", EventQueue.isDispatchThread(), s)
  }
}
def wd = new Thread({ Thread.sleep(200000); log("WATCHDOG fired -> halt(3)"); Runtime.getRuntime().halt(3) } as Runnable, "G8-watchdog")
wd.setDaemon(true); wd.start()
while (IJ.getInstance() == null) Thread.sleep(50)
Thread.sleep(1500)
log("START ij=" + IJ.getFullVersion() + " headless=" + java.awt.GraphicsEnvironment.isHeadless())

// ---------------------------------------------------------------- listeners
def imgL = [
  imageOpened : { ImagePlus imp -> log("IJ1 ImageListener.imageOpened  '" + imp.getTitle() + "'") },
  imageClosed : { ImagePlus imp -> log("IJ1 ImageListener.imageClosed  '" + imp.getTitle() + "'") },
  imageUpdated: { ImagePlus imp -> log("IJ1 ImageListener.imageUpdated '" + imp.getTitle() + "'") }
] as ImageListener
ImagePlus.addImageListener(imgL)
def roiNames = [1: "CREATED", 2: "MOVED", 3: "MODIFIED", 4: "EXTENDED", 5: "COMPLETED", 6: "DELETED"]
def roiL = { ImagePlus imp, int id -> log("IJ1 RoiListener.roiModified " + roiNames[id] + " on '" + imp?.getTitle() + "'") } as RoiListener
Roi.addRoiListener(roiL)
def cmdL = { String cmd -> log("IJ1 CommandListener.commandExecuting '" + cmd + "' Macro.getOptions()=" + Macro.getOptions()); return cmd } as CommandListener
Executer.addCommandListener(cmdL)
def evNames = [0: "FOREGROUND_COLOR_CHANGED", 1: "BACKGROUND_COLOR_CHANGED", 2: "COLOR_PICKER_CLOSED", 3: "LOG_WINDOW_CLOSED", 4: "TOOL_CHANGED"]
def ijL = { int id -> log("IJ1 IJEventListener.eventOccurred " + evNames[id]) } as IJEventListener
IJ.addEventListener(ijL)

class G8Sub {
  Closure log
  String t(ModuleEvent e) { def i = e.getModule().getInfo(); return i.getTitle() + " <" + i.getDelegateClassName() + ">" }
  @EventHandler void a(ModuleStartedEvent e)   { log("SJ ModuleStartedEvent   " + t(e)) }
  @EventHandler void b(ModuleExecutingEvent e) { log("SJ ModuleExecutingEvent " + t(e)) }
  @EventHandler void c(ModuleExecutedEvent e)  { log("SJ ModuleExecutedEvent  " + t(e)) }
  @EventHandler void d(ModuleFinishedEvent e)  { log("SJ ModuleFinishedEvent  " + t(e)) }
  @EventHandler void f(ModuleCanceledEvent e)  { log("SJ ModuleCanceledEvent  " + t(e)) }
  @EventHandler void g(ModuleErroredEvent e)   { log("SJ ModuleErroredEvent   " + t(e) + " " + e.getException()) }
  @EventHandler void h(DisplayCreatedEvent e)  { log("SJ DisplayCreatedEvent  " + e.getObject()) }
  @EventHandler void i(DisplayUpdatedEvent e)  { log("SJ DisplayUpdatedEvent  " + e.getDisplay() + " level=" + e.getLevel()) }
  @EventHandler void j(DisplayActivatedEvent e){ log("SJ DisplayActivatedEvent " + e.getDisplay()) }
  @EventHandler void k(DisplayDeletedEvent e)  { log("SJ DisplayDeletedEvent  " + e.getObject()) }
  @EventHandler void l(DatasetCreatedEvent e)  { log("SJ DatasetCreatedEvent  " + e.getObject()) }
  @EventHandler void m(DatasetUpdatedEvent e)  { log("SJ DatasetUpdatedEvent  " + e.getObject()) }
  @EventHandler void n(DatasetDeletedEvent e)  { log("SJ DatasetDeletedEvent  " + e.getObject()) }
}
def es = context.service(EventService)
def sub = new G8Sub(log: log)
def subscribers = es.subscribe(sub)   // keep a strong reference: EventService holds subscribers weakly
log("SciJava EventService.subscribe -> " + subscribers.size() + " handlers")

// ---------------------------------------------------------------- step helper
def rtSize = { ResultsTable.getResultsTable() == null ? -1 : ResultsTable.getResultsTable().size() }
def step = { String name, Closure c ->
  int before = rtSize()
  log("=== BEGIN " + name + "  (Results rows=" + before + ")")
  long t = System.currentTimeMillis()
  try { def r = c.call(); if (r != null) log("    result: " + r) }
  catch (Throwable e) { log("    THREW " + e.getClass().getName() + ": " + e.getMessage()) }
  log("=== END   " + name + " (" + (System.currentTimeMillis() - t) + " ms; Results rows " + before + " -> " + rtSize() + ")")
  Thread.sleep(700)   // let EDT-dispatched listeners (ImageListener uses EventQueue.invokeLater) run
  log("    (after 700 ms settle)")
}

ImagePlus imp
step("S01 IJ.createImage 512x512 16-bit + show()") {
  imp = IJ.createImage("p80-16bit", "16-bit ramp", 512, 512, 1); imp.show(); Thread.sleep(300); "window=" + imp.getWindow()?.getClass()?.getName()
}
step("S02 IJ.run(imp, 'Gaussian Blur...', 'sigma=2')") { IJ.run(imp, "Gaussian Blur...", "sigma=2") }
step("S03 IJ.run('Gaussian Blur...', 'sigma=2') on current image") { IJ.run("Gaussian Blur...", "sigma=2"); "current=" + WindowManager.getCurrentImage()?.getTitle() }
step("S04 IJ.runMacro('run(\"Invert\");')") { IJ.runMacro('run("Invert");') }
step("S05 IJ.doCommand('Invert') (menu-equivalent: new Executer(cmd, null) on its own thread)") { IJ.doCommand("Invert"); Thread.sleep(1500) }

def cs = context.service(org.scijava.command.CommandService)
def ms = context.service(org.scijava.module.ModuleService)
step("S06 CommandService.run(net.imagej.plugins.commands.assign.InvertDataValues, process=true, allPlanes=false)") {
  def f = cs.run(net.imagej.plugins.commands.assign.InvertDataValues.class, true, "allPlanes", false)
  def m = f.get(30, TimeUnit.SECONDS)
  "module=" + m?.getClass()?.getName() + " display=" + m?.getInput("display")
}
step("S07 ModuleService: locate legacy IJ1 'Invert' module (LegacyCommandInfo) and run it") {
  def cands = ms.getModules().findAll { it.getTitle() == "Invert" }
  log("    candidates: " + cands.collect { it.getClass().getSimpleName() + ":" + it.getDelegateClassName() + ":" + it.getIdentifier() })
  def info = cands.find { it.getClass().getName().contains("Legacy") } ?: cands.find { true }
  if (info == null) return "no module titled Invert"
  def m = ms.run(info, true).get(30, TimeUnit.SECONDS)
  "ran " + info.getIdentifier()
}
step("S08 ScriptService.run('g8.ijm', 'run(\"Invert\");') (IJ1 macro through the SciJava script engine)") {
  def ss = context.service(org.scijava.script.ScriptService)
  ss.run("g8.ijm", 'run("Invert");', true).get(30, TimeUnit.SECONDS); "ok"
}
step("S09 imp.setRoi(new Roi(50,50,100,80))") { imp.setRoi(new Roi(50, 50, 100, 80)); "roi=" + imp.getRoi() }
step("S10 programmatic modify: imp.getRoi().setLocation(80,90); imp.draw()") { imp.getRoi().setLocation(80, 90); imp.draw(); "roi=" + imp.getRoi() }
step("S11 macro modify: IJ.runMacro('makeRectangle(10,10,200,150);')") { IJ.runMacro('makeRectangle(10,10,200,150);'); "roi=" + imp.getRoi() }
step("S12 IJ.run(imp, 'Measure', '')") { IJ.run(imp, "Measure", "") }
step("S13 IJ.runMacro('run(\"Measure\");')") { IJ.runMacro('run("Measure");') }
step("S14 IJ.run(imp, 'Select None', '')") { IJ.run(imp, "Select None", "") }
step("S15 IJ.runMacro('setAutoThreshold(\"Otsu dark\"); run(\"Convert to Mask\");') on a duplicate") {
  IJ.run(imp, "Duplicate...", "title=p80-mask")
  def d = WindowManager.getImage("p80-mask")
  IJ.runMacro('selectImage("p80-mask"); setAutoThreshold("Otsu dark"); run("Convert to Mask");')
  "mask bitDepth=" + d?.getBitDepth() + " isThreshold-applied=" + d?.getProcessor()?.isBinary()
}
step("S16 Java API setAutoThreshold + IJ.run(imp2,'Convert to Mask','')") {
  def d2 = imp.duplicate(); d2.setTitle("p80-mask2"); d2.show(); Thread.sleep(200)
  d2.getProcessor().setAutoThreshold("Otsu dark")
  IJ.run(d2, "Convert to Mask", ""); "binary=" + d2.getProcessor().isBinary()
}
step("S17 imp.duplicate() (no show) then close()") { def d3 = imp.duplicate(); d3.setTitle("p80-dup-unshown"); d3.close(); "closed" }
step("S18 close shown duplicate 'p80-mask' (changes=false; close())") { def d = WindowManager.getImage("p80-mask"); d.changes = false; d.close(); "closed" }
step("S19 IJ.setTool('oval') then IJ.setForegroundColor(255,0,0)") { IJ.setTool("oval"); IJ.setForegroundColor(255, 0, 0); IJ.setTool("rectangle"); "ok" }
step("S20 imp.setTitle('p80-renamed')") { imp.setTitle("p80-renamed") }
step("S21 ip.invert() + imp.updateAndDraw() (direct processor API)") { imp.getProcessor().invert(); imp.updateAndDraw() }

log("SUMMARY open images=" + WindowManager.getImageCount() + " Results rows=" + rtSize())
log("DONE -> marking images unchanged and Runtime.halt(0)")
for (int id : (WindowManager.getIDList() ?: new int[0])) { def i = WindowManager.getImage(id); if (i != null) i.changes = false }
new File(OUTDIR, "done.flag").text = "done"
Runtime.getRuntime().halt(0)
