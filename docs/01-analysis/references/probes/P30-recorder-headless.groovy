// P30: Does the ImageJ 1.x Recorder produce text in headless Fiji?
// Run: fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless --run <this file>
import ij.*
import ij.plugin.frame.Recorder
import java.awt.GraphicsEnvironment
def out = new StringBuilder()
def p = { s -> out.append(s.toString()).append("\n") }
p "headless=" + GraphicsEnvironment.isHeadless()
p "IJ.getInstance()=" + IJ.getInstance()
p "before: Recorder.record=" + Recorder.record + " IJ.recording()=" + IJ.recording() + " Recorder.getInstance()=" + Recorder.getInstance()
try {
    def r = new Recorder(false)
    p "new Recorder(false) succeeded: " + r
} catch (Throwable t) {
    p "new Recorder(false) threw: " + t.getClass().getName() + ": " + t.getMessage()
}
p "after ctor: Recorder.record=" + Recorder.record + " IJ.recording()=" + IJ.recording() + " Recorder.getInstance()=" + Recorder.getInstance()
// Force the public static flag and see whether IJ.recording() follows it
Recorder.record = true
Recorder.recordInMacros = true
p "forced Recorder.record=true: IJ.recording()=" + IJ.recording()
def imp = IJ.createImage("p30", "8-bit ramp", 64, 64, 1)
IJ.run(imp, "Gaussian Blur...", "sigma=2")
Recorder.record("print", "recorded-call")
Recorder.recordOption("sigma", "2")
p "Recorder.getCommandOptions() after recordOption=" + Recorder.getCommandOptions()
try { p "Recorder.getInstance()=" + Recorder.getInstance() } catch (Throwable t) { p "getInstance threw " + t }
Recorder.record = false
Recorder.recordInMacros = false
new File(System.getProperty("probe.out", "C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P30-recorder-headless.out.txt")).text = out.toString()

// Explicit exit: the first run of this probe wrote its output but the JVM did not terminate within 300 s.
System.exit(0)
