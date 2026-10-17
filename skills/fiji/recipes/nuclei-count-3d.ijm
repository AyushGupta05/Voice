// @recipe nuclei-count-3d
// @title Count objects in a 3D stack (3D Objects Counter)
// @when count nuclei, cells or spots in 3D / a z-stack / volume; object volumes in 3D ("how many nuclei in this stack", "3D object count")
// @param channel = 1 | channel with the objects, 1-based
// @param method = Otsu | auto-threshold method over the whole stack histogram
// @param threshold = 0 | explicit intensity threshold (0 = use the auto method)
// @param sigma = 1 | 3D Gaussian blur before counting, px (0 = none)
// @param minVoxels = 10 | smallest object, voxels
// @param excludeEdges = false | drop objects touching the stack edges
// @output Objects map image ("Objects map of …", one label per object); Log summary from 3D Objects Counter; Results with per-object volume, surface, mean intensity and centroid. Current time frame only.
// @say Found {count} objects in 3D, mean volume {meanVolume:volume}.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Otsu");
threshold = parseFloat(pennyParam("threshold", "0"));
sigma = parseFloat(pennyParam("sigma", "1"));
minVoxels = parseFloat(pennyParam("minVoxels", "10"));
excludeEdges = pennyParam("excludeEdges", "false"); excludeEdges = (excludeEdges == "true");

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
if (slices < 2) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=not_a_z_stack slices=" + slices); exit; }
frame = 1; if (frames > 1) Stack.getPosition(c0, z0, frame);
run("Select None");
run("Duplicate...", "title=penny-3d duplicate channels=" + channel + " frames=" + frame);
if (sigma > 0) run("Gaussian Blur 3D...", "x=" + sigma + " y=" + sigma + " z=" + sigma);
if (threshold <= 0) { setAutoThreshold(method + " dark stack"); getThreshold(lower, upper); threshold = lower; resetThreshold(); }
middle = floor(slices / 2) + 1;
run("3D OC Options", "volume surface nb_of_obj._voxels integrated_density mean_gray_value centroid dots_size=5 font_size=10 redirect_to=none");
options = "threshold=" + threshold + " slice=" + middle + " min.=" + minVoxels + " max.=" + (w * h * slices) + " objects statistics summary";
if (excludeEdges) options = options + " exclude_objects_on_edges";
run("Clear Results");
run("3D Objects Counter", options);
count = nResults;
// The column name depends on the calibration unit; read the first column that starts with "Volume".
headings = split(String.getResultsHeadings, "\t");
volumeColumn = "";
for (k = 0; k < headings.length; k++) if (startsWith(headings[k], "Volume")) { volumeColumn = headings[k]; k = headings.length; }
sumVolume = 0;
if (volumeColumn != "") for (i = 0; i < count; i++) sumVolume += getResult(volumeColumn, i);
close("penny-3d");
getVoxelSize(vw, vh, vd, unit);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT count=" + count + " meanVolume=" + d2s(sumVolume / maxOf(count, 1), 2) + " unit=" + replace(unit, " ", "_") + " threshold=" + threshold + " channel=" + channel + " frame=" + frame);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
