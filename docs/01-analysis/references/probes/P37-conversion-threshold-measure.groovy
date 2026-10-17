// P37: Global-state effects on numeric results: 16->8-bit scaling, Convert to Mask vs blackBackground,
// ResultsTable rounding on save, and accumulation of the system Results table headless.
// All modified globals are restored in the finally block.
// Run: fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless --run <this file>
import ij.*
import ij.process.*
import ij.measure.*
import ij.plugin.filter.Analyzer
def out = new StringBuilder()
def p = { s -> out.append(s.toString()).append("\n") }
def saveBB = Prefs.blackBackground
def saveScale = ImageConverter.getDoScaling()
def saveMeas = Analyzer.getMeasurements()
def savePrec = Analyzer.getPrecision()
p "initial: Prefs.blackBackground=" + saveBB + " ImageConverter.getDoScaling()=" + saveScale + " Analyzer.getMeasurements()=" + saveMeas + " precision=" + savePrec
try {
  def mk = { -> def ip = new ShortProcessor(4, 1); [0, 250, 500, 1000].eachWithIndex { v, i -> ip.set(i, 0, v) }; new ImagePlus("s", ip) }
  def a = mk(); a.setDisplayRange(0, 1000); new ImageConverter(a).convertToGray8()
  p "16->8 pixels [0,250,500,1000], display 0..1000, scaling on: " + (0..3).collect{ a.getProcessor().get(it,0) }
  def b = mk(); b.setDisplayRange(0, 500); new ImageConverter(b).convertToGray8()
  p "16->8 pixels [0,250,500,1000], display 0..500, scaling on: " + (0..3).collect{ b.getProcessor().get(it,0) }
  ImageConverter.setDoScaling(false)
  def c = mk(); c.setDisplayRange(0, 500); new ImageConverter(c).convertToGray8()
  p "16->8 pixels [0,250,500,1000], display 0..500, scaling off: " + (0..3).collect{ c.getProcessor().get(it,0) }
  ImageConverter.setDoScaling(saveScale)
  [true, false].each { bb ->
    Prefs.blackBackground = bb
    def imp = IJ.createImage("m", "8-bit ramp", 256, 3, 1)
    imp.getProcessor().setThreshold(128, 255, ImageProcessor.NO_LUT_UPDATE)
    IJ.run(imp, "Convert to Mask", "")
    def ip = imp.getProcessor()
    p "Convert to Mask (thr 128-255) blackBackground=" + bb + ": pixel(10)=" + ip.get(10,1) + " pixel(200)=" + ip.get(200,1) + " invertedLut=" + imp.isInvertedLut()
    IJ.run(imp, "Erode", "")
    p "   after Erode: pixel(128)=" + imp.getProcessor().get(128,1) + " pixel(129)=" + imp.getProcessor().get(129,1) + " pixel(130)=" + imp.getProcessor().get(130,1)
  }
  Prefs.blackBackground = saveBB
  def noThr = IJ.createImage("n", "8-bit ramp", 256, 1, 1)
  IJ.run(noThr, "Convert to Mask", "")
  p "Convert to Mask with no threshold set: pixel(10)=" + noThr.getProcessor().get(10,0) + " pixel(200)=" + noThr.getProcessor().get(200,0)
  // Measure accumulation and rounding
  def rt = ResultsTable.getResultsTable(); rt.reset()
  Analyzer.setMeasurements(Measurements.AREA + Measurements.MEAN)
  def r = IJ.createImage("r", "32-bit ramp", 7, 3, 1)
  IJ.run(r, "Measure", ""); IJ.run(r, "Measure", "")
  p "after two Measure calls: rows=" + rt.size() + " headings=" + rt.getColumnHeadings().trim() + " Mean full=" + rt.getValue("Mean", 0) + " asString=" + rt.getStringValue("Mean", 0)
  def f = "C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G2/p37-results.csv"
  rt.save(f)
  p "saved CSV:\n" + new File(f).text
} catch (Throwable t) {
  p "threw " + t
} finally {
  Prefs.blackBackground = saveBB; ImageConverter.setDoScaling(saveScale); Analyzer.setMeasurements(saveMeas); Analyzer.setPrecision(savePrec)
}
new File("C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P37-conversion-threshold-measure.out.txt").text = out.toString()
