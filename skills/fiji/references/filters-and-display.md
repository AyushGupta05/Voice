# Filters, LUTs, brightness/contrast and enhance contrast

Filters change pixel data (data actions, checkpointed, undoable with `fiji_undo` right after); LUTs and display ranges change only how the image looks (view actions, not data). Say which kind you did. All filters apply to the active ROI when one exists and to the current slice unless `stack` is in the options (ImageJ otherwise asks “Process all N images?”; include `stack` to avoid the dialog).

## Filters (`fiji_filter {name, …}` or `fiji_run_command {name, options}`)

| Task | `fiji_filter` | Command and option string | Parameters and units |
|---|---|---|---|
| Smooth noise, preserve shape | `gaussian`, `sigma` | `"Gaussian Blur..."`, `"sigma=2 stack"` | `sigma` in pixels (add `scaled` to use calibrated units). σ 1–2 for noise before thresholding; σ ≈ object radius to make blobs for Find Maxima. |
| Remove salt-and-pepper noise | `median`, `radius` | `"Median..."`, `"radius=2 stack"` | radius in pixels; 1–3 typical. |
| Box average | `mean`, `radius` | `"Mean..."`, `"radius=2"` | radius in pixels. |
| Sharpen | `unsharp`, `radius`, `weight` | `"Unsharp Mask..."`, `"radius=1 mask=0.60 stack"` | radius = Gaussian σ (px), mask weight 0.1–0.9. |
| Flatten uneven background | `subtract_background`, `rolling` | `"Subtract Background..."`, `"rolling=50 light sliding disable stack"` | rolling ball radius in pixels ≈ 3× the largest object radius (object size is what must survive); `light` for dark objects on light background; `sliding` paraboloid; `disable` smoothing; `create` returns the background instead. |
| Stretch display range (data unchanged) | `enhance_contrast`, `saturated` | `"Enhance Contrast..."`, `"saturated=0.35"` | percentage of pixels allowed to saturate; `normalize` rewrites pixel values (8/16-bit, one slice unless `process_all`); `equalize` histogram-equalises (data); `use` stack histogram. |
| Invert values | `invert` | `"Invert"`, `"stack"` | data inversion (255−v); `Invert LUT` is display only. |
| 3×3 smoothing / sharpening / edges | `smooth`, `sharpen`, `find_edges` | `"Smooth"`, `"Sharpen"`, `"Find Edges"` | fixed 3×3 kernels; Find Edges = Sobel. |
| Morphological grayscale | | `"Minimum..."`, `"Maximum..."`, `"Top Hat..."` (`"radius=5"`), `"Variance..."` | radius px; Top Hat removes background smaller than the radius. |
| Custom kernel | | `"Convolve..."`, `"text1=[-1 -1 -1\n-1 8 -1\n-1 -1 -1] normalize stack"` | kernel rows separated by `\n`. |
| Despeckle / outliers | | `"Despeckle"` (median r=1), `"Remove Outliers..."` (`"radius=2 threshold=50 which=Bright"`) | which = Bright or Dark. |
| Frequency filtering | | `"Bandpass Filter..."`, `"filter_large=40 filter_small=3 suppress=None tolerance=5 autoscale saturate"` | structure sizes in pixels. |
| Local contrast | | `"Enhance Local Contrast (CLAHE)"`, `"blocksize=127 histogram=256 maximum=3 mask=*None*"` | display-like but changes data. |
| 3D | | `"Gaussian Blur 3D..."` (`"x=2 y=2 z=2"`), `"Median 3D..."`, `"Mean 3D..."`, `"Minimum 3D..."`, `"Maximum 3D..."` | per-axis sigma/radius in pixels. |
| Image math | | `"Add..."`, `"Subtract..."`, `"Multiply..."`, `"Divide..."`, `"Min..."`, `"Max..."`, `"Gamma..."`, `"Set..."` (`"value=25 stack"`), `"Log"`, `"Exp"`, `"Square"`, `"Square Root"`, `"Abs"`, `"Reciprocal"` | value in pixel units; 8-bit clips at 0/255 (convert to 32-bit first for ratios). |
| Two images | | `"Image Calculator..."`, `"image1=A operation=Subtract image2=B create 32-bit stack"` | operations Add, Subtract, Multiply, Divide, AND, OR, XOR, Min, Max, Average, Difference, Copy, Transparent-zero; `create` makes a new window. |
| Local maxima (spot counting without a threshold) | | `"Find Maxima..."`, `"prominence=20 strict exclude output=Count"` | prominence in gray levels (1.52+; older `noise=`); `output=` Count, [Point Selection], List, [Single Points], [Maxima Within Tolerance], [Segmented Particles]; `light` for dark spots. Count lands in Results column `Count`. |
| Geometry | | `"Scale..."` (`"x=0.5 y=0.5 interpolation=Bilinear average create"`), `"Rotate... "` (`"angle=15 grid=1 interpolation=Bilinear"`; note the trailing space in the label), `"Translate..."`, `"Bin..."` (`"x=2 y=2 bin=average"`), `"Crop"`, `"Canvas Size..."`, `"Size..."` (`"width=512 height=512 constrain average interpolation=Bilinear"`), `"Flip Horizontally"`, `"Rotate 90 Degrees Right"` | |

