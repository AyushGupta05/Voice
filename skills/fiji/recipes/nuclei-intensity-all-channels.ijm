// @recipe nuclei-intensity-all-channels
// @title Per-nucleus intensity in every channel
// @when measure each nucleus or cell in every channel at once: marker expression per cell, nuclear vs channel intensities, multi-channel per-cell table ("measure every channel for each nucleus", "intensity of all markers per cell")
// @param nucleiChannel = 1 | DNA channel used to find the nuclei, 1-based
// @param method = Otsu | auto-threshold method for the nuclei
// @param sigma = 2 | Gaussian blur before thresholding, px
// @param minSize = 50 | smallest nucleus, pixels²
// @output Results with one row per nucleus: Area, X, Y and "Mean C1", "Mean C2", … for every channel; ROI Manager entries and an overlay. Reports the count and each channel's mean over nuclei.
// @say Measured {count} nuclei in {channels} channels.
nucleiChannel = parseFloat(pennyParam("nucleiChannel", "1"));
method = pennyParam("method", "Otsu");
sigma = parseFloat(pennyParam("sigma", "2"));
minSize = parseFloat(pennyParam("minSize", "50"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
getDimensions(w, h, channels, slices, frames);
wasBatch = is("batch mode");
if (!wasBatch && !isOpen("ROI Manager")) run("ROI Manager...");
setBatchMode(true);
run("Select None");
if (channels > 1) Stack.setChannel(nucleiChannel);
run("Duplicate...", "title=penny-nuclei-mask");
if (bitDepth() == 24) run("8-bit");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
setAutoThreshold(method + " dark");
setOption("BlackBackground", true);
run("Convert to Mask"); run("Fill Holes"); run("Watershed");
roiManager("reset");
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Nothing clear add");
close("penny-nuclei-mask");
selectImage(orig);
count = roiManager("count");
run("Clear Results");
means = newArray(channels);
for (i = 0; i < count; i++) {
  roiManager("select", i);
  getStatistics(area);
  setResult("Label", i, title + ":" + (i + 1)); setResult("Area", i, area);
  List.setMeasurements; setResult("X", i, List.getValue("X")); setResult("Y", i, List.getValue("Y"));
  for (c = 1; c <= channels; c++) {
    if (channels > 1) Stack.setChannel(c);
    getStatistics(a, mean);
    setResult("Mean C" + c, i, mean); means[c - 1] += mean;
  }
}
updateResults();
run("Select None");
if (channels > 1) Stack.setChannel(nucleiChannel);
run("Remove Overlay");
if (count > 0) run("From ROI Manager");
summary = "";
for (c = 1; c <= channels; c++) summary = summary + " meanC" + c + "=" + d2s(means[c - 1] / maxOf(count, 1), 2);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT count=" + count + " channels=" + channels + summary + " nucleiChannel=" + nucleiChannel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
