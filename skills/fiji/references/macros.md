# Verified ImageJ macro recipes for `fiji_run_macro`

Each recipe is a complete ImageJ 1.x macro (`.ijm`) for `fiji_run_macro {code}`. Replace `<placeholders>` with values from the context or the conversation; leave the rest exactly as written. Every recipe ends with a `print(...)` whose text is returned in the tool result, so the sentence to the researcher can quote it. All 17 recipes were executed on the installed Fiji (2.18.0 / ImageJ 1.54p) on 17 Oct 2026 against the public samples with the launcher in ImageJ batch mode (`Fiji.app/fiji --java-home <jdk> -batch <file.ijm>`, no windows shown); the “Verified” line under each recipe says with which image and what it printed. The ROI Manager cannot be created under `--headless` (`java.awt.HeadlessException`), so batch mode, which matches the GUI session's behaviour, was used. Macro functions are the built-in ones (`.local/upstream/imagej-docs/macro-functions.md`; `fiji_knowledge` searches it).

Rules of thumb: a macro acts on the active image; `run("Select None")` first when the researcher wants the whole image, keep the ROI when they mean “here”. `setOption("BlackBackground", true)` before `Convert to Mask` so objects are 255. `if (isOpen("ROI Manager")) roiManager("reset");` before adding particles so counts describe one run (`RoiManager.size` works whether or not the manager window exists). Read manager entries with a `roiManager("select", i); getStatistics(area, mean);` loop and `setResult(...)`, which works in every mode; `roiManager("Measure")` (and `fiji_measure {scope: "manager"}`) fills the Results table directly in the GUI session. `nResults`, `getResult("Column", row)` and `getStatistics(...)` give numbers without reading tables. Prefer `selectImage(title)` over window clicks. `setBatchMode(true)` hides windows and is much faster in loops.

## 1. Open a sample and report what it is

```ijm
run("<Blobs>");
getDimensions(width, height, channels, slices, frames);
getPixelSize(unit, pixelWidth, pixelHeight);
print(getTitle() + ": " + width + "x" + height + ", " + channels + " channel(s), " + slices + " slice(s), " + frames + " frame(s), " + bitDepth() + "-bit, " + pixelWidth + " " + unit + "/px, inverting LUT=" + is("Inverting LUT"));
```

Sample names are in [samples](samples.md). For a file: `open("<path>");` (Bio-Formats fallback: recipe 15). Verified (batch mode, Blobs) → `blobs.gif: 256x254, 1 channel(s), 1 slice(s), 1 frame(s), 8-bit, 1 pixels/px, inverting LUT=1`.

## 2. Segment cells: duplicate → 8-bit → blur → auto threshold → mask → fill holes → watershed → particles → measure on the original

```ijm
original = getTitle();
run("Select None");
run("Duplicate...", "title=mask");
run("8-bit");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Default<dark>");
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
run("Watershed");
if (isOpen("ROI Manager")) roiManager("reset");
run("Set Measurements...", "area mean min perimeter shape feret's integrated median display redirect=[" + original + "] decimal=3");
run("Clear Results");
run("Analyze Particles...", "size=<50>-Infinity pixel circularity=0.00-1.00 show=Nothing display exclude clear add");
run("Set Measurements...", "area mean min perimeter shape feret's integrated median display redirect=None decimal=3");
close("mask");
selectImage(original);
count = nResults;
sumArea = 0; sumMean = 0;
for (i = 0; i < count; i++) { sumArea += getResult("Area", i); sumMean += getResult("Mean", i); }
getPixelSize(unit, pw, ph);
print("cells=" + count + " meanArea=" + d2s(sumArea / maxOf(count, 1), 1) + " " + unit + "^2 meanIntensity=" + d2s(sumMean / maxOf(count, 1), 1) + " (ROI Manager: " + roiManager("count") + ")");
```

