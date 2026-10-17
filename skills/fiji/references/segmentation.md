# Segmentation in Fiji: thresholds, particles, watershed and the learned alternatives

Order of routes: `fiji_segment_cells` → `fiji_threshold` + `fiji_analyze_particles` → `fiji_run_command` with the option strings below → a macro from [macros](macros.md). Every number below is a starting point for the open image, never a validated biological truth; report method and bounds with the count.

## Choosing a threshold

`fiji_threshold {method, dark}` runs ImageJ's `setAutoThreshold("<Method> [dark]")` on the active image or ROI and returns `{lower, upper, method}`. The 17 global methods installed (`getList("threshold.methods")`, verified on ImageJ 1.54p): Default, Huang, Intermodes, IsoData, IJ_IsoData, Li, MaxEntropy, Mean, MinError, Minimum, Moments, Otsu, Percentile, RenyiEntropy, Shanbhag, Triangle, Yen.

| Situation | Method | Why |
|---|---|---|
| General first try, bimodal histogram | `Default` (IJ_IsoData variant) or `Otsu` | Minimises intra-class variance; the ImageJ default most tutorials use |
| Fluorescence nuclei on dark background | `Otsu` or `Li` with `dark: true` | Bright objects, dark background; `Li` is less biased toward the large background class |
| Dim signal, long tail of bright pixels | `Triangle` | Works when the object class is a small shoulder of a unimodal histogram |
| Uneven illumination | Subtract background first (`Subtract Background...` rolling ≈ 3× object radius), then `Otsu`; or Auto Local Threshold | A global threshold cannot follow a gradient |
| Thick blobs that should not fragment | `Huang` or `Minimum` | Conservative, keeps large connected regions |
| Very noisy EM/phase images | `Gaussian Blur...` σ 1–2 then `Default` | Smoothing before thresholding stabilises the histogram |
| Brightfield stained cells (dark objects) | `Default` with `dark: false` (objects are dark) | ImageJ picks the side by the LUT; `dark` says the background is dark |

`dark` means a dark background. On an inverting LUT (Blobs) ImageJ treats the displayed appearance; the bounds returned are what count. Manual bounds: `fiji_threshold {lower, upper}` for 8-bit/16-bit; stricter or looser means moving the bound on the object side by about 20 % of the range, keeping the method fixed so the comparison stays honest.

Menu equivalents (`fiji_run_command`): `"Threshold..."` (interactive dialog, needs `fiji_ui_action` to press Apply), `"Auto Threshold"` with `options: "method=Otsu white"` (Fiji plugin, also `method=[Try all]` to see all methods tiled, `ignore_black ignore_white stack`), `"Auto Local Threshold"` with `options: "method=Phansalkar radius=15 parameter_1=0 parameter_2=0 white"` (methods Bernsen, Contrast, Mean, Median, MidGrey, Niblack, Otsu, Phansalkar, Sauvola). `"Convert to Mask"` turns the thresholded image into a binary (0/255) image using the current `Black background` option; `"Make Binary"` does the same on an unthresholded image using the Default method. `Color Threshold...` for RGB needs a researcher at the dialog; prefer `Split Channels` and thresholding one channel.

Black background: `Process ▷ Binary ▷ Options...` → `options: "iterations=1 count=1 black"`. With it on, objects are 255 on 0; Analyze Particles on an unthresholded binary image takes 255 as object. `setOption("BlackBackground", true)` in macros. Mixing the two conventions inverts the count (background counted as one giant particle): check `count` against what the highlight shows.

## Analyze Particles

`fiji_analyze_particles {minSize, maxSize, minCircularity, exclude, include, show, addToManager, summarize}` → `{count, totalArea, averageSize}`. The equivalent command is `"Analyze Particles..."` with `options: "size=50-Infinity pixel circularity=0.00-1.00 show=Outlines display exclude clear include summarize add"`.

