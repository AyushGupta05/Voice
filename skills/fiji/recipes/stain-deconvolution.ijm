// @recipe stain-deconvolution
// @title Colour deconvolution of H&E / IHC stains
// @when separate haematoxylin, eosin or DAB from a brightfield RGB image and measure how much of each stain is present ("colour deconvolution", "split the H&E stains", "how much haematoxylin / eosin", "unmix the stains")
// @param vectors = H&E | stain vectors: "H&E", "H&E 2", "H DAB", "H&E DAB", "Feulgen Light Green", "Giemsa", "FastRed FastBlue DAB", "Methyl Green DAB", "Masson Trichrome", "Alcian blue & H", "H PAS", "RGB", "CMY"
// @param method = Default | auto-threshold method applied to each stain image
// @output Three 8-bit stain images "<title>-(Colour_1/2/3)" (dark = more stain). Reports the area fraction of the image (or of the active selection) above threshold for stain 1 and stain 2 and their mean optical density there.
// @say Stain 1 covers {fraction1:%} and stain 2 {fraction2:%} of the {basis}.
vectors = pennyParam("vectors", "H&E");
method = pennyParam("method", "Default");

if (nImages == 0) { print("RESULT error=no_image"); exit; }
// Only the built-in vector sets: "From ROI" and "User values" would wait for the mouse or a dialog.
knownVectors = newArray("H&E", "H&E 2", "H DAB", "H&E DAB", "NBT/BCIP Red Counterstain II", "H DAB NewFuchsin", "H HRP-Green NewFuchsin", "Feulgen LightGreen", "Giemsa", "FastRed FastBlue DAB", "Methyl Green DAB", "H AEC", "Azan-Mallory", "Masson Trichrome", "Alcian blue & H", "H PAS", "Brilliant_Blue", "AstraBlue Fuchsin", "RGB", "CMY");
known = false; for (k = 0; k < knownVectors.length; k++) if (vectors == knownVectors[k]) known = true;
if (!known) { print("RESULT error=unknown_vectors vectors=[" + vectors + "]"); exit; }
if (bitDepth() != 24) { print("RESULT error=needs_rgb_brightfield"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
if (area) Roi.copy();
run("Select None");
run("Colour Deconvolution", "vectors=[" + vectors + "] hide");
fraction = newArray(3); density = newArray(3);
for (s = 1; s <= 3; s++) {
  name = title + "-(Colour_" + s + ")";
  if (!isOpen(name)) { print("RESULT error=missing_stain_image stain=" + s); exit; }
  selectImage(name);
  if (area) Roi.paste();
  setAutoThreshold(method);
  getThreshold(lower, upper);
  // Stained pixels are dark (low transmittance); the mask covers 0..upper.
  getRawStatistics(pixels, mean, min, max, std, histogram);
  inside = 0; sumOD = 0;
  for (v = lower; v <= upper; v++) { inside += histogram[v]; sumOD += histogram[v] * -log(maxOf(v, 1) / 255) / log(10); }
  fraction[s - 1] = inside / pixels; density[s - 1] = sumOD / maxOf(inside, 1);
  resetThreshold();
  run("Select None");
}
selectImage(orig);
if (area) Roi.paste();
basis = "image"; if (area) basis = "selection";
if (!wasBatch) setBatchMode("exit and display");
print("RESULT fraction1=" + d2s(fraction[0], 4) + " fraction2=" + d2s(fraction[1], 4) + " fraction3=" + d2s(fraction[2], 4) + " od1=" + d2s(density[0], 3) + " od2=" + d2s(density[1], 3) + " basis=" + basis + " vectors=[" + vectors + "] method=" + method);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
