// @recipe clahe
// @title Local contrast enhancement (CLAHE)
// @when make faint or uneven structures visible, enhance local contrast for display or before segmentation ("enhance local contrast", "CLAHE", "I can't see the dim cells")
// @param blocksize = 127 | block size, px (about the size of the features to enhance, larger = gentler)
// @param maximum = 3 | maximum slope (1 = no change, 3 typical)
// @output A new image "<title>-clahe" (current plane); the original is unchanged. Intensities are changed, so measure on the original.
// @say Local contrast enhanced; contrast went from {contrastBefore} to {contrastAfter}.
blocksize = parseFloat(pennyParam("blocksize", "127"));
maximum = parseFloat(pennyParam("maximum", "3"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
run("Select None");
pennyPlane(title + "-clahe");
out = getImageID();
getStatistics(n, mean, min, max, before);
run("Enhance Local Contrast (CLAHE)", "blocksize=" + blocksize + " histogram=256 maximum=" + maximum + " mask=*None*");
getStatistics(n, mean, min, max, after);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT contrastBefore=" + d2s(before, 2) + " contrastAfter=" + d2s(after, 2) + " blocksize=" + blocksize + " maximum=" + maximum);

/** Duplicates only the displayed plane (current channel, slice and frame); a plain Duplicate of a composite z-stack copies every channel. */
function pennyPlane(name) {
  if (Stack.isHyperstack || is("composite")) { Stack.getPosition(pc, pz, pt); run("Duplicate...", "title=[" + name + "] duplicate channels=" + pc + " slices=" + pz + " frames=" + pt); }
  else run("Duplicate...", "title=[" + name + "]");
}
function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
