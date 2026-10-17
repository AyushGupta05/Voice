// @recipe cells-from-nuclei
// @title Whole-cell segmentation seeded by nuclei
// @when segment whole cells (not just nuclei) using a nuclear channel and a cytoplasm/membrane/actin channel: cell area, cell outlines, cytoplasm per cell ("segment the whole cells", "cell boundaries from the nuclei", "how big are the cells")
// @param nucleiChannel = 1 | DNA channel (DAPI/Hoechst), 1-based
// @param cellChannel = 2 | cytoplasm, membrane or actin channel, 1-based
// @param method = Otsu | auto-threshold method for both masks
// @param cellMethod = Li | auto-threshold method for the cell (cytoplasm) mask; Li keeps dim cytoplasm
// @param sigma = 2 | Gaussian blur before thresholding, px
// @param minSize = 100 | smallest cell, pixels²
// @output One ROI per cell in the ROI Manager and an overlay; Results with area, mean (cell channel) and shape per cell. Neighbouring cells are separated along the Voronoi lines between their nuclei, so a cell is the part of the cytoplasm closest to its nucleus.
// @say Found {cells} cells around {nuclei} nuclei, mean cell area {meanArea:area}.
nucleiChannel = parseFloat(pennyParam("nucleiChannel", "1"));
cellChannel = parseFloat(pennyParam("cellChannel", "2"));
method = pennyParam("method", "Otsu");
cellMethod = pennyParam("cellMethod", "Li");
sigma = parseFloat(pennyParam("sigma", "2"));
minSize = parseFloat(pennyParam("minSize", "100"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
getDimensions(w, h, channels, slices, frames);
if (channels < 2) { print("RESULT error=needs_nuclear_and_cell_channels"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode");
if (!wasBatch && !isOpen("ROI Manager")) run("ROI Manager...");
setBatchMode(true);
run("Select None");
setOption("BlackBackground", true);
Stack.setChannel(nucleiChannel);
run("Duplicate...", "title=penny-nuclei");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
setAutoThreshold(method + " dark"); run("Convert to Mask"); run("Fill Holes"); run("Watershed");
run("Analyze Particles...", "size=" + (minSize / 4) + "-Infinity pixel show=Masks");
rename("penny-seeds"); run("Invert LUT");
run("Analyze Particles...", "size=0-Infinity pixel show=Nothing clear");
nuclei = nResults;
close("penny-nuclei");
selectImage(orig); Stack.setChannel(cellChannel);
run("Duplicate...", "title=penny-cells");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
setAutoThreshold(cellMethod + " dark"); run("Convert to Mask");
imageCalculator("OR", "penny-cells", "penny-seeds");
selectImage("penny-seeds");
run("Duplicate...", "title=penny-voronoi");
run("Voronoi");
setThreshold(1, 255); run("Convert to Mask");
imageCalculator("Subtract", "penny-cells", "penny-voronoi");
selectImage("penny-cells");
run("Set Measurements...", "area mean centroid perimeter shape display redirect=[" + title + "] decimal=3");
selectImage(orig); Stack.setChannel(cellChannel); selectImage("penny-cells");
run("Clear Results");
roiManager("reset");
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Nothing display clear add");
run("Set Measurements...", "area mean centroid perimeter shape display redirect=None decimal=3");
// A cell must contain a nucleus: cytoplasm fragments without a seed are dropped.
selectImage("penny-seeds");
for (i = roiManager("count") - 1; i >= 0; i--) { roiManager("select", i); getStatistics(a, mean, min, max); if (max == 0) { roiManager("delete"); IJ.deleteRows(i, i); } }
run("Select None");
cells = nResults; sumArea = 0;
for (i = 0; i < cells; i++) sumArea += getResult("Area", i);
close("penny-cells"); close("penny-seeds"); close("penny-voronoi");
selectImage(orig);
run("Remove Overlay");
if (cells > 0) run("From ROI Manager");
getPixelSize(unit, pw, ph);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT cells=" + cells + " nuclei=" + nuclei + " meanArea=" + d2s(sumArea / maxOf(cells, 1), 2) + " unit=" + replace(unit, " ", "_") + " nucleiChannel=" + nucleiChannel + " cellChannel=" + cellChannel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
