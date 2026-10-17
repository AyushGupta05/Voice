#@ DatasetIOService dio
#@ ConvertService convert
// P43 - Metadata/pixel fidelity of common save/open paths in the pinned Fiji (headless).
// Fiji Modernisation Project, agent G3.
// Run: fiji-windows-x64.exe --java-home=<bundled JDK> --allow-multiple --headless --run <this file>
// All files are written to the scratch WORK directory, never into the Fiji directory.
import ij.*
import ij.gui.*
import ij.process.*
import ij.measure.Calibration
import loci.plugins.BF
import loci.plugins.in.ImporterOptions
import net.imagej.axis.Axes

def SCR = "C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G3/out"
def WORK = new File(SCR, "P43-work"); WORK.mkdirs()
def OUT = new File(SCR, "P43-file-format-fidelity.out.txt")
System.setErr(new PrintStream(new FileOutputStream(new File(SCR, "P43-stderr.txt")), true))
def lines = []
def rep = { String k, Object v -> lines << "${k} = ${v}"; OUT.text = lines.join("\n") + "\n" }
def chk = { String k, Closure c -> try { rep(k, c.call()) } catch (Throwable t) { rep(k, "THREW " + t.getClass().getName() + ": " + t.getMessage()) } }
def wd = new Thread({ Thread.sleep(240000); rep("Z9 watchdog", "halt"); Runtime.getRuntime().halt(4) } as Runnable, "P43-watchdog"); wd.setDaemon(true); wd.start()

// ---- reference image: 16-bit, C=3 Z=4 T=2, calibrated, composite, overlay, Info, slice label
def W = 64, H = 48, C = 3, Z = 4, T = 2
def imp = IJ.createHyperStack("p43ref", W, H, C, Z, T, 16)
for (int t = 1; t <= T; t++) for (int z = 1; z <= Z; z++) for (int c = 1; c <= C; c++) {
  def ip = imp.getStack().getProcessor(imp.getStackIndex(c, z, t))
  for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) ip.set(x, y, c * 1000 + z * 100 + t * 10 + (x % 10) + 3000 * (y % 2))
}
def cal = imp.getCalibration()
cal.pixelWidth = 0.25; cal.pixelHeight = 0.25; cal.pixelDepth = 1.5; cal.setUnit("micron")
cal.frameInterval = 2.5; cal.setTimeUnit("sec")
imp = new CompositeImage(imp, IJ.COMPOSITE)
imp.setCalibration(cal)
[[1000, 4500], [2000, 5500], [3000, 6500]].eachWithIndex { r, i -> imp.setC(i + 1); imp.setDisplayRange(r[0], r[1]) }
imp.setC(1)
imp.setProperty("Info", "P43 info line\nkey=value")
imp.getStack().setSliceLabel("label-c1z1t1", 1)
def ov = new Overlay(); ov.add(new Roi(5, 5, 20, 10)); ov.add(new OvalRoi(30, 10, 12, 12)); imp.setOverlay(ov)
imp.setRoi(new Roi(2, 3, 11, 13))

def checksum = { ImagePlus im ->
  double s = 0; def st = im.getStack(); for (int i = 1; i <= st.getSize(); i++) { def p = st.getProcessor(i); for (int k = 0; k < p.getPixelCount(); k++) s += p.getf(k) }; s
}
def describe = { ImagePlus im ->
  if (im == null) return "null"
  def c2 = im.getCalibration()
  def ranges = []
  if (im.isComposite()) { for (int i = 1; i <= im.getNChannels(); i++) { def l = ((CompositeImage) im).getChannelLut(i); ranges << ("" + (int) l.min + "-" + (int) l.max) } }
  else ranges << ("" + (int) im.getDisplayRangeMin() + "-" + (int) im.getDisplayRangeMax())
  "dims(xyczt)=" + im.getDimensions().join("x") + " bitDepth=" + im.getBitDepth() + " hyperstack=" + im.isHyperStack() +
    " composite=" + im.isComposite() + (im.isComposite() ? "(mode=" + ((CompositeImage) im).getMode() + ")" : "") +
    " cal=" + c2.pixelWidth + "/" + c2.pixelHeight + "/" + c2.pixelDepth + " " + c2.getUnit() +
    " frameInterval=" + c2.frameInterval + " " + c2.getTimeUnit() +
    " ranges=" + ranges.join(",") +
    " overlay=" + (im.getOverlay() == null ? 0 : im.getOverlay().size()) +
    " roi=" + (im.getRoi() == null ? "none" : im.getRoi().getBounds().toString().replace("java.awt.Rectangle", "")) +
    " info=" + (im.getInfoProperty() == null ? "none" : (im.getInfoProperty().contains("P43 info line") ? "kept" : "other(" + im.getInfoProperty().length() + " chars)")) +
    " label1=" + im.getStack().getSliceLabel(1) +
    " checksum=" + checksum(im)
}
rep("R0 reference", describe(imp))

