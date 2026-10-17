# Stacks, hyperstacks, channels and Bio-Formats

`fiji_context.image` gives `channels`, `slices`, `frames` (C, Z, T), `bitDepth` and whether the window is a composite. A plain stack has one of C/Z/T > 1; a hyperstack has two or more. Everything that measures or filters applies to the displayed plane unless the command's option string says `stack`.

## Navigating

- `fiji_stack {slice}`, `{channel}`, `{frame}` (1-based) set the displayed plane; macros use `Stack.setSlice(n)`, `Stack.setChannel(c)`, `Stack.setFrame(t)`, `Stack.getPosition(c, z, t)`, `Stack.getDimensions(w, h, c, z, t)` (or `getDimensions`). “Next / previous slice” are instant routes (`Image ▷ Stacks ▷ Next Slice [>]`).
- `Image ▷ Stacks ▷ Set Slice...` (`options: "slice=12"`), `Image ▷ Hyperstacks ▷ Make Subset...` (`options: "channels=1-2 slices=5-10 frames=1"`), `Image ▷ Stacks ▷ Tools ▷ Make Substack...` (`options: "slices=10-20"` or `"channels=2 slices=1-5"`), `Reduce Dimensionality...` (`options: "channels frames keep"` keeps the ticked dimensions at the current position).
- `Image ▷ Stacks ▷ Orthogonal Views` (XZ and YZ windows), `Reslice [/]...` (`options: "output=1.000 start=Top avoid"`), `3D Project...`, `Make Montage...` (`options: "columns=5 rows=5 scale=0.5 first=1 last=25 increment=1 border=0 font=12 label"`), `Animation ▷ Start Animation`.
- Stack to images and back: `Image ▷ Stacks ▷ Stack to Images`, `Images to Stack` (`options: "name=Stack title=[] use"`), `Stack to Hyperstack...` (`options: "order=xyczt(default) channels=2 slices=25 frames=1 display=Composite"`), `Hyperstack to Stack`.

## Channels

- `fiji_convert {to: "Composite"}` = `Image ▷ Color ▷ Make Composite` (`options: "display=Composite"`; `display=Color` shows one channel at a time, `display=Grayscale`); `fiji_convert {splitChannels: true}` = `Split Channels` (new windows `C1-<title>`, `C2-<title>`…); `Merge Channels...` (`options: "c1=<red title> c2=<green title> c3=<blue title> create keep"`; c1 red, c2 green, c3 blue, c4 gray, c5 cyan, c6 magenta, c7 yellow; `create` makes a composite, otherwise RGB; `keep` leaves sources open).
- `Image ▷ Color ▷ Channels Tool...` (`Shift+Z`) opens the dialog that toggles channels and sets the composite mode; in macros `Stack.setDisplayMode("composite"|"color"|"grayscale")`, `Stack.setActiveChannels("101")` (string of 0/1 per channel) shows a subset without changing data. `Arrange Channels...` (`options: "new=312"`) reorders.
- Per-channel display: `fiji_display {channel, min, max}` or `{channel, lut}`; macro `Stack.setChannel(2); setMinAndMax(100, 2000); run("Green");`. LUTs are display colours, not data; a composite shows channels in false colour.
- RGB versus composite: `Stack to RGB` flattens a composite to 24-bit RGB (loses 16-bit range and channel separation); `Image ▷ Type ▷ RGB Color` does the same from the current display. Keep the composite for measurements; export RGB only for figures.
- Channel names from Bio-Formats appear in the slice labels and `Image ▷ Properties`; `getInfo("slice.label")` reads the current one. The researcher's “the DAPI channel” normally means the blue channel (c3 in the merge convention) or the channel labelled DAPI; read `image.channels` and the labels before assuming channel 1.

## Z projections and time

