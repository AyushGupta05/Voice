#@ Context context
// P87 - Swing (FlatLaf) JFrame + JavaFX JFXPanel/WebView + JS->Java bridge inside Fiji's GUI-mode JVM.
// Agent G8, Fiji Modernisation Project, 2026-10-17 (requested by the lead for A21). Launched by P85-gui-driver.ps1.
// Steps: (1) JFrame with the active look-and-feel, HiDPI transform, a JPanel painting imp.getBufferedImage();
// (2) JavaFX: module resolution of javafx.swing/javafx.web, JFXPanel in the JFrame, WebView.loadContent timing,
//     Prism pipeline (prism.verbose output tee'd to a file, plus Platform.isSupported(SCENE3D)), any
//     "Unsupported JavaFX configuration" warning; (3) JS -> Java callback via window.setMember bridge;
// (4) process working set (Get-Process via a child powershell) before/after JavaFX+WebView initialisation.
// No screenshots; the painted panel is verified by painting it into an off-screen BufferedImage.
// Termination: Runtime.halt(0) after closing everything (System.exit blocks in a shutdown hook, see P85).
import ij.*
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*
import java.lang.module.*

def OUTDIR = new File(System.getProperty("g8.out.dir"))
def OUT = new File(OUTDIR, "P87-swing-javafx-webview.out.txt")
OUT.text = ""
long T0 = System.currentTimeMillis()
def log = { String s -> synchronized (OUT) { OUT << String.format("%6d [%s] %s%n", System.currentTimeMillis() - T0, Thread.currentThread().getName(), s) } }
def wd = new Thread({ Thread.sleep(150000); log("WATCHDOG fired -> halt(3)"); Runtime.getRuntime().halt(3) } as Runnable, "G8-watchdog")
wd.setDaemon(true); wd.start()
while (IJ.getInstance() == null) Thread.sleep(50)
Thread.sleep(1500)

// tee System.out / System.err so that prism.verbose and JavaFX warnings are captured
def TEE = new File(OUTDIR, "P87-stdout-stderr-tee.txt"); TEE.text = ""
def teeOut = new FileOutputStream(TEE, true)
def mkTee = { PrintStream orig, String tag -> new PrintStream(new OutputStream() {
  void write(int b) { synchronized (teeOut) { teeOut.write(b) }; orig.write(b) }
  void write(byte[] b, int o, int l) { synchronized (teeOut) { teeOut.write(b, o, l); teeOut.flush() }; orig.write(b, o, l) }
}, true) }
def origOut = System.out, origErr = System.err
System.setOut(mkTee(origOut, "out")); System.setErr(mkTee(origErr, "err"))
System.setProperty("prism.verbose", "true")

long pid = ProcessHandle.current().pid()
def ws = { String tag ->
  def p = new ProcessBuilder("powershell", "-NoProfile", "-Command", "Get-Process -Id " + pid + " | Select-Object WorkingSet64,PrivateMemorySize64,PeakWorkingSet64 | ConvertTo-Csv -NoTypeInformation").redirectErrorStream(true).start()
  def txt = p.inputStream.text.trim(); p.waitFor()
  def r = Runtime.getRuntime()
  log("WS " + tag + ": pid=" + pid + " " + txt + " javaHeapUsed=" + (r.totalMemory() - r.freeMemory()) + " javaHeapCommitted=" + r.totalMemory())
}
def step = { String name, Closure c -> try { def r = c.call(); log("OK   " + name + (r == null ? "" : " -> " + r)); r } catch (Throwable t) { log("FAIL " + name + " -> " + t.getClass().getName() + ": " + t.getMessage() + (t.getCause() ? " | cause: " + t.getCause() : "")); null } }

log("START ij=" + IJ.getFullVersion() + " java=" + System.getProperty("java.version") + " pid=" + pid)
ws("baseline (Fiji UI up, before P87 work)")

