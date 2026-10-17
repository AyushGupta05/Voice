# Biology tools: tracking, kinetics, positivity, tumour area, colocalisation and fiji_make_macro templates

Which structured tool answers which question, what the number means, what it does not mean, and compact parameterised templates for `fiji_make_macro` when no tool fits. The longer placeholder recipes for `fiji_run_macro` on public data are in [biology recipes](biology-recipes.md). Numbers always come from tool results; report them with the method, the threshold and the unit the result gives. Every operation below executes immediately, is journaled, and highlights what it measured.

| The researcher says | Route |
|---|---|
| “track the cells / puncta / particles”, “how fast do they move” | `fiji_track_objects` (time-lapse) |
| “how does the intensity change”, “FRAP”, “is it bleaching” | `fiji_intensity_over_time`; FRAP normalisation: template 7 |
| “how many nuclei” | `fiji_segment_cells` (nuclear channel), then `fiji_object_stats` |
| “what fraction are Ki67-positive”, “% protein-positive cells” | `fiji_segment_cells` then `fiji_classify_intensity {label: "Ki67"}` |
| “outline the tumour”, “how much of the section is tissue” | `fiji_tumor_region` |
| “do the two channels colocalise” | `fiji_colocalize` |
| anything else, “write a macro that…” | `fiji_make_macro` (templates below), then `fiji_run_workflow` for “all of them / the folder” |
| “save what you just did” | `fiji_capture_macro` |

## Tracking (`fiji_track_objects`)

Detection per frame (threshold `method`, `dark: true` for bright objects on a dark background, `minSize`/`maxSize`, `sigma` smoothing) then frame-to-frame linking: `maxDistance` is the furthest an object may move between frames (px/frame; start at about one object diameter, larger for fast cells, smaller for dense puncta), `maxGap` lets a track survive frames where the object was missed, `minLength` drops short tracks (noise). Restrict with `startFrame`/`endFrame`. Speeds and displacements are in the reported `unit` per frame, or per `timeUnit` when the image carries a frame interval (`Image ▷ Properties`); never assume a frame interval. Say “Tracked 12 objects over 40 frames, mean speed 0.8 µm per frame.”

- Linking errors grow with density: when objects come closer than `maxDistance`, identities can swap. Fewer tracks than objects per frame usually means `maxDistance` is too small or detection is noisy.
- Divisions are reported only when the bridge detects a split; a division count is not a validated mitosis count.
- Mean displacement (start → end) below mean path length means meandering, not slow movement.
- For publication-grade tracking (LAP linker, track splitting/merging, manual curation) open TrackMate (`fiji_run_command {name: "TrackMate"}`) and leave its wizard to the researcher.

## Intensity over time, FRAP and bleaching (`fiji_intensity_over_time`)

Mean intensity per frame for the active ROI or ROI Manager entries (`indices` picks some), in one `channel`. `changePercent` compares last to first frame, `peakFrame` is the brightest frame. A steady decline in every region, including a background or reference region, is photobleaching, not biology; `bleachingEstimate` reports it when measurable. Expression kinetics need the same ROI to stay on the cell; for moving cells track first.

FRAP: three regions (bleached, an unbleached reference cell, background) and double normalisation, template 7: `I(t) = ((bleach − bg) / pre-bleach mean) / ((reference − bg) / reference pre-bleach mean)`. The mobile fraction is `(plateau − post) / (1 − post)`; the half-recovery time is read from the curve and is in frames unless the frame interval is set. A plateau that is still rising means the acquisition was too short; say so instead of quoting a mobile fraction.

## Nuclei counting

`fiji_segment_cells {channel}` (DAPI/Hoechst channel, usually 1) → count; `fiji_object_stats` for area/intensity distributions and the largest/smallest objects. Watershed splits touching nuclei and over-splits elongated ones; clumps under-split. Report the count as objects found by these settings. Template 1 is the same pipeline as a parameterised macro; [macros](macros.md) recipe 2 measures on the original image.

## Marker positivity: Ki67 index, protein-positive cells (`fiji_classify_intensity`)

Segment nuclei/cells first (`fiji_segment_cells` on the nuclear channel), then score each ROI by its `mean` (default; `intdens` for total signal, `max` for punctate signal) in the marker `channel`. Positive = value ≥ threshold. `threshold` explicit, or `method` (auto) on the per-cell values. Say “34 of 120 cells Ki67-positive, 28%.” with the threshold when asked.

