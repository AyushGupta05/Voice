// @recipe area-fraction
// @title Stained / covered area fraction
// @when what percentage of the image or region is stained, covered, positive or confluent ("percent area", "area fraction", "confluence", "how much of this is fibrosis / collagen / signal")
// @param channel = 1 | channel to measure, 1-based
// @param method = Otsu | auto-threshold method (Otsu, Li, Triangle, Default, Huang, Yen…)
// @param sigma = 1 | Gaussian blur before thresholding, px (0 = none)
// @param dark = true | bright signal on a dark background (fluorescence); false for stain on a light background
// @output Nothing new is opened; the active selection is kept. Reports the thresholded fraction of the selection (or the whole image) and its area.
// @say Signal covers {areaFraction:%} of the {basis}, {area:area}.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Otsu");
sigma = parseFloat(pennyParam("sigma", "1"));
dark = pennyParam("dark", "true"); dark = (dark == "true");

// Temporary windows get a per-run suffix, so cleanup by title can never match one of the researcher's images.
tag = "" + getTime() + "-" + floor(random * 100000);
if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
if (area) Roi.copy();
getDimensions(w, h, channels, slices, frames);
if (channels > 1) Stack.setChannel(channel);
run("Select None");
pennyPlane("penny-fraction-" + tag);
if (bitDepth() == 24) run("8-bit");
if (is("Inverting LUT")) run("Invert LUT");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
if (area) Roi.paste();
if (dark) setAutoThreshold(method + " dark"); else setAutoThreshold(method);
getThreshold(lower, upper);
run("Select None");
setOption("BlackBackground", true);
run("Convert to Mask");
if (area) Roi.paste();
getRawStatistics(pixels, mean);
fraction = mean / 255;
getPixelSize(unit, pw, ph);
close("penny-fraction-" + tag);
selectImage(orig);
if (area) Roi.paste();
basis = "image"; if (area) basis = "selection";
if (!wasBatch) setBatchMode("exit and display");
print("RESULT areaFraction=" + d2s(fraction, 4) + " area=" + d2s(fraction * pixels * pw * ph, 2) + " unit=" + replace(unit, "\\s", "_") + " basis=" + basis + " threshold=" + method + ":" + d2s(lower, 0) + "-" + d2s(upper, 0) + " channel=" + channel);

/** Duplicates only the displayed plane (current channel, slice and frame); a plain Duplicate of a composite z-stack copies every channel. */
function pennyPlane(name) {
  if (Stack.isHyperstack || is("composite")) { Stack.getPosition(pc, pz, pt); run("Duplicate...", "title=[" + name + "] duplicate channels=" + pc + " slices=" + pz + " frames=" + pt); }
  else run("Duplicate...", "title=[" + name + "]");
}
function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
