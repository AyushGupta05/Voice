// @recipe local-threshold
// @title Local (adaptive) threshold for uneven illumination
// @when segment or count objects when the background is uneven, vignetted or shaded ("local threshold", "adaptive threshold", "uneven illumination", "Phansalkar / Bernsen / Sauvola")
// @param channel = 1 | channel to segment, 1-based
// @param method = Mean | Auto Local Threshold method (Mean, Median, MidGrey, Bernsen, Contrast, Niblack, Otsu, Phansalkar, Sauvola); Phansalkar suits low-contrast nuclei on a black background
// @param radius = 15 | local window radius, px (about the object radius or larger)
// @param offset = 10 | Mean/Median/MidGrey only: how far (8-bit grey levels) a pixel must be above its local value to count as object
// @param sigma = 1 | Gaussian blur before thresholding, px (0 = none)
// @param minSize = 20 | smallest object counted, pixels²
// @param dark = true | bright objects on a dark background
// @output A binary mask image "<title>-local-mask" (255 = object); Results with one row per object (area, centroid). Count and covered fraction.
// @say Local threshold found {count} objects covering {areaFraction:%} of the image.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Mean");
radius = parseFloat(pennyParam("radius", "15"));
offset = parseFloat(pennyParam("offset", "10"));
sigma = parseFloat(pennyParam("sigma", "1"));
minSize = parseFloat(pennyParam("minSize", "20"));
dark = pennyParam("dark", "true"); dark = (dark == "true");

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
if (channels > 1) Stack.setChannel(channel);
run("Select None");
run("Duplicate...", "title=[" + title + "-local-mask]");
mask = getImageID();
if (is("Inverting LUT")) run("Invert LUT");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
run("8-bit");
if (!dark) run("Invert");
p1 = 0; if (method == "Mean" || method == "Median" || method == "MidGrey") p1 = -offset;
run("Auto Local Threshold", "method=" + method + " radius=" + radius + " parameter_1=" + p1 + " parameter_2=0 white");
setOption("BlackBackground", true);
setThreshold(255, 255);
run("Convert to Mask");
getStatistics(n, mean);
fraction = mean / 255;
run("Set Measurements...", "area centroid display redirect=None decimal=3");
run("Clear Results");
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Nothing display clear");
count = nResults;
if (!wasBatch) setBatchMode("exit and display");
print("RESULT count=" + count + " areaFraction=" + d2s(fraction, 4) + " method=" + method + " radius=" + radius + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