`<dark>` is ` dark` when the background is dark (fluorescence), empty for dark objects on a light background or for Blobs' inverting LUT (ImageJ interprets “dark” in display terms). `<50>` is the minimum particle area in pixels (`pixel` keyword); drop `pixel` to use calibrated units². `exclude` drops border objects. This is what `fiji_segment_cells` does. Intensities come from the original image through `redirect=[original]` (the standard ImageJ way to measure a mask's objects on another image); the redirect is reset afterwards so later measurements are not redirected. The particles are also added to the ROI Manager for `fiji_highlight {rois: "manager"}` and recipe 3. Verified (Blobs, `<dark>` empty, min 50) → `cells=48 meanArea=369.2 pixels^2 meanIntensity=191.6 (ROI Manager: 48)`. Without the blur and watershed the same threshold and size filter give 43 particles; without `exclude` 64: say which settings produced the count.

## 3. Count and mean area of what is already in the ROI Manager

```ijm
n = roiManager("count");
if (n == 0) exit("The ROI Manager is empty; segment first.");
getPixelSize(unit, pw, ph);
run("Clear Results");
sum = 0; minA = 1e99; maxA = 0;
for (i = 0; i < n; i++) {
  roiManager("select", i);
  getStatistics(area, mean, min, max, std);
  setResult("Label", i, call("ij.plugin.frame.RoiManager.getName", i));
  setResult("Area", i, area); setResult("Mean", i, mean); setResult("Min", i, min); setResult("Max", i, max); setResult("StdDev", i, std);
  sum += area; minA = minOf(minA, area); maxA = maxOf(maxA, area);
}
updateResults();
run("Select None");
print("objects=" + n + " meanArea=" + d2s(sum / n, 1) + " " + unit + "^2 (min " + d2s(minA, 1) + ", max " + d2s(maxA, 1) + ")");
```

Verified after recipe 2 on Blobs → `objects=48 meanArea=369.2 pixels^2 (min 74.0, max 848.0)`.

## 4. Stricter or looser threshold, then recount with the same particle settings

```ijm
if (is("binary")) exit("Active image is already a binary mask; run this on the grayscale image.");
getThreshold(lower, upper);
if (lower == -1) { setAutoThreshold("Default<dark>"); getThreshold(lower, upper); }
maxValue = 255; if (bitDepth() == 16) maxValue = 65535;
range = upper - lower;
if (upper >= maxValue) lower = lower + <0.2> * range; else upper = upper - <0.2> * range;
setThreshold(lower, upper);
if (isOpen("ROI Manager")) roiManager("reset");
run("Clear Results");
run("Analyze Particles...", "size=<50>-Infinity pixel circularity=0.00-1.00 show=Nothing display exclude clear add");
print("threshold=" + d2s(lower, 0) + "-" + d2s(upper, 0) + " cells=" + nResults);
```

`<0.2>` positive = stricter (shrinks the object range by 20 %), negative = looser. Bright objects (upper at the maximum) move the lower bound; dark objects move the upper bound. Works on 8-bit and 16-bit grayscale (RGB: duplicate and `run("8-bit")` first). Verified: Blobs after `setAutoThreshold("Default")`, factor 0.2 → `threshold=152-255 cells=43` (48 before tightening).

## 5. Measure the current selection (or a given rectangle)

```ijm
if (selectionType() == -1) makeRectangle(<100>, <100>, <60>, <60>);
run("Set Measurements...", "area mean standard min median integrated display redirect=None decimal=3");
run("Measure");
r = nResults - 1;
getPixelSize(unit, pw, ph);
print("area=" + getResult("Area", r) + " " + unit + "^2 mean=" + getResult("Mean", r) + " sd=" + getResult("StdDev", r) + " min=" + getResult("Min", r) + " max=" + getResult("Max", r) + " median=" + getResult("Median", r) + " intDen=" + getResult("IntDen", r));
```

Oval: `makeOval(x, y, w, h)`; polygon: `makePolygon(x1, y1, x2, y2, ...)`; line: `makeLine(x1, y1, x2, y2)` then `Length`. Verified: Blobs with the 60×60 rectangle at (100, 100) → `area=3600 pixels^2 mean=100.1778 sd=65.3403 min=24 max=232 median=64 intDen=360640`.

## 6. Split a multichannel image and merge selected channels back

```ijm
run("<Fluorescent Cells>");
title = getTitle();
run("Split Channels");
run("Merge Channels...", "c1=[C1-" + title + "] c2=[C2-" + title + "] c3=[C3-" + title + "] create");
rename("merged");
Stack.getDimensions(width, height, channels, slices, frames);
print("merged " + channels + " channels of " + title + " into " + getTitle() + " (" + width + "x" + height + ", composite=" + is("composite") + ")");
```

