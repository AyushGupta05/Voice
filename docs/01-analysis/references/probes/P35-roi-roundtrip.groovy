// P35: .roi encode/decode fidelity, mask convention and RoiManager without a window (headless).
// Run: fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless --run <this file>
import ij.*
import ij.gui.*
import ij.io.*
import ij.process.*
import ij.plugin.frame.RoiManager
import java.awt.Color
def out = new StringBuilder()
def p = { s -> out.append(s.toString()).append("\n") }
def rt = { Roi r ->
  def bytes = RoiEncoder.saveAsByteArray(r)
  def r2 = new RoiDecoder(bytes, "x.roi").getRoi()
  return [bytes.length, r2]
}
try {
def fp = new FloatPolygon([10.25f, 30.5f, 20.75f] as float[], [5.125f, 6.5f, 25.875f] as float[], 3)
def poly = new PolygonRoi(fp, Roi.POLYGON)
poly.setName("tri"); poly.setPosition(2, 3, 4); poly.setStrokeColor(Color.red); poly.setStrokeWidth(1.5); poly.setProperty("k", "v")
p "  stroke before setGroup=" + poly.getStrokeColor()
poly.setGroup(7)
p "  stroke after setGroup(7)=" + poly.getStrokeColor()
def r1 = rt(poly); def d1 = r1[1]
p "polygon: type=" + poly.getType() + " subPixel=" + poly.subPixelResolution() + " bytes=" + r1[0]
p "  orig float x=" + Arrays.toString(poly.getFloatPolygon().xpoints) + " y=" + Arrays.toString(poly.getFloatPolygon().ypoints)
p "  dec  float x=" + Arrays.toString(d1.getFloatPolygon().xpoints) + " y=" + Arrays.toString(d1.getFloatPolygon().ypoints)
p "  dec name=" + d1.getName() + " CZT=" + d1.getCPosition() + "," + d1.getZPosition() + "," + d1.getTPosition() + " hasHyperStackPosition=" + d1.hasHyperStackPosition() + " stroke=" + d1.getStrokeColor() + " width=" + d1.getStrokeWidth() + " prop k=" + d1.getProperty("k") + " group=" + d1.getGroup()
def ipoly = new PolygonRoi([10, 30, 20] as int[], [5, 6, 25] as int[], 3, Roi.POLYGON)
def r2 = rt(ipoly); def d2 = r2[1]
p "int polygon: subPixel=" + ipoly.subPixelResolution() + " bytes=" + r2[0] + " dec subPixel=" + d2.subPixelResolution() + " dec x=" + Arrays.toString(d2.getPolygon().xpoints)
def rect = new Roi(1.5d, 2.25d, 10.5d, 4.75d)
def d3 = rt(rect)[1]
p "double rect: orig floatBounds=" + rect.getFloatBounds() + " subPixel=" + rect.subPixelResolution() + " dec floatBounds=" + d3.getFloatBounds() + " dec intBounds=" + d3.getBounds()
def line = new Line(0.5d, 0.5d, 10.25d, 3.75d)
def d4 = rt(line)[1]
p "line: dec x1d,y1d,x2d,y2d=" + d4.x1d + "," + d4.y1d + "," + d4.x2d + "," + d4.y2d
def pts = new PointRoi([1.5f, 4.25f] as float[], [2.5f, 8.75f] as float[], 2)
pts.setPosition(5)
p "points: orig getPosition()=" + pts.getPosition() + " hasHyperStackPosition=" + pts.hasHyperStackPosition()
def pts2 = new PointRoi([1.5f, 4.25f] as float[], [2.5f, 8.75f] as float[], 2); pts2.setPosition(0, 5, 0)
def d5b = rt(pts2)[1]
p "points setPosition(0,5,0) round trip: Z=" + d5b.getZPosition() + " hasHyperStackPosition=" + d5b.hasHyperStackPosition()
def r1pt = new PointRoi(3.5d, 4.5d); r1pt.setPosition(5)
p "single point setPosition(5): orig=" + r1pt.getPosition() + " decoded=" + rt(r1pt)[1].getPosition()
def d5 = rt(pts)[1]
p "points: dec type=" + d5.getType() + " n=" + d5.size() + " x=" + Arrays.toString(d5.getFloatPolygon().xpoints) + " position=" + d5.getPosition()
def s = new ShapeRoi(new Roi(0,0,10,10)).not(new ShapeRoi(new OvalRoi(2,2,4,4)))
def d6 = rt(s)[1]
p "shape (rect minus oval): type=" + s.getType() + " dec class=" + d6.getClass().getSimpleName() + " dec type=" + d6.getType() + " mask count255=" + d6.getMask().getStatistics().histogram[255] + " orig count255=" + s.getMask().getStatistics().histogram[255]
// Mask convention: pixel included iff its centre is inside
def sq = new PolygonRoi(new FloatPolygon([0.6f, 2.4f, 2.4f, 0.6f] as float[], [0.6f, 0.6f, 2.4f, 2.4f] as float[], 4), Roi.POLYGON)
def m = sq.getMask()
p "mask of square (0.6..2.4): bounds=" + sq.getBounds() + " mask " + m.getWidth() + "x" + m.getHeight() + " count255=" + m.getStatistics().histogram[255] + " contains(1,1)=" + sq.contains(1,1) + " contains(0,0)=" + sq.contains(0,0) + " containsPoint(0.7,0.7)=" + sq.containsPoint(0.7d,0.7d) + " containsPoint(1.5,1.5)=" + sq.containsPoint(1.5d,1.5d) + " intPolygon(0..3).containsPoint(1.5,1.5)=" + new PolygonRoi([0,3,3,0] as int[], [0,0,3,3] as int[], 4, Roi.POLYGON).containsPoint(1.5d,1.5d)
p "Roi.getMask() for plain rectangle is null: " + (new Roi(0,0,5,5).getMask()==null)
} catch (Throwable t) { p "ROI section threw " + t }
// RoiManager without a window
try {
  def poly2 = new PolygonRoi([10, 30, 20] as int[], [5, 6, 25] as int[], 3, Roi.POLYGON)
  def rm = new RoiManager(true)
  rm.addRoi(poly2); rm.addRoi(new Roi(1, 2, 10, 4))
  p "RoiManager(true): count=" + rm.getCount() + " names=" + (0..<rm.getCount()).collect{ rm.getName(it) } + " isVisible=" + rm.isVisible()
  def zip = "C:/Users/arnav/AppData/Local/Temp/claude/c--Users-arnav-Documents-dev-click-to-batch/0143a74a-54d3-45c4-9ab8-8ca264a2998b/scratchpad/G2/p35-RoiSet.zip"
  p "save zip ok=" + rm.save(zip)
  def rm2 = new RoiManager(true)
  p "reopen ok=" + rm2.open(zip) + " count=" + rm2.getCount() + " names=" + (0..<rm2.getCount()).collect{ rm2.getName(it) }
} catch (Throwable t) { p "RoiManager(true) threw " + t.getClass().getName() + ": " + t.getMessage() }
try { def rm3 = RoiManager.getRoiManager(); p "RoiManager.getRoiManager()=" + rm3.getClass().getName() + " getInstance()=" + RoiManager.getInstance() } catch (Throwable t) { p "getRoiManager threw " + t.getClass().getName() + ": " + t.getMessage() }
new File("C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P35-roi-roundtrip.out.txt").text = out.toString()

System.exit(0)
