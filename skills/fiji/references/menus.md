# Exact menu command names and option strings for `fiji_run_command`

`fiji_run_command {name, options}` calls `IJ.run(imp, name, options)`: `name` is the menu label exactly as ImageJ registers it (ending in `...` where the menu does, without the keyboard shortcut in brackets unless the label itself contains it, e.g. `"Next Slice [>]"`, `"Reslice [/]..."`), and `options` is the macro-recorder string that fills the dialog so it never opens. Boolean checkboxes appear as bare words; values as `key=value`; values with spaces in `[brackets]`. Omit `options` only for commands without a dialog. Everything below was checked against the command table of the installed Fiji (ImageJ 1.54p) on 17 Oct 2026; `fiji_commands {query}` confirms a name live.

Convention in the tables: *command* → `options` example. `stack` processes all slices (otherwise the current one, with an “Process all images?” dialog). Values are examples to adapt from the context, not recommendations.

## File

| Command | Options / notes |
|---|---|
| `"Open..."` | `"open=[/path/img.tif]"` (prefer `fiji_open_path`) |
| `"Bio-Formats Importer"` (also `"Bio-Formats Windowless Importer"`) | `"open=[/path/f.czi] autoscale color_mode=Composite rois_import=[ROI manager] view=Hyperstack stack_order=XYCZT"`; see stacks-and-channels |
| `"Image Sequence..."` | `"open=[/folder/first.tif] sort"` (`use` = virtual) ; newer form `"dir=[/folder/] sort"` |
| `"TIFF Virtual Stack..."`, `"Raw..."`, `"Text Image... "`, `"URL..."`, `"Results... "`, `"Table... "` | `"open=[…]"`; note trailing spaces are part of some labels (`"Text Image... "`, `"Results... "`) |
| `"Blobs"`, `"Boats"`, `"Clown"`, `"Fluorescent Cells"`, `"T1 Head (16-bits)"`, `"Confocal Series"`, `"Mitosis (5D stack)"` … | File ▷ Open Samples; see samples.md |
| `"Close"`, `"Close All"` | no options (unsaved changes are discarded in macros; prefer `fiji_close`) |
| `"Save"`, `"Tiff..."`, `"PNG..."`, `"Jpeg..."`, `"ZIP..."`, `"Image Sequence... "`, `"Results..."`, `"Selection..."`, `"XY Coordinates..."`, `"Text..."` | `"save=[/path/out.tif]"`; prefer `fiji_save_image`, `fiji_export_results` (`saveAs("Results", path)` in macros) |
| `"Revert"` | reloads the file from disk (not undoable) |
| `"Bio-Formats Exporter"` | `"save=[/path/out.ome.tiff] compression=Uncompressed"` |

## Edit

| Command | Options / notes |
|---|---|
| `"Undo"` | ImageJ single-step undo (`fiji_undo` wraps it) |
| `"Select All"`, `"Select None"`, `"Restore Selection"` | no options |
| `"Make Inverse"`, `"Create Selection"` (from thresholded/binary), `"Create Mask"`, `"Fit Ellipse"`, `"Fit Circle"`, `"Convex Hull"`, `"Interpolate"` (`"interval=1 smooth"`), `"To Bounding Box"`, `"Area to Line"`, `"Line to Area"` | selections |
| `"Enlarge..."` | `"enlarge=5"` (negative shrinks; pixels, `pixel` to force) |
| `"Specify..."` | `"width=100 height=100 x=50 y=50 oval centered"` (prefer `fiji_select_roi`) |
| `"Scale... "` (selection), `"Rotate..."` (selection; note `"Rotate... "` with trailing space is the image rotation under Image ▷ Transform), `"Translate... "` | `"x=1.2 y=1.2 centered"` |
| `"Make Band..."` | `"band=10"` |
| `"Properties... "` (selection) | `"name=cell1 position=none stroke=yellow width=2 fill=none"` |
| `"Add to Manager"` | adds the active ROI to the ROI Manager (`fiji_add_to_manager`) |
| `"Clear"`, `"Clear Outside"`, `"Fill"`, `"Draw"`, `"Invert"` | data edits inside/outside the ROI (`"slice"` or `"stack"`) |
| `"Copy"`, `"Paste"`, `"Internal Clipboard"`, `"Duplicate..."` (Image menu) | |
| `"Colors..."`, `"Appearance..."`, `"Conversions..."` (`"scale weighted"`), `"Line Width..."` (`"line=3"`), `"Memory & Threads..."`, `"Misc..."` | Edit ▷ Options |
| `"Options..."` (Process ▷ Binary ▷ Options) | `"iterations=1 count=1 black do=Nothing"`; `do=` Erode/Dilate/Open/Close/Outline/Fill Holes/Skeletonize applies that op |

