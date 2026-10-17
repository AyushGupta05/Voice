// P35 (macro variant): can a headless batch-mode macro use roiManager() and Overlay?
out = "C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P35-roi-manager-macro.out.txt";
setBatchMode(true);
newImage("r", "8-bit ramp", 64, 64, 1);
makeRectangle(5, 5, 10, 10);
Overlay.addSelection;
s = "overlay size after addSelection=" + Overlay.size + "\n";
File.saveString(s, out);
makeOval(20, 20, 10, 10);
roiManager("add");
s = s + "roiManager count after add=" + roiManager("count") + "\n";
File.saveString(s, out);
eval("script", "System.exit(0);");