- Comparing conditions needs one fixed threshold for all images: an auto threshold per image finds a “positive” population even when none exists. After the first image, pass the returned `threshold` explicitly.
- Brightfield IHC (DAB): positivity is in the DAB channel of colour deconvolution (`vectors=[H DAB]`, `Colour_2`); DAB optical density is not linear with antigen amount, so report fractions, not “expression levels”.
- The Ki67 index is positive nuclei / all nuclei in the counted region; hot-spot vs whole-section scoring are different numbers.

## Tumour and tissue area (`fiji_tumor_region`)

`stain: "auto"` decides from the image: RGB brightfield uses colour deconvolution (H&E → haematoxylin `Colour_1`, dense nuclei mark tumour-like cellular regions; IHC → `H DAB`), fluorescence thresholds `channel`. `sigma` smooths to tissue scale (larger for whole sections), `keepLargest` keeps that many regions, `minAreaFraction` drops fragments. The result is `area` in `unit`² and `areaFraction` of the image. Say “Tumour covers 38% of the section, 2.1 mm².”

- This is a stain-density region, not a pathologist's tumour annotation; stroma, lymphocyte aggregates and necrosis are not separated. Call it “the dense haematoxylin region” when the researcher asks what it is.
- `areaFraction` is of the whole image, including glass; for “fraction of the tissue”, outline the tissue too (`stain: "auto"`, larger `sigma`, `keepLargest: 1`) and divide the two reported areas.
- Colour vectors drift with staining and scanner; `[H&E 2]` or `[User values]` can fit better.

## Colocalisation (`fiji_colocalize`)

Pearson's r measures linear correlation of the two channels' intensities across pixels (−1 to 1, threshold-free). Manders' M1/M2 are fractions of each channel's signal overlapping above-threshold signal of the other, so they need thresholds (auto, Costes-like, or `thresholdA`/`thresholdB`). Spoken bands (conventions, not tests): |r| ≥ 0.7 strong, ≥ 0.4 moderate, ≥ 0.1 weak, else none.

- Colocalisation is not interaction: at light-microscope resolution (≈ 200–250 nm) two proteins in the same pixel can be far apart molecularly. Say “correlated” or “overlapping”, never “bind” or “interact”.
- Restrict to cells (`roi: true` with a drawn ROI) so empty background does not inflate r.
- Bleed-through, chromatic shift, saturation and background all bias the numbers; single-labelled controls and a randomisation test (Costes; `Coloc 2` via `fiji_run_command`) give significance.
- Say which thresholds produced M1/M2; they change with the threshold.

## Writing macros with `fiji_make_macro`

1. Write ImageJ macro (ijm) for the active image; keep it self-contained, end with one `print(...)` line carrying the numbers (that line is what the researcher hears).
2. Parameters: `params: {channel: 2, min: 30}` arrive as a `getArgument()` string of `key=value` pairs; read each with a default: `call("ij.Macro.getValue", getArgument(), "channel", "1")` (strings; `parseInt`/`parseFloat` them). Values with spaces arrive bracketed (`folder=[/a b/]`) and come back without brackets.
3. Test runs on the current image. On `ok: false`, read `error` and `errorLine`, fix that line, call again with the same `name`; at most 3 tries, silently. Then say what it found, or what failed.
4. `setBatchMode(true)` for loops over files or many images (no windows, much faster); dialogs and `waitForUser` cannot run in batch mode. In batch mode some errors abort without a dialog, so a missing `print` line means the macro stopped early.
5. Pitfalls verified below: comparisons are not values (`setResult("Positive", i, mean >= t)` aborts; set 0/1 with an `if`); `roiManager("select", i)` jumps to the frame/slice the ROI was added on, so set the position after selecting; `run("Duplicate...")` on a hyperstack copies the current plane only (add `duplicate` for all); `setOption("BlackBackground", true)` before `Convert to Mask`; reset `redirect` in `Set Measurements...` afterwards.
6. A good macro becomes a workflow automatically (it is saved under `name`); “run it on the folder” is `fiji_run_workflow {name, target: "folder", folder, pattern: "*.tif", saveResults: true}`.

