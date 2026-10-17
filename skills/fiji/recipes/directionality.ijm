// @recipe directionality
// @title Fibre orientation (Directionality)
// @when orientation, alignment or anisotropy of fibres, collagen, actin, microtubules or tissue ("which direction do the fibres run", "are they aligned", "fibre orientation", "directionality")
// @param channel = 1 | channel with the fibres, 1-based
// @param method = Fourier components | "Fourier components" or "Local gradient orientation"
// @param bins = 90 | histogram bins over −90…90°
// @output Nothing new is opened. Reports the dominant direction from Directionality (0° = horizontal, counter-clockwise positive), its dispersion, the amount of structure in that peak and the goodness of the Gaussian fit.
// @say Fibres run at {direction} degrees, spread {dispersion} degrees.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Fourier components");
bins = parseFloat(pennyParam("bins", "90"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
if (channels > 1) Stack.setChannel(channel);
run("Select None");
run("Duplicate...", "title=penny-fibres");
if (bitDepth() == 24) run("8-bit");
// The plugin's analysis table is not shown in batch mode, so its fit is read from the plugin object.
kind = "FOURIER_COMPONENTS"; if (startsWith(toLowerCase(method), "local")) kind = "LOCAL_GRADIENT_ORIENTATION";
js = "var imp = Packages.ij.WindowManager.getCurrentImage(); var D = Packages.fiji.analyze.directionality.Directionality_; var d = new D(); d.setImagePlus(imp);"
  + "d.setMethod(D.AnalysisMethod." + kind + "); d.setBinNumber(" + bins + "); d.setBinStart(-90); d.setBuildOrientationMapFlag(false);"
  + "d.computeHistograms(); d.fitHistograms(); var f = d.getFitAnalysis().get(0);"
  + "(f[0] * 180 / Math.PI) + ' ' + (f[1] * 180 / Math.PI) + ' ' + f[2] + ' ' + f[3];";
parts = split(eval("js", js), " ");
direction = parseFloat(parts[0]); dispersion = parseFloat(parts[1]); amount = parseFloat(parts[2]); goodness = parseFloat(parts[3]);
close("penny-fibres");
selectImage(orig);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT direction=" + d2s(direction, 2) + " dispersion=" + d2s(dispersion, 2) + " amount=" + d2s(amount, 3) + " goodness=" + d2s(goodness, 3) + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