## Image

| Command | Options / notes |
|---|---|
| `"8-bit"`, `"16-bit"`, `"32-bit"`, `"RGB Color"`, `"RGB Stack"`, `"HSB Stack"`, `"Lab Stack"`, `"8-bit Color"` (`"number=256"`) | Image ▷ Type (`fiji_convert`) |
| `"Brightness/Contrast..."`, `"Window/Level..."`, `"Color Balance..."` | open the adjuster window; set ranges with `fiji_display` instead |
| `"Threshold..."` | opens the thresholder; `fiji_threshold` or `setAutoThreshold("Otsu dark")` instead |
| `"Auto Threshold"` | `"method=Otsu white"` (`method=[Try all]`, `ignore_black ignore_white stack use_stack_histogram`) |
| `"Auto Local Threshold"` | `"method=Phansalkar radius=15 parameter_1=0 parameter_2=0 white"` |
| `"Color Threshold..."` | interactive (needs the researcher); prefer `"Split Channels"` |
| `"Size..."` | `"width=512 height=512 depth=25 constrain average interpolation=Bilinear"` |
| `"Canvas Size..."` | `"width=600 height=600 position=Center zero"` |
| `"Coordinates..."` | sets origin |
| `"Crop"` | to the ROI |
| `"Duplicate..."` | `"title=copy"`; `"title=copy duplicate"` for the whole stack; `"title=nuc duplicate channels=3"` / `"… slices=5-10 frames=1"` for part of a hyperstack (`fiji_duplicate`) |
| `"Rename..."` | `"title=new"` |
| `"Scale..."` (image) | `"x=0.5 y=0.5 z=1.0 interpolation=Bilinear average create title=small"` |
| `"Rotate... "` (image; trailing space) | `"angle=15 grid=1 interpolation=Bilinear enlarge stack"` |
| `"Translate..."` | `"x=10 y=0 interpolation=None stack"` |
| `"Bin..."` | `"x=2 y=2 bin=average"` |
| `"Flip Horizontally"`, `"Flip Vertically"`, `"Flip Z"`, `"Rotate 90 Degrees Right"`, `"Rotate 90 Degrees Left"` | no options |
| `"Image to Results"`, `"Results to Image"` | pixel ↔ table |
| `"Set Scale..."` | `"distance=100 known=25 pixel=1 unit=um global"` (Analyze menu) |
| `"Properties..."` | `"channels=1 slices=1 frames=1 pixel_width=0.325 pixel_height=0.325 voxel_depth=1 unit=micron frame=[0 sec]"` |
| `"Add Slice"`, `"Delete Slice"`, `"Next Slice [>]"`, `"Previous Slice [<]"`, `"Set Slice..."` (`"slice=12"`) | Image ▷ Stacks |
| `"Images to Stack"` (`"name=Stack title=[] use"`), `"Stack to Images"`, `"Make Montage..."` (`"columns=5 rows=5 scale=0.5 first=1 last=25 increment=1 border=0 font=12 label"`), `"Reslice [/]..."` (`"output=1.000 start=Top avoid"`), `"Orthogonal Views"`, `"Z Project..."` (`"start=1 stop=25 projection=[Max Intensity]"`; also `[Average Intensity]`, `[Sum Slices]`, `[Min Intensity]`, `[Standard Deviation]`, `[Median]`, add `all` for every frame), `"3D Project..."`, `"Plot Z-axis Profile"`, `"Measure Stack..."`, `"Label..."`, `"Statistics"` | |
| `"Make Substack..."` (`"slices=10-20"` / `"channels=2 slices=1-5 frames=1"`), `"Grouped Z Project..."` (`"projection=[Max Intensity] group=5"`), `"Concatenate..."`, `"Reduce..."`, `"Reverse"`, `"Combine..."`, `"Insert..."`, `"Plot XY Profile"`, `"Remove Slice Labels"`, `"Set Label..."` | Image ▷ Stacks ▷ Tools |
| `"Start Animation [\]"`, `"Stop Animation"`, `"Animation Options..."` (`"speed=10 loop"`) | |
| `"New Hyperstack..."`, `"Stack to Hyperstack..."` (`"order=xyczt(default) channels=2 slices=25 frames=1 display=Composite"`), `"Hyperstack to Stack"`, `"Reduce Dimensionality..."` (`"channels frames keep"`), `"Make Subset..."` (`"channels=1-2 slices=5-10 frames=1"`), `"Re-order Hyperstack ..."` (Fiji; `"channels=[Channels (c)] slices=[Frames (t)] frames=[Slices (z)]"`) | Image ▷ Hyperstacks |
| `"Crop"` | |
| `"Split Channels"`, `"Merge Channels..."` (`"c1=red c2=green c3=blue create keep"`), `"Arrange Channels..."` (`"new=312"`), `"Invert LUTs"`, `"Channels Tool..."`, `"Stack to RGB"`, `"Make Composite"` (`"display=Composite"`), `"Show LUT"`, `"Display LUTs"`, `"Edit LUT..."`, `"Color Picker..."`, `"Colour Deconvolution"` (`"vectors=[H DAB]"`; also `[H&E]`, `[H&E 2]`, `[H DAB]`, `[Feulgen Light Green]`, `[Giemsa]`, `[FastRed FastBlue DAB]`, `[Methyl Green DAB]`, `[H&E DAB]`, `[H AEC]`, `[Azan-Mallory]`, `[Masson Trichrome]`, `[Alcian blue & H]`, `[H PAS]`, `[RGB]`, `[CMY]`, `[User values]`) | Image ▷ Color |
| `"Add Selection..."` (`"stroke=yellow width=2 fill=none"`), `"Add Image..."`, `"Hide Overlay"`, `"Show Overlay"`, `"From ROI Manager"`, `"To ROI Manager"`, `"Remove Overlay"`, `"Toggle Overlay"`, `"List Elements"`, `"Flatten"`, `"Labels..."` (`"color=white font=12 show use bold"`), `"Measure Overlay"`, `"Overlay Options..."` | Image ▷ Overlay (`fiji_overlay`) |
| `"Invert LUT"`, `"Apply LUT"`, `"Fire"`, `"Grays"`, `"Ice"`, `"Spectrum"`, `"3-3-2 RGB"`, `"Red"`, `"Green"`, `"Blue"`, `"Cyan"`, `"Magenta"`, `"Yellow"`, `"Red/Green"`, and the `Fiji.app/luts` names (`"mpl-viridis"`, `"glasbey"`, `"Green Fire Blue"`, `"HiLo"`, …) | Image ▷ Lookup Tables (`fiji_display {lut}`) |
| `"In [+]"`, `"Out [-]"`, `"Original Scale"`, `"View 100%"`, `"To Selection"`, `"Scale to Fit"`, `"Set... "` (`"zoom=200 x=128 y=128"`), `"Maximize"` | Image ▷ Zoom (`fiji_zoom`) |