// ---------------------------------------------------------------- (1) Swing + FlatLaf + ImagePlus painting
def imp = IJ.createImage("p87", "8-bit ramp", 320, 240, 1)
JFrame frame = null; JPanel panel = null
step("1a UIManager.getLookAndFeel()") { UIManager.getLookAndFeel().getClass().getName() + " name=" + UIManager.getLookAndFeel().getName() }
step("1b GraphicsConfiguration.getDefaultTransform() / normalizing transform") {
  def gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
  "default=" + gc.getDefaultTransform() + " normalizing=" + gc.getNormalizingTransform() + " bounds=" + gc.getBounds() + " sun.java2d.uiScale=" + System.getProperty("sun.java2d.uiScale")
}
step("1c JFrame + lightweight JPanel painting imp.getBufferedImage()") {
  SwingUtilities.invokeAndWait {
    frame = new JFrame("P87 probe frame")
    panel = new JPanel() { protected void paintComponent(Graphics g) { super.paintComponent(g); g.drawImage(imp.getBufferedImage(), 0, 0, null) } }
    panel.setPreferredSize(new Dimension(320, 240))
    frame.getContentPane().setLayout(new BorderLayout())
    frame.getContentPane().add(panel, BorderLayout.WEST)
    frame.setSize(760, 300); frame.setLocation(40, 40)
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
    frame.setVisible(true)
  }
  Thread.sleep(300)
  def bi = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
  SwingUtilities.invokeAndWait { def g = bi.createGraphics(); panel.paint(g); g.dispose() }
  "frame showing=" + frame.isShowing() + " rootPane UI=" + frame.getRootPane().getUI().getClass().getSimpleName() + " offscreen paint pixel(300,10)=0x" + Integer.toHexString(bi.getRGB(300, 10)) + " pixel(5,10)=0x" + Integer.toHexString(bi.getRGB(5, 10))
}

// ---------------------------------------------------------------- (2) JavaFX
step("2a boot layer modules javafx.*") { ModuleLayer.boot().modules().findAll { it.getName().startsWith("javafx") }.collect { it.getName() }.sort() }
step("2b javafx.scene.web.WebView class / module / code source") { def c = Class.forName("javafx.scene.web.WebView", false, ClassLoader.getSystemClassLoader()); c.getModule().toString() + " src=" + c.getProtectionDomain()?.getCodeSource()?.getLocation() }
def jfxPanelClass = step("2c Class.forName('javafx.embed.swing.JFXPanel') from the app class loader") { def c = Class.forName("javafx.embed.swing.JFXPanel", false, ClassLoader.getSystemClassLoader()); log("     JFXPanel module=" + c.getModule() + " src=" + c.getProtectionDomain()?.getCodeSource()?.getLocation()); c }
if (jfxPanelClass == null) {
  jfxPanelClass = step("2d define javafx.swing in a child ModuleLayer (jars/win64/javafx-swing-23.0.2-win.jar)") {
    def jar = new File(System.getProperty("fiji.dir") ?: "C:/Users/arnav/Documents/dev/Fiji", "jars/win64/javafx-swing-23.0.2-win.jar").toPath()
    def finder = ModuleFinder.of(jar)
    def cf = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), Set.of("javafx.swing"))
    def layer = ModuleLayer.boot().defineModulesWithOneLoader(cf, ClassLoader.getSystemClassLoader())
    def c = layer.findLoader("javafx.swing").loadClass("javafx.embed.swing.JFXPanel")
    log("     child layer module=" + c.getModule() + " descriptor requires=" + c.getModule().getDescriptor().requires().collect { it.name() })
    c
  }
}
def jfxPanel = null
long tFx0 = System.currentTimeMillis()
step("2e new JFXPanel() on the EDT (initialises the JavaFX toolkit) and add to the JFrame") {
  SwingUtilities.invokeAndWait { jfxPanel = jfxPanelClass.getDeclaredConstructor().newInstance(); jfxPanel.setPreferredSize(new Dimension(420, 240)); frame.getContentPane().add(jfxPanel, BorderLayout.CENTER); frame.validate() }
  "toolkit init ms=" + (System.currentTimeMillis() - tFx0)
}
def Platform = step("2f javafx.application.Platform") { Class.forName("javafx.application.Platform") }
step("2g Platform.isSupported(SCENE3D) (true only with a hardware Prism pipeline) / isFxApplicationThread") {
  def cf = Class.forName("javafx.application.ConditionalFeature")
  def r = [:]
  def latch = new java.util.concurrent.CountDownLatch(1)
  javafx.application.Platform.runLater { r.scene3d = javafx.application.Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D); r.effect = javafx.application.Platform.isSupported(javafx.application.ConditionalFeature.EFFECT); r.thread = Thread.currentThread().getName(); latch.countDown() }
  latch.await(10, java.util.concurrent.TimeUnit.SECONDS); r
}
step("2h Prism pipeline via com.sun.prism.GraphicsPipeline (reflection; package not exported to unnamed module)") {
  def c = Class.forName("com.sun.prism.GraphicsPipeline"); def m = c.getMethod("getPipeline"); def p = m.invoke(null); p?.getClass()?.getName()
}

