# Demo data and scientific provenance

## OpenSlide OS-2.ndpi

- Source: [OpenSlide Hamamatsu test data](https://openslide.cs.cmu.edu/download/openslide-testdata/Hamamatsu/).
- Provider description: Ki-67 stain, brightfield, circa 2012.
- Provider usage statement: free to use and distribute, with or without modification.
- Local location: `.local/data/OS-2.ndpi`, excluded from Git.
- Actual observed dimensions: 126,976 × 73,728 pixels, one z plane/timepoint.
- Observed calibration: approximately 0.22710 µm/pixel.
- QuPath's own [cell detection tutorial](https://qupath.readthedocs.io/en/0.7/docs/tutorials/cell_detection.html) uses this sample.

The repository's viewer screenshots show this public dataset. They are actual application captures, not generated microscopy images.

## Reproducible software fixture

The authorized fixture uses a 2,200 × 2,200 pixel rectangle with origin (50,000, 26,000). QuPath 0.7 WatershedCellDetection uses Hematoxylin OD, requested pixel size 0.5 µm, background radius 8 µm, sigma 1.5 µm, nucleus area 10–400 µm², detection threshold 0.1 and cell expansion 5 µm. The script records the complete parameter object.

The 17 October test detected 899 cells. Classifying `Nucleus: DAB OD mean >= 0.2` yielded 49 Positive and 850 Negative. These counts are evidence of correct software execution for that fixture. They do not validate the segmentation, threshold or tumor-cell specificity and should not be presented as a clinical Ki67 score.

The fixture restored its empty hierarchy afterward. Image type remains H-DAB because hierarchy recovery intentionally does not undo image settings. It never overwrites the source slide.

Machine-readable evidence: [integration-evidence.json](pitch/integration-evidence.json).

## Initial H&E smoke sample

[CMU-1-Small-Region.svs](https://openslide.cs.cmu.edu/download/openslide-testdata/Aperio/) is a small public Aperio sample. It was used to verify actual image context, calibration, native approval and annotation creation before the larger Ki-67 fixture. It is also excluded from Git.

## Fiji sample images (File ▷ Open Samples)

The Fiji demo uses ImageJ's built-in sample images. They are fetched by ImageJ itself from `https://imagej.net/images/` (the `images.location` entry of `IJ_Props.txt` in `ij-1.54p.jar`) each time `File ▷ Open Samples ▷ <name>` or `run("<name>")` executes, so the first open of a session needs network access and nothing is stored in this repository or in `.local/data`. The command names below are the ImageJ 1.54 menu labels (older `"Blobs (25K)"`-style names are still translated).

Observed in the installed Fiji 2.18.0 / ImageJ 1.54p on 17 October 2026 (`getDimensions`, `bitDepth`, `getPixelSize`):

| Command | File | Size | Type | Calibration |
|---|---|---|---|---|
| `Blobs` | blobs.gif | 256 × 254 | 8-bit, inverting LUT | pixels |
| `Boats` | boats.gif | 720 × 576 | 8-bit | pixels |
| `Clown` | clown.jpg | 320 × 200 | RGB | pixels |
| `Fluorescent Cells` | FluorescentCells.tif | 512 × 512, 3 channels | 8-bit composite | pixels |
| `T1 Head (16-bits)` | t1-head.tif | 256 × 256 × 129 slices | 16-bit | 1.5 mm/px |
| `Confocal Series` | confocal-series.tif | 400 × 400, 2 channels × 25 slices | 8-bit composite | 0.05446 µm/px |
| `Mitosis (5D stack)` | mitosis.tif | 171 × 196, 2 channels × 5 slices × 51 frames | 16-bit composite | 0.0885 µm/px |
| `Particles` | particles.gif | 2000 × 1000 | 8-bit binary | pixels |
| `HeLa Cells (48-bit RGB)` | hela-cells.tif | 672 × 512, 3 channels | 16-bit composite | 0.05149 µm/px |
| `MRI Stack` | mri-stack.tif | 186 × 226 × 27 slices | 8-bit | pixels |

The full list of 30 samples with what each shows is in [skills/fiji/references/samples.md](../skills/fiji/references/samples.md).

Segmentation numbers quoted in the Fiji skill (for example 43 blobs with the `Default` threshold, exclude-on-edges and a 50 px² minimum, or the counts after Gaussian blur and watershed) are software outputs for those exact settings on `blobs.gif`, recorded by running the macro recipes headless in the installed Fiji (`skills/fiji/references/macros.md`). They validate that the commands execute and return numbers; they do not validate the segmentation of any research image. The sample images are the ImageJ project's demonstration data; cite the ImageJ documentation, not these images, as a scientific source.

Local documentation used by `fiji_knowledge` is downloaded by `script/setup-fiji-docs.py` into `.local/upstream/imagej-docs` (imagej.net wiki Markdown at the commit pinned in `script/dependencies.json`, the ImageJ 1.46r User Guide, the ImageJ 1.x menu reference and the built-in macro function reference), excluded from Git.
