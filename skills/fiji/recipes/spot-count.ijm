// @recipe spot-count
// @title Count spots or puncta with Find Maxima
// @when count spots, puncta, foci, vesicles, FISH dots or peaks without a threshold ("count the spots", "how many foci per image", "find maxima")
// @param channel = 1 | channel with the spots, 1-based
// @param prominence = 20 | how much a spot must stand out from its surroundings (intensity units); raise it to ignore noise
// @param sigma = 1 | Gaussian blur before detection, px (0 = none)
// @param background = 0 | rolling-ball radius for background subtraction before detection, px (0 = none; use about 3× the spot radius)
// @param tophat = 0 | white top-hat radius, px, to remove uneven background around small spots (0 = none)
// @param excludeEdges = false | ignore maxima on the image edge
// @param light = false | dark spots on a light background
// @output A point selection on the original image marking every spot (inside the active area selection when there is one); a Results row per spot with X, Y and the intensity of the original.
// @say Found {count} spots.
channel = parseFloat(pennyParam("channel", "1"));
prominence = parseFloat(pennyParam("prominence", "20"));
sigma = parseFloat(pennyParam("sigma", "1"));
background = parseFloat(pennyParam("background", "0"));
tophat = parseFloat(pennyParam("tophat", "0"));
excludeEdges = pennyParam("excludeEdges", "false"); excludeEdges = (excludeEdges == "true");
light = pennyParam("light", "false"); light = (light == "true");

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
if (area) getSelectionCoordinates(rx, ry);
getDimensions(w, h, channels, slices, frames);
if (channels > 1) Stack.setChannel(channel);
run("Select None");
run("Duplicate...", "title=penny-spots");
if (bitDepth() == 24) run("8-bit");
if (is("Inverting LUT")) run("Invert LUT");
if (light) run("Invert");
run("32-bit");
if (background > 0) run("Subtract Background...", "rolling=" + background);
if (tophat > 0) run("Top Hat...", "radius=" + tophat);
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
if (area) makeSelection("polygon", rx, ry);
options = "prominence=" + prominence + " output=[Point Selection]";
if (excludeEdges) options = options + " exclude";
run("Find Maxima...", options);
count = 0;
if (selectionType() == 10) { getSelectionCoordinates(xs, ys); count = xs.length; }
close("penny-spots");
selectImage(orig);
run("Clear Results");
sum = 0;
for (i = 0; i < count; i++) { v = getPixel(xs[i], ys[i]); setResult("X", i, xs[i]); setResult("Y", i, ys[i]); setResult("Value", i, v); sum += v; }
if (count > 0) makeSelection("point", xs, ys); else if (area) makeSelection("polygon", rx, ry); else run("Select None");
updateResults();
if (!wasBatch) setBatchMode("exit and display");
print("RESULT count=" + count + " meanValue=" + d2s(sum / maxOf(count, 1), 2) + " prominence=" + prominence + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