- `size=min-max` in calibrated units² unless `pixel` is given; `Infinity` is accepted. A sensible `minSize` is about 20–30 % of the expected object area (removes debris); for Blobs 50 px², for nuclei at 0.3 µm/px about 10 µm².
- `circularity=0.00-1.00` filters by 4π·area/perimeter²; nuclei 0.5–1.0, fibres 0.0–0.3.
- `exclude` = exclude on edges (drops objects touching the image or ROI border; honest for size statistics, undercounts density). `include` = include holes (fills interior holes before measuring; use for cells with dark vacuoles). `clear` resets Results first. `summarize` adds a Summary window (count, total area, average size, %area). `add` adds each particle to the ROI Manager (what `fiji_highlight {rois: "manager"}` and `fiji_measure {scope: "manager"}` use). `display` records one Results row per particle with the current Set Measurements. `composite` adds outlines as an overlay; `overlay` draws masks as an overlay. `show=` is one of `Nothing`, `Outlines`, `Bare Outlines`, `Ellipses`, `Masks`, `Count Masks`, `Overlay`, `Overlay Masks`.
- Analyze Particles needs a thresholded image or a binary (8-bit, 0/255) image, and works on the active ROI if one exists. On a stack it asks “Process all images?” unless `stack` is in the options.
- Results: `Count`, `Total Area`, `Average Size`, `%Area`, `Mean` in the Summary table; per-particle rows in Results per Set Measurements. `fiji_measure {scope: "manager"}` re-measures the particles on the original image, which is the right place for intensity.

## Watershed and binary clean-up

Binary menu (`Process ▷ Binary ▷`) commands take no options except via `Options...`: `"Fill Holes"`, `"Watershed"`, `"Erode"`, `"Dilate"`, `"Open"`, `"Close-"` (with the trailing dash), `"Outline"`, `"Skeletonize"`, `"Distance Map"`, `"Ultimate Points"`, `"Voronoi"`. They expect a binary image (`Convert to Mask` first) and honour the Black background setting.

Watershed computes the Euclidean distance map, finds ultimate eroded points and splits where two basins meet by a one-pixel line. It separates touching convex objects of similar size (nuclei, beads). It over-segments elongated or lobed objects and anything with internal holes (so `Fill Holes` first), and cannot split dense clusters whose distance map has one maximum. Smoothing (`Gaussian Blur...` σ 1–2) before thresholding reduces spurious splits. Say “after watershed splitting” when reporting counts from `segment_cells`. `"Erode"` → `"Dilate"` (an opening) removes single-pixel noise before counting; iterations are set with `Options...` (`iterations=2 count=1`).

Marker-controlled watershed, geodesic reconstruction, and label images are in MorphoLibJ (below), which handles 16-bit label maps that Analyze Particles cannot.

## Multichannel segmentation

Segment on one channel: `fiji_segment_cells {channel: n}` or `fiji_stack {channel: n}` → `fiji_duplicate {title: "nuclei"}` (duplicates the current channel for a composite when `roiOnly` is false; the macro form is `run("Duplicate...", "title=nuclei duplicate channels=1")`) → threshold → particles with `add` → `fiji_activate_image` the original → `fiji_stack {channel: m}` → `fiji_measure {scope: "manager"}` to read the second channel's intensity inside the nuclear masks. Report which channel each number came from. See [stacks-and-channels](stacks-and-channels.md).

## Learned segmentation available as plugins

