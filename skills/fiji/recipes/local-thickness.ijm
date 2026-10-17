// @recipe local-thickness
// @title Local thickness map
// @when thickness / width / diameter of structures, walls, fibres, vessels or pores ("local thickness", "how thick are these", "vessel diameter map", "trabecular thickness")
// @param channel = 1 | channel to measure, 1-based
// @param method = Otsu | auto-threshold method for the structure mask
// @param sigma = 1 | Gaussian blur before thresholding, px (0 = none)
// @param dark = true | bright structures on a dark background
// @output Current plane only (Local Thickness is 2D here; project a z-stack first). 32-bit thickness map "<title>-thickness" (each pixel = diameter of the largest disc that fits there, calibrated), shown with the Fire LUT. Reports mean and maximum thickness over the structure.
// @say Mean thickness {meanThickness:len}, maximum {maxThickness:len}.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Otsu");
sigma = parseFloat(pennyParam("sigma", "1"));
dark = pennyParam("dark", "true"); dark = (dark == "true");

// Temporary windows get a per-run suffix, so cleanup by title can never match one of the researcher's images.
tag = "" + getTime() + "-" + floor(random * 100000);
if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
if (channels > 1) Stack.setChannel(channel);
run("Select None");
pennyPlane("penny-thickness-mask-" + tag);
if (bitDepth() == 24) run("8-bit");
if (is("Inverting LUT")) run("Invert LUT");
if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
if (dark) setAutoThreshold(method + " dark"); else setAutoThreshold(method);
setOption("BlackBackground", true);
run("Convert to Mask");
run("Local Thickness (masked, calibrated, silent)");
rename(title + "-thickness");
map = getImageID();
setThreshold(0.0001, 1e30);
run("Set Measurements...", "area mean min limit redirect=None decimal=3");
List.setMeasurements("limit");
meanThickness = List.getValue("Mean"); maxThickness = List.getValue("Max");
resetThreshold();
run("Set Measurements...", "area mean min redirect=None decimal=3");
run("Fire");
close("penny-thickness-mask-" + tag);
selectImage(map);
getPixelSize(unit, pw, ph);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT meanThickness=" + d2s(meanThickness, 3) + " maxThickness=" + d2s(maxThickness, 3) + " unit=" + replace(unit, "\\s", "_") + " channel=" + channel);

/** Duplicates only the displayed plane (current channel, slice and frame); a plain Duplicate of a composite z-stack copies every channel. */
function pennyPlane(name) {
  if (Stack.isHyperstack || is("composite")) { Stack.getPosition(pc, pz, pt); run("Duplicate...", "title=[" + name + "] duplicate channels=" + pc + " slices=" + pz + " frames=" + pt); }
  else run("Duplicate...", "title=[" + name + "]");
}
function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
