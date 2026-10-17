# File ▷ Open Samples: exact names and what each image shows

Open with `fiji_run_command {name: "<Name>"}` (the command name is the menu label; ImageJ 1.54 dropped the old size suffixes, and the old forms such as “Blobs (25K)” are still translated). Images download from https://imagej.net/images/ on first use, so the first open needs network and takes a second. Dimensions, bit depth and calibration below were read from the installed Fiji (ImageJ 1.54p) with `getDimensions`/`getPixelSize`; titles are what `fiji_context.image.title` shows afterwards.

| Command name | Title after opening | Size (W×H, C/Z/T) | Type | Calibration | What it shows / good for |
|---|---|---|---|---|---|
| `Blobs` | blobs.gif | 256×254 | 8-bit, **inverting LUT** | pixels | Dark blobs on light background (displayed), the classic segmentation demo: threshold → particles → 60–65 objects. Because the LUT is inverting, raw values are high for blobs; `Default` auto threshold handles it. |
| `Boats` | boats.gif | 720×576 | 8-bit | pixels | Natural grayscale photo; filters, FFT, edge detection, contrast demos. |
| `Clown` | clown.jpg | 320×200 | RGB (24-bit) | pixels | Colour photo; RGB split/merge, colour threshold, 8-bit conversion demos. |
| `Fluorescent Cells` | FluorescentCells.tif | 512×512, C=3 | 8-bit composite | pixels | Three-channel fluorescence (nuclei blue, actin/tubulin red and green). Channel 3 (blue) is nuclei: `fiji_segment_cells {channel: 3}`. |
| `T1 Head (16-bits)` | t1-head.tif | 256×256, Z=129 | 16-bit | mm, 1.5 | MRI head volume; Z navigation, orthogonal views, projections, 3D. |
| `Confocal Series` | confocal-series.tif | 400×400, C=2, Z=25 | 8-bit composite | µm, 0.0545 | Two-channel confocal stack; Z Project, channel tools, colocalisation. |
| `Mitosis (5D stack)` | mitosis.tif | 171×196, C=2, Z=5, T=51 | 16-bit composite | µm, 0.0885 | Dividing cell over time (GFP-tubulin + DNA); hyperstack navigation, TrackMate, time series. |
| `Particles` | particles.gif | 2000×1000 | 8-bit binary, inverting LUT | pixels | Already-binary particles; Analyze Particles straight away (size/circularity filtering demo). |
| `Cell Colony` | Cell_Colony.jpg | 406×408 | 8-bit | pixels | Dark colonies on light dish; count colonies with a dark-object threshold, Watershed splits touching ones. |
| `Dot Blot` | Dot_Blot.jpg | 513×282 | 8-bit | pixels | Dot blot; integrated density per spot with Analyze Particles or a grid of ROIs. |
| `Gel` | gel.gif | 276×467 | 8-bit | pixels | Electrophoresis gel; `Analyze ▷ Gels` lane densitometry. |
| `Embryos` | embryos.jpg | 1600×1200 | RGB | pixels | Brightfield embryos; colour to 8-bit, threshold, count, Set Scale demo (there is a scale bar). |
| `HeLa Cells (48-bit RGB)` | hela-cells.tif | 672×512, C=3 | 16-bit composite, 1 overlay element | µm, 0.0515 | Three-channel 16-bit fluorescence; per-channel display ranges, 16-bit versus 8-bit demo. |
| `Neuron (5 channels)` | Rat_Hippocampal_Neuron.tif | 512×512, C=5 | 16-bit composite | µm, 0.16 | Five-channel immunofluorescence; channel subsets, `Stack.setActiveChannels`. |
| `Organ of Corti (4D stack)` | organ-of-corti.tif | 548×249, C=4, Z=15 | 16-bit composite | µm, 0.315 | Four channels × 15 slices; hyperstack projections per channel. |
| `Fly Brain` | flybrain.tif | 256×256, Z=57 | RGB stack | µm, 2.68 | RGB confocal stack; 3D projection, reslice. |
| `MRI Stack` | mri-stack.tif | 186×226, Z=27 | 8-bit | pixels | Small 8-bit MRI stack; montage, reslice, animation. |
| `Bat Cochlea Volume` | bat-cochlea-volume.tif | 121×154, Z=114 | 8-bit binary | pixels | Binary 3D volume; 3D Objects Counter, 3D Viewer, skeletonize demos. |
| `Bat Cochlea Renderings` | bat-cochlea-renderings.tif | 284×154, Z=36 | 8-bit | pixels | Pre-rendered rotation of the cochlea; animation. |
| `T1 Head Renderings` | t1-rendering.tif | 206×218, Z=36 | 8-bit | pixels | Pre-rendered head rotation; animation. |
| `CT (16-bit DICOM)` | ct.dcm.tif | 888×495 | 16-bit, signed CT values | mm, 0.597 | DICOM CT slice; Window/Level, Hounsfield-style thresholds (`Image ▷ Adjust ▷ Window/Level...`). |
| `Cardio (RGB DICOM)` | Cardio.dcm | 1000×1000 | RGB | pixels | RGB DICOM angiogram. |
| `M51 Galaxy (16-bits)` | m51.tif | 320×510 | 16-bit | pixels | Astronomy image with large dynamic range; log display, `Enhance Contrast`, 16-bit histogram. |
| `AuPbSn 40` | AuPbSn40.jpg | 600×412 | 8-bit | pixels | SEM of an alloy; phase-fraction by threshold (`area_fraction`). |
| `TEM Filter` | TEM_filter_sample.jpg | 500×417 | 8-bit | pixels | TEM of a filter membrane; pore counting with particles. |
| `Tree Rings` | Tree_Rings.jpg | 1796×162 | 8-bit | pixels | Plot Profile along a line, peak counting. |
| `Leaf` | leaf.jpg | 507×446 | RGB | pixels | Colour leaf; colour threshold, area fraction. |
| `Nile Bend` | NileBend.jpg | 4200×4200 | RGB | pixels | Large satellite RGB image; performance, scaling, colour segmentation. |
| `Line Graph` | LineGraph.jpg | 516×356 | 8-bit | pixels | Scanned plot; `Analyze ▷ Tools ▷ Analyze Line Graph`. |
| `Image with Overlay` | ImageWithOverlay.tif | 672×512 | 16-bit, 13 overlay elements | pixels | Demonstrates overlays (`Image ▷ Overlay ▷ List Elements`, `To ROI Manager`). |

Notes

- “Open the cells sample” is ambiguous between Fluorescent Cells, HeLa Cells, Cell Colony and Blobs. For a segmentation demo on a plain image pick `Blobs`; for fluorescence pick `Fluorescent Cells`; if the researcher says HeLa, 16-bit or “real microscope data”, pick `HeLa Cells (48-bit RGB)`. Say which one you opened.
- After opening, read `fiji_context` and report the title and size; the sample names differ from the window titles.
- `Blobs` and `Particles` use an inverting LUT: pixel 0 displays white. `Edit ▷ Options ▷ Appearance` “Interpolate zoomed images” and `Image ▷ Lookup Tables ▷ Invert LUT` change display only.
- Samples are small (the largest, Nile Bend, is 4200×4200 RGB ≈ 53 MB), so every recipe in [macros](macros.md) runs in seconds.
- Local demo data for Luma lives in `.local/data` (see docs/DATASETS.md); open it with `fiji_open_path`.

Source: `IJ_Props.txt` inside the installed `ij-1.54p.jar` (menu labels and file names), verified headless on 17 Oct 2026; ImageJ User Guide § 26.3 Open Samples; imagej.net `plugins/samples.md`.