Merge slots: `c1` red, `c2` green, `c3` blue, `c4` gray, `c5` cyan, `c6` magenta, `c7` yellow; `create` makes a composite (omit for RGB), `keep` keeps the sources. Split windows are named `C1-<title>`, `C2-<title>`… Verified → `merged 3 channels of FluorescentCells.tif into merged (512x512, composite=1)`.

## 7. Maximum-intensity Z projection (whole hyperstack or one channel)

```ijm
run("<Confocal Series>");
source = getTitle();
run("Z Project...", "projection=[Max Intensity]");
projected = getTitle();
Stack.getDimensions(width, height, channels, slices, frames);
print(projected + ": " + channels + " channel(s) x " + slices + " slice(s), from " + source);
```

Other projections: `[Average Intensity]`, `[Sum Slices]` (becomes 32-bit), `[Min Intensity]`, `[Standard Deviation]`, `[Median]`; a range: `"start=5 stop=15 projection=[Max Intensity]"`; every frame of a time series: add ` all`. One channel only: `run("Duplicate...", "title=c2 duplicate channels=2");` first. Verified → `MAX_confocal-series.tif: 2 channel(s) x 1 slice(s), from confocal-series.tif`.

## 8. Nuclear mask from one channel, intensity read from another

```ijm
run("<Fluorescent Cells>");
original = getTitle();
run("Select None");
run("Duplicate...", "title=nuclei duplicate channels=<3>");
run("Gaussian Blur...", "sigma=2");
setAutoThreshold("Otsu dark");
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
run("Watershed");
if (isOpen("ROI Manager")) roiManager("reset");
run("Analyze Particles...", "size=<100>-Infinity pixel show=Nothing exclude add");
close("nuclei");
selectImage(original);
Stack.setChannel(<1>);
n = roiManager("count");
run("Clear Results");
sum = 0;
for (i = 0; i < n; i++) {
  roiManager("select", i);
  getStatistics(area, mean);
  setResult("Area", i, area); setResult("Mean", i, mean);
  sum += mean;
}
updateResults();
run("Select None");
print("nuclei=" + n + " (channel <3>) meanIntensityInChannel<1>=" + d2s(sum / maxOf(n, 1), 1));
```

Read `image.channels` and the channel labels first; `<3>` is the nuclear channel (blue in Fluorescent Cells), `<1>` the channel to quantify. Verified → `nuclei=15 (channel 3) meanIntensityInChannel1=78.6`.

## 9. Batch over a folder: threshold, count particles, save masks and one results table

```ijm
input = "<folder>/";
output = "<outputFolder>/";
File.makeDirectory(output);
list = getFileList(input);
setBatchMode(true);
run("Clear Results");
run("Set Measurements...", "area mean perimeter shape display redirect=None decimal=3");
processed = 0;
for (i = 0; i < list.length; i++) {
  if (!(endsWith(toLowerCase(list[i]), ".tif") || endsWith(toLowerCase(list[i]), ".tiff") || endsWith(toLowerCase(list[i]), ".png"))) continue;
  open(input + list[i]);
  name = File.nameWithoutExtension;
  if (bitDepth() != 8) run("8-bit");
  setAutoThreshold("Default<dark>");
  setOption("BlackBackground", true);
  run("Convert to Mask");
  run("Analyze Particles...", "size=<50>-Infinity pixel show=Masks display exclude summarize");
  saveAs("PNG", output + name + "_mask.png");
  close("*");
  processed++;
}
setBatchMode(false);
saveAs("Results", output + "particles.csv");
print("processed " + processed + " of " + list.length + " files; " + nResults + " particles -> " + output + "particles.csv");
```

Results rows carry the image name in `Label` (because of `display`). The Summary table (one row per image: Count, Total Area, Average Size, %Area) can be saved with `selectWindow("Summary"); saveAs("Results", output + "summary.csv");` when a window exists (interactive sessions). Bio-Formats files: replace `open(...)` with the importer line from recipe 15. Verified on a folder with three TIFF copies of Blobs → `processed 3 of 3 files; 129 particles -> …/particles.csv` (43 per image: Default threshold, exclude, min 50 px², no blur).