## Process

| Command | Options / notes |
|---|---|
| `"Smooth"`, `"Sharpen"`, `"Find Edges"`, `"Enhance Contrast..."` (`"saturated=0.35"`; `normalize`, `equalize`, `process_all`, `use`) | |
| `"Add Noise"`, `"Add Specified Noise..."` (`"standard=25"`), `"Salt and Pepper"`, `"Despeckle"`, `"Remove Outliers..."` (`"radius=2 threshold=50 which=Bright"`), `"Remove NaNs..."` (`"radius=2"`) | Process ▷ Noise |
| `"North"` … `"Northwest"` | Process ▷ Shadows |
| `"Make Binary"`, `"Convert to Mask"`, `"Erode"`, `"Dilate"`, `"Open"`, `"Close-"`, `"Median"`, `"Outline"`, `"Fill Holes"`, `"Skeletonize"`, `"Distance Map"`, `"Ultimate Points"`, `"Watershed"`, `"Voronoi"`, `"Options..."` | Process ▷ Binary; on stacks add `"stack"` (e.g. `run("Convert to Mask", "method=Default background=Dark calculate black")` for per-slice thresholds) |
| `"Add..."`, `"Subtract..."`, `"Multiply..."`, `"Divide..."`, `"AND..."`, `"OR..."`, `"XOR..."`, `"Min..."`, `"Max..."`, `"Gamma..."`, `"Set..."` (`"value=25 stack"`), `"Log"`, `"Exp"`, `"Square"`, `"Square Root"`, `"Reciprocal"`, `"NaN Background"`, `"Abs"`, `"Macro..."` (`"code=v=v*2 stack"`) | Process ▷ Math |
| `"FFT"`, `"Inverse FFT"`, `"Bandpass Filter..."` (`"filter_large=40 filter_small=3 suppress=None tolerance=5 autoscale saturate"`), `"Custom Filter..."`, `"FD Math..."`, `"Swap Quadrants"` | Process ▷ FFT |
| `"Convolve..."` (`"text1=[0 1 0\n1 -4 1\n0 1 0] normalize stack"`), `"Gaussian Blur..."` (`"sigma=2 stack"`; `scaled`), `"Median..."` (`"radius=2"`), `"Mean..."`, `"Minimum..."`, `"Maximum..."`, `"Unsharp Mask..."` (`"radius=1 mask=0.60"`), `"Variance..."`, `"Top Hat..."` (`"radius=5"`), `"Gaussian Blur 3D..."` (`"x=2 y=2 z=2"`), `"Median 3D..."`, `"Mean 3D..."`, `"Minimum 3D..."`, `"Maximum 3D..."`, `"Variance 3D..."` | Process ▷ Filters (`fiji_filter`) |
| `"Measure..."`, `"Convert..."`, `"Macro... "` (trailing space; batch macro), `"Virtual Stack..."` | Process ▷ Batch |
| `"Image Calculator..."` | `"image1=A operation=Subtract image2=B create 32-bit stack"` |
| `"Subtract Background..."` | `"rolling=50 light sliding disable stack"` (`create` returns the background) |
| `"Find Maxima..."` | `"prominence=20 strict exclude light output=Count"` (`output=` Count, [Point Selection], List, [Single Points], [Maxima Within Tolerance], [Segmented Particles]) |
| `"Enhance Local Contrast (CLAHE)"` | `"blocksize=127 histogram=256 maximum=3 mask=*None*"` |
| `"Gray Morphology"` | `"radius=2 type=circle operator=open"` (Process ▷ Morphology) |

