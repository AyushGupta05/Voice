// P46 - Headless follow-ups to P41/P43 (Fiji Modernisation Project, agent G3).
//  (a) why roiManager("add") aborts in a headless macro (P41 G4);
//  (b) why "Bio-Formats Exporter" with save=[...] produced no file headless (P43 S3);
//  (c) which non-daemon threads remain alive after a --run script ends (P41 did not exit).
// Run: fiji-windows-x64.exe --java-home=<bundled JDK> --allow-multiple --headless --run <this file>
// System.out/System.err are redirected to scratch files so that IJ.log/IJ.error text is captured.
// This script deliberately does NOT call System.exit, to observe whether the launcher terminates.
import ij.*
import ij.gui.*

def SCR = "C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G3/out"
def OUT = new File(SCR, "P46-headless-followups.out.txt")
def CAP = new File(SCR, "P46-stdout-stderr.txt")
def ps = new PrintStream(new FileOutputStream(CAP), true)
System.setOut(ps); System.setErr(ps)
def lines = []
def rep = { String k, Object v -> lines << "${k} = ${v}"; OUT.text = lines.join("\n") + "\n" }
def chk = { String k, Closure c -> try { rep(k, c.call()) } catch (Throwable t) { rep(k, "THREW " + t.getClass().getName() + ": " + t.getMessage()) } }

chk("a1 macro roiManager(add) headless, return value") {
  IJ.runMacro('setBatchMode(true); newImage("m", "8-bit black", 32, 32, 1); makeRectangle(2,2,10,10); roiManager("add"); return "" + roiManager("count");')
}
chk("a2 macro Overlay.addSelection headless") {
  IJ.runMacro('newImage("o", "8-bit black", 32, 32, 1); makeRectangle(2,2,10,10); Overlay.addSelection; makeOval(5,5,9,9); Overlay.addSelection; return "" + Overlay.size;')
}
def work = new File(SCR, "P46-work"); work.mkdirs()
def imp = IJ.createHyperStack("p46", 32, 24, 2, 3, 1, 16)
def ome1 = new File(work, "a.ome.tif").getPath()
def ome2 = new File(work, "b.ome.tif").getPath()
chk("b1 IJ.run(imp, Bio-Formats Exporter, save=[..] compression=Uncompressed)") {
  IJ.run(imp, "Bio-Formats Exporter", "save=[" + ome1 + "] compression=Uncompressed"); "exists=" + new File(ome1).exists() + " size=" + new File(ome1).length()
}
chk("b2 IJ.run(imp, Bio-Formats Exporter, save=.. export compression=Uncompressed) with image shown") {
  imp.show(); IJ.run(imp, "Bio-Formats Exporter", "save=" + ome2 + " export compression=Uncompressed"); "exists=" + new File(ome2).exists() + " size=" + new File(ome2).length()
}
chk("b3 reopen b via Bio-Formats") {
  if (!new File(ome2).exists()) return "skipped"
  def o = new loci.plugins.in.ImporterOptions(); o.setId(ome2); def r = loci.plugins.BF.openImagePlus(o); "dims=" + r[0].getDimensions().join("x")
}
chk("c1 live non-daemon threads at end of script body") {
  Thread.getAllStackTraces().keySet().findAll { !it.isDaemon() && it.isAlive() }.collect { it.getName() + "[" + it.getState() + "]" }.sort().join(", ")
}
rep("c2 script end", new Date().toString())
// Background observer: records non-daemon threads 20 s after the script returned.
def obs = new Thread({
  Thread.sleep(20000)
  lines << ("c3 non-daemon threads 20 s after script end = " +
    Thread.getAllStackTraces().keySet().findAll { !it.isDaemon() && it.isAlive() }.collect { it.getName() + "[" + it.getState() + "]" }.sort().join(", "))
  def main = Thread.getAllStackTraces().find { it.key.getName() == "main" }
  if (main != null) lines << ("c4 main thread stack top = " + main.value.take(8).join(" <- "))
  OUT.text = lines.join("\n") + "\n"
} as Runnable, "P46-observer")
obs.setDaemon(true); obs.start()
