// @recipe dab-positive-area
// @title DAB-positive area and positive nuclei (IHC)
// @when IHC quantification on brightfield: how much is DAB / brown positive, Ki-67-style positive versus haematoxylin nuclei ("DAB positive area", "how much is brown", "Ki67 index on this IHC", "percentage positive nuclei")
// @param dabMethod = Default | auto-threshold method for the DAB (brown) image
// @param hemMethod = Huang | auto-threshold method for the haematoxylin (blue) image; pale counterstain needs a looser method (Yen, MaxEntropy, Huang)
// @param minSize = 150 | smallest nucleus counted, pixels² (about a fifth of a typical nucleus)
// @param sigma = 2 | Gaussian blur on the stain images before thresholding, px (0 = none)
// @output Stain images from Colour Deconvolution (H DAB). Reports the DAB-positive area fraction of the image or selection, the DAB-positive objects, the haematoxylin objects without DAB (negative) and the positive percentage. Counts are thresholded objects, not validated cells.
// @say DAB covers {dabFraction:%} of the {basis}; {positive} of {total} nuclei positive, {percentPositive}%.
dabMethod = pennyParam("dabMethod", "Default");
hemMethod = pennyParam("hemMethod", "Huang");
minSize = parseFloat(pennyParam("minSize", "150"));
sigma = parseFloat(pennyParam("sigma", "2"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
if (bitDepth() != 24) { print("RESULT error=needs_rgb_brightfield"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
if (area) getSelectionCoordinates(rx, ry);
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
  if (area) makeSelection("polygon", rx, ry);
  setAutoThreshold(methods[s]);
  run("Select None");
  setOption("BlackBackground", true);
  run("Convert to Mask");
  run("Fill Holes");
  if (area) makeSelection("polygon", rx, ry);
  getStatistics(pixels, mean);
  fractions[s] = mean / 255;
  run("Select None");
}
// Negative nuclei are haematoxylin objects that carry no DAB: the DAB mask is removed from the haematoxylin mask.
imageCalculator("Subtract", "penny-hem-mask", "penny-dab-mask");
counts = newArray(2);
for (s = 0; s < 2; s++) {
  selectImage(masks[s]);
  run("Watershed");
  if (area) makeSelection("polygon", rx, ry);
  run("Analyze Particles...", "size=" + minSize + "-Infinity pixel show=Nothing clear summarize");
  counts[s] = Table.get("Count", Table.size("Summary") - 1, "Summary");
  close(masks[s]);
}
if (isOpen("Summary")) { selectWindow("Summary"); run("Close"); }
positive = counts[1]; total = counts[0] + counts[1];
selectImage(orig);
if (area) makeSelection("polygon", rx, ry);
basis = "image"; if (area) basis = "selection";
if (!wasBatch) setBatchMode("exit and display");
print("RESULT dabFraction=" + d2s(fractions[1], 4) + " hematoxylinFraction=" + d2s(fractions[0], 4) + " positive=" + positive + " negative=" + counts[0] + " total=" + total + " percentPositive=" + d2s(100 * positive / maxOf(total, 1), 1) + " basis=" + basis + " dabMethod=" + dabMethod + " hemMethod=" + hemMethod);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