// WebView load
class Bridge { java.util.List calls = Collections.synchronizedList(new ArrayList()); long t0; public void ping(String s) { calls << [msg: s, thread: Thread.currentThread().getName(), ms: System.currentTimeMillis() - t0] } }
def bridge = new Bridge(t0: System.currentTimeMillis())
def loadRes = [:]
def html = '''<html><body style="font-family:sans-serif"><h3 id="h">P87 WebView</h3>
<script>
  document.getElementById('h').textContent = 'P87 WebView ' + navigator.userAgent;
  setTimeout(function(){ if (window.bridge) { window.bridge.ping('timer-callback-from-page'); } }, 300);
</script></body></html>'''
def latch2 = new java.util.concurrent.CountDownLatch(1)
long tW0 = System.currentTimeMillis()
step("2i Platform.runLater: create WebView, Scene, set on JFXPanel, engine.loadContent(html)") {
  javafx.application.Platform.runLater {
    try {
      def wv = new javafx.scene.web.WebView()
      loadRes.createMs = System.currentTimeMillis() - tW0
      def eng = wv.getEngine()
      eng.getLoadWorker().stateProperty().addListener({ obs, o, n ->
        if (n.toString() == "SUCCEEDED") {
          loadRes.succeededMs = System.currentTimeMillis() - tW0
          try {
            def win = eng.executeScript("window")
            loadRes.windowClass = win.getClass().getName()
            win.setMember("bridge", bridge)
            loadRes.execResult = eng.executeScript("window.bridge.ping('direct-executeScript-call'); 'js-ok:' + typeof window.bridge")
            loadRes.ua = eng.getUserAgent()
          } catch (Throwable t) { loadRes.bridgeErr = t.toString() }
          latch2.countDown()
        } else if (n.toString() == "FAILED") { loadRes.failed = "" + eng.getLoadWorker().getException(); latch2.countDown() }
      } as javafx.beans.value.ChangeListener)
      jfxPanel.setScene(new javafx.scene.Scene(wv, 420, 240))
      eng.loadContent(html)
    } catch (Throwable t) { loadRes.err = t.toString(); latch2.countDown() }
  }
  latch2.await(60, java.util.concurrent.TimeUnit.SECONDS)
  Thread.sleep(1200)   // let the page's setTimeout callback run
  loadRes
}
step("3  JS -> Java bridge calls received by Java") { bridge.calls }
ws("after JavaFX toolkit + WebView load")

// ---------------------------------------------------------------- cleanup
step("9a dispose JFrame, Platform.exit()") {
  SwingUtilities.invokeAndWait { frame.dispose() }
  javafx.application.Platform.exit(); "ok"
}
Thread.sleep(500)
System.out.flush(); System.err.flush()
log("TEE excerpt (prism/javafx lines): " + TEE.readLines().findAll { it =~ /(?i)prism|javafx|d3d|direct3d|es2|sw pipeline|Unsupported|WARNING/ }.take(40))
log("DONE -> halt")
imp.changes = false
new File(OUTDIR, "done.flag").text = "done"
Runtime.getRuntime().halt(0)
