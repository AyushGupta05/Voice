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
