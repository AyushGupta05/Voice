// @recipe dab-positive-area
// @title DAB-positive area and positive nuclei (IHC)
// @when IHC quantification on brightfield: how much is DAB / brown positive, Ki-67-style positive versus haematoxylin nuclei ("DAB positive area", "how much is brown", "Ki67 index on this IHC", "percentage positive nuclei")
// @param dabMethod = Default | auto-threshold method for the DAB (brown) image
// @param hemMethod = Huang | auto-threshold method for the haematoxylin (blue) image; pale counterstain needs a looser method (Yen, MaxEntropy, Huang)
// @param minSize = 150 | smallest nucleus counted, pixels² (about a fifth of a typical nucleus)
// @param sigma = 2 | Gaussian blur on the stain images before thresholding, px (0 = none)
// @param minDabFraction = 0.3 | a nucleus is positive when at least this share of its pixels is DAB-positive
// @output Stain images from Colour Deconvolution (H DAB). Reports the DAB-positive area fraction of the image or selection and, for the nuclei of the combined haematoxylin + DAB mask, how many are positive (at least minDabFraction of their pixels DAB) or negative, and the positive percentage. Counts are thresholded objects, not validated cells.
// @say DAB covers {dabFraction:%} of the {basis}; {positive} of {total} nuclei positive, {percentPositive}%.
dabMethod = pennyParam("dabMethod", "Default");
hemMethod = pennyParam("hemMethod", "Huang");
minSize = parseFloat(pennyParam("minSize", "150"));
sigma = parseFloat(pennyParam("sigma", "2"));
minDabFraction = parseFloat(pennyParam("minDabFraction", "0.3"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
if (bitDepth() != 24) { print("RESULT error=needs_rgb_brightfield"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
if (area) Roi.copy();
run("Select None");
run("Colour Deconvolution", "vectors=[H DAB] hide");
hem = title + "-(Colour_1)"; dab = title + "-(Colour_2)";
if (!isOpen(hem) || !isOpen(dab)) { print("RESULT error=missing_stain_image"); exit; }
fractions = newArray(2);
masks = newArray("penny-hem-mask", "penny-dab-mask");
names = newArray(hem, dab);
methods = newArray(hemMethod, dabMethod);
for (s = 0; s < 2; s++) {
  selectImage(names[s]);
  run("Duplicate...", "title=" + masks[s]);
  if (sigma > 0) run("Gaussian Blur...", "sigma=" + sigma);
  if (area) Roi.paste();
  setAutoThreshold(methods[s]);
  run("Select None");
  setOption("BlackBackground", true);
  run("Convert to Mask");
  run("Fill Holes");
  if (area) Roi.paste();
  getStatistics(pixels, mean);
  fractions[s] = mean / 255;
  run("Select None");
}
// Nuclei are the objects of the combined (haematoxylin OR DAB) mask; each whole nucleus is then classified by the
// share of its pixels that are DAB-positive, so one nucleus is never counted as both positive and negative.
imageCalculator("OR", "penny-hem-mask", "penny-dab-mask");
selectImage("penny-hem-mask");
run("Fill Holes");
run("Watershed");
if (area) Roi.paste();
run("Set Measurements...", "area mean redirect=penny-dab-mask decimal=3");
run("Clear Results");
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Nothing display clear");
run("Set Measurements...", "area mean redirect=None decimal=3");
total = nResults; positive = 0;
for (i = 0; i < total; i++) if (getResult("Mean", i) / 255 >= minDabFraction) positive++;
run("Clear Results");
close("penny-hem-mask"); close("penny-dab-mask");
counts = newArray(total - positive, positive);
selectImage(orig);
if (area) Roi.paste();
basis = "image"; if (area) basis = "selection";
if (!wasBatch) setBatchMode("exit and display");
print("RESULT dabFraction=" + d2s(fractions[1], 4) + " hematoxylinFraction=" + d2s(fractions[0], 4) + " positive=" + positive + " negative=" + counts[0] + " total=" + total + " percentPositive=" + d2s(100 * positive / maxOf(total, 1), 1) + " minDabFraction=" + minDabFraction + " basis=" + basis + " dabMethod=" + dabMethod + " hemMethod=" + hemMethod);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
