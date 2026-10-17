#@ Context context
// P84 - Pixel transfer and rendering cost in a GUI-mode Fiji JVM (relevant to an external UI).
// Agent G8, Fiji Modernisation Project, 2026-10-17. Launched by P85-gui-driver.ps1 (GUI mode, --run).
// For 2048x2048 8-, 16- and 32-bit images: getProcessor().getPixels() + copy into a direct little-endian
// java.nio.ByteBuffer; ImagePlus.getBufferedImage(); ImageProcessor.createImage(); PNG encoding (ImageIO) of the
// full 8-bit display image; PNG and JPEG encoding of a 1024x1024 viewport crop. 3 warm-up + 20 timed runs each;
// median and p95 (nearest-rank, 19th of 20 sorted values). Encodes into memory only; nothing is written to disk.
// Termination: Runtime.halt(0) (see P85).
import ij.*
import ij.process.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

def OUTDIR = new File(System.getProperty("g8.out.dir"))
def OUT = new File(OUTDIR, "P84-pixel-transfer-cost.out.txt")
OUT.text = ""
def log = { String s -> OUT << s + "\n" }
def wd = new Thread({ Thread.sleep(300000); log("WATCHDOG fired -> halt(3)"); Runtime.getRuntime().halt(3) } as Runnable, "G8-watchdog")
wd.setDaemon(true); wd.start()
while (IJ.getInstance() == null) Thread.sleep(50)
Thread.sleep(1500)
def rt = Runtime.getRuntime()
log("START ij=" + IJ.getFullVersion() + " java=" + System.getProperty("java.version") + " maxMemory=" + rt.maxMemory() + " totalMemory=" + rt.totalMemory() +
    " availableProcessors=" + rt.availableProcessors() + " JVM args(heap/gc)=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().findAll { it.startsWith("-Xm") || it.contains("GC") })
log("ImageIO writers: png=" + ImageIO.getImageWritersByFormatName("png").collect { it.getClass().getName() } + " jpeg=" + ImageIO.getImageWritersByFormatName("jpeg").collect { it.getClass().getName() })
try {
def stats = { List<Double> v -> def s = v.sort(false); [median: (s[9] + s[10]) / 2.0, p95: s[18], min: s[0], max: s[19]] }
def bench = { String label, Closure c ->
  for (int i = 0; i < 3; i++) c.call()
  def times = []
  def last = null
  for (int i = 0; i < 20; i++) { long t = System.nanoTime(); last = c.call(); times << (System.nanoTime() - t) / 1.0e6 }
  def s = stats(times)
  log(String.format("%-70s median=%8.2f ms  p95=%8.2f ms  min=%8.2f  max=%8.2f  %s", label, s.median, s.p95, s.min, s.max, last == null ? "" : "out=" + last))
}
def mk = { String type ->
  def imp = IJ.createImage("p84-" + type, type + " black", 2048, 2048, 1)
  def ip = imp.getProcessor()
  def rnd = new Random(7)
  // deterministic structured content (gradient + noise) so that PNG compression is not trivial
  for (int y = 0; y < 2048; y++) for (int x = 0; x < 2048; x += 1) ip.putPixelValue(x, y, ((x + y) % 256) * (type == "8-bit" ? 1 : type == "16-bit" ? 64 : 0.01) + rnd.nextInt(16))
  ip.resetMinAndMax()
  imp
}
for (String type : ["8-bit", "16-bit", "32-bit"]) {
  def imp = mk(type)
  def ip = imp.getProcessor()
  log("--- " + type + " 2048x2048, pixel array class=" + ip.getPixels().getClass().getSimpleName() + " bytes=" + (2048L * 2048 * imp.getBytesPerPixel()))
  int nBytes = 2048 * 2048 * imp.getBytesPerPixel()
  def bb = ByteBuffer.allocateDirect(nBytes).order(ByteOrder.LITTLE_ENDIAN)
  bench(type + " getPixels() + copy into direct ByteBuffer") {
    def px = imp.getProcessor().getPixels()
    bb.clear()
    if (px instanceof byte[]) bb.put((byte[]) px)
    else if (px instanceof short[]) bb.asShortBuffer().put((short[]) px)
    else if (px instanceof float[]) bb.asFloatBuffer().put((float[]) px)
    null
  }
  bench(type + " getPixels() only (reference, no copy)") { imp.getProcessor().getPixels(); null }
  bench(type + " ImagePlus.getBufferedImage()") { def bi = imp.getBufferedImage(); "type=" + bi.getType() }
  bench(type + " ImageProcessor.createImage() (AWT Image, 8-bit display LUT)") { def im = imp.getProcessor().createImage(); im.getClass().getSimpleName() }
  def biFull = imp.getBufferedImage()
  bench(type + " PNG encode full 2048x2048 display image (ImageIO)") { def bos = new ByteArrayOutputStream(); ImageIO.write(biFull, "png", bos); "bytes=" + bos.size() }
  def crop = biFull.getSubimage(512, 512, 1024, 1024)
  def cropCopy = new BufferedImage(1024, 1024, biFull.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_BYTE_GRAY : biFull.getType())
  cropCopy.createGraphics().drawImage(crop, 0, 0, null)
  bench(type + " getBufferedImage + 1024x1024 crop copy (viewport)") { def b = imp.getBufferedImage(); def c = new BufferedImage(1024, 1024, BufferedImage.TYPE_BYTE_GRAY); def g = c.createGraphics(); g.drawImage(b.getSubimage(512, 512, 1024, 1024), 0, 0, null); g.dispose(); null }
  bench(type + " PNG encode 1024x1024 viewport crop") { def bos = new ByteArrayOutputStream(); ImageIO.write(cropCopy, "png", bos); "bytes=" + bos.size() }
  bench(type + " JPEG encode 1024x1024 viewport crop (default quality)") { def bos = new ByteArrayOutputStream(); ImageIO.write(cropCopy, "jpeg", bos); "bytes=" + bos.size() }
  imp.close()
}
// RGB for completeness (colour composites/LUT-rendered views end up as int[] ARGB)
def rgb = IJ.createImage("p84-rgb", "RGB ramp", 2048, 2048, 1)
bench("RGB 2048x2048 getPixels() + copy into direct ByteBuffer (int[])") { def bb = ByteBuffer.allocateDirect(2048 * 2048 * 4).order(ByteOrder.LITTLE_ENDIAN); bb.asIntBuffer().put((int[]) rgb.getProcessor().getPixels()); null }
bench("RGB 1024x1024 crop PNG encode") { def c = rgb.getBufferedImage().getSubimage(512, 512, 1024, 1024); def bos = new ByteArrayOutputStream(); ImageIO.write(c, "png", bos); "bytes=" + bos.size() }
bench("RGB 1024x1024 crop JPEG encode") { def src = rgb.getBufferedImage().getSubimage(512, 512, 1024, 1024); def c = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB); c.createGraphics().drawImage(src, 0, 0, null); def bos = new ByteArrayOutputStream(); ImageIO.write(c, "jpeg", bos); "bytes=" + bos.size() }
} catch (Throwable t) {
  def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); log("SCRIPT THREW " + sw.toString().readLines().take(12).join(" | "))
}
log("END totalMemory=" + rt.totalMemory() + " free=" + rt.freeMemory())
new File(OUTDIR, "done.flag").text = "done"
Runtime.getRuntime().halt(0)