## Analyze

| Command | Options / notes |
|---|---|
| `"Measure"` | current ROI / image, per Set Measurements (`fiji_measure`) |
| `"Analyze Particles..."` | `"size=50-Infinity pixel circularity=0.00-1.00 show=Outlines display exclude clear include summarize add composite"`; `show=` Nothing, Outlines, [Bare Outlines], Ellipses, Masks, [Count Masks], Overlay, [Overlay Masks]; on stacks add `stack` |
| `"Summarize"`, `"Distribution..."` (`"parameter=Area automatic"`), `"Clear Results"` | |
| `"Set Measurements..."` | `"area mean standard modal min centroid center perimeter bounding fit shape feret's integrated median skewness kurtosis area_fraction stack limit display redirect=None decimal=3"` (see measurements.md) |
| `"Set Scale..."` | `"distance=100 known=25 pixel=1 unit=um global"`; `"distance=0"` removes |
| `"Calibrate..."` | `"function=[Straight Line] unit=OD text1=[0 255] text2=[0 2.5]"` |
| `"Histogram"` | `"bins=256 use x_min=0 x_max=255 y_max=Auto stack"` |
| `"Plot Profile"`, `"Surface Plot..."` | line/rectangle profile (`"Plot Profile"` fills Results via `Table`/`getProfile()` in macros) |
| `"Select First Lane"`, `"Select Next Lane"`, `"Plot Lanes"`, `"Re-plot Lanes"`, `"Label Peaks"`, `"Gel Analyzer Options..."` | Analyze ▷ Gels |
| `"Save XY Coordinates..."`, `"Fractal Box Count..."`, `"Analyze Line Graph"`, `"Curve Fitting..."`, `"ROI Manager..."`, `"Scale Bar..."` (`"width=50 height=4 font=14 color=White background=None location=[Lower Right] bold overlay"`), `"Calibration Bar..."` (`"location=[Upper Right] fill=White label=Black number=5 decimal=0 font=12 zoom=1 overlay"`), `"Synchronize Windows"`, `"Grid..."` (`"grid=Lines area=10000 color=Cyan"`) | Analyze ▷ Tools |
| `"3D Objects Counter"`, `"3D OC Options"` | `"threshold=100 slice=1 min.=10 max.=1000000 exclude_objects_on_edges objects statistics summary"` |
| `"Analyze Skeleton (2D/3D)"` | `"prune=none calculate show display"` |
| `"Coloc 2"` | needs two channel images; `"channel_1=[C1-x] channel_2=[C2-x] roi_or_mask=[<None>] threshold_regression=Costes display_images_in_result pearson's_r_(above_threshold) manders'_correlation psf=3 costes_randomisations=10"` |
| `"Directionality"` | `"method=[Fourier components] nbins=90 histogram_start=-90 histogram_end=90 display_table"` |

