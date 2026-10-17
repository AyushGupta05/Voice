// @recipe nuclei-count-2d
// @title Count nuclei or cells in 2D
// @when count / segment nuclei, cells or blobs in a 2D image or one channel ("how many nuclei", "count the DAPI nuclei", "segment the cells in here")
// @param channel = 1 | channel with the objects (DAPI/Hoechst for nuclei), 1-based
// @param method = Otsu | auto-threshold method (Otsu, Li, Triangle, Default, Huang, Yen…)
// @param sigma = 2 | Gaussian blur before thresholding, px (0 = none)
// @param minSize = 50 | smallest object, pixels²
// @param maxSize = 0 | largest object, pixels² (0 = no limit)
// @param minCircularity = 0 | 0–1; 0.3 rejects debris and fibres
// @param watershed = true | split touching objects
// @param exclude = false | drop objects touching the image or selection edge
// @param dark = true | bright objects on a dark background (fluorescence); false for brightfield
// @output Results: one row per object (area, intensity from the original, centroid, shape); ROI Manager entries and an overlay on the image. Inside the active area selection when there is one.
// @say Found {count} objects, mean area {meanArea:area}.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Otsu");
sigma = parseFloat(pennyParam("sigma", "2"));
minSize = parseFloat(pennyParam("minSize", "50"));
maxSize = parseFloat(pennyParam("maxSize", "0"));
minCircularity = parseFloat(pennyParam("minCircularity", "0"));
watershed = pennyParam("watershed", "true"); watershed = (watershed == "true");
exclude = pennyParam("exclude", "false"); exclude = (exclude == "true");
dark = pennyParam("dark", "true"); dark = (dark == "true");

// Temporary windows get a per-run suffix, so cleanup by title can never match one of the researcher's images.
tag = "" + getTime() + "-" + floor(random * 100000);
if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode");
if (!wasBatch && !isOpen("ROI Manager")) run("ROI Manager...");
setBatchMode(true);
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
if (area) Roi.copy();
getDimensions(w, h, channels, slices, frames);
if (channels > 1) Stack.setChannel(channel);
run("Select None");
pennyPlane("penny-nuclei-mask-" + tag);
if (bitDepth() == 24) run("8-bit");
if (is("Inverting LUT")) run("Invert LUT");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
if (dark) setAutoThreshold(method + " dark"); else setAutoThreshold(method);
getThreshold(lower, upper);
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
if (watershed) run("Watershed");
// Only this image's earlier objects are replaced; other images keep theirs.
pennyClearManager(orig);
first = roiManager("count");
if (area) Roi.paste();
run("Set Measurements...", "area mean centroid perimeter shape display redirect=None decimal=3");
run("Clear Results");
max = "Infinity"; if (maxSize > 0) max = "" + maxSize;
// No "clear" with "add": it would empty the whole ROI Manager, other images' objects included (Results were cleared above).
options = "size=" + minSize + "-" + max + " pixel circularity=" + minCircularity + "-1.00 show=Nothing display add";
if (exclude) options = options + " exclude";
run("Analyze Particles...", options);
pennyTagManager(first, orig);
close("penny-nuclei-mask-" + tag);
selectImage(orig);
// Intensities are read from the original through each ROI, never by window title (two windows may share one).
// Selecting a plane-less ROI on a hyperstack moves the frame, so the analysed plane is restored before each measurement.
Stack.getPosition(mc, mz, mt);
for (i = 0; i < nResults; i++) { roiManager("select", first + i); Stack.setPosition(mc, mz, mt); getStatistics(roiArea, roiMean); setResult("Mean", i, roiMean); setResult("Label", i, title); }
run("Select None"); updateResults();
count = nResults; sumArea = 0; sumMean = 0;
for (i = 0; i < count; i++) { sumArea += getResult("Area", i); sumMean += getResult("Mean", i); }
pennyOverlay(first, count);
if (area) Roi.paste();
getPixelSize(unit, pw, ph);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT count=" + count + " meanArea=" + d2s(ratio(sumArea, count), 2) + " meanIntensity=" + d2s(ratio(sumMean, count), 2) + " unit=" + replace(unit, "\\s", "_") + " channel=" + channel + " threshold=" + method + ":" + d2s(lower, 0) + "-" + d2s(upper, 0));

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
