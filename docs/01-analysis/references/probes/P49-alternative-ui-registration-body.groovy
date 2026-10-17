// P49 - (a) Does the non-IJ1 Swing SDI UI ("swing", org.scijava.ui.swing.sdi.SwingSDIUI) start in the pinned
// Fiji when selected with -Dscijava.ui=swing, and how does it display a Dataset?  (b) Can a new UserInterface
// implementation be registered at runtime (UIService.addUI) and shown?  Windows appear briefly; JVM halts at the end.
// Fiji Modernisation Project, agent G3. Output to scratch.
import java.awt.EventQueue
import org.scijava.ui.*
import org.scijava.display.Display
import org.scijava.ui.viewer.DisplayWindow

def SCR = "C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G3/out"
def OUT = new File(SCR, "P49-alternative-ui-registration.out.txt")
def ps = new PrintStream(new FileOutputStream(new File(SCR, "P49-stdout-stderr.txt")), true); System.setOut(ps); System.setErr(ps)
def lines = []
def rep = { String k, Object v -> lines << "${k} = ${v}"; OUT.text = lines.join("\n") + "\n" }
def chk = { String k, Closure c -> try { rep(k, c.call()) } catch (Throwable t) { rep(k, "THREW " + t.getClass().getName() + ": " + t.getMessage()) } }
def wd = new Thread({ Thread.sleep(120000); rep("Z9 watchdog", "halt"); Runtime.getRuntime().halt(4) } as Runnable, "P49-watchdog"); wd.setDaemon(true); wd.start()

System.setProperty("scijava.ui", "swing")
def ij = new net.imagej.ImageJ()
def ui = ij.ui()
def legacy = ij.get(net.imagej.legacy.LegacyService.class)
chk("A1 default UI with -Dscijava.ui=swing") { ui.getDefaultUI().getClass().getName() }
chk("A2 LegacyService active / legacyMode / IJ.getInstance()") { "" + legacy.isActive() + " / " + legacy.isLegacyMode() + " / " + Class.forName("ij.IJ").getMethod("getInstance").invoke(null) }
chk("A3 showUI(): swing app frame visible") {
  ui.showUI(); Thread.sleep(2000); def f = ui.getDefaultUI().getApplicationFrame(); f == null ? "no frame" : f.getClass().getName() + " visible=" + ui.getDefaultUI().isVisible()
}
chk("A4 IJ1 main window visible after swing showUI") { def m = Class.forName("ij.IJ").getMethod("getInstance").invoke(null); m == null ? "null" : "visible=" + m.isVisible() }
chk("A5 show Dataset in swing UI: viewer + window class") {
  def ds = ij.dataset().create([64L, 48L] as long[], "p49ds", [net.imagej.axis.Axes.X, net.imagej.axis.Axes.Y] as net.imagej.axis.AxisType[], 8, false, false)
  ui.show("p49ds", ds); Thread.sleep(1500)
  def disp = ij.display().getDisplay("p49ds"); def v = disp == null ? null : ui.getDisplayViewer(disp)
  "display=" + disp?.getClass()?.getName() + " viewer=" + v?.getClass()?.getName() + " window=" + v?.getWindow()?.getClass()?.getName()
}
chk("A6 legacy commands registered as modules") { ij.module().getModules().count { it.getClass().getName() == "net.imagej.legacy.command.LegacyCommandInfo" } }
chk("A7 run IJ1 command via LegacyService while swing UI is default (Invert on WindowManager image)") {
  def IJ = Class.forName("ij.IJ"); def imp = IJ.getMethod("createImage", String, String, int, int, int).invoke(null, "p49imp", "8-bit ramp", 32, 32, 1)
  def before = imp.getStatistics().mean; IJ.getMethod("run", Class.forName("ij.ImagePlus"), String, String).invoke(null, imp, "Invert", ""); "" + before + " -> " + imp.getStatistics().mean
}

// (b) runtime registration of a custom UserInterface
class ProbeUI extends AbstractUserInterface {
  boolean created = false
  @Override protected void createUI() { created = true }
  @Override DisplayWindow createDisplayWindow(Display<?> d) { null }
  @Override DialogPrompt dialogPrompt(String m, String t, DialogPrompt.MessageType mt, DialogPrompt.OptionType ot) { null }
  @Override void showContextMenu(String r, Display<?> d, int x, int y) {}
  @Override boolean requiresEDT() { false }
}
def probe = new ProbeUI()
chk("B1 inject context + UIService.addUI(\"probe-ui\", ...)") { ij.context().inject(probe); ui.addUI("probe-ui", probe); ui.getUI("probe-ui")?.getClass()?.getName() }
chk("B2 available UIs") { ui.getAvailableUIs().collect { it.getClass().getSimpleName() }.join(", ") }
chk("B3 showUI(\"probe-ui\") -> createUI called / visible") { ui.showUI("probe-ui"); "created=" + probe.created + " visible=" + probe.isVisible() }
chk("B4 setDefaultUI(probe) -> getDefaultUI") { ui.setDefaultUI(probe); ui.getDefaultUI().getClass().getSimpleName() }
rep("Z0 done", "halt(0)")
Runtime.getRuntime().halt(0)
