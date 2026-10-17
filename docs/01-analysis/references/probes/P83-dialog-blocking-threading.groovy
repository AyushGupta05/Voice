#@ Context context
// P83 - Dialog blocking and threading in a GUI-mode (non-headless) Fiji JVM.
// Agent G8, Fiji Modernisation Project, 2026-10-17. Launched by P85-gui-driver.ps1 (GUI mode, --run).
// D1-D4: a dialog command is called WITHOUT options from a non-EDT worker thread; the probe detects the
//        GenericDialog, reads its fields, sets values and presses OK/Cancel from code.
// E1-E3: IJ.run called ON the EDT (with options, and without options -> modal dialog from the EDT).
// Synthetic images only; nothing is saved. Termination: Runtime.halt(0) (see P85).
import ij.*
import ij.gui.*
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.SwingUtilities

def OUTDIR = new File(System.getProperty("g8.out.dir"))
def OUT = new File(OUTDIR, "P83-dialog-blocking-threading.out.txt")
OUT.text = ""
long T0 = System.currentTimeMillis()
def log = { String s -> synchronized (OUT) { OUT << String.format("%6d [%s] edt=%s %s%n", System.currentTimeMillis() - T0, Thread.currentThread().getName(), EventQueue.isDispatchThread(), s) } }
def wd = new Thread({ Thread.sleep(200000); log("WATCHDOG fired -> halt(3)"); Runtime.getRuntime().halt(3) } as Runnable, "G8-watchdog")
wd.setDaemon(true); wd.start()
while (IJ.getInstance() == null) Thread.sleep(50)
Thread.sleep(1500)
log("START ij=" + IJ.getFullVersion() + " Prefs.nonBlockingFilterDialogs=" + Prefs.nonBlockingFilterDialogs)
try {
def imp = IJ.createImage("p83", "8-bit ramp", 256, 256, 1)
imp.show(); Thread.sleep(400)

def findGD = { long ms ->
  long t = System.currentTimeMillis()
  while (System.currentTimeMillis() - t < ms) {
    def w = Window.getWindows().find { it instanceof GenericDialog && it.isShowing() }
    if (w != null) return w
    Thread.sleep(25)
  }
  null
}
def describe = { GenericDialog gd ->
  def frames = Frame.getFrames().collect { it.getTitle() }
  def owned = Frame.getFrames().collectMany { f -> f.getOwnedWindows().findAll { it.isShowing() }.collect { (it instanceof Dialog ? it.getTitle() : "?") + "<" + it.getClass().getSimpleName() + ">" } }
  log("  detected via Window.getWindows(): '" + gd.getTitle() + "' class=" + gd.getClass().getName() + " modal=" + gd.isModal() + " owner=" + gd.getOwner()?.getClass()?.getSimpleName())
  log("  WindowManager.getActiveWindow()=" + WindowManager.getActiveWindow()?.getClass()?.getSimpleName() + " WindowManager.getFrontWindow()=" + WindowManager.getFrontWindow()?.getTitle() +
      " WindowManager.getWindow(title)=" + WindowManager.getWindow(gd.getTitle()) + " GenericDialog.getInstance()=" + GenericDialog.getInstance()?.getTitle())
  log("  Frame.getFrames() titles=" + frames + " ; showing windows owned by frames=" + owned)
  log("  numericFields=" + gd.getNumericFields()?.collect { it.getText() } + " stringFields=" + gd.getStringFields()?.collect { it.getText() } +
      " checkboxes=" + gd.getCheckboxes()?.collect { it.getLabel() + "=" + it.getState() } + " choices=" + gd.getChoices()?.collect { it.getSelectedItem() } +
      " buttons=" + gd.getButtons().collect { it?.getLabel() })
}
def worker = { String name, Closure c ->
  def res = [:]
  def th = new Thread({ long t = System.currentTimeMillis(); try { c.call(); res.ok = true } catch (Throwable e) { res.err = e.getClass().getName() + ": " + e.getMessage() }; res.ms = System.currentTimeMillis() - t } as Runnable, name)
  th.start(); [th, res]
}

// D1: Gaussian Blur without options from a non-EDT thread; OK pressed via gd.actionPerformed on the EDT
int p0 = imp.getProcessor().get(10, 10)
def (t1, r1) = worker("G8-worker-D1") { IJ.run(imp, "Gaussian Blur...", "") }
def gd1 = findGD(5000)
log("D1 IJ.run(imp,'Gaussian Blur...','') on non-EDT worker: worker alive (blocked)=" + t1.isAlive() + " dialog found=" + (gd1 != null))
if (gd1 != null) {
  describe(gd1)
  SwingUtilities.invokeAndWait { gd1.getNumericFields()[0].setText("4") }
  SwingUtilities.invokeAndWait { gd1.actionPerformed(new ActionEvent(gd1.getButtons()[0], ActionEvent.ACTION_PERFORMED, "OK")) }
  t1.join(10000)
  log("D1 after OK: worker alive=" + t1.isAlive() + " result=" + r1 + " wasOKed=" + gd1.wasOKed() + " pixel(10,10) " + p0 + " -> " + imp.getProcessor().get(10, 10))
}

// D2: Subtract Background without options; radius set; OK pressed by Button.dispatchEvent posted to the EDT
def (t2, r2) = worker("G8-worker-D2") { IJ.run(imp, "Subtract Background...", "") }
def gd2 = findGD(5000)
log("D2 IJ.run(imp,'Subtract Background...','') on non-EDT worker: blocked=" + t2.isAlive() + " dialog found=" + (gd2 != null))
if (gd2 != null) {
  describe(gd2)
  SwingUtilities.invokeAndWait { gd2.getNumericFields()[0].setText("25") }
  def ok = gd2.getButtons()[0]
  EventQueue.invokeLater { ok.dispatchEvent(new ActionEvent(ok, ActionEvent.ACTION_PERFORMED, ok.getActionCommand())) }
  t2.join(10000)
  log("D2 after Button.dispatchEvent(ActionEvent): worker alive=" + t2.isAlive() + " result=" + r2 + " wasOKed=" + gd2.wasOKed())
}

// D3: Cancel instead of OK
def (t3, r3) = worker("G8-worker-D3") { IJ.run(imp, "Gaussian Blur...", ""); "returned" }
def gd3 = findGD(5000)
if (gd3 != null) {
  SwingUtilities.invokeAndWait { gd3.actionPerformed(new ActionEvent(gd3.getButtons()[1], ActionEvent.ACTION_PERFORMED, "Cancel")) }
  t3.join(10000)
  log("D3 Cancel pressed from code: worker alive=" + t3.isAlive() + " result=" + r3 + " wasCanceled=" + gd3.wasCanceled() + " Macro.abort? IJ status")
}

// D4: setVisible(false)/dispose() of the dialog without pressing a button
def (t4, r4) = worker("G8-worker-D4") { IJ.run(imp, "Gaussian Blur...", "") }
def gd4 = findGD(5000)
if (gd4 != null) {
  SwingUtilities.invokeAndWait { gd4.dispose() }
  t4.join(10000)
  log("D4 gd.dispose() from code (no button): worker alive=" + t4.isAlive() + " result=" + r4 + " wasOKed=" + gd4.wasOKed() + " wasCanceled=" + gd4.wasCanceled())
}

// D5: script thread itself blocks: is the calling thread released only by the dialog? (timed by a helper that presses OK)
def helper = new Thread({ def g = findGD(5000); if (g != null) { Thread.sleep(1000); SwingUtilities.invokeAndWait { g.actionPerformed(new ActionEvent(g.getButtons()[0], ActionEvent.ACTION_PERFORMED, "OK")) } } } as Runnable, "G8-helper-D5")
helper.start()
long td5 = System.currentTimeMillis()
IJ.run(imp, "Gaussian Blur...", "")
log("D5 IJ.run(imp,'Gaussian Blur...','') on the script thread returned after " + (System.currentTimeMillis() - td5) + " ms (helper pressed OK after ~1000 ms)")

// E1: IJ.run on the EDT with full options
def e1 = [:]
SwingUtilities.invokeAndWait { long t = System.currentTimeMillis(); try { IJ.run(imp, "Gaussian Blur...", "sigma=1"); e1.ok = true } catch (Throwable e) { e1.err = "" + e }; e1.ms = System.currentTimeMillis() - t; e1.name = Thread.currentThread().getName() }
log("E1 IJ.run(imp,'Gaussian Blur...','sigma=1') inside invokeAndWait on EDT: " + e1)

// E2: IJ.run on the EDT with a command that repaints/updates via invokeAndWait internally (Duplicate shows a new window)
def e2 = [:]
SwingUtilities.invokeAndWait { long t = System.currentTimeMillis(); try { IJ.run(imp, "Duplicate...", "title=p83-dup"); e2.ok = true } catch (Throwable e) { e2.err = "" + e }; e2.ms = System.currentTimeMillis() - t }
log("E2 IJ.run(imp,'Duplicate...','title=p83-dup') on EDT: " + e2 + " dupShown=" + (WindowManager.getImage("p83-dup")?.getWindow()?.isShowing()))

// E3: IJ.run on the EDT WITHOUT options -> modal dialog opened from the EDT (secondary event loop)
def e3 = [done: false]
EventQueue.invokeLater { long t = System.currentTimeMillis(); try { IJ.run(imp, "Gaussian Blur...", ""); e3.ok = true } catch (Throwable e) { e3.err = "" + e }; e3.ms = System.currentTimeMillis() - t; e3.done = true }
def gd5 = findGD(5000)
log("E3 IJ.run(imp,'Gaussian Blur...','') invoked on EDT: dialog found=" + (gd5 != null))
if (gd5 != null) {
  def probe = [ran: false]
  long tq = System.currentTimeMillis()
  SwingUtilities.invokeAndWait { probe.ran = true }   // does the EDT still process events while the modal dialog is open?
  log("E3 EDT still dispatching while modal dialog open: " + probe.ran + " (invokeAndWait returned in " + (System.currentTimeMillis() - tq) + " ms)")
  SwingUtilities.invokeAndWait { gd5.actionPerformed(new ActionEvent(gd5.getButtons()[0], ActionEvent.ACTION_PERFORMED, "OK")) }
  long tw = System.currentTimeMillis(); while (!e3.done && System.currentTimeMillis() - tw < 10000) Thread.sleep(20)
  log("E3 after OK: " + e3)
}
log("EDT thread name now: " + { def n; SwingUtilities.invokeAndWait { n = Thread.currentThread().getName() }; n }.call())
} catch (Throwable t) {
  def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); log("SCRIPT THREW " + sw.toString().readLines().take(12).join(" | "))
}
log("DONE -> halt")
for (int id : (WindowManager.getIDList() ?: new int[0])) { def i = WindowManager.getImage(id); if (i != null) i.changes = false }
new File(OUTDIR, "done.flag").text = "done"
Runtime.getRuntime().halt(0)
