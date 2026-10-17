// P85 - GUI-mode Fiji JVM start-up time and footprint (agent G8, Fiji Modernisation Project, 2026-10-17).
// Launched by P85-gui-driver.ps1, which records the process launch time (epoch ms) in
// <out>/launch-epoch-ms.txt and samples Get-Process when this script writes <out>/ready.flag.
// Writes only into the directory given by -Dg8.out.dir. Exits with System.exit(0).
import ij.IJ
import java.awt.EventQueue
import java.lang.management.ManagementFactory

def t0 = System.currentTimeMillis()
def OUTDIR = new File(System.getProperty("g8.out.dir"))
def OUT = new File(OUTDIR, "P85-gui-startup-footprint.out.txt")
def lines = []
def rep = { String k, Object v -> lines << ("" + k + " = " + v); OUT.text = lines.join("\n") + "\n" }
def wd = new Thread({ Thread.sleep(120000); rep("Z9 watchdog", "fired; System.exit(3)"); System.exit(3) } as Runnable, "G8-watchdog")
wd.setDaemon(true); wd.start()

def launch = new File(OUTDIR, "launch-epoch-ms.txt")
long launchMs = launch.exists() ? Long.parseLong(launch.text.trim()) : -1L
def rt = ManagementFactory.getRuntimeMXBean()
rep("A0 script first statement epochMs", t0)
rep("A1 driver launch epochMs", launchMs)
rep("A2 launch -> script start ms", launchMs > 0 ? (t0 - launchMs) : "n/a")
rep("A3 JVM start epochMs (RuntimeMXBean.getStartTime)", rt.getStartTime())
rep("A4 launch -> JVM start ms", launchMs > 0 ? (rt.getStartTime() - launchMs) : "n/a")
rep("A5 JVM uptime at script start ms", rt.getUptime())
rep("A6 script thread / isEDT", Thread.currentThread().getName() + " / " + EventQueue.isDispatchThread())
rep("A7 java.awt.headless / GraphicsEnvironment.isHeadless", System.getProperty("java.awt.headless") + " / " + java.awt.GraphicsEnvironment.isHeadless())
rep("A8 IJ.getInstance() at script start", IJ.getInstance())
while (IJ.getInstance() == null && System.currentTimeMillis() - t0 < 60000) Thread.sleep(20)
def t1 = System.currentTimeMillis()
rep("B1 IJ.getInstance()!=null at epochMs", t1)
rep("B2 launch -> IJ.getInstance()!=null ms", launchMs > 0 ? (t1 - launchMs) : "n/a")
rep("B3 IJ.getInstance() visible", IJ.getInstance()?.isVisible())
rep("B4 ij version / java", IJ.getFullVersion() + " / " + System.getProperty("java.version"))
rep("B5 JVM input arguments (heap-related)", rt.getInputArguments().findAll { it.startsWith("-Xm") || it.contains("GC") })
def r = Runtime.getRuntime()
System.gc(); Thread.sleep(500)
rep("C1 Runtime.maxMemory bytes", r.maxMemory())
rep("C2 Runtime.totalMemory bytes", r.totalMemory())
rep("C3 Runtime.freeMemory bytes", r.freeMemory())
rep("C4 used heap after System.gc bytes", r.totalMemory() - r.freeMemory())
rep("C5 availableProcessors", r.availableProcessors())
rep("C6 live thread count", ManagementFactory.getThreadMXBean().getThreadCount())
rep("C7 loaded class count", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount())
rep("C8 IJ.maxMemory() / IJ.freeMemory()", IJ.maxMemory() + " / " + IJ.freeMemory())
def nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage()
rep("C9 non-heap used / committed bytes", nonHeap.getUsed() + " / " + nonHeap.getCommitted())
rep("D1 visible windows", java.awt.Window.getWindows().findAll { it.isShowing() }.collect { (it.hasProperty("title") ? it.title : "") + "<" + it.getClass().getName() + ">" })

new File(OUTDIR, "ready.flag").text = "ready"
def tw = System.currentTimeMillis()
while (!new File(OUTDIR, "measured.flag").exists() && System.currentTimeMillis() - tw < 20000) Thread.sleep(50)
rep("E1 driver measured.flag seen", new File(OUTDIR, "measured.flag").exists())
rep("Z0 done", "Runtime.getRuntime().halt(0)")
new File(OUTDIR, "done.flag").text = "done"
// Runs 1 and 2 (2026-10-17 08:39, 08:42) ended with System.exit(0): it blocked indefinitely inside a shutdown hook
// (org.scijava.Context dispose -> LegacyUI.dispose -> IJ1Helper.dispose -> ij.ImageJ.run -> Window.dispose ->
// EventQueue.invokeAndWait; see P85-run2-jstack.txt), and ImageJ.run() had already written
// %USERPROFILE%\.imagej\IJ_Prefs.txt via Prefs.savePreferences(). From run 3 on every G8 probe terminates with
// Runtime.halt(0), which skips shutdown hooks: no preference save, no "save changes?" dialog.
Runtime.getRuntime().halt(0)
