# Biology workflows as parameterised macros for `fiji_run_macro`

These recipes turn the sentences researchers actually say ("segment the nuclei in the blue channel and count them", "how much protein is in this region", "what fraction of the cells are positive", "do the two channels colocalise", "count the nuclei in every image of that folder", "only inside the tumour") into complete ImageJ 1.x macros. Each is a drop-in `code` for `fiji_run_macro`: substitute the `<placeholders>` from the live context or the conversation, leave everything else exactly as written, and quote the final `print` line to the researcher. They compose with the general recipes in [macros](macros.md) (open, split/merge, projection, montage, save) and with the dynamic-macro rules in `SKILL.md`.

Every recipe was executed on the installed Fiji (2.18.0 / ImageJ 1.54p) on 17 Oct 2026 in ImageJ batch mode (`Fiji.app/fiji -batch <file.ijm>`, no windows) against the local copies of public data that `python3 -I script/setup-fiji-samples.py` downloads into `.local/data/fiji` (ImageJ samples, a BBBC039 nuclei subset with ground truth, the TrackMate tutorial time-lapse; licences in `docs/DEMO-FIJI.md`). The “Verified” line under each recipe quotes what it printed for the stated placeholder values. Those numbers are software outputs for those settings on those images, not biological ground truth; say which settings produced a count.

Conventions shared by all recipes:

- They act on the **active image** and its current plane. For a hyperstack, `Stack.setChannel(c)` picks the channel; `Duplicate...` without the `duplicate` keyword copies only the displayed plane, with it the whole stack. Z/T stacks go through recipe B5 first.
- `<nucleiChannel>` is the index of the DNA stain (DAPI/Hoechst; often the blue channel, 3 in FluorescentCells), `<markerChannel>` the protein of interest. Read `image.channels` from `fiji_context`; a single-channel image uses channel 1 and the `Stack.setChannel` lines are harmless.
- `<method>` is an ImageJ auto-threshold name (`Otsu`, `Li`, `Default`, `Triangle`, `Huang`, `Yen`, …; recipe B8 surveys them). `dark` means bright objects on a dark background, which is every fluorescence image; drop it for brightfield/absorbance.
- `<sigma>` (Gaussian blur, px) smooths noise before thresholding: 1 for crisp 8-bit, 2 for 16-bit widefield nuclei, 3 for noisy confocal. `<minSize>` is in pixels² (the `pixel` keyword); typical nuclei are 100–2000 px² at 20×–40×. `<minCircularity>` 0.3 rejects fibres and debris while keeping irregular nuclei.
- `<exclude>` is the word `exclude` to drop objects touching the border (fair for density, wrong when comparing with a ground truth that includes them) or nothing.
- Results go to the Results table (one row per object, `Label` carries the image name) and the objects to the ROI Manager, so `fiji_highlight {rois: "manager"}`, `fiji_results` and `fiji_export_results` work right after. Recipes that build on the manager (B2b, B3) say so.
- `setOption("BlackBackground", true)` makes masks 255 = object. The `redirect=[...]` measurement setting reads intensities from the original image while the particles come from the mask; it is reset to `None` at the end so later measurements behave normally.

## B1. Segment the nuclei in one channel and count them

```ijm
original = getTitle();
run("Select None");
Stack.getDimensions(width, height, channels, slices, frames);
if (channels > 1) Stack.setChannel(<nucleiChannel>);
run("Duplicate...", "title=nuclei");
run("Gaussian Blur...", "sigma=<sigma>");
setAutoThreshold("<method> dark");
getThreshold(lower, upper);
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
run("Watershed");
if (isOpen("ROI Manager")) roiManager("reset");
run("Set Measurements...", "area mean min centroid perimeter shape integrated median display redirect=[" + original + "] decimal=3");
run("Clear Results");
run("Analyze Particles...", "size=<minSize>-Infinity pixel circularity=<minCircularity>-1.00 show=Nothing display <exclude> clear add");
run("Set Measurements...", "area mean min centroid perimeter shape integrated median display redirect=None decimal=3");
close("nuclei");
selectImage(original);
run("Remove Overlay");
if (roiManager("count") > 0) run("From ROI Manager");
count = nResults;
sumArea = 0; sumMean = 0;
for (i = 0; i < count; i++) { sumArea += getResult("Area", i); sumMean += getResult("Mean", i); }
getPixelSize(unit, pw, ph);
print("nuclei=" + count + " channel=<nucleiChannel> threshold=<method> " + d2s(lower, 0) + "-" + d2s(upper, 0) + " sigma=<sigma> minSize=<minSize>px meanArea=" + d2s(sumArea / maxOf(count, 1), 1) + " " + unit + "^2 meanIntensity=" + d2s(sumMean / maxOf(count, 1), 1));
```

