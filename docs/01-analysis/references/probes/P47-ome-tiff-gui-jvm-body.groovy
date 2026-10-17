// P47 - OME-TIFF export/import fidelity in a NON-headless JVM with no UI shown (Fiji Modernisation Project, G3).
// Follow-up to P43/P46, where "Bio-Formats Exporter" failed headless (NPE: "checkboxes" is null).
// Same reference image as P43. Watchdog halts the JVM after 120 s. Files go to scratch only.
import ij.*
import ij.gui.*
import ij.process.*
import loci.plugins.BF
import loci.plugins.in.ImporterOptions

def SCR = "C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G3/out"
def WORK = new File(SCR, "P47-work"); WORK.mkdirs()
def OUT = new File(SCR, "P47-ome-tiff-gui-jvm.out.txt")
def ps = new PrintStream(new FileOutputStream(new File(SCR, "P47-stdout-stderr.txt")), true); System.setOut(ps); System.setErr(ps)
def lines = []
def rep = { String k, Object v -> lines << "${k} = ${v}"; OUT.text = lines.join("\n") + "\n" }
def chk = { String k, Closure c -> try { rep(k, c.call()) } catch (Throwable t) { rep(k, "THREW " + t.getClass().getName() + ": " + t.getMessage()) } }
def wd = new Thread({ Thread.sleep(120000); rep("Z9 watchdog", "halt"); Runtime.getRuntime().halt(4) } as Runnable, "P47-watchdog"); wd.setDaemon(true); wd.start()

def ij = new net.imagej.ImageJ()
def W = 64, H = 48, C = 3, Z = 4, T = 2
def imp = IJ.createHyperStack("p47ref", W, H, C, Z, T, 16)
for (int t = 1; t <= T; t++) for (int z = 1; z <= Z; z++) for (int c = 1; c <= C; c++) {
  def ip = imp.getStack().getProcessor(imp.getStackIndex(c, z, t))
  for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) ip.set(x, y, c * 1000 + z * 100 + t * 10 + (x % 10) + 3000 * (y % 2))
}
def cal = imp.getCalibration(); cal.pixelWidth = 0.25; cal.pixelHeight = 0.25; cal.pixelDepth = 1.5; cal.setUnit("micron"); cal.frameInterval = 2.5; cal.setTimeUnit("sec")
imp = new CompositeImage(imp, IJ.COMPOSITE); imp.setCalibration(cal)
[[1000, 4500], [2000, 5500], [3000, 6500]].eachWithIndex { r, i -> imp.setC(i + 1); imp.setDisplayRange(r[0], r[1]) }
imp.setC(1)
imp.setProperty("Info", "P47 info line\nkey=value")
def ov = new Overlay(); ov.add(new Roi(5, 5, 20, 10)); ov.add(new OvalRoi(30, 10, 12, 12)); imp.setOverlay(ov)
def checksum = { ImagePlus im -> double s = 0; def st = im.getStack(); for (int i = 1; i <= st.getSize(); i++) { def p = st.getProcessor(i); for (int k = 0; k < p.getPixelCount(); k++) s += p.getf(k) }; s }
def describe = { ImagePlus im ->
  if (im == null) return "null"
  def c2 = im.getCalibration(); def ranges = []
  if (im.isComposite()) { for (int i = 1; i <= im.getNChannels(); i++) { def l = ((CompositeImage) im).getChannelLut(i); ranges << ("" + (int) l.min + "-" + (int) l.max) } }
  else ranges << ("" + (int) im.getDisplayRangeMin() + "-" + (int) im.getDisplayRangeMax())
  "dims(xyczt)=" + im.getDimensions().join("x") + " bitDepth=" + im.getBitDepth() + " hyperstack=" + im.isHyperStack() + " composite=" + im.isComposite() +
    " cal=" + c2.pixelWidth + "/" + c2.pixelHeight + "/" + c2.pixelDepth + " " + c2.getUnit() + " frameInterval=" + c2.frameInterval + " " + c2.getTimeUnit() +
    " ranges=" + ranges.join(",") + " overlay=" + (im.getOverlay() == null ? 0 : im.getOverlay().size()) +
    " info=" + (im.getInfoProperty() == null ? "none" : (im.getInfoProperty().contains("P47 info line") ? "kept" : "other(" + im.getInfoProperty().length() + " chars)")) +
    " checksum=" + checksum(im)
}
rep("R0 reference", describe(imp))
def ome = new File(WORK, "ref.ome.tif").getPath()
chk("S1 GraphicsEnvironment.isHeadless()") { java.awt.GraphicsEnvironment.isHeadless() }
chk("S2 IJ.run(imp, Bio-Formats Exporter, save=[..] compression=Uncompressed)") {
  IJ.run(imp, "Bio-Formats Exporter", "save=[" + ome + "] compression=Uncompressed"); "exists=" + new File(ome).exists() + " size=" + new File(ome).length()
}
chk("O1 BF.openImagePlus(ome.tif) composite") { def o = new ImporterOptions(); o.setId(ome); o.setColorMode(ImporterOptions.COLOR_MODE_COMPOSITE); def r = BF.openImagePlus(o); "series=" + r.length + " " + describe(r[0]) }
chk("O2 IJ.openImage(ome.tif) [IJ1 TIFF reader]") { describe(IJ.openImage(ome)) }
chk("O3 SCIFIO open(ome.tif)") {
  def ds = ij.scifio().datasetIO().open(ome)
  (0..<ds.numDimensions()).collect { i -> ds.axis(i).type().getLabel() + ":" + ds.dimension(i) + "@" + ds.averageScale(i) + (ds.axis(i).unit() ?: "") }.join(",")
}
chk("M1 OME-XML in ImageDescription (first 300 chars)") {
  def fi = new ij.io.TiffDecoder(WORK.getPath() + File.separator, "ref.ome.tif").getTiffInfo(); (fi[0].description ?: "none").replace("\n", " ").take(300)
}
chk("M2 OME-XML mentions PhysicalSizeX / TimeIncrement / Channel count") {
  def d = new ij.io.TiffDecoder(WORK.getPath() + File.separator, "ref.ome.tif").getTiffInfo()[0].description ?: ""
  def m1 = (d =~ /PhysicalSizeX="([^"]+)"/); def m2 = (d =~ /PhysicalSizeXUnit="([^"]+)"/); def m3 = (d =~ /TimeIncrement="([^"]+)"/)
  "PhysicalSizeX=" + (m1 ? m1[0][1] : "absent") + " unit=" + (m2 ? m2[0][1] : "absent") + " TimeIncrement=" + (m3 ? m3[0][1] : "absent") + " <Channel count=" + (d =~ /<Channel /).count + " ROI elements=" + (d =~ /<ROI /).count
}
rep("Z0 done", "halt(0)")
Runtime.getRuntime().halt(0)