## 10. Save the Results table and the ROI Manager

```ijm
saveAs("Results", "<outputFolder>/results.csv");
if (roiManager("count") > 0) roiManager("save", "<outputFolder>/rois.zip");
print("saved " + nResults + " rows and " + roiManager("count") + " ROIs to <outputFolder>");
```

`saveAs("Results", path)` writes CSV (`.csv`) or tab-separated (`.txt`/`.xls`) by extension. `fiji_export_results` does the first line into `.local/exports`. Verified after recipe 2 → `saved 48 rows and 48 ROIs to …`.

## 11. Count spots without a threshold: background subtraction + Find Maxima

```ijm
run("Select None");
run("Duplicate...", "title=spots");
run("Subtract Background...", "rolling=<50><light>");
run("Find Maxima...", "prominence=<20> strict exclude output=[Point Selection]");
count = 0;
if (selectionType() == 10) { getSelectionCoordinates(xs, ys); count = xs.length; }
print("maxima=" + count + " at prominence <20>");
```

`<light>` is ` light` for dark spots on a light background (then Find Maxima needs ` light` too). Prominence is in gray levels; raise it to count fewer, brighter spots. The count is read from the point selection (`selectionType()` 10 = multipoint), which also shows the counted maxima (`fiji_highlight {rois: "active"}`); `output=Count` writes a `Count` column to Results in the GUI session instead. Verified: Blobs, rolling 50, no `light`, prominence 20 → `maxima=43 at prominence 20`.

## 12. Mean intensity of a region over time (hyperstack frames)

```ijm
run("<Mitosis (5D stack)>");
Stack.getDimensions(width, height, channels, slices, frames);
Stack.setChannel(<1>);
Stack.setSlice(<3>);
if (selectionType() == -1) makeOval(<60>, <70>, <50>, <50>);
means = newArray(frames);
for (f = 1; f <= frames; f++) { Stack.setFrame(f); getStatistics(area, mean); means[f - 1] = mean; }
Array.getStatistics(means, min, max, mean, std);
print("frames=" + frames + " channel=<1> slice=<3> meanIntensity: first=" + d2s(means[0], 1) + " last=" + d2s(means[frames - 1], 1) + " min=" + d2s(min, 1) + " max=" + d2s(max, 1));
```

`getStatistics` reads the displayed plane inside the ROI without touching Results. For a per-frame table use `setResult("Mean", f - 1, mean)` inside the loop and `updateResults()`. Verified → `frames=51 channel=1 slice=3 meanIntensity: first=6334.4 last=2263.5 min=2263.5 max=6334.4` for the oval at (60, 70) 50×50 (photobleaching trend of the GFP channel).

## 13. Set a spatial scale from a known distance, then measure in µm

```ijm
run("Set Scale...", "distance=<100> known=<25> pixel=1 unit=um");
getPixelSize(unit, pw, ph);
run("Select None");
run("Set Measurements...", "area mean display redirect=None decimal=3");
run("Measure");
print("scale: " + pw + " " + unit + "/px; image area=" + getResult("Area", nResults - 1) + " " + unit + "^2");
```

`<100>` pixels correspond to `<25>` known units (from a scale bar the researcher measured with a line selection: `getLine(x1, y1, x2, y2, w)` gives the pixel distance). Add ` global` to apply to all open images; `distance=0` removes the scale. Verified: Blobs → `scale: 0.25 µm/px; image area=4064 µm^2` (256 × 254 px × 0.0625 µm²).

## 14. Montage of a stack for a figure

```ijm
run("<MRI Stack>");
Stack.getDimensions(width, height, channels, slices, frames);
columns = <6>;
rows = floor((slices + columns - 1) / columns);
run("Make Montage...", "columns=" + columns + " rows=" + rows + " scale=<0.5> first=1 last=" + slices + " increment=1 border=2 font=12 label");
saveAs("PNG", "<outputFolder>/montage.png");
print("montage " + getWidth() + "x" + getHeight() + " of " + slices + " slices saved to <outputFolder>/montage.png");
```

