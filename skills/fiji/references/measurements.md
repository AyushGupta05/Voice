# Measurements: Set Measurements names, Results columns and calibration

`fiji_measure {measurements, scope}` configures `Analyze ▷ Set Measurements...` from the names below, runs `Measure` (scope `roi` or `all`) or ROI Manager ▷ Measure (scope `manager`), and returns `{rows, columns, lastRow, summary}`. `fiji_results` pages the Results table; `fiji_export_results` saves it as CSV; `fiji_clear_results` empties it. Everything is measured on the active image at its current slice/channel/frame, inside the active ROI when one exists, otherwise over the whole image.

## Set Measurements option names (exactly as ImageJ spells them)

The option string for `fiji_run_command {name: "Set Measurements...", options}` and `run("Set Measurements...", "…")` is a space-separated list of these keywords plus `redirect=None decimal=3`:

| Keyword | Dialog label | Results columns added |
|---|---|---|
| `area` | Area | `Area` |
| `mean` | Mean gray value | `Mean` |
| `standard` | Standard deviation | `StdDev` |
| `modal` | Modal gray value | `Mode` |
| `min` | Min & max gray value | `Min`, `Max` |
| `centroid` | Centroid | `X`, `Y` |
| `center` | Center of mass | `XM`, `YM` |
| `perimeter` | Perimeter | `Perim.` |
| `bounding` | Bounding rectangle | `BX`, `BY`, `Width`, `Height` |
| `fit` | Fit ellipse | `Major`, `Minor`, `Angle` |
| `shape` | Shape descriptors | `Circ.`, `AR`, `Round`, `Solidity` |
| `feret's` | Feret's diameter | `Feret`, `FeretX`, `FeretY`, `FeretAngle`, `MinFeret` |
| `integrated` | Integrated density | `IntDen`, `RawIntDen` |
| `median` | Median | `Median` |
| `skewness` | Skewness | `Skew` |
| `kurtosis` | Kurtosis | `Kurt` |
| `area_fraction` | Area fraction | `%Area` |
| `stack` | Stack position | `Slice` (and `Ch`, `Frame` on hyperstacks) |
| `limit` | Limit to threshold | restricts statistics to thresholded pixels; adds `MinThr`, `MaxThr` |
| `display` | Display label | `Label` column (image title, ROI name, slice) |
| `invert` | Invert Y coordinates | y measured from the bottom |
| `scientific` | Scientific notation | formatting only |
| `add` | Add to overlay | adds the measured ROI to the overlay |
| `nan` | NaN empty cells | empty cells as NaN |
| `redirect=None` | Redirect to | `redirect=<title>` measures intensities on another open image (same size) using the ROIs of this one |
| `decimal=3` | Decimal places | 0–9 |

Verified full heading set after `area mean standard modal min centroid center perimeter bounding fit shape feret's integrated median skewness kurtosis area_fraction stack limit display` on ImageJ 1.54p: `Label Area Mean StdDev Mode Min Max X Y XM YM Perim. BX BY Width Height Major Minor Angle Circ. Feret IntDen Median Skew Kurt %Area RawIntDen Slice FeretX FeretY FeretAngle MinFeret AR Round Solidity MinThr MaxThr`.

Loose names map to keywords: “intensity / brightness / mean gray” → `mean` (+ `min`, `standard`, `integrated`); “size” → `area`; “shape / roundness / circularity” → `shape`; “length / diameter” → `feret's` (max calliper) or `fit` (ellipse axes); “position” → `centroid`; “total signal” → `integrated` (`IntDen` = Area × Mean in calibrated units; `RawIntDen` = sum of pixel values). `fiji_measure` accepts these keywords directly in `measurements`.

## Reading the Results table

