// @recipe background-subtract
// @title Rolling-ball background subtraction
// @when remove background, haze, autofluorescence or uneven illumination from the image itself ("subtract the background", "flatten the background", "rolling ball")
// @param radius = 50 | rolling-ball radius, px; larger than the biggest object
// @param light = false | light background (brightfield)
// @param allSlices = true | process every slice/frame/channel of a stack
// @output Changes the pixels of the active image in place (a checkpoint is taken first). Reports the mean intensity before and after.
// @say Background subtracted; mean intensity went from {meanBefore} to {meanAfter}.
radius = parseFloat(pennyParam("radius", "50"));
light = pennyParam("light", "false"); light = (light == "true");
allSlices = pennyParam("allSlices", "true"); allSlices = (allSlices == "true");

if (nImages == 0) { print("RESULT error=no_image"); exit; }
run("Select None");
getStatistics(n, before);
options = "rolling=" + radius;
if (light) options = options + " light";
if (allSlices && nSlices > 1) options = options + " stack";
run("Subtract Background...", options);
getStatistics(n, after);
print("RESULT meanBefore=" + d2s(before, 2) + " meanAfter=" + d2s(after, 2) + " radius=" + radius + " slices=" + nSlices);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
