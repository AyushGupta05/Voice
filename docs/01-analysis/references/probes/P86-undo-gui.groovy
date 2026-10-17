#@ Context context
// P86 - Undo behaviour when Fiji (GUI mode, non-headless) is driven from a script.
// Agent G8, Fiji Modernisation Project, 2026-10-17. Launched by P85-gui-driver.ps1 (GUI mode, --run).
// Undo is ImageJ 1.x's single global buffer (ij/Undo.java), keyed by image ID and WindowManager.getCurrentImage().
// Each case compares a CRC32 of the pixel array before the operation, after it, and after Undo.
// Synthetic images only; nothing is saved. Termination: Runtime.halt(0) (see P85).
import ij.*
import ij.gui.*
import ij.macro.Interpreter
import java.util.zip.CRC32
import javax.swing.SwingUtilities

def OUTDIR = new File(System.getProperty("g8.out.dir"))
def OUT = new File(OUTDIR, "P86-undo-gui.out.txt")
OUT.text = ""
long T0 = System.currentTimeMillis()
def log = { String s -> synchronized (OUT) { OUT << String.format("%6d [%s] %s%n", System.currentTimeMillis() - T0, Thread.currentThread().getName(), s) } }
def wd = new Thread({ Thread.sleep(150000); log("WATCHDOG fired -> halt(3)"); Runtime.getRuntime().halt(3) } as Runnable, "G8-watchdog")
wd.setDaemon(true); wd.start()
while (IJ.getInstance() == null) Thread.sleep(50)
Thread.sleep(1500)
log("START ij=" + IJ.getFullVersion() + " Prefs.disableUndo=" + Prefs.disableUndo + " Prefs.supportMacroUndo=" + Prefs.supportMacroUndo)
try {
def crc = { ImagePlus imp -> def c = new CRC32(); c.update((byte[]) imp.getProcessor().getPixels()); Long.toHexString(c.getValue()) }
def mk = { String t -> def ip = new ij.process.ByteProcessor(256, 256); def r = new Random(t.hashCode()); for (int i = 0; i < 256 * 256; i++) ip.set(i, r.nextInt(256)); new ImagePlus(t, ip) }
def undoState = { def f = Undo.class.getDeclaredField("whatToUndo"); f.setAccessible(true); def g = Undo.class.getDeclaredField("imageID"); g.setAccessible(true); "whatToUndo=" + f.get(null) + " undoImageID=" + g.get(null) }
def kase = { String label, ImagePlus imp, Closure op, Closure undo ->
  String c0 = crc(imp)
  op.call()
  String c1 = crc(imp)
  String st = undoState()
  undo.call()
  Thread.sleep(150)
  String c2 = crc(imp)
  log(String.format("%-78s before=%s after=%s afterUndo=%s  changed=%s restored=%s  [%s; current=%s id=%d]", label, c0, c1, c2, c0 != c1, c0 == c2, st,
      WindowManager.getCurrentImage()?.getTitle(), imp.getID()))
  [c0, c1, c2]
}

def a = mk("p86-A"); a.show(); Thread.sleep(400)
def b = mk("p86-B"); b.show(); Thread.sleep(400)
IJ.selectWindow("p86-A"); Thread.sleep(300)
log("current after selectWindow(A)=" + WindowManager.getCurrentImage()?.getTitle())

kase("U1 shown A: IJ.run(a,'Invert','') ; Undo.undo() from script thread", a, { IJ.run(a, "Invert", "") }, { Undo.undo() })
kase("U2 shown A: second Undo.undo() straight after U1 (redo toggle?)", a, { }, { Undo.undo() })
kase("U3 shown A: IJ.run(a,'Gaussian Blur...','sigma=2') ; Undo.undo()", a, { IJ.run(a, "Gaussian Blur...", "sigma=2") }, { Undo.undo() })
kase("U4 shown A: Invert ; IJ.run('Undo') (macro path: IJ.macroRunning()=true)", a, { IJ.run(a, "Invert", "") }, { IJ.run("Undo") })
kase("U5 shown A: Invert ; IJ.runMacro('run(\"Undo\");')", a, { IJ.run(a, "Invert", "") }, { IJ.runMacro('run("Undo");') })
kase("U6 shown A: Invert then Gaussian ; one Undo.undo() (single level?)", a, { IJ.run(a, "Invert", ""); IJ.run(a, "Gaussian Blur...", "sigma=2") }, { Undo.undo() })
// U6b: what does the image equal after that one undo?
def a0 = mk("p86-A-ref"); IJ.run(a0, "Invert", "")
log("U6b after one undo, A equals 'inverted original'? " + (crc(a) == crc(a0)) + " (CRC of reference inverted original=" + crc(a0) + ")")
IJ.run(a, "Invert", "")  // bring A back to (approximately) its original for following cases is not needed; cases are relative

// switching the current image between the operation and Undo
kase("U7 Invert A ; IJ.selectWindow(B) ; IJ.selectWindow(A) ; Undo.undo()", a, { IJ.run(a, "Invert", ""); IJ.selectWindow("p86-B"); Thread.sleep(300); IJ.selectWindow("p86-A"); Thread.sleep(300) }, { Undo.undo() })
String bBefore = crc(b)
kase("U8 Invert A ; WindowManager.setCurrentWindow(B.win) ; Undo.undo() (applies to B?)", a, { IJ.run(a, "Invert", ""); SwingUtilities.invokeAndWait { WindowManager.setCurrentWindow(b.getWindow()) }; Thread.sleep(200) }, { Undo.undo() })
log("U8b B changed by that Undo? before=" + bBefore + " after=" + crc(b) + " changed=" + (bBefore != crc(b)))
SwingUtilities.invokeAndWait { WindowManager.setCurrentWindow(a.getWindow()) }; Thread.sleep(200)
kase("U9 IJ.run(a,'Invert') with WindowManager.setTempCurrentImage(a) around Undo.undo()", a, { IJ.run(a, "Invert", "") }, { WindowManager.setTempCurrentImage(a); Undo.undo(); WindowManager.setTempCurrentImage(null) })

// hidden window
def h = mk("p86-hidden"); h.show(); Thread.sleep(300)
SwingUtilities.invokeAndWait { h.getWindow().setVisible(false) }
log("hidden: current=" + WindowManager.getCurrentImage()?.getTitle())
kase("U10 hidden-window image: IJ.run(h,'Invert','') ; Undo.undo()", h, { IJ.run(h, "Invert", "") }, { Undo.undo() })
kase("U11 hidden-window image: Invert ; setTempCurrentImage(h) ; Undo.undo()", h, { IJ.run(h, "Invert", "") }, { WindowManager.setTempCurrentImage(h); Undo.undo(); WindowManager.setTempCurrentImage(null) })

// batch mode image
Interpreter.batchMode = true
def bm = mk("p86-batch"); bm.show()
log("batch: current=" + WindowManager.getCurrentImage()?.getTitle() + " window=" + bm.getWindow())
kase("U12 batch-mode image (Interpreter.batchMode=true, shown): Invert ; Undo.undo()", bm, { IJ.run(bm, "Invert", "") }, { Undo.undo() })
kase("U13 batch-mode image: Gaussian ; Undo.undo()", bm, { IJ.run(bm, "Gaussian Blur...", "sigma=2") }, { Undo.undo() })
Interpreter.batchMode = false
// never-shown image: Undo targets the *current* image instead
def u = mk("p86-unshown")
String aBefore = crc(a)
SwingUtilities.invokeAndWait { WindowManager.setCurrentWindow(a.getWindow()) }; Thread.sleep(200)
kase("U14 never-shown image: IJ.run(u,'Invert','') ; Undo.undo()", u, { IJ.run(u, "Invert", "") }, { Undo.undo() })
log("U14b foreground A modified by that Undo? before=" + aBefore + " after=" + crc(a) + " changed=" + (aBefore != crc(a)))
kase("U15 never-shown image: Invert ; setTempCurrentImage(u) ; Undo.undo()", u, { IJ.run(u, "Invert", "") }, { WindowManager.setTempCurrentImage(u); Undo.undo(); WindowManager.setTempCurrentImage(null) })
// macro-language Undo inside one macro
kase("U16 IJ.runMacro('selectImage(\"p86-A\"); run(\"Invert\"); run(\"Undo\");') (measured around whole macro)", a, { IJ.runMacro('selectImage("p86-A"); run("Invert"); run("Undo");') }, { })
} catch (Throwable t) {
  def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); log("SCRIPT THREW " + sw.toString().readLines().take(12).join(" | "))
}
log("DONE -> halt")
for (int id : (WindowManager.getIDList() ?: new int[0])) { def i = WindowManager.getImage(id); if (i != null) i.changes = false }
new File(OUTDIR, "done.flag").text = "done"
Runtime.getRuntime().halt(0)