What it leaves behind: one ROI per nucleus in the ROI Manager and as an overlay on the original (`fiji_highlight {rois: "manager"}` points at them; `fiji_overlay {clear: true}` removes the outlines), one Results row per nucleus with area, centroid, shape and the intensity read from the nuclear channel of the original. The count is after watershed splitting and the size/circularity filter.

Verified (FluorescentCells.tif, `nucleiChannel` 3 = DAPI, Otsu, sigma 2, minSize 100, minCircularity 0.30, exclude) → `nuclei=15 channel=3 threshold=Otsu 64-255 sigma=2 minSize=100px meanArea=496.4 pixels^2 meanIntensity=124.4`. Verified (BBBC039 `IXMtest_A09_s1_w1…tif`, 16-bit Hoechst, channel 1, Otsu, sigma 2, minSize 100, circularity 0.30, no exclude) → `nuclei=149 channel=1 threshold=Otsu 385-65535 sigma=2 minSize=100px meanArea=591.7 pixels^2 meanIntensity=589.6`; the BBBC039 ground-truth mask for that field has 156 nuclei. A sweep on the same field: without watershed 128 (Otsu, sigma 2, min 100); sigma 1 + min 50 + watershed 151; Triangle + sigma 1 + min 50 + watershed 156; Yen 1 (its threshold 1133 keeps only the brightest nucleus). Say the settings with the count.

Variant, every channel at once ("how many objects are in each channel?"): the same pipeline in a loop, counts only.

```ijm
original = getTitle();
run("Select None");
Stack.getDimensions(width, height, channels, slices, frames);
summary = "";
for (c = 1; c <= channels; c++) {
  selectImage(original);
  if (channels > 1) Stack.setChannel(c);
  run("Duplicate...", "title=ch" + c);
  run("Gaussian Blur...", "sigma=<sigma>");
  setAutoThreshold("<method> dark");
  setOption("BlackBackground", true);
  run("Convert to Mask");
  run("Fill Holes");
  run("Watershed");
  run("Clear Results");
  run("Analyze Particles...", "size=<minSize>-Infinity pixel circularity=<minCircularity>-1.00 show=Nothing display <exclude> clear");
  summary = summary + "channel " + c + ": " + nResults + " objects; ";
  close("ch" + c);
}
selectImage(original);
print(summary + "(<method>, sigma <sigma>, min <minSize> px)");
```