- `fiji_measure` returns `rows` (table length), `columns`, `lastRow` (the newest measurement; for scope `roi` that is the ROI just measured) and `summary` (min/max/mean per numeric column over the whole table). Use `summary` for “mean area of the cells”; use `lastRow` for “intensity here”. Never average from a truncated `fiji_results` page.
- `Measure` appends a row each time; `fiji_clear_results` before a fresh series so `summary` describes only the current objects. Analyze Particles with `clear` does the same.
- `Label` (with `display`) is `<image title>:<roi name>:<slice>`; useful for multi-image tables.
- Line selections measure `Length` and `Angle` (and intensity along the line); point selections measure `X`, `Y` and the pixel value; multipoint gives one row per point.
- Per-ROI tables for stacks: ROI Manager ▷ More ▷ Multi Measure (`roiManager("multi measure")`) makes one row per slice and one column set per ROI; `fiji_measure {scope: "manager"}` uses plain `Measure` (one row per ROI on the current slice), which is what the demo needs.
- `Analyze ▷ Summarize` appends Mean/SD/Min/Max rows to the Results table; `Analyze ▷ Distribution...` plots a histogram of a column (`options: "parameter=Area automatic"`).
- Histogram of an image or ROI: `"Histogram"` (`options: "bins=256 use x_min=0 x_max=255 y_max=Auto"`), returns a window; the macro `getHistogram(values, counts, 256)` gives arrays; `getStatistics(area, mean, min, max, std, histogram)` returns the numbers directly without touching Results.

## Calibration and units

- `fiji_context.image.calibration` gives `pixelWidth`, `pixelHeight`, `unit`. Area is in `unit`², lengths in `unit`; uncalibrated images report `pixels` with `pixelWidth` 1. Always say the unit; a “mean area 345” without a unit is ambiguous.
- Samples that are calibrated (verified): Confocal Series 0.0545 µm, Mitosis 0.0885 µm, HeLa Cells 0.0515 µm, Neuron 0.16 µm, Organ of Corti 0.315 µm, Fly Brain 2.68 µm, T1 Head 1.5 mm, CT 0.597 mm. Blobs, Boats, Clown, Fluorescent Cells, Particles and the other JPEG/GIF samples are uncalibrated (pixels).
- Set a scale only from the researcher's number: `run("Set Scale...", "distance=100 known=25 unit=um global")` means 100 px = 25 µm (`pixel=1` aspect ratio; `global` applies to all open images); `"Properties..."` with `options: "channels=1 slices=1 frames=1 pixel_width=0.325 pixel_height=0.325 voxel_depth=1 unit=micron"` sets it directly. Remove with `distance=0`.
- Intensity values are the stored pixel values (8-bit 0–255, 16-bit 0–65535, 32-bit float); the display range does not change them. On RGB images ImageJ measures brightness `(R+G+B)/3` (or weighted if `Edit ▷ Options ▷ Conversions` says so); split channels to measure one colour. After an 8-bit conversion all intensities are rescaled, so measure intensities on the original (use `redirect=<original title>` when the mask is a converted copy).
- `Calibrate...` (`Analyze ▷ Calibrate...`) maps gray values to calibrated units (OD, concentration); measured `Mean` becomes calibrated. Rare; mention when `image.type` shows a calibration function.
- Densitometry on gels uses `Analyze ▷ Gels ▷` lane tools; integrated density of a background-corrected band is `IntDen` of the band ROI minus the same-area background ROI.

## Intensity workflows

- Mean intensity per cell after segmentation: particles in the manager → activate the original → `fiji_measure {scope: "manager", measurements: ["area","mean","integrated"]}` → `summary.mean.mean` is the mean of means; `rows` the count.
- Background-corrected intensity: measure a background ROI (`lastRow.Mean`), then report `Mean − background` and say so; or `Subtract Background...` first and note it.
- Colocalisation is not a Pearson-by-eye: `Analyze ▷ Colocalization ▷ Coloc 2` (installed) computes Pearson/Manders with statistics; needs two channels and optionally a mask ROI.
- Time series: `Image ▷ Stacks ▷ Plot Z-axis Profile` plots mean intensity of the ROI per slice/frame; the Results table it fills has one row per frame.

Sources: ImageJ User Guide §§ 30.1 Measure, 30.7 Set Measurements, 30.3 Summarize, 22 Results table; menu reference `analyze.md`; imagej.net `imaging/spatial-calibration.md`, `imaging/measurements.md` (local copies under `.local/upstream/imagej-docs`).
