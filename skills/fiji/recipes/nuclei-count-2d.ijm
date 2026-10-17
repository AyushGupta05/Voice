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
run("Duplicate...", "title=penny-nuclei-mask");
if (bitDepth() == 24) run("8-bit");
if (is("Inverting LUT")) run("Invert LUT");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
if (dark) setAutoThreshold(method + " dark"); else setAutoThreshold(method);
getThreshold(lower, upper);
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
if (watershed) run("Watershed");
if (area) Roi.paste();
run("Set Measurements...", "area mean centroid perimeter shape display redirect=[" + title + "] decimal=3");
run("Clear Results");
roiManager("reset");
max = "Infinity"; if (maxSize > 0) max = "" + maxSize;
options = "size=" + minSize + "-" + max + " pixel circularity=" + minCircularity + "-1.00 show=Nothing display clear add";
if (exclude) options = options + " exclude";
run("Analyze Particles...", options);
run("Set Measurements...", "area mean centroid perimeter shape display redirect=None decimal=3");
close("penny-nuclei-mask");
selectImage(orig);
if (area) Roi.paste();
count = nResults; sumArea = 0; sumMean = 0;
for (i = 0; i < count; i++) { sumArea += getResult("Area", i); sumMean += getResult("Mean", i); }
run("Remove Overlay");
if (count > 0) { run("From ROI Manager"); }
getPixelSize(unit, pw, ph);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT count=" + count + " meanArea=" + d2s(sumArea / maxOf(count, 1), 2) + " meanIntensity=" + d2s(sumMean / maxOf(count, 1), 2) + " unit=" + replace(unit, " ", "_") + " channel=" + channel + " threshold=" + method + ":" + d2s(lower, 0) + "-" + d2s(upper, 0));

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