| Tool | Installed in base Fiji? | Menu / how to run | Notes |
|---|---|---|---|
| Trainable Weka Segmentation | yes (4.0.0) | `Plugins ▷ Segmentation ▷ Trainable Weka Segmentation` (`fiji_run_command {name: "Trainable Weka Segmentation"}`), 3D variant alongside | Interactive: the researcher paints classes, presses Train, then Create result. Scriptable through its Groovy/BeanShell API (`trainableSegmentation.WekaSegmentation`) and saved classifiers (`.model`). Produces a probability map or classified image; threshold the probability map, then Analyze Particles. |
| Labkit | yes (0.4.0) | `Plugins ▷ Labkit ▷ Open Current Image With Labkit` | Fast pixel classification with scribbles, handles large images; exports label images. Interactive. |
| Auto Threshold / Auto Local Threshold | yes | `Image ▷ Adjust ▷ Auto Threshold`, `Image ▷ Adjust ▷ Auto Local Threshold` | Option strings above. Not learned, but the standard global/local methods. |
| MorphoLibJ | no; update site **IJPB-plugins** | `Plugins ▷ MorphoLibJ ▷ Segmentation ▷ Morphological Segmentation`, `… ▷ Classic Watershed`, `… ▷ Marker-controlled Watershed`, `Plugins ▷ MorphoLibJ ▷ Analyze ▷ Analyze Regions`, `Label Images ▷ …` | Label-image based: `Analyze Regions` measures labels (16-bit) with `options: "area perimeter circularity centroid"`; `Connected Components Labeling` with `options: "connectivity=4 type=[16 bits]"`. Use for 3D, for >65 k objects, or when watershed from ImageJ's binary menu is too crude. |
| StarDist | no; update sites **CSBDeep** + **StarDist** | `Plugins ▷ StarDist ▷ StarDist 2D` | Pretrained `Versatile (fluorescent nuclei)` and `Versatile (H&E nuclei)` models; outputs label image and/or ROI Manager entries. Macro form recorded by Fiji's recorder when the plugin is installed: `run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], args=['input':'<title>', 'modelChoice':'Versatile (fluorescent nuclei)', 'normalizeInput':'true', 'percentileBottom':'1.0', 'percentileTop':'99.8', 'probThresh':'0.5', 'nmsThresh':'0.4', 'outputType':'Both', 'nTiles':'1', 'excludeBoundary':'2', 'roiPosition':'Automatic', 'verbose':'false', 'showCsbdeepProgress':'false', 'showProbAndDist':'false'], process=[false]")` (that command is registered only in the running GUI, not headless; confirm with `fiji_commands {query: "StarDist"}` first, or run the SciJava command from Groovy via `fiji_run_script`: `#@ CommandService cs` … `cs.run(Class.forName("de.csbdresden.stardist.StarDist2D"), true, "input", imp, "modelChoice", "Versatile (fluorescent nuclei)")`). Needs the update sites and downloads the model on first run. |
| Cellpose | no; via **BIOP** update site (`Plugins ▷ BIOP ▷ Cellpose/Omnipose ▷ Cellpose Advanced`) or the newer Fiji-Cellpose plugin | needs a local Python environment with cellpose | Whole-cell segmentation (`cyto3`, `nuclei` models); outputs a label image. Only when the researcher has set the environment path. |
| Weka-free alternatives in base Fiji | yes | `Analyze ▷ 3D Objects Counter` (`options: "threshold=100 slice=1 min.=10 max.=1000000 objects statistics summary"`), `Analyze ▷ Skeleton ▷ Analyze Skeleton (2D/3D)`, `Plugins ▷ Tracking ▷ TrackMate` (spot detection + tracking over time; LoG detector takes `estimated diameter` and `threshold`) | 3D Objects Counter is the 3D equivalent of Analyze Particles for stacks. |

Check `fiji_plugins {query: "StarDist"}` or `fiji_commands` before promising one of these; if absent, say which update site adds it (Help ▷ Update… ▷ Manage Update Sites, needs a restart) rather than installing silently. See [plugins](plugins.md).

## Reporting

“Found 64 particles (Default threshold 125–255, min 50 px², watershed), mean area 345 px².” A particle count is objects after splitting; it becomes a cell count once the researcher looks at the outlines (`fiji_highlight {rois: "manager"}`). When exclude-on-edges was used, say that border objects were dropped.

Sources: ImageJ User Guide §§ 28.2 (Threshold), 29.8 (Binary), 30.2 (Analyze Particles); imagej.net wiki pages `imaging/thresholding.md`, `imaging/particle-analysis.md`, `plugins/auto-threshold.md`, `plugins/auto-local-threshold.md`, `plugins/morpholibj.md`, `plugins/stardist.md`, `plugins/cellpose.md`, `plugins/trainable-weka-segmentation.md` (local copies under `.local/upstream/imagej-docs/imagej.github.io/_pages`).