All seven templates were run on 17 Oct 2026 with the installed ImageJ 1.54p (`Fiji.app/jars/ij-1.54p.jar`, Fiji's bundled Java 21, `ij.ImageJ -batch`; template 6 with the installed `Colour_Deconvolution-3.0.3.jar`) on synthetic images built for the purpose; the “Verified” line gives the input and the printed line.

### 1. Count nuclei (parameterised)

```ijm
// Count nuclei. Params: channel=1 method=Otsu min=30 (px²) watershed=true
args = getArgument();
channel = parseInt(call("ij.Macro.getValue", args, "channel", "1"));
method = call("ij.Macro.getValue", args, "method", "Otsu");
minSize = call("ij.Macro.getValue", args, "min", "30");
original = getTitle();
run("Select None");
if (nSlices > 1) Stack.setChannel(channel);
run("Duplicate...", "title=nuclei-mask");
run("8-bit");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold(method + " dark");
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
if (call("ij.Macro.getValue", args, "watershed", "true") == "true") run("Watershed");
roiManager("reset");
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel exclude add");
close("nuclei-mask");
selectImage(original);
n = roiManager("count");
sum = 0;
for (i = 0; i < n; i++) { roiManager("select", i); getStatistics(area); sum += area; }
getPixelSize(unit, pw, ph);
print("nuclei=" + n + " meanArea=" + d2s(sum / maxOf(n, 1), 1) + " " + unit + "^2 method=" + method);
```

Verified: 36 filled 22-px discs with noise, `min=30` → `nuclei=36 meanArea=384.0 pixels^2 method=Otsu`.

### 2. Mean intensity of each ROI over time

```ijm
// Mean intensity of every ROI Manager entry (or the active ROI) in every frame. Params: channel=1
channel = parseInt(call("ij.Macro.getValue", getArgument(), "channel", "1"));
Stack.getDimensions(w, h, channels, slices, frames);
hyper = Stack.isHyperstack;
if (!hyper) frames = nSlices;  // a plain stack: slices are the time points
n = roiManager("count");
if (n == 0) { if (selectionType() == -1) exit("Draw or add a region first."); roiManager("add"); n = 1; }
run("Clear Results");
for (t = 1; t <= frames; t++) {
  setResult("Frame", t - 1, t);
  for (i = 0; i < n; i++) {
    roiManager("select", i);  // selecting may jump to the ROI's own frame, so position afterwards
    if (hyper) Stack.setPosition(channel, 1, t); else setSlice(t);
    getStatistics(area, mean);
    setResult("ROI" + (i + 1), t - 1, mean);
  }
}
updateResults();
for (i = 0; i < n; i++) {
  first = getResult("ROI" + (i + 1), 0); last = getResult("ROI" + (i + 1), frames - 1);
  print("ROI" + (i + 1) + " frames=" + frames + " first=" + d2s(first, 1) + " last=" + d2s(last, 1) + " change=" + d2s(100 * (last - first) / first, 1) + "%");
}
```

Verified: 10-frame 16-bit hyperstack, two discs fading/brightening, ROIs added on frame 10 → `ROI1 frames=10 first=950.0 last=500.0 change=-47.4%`, `ROI2 … first=230.0 last=500.0 change=117.4%` (before positioning after `roiManager("select")` both read 500 at every frame).

### 3. Marker-positive fraction of segmented cells

```ijm
// Marker-positive fraction of the cells in the ROI Manager. Params: channel=2 threshold=<number, optional> method=Otsu label=Ki67
args = getArgument();
channel = parseInt(call("ij.Macro.getValue", args, "channel", "2"));
label = call("ij.Macro.getValue", args, "label", "marker");
threshold = parseFloat(call("ij.Macro.getValue", args, "threshold", "NaN"));
n = roiManager("count");
if (n == 0) exit("Segment the cells first (ROI Manager is empty).");
if (nSlices > 1) Stack.setChannel(channel);
if (isNaN(threshold)) {  // auto: the image's own threshold of the marker channel
  run("Select None");
  setAutoThreshold(call("ij.Macro.getValue", args, "method", "Otsu") + " dark");
  getThreshold(threshold, upper);
  resetThreshold();
}
positive = 0;
run("Clear Results");
for (i = 0; i < n; i++) {
  roiManager("select", i);
  if (nSlices > 1) Stack.setChannel(channel);
  getStatistics(area, mean);
  isPositive = 0;
  if (mean >= threshold) { isPositive = 1; positive++; }
  setResult("Cell", i, i + 1); setResult("Mean", i, mean); setResult("Positive", i, isPositive);
}
updateResults();
run("Select None");
print(label + "-positive=" + positive + " of " + n + " (" + d2s(100 * positive / n, 1) + "%) threshold=" + d2s(threshold, 1));
```

Verified: 2-channel image, 36 nuclei, 12 marker-positive → `Ki67-positive=12 of 36 (33.3%) threshold=34.0` (auto) and the same with `threshold=100`.

### 4. Maximum projection, threshold, count

```ijm
// Maximum projection, threshold, count spots. Params: method=Triangle min=10 (px²)
args = getArgument();
method = call("ij.Macro.getValue", args, "method", "Triangle");
minSize = call("ij.Macro.getValue", args, "min", "10");
source = getTitle();
run("Z Project...", "projection=[Max Intensity]");
projection = getTitle();
run("Duplicate...", "title=projection-mask");
setAutoThreshold(method + " dark");
setOption("BlackBackground", true);
run("Convert to Mask");
run("Set Measurements...", "area mean redirect=[" + projection + "] decimal=3");
run("Analyze Particles...", "size=" + minSize + "-Infinity pixel display clear");
run("Set Measurements...", "area mean redirect=None decimal=3");
close("projection-mask");
selectImage(projection);
print("projection of " + source + ": spots=" + nResults + " method=" + method);
```

Verified: 8-slice stack with two spots per slice at different x → `projection of zstack: spots=16 method=Triangle`.

### 5. Batch over a folder into one table

```ijm
// Batch: count objects in every matching file of a folder; one combined table. Params: folder=[/abs/path/] pattern=.tif method=Otsu min=20 out=[/abs/path/counts.csv]
args = getArgument();
folder = call("ij.Macro.getValue", args, "folder", "");
if (folder == "" || !File.isDirectory(folder)) exit("Give an existing folder: folder=[/path/to/images/]");
if (!endsWith(folder, File.separator)) folder = folder + File.separator;
pattern = call("ij.Macro.getValue", args, "pattern", ".tif");
method = call("ij.Macro.getValue", args, "method", "Otsu");
minSize = call("ij.Macro.getValue", args, "min", "20");
out = call("ij.Macro.getValue", args, "out", folder + "counts.csv");
setBatchMode(true);
files = getFileList(folder);
Table.create("Counts");
row = 0;
for (f = 0; f < files.length; f++) {
  if (!endsWith(files[f], pattern)) continue;
  open(folder + files[f]);
  run("8-bit");
  setAutoThreshold(method + " dark");
  setOption("BlackBackground", true);
  run("Convert to Mask");
  run("Analyze Particles...", "size=" + minSize + "-Infinity pixel display clear");
  Table.set("Image", row, files[f], "Counts");
  Table.set("Objects", row, nResults, "Counts");
  row++;
  close();
}
Table.save(out, "Counts");
setBatchMode(false);
print("images=" + row + " table=" + out);
```

Verified: three TIFFs with 2, 3 and 4 discs → `images=3 table=…/counts.csv`; the CSV reads `img1.tif,2`, `img2.tif,3`, `img3.tif,4`.

### 6. Tumour (haematoxylin-dense) area on H&E

```ijm
// Tumour (haematoxylin-dense) area on an RGB H&E section. Params: vectors=[H&E] sigma=4 method=Otsu keep=1
args = getArgument();
vectors = call("ij.Macro.getValue", args, "vectors", "H&E");
sigma = call("ij.Macro.getValue", args, "sigma", "4");
method = call("ij.Macro.getValue", args, "method", "Otsu");
keep = parseInt(call("ij.Macro.getValue", args, "keep", "1"));
if (bitDepth() != 24) exit("Colour deconvolution needs an RGB brightfield image.");
section = getTitle();
run("Select None");
getPixelSize(unit, pw, ph);
imageArea = getWidth() * getHeight() * pw * ph;
run("Colour Deconvolution", "vectors=[" + vectors + "] hide");
selectImage(section + "-(Colour_1)");                  // haematoxylin; low values = strong stain
run("Gaussian Blur...", "sigma=" + sigma);
setAutoThreshold(method);                               // dark (stained) pixels
setOption("BlackBackground", true);
run("Convert to Mask");
run("Fill Holes");
roiManager("reset");
run("Analyze Particles...", "size=0-Infinity add");
n = roiManager("count");
areas = newArray(n);
for (i = 0; i < n; i++) { roiManager("select", i); getStatistics(a); areas[i] = a; }
ranks = Array.rankPositions(areas); total = 0; kept = 0;
for (k = n - 1; k >= 0 && kept < keep; k--) { total += areas[ranks[k]]; kept++; }
for (c = 1; c <= 3; c++) close(section + "-(Colour_" + c + ")");
selectImage(section);
print("tumour regions=" + kept + " area=" + d2s(total, 1) + " " + unit + "^2 fraction=" + d2s(100 * total / imageArea, 1) + "% of the image (" + vectors + ", " + method + ")");
```

Verified: 300×300 RGB, pink tissue disc with a purple 120×100 ellipse → `tumour regions=1 area=9472.0 pixels^2 fraction=10.5% of the image (H&E, Otsu)` (ellipse area 9,425 px²). Output windows of Colour Deconvolution 3.0.3 are `<title>-(Colour_1|2|3)`.

### 7. FRAP with double normalisation

```ijm
// FRAP with double normalisation. ROI Manager: 0 bleached, 1 reference, 2 background. Params: bleach=<frame of the bleach> pre=<frames before it, default bleach-1>
args = getArgument();
if (roiManager("count") < 3) exit("Add three ROIs: bleached, reference, background.");
Stack.getDimensions(w, h, c, z, frames);
if (frames == 1) frames = nSlices;
bleach = parseInt(call("ij.Macro.getValue", args, "bleach", "0"));
values = newArray(3 * frames);
for (t = 1; t <= frames; t++) for (r = 0; r < 3; r++) {
  roiManager("select", r);
  if (Stack.isHyperstack) Stack.setFrame(t); else setSlice(t);
  getStatistics(area, mean); values[r * frames + t - 1] = mean;
}
if (bleach < 2) {  // the bleach frame is the largest drop of the bleached ROI
  drop = 0;
  for (t = 2; t <= frames; t++) { d = values[t - 2] - values[t - 1]; if (d > drop) { drop = d; bleach = t; } }
}
pre = parseInt(call("ij.Macro.getValue", args, "pre", "" + (bleach - 1)));
preB = 0; preR = 0;
for (t = bleach - pre; t < bleach; t++) { preB += values[t - 1] - values[2 * frames + t - 1]; preR += values[frames + t - 1] - values[2 * frames + t - 1]; }
preB /= pre; preR /= pre;
run("Clear Results");
for (t = 1; t <= frames; t++) {
  bg = values[2 * frames + t - 1];
  norm = ((values[t - 1] - bg) / preB) / ((values[frames + t - 1] - bg) / preR);
  setResult("Frame", t - 1, t); setResult("Normalised", t - 1, norm);
}
updateResults();
post = getResult("Normalised", bleach - 1); plateau = getResult("Normalised", frames - 1);
half = post + (plateau - post) / 2; halfFrame = -1;
for (t = bleach; t <= frames && halfFrame < 0; t++) if (getResult("Normalised", t - 1) >= half) halfFrame = t;
print("bleach frame=" + bleach + " post=" + d2s(post, 2) + " plateau=" + d2s(plateau, 2) + " mobile fraction=" + d2s((plateau - post) / (1 - post), 2) + " half-recovery frame=" + halfFrame + " (" + (halfFrame - bleach) + " frames after the bleach)");
```

Verified: 20 frames, reference bleaching 1 %/frame, bleach to 20 % at frame 5 recovering with τ = 3 frames → `bleach frame=5 post=0.19 plateau=1.00 mobile fraction=0.99 half-recovery frame=8 (3 frames after the bleach)`.

Sources: built-in macro functions (local `.local/upstream/imagej-docs/macro-functions.md`: `getArgument`, `call`, `Stack.*`, `roiManager`, `Table.*`, `setBatchMode`), `ij.Macro.getValue` in ImageJ 1.54p, imagej.net `scripting/batch.md`, `plugins/colour-deconvolution.md`, `plugins/coloc-2.md`, `plugins/trackmate/index.md` (local copies under `.local/upstream/imagej-docs/imagej.github.io/_pages`).