Verified (FluorescentCells.tif, Otsu, sigma 2, min 100, 0.30, exclude) → `channel 1: 49 objects; channel 2: 24 objects; channel 3: 15 objects; (Otsu, sigma 2, min 100 px)` (channel 1 = actin fibres, 2 = tubulin, 3 = nuclei: only channel 3's count means cells).

## B2. Protein intensity in a region, background-corrected

The standard corrected total fluorescence: integrated density of the region minus its area times a background estimate. The background is the modal (most frequent) value of the whole channel, which in a fluorescence image is the unlabeled space; `<background>` can be `Mode` or `Median`, or replace the two whole-image lines with a background ROI the researcher draws.

```ijm
original = getTitle();
Stack.getDimensions(width, height, channels, slices, frames);
if (channels > 1) Stack.setChannel(<markerChannel>);
if (selectionType() == -1) makeRectangle(<x>, <y>, <w>, <h>);
run("Set Measurements...", "area mean standard modal min median integrated display redirect=None decimal=3");
run("Clear Results");
run("Measure");
setResult("Label", 0, "region");
area = getResult("Area", 0); mean = getResult("Mean", 0); intDen = getResult("IntDen", 0); median = getResult("Median", 0); sd = getResult("StdDev", 0);
roiManager("reset");
roiManager("add");
run("Select None");
run("Measure");
setResult("Label", 1, "whole channel <markerChannel>");
background = getResult("<background>", 1);
roiManager("select", 0);
corrected = intDen - area * background;
setResult("Background", 0, background);
setResult("CTCF", 0, corrected);
updateResults();
getPixelSize(unit, pw, ph);
print("region: area=" + d2s(area, 1) + " " + unit + "^2 mean=" + d2s(mean, 2) + " sd=" + d2s(sd, 2) + " median=" + median + " intDen=" + d2s(intDen, 0) + " background(<background> of channel <markerChannel>)=" + background + " meanAboveBackground=" + d2s(mean - background, 2) + " correctedTotal=" + d2s(corrected, 0));
```

The region stays selected (and sits in the ROI Manager as entry 0) so `fiji_highlight {rois: "active"}` points at it. Row 0 of Results is the region with `Background` and `CTCF` columns added; row 1 is the whole channel the background came from.

Verified (FluorescentCells.tif, `markerChannel` 2 = tubulin, rectangle 200,200 100×100, Mode) → `region: area=10000.0 pixels^2 mean=94.19 sd=51.93 median=97 intDen=941901 background(Mode of channel 2)=17 meanAboveBackground=77.19 correctedTotal=771901`.

Per-object table ("intensity of the marker in every nucleus"), after B1 or any recipe that filled the ROI Manager:

```ijm
original = getTitle();
Stack.getDimensions(width, height, channels, slices, frames);
if (channels > 1) Stack.setChannel(<markerChannel>);
n = roiManager("count");
if (n == 0) exit("The ROI Manager is empty; segment the nuclei first (recipe B1).");
run("Select None");
List.setMeasurements;
background = List.getValue("<background>");
run("Clear Results");
sumMean = 0; sumCorrected = 0;
for (i = 0; i < n; i++) {
  roiManager("select", i);
  List.setMeasurements;
  area = List.getValue("Area"); mean = List.getValue("Mean"); intDen = List.getValue("IntDen");
  setResult("Label", i, call("ij.plugin.frame.RoiManager.getName", i));
  setResult("Area", i, area); setResult("Mean", i, mean); setResult("IntDen", i, intDen);
  setResult("MeanAboveBackground", i, mean - background); setResult("CTCF", i, intDen - area * background);
  sumMean += mean; sumCorrected += intDen - area * background;
}
updateResults();
run("Select None");
print("objects=" + n + " channel=<markerChannel> background(<background>)=" + background + " meanIntensity=" + d2s(sumMean / n, 2) + " meanAboveBackground=" + d2s(sumMean / n - background, 2) + " meanCorrectedTotal=" + d2s(sumCorrected / n, 0));
```

`List.setMeasurements` measures the current selection with every measurement without touching the Results table, so the table ends up with exactly one row per object. Columns: `Area`, `Mean`, `IntDen`, `MeanAboveBackground`, `CTCF`.

Verified (FluorescentCells.tif after B1, channel 2, Mode) → `objects=15 channel=2 background(Mode)=17 meanIntensity=103.54 meanAboveBackground=86.54 meanCorrectedTotal=36277`.

## B3. Positive fraction by intensity (a Ki-67-style index on fluorescence)

Objects from the ROI Manager (B1) are called positive when their mean intensity in `<markerChannel>` exceeds a cut-off. `<cutoff>` is a number, or `auto` to take the lower bound of `<method>` on the marker channel of the whole image (the same software threshold the researcher would set by eye in Image ▷ Adjust ▷ Threshold).

```ijm
original = getTitle();
Stack.getDimensions(width, height, channels, slices, frames);
if (channels > 1) Stack.setChannel(<markerChannel>);
n = roiManager("count");
if (n == 0) exit("The ROI Manager is empty; segment the nuclei first (recipe B1).");
run("Select None");
cutoffText = "<cutoff>";
if (cutoffText == "auto") { setAutoThreshold("<method> dark"); getThreshold(cutoff, upper); resetThreshold(); } else cutoff = parseFloat(cutoffText);
run("Clear Results");
positive = 0; positiveList = "";
for (i = 0; i < n; i++) {
  roiManager("select", i);
  getStatistics(area, mean);
  isPositive = mean > cutoff;
  setResult("Label", i, call("ij.plugin.frame.RoiManager.getName", i));
  setResult("Area", i, area); setResult("Mean", i, mean); setResult("Positive", i, isPositive);
  if (isPositive) { positive++; if (lengthOf(positiveList) < 400) positiveList = positiveList + i + ","; }
}
updateResults();
run("Select None");
print("positive=" + positive + " of " + n + " (" + d2s(100 * positive / n, 1) + "%) cutoff=" + d2s(cutoff, 1) + " on channel <markerChannel> (<cutoff>) positiveIndices=" + positiveList);
```

The Results table gets a `Positive` column (1/0) per object; `positiveIndices` lists the ROI Manager indices (truncated after ~400 characters) for `fiji_highlight {rois: "manager", indices: [...]}`. “Stricter” means a higher cut-off: rerun with `<cutoff>` set to the printed value × 1.2 (or the researcher's number); “looser” × 0.8.

Verified (FluorescentCells.tif after B1, `markerChannel` 2, `cutoff` auto with Otsu) → `positive=14 of 15 (93.3%) cutoff=72.0 on channel 2 (auto) positiveIndices=1,2,3,4,5,6,7,8,9,10,11,12,13,14,`; with `cutoff` 100 → `positive=9 of 15 (60.0%) cutoff=100.0 on channel 2 (100) positiveIndices=6,7,8,9,10,11,12,13,14,`. Tubulin is cytoplasmic and present in every cell, so a high positive fraction is what the biology predicts; the recipe is the mechanism for a nuclear marker (Ki-67, EdU, pH3) where the fraction is the result.

## B4. Colocalization of two channels: Pearson and Manders from the pixels

Works everywhere (batch, headless, GUI), on the current plane, inside the active ROI when there is one. Pearson's r is the correlation of the two channels' intensities; Manders' M1/M2 are the fraction of channel-A intensity in pixels where B is above its `<method>` threshold and vice versa. The product image trick (`imageCalculator`) keeps it fast on large planes.

```ijm
original = getTitle();
Stack.getDimensions(width, height, channels, slices, frames);
if (channels < 2) exit("Colocalization needs two channels; " + original + " has " + channels + ".");
roiIndex = -1;
if (selectionType() != -1) { roiManager("add"); roiIndex = roiManager("count") - 1; }
run("Select None");
Stack.setChannel(<channelA>); run("Duplicate...", "title=cA"); run("32-bit");
selectImage(original); Stack.setChannel(<channelB>); run("Duplicate...", "title=cB"); run("32-bit");
imageCalculator("Multiply create 32-bit", "cA", "cB"); rename("cAB");
getPixelSize(unit, pw, ph);
selectImage("cA"); if (roiIndex >= 0) roiManager("select", roiIndex); getStatistics(area, meanA, minA, maxA, sdA); n = area / (pw * ph);
setAutoThreshold("<method> dark"); getThreshold(tA, upperA); resetThreshold();
selectImage("cB"); if (roiIndex >= 0) roiManager("select", roiIndex); getStatistics(area, meanB, minB, maxB, sdB);
setAutoThreshold("<method> dark"); getThreshold(tB, upperB); resetThreshold();
selectImage("cAB"); if (roiIndex >= 0) roiManager("select", roiIndex); getStatistics(area, meanAB);
cov = meanAB - meanA * meanB;
pearson = cov / sqrt(sdA * sdA * (n - 1) / n * sdB * sdB * (n - 1) / n);
selectImage("cA"); run("Select None"); run("Duplicate...", "title=mA"); setThreshold(tA, 1e30); run("Convert to Mask"); run("Divide...", "value=255");
selectImage("cB"); run("Select None"); run("Duplicate...", "title=mB"); setThreshold(tB, 1e30); run("Convert to Mask"); run("Divide...", "value=255");
imageCalculator("Multiply create 32-bit", "cA", "mB"); rename("aInB"); if (roiIndex >= 0) roiManager("select", roiIndex); getStatistics(area, meanAinB);
imageCalculator("Multiply create 32-bit", "cB", "mA"); rename("bInA"); if (roiIndex >= 0) roiManager("select", roiIndex); getStatistics(area, meanBinA);
m1 = meanAinB / meanA; m2 = meanBinA / meanB;
close("cA"); close("cB"); close("cAB"); close("mA"); close("mB"); close("aInB"); close("bInA");
selectImage(original);
if (roiIndex >= 0) { roiManager("select", roiIndex); roiManager("delete"); run("Restore Selection"); }
print("colocalization channels <channelA> vs <channelB>: pearson=" + d2s(pearson, 3) + " mandersM1=" + d2s(m1, 3) + " (fraction of ch<channelA> signal where ch<channelB> > " + d2s(tB, 1) + ") mandersM2=" + d2s(m2, 3) + " (fraction of ch<channelB> signal where ch<channelA> > " + d2s(tA, 1) + ") pixels=" + n + " thresholds=<method>");
```

Interpretation to say out loud: r near 1 means the intensities rise and fall together, near 0 no linear relation, negative mutual exclusion; M1/M2 near 1 mean almost all of one signal sits where the other is above threshold. Pearson is independent of the thresholds; Manders is not, so quote the method.

Verified (FluorescentCells.tif, channels 1 = actin vs 2 = tubulin, Otsu, whole plane) → `colocalization channels 1 vs 2: pearson=0.400 mandersM1=0.371 (fraction of ch1 signal where ch2 > 72.0) mandersM2=0.519 (fraction of ch2 signal where ch1 > 56.0) pixels=262144 thresholds=Otsu`; Coloc 2 on the same two channels reports `Pearson's R value (no threshold), 0.40`, so the pixel formula agrees. With an oval ROI (150,150 200×200) → `pearson=0.176 mandersM1=0.428 (… ch2 > 91.0) mandersM2=0.168 (… ch1 > 93.0) pixels=31428 thresholds=Otsu`.

Full Coloc 2 report (Costes auto-threshold, Spearman, Manders, Li ICQ, randomisation p-value) when the researcher wants the published-style numbers. Coloc 2 is installed (Analyze ▷ Colocalization ▷ Coloc 2). It wants two single-channel images, so split first. In the GUI session it opens its own results window; the macro additionally scans the Log for the key lines in case they are echoed there, and otherwise the summary is empty and the researcher reads the window (`fiji_ui` lists it).

```ijm
original = getTitle();
Stack.getDimensions(width, height, channels, slices, frames);
if (channels < 2) exit("Coloc 2 needs two channels.");
run("Select None");
run("Duplicate...", "title=coloc duplicate");
run("Split Channels");
print("\\Clear");
run("Coloc 2", "channel_1=[C<channelA>-coloc] channel_2=[C<channelB>-coloc] roi_or_mask=<None> threshold_regression=Costes spearman's_rank_correlation manders'_correlation psf=3 costes_randomisations=10");
text = getInfo("log");
lines = split(text, "\n");
summary = "";
for (i = 0; i < lines.length; i++) {
  if (startsWith(lines[i], "Pearson's R value (no threshold)") || startsWith(lines[i], "Spearman's rank correlation value") || startsWith(lines[i], "Manders' tM1") || startsWith(lines[i], "Manders' tM2") || startsWith(lines[i], "Costes P-Value")) summary = summary + replace(lines[i], ", ", "=") + "; ";
}
close("C*-coloc");
selectImage(original);
print("Coloc 2 channels <channelA> vs <channelB>: " + summary);
```

Caveat: Coloc 2 warns when the regression's y-intercept is far from zero (uneven background); say so and offer a background subtraction (`fiji_filter {name: "subtract_background", rolling: 50}` on a duplicate) before trusting Manders. Costes' thresholds differ from the Otsu ones in the pixel recipe, so tM1/tM2 differ from M1/M2 above; Pearson (no threshold) agrees.

Verified in batch mode (FluorescentCells.tif, 1 vs 2): Coloc 2 ran (≈2 s) and printed its table: `Pearson's R value (no threshold), 0.40`, `Spearman's rank correlation value, 0.62370240`, `Manders' tM1 (Above autothreshold of Ch2), 0.565`, `Manders' tM2 (Above autothreshold of Ch1), 0.686`, Costes thresholds 42/48, plus the y-intercept warning. In batch mode that table went to the console, not the Log window, so the macro's summary line came back empty (`Coloc 2 channels 1 vs 2: `); whether the GUI session fills the Log was not verified (the bridge was not available to this check). Treat B4 as the reliable numbers and B4b as the report the researcher can read in Coloc 2's own window.

## B5. Z stacks and 5D hyperstacks: project, then segment

Segment a projection, never the raw 3D stack, unless the researcher asks for 3D objects (then `3D Objects Counter`, see plugins). For a time series pick the frame first; without ` all` Z Project projects only the current frame.

```ijm
original = getTitle();
run("Select None");
Stack.getDimensions(width, height, channels, slices, frames);
if (frames > 1) Stack.setFrame(<frame>);
if (slices > 1) run("Z Project...", "projection=[<projection>]"); else run("Duplicate...", "title=plane");
projected = getTitle();
Stack.getDimensions(width, height, pChannels, pSlices, pFrames);
if (pChannels > 1) Stack.setChannel(<nucleiChannel>);
run("Duplicate...", "title=nuclei");
run("Gaussian Blur...", "sigma=<sigma>");
setAutoThreshold("<method> dark");
getThreshold(lower, upper);
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
run("Watershed");
if (isOpen("ROI Manager")) roiManager("reset");
run("Set Measurements...", "area mean min centroid perimeter shape integrated display redirect=[" + projected + "] decimal=3");
run("Clear Results");
run("Analyze Particles...", "size=<minSize>-Infinity pixel circularity=<minCircularity>-1.00 show=Nothing display <exclude> clear add");
run("Set Measurements...", "area mean min centroid perimeter shape integrated display redirect=None decimal=3");
close("nuclei");
selectImage(projected);
run("Remove Overlay");
if (roiManager("count") > 0) run("From ROI Manager");
count = nResults; sumArea = 0; sumMean = 0;
for (i = 0; i < count; i++) { sumArea += getResult("Area", i); sumMean += getResult("Mean", i); }
getPixelSize(unit, pw, ph);
print("objects=" + count + " in " + projected + " (<projection> of " + slices + " slices, frame <frame>, channel <nucleiChannel>, <method> " + d2s(lower, 0) + "-" + d2s(upper, 0) + ") meanArea=" + d2s(sumArea / maxOf(count, 1), 2) + " " + unit + "^2 meanIntensity=" + d2s(sumMean / maxOf(count, 1), 1));
```

`<projection>` is `Max Intensity` (bright nuclei), `Sum Slices` (total signal; 32-bit), `Average Intensity` or `Median`. The projection stays open and active with the overlay so the researcher sees the outlines on it; `fiji_highlight {rois: "manager"}` points at them there. Areas are in the calibrated unit (µm² for the confocal and mitosis samples).

Verified (confocal-series.tif, 2 channels × 25 slices, 0.05446 µm/px; frame 1, Max Intensity, channel 2, Otsu, sigma 3, minSize 200, 0.30, exclude) → `objects=1 in MAX_confocal-series.tif (Max Intensity of 25 slices, frame 1, channel 2, Otsu 71-255) meanArea=86.11 µm^2 meanIntensity=135.5` (the sample is a single cell; channel 2 segments it as one object); channel 1 → `objects=5 … (Otsu 70-255) meanArea=17.39 µm^2 meanIntensity=110.5`. Verified (mitosis.tif, 2 channels × 5 slices × 51 frames, 0.0885 µm/px; frame 1, Max Intensity, channel 2, Otsu, sigma 2, minSize 50, circularity 0) → `objects=2 in MAX_mitosis.tif (Max Intensity of 5 slices, frame 1, channel 2, Otsu 6253-65535) meanArea=16.11 µm^2 meanIntensity=10125.3` (the two chromosome masses of one dividing cell).

## B6. Batch a folder: nuclei count per image and one CSV

```ijm
input = "<folder>/";
output = "<outputFolder>/";
File.makeDirectory(output);
list = getFileList(input);
setBatchMode(true);
labels = newArray(0); counts = newArray(0); areas = newArray(0); means = newArray(0);
totalNuclei = 0;
for (i = 0; i < list.length; i++) {
  name = toLowerCase(list[i]);
  if (!(endsWith(name, ".tif") || endsWith(name, ".tiff") || endsWith(name, ".png") || endsWith(name, ".jpg"))) continue;
  open(input + list[i]);
  title = getTitle();
  Stack.getDimensions(width, height, channels, slices, frames);
  if (channels > 1) Stack.setChannel(<nucleiChannel>);
  run("Duplicate...", "title=nuclei");
  run("Gaussian Blur...", "sigma=<sigma>");
  setAutoThreshold("<method> dark");
  setOption("BlackBackground", true);
  run("Convert to Mask");
  run("Fill Holes");
  run("Watershed");
  if (isOpen("ROI Manager")) roiManager("reset");
  run("Analyze Particles...", "size=<minSize>-Infinity pixel circularity=<minCircularity>-1.00 show=Masks <exclude> add");
  saveAs("PNG", output + File.nameWithoutExtension + "_mask.png");
  n = roiManager("count");
  selectImage(title);
  sumArea = 0; sumMean = 0;
  for (k = 0; k < n; k++) { roiManager("select", k); getStatistics(area, mean); sumArea += area; sumMean += mean; }
  run("Select None");
  labels = Array.concat(labels, title); counts = Array.concat(counts, n); areas = Array.concat(areas, sumArea / maxOf(n, 1)); means = Array.concat(means, sumMean / maxOf(n, 1));
  totalNuclei += n;
  close("*");
}
setBatchMode(false);
if (isOpen("ROI Manager")) roiManager("reset");
run("Clear Results");
for (i = 0; i < labels.length; i++) { setResult("Label", i, labels[i]); setResult("Count", i, counts[i]); setResult("MeanArea", i, areas[i]); setResult("MeanIntensity", i, means[i]); }
updateResults();
saveAs("Results", output + "nuclei-per-image.csv");
print("processed " + labels.length + " images, " + totalNuclei + " nuclei total (<method>, sigma <sigma>, min <minSize> px) -> " + output + "nuclei-per-image.csv");
```

One Results row per image (`Label`, `Count`, `MeanArea`, `MeanIntensity`) saved as CSV, plus a mask PNG per image (`show=Masks` draws the kept particles). The ROI Manager is cleared at the end so it does not hold the last image's nuclei. For Bio-Formats files replace `open(...)` with the importer line from macros recipe 15; for a different count per channel change `<nucleiChannel>`.

Verified (`.local/data/fiji/bbbc039/images`, the 10-field BBBC039 test subset, channel 1, Otsu, sigma 2, minSize 100, 0.30, no exclude) → `processed 10 images, 963 nuclei total (Otsu, sigma 2, min 100 px) -> …/nuclei-per-image.csv` in ≈ 12 s. Per field (count / ground truth from the BBBC039 masks): A09 149/156, A12 18/69, A16 84/93, A22 83/95, B02 60/66, B04 105/122, B05 104/115, C07 138/156, C14 119/137, C23 103/119. A12 is a dim field where Otsu keeps only the brightest nuclei: the example of why to run B8 and say which method was used.

## B7. Inside a region the researcher drew ("the tumour"): segment, count, density, intensity

The active ROI (rectangle, oval, polygon or freehand drawn with the mouse; see the SKILL rules for resolving "here") bounds the analysis: the threshold is computed from the region's own histogram, only particles inside it are counted, and the per-area density uses the region's area. Nothing outside is touched.

```ijm
original = getTitle();
if (selectionType() == -1) makeOval(<x>, <y>, <w>, <h>);
if (isOpen("ROI Manager")) roiManager("reset");
roiManager("add");
roiManager("select", 0);
roiManager("rename", "region");
Stack.getDimensions(width, height, channels, slices, frames);
if (channels > 1) Stack.setChannel(<nucleiChannel>);
getStatistics(regionArea, regionMean);
run("Select None");
run("Duplicate...", "title=mask");
roiManager("select", 0);
run("Gaussian Blur...", "sigma=<sigma>");
setAutoThreshold("<method> dark");
getThreshold(lower, upper);
setOption("BlackBackground", true);
run("Convert to Mask");
roiManager("select", 0);
run("Fill Holes");
run("Watershed");
roiManager("select", 0);
run("Set Measurements...", "area mean min centroid perimeter shape integrated display redirect=[" + original + "] decimal=3");
run("Clear Results");
run("Analyze Particles...", "size=<minSize>-Infinity pixel circularity=<minCircularity>-1.00 show=Nothing display <exclude> clear add");
run("Set Measurements...", "area mean min centroid perimeter shape integrated display redirect=None decimal=3");
close("mask");
selectImage(original);
run("Remove Overlay");
run("From ROI Manager");
roiManager("select", 0);
count = nResults; sumArea = 0; sumMean = 0;
for (i = 0; i < count; i++) { sumArea += getResult("Area", i); sumMean += getResult("Mean", i); }
getPixelSize(unit, pw, ph);
print("region area=" + d2s(regionArea, 0) + " " + unit + "^2: objects=" + count + " density=" + d2s(1000 * count / regionArea, 2) + " per 1000 " + unit + "^2 objectArea=" + d2s(100 * sumArea / regionArea, 1) + "% meanObjectArea=" + d2s(sumArea / maxOf(count, 1), 1) + " " + unit + "^2 meanIntensity=" + d2s(sumMean / maxOf(count, 1), 1) + " (<method> " + d2s(lower, 0) + "-" + d2s(upper, 0) + " inside the region)");
```

ROI Manager entry 0 is the region (named `region`), entries 1..n the objects inside it; the overlay shows both and the region stays selected afterwards. Analyze Particles treats the selection as a mask, so objects crossing its boundary are clipped at the boundary and `exclude` additionally drops those touching it. The intensity is read from the original's displayed channel (set `Stack.setChannel` to the marker instead of the nuclei when the question is "protein inside the tumour", or run B2b afterwards for a per-object table in another channel).

Verified (BBBC039 A09, oval 150,100 300×300 as the region, channel 1, Otsu, sigma 2, minSize 100, 0.30, exclude) → `region area=70688 pixels^2: objects=25 density=0.35 per 1000 pixels^2 objectArea=22.2% meanObjectArea=628.4 pixels^2 meanIntensity=578.0 (Otsu 368-65535 inside the region)`; ROI Manager: `region` + 25 objects.

## B8. Stricter, looser, or “which threshold?”

Survey of the auto-threshold methods on the same image with the same cleanup and filters, so the researcher can choose by looking (`fiji_highlight` after rerunning B1 with the chosen method):

```ijm
original = getTitle();
run("Select None");
Stack.getDimensions(width, height, channels, slices, frames);
if (channels > 1) Stack.setChannel(<nucleiChannel>);
methods = newArray("Default", "Huang", "Li", "Otsu", "Triangle", "Yen");
line = "";
for (m = 0; m < methods.length; m++) {
  selectImage(original);
  run("Duplicate...", "title=survey");
  run("Gaussian Blur...", "sigma=<sigma>");
  setAutoThreshold(methods[m] + " dark");
  getThreshold(lower, upper);
  setOption("BlackBackground", true);
  run("Convert to Mask");
  run("Fill Holes");
  run("Watershed");
  run("Clear Results");
  run("Analyze Particles...", "size=<minSize>-Infinity pixel circularity=<minCircularity>-1.00 show=Nothing display <exclude> clear");
  line = line + methods[m] + "=" + nResults + " (from " + d2s(lower, 0) + "); ";
  close("survey");
}
selectImage(original);
print("objects by threshold method (sigma <sigma>, min <minSize> px): " + line);
```

Verified (BBBC039 A09, channel 1, sigma 2, minSize 100, 0.30, no exclude) → `objects by threshold method (sigma 2, min 100 px): Default=149 (from 385); Huang=151 (from 259); Li=152 (from 292); Otsu=149 (from 385); Triangle=149 (from 226); Yen=1 (from 1133);` (ground truth 156). ≈ 5 s.

Stricter or looser version of the last segmentation, keeping the pipeline: `<factor>` raises (positive) or lowers (negative) the lower bound by that fraction of the range between the auto threshold and the brightest pixel actually present (not the 16-bit maximum), as in macros recipe 4 but with the nuclei cleanup (blur, fill holes, watershed). "Stricter" also commonly means a larger `<minSize>` or `<minCircularity>`: say which knob you turned.

```ijm
original = getTitle();
run("Select None");
Stack.getDimensions(width, height, channels, slices, frames);
if (channels > 1) Stack.setChannel(<nucleiChannel>);
run("Duplicate...", "title=nuclei");
run("Gaussian Blur...", "sigma=<sigma>");
setAutoThreshold("<method> dark");
getThreshold(lower, upper);
getRawStatistics(nPixels, mean, min, max);
top = minOf(upper, max);
lower = lower + <factor> * (top - lower);
setThreshold(lower, upper);
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
run("Watershed");
if (isOpen("ROI Manager")) roiManager("reset");
run("Set Measurements...", "area mean centroid shape display redirect=[" + original + "] decimal=3");
run("Clear Results");
run("Analyze Particles...", "size=<minSize>-Infinity pixel circularity=<minCircularity>-1.00 show=Nothing display <exclude> clear add");
run("Set Measurements...", "area mean centroid shape display redirect=None decimal=3");
close("nuclei");
selectImage(original);
run("Remove Overlay");
if (roiManager("count") > 0) run("From ROI Manager");
print("nuclei=" + nResults + " with the <method> threshold moved to " + d2s(lower, 0) + "-" + d2s(upper, 0) + " (factor <factor>, sigma <sigma>, min <minSize> px)");
```

Verified (BBBC039 A09, Otsu, sigma 2, minSize 100, 0.30, no exclude; auto threshold 385, brightest pixel 1720): `factor` 0.2 → `nuclei=92 with the Otsu threshold moved to 612-65535 (factor 0.2, sigma 2, min 100 px)`; `factor` −0.2 → `nuclei=72 with the Otsu threshold moved to 158-65535 (factor -0.2, sigma 2, min 100 px)`. Looser is not always more: at 158 neighbouring nuclei fuse into clumps that watershed cannot split, so the count falls while the object area rises. Report both the count and the mean area when a threshold changes.

## Choosing parameters from what the researcher said

| They say | Recipe | Placeholders |
|---|---|---|
| "segment / count the nuclei (in the blue channel)" | B1 | `nucleiChannel` = DAPI/Hoechst channel; `method` Otsu; `sigma` 1 (8-bit) or 2 (16-bit); `minSize` from the image: about (nucleus diameter in px)² / 2; `minCircularity` 0.3 |
| "how many cells are in each channel" | B1 variant | same, loop |
| "measure the protein / intensity in this region" | B2 | `markerChannel`; active ROI; `background` Mode |
| "intensity of X in every nucleus" | B1 then B2b | `markerChannel` = X's channel |
| "what fraction of cells are positive for X" | B1 then B3 | `cutoff` auto with Otsu, or the researcher's number |
| "do the two channels colocalise" | B4 (and B4b for the full report) | `channelA`, `channelB`; `method` Otsu |
| "it's a stack / over Z / at time point t" | B5 then any | `projection` Max Intensity, `frame` |
| "do this for every image in the folder" | B6 | `folder`, `outputFolder` (`.local/exports/<name>`) |
| "only inside the tumour / this region" | B7 | active ROI; then B2b or B3 for the marker |
| "that threshold is too loose / too strict / try another method" | B8, B8b, macros 4 | `factor` ±0.2; or a different `method` / `minSize` |
| "track the cells over time" | [tracking](tracking.md) | TrackMate script |

Always finish with the numbers from the printed line, then `fiji_highlight {rois: "manager"}` (or `"active"`) so the researcher sees what was counted, and offer the CSV (`fiji_export_results`) when there is a table.