Type conversions (`fiji_convert`): `"8-bit"`, `"16-bit"`, `"32-bit"`, `"RGB Color"`, `"8-bit Color"` (`"number=256"`), `"RGB Stack"`, `"HSB Stack"`, `"Lab Stack"`. 16→8-bit scales the *current display range* to 0–255 (`Edit ▷ Options ▷ Conversions...` “Scale when converting”); set the range you mean first (`fiji_display {min, max}` or `resetMinAndMax`), and say that measured intensities after conversion are on 0–255. 8-bit→16/32-bit is lossless. RGB→8-bit takes the weighted or unweighted mean of channels; RGB→`RGB Stack` or `Split Channels` keeps the colours separate.

## Brightness, contrast and display range (`fiji_display`)

- `fiji_display {min, max}` = `setMinAndMax(min, max)`; `{auto: true}` = `resetMinAndMax` then `Enhance Contrast saturated=0.35` style auto; `{channel}` targets one channel of a composite. The window is `Image ▷ Adjust ▷ Brightness/Contrast...` (`C`); its Apply button rewrites pixels (that is the one data action in this dialog; `fiji_ui_action fire` on “Apply” only when the researcher asks to “apply” or “bake in”).
- `Window/Level...` is the same adjustment in radiology terms (window = max−min, level = centre); CT sample: level ≈ 40, window ≈ 400 HU for soft tissue.
- 16-bit images open with the range of their data (or Bio-Formats `autoscale`); “too dark” almost always means the display range, not the data: adjust display first and say “display only”.
- Composite images keep one range per channel; `fiji_display {channel: 2, min: 100, max: 1500}`. `Image ▷ Color ▷ Channels Tool...` toggles channels (`Stack.setActiveChannels("110")`).
- Overlay and text labels: `Image ▷ Overlay ▷ Labels...` (`options: "color=white font=12 show use bold"`), `Analyze ▷ Tools ▷ Scale Bar...` (`options: "width=50 height=4 font=14 color=White background=None location=[Lower Right] bold overlay"`; width in calibrated units), `Calibration Bar...` for a LUT legend, `Grid...`.

## LUTs (lookup tables; display only on grayscale, data on RGB conversion)

- `fiji_display {lut: "<name>"}` = `run("<name>")` with the LUT command name. Built-in `Image ▷ Lookup Tables`: `Fire`, `Grays`, `Ice`, `Spectrum`, `3-3-2 RGB`, `Red`, `Green`, `Blue`, `Cyan`, `Magenta`, `Yellow`, `Red/Green`, plus `Invert LUT` (toggle) and `Apply LUT` (data: writes the display range into the pixels).
- LUT files shipped in `Fiji.app/luts` (36, command name = file name without `.lut`): `16_colors`, `5_ramps`, `6_shades`, `Cyan Hot`, `Green Fire Blue`, `HiLo` (shows clipped pixels: 0 blue, 255 red), `ICA`, `ICA2`, `ICA3`, `Magenta Hot`, `Orange Hot`, `Rainbow RGB`, `Red Hot`, `Thermal`, `Yellow Hot`, `blue_orange_icb`, `brgbcmyw`, `cool`, `edges`, `gem`, `glasbey`, `glasbey_inverted`, `glasbey_on_dark` (distinct colours for label images), `glow`, `mpl-inferno`, `mpl-magma`, `mpl-plasma`, `mpl-viridis` (perceptually uniform; the right choice for intensity maps), `phase`, `physics`, `royal`, `sepia`, `smart`, `thal`, `thallium`, `unionjack`.
- An inverting LUT (Blobs, Particles) displays 0 as white; `is("Inverting LUT")` in macros; thresholds are on the raw values regardless. `Image ▷ Lookup Tables ▷ Invert LUT` fixes the appearance without touching data; `Edit ▷ Invert` changes data.
- For figures: set LUT and range, then `Image ▷ Overlay ▷ Flatten` (`fiji_run_command {name: "Flatten"}`) makes an RGB copy with overlays burned in; `fiji_save_image {format: "png"}` saves what is shown. Keep the original for measurement.
- `Edit ▷ Options ▷ Appearance...` (interpolate zoomed images, black canvas) and `Image ▷ Zoom ▷` are view settings; `fiji_zoom` covers zoom.

## Good defaults for “clean this up”

Fluorescence spots or nuclei: `Subtract Background rolling=50` → `Gaussian Blur sigma=1` → threshold (Otsu dark) → particles. Brightfield colonies: `8-bit` → `Subtract Background rolling=50 light` → threshold Default (objects dark) → `Fill Holes` → `Watershed`. Noisy EM: `Median radius=2` or `Despeckle`. Report every filter and parameter in the sentence that reports the count (“after a rolling-ball background subtraction of 50 px and σ 1 blur”).

Sources: ImageJ User Guide §§ 29 Process menu (Filters, Subtract Background, Enhance Contrast, Math, Image Calculator, Find Maxima), 28.1 Adjust, 28.8 Lookup Tables; menu reference `process.md`, `image.md`; LUT list from `Fiji.app/luts` (17 Oct 2026).
