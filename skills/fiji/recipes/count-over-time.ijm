// @recipe count-over-time
// @title Object count in every frame or slice
// @when how the number of cells, nuclei or spots changes over time or through a stack: proliferation, death, division, per-slice counts ("count cells in every frame", "how many cells over time", "count per slice")
// @param channel = 1 | channel with the objects, 1-based
// @param method = Otsu | auto-threshold method
// @param sigma = 1 | Gaussian blur before thresholding, px (0 = none)
// @param minSize = 20 | smallest object, pixels²
// @param watershed = true | split touching objects
// @param fixed = true | one threshold for all frames (from the first frame) instead of a new one per frame; keeps counts comparable when the intensity fades
// @output Results with one row per frame: Frame, Count, Mean area; inside the active area selection when there is one (the threshold still comes from the whole frame). A z-stack with frames is max-projected per time point first.
// @say Counted from {first} in the first frame to {last} in the last, {min} to {max} over {frames} frames.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Otsu");
sigma = parseFloat(pennyParam("sigma", "1"));
minSize = parseFloat(pennyParam("minSize", "20"));
watershed = pennyParam("watershed", "true"); watershed = (watershed == "true");
fixed = pennyParam("fixed", "true"); fixed = (fixed == "true");

// Temporary windows get a per-run suffix, so cleanup by title can never match one of the researcher's images.
tag = "" + getTime() + "-" + floor(random * 100000);
if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
series = frames; if (frames == 1) series = slices;
if (series < 2) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=not_a_stack"); exit; }
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
if (area) Roi.copy();
run("Select None");
if (channels > 1 || (slices > 1 && frames > 1)) run("Duplicate...", "title=[penny-count-" + tag + "] duplicate channels=" + channel);
else run("Duplicate...", "title=[penny-count-" + tag + "] duplicate");
if (slices > 1 && frames > 1) { run("Z Project...", "projection=[Max Intensity] all"); close("penny-count-" + tag); rename("penny-count-" + tag); }
if (is("Inverting LUT")) run("Invert LUT");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma + " stack");
stack = getImageID();
n = nSlices;
setSlice(1); setAutoThreshold(method + " dark"); getThreshold(lower, upper);
counts = newArray(n); areas = newArray(n);
setOption("BlackBackground", true);
run("Set Measurements...", "area redirect=None decimal=3");
for (t = 1; t <= n; t++) {
  selectImage(stack); setSlice(t);
  run("Duplicate...", "title=[penny-count-frame-" + tag + "]");
  if (fixed) setThreshold(lower, upper); else setAutoThreshold(method + " dark");
  run("Convert to Mask");
  if (watershed) run("Watershed");
  if (area) Roi.paste();
  run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Nothing clear");
  counts[t - 1] = nResults; sum = 0;
  for (i = 0; i < nResults; i++) sum += getResult("Area", i);
  areas[t - 1] = ratio(sum, nResults);
  close("penny-count-frame-" + tag);
}
selectImage(stack); close();
run("Clear Results");
for (t = 0; t < n; t++) { setResult("Frame", t, t + 1); setResult("Count", t, counts[t]); setResult("Mean area", t, areas[t]); }
updateResults();
Array.getStatistics(counts, min, max, mean);
selectImage(orig);
if (area) Roi.paste();
if (!wasBatch) setBatchMode("exit and display");
print("RESULT frames=" + n + " first=" + counts[0] + " last=" + counts[n - 1] + " min=" + min + " max=" + max + " mean=" + d2s(mean, 2) + " threshold=" + method + ":" + d2s(lower, 0) + " channel=" + channel);

/** a / b, or NaN when nothing was measured (b = 0), so an empty result is never reported as a zero mean. */
function ratio(a, b) { if (b == 0) return NaN; return a / b; }
function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