Verified → `montage 568x573 of 27 slices saved to …/montage.png`.

## 15. Open a microscopy file with Bio-Formats (no dialog) and report dimensions

```ijm
run("Bio-Formats Importer", "open=[<path>] autoscale color_mode=Composite rois_import=[ROI manager] view=Hyperstack stack_order=XYCZT");
Stack.getDimensions(width, height, channels, slices, frames);
getVoxelSize(vw, vh, vd, unit);
print(getTitle() + ": " + width + "x" + height + " c=" + channels + " z=" + slices + " t=" + frames + " " + bitDepth() + "-bit, voxel " + vw + "x" + vh + "x" + vd + " " + unit);
```

Options: `split_channels`, `use_virtual_stack`, `series_2`, `specify_range z_begin_1=1 z_end_1=10 z_step_1=1`, `crop_on_import x_coordinate_1=0 y_coordinate_1=0 width_1=512 height_1=512` (see stacks-and-channels). Verified with a TIFF written by Fiji → `blobs.tif: 256x254 c=1 z=1 t=1 8-bit, voxel 1x1x1 pixels`.

## 16. Draw the counted particles as an overlay and save a figure

```ijm
if (roiManager("count") == 0) exit("No ROIs in the manager; run the segmentation first.");
run("Select None");
run("Remove Overlay");
run("From ROI Manager");
run("Labels...", "color=yellow font=10 show use");
run("Flatten");
saveAs("PNG", "<outputFolder>/outlines.png");
print("overlay with " + roiManager("count") + " outlines saved to <outputFolder>/outlines.png");
```

`Flatten` creates an RGB copy with the overlay burned in (the original keeps its data and overlay). `fiji_overlay {fromManager: true, labels: true}` does the first three steps live. Verified after recipe 2 on Blobs → `overlay with 48 outlines saved to …/outlines.png`.

## 17. Per-slice particle counts through a stack

```ijm
run("<MRI Stack>");
original = getTitle();
run("Select None");
run("Duplicate...", "title=mask duplicate");
run("Convert to Mask", "method=Default background=Dark calculate black");
run("Set Measurements...", "area mean stack display redirect=None decimal=3");
run("Clear Results");
run("Analyze Particles...", "size=<20>-Infinity pixel show=Nothing display exclude clear stack");
counts = newArray(nSlices);
for (i = 0; i < nResults; i++) { s = getResult("Slice", i); counts[s - 1] = counts[s - 1] + 1; }
Array.getStatistics(counts, min, max, mean, std);
close("mask");
selectImage(original);
print("slices=" + nSlices + " particles total=" + nResults + " perSlice min=" + min + " max=" + max + " mean=" + d2s(mean, 1));
```

`calculate` thresholds each slice separately; drop it to use one threshold for the stack. Verified → `slices=27 particles total=213 perSlice min=3 max=22 mean=7.9` (MRI Stack with per-slice Default thresholds; the numbers describe that setting, not anatomy).

## Notes on running

- `fiji_run_macro` returns the Log text and the last expression; `print` lines are what the researcher hears. Keep them to one line with the numbers.
- Macros that end with an image the researcher should see (projection, montage, merged composite) leave it active; `fiji_highlight` can then point at it.
- Batch mode (`-batch`, or `setBatchMode(true)` inside a macro) hides windows; `waitForUser` and dialogs fail; `roiManager(...)`, `Analyze Particles ... add`, `saveAs`, `Flatten`, `From ROI Manager` and `Make Montage` work. In the live Fiji everything above also updates the windows. Under `--headless` anything that touches the ROI Manager throws `HeadlessException`.
- `roiManager("Measure")` and `Find Maxima output=Count` fill the Results table in the GUI session but not reliably without the ImageJ main window; the recipes use `getStatistics`/`setResult` and the point selection instead so the same text works everywhere.
- Errors come back as `Macro Error` text with the line; fix the placeholder and rerun, do not retry unchanged.

Sources: built-in macro functions reference (local `macro-functions.md`), ImageJ User Guide § 14 Macros, imagej.net `scripting/macro.md`, `scripting/batch.md`; the headless runs above (`.local/fiji/Fiji.app/fiji --headless --console -macro`).
