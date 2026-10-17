// P32: ImageListener events, IDs and WindowManager state in headless (forced batch) mode.
import ij.*
import ij.macro.Interpreter
import java.awt.EventQueue
def out = new StringBuilder()
def p = { s -> out.append(s.toString()).append("\n") }
def events = Collections.synchronizedList([])
def l = new ImageListener() {
    void imageOpened(ImagePlus i) { events << ("opened " + i.getTitle()) }
    void imageClosed(ImagePlus i) { events << ("closed " + i.getTitle()) }
    void imageUpdated(ImagePlus i) { events << ("updated " + i.getTitle()) }
}
ImagePlus.addImageListener(l)
p "Interpreter.isBatchMode()=" + Interpreter.isBatchMode() + " Interpreter.batchMode field=" + Interpreter.batchMode
def a = IJ.createImage("A", "8-bit ramp", 32, 32, 1)
def b = IJ.createImage("B", "16-bit ramp", 32, 32, 1)
p "IDs: A=" + a.getID() + " B=" + b.getID()
a.show(); b.show()
p "after show: getImageCount=" + WindowManager.getImageCount() + " getIDList=" + Arrays.toString(WindowManager.getIDList()) + " current=" + WindowManager.getCurrentImage()?.getTitle() + " A.getWindow()=" + a.getWindow()
WindowManager.setTempCurrentImage(a)
p "after setTempCurrentImage(A): current=" + WindowManager.getCurrentImage()?.getTitle()
IJ.run(a, "Invert", "")
a.updateAndDraw()
p "A.changes after Invert=" + a.changes
a.getProcessor().set(0,0,7); a.updateAndDraw()
// display range change does not touch pixels
b.setDisplayRange(0, 100)
p "B pixel(5,5)=" + b.getProcessor().get(5,5) + " displayMin/Max=" + b.getDisplayRangeMin() + "/" + b.getDisplayRangeMax()
a.close(); b.close()
EventQueue.invokeAndWait({} as Runnable)
Thread.sleep(500)
EventQueue.invokeAndWait({} as Runnable)
p "events=" + events
p "after close: getImageCount=" + WindowManager.getImageCount()
ImagePlus.removeImageListener(l)
p "IJ.maxMemory()=" + IJ.maxMemory() + " IJ.freeMemory()=" + IJ.freeMemory()
new File("C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P32-image-listener-batch.out.txt").text = out.toString()
