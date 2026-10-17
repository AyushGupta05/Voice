// P38: Memory ownership: which ImagePlus/ImageProcessor operations share pixel arrays.
// Run: fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless --run <this file>
import ij.*
import ij.gui.*
import ij.process.*
def out = new StringBuilder()
def p = { s -> out.append(s.toString()).append("\n") }
try {
  def imp = IJ.createImage("s", "8-bit ramp", 16, 16, 3)
  imp.setSlice(2)
  p "stack: ip.getPixels()==stack.getPixels(2): " + imp.getProcessor().getPixels().is(imp.getStack().getPixels(2))
  def ip2 = imp.getStack().getProcessor(2)
  p "stack.getProcessor(2) shares pixels with stack: " + ip2.getPixels().is(imp.getStack().getPixels(2))
  def wrap = new ImagePlus("wrap", imp.getProcessor())
  p "new ImagePlus(title, ip) shares pixels: " + wrap.getProcessor().getPixels().is(imp.getProcessor().getPixels())
  def dup = imp.duplicate()
  p "duplicate(): stackSize=" + dup.getStackSize() + " sharesSlice2=" + dup.getStack().getPixels(2).is(imp.getStack().getPixels(2))
  imp.setRoi(new Roi(2, 2, 4, 4))
  def cr = imp.crop()
  p "crop() with ROI on slice 2: size=" + cr.getWidth() + "x" + cr.getHeight() + " stackSize=" + cr.getStackSize()
  def cr2 = imp.crop("stack")
  p "crop(\"stack\") with ROI: size=" + cr2.getWidth() + "x" + cr2.getHeight() + " stackSize=" + cr2.getStackSize()
  def dupR = imp.duplicate()
  p "duplicate() with ROI present: size=" + dupR.getWidth() + "x" + dupR.getHeight() + " stackSize=" + dupR.getStackSize() + " roiStillOnSource=" + (imp.getRoi()!=null)
  imp.deleteRoi()
  def ipd = imp.getProcessor().duplicate()
  p "ip.duplicate() shares: " + ipd.getPixels().is(imp.getProcessor().getPixels())
  // Hyperstack index arithmetic
  def hs = IJ.createImage("h", "8-bit black", 8, 8, 2, 3, 4)
  p "hyperstack C,Z,T=" + hs.getNChannels() + "," + hs.getNSlices() + "," + hs.getNFrames() + " getStackIndex(2,3,4)=" + hs.getStackIndex(2,3,4) + " isHyperStack=" + hs.isHyperStack() + " class=" + hs.getClass().getSimpleName()
  hs.setPosition(2, 3, 4)
  p "after setPosition(2,3,4): currentSlice=" + hs.getCurrentSlice() + " C,Z,T=" + hs.getC() + "," + hs.getZ() + "," + hs.getT()
  // Calibration is per-image unless global
  def cal = imp.getCalibration(); cal.pixelWidth = 0.5; cal.setUnit("um")
  p "calibration after edit through getCalibration(): pixelWidth=" + imp.getCalibration().pixelWidth + " unit=" + imp.getCalibration().getUnit() + " dup pixelWidth=" + dup.getCalibration().pixelWidth
} catch (Throwable t) { p "threw " + t }
new File("C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P38-pixel-sharing.out.txt").text = out.toString()
System.exit(0)