- `fiji_stack {project: "max"|"sum"|"avg"}` = `Image ▷ Stacks ▷ Z Project...` with `options: "projection=[Max Intensity]"`, `"projection=[Sum Slices]"`, `"projection=[Average Intensity]"`; also `[Min Intensity]`, `[Standard Deviation]`, `[Median]`; add `start=5 stop=15` for a range and `all` to project every frame of a time series. The result is a new window `MAX_<title>` / `SUM_<title>` / `AVG_<title>`. Sum projections of 8/16-bit data become 32-bit float; max projections keep the type.
- `Image ▷ Stacks ▷ Tools ▷ Grouped Z Project...` (`options: "projection=[Max Intensity] group=5"`) projects consecutive groups. `Plot Z-axis Profile` gives mean intensity in the ROI per slice (fills Results).
- Time-lapse: frames are T. `Image ▷ Hyperstacks ▷ Re-order Hyperstack ...` (Fiji) swaps Z and T when an import put time in slices (`options: "channels=[Channels (c)] slices=[Frames (t)] frames=[Slices (z)]"`). TrackMate (`Plugins ▷ Tracking ▷ TrackMate`) tracks spots over frames.
- Measuring on stacks: `Image ▷ Stacks ▷ Measure Stack...` measures every slice with the current ROI; `Statistics` gives whole-stack statistics; `fiji_measure` with `Set Measurements` `stack` adds the `Slice` column so rows stay attributable. ROI Manager ▷ Multi Measure for ROI × slice tables.
- 3D analysis: `Analyze ▷ 3D Objects Counter` (`options: "threshold=100 slice=10 min.=50 max.=1000000 exclude_objects_on_edges objects statistics summary"`), `Image ▷ Stacks ▷ 3D Project...`, `Plugins ▷ 3D Viewer`. MorphoLibJ (update site) adds 3D watershed and label analysis.

## Bio-Formats import

`fiji_open_path {path}` tries `IJ.openImage` and falls back to the Bio-Formats importer for proprietary formats (CZI, LIF, ND2, OIB/OIF, LSM, SVS, DICOM series, OME-TIFF). Explicit form: `fiji_run_command {name: "Bio-Formats Importer", options: "open=[/path/file.czi] autoscale color_mode=Composite rois_import=[ROI manager] view=Hyperstack stack_order=XYCZT"}`.

Useful option tokens: `color_mode=Default|Composite|Colorized|Grayscale|Custom`; `view=Hyperstack|[Standard ImageJ]|[Image5D]|[View 5D]|[Data Browser]`; `stack_order=XYCZT`; `autoscale` (display range from data; pixel values unchanged); `split_channels`, `split_focal`, `split_timepoints` (one window per channel/plane/frame); `series_1 series_3` to pick series of a multi-series file (`open_all_series`); `specify_range c_begin_1=1 c_end_1=2 c_step_1=1 z_begin_1=5 z_end_1=15 z_step_1=1 t_begin_1=1 t_end_1=1 t_step_1=1` for a sub-volume; `crop_on_import x_coordinate_1=0 y_coordinate_1=0 width_1=512 height_1=512`; `use_virtual_stack` for very large files (read on demand; filters then need `Duplicate...` first); `display_metadata`, `display_ome-xml`; `concatenate_series`, `stitch_tiles`, `group_files` (`axis_1_number_of_images=10 axis_1_axis_first_image=1 axis_1_axis_increment=1 contiguous`) for file-per-plane series; `quiet` to suppress dialogs.

Bio-Formats sets the calibration (`image.calibration`) and channel names from the file metadata; keep them. `Plugins ▷ Bio-Formats ▷ Bio-Formats Exporter` (`options: "save=[/path/out.ome.tiff] compression=Uncompressed"`) writes OME-TIFF with metadata; `fiji_save_image {format: "tiff"}` writes ImageJ TIFF (hyperstack dimensions and calibration preserved, OME metadata not). The macro extensions `run("Bio-Formats Macro Extensions"); Ext.setId(path); Ext.getSeriesCount(n); Ext.getSizeC(c);` read metadata without opening pixels.

## Virtual and large data

`File ▷ Import ▷ Image Sequence...` (`options: "open=[/folder/first.tif] sort use"` — `use` = virtual stack) and `File ▷ Import ▷ TIFF Virtual Stack...` keep data on disk. Virtual stacks are read-only: duplicate (`Duplicate...` with `duplicate`) or `Make Substack...` before filtering. `Edit ▷ Options ▷ Memory & Threads...` shows the heap; the context's `image.width × height × planes × bytes` tells whether a copy fits.

Sources: ImageJ User Guide §§ 8 Stacks/Hyperstacks, 28.3 Color, 28.4 Stacks, 28.5 Hyperstacks; imagej.net `formats/bio-formats.md`, `imaging/z-functions.md`, `imaging/color-image-processing.md`, `plugins/bio-formats` (local copies under `.local/upstream/imagej-docs`).