## Plugins (installed; details in plugins.md)

`"Record..."` (Macro Recorder), `"Install..."`, `"Run..."` (`"run=[/path/macro.ijm]"`), `"Startup Macros..."`, `"Interactive Interpreter..."`; `"Find Commands..."`, `"Commands..."`, `"Monitor Memory..."`, `"Capture Screen"`, `"Capture Image"`, `"ImageJ Properties"`; `"Bio-Formats Importer"`, `"Bio-Formats Exporter"`, `"Stack Slicer"`, `"Bio-Formats Macro Extensions"`; `"Trainable Weka Segmentation"`, `"Trainable Weka Segmentation 3D"`, `"Color Clustering"`; `"Open Current Image With Labkit"`; `"TrackMate"`, `"Manual tracking with TrackMate"`, `"Load a TrackMate file"`; `"Grid/Collection stitching"`, `"Pairwise stitching"`; `"Linear Stack Alignment with SIFT"`, `"Descriptor-based registration (2d/3d)"`, `"Descriptor-based series registration (2d/3d + t)"`, `"bUnwarpJ"`, `"Register Virtual Stack Slices"`; `"Skeletonize (2D/3D)"`; `"Cell Counter"`; `"3D Viewer"`; `"Exact Euclidean Distance Transform (3D)"`; `"Repeat a Recent Command"`.

## Window and Help

`"Show All"`, `"Put Behind [tab]"`, `"Cascade"`, `"Tile"`, `"Main Window [return]"`; `"Update ImageJ..."`, `"Update..."` (Fiji updater; needs the researcher), `"Update Bio-Formats Plugins"`, `"About ImageJ..."`, `"Macro Functions..."` (opens the online reference).

## Reading the recorder

`Plugins ▷ Macros ▷ Record...` writes exactly these `run("Name", "options")` lines as the researcher clicks; `fiji_recorder` returns them and `fiji_save_workflow` saves them. When unsure of an option string, run the command once with the dialog (`fiji_run_command` returns `dialog.controls` with the labels), set it through `fiji_ui_action`, and read the recorder line back. Option keywords are the first word of the dialog label, lower-cased (“Rolling ball radius” → `rolling`, “Light background” → `light`, “Feret's diameter” → `feret's`).

Sources: `IJ_Props.txt` and `plugins.config` entries of the installed Fiji (ImageJ 1.54p, 944 registered commands), menu reference pages `file.md`, `edit.md`, `image.md`, `process.md`, `analyze.md` and User Guide §§ 26–32 under `.local/upstream/imagej-docs`.
