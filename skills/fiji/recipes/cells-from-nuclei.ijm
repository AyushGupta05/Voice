// @recipe cells-from-nuclei
// @title Whole-cell segmentation seeded by nuclei
// @when segment whole cells (not just nuclei) using a nuclear channel and a cytoplasm/membrane/actin channel: cell area, cell outlines, cytoplasm per cell ("segment the whole cells", "cell boundaries from the nuclei", "how big are the cells")
// @param nucleiChannel = 1 | DNA channel (DAPI/Hoechst), 1-based
// @param cellChannel = 2 | cytoplasm, membrane or actin channel, 1-based
// @param method = Otsu | auto-threshold method for both masks
// @param cellMethod = Li | auto-threshold method for the cell (cytoplasm) mask; Li keeps dim cytoplasm
// @param sigma = 2 | Gaussian blur before thresholding, px
// @param minSize = 100 | smallest cell, pixels²
// @output Inside the active area selection when there is one (cells touching its edge are cut there). One ROI per cell in the ROI Manager and an overlay; Results with area, mean (cell channel) and shape per cell. Neighbouring cells are separated along the Voronoi lines between their nuclei, so a cell is the part of the cytoplasm closest to its nucleus.
// @say Found {cells} cells around {nuclei} nuclei, mean cell area {meanArea:area}.
nucleiChannel = parseFloat(pennyParam("nucleiChannel", "1"));
cellChannel = parseFloat(pennyParam("cellChannel", "2"));
method = pennyParam("method", "Otsu");
cellMethod = pennyParam("cellMethod", "Li");
sigma = parseFloat(pennyParam("sigma", "2"));
minSize = parseFloat(pennyParam("minSize", "100"));

// Temporary windows get a per-run suffix, so cleanup by title can never match one of the researcher's images.
tag = "" + getTime() + "-" + floor(random * 100000);
if (nImages == 0) { print("RESULT error=no_image"); exit; }
getDimensions(w, h, channels, slices, frames);
if (channels < 2) { print("RESULT error=needs_nuclear_and_cell_channels"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode");
if (!wasBatch && !isOpen("ROI Manager")) run("ROI Manager...");
setBatchMode(true);
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
if (area) Roi.copy();
run("Select None");
setOption("BlackBackground", true);
Stack.setChannel(nucleiChannel);
pennyPlane("penny-nuclei-" + tag);
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
setAutoThreshold(method + " dark"); run("Convert to Mask"); run("Fill Holes"); run("Watershed");
run("Analyze Particles...", "size=" + (minSize / 4) + "-Infinity pixel show=Masks");
rename("penny-seeds-" + tag); run("Invert LUT");
if (area) Roi.paste();
run("Analyze Particles...", "size=0-Infinity pixel show=Nothing clear");
nuclei = nResults;
run("Select None");
close("penny-nuclei-" + tag);
selectImage(orig); Stack.setChannel(cellChannel);
pennyPlane("penny-cells-" + tag);
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
setAutoThreshold(cellMethod + " dark"); run("Convert to Mask");
imageCalculator("OR", "penny-cells-" + tag, "penny-seeds-" + tag);
selectImage("penny-seeds-" + tag);
run("Duplicate...", "title=[penny-voronoi-" + tag + "]");
run("Voronoi");
setThreshold(1, 255); run("Convert to Mask");
imageCalculator("Subtract", "penny-cells-" + tag, "penny-voronoi-" + tag);
selectImage("penny-cells-" + tag);
run("Set Measurements...", "area mean centroid perimeter shape display redirect=None decimal=3");
// Only this image's earlier objects are replaced; other images keep theirs.
pennyClearManager(orig);
first = roiManager("count");
run("Clear Results");
if (area) Roi.paste();
// No "clear" with "add": it would empty the whole ROI Manager, other images' objects included.
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Nothing display add");
// A cell must contain a nucleus: cytoplasm fragments without a seed are dropped.
selectImage("penny-seeds-" + tag);
for (i = roiManager("count") - 1; i >= first; i--) { roiManager("select", i); getStatistics(a, mean, min, max); if (max == 0) { roiManager("delete"); IJ.deleteRows(i - first, i - first); } }
run("Select None");
pennyTagManager(first, orig);
selectImage(orig); Stack.setChannel(cellChannel);
// Intensities are read from the original through each ROI, never by window title (two windows may share one).
// Selecting a plane-less ROI on a hyperstack moves the frame, so the analysed plane is restored before each measurement.
Stack.getPosition(mc, mz, mt);
for (i = 0; i < nResults; i++) { roiManager("select", first + i); Stack.setPosition(mc, mz, mt); getStatistics(roiArea, roiMean); setResult("Mean", i, roiMean); setResult("Label", i, title); }
run("Select None"); updateResults();
cells = nResults; sumArea = 0;
for (i = 0; i < cells; i++) sumArea += getResult("Area", i);
close("penny-cells-" + tag); close("penny-seeds-" + tag); close("penny-voronoi-" + tag);
selectImage(orig);
pennyOverlay(first, cells);
if (area) Roi.paste();
getPixelSize(unit, pw, ph);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT cells=" + cells + " nuclei=" + nuclei + " meanArea=" + d2s(ratio(sumArea, cells), 2) + " unit=" + replace(unit, "\\s", "_") + " nucleiChannel=" + nucleiChannel + " cellChannel=" + cellChannel);

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
