#@ String out
import ij.*
def f = new File(out)
f.text = "headless=" + java.awt.GraphicsEnvironment.isHeadless() + "\nij=" + IJ.getFullVersion() + "\nmenus=" + (Menus.getCommands()==null ? "null" : Menus.getCommands().size()) + "\n"
println("HELLO-STDOUT")
