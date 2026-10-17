// @recipe nuclei-intensity-all-channels
// @title Per-nucleus intensity in every channel
// @when measure each nucleus or cell in every channel at once: marker expression per cell, nuclear vs channel intensities, multi-channel per-cell table ("measure every channel for each nucleus", "intensity of all markers per cell")
// @param nucleiChannel = 1 | DNA channel used to find the nuclei, 1-based
// @param method = Otsu | auto-threshold method for the nuclei
// @param sigma = 2 | Gaussian blur before thresholding, px
// @param minSize = 50 | smallest nucleus, pixels²
// @output Inside the active area selection when there is one. Results with one row per nucleus: Area, X, Y and "Mean C1", "Mean C2", … for every channel; ROI Manager entries and an overlay. Reports the count and each channel's mean over nuclei.
// @say Measured {count} nuclei in {channels} channels.
nucleiChannel = parseFloat(pennyParam("nucleiChannel", "1"));
method = pennyParam("method", "Otsu");
sigma = parseFloat(pennyParam("sigma", "2"));
minSize = parseFloat(pennyParam("minSize", "50"));

// Temporary windows get a per-run suffix, so cleanup by title can never match one of the researcher's images.
tag = "" + getTime() + "-" + floor(random * 100000);
if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
getDimensions(w, h, channels, slices, frames);
wasBatch = is("batch mode");
if (!wasBatch && !isOpen("ROI Manager")) run("ROI Manager...");
setBatchMode(true);
inSelection = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
if (inSelection) Roi.copy();
run("Select None");
if (channels > 1) Stack.setChannel(nucleiChannel);
pennyPlane("penny-nuclei-mask-" + tag);
if (bitDepth() == 24) run("8-bit");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
setAutoThreshold(method + " dark");
setOption("BlackBackground", true);
run("Convert to Mask"); run("Fill Holes"); run("Watershed");
// Only this image's earlier objects are replaced; other images keep theirs.
pennyClearManager(orig);
first = roiManager("count");
if (inSelection) Roi.paste();
// No "clear" with "add": it would empty the whole ROI Manager, other images' objects included.
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Nothing add");
pennyTagManager(first, orig);
close("penny-nuclei-mask-" + tag);
selectImage(orig);
count = roiManager("count") - first;
run("Clear Results");
means = newArray(channels);
// Selecting a plane-less ROI on a hyperstack moves the frame, so the analysed slice and frame are restored for every channel.
Stack.getPosition(mc, mz, mt);
for (i = 0; i < count; i++) {
  roiManager("select", first + i);
  Stack.setPosition(mc, mz, mt);
  getStatistics(area);
  setResult("Label", i, title + ":" + (i + 1)); setResult("Area", i, area);
  List.setMeasurements; setResult("X", i, List.getValue("X")); setResult("Y", i, List.getValue("Y"));
  for (c = 1; c <= channels; c++) {
    if (channels > 1) Stack.setPosition(c, mz, mt);
    getStatistics(a, mean);
    setResult("Mean C" + c, i, mean); means[c - 1] += mean;
  }
}
updateResults();
run("Select None");
if (channels > 1) Stack.setChannel(nucleiChannel);
pennyOverlay(first, count);
if (inSelection) Roi.paste();
summary = "";
for (c = 1; c <= channels; c++) summary = summary + " meanC" + c + "=" + d2s(ratio(means[c - 1], count), 2);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT count=" + count + " channels=" + channels + summary + " nucleiChannel=" + nucleiChannel);

/** a / b, or NaN when nothing was measured (b = 0), so an empty result is never reported as a zero mean. */
function ratio(a, b) { if (b == 0) return NaN; return a / b; }
/** Duplicates only the displayed plane (current channel, slice and frame); a plain Duplicate of a composite z-stack copies every channel. */
function pennyPlane(name) {
  if (Stack.isHyperstack || is("composite")) { Stack.getPosition(pc, pz, pt); run("Duplicate...", "title=[" + name + "] duplicate channels=" + pc + " slices=" + pz + " frames=" + pt); }
  else run("Duplicate...", "title=[" + name + "]");
}
/** Removes this image's objects (and untagged ones) from the ROI Manager; other images' objects stay. Run on a 2D image. */
function pennyClearManager(id) {
  for (i = roiManager("count") - 1; i >= 0; i--) { roiManager("select", i); owner = Roi.getProperty("luma.imageId"); if (owner == "" || owner == "" + id) roiManager("delete"); }
  run("Select None");
}
/** Tags the ROI Manager entries from index first on as objects of image id (the bridge's luma.imageId ownership). */
function pennyTagManager(first, id) {
  for (i = first; i < roiManager("count"); i++) { roiManager("select", i); Roi.setProperty("luma.imageId", "" + id); roiManager("update"); }
  run("Select None");
}
/** Draws ROI Manager entries first..first+n-1 as the overlay of the active image, keeping its displayed plane. */
function pennyOverlay(first, n) {
  run("Remove Overlay"); Stack.getPosition(oc, oz, ot);
  for (i = first; i < first + n; i++) { roiManager("select", i); Stack.setPosition(oc, oz, ot); Overlay.addSelection; }
  run("Select None");
}
function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