def tif = new File(WORK, "ref.tif").getPath()
def zip = new File(WORK, "ref.zip").getPath()
def ome = new File(WORK, "ref.ome.tif").getPath()
def png = new File(WORK, "ref.png").getPath()
def jpg = new File(WORK, "ref.jpg").getPath()
def png16 = new File(WORK, "plane16.png").getPath()

chk("S1 IJ.saveAsTiff") { IJ.saveAsTiff(imp, tif) }
chk("S2 IJ.saveAs ZIP") { IJ.saveAs(imp.duplicate(), "ZIP", zip); new File(zip).exists() }
chk("S3 Bio-Formats Exporter (macro options) -> OME-TIFF") {
  IJ.run(imp, "Bio-Formats Exporter", "save=[" + ome + "] compression=Uncompressed"); new File(ome).exists()
}
chk("S4 IJ.saveAs PNG of composite") { IJ.saveAs(imp.duplicate(), "PNG", png); new File(png).exists() }
chk("S5 IJ.saveAs JPEG of composite") { IJ.saveAs(imp.duplicate(), "Jpeg", jpg); new File(jpg).exists() }
chk("S6 IJ.saveAs PNG of one 16-bit plane") {
  def p = new ImagePlus("plane", imp.getStack().getProcessor(1).duplicate()); p.setCalibration(cal.copy()); IJ.saveAs(p, "PNG", png16); new File(png16).exists()
}

chk("O1 IJ.openImage(tif)") { describe(IJ.openImage(tif)) }
chk("O2 IJ.openImage(zip)") { describe(IJ.openImage(zip)) }
chk("O3 IJ.openImage(ome.tif) [IJ1 TIFF reader]") { describe(IJ.openImage(ome)) }
chk("O4 BF.openImagePlus(ome.tif) composite") {
  def o = new ImporterOptions(); o.setId(ome); o.setColorMode(ImporterOptions.COLOR_MODE_COMPOSITE); def r = BF.openImagePlus(o); "series=" + r.length + " " + describe(r[0])
}
chk("O5 BF.openImagePlus(tif) composite") {
  def o = new ImporterOptions(); o.setId(tif); o.setColorMode(ImporterOptions.COLOR_MODE_COMPOSITE); def r = BF.openImagePlus(o); "series=" + r.length + " " + describe(r[0])
}
chk("O6 IJ.openImage(png) of composite") { describe(IJ.openImage(png)) }
chk("O7 IJ.openImage(jpg) of composite") { describe(IJ.openImage(jpg)) }
chk("O8 IJ.openImage(png16)") { describe(IJ.openImage(png16)) }
chk("O9 SCIFIO DatasetIOService.open(tif)") {
  def ds = dio.open(tif)
  def ax = (0..<ds.numDimensions()).collect { i -> ds.axis(i).type().getLabel() + ":" + ds.dimension(i) + "@" + ds.averageScale(i) + (ds.axis(i).unit() ?: "") }
  "axes=" + ax.join(",") + " type=" + ds.getTypeLabelShort()
}
chk("O10 SCIFIO DatasetIOService.open(ome.tif)") {
  def ds = dio.open(ome)
  def ax = (0..<ds.numDimensions()).collect { i -> ds.axis(i).type().getLabel() + ":" + ds.dimension(i) + "@" + ds.averageScale(i) + (ds.axis(i).unit() ?: "") }
  "axes=" + ax.join(",") + " type=" + ds.getTypeLabelShort()
}
chk("O11 Dataset (SCIFIO, tif) -> ImagePlus via ConvertService") { describe(convert.convert(dio.open(tif), ImagePlus.class)) }

chk("L1 8-bit conversion of one plane: min/max before -> after") {
  def p = new ImagePlus("p", imp.getStack().getProcessor(1).duplicate()); p.setDisplayRange(1000, 4500)
  def a = p.getStatistics(); IJ.run(p, "8-bit", ""); def b = p.getStatistics()
  "before min=" + a.min + " max=" + a.max + " -> after bitDepth=" + p.getBitDepth() + " min=" + b.min + " max=" + b.max
}
chk("L2 Stack to RGB of composite") { def d = imp.duplicate(); IJ.run(d, "Stack to RGB", "slices frames keep"); def r = WindowManager.getCurrentImage(); describe(r) }

chk("M1 TIFF ImageDescription (first 400 chars)") {
  def fi = new ij.io.TiffDecoder(WORK.getPath() + File.separator, "ref.tif").getTiffInfo()
  (fi[0].description ?: "none").replace("\n", "\\n").take(400)
}
chk("M2 OME-TIFF ImageDescription starts with") {
  def fi = new ij.io.TiffDecoder(WORK.getPath() + File.separator, "ref.ome.tif").getTiffInfo()
  (fi[0].description ?: "none").replace("\n", " ").take(160)
}
chk("M3 file sizes (bytes)") { [tif, zip, ome, png, jpg, png16].collect { new File(it).getName() + ":" + new File(it).length() }.join(", ") }

rep("Z0 done", "System.exit(0)")
System.exit(0)
