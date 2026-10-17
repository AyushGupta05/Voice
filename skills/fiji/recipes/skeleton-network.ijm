// @recipe skeleton-network
// @title Skeleton length, branches and junctions of a network
// @when total length / branching of neurites, vessels, filaments, hyphae, mitochondrial networks or roots ("skeletonize", "neurite length", "vessel network", "how branched is it", "Analyze Skeleton")
// @param channel = 1 | channel with the network, 1-based
// @param method = Otsu | auto-threshold method for the network mask
// @param sigma = 1 | Gaussian blur before thresholding, px (0 = none)
// @param tubeness = 0 | enhance tube-like structures first with Tubeness at this scale (sigma in calibrated units; 0 = off). Use about the vessel/neurite radius.
// @param minSize = 20 | remove mask fragments smaller than this before skeletonizing, pixels²
// @param dark = true | bright network on a dark background
// @output Skeleton image "<title>-skeleton" (255 = skeleton) and the totals from Analyze Skeleton (2D/3D): skeletons, branches, junctions, end points and total branch length (calibrated).
// @say Network of {skeletons} pieces: total length {totalLength:len}, {branches} branches, {junctions} junctions.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Otsu");
sigma = parseFloat(pennyParam("sigma", "1"));
tubeness = parseFloat(pennyParam("tubeness", "0"));
minSize = parseFloat(pennyParam("minSize", "20"));
dark = pennyParam("dark", "true"); dark = (dark == "true");

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
if (channels > 1) Stack.setChannel(channel);
run("Select None");
run("Duplicate...", "title=penny-network");
if (bitDepth() == 24) run("8-bit");
if (is("Inverting LUT")) run("Invert LUT");
if (!dark) run("Invert");
if (tubeness > 0) {
  run("Tubeness", "sigma=" + tubeness + " use");
  tube = getImageID();
  close("penny-network");
  selectImage(tube); rename("penny-network");
}
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
setAutoThreshold(method + " dark");
setOption("BlackBackground", true);
run("Convert to Mask");
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Masks");
rename(title + "-skeleton");
skeleton = getImageID();
run("Invert LUT");
close("penny-network");
selectImage(skeleton);
run("Skeletonize (2D/3D)");
getHistogram(values, counts, 256);
skeletonPixels = counts[255];
// Analyze Skeleton's own summary table is not shown in batch mode, so its result object is read directly.
js = "var imp = Packages.ij.WindowManager.getCurrentImage(); var a = new Packages.sc.fiji.analyzeSkeleton.AnalyzeSkeleton_(); a.setup('', imp);"
  + "var r = a.run(Packages.sc.fiji.analyzeSkeleton.AnalyzeSkeleton_.NONE, false, false, null, true, false);"
  + "var n = r.getNumOfTrees(), b = r.getBranches(), j = r.getJunctions(), e = r.getEndPoints(), avg = r.getAverageBranchLength(), tb = 0, tj = 0, te = 0, tl = 0;"
  + "for (var i = 0; i < n; i++) { tb += b[i]; tj += j[i]; te += e[i]; tl += b[i] * avg[i]; }"
  + "n + ' ' + tb + ' ' + tj + ' ' + te + ' ' + tl;";
parts = split(eval("js", js), " ");
skeletons = parseInt(parts[0]); branches = parseInt(parts[1]); junctions = parseInt(parts[2]); endPoints = parseInt(parts[3]); totalLength = parseFloat(parts[4]);
getPixelSize(unit, pw, ph);
selectImage(skeleton);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT skeletons=" + skeletons + " totalLength=" + d2s(totalLength, 2) + " branches=" + branches + " junctions=" + junctions + " endPoints=" + endPoints + " skeletonPixels=" + skeletonPixels + " unit=" + replace(unit, " ", "_") + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
