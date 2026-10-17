// P39: When does IJ.run("Measure") append to versus reset the system Results table (headless)?
// Follow-up to P37, where two Measure calls after rt.reset() left a single row.
// Run: fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless --run <this file>
import ij.*
import ij.measure.*
import ij.plugin.filter.Analyzer
def out = new StringBuilder()
def p = { s -> out.append(s.toString()).append("\n") }
def saveMeas = Analyzer.getMeasurements()
try {
  def rt = ResultsTable.getResultsTable()
  def r = IJ.createImage("r", "32-bit ramp", 7, 3, 1)
  p "fresh table: size=" + rt.size() + " heading37=" + rt.getColumnHeading(ResultsTable.LAST_HEADING)
  (1..3).each { IJ.run(r, "Measure", ""); p "  after Measure #" + it + ": size=" + rt.size() + " heading37=" + rt.getColumnHeading(ResultsTable.LAST_HEADING) }
  rt.reset()
  p "after rt.reset(): size=" + rt.size() + " heading37=" + rt.getColumnHeading(ResultsTable.LAST_HEADING)
  (1..3).each { IJ.run(r, "Measure", ""); p "  after Measure #" + it + ": size=" + rt.size() }
  IJ.run("Clear Results", "")
  p "after Clear Results: size=" + rt.size() + " heading37=" + rt.getColumnHeading(ResultsTable.LAST_HEADING)
  (1..3).each { IJ.run(r, "Measure", ""); p "  after Measure #" + it + ": size=" + rt.size() }
  Analyzer.setMeasurements(Measurements.AREA + Measurements.MEAN)
  IJ.run(r, "Measure", "")
  p "after changing measurement flags then Measure: size=" + rt.size() + " headings=" + rt.getColumnHeadings().trim()
} catch (Throwable t) { p "threw " + t } finally { Analyzer.setMeasurements(saveMeas) }
new File("C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P39-measure-accumulation.out.txt").text = out.toString()
System.exit(0)
