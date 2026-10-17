// P39 (macro variant): does run("Measure") accumulate rows in a headless batch-mode IJ1 macro?
out = "C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P39-measure-accumulation-macro.out.txt";
setBatchMode(true);
newImage("r", "32-bit ramp", 7, 3, 1);
s = "";
for (i=1; i<=3; i++) { run("Measure"); s = s + "after Measure #" + i + ": nResults=" + nResults + "\n"; }
File.saveString(s, out);
eval("script", "System.exit(0);");
