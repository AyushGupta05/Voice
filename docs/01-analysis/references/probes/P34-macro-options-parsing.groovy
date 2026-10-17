// P34: Exact semantics of ij.Macro.getValue / trimKey (the parser GenericDialog uses for run() options).
// Run: fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless --run <this file>
import ij.*
def out = new StringBuilder()
def p = { s -> out.append(s.toString()).append("\n") }
def cases = [
  ["sigma=2 scaled", "Sigma (Radius):", "DEF"],
  ["title=[My Image 1] sigma=2", "Title:", "DEF"],
  ["title='quoted value' x=1", "title", "DEF"],
  ["xsigma=3 sigma=2", "sigma", "DEF"],
  ["_sigma=3 sigma=2", "sigma", "DEF"],
  ["1sigma=3 sigma=2", "sigma", "DEF"],
  ["radius=5", "sigma", "DEF"],
  ["path=[C:/a b/c [1].tif] x=1", "path", "DEF"],
  ["SIGMA=4", "sigma", "DEF"],
  ["value=1e-3", "value", "DEF"],
]
cases.each { c -> p "getValue(\"" + c[0] + "\", \"" + c[1] + "\") -> [" + Macro.getValue(c[0], c[1], c[2]) + "]" }
p "trimKey(\"Sigma (Radius):\") -> [" + Macro.trimKey("Sigma (Radius):") + "]"
p "trimKey(\"Calculate threshold for each image\") -> [" + Macro.trimKey("Calculate threshold for each image") + "]"
p "trimKey(\"Black background (of binary masks)\") -> [" + Macro.trimKey("Black background (of binary masks)") + "]"
new File("C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P34-macro-options-parsing.out.txt").text = out.toString()
