// P31: Does a CommandListener see IJ.run() commands headless, and can it read the options string?
import ij.*
def out = new StringBuilder()
def p = { s -> out.append(s.toString()).append("\n") }
def listener = new CommandListener() {
    String commandExecuting(String command) {
        p "commandExecuting: command=[" + command + "] thread=[" + Thread.currentThread().getName() + "] Macro.getOptions()=[" + Macro.getOptions() + "]"
        return command
    }
}
Executer.addCommandListener(listener)
def imp = IJ.createImage("p31", "8-bit ramp", 64, 64, 1)
IJ.run(imp, "Invert", "")
IJ.run(imp, "Gaussian Blur...", "sigma=3")
IJ.run(imp, "Add...", "value=10")
Executer.removeCommandListener(listener)
// Thread-name dependence of Macro.getOptions()
Macro.setOptions("sigma=7")
p "Macro.getOptions() on thread [" + Thread.currentThread().getName() + "] after setOptions = [" + Macro.getOptions() + "]"
Macro.setOptions(null)
new File("C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P31-command-listener.out.txt").text = out.toString()

// Explicit exit: the first run of this probe wrote its output but the JVM did not terminate within 300 s.
System.exit(0)
