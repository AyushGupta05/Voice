---
id: A14
title: Scientific File Formats and Metadata
stage: 01-analysis
status: Draft
priority: P1
source_revisions: [imagej/ImageJ@v1.54p, imagej/imagej-legacy@imagej-legacy-3.0.0, scifio/scifio@scifio-0.49.2, ome/bioformats@v8.5.0]
related_requirements: []
related_decisions: []
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A14 — Scientific File Formats and Metadata

## Executive Summary

Fiji has three independent IO stacks: the ImageJ 1.x readers and writers, Bio-Formats 8.5.0, and SCIFIO 0.49.2 (ImageJ2). Each preserves a different subset of metadata. P43 and P47 round-tripped one reference image through every main path: a 16-bit hyperstack of 3 channels × 4 slices × 2 frames, calibrated at 0.25 µm × 0.25 µm × 1.5 µm with a 2.5 s frame interval, in composite mode, with per-channel display ranges, a two-element overlay, a ROI, an `Info` property and a slice label. The results:

- **IJ1 TIFF** (`File › Save As › Tiff…`) is the only path that preserved everything. Pixels, dimensions, calibration, frame interval, composite mode, all three per-channel display ranges, overlay, ROI, `Info` and slice labels all survived. **VERIFIED** (P43 O1)
- **ZIP** preserved everything except the active ROI. **VERIFIED** (P43 O2)
- **OME-TIFF** through *Bio-Formats Exporter* preserved pixels, dimensions, XYZ calibration with units and the time increment. On re-import it lost the overlay, the `Info` text and the display ranges (re-autoscaled). The export **failed silently in a headless JVM** with `NullPointerException … "checkboxes" is null`. **VERIFIED** (P46 b1, P47)
- **PNG/JPEG** of a composite flattened it to a single 8-bit RGB plane with no calibration. A 16-bit plane saved as PNG reopened as 8-bit because its LUT was not the default grey LUT. **VERIFIED** (P43 O6–O8; `ij/plugin/PNG_Writer.java#L61`)
- **SCIFIO** (`DatasetIOService.open`) kept the axes and the X/Y/Z scales but dropped the time calibration. Converting the result to an `ImagePlus` dropped display ranges (0–1) and set the frame interval to 1.0. **VERIFIED** (P43 O9, O11)

Any modern UI save or export must therefore state its fidelity explicitly and default to IJ1 TIFF for analysis data.

## 1. Purpose and Scope

This document covers which file formats the pinned Fiji reads and writes through which component, what metadata each path keeps, and what a new UI must preserve. Algorithms and the full Bio-Formats reader catalogue are out of scope.

## 2. Sources and Evidence

- `imagej/ImageJ@v1.54p`: `ij/io/Opener.java`, `FileOpener.java`, `TiffDecoder.java`, `FileSaver.java`, `ij/plugin/PNG_Writer.java`, `IJ_Props.txt`.
- `ome/bioformats@v8.5.0`: `components/bio-formats-plugins/src/loci/plugins/out/Exporter.java` and `…/in/importer-options.txt`, read on GitHub on 2026-10-17. Installed class declarations are in P45.
- `imagej/imagej-legacy@imagej-legacy-3.0.0` (`ImageJ2Options`, `DefaultLegacyOpener`); `scifio/scifio@scifio-0.49.2`.
- Probes: P43 (headless round trip), P46 (headless exporter failure), P47 (OME-TIFF in a GUI-capable JVM). The fixtures were generated synthetically inside the probes. No dataset was downloaded.

## 3. IO stacks and routing

| Stack | Entry points | Role at pin | Evidence |
|---|---|---|---|
| IJ1 | `IJ.openImage(path)` → `ij.io.Opener.openImage` → type sniffing `getFileType` → `FileOpener`/`TiffDecoder` for TIFF; `IJ.saveAs(imp, format, path)` → `ij.plugin.filter.Writer(fmt)` → `ij.io.FileSaver` | Default for `File › Open`, `Save As` (`IJ_Props.txt` `save01`–`save17`: Tiff, Gif, Jpeg, Text Image, ZIP, Raw, Image Sequence, AVI, BMP, PNG, PGM, FITS, LUT, Selection, XY Coordinates, Results, Text) | VERIFIED |
| IJ1 → Bio-Formats hand-off | `Opener.getFileType` returns `UNKNOWN` for names containing `.ome.tif` when the Bio-Formats importer is installed (`ij/io/Opener.java#L1335-L1336`). `UNKNOWN` goes to `openUsingBioFormats(path)` (`#L376-L388`, reflection on `loci.plugins.BF`, `#L1195`), then to `HandleExtraFileTypes` | `IJ.openImage("x.ome.tif")` is read by Bio-Formats (P47 O2 matches O1) | VERIFIED |
| Bio-Formats (IJ1 plugin) | `loci.plugins.LociImporter implements PlugIn`, `loci.plugins.BF.openImagePlus(String \| ImporterOptions)`, `loci.plugins.in.ImporterOptions extends OptionsList`, `loci.plugins.LociExporter implements PlugInFilter` (P45). Menus: `File › Import › Bio-Formats`, `Plugins › Bio-Formats › Bio-Formats Importer/Exporter`, `File › Save As › OME-TIFF…` (P48 `plugins.config`, 21 lines) | Proprietary microscopy formats, OME-TIFF | VERIFIED |
| SCIFIO (IJ2) | `io.scif.services.DatasetIOService.open/save`; 29 `io.scif.Format` plugins (P48); IJ2 commands `io.scif.commands.OpenDataset`, `SaveAsImage`, `SaveDataset`, `SaveImage` | Used by IJ2 commands and scripts. Used for IJ1 `File › Open` only if *Edit › Options › ImageJ2… › "Use SCIFIO when opening files (BETA!)"* is set; default `false` (`imagej-legacy@…:src/main/java/net/imagej/legacy/ImageJ2Options.java#L76-L82`; `DefaultLegacyOpener.java#L132`) | VERIFIED |
| Fiji `IO_` plugins | `plugins/IO_-4.3.0.jar`: 31 `plugins.config` lines (Analyze, MetaImage, Nrrd, DF3, ICO, Icns, SVG, PDF, Animated GIF, EPS, …) | Extra readers/writers in `File › Import`/`Save As` | VERIFIED |
| N5/Zarr | `n5-ij-5.0.0.jar`: `File › Save As › HDF5/N5/Zarr/OME-NGFF …` (`org.janelia.saalfeldlab.n5.ij.N5ScalePyramidExporter`, P48) | Chunked formats | VERIFIED (presence) |

## 4. TIFF in ImageJ 1.x

- **ImageDescription (tag 270).** `FileSaver.getDescriptionString()` writes `ImageJ=<version>`, `images=`, `channels=`, `slices=`, `frames=`, `hyperstack=true`, `mode=`, `unit=`, `cf=`/`c0..`/`vunit=` (calibration function), `finterval=`, `tunit=`, `yunit=`, `zunit=`, `spacing=`, `fps=`, `loop=`, `min=`/`max=` (first channel only), `xorigin=`/`yorigin=`/`zorigin=`, `inverty=` and `8bitcolor=` (`ij/io/FileSaver.java#L715-L818`). The P43 M1 output for the reference image was `ImageJ=1.54p\nimages=24\nchannels=3\nslices=4\nframes=2\nhyperstack=true\nmode=composite\nunit=micron\nfinterval=2.5\nspacing=1.5\nloop=false\nmin=1000.0\nmax=4500.0\n…`. Pixel width and height go in the TIFF resolution tags. `FileOpener.decodeDescriptionString` parses the string back (`ij/io/FileOpener.java#L605`, frame interval at `#L447`, spacing at `#L641`). **VERIFIED**
- **Private metadata tags.** `META_DATA_BYTE_COUNTS` = 50838 and `META_DATA` = 50839 carry typed blocks: `info` (Info property), `labl` (slice labels), `rang` (per-channel display ranges), `luts` (channel LUTs), `plot`, `roi `, `over` (overlay) and `prop` (properties) (`ij/io/TiffDecoder.java#L44-L65`). This explains why IJ1 TIFF round-trips per-channel ranges, overlays and the ROI (P43 O1) while third-party readers do not (P43 O5). **VERIFIED**
- **Hyperstack metadata.** Dimension order on disk is the IJ1 stack order (C fastest, then Z, then T); `channels/slices/frames/hyperstack/mode` restore it. Composite mode `mode=composite` came back as `CompositeImage` mode 1 (P43 O1). **VERIFIED**

## 5. Bio-Formats and OME-TIFF

- **Importer macro options.** The option labels in `importer-options.txt` at v8.5.0 are `Open` (id), `Color_mode:` (default `Default`), `View stack with:` (default `Hyperstack`), `Stack_order:` (default `XYCZT`), `Autoscale` (default `true`), `Split_channels`, `Split_focal planes`, `Split_timepoints`, `windowless`, `Open_all_series`, `Display_metadata`, `Display_OME-XML metadata`, `Display_ROIs`, `ROIs_Import Mode:` (`ROI manager` or `Overlay`), `Use_virtual_stack`, `Concatenate_series when compatible`, `Group_files with similar names`, `Crop on import` and `Quiet mode`. **VERIFIED** (documentation file). The macro keys follow `ij.Macro.trimKey`: the text before the first space or colon, lower-cased (`ij/Macro.java#L159-L168`). That gives `open`, `color_mode`, `view`, `stack_order`, `autoscale`, `split_channels`, `split_focal`, `split_timepoints`, `windowless`, `open_all_series`, `display_metadata`, `display_ome-xml`, `display_rois`, `rois_import`, `use_virtual_stack`, `concatenate_series`, `group_files`, `crop` and `quiet`. **INFERRED** (rule applied to labels; individual keys not probed)
- **Series.** `BF.openImagePlus` returns `ImagePlus[]`, one entry per opened series (P43 O5: `series=1`). Multi-series files need `open_all_series` or `series_<n>` selection. **VERIFIED** (API); multi-series behaviour **UNKNOWN** without a multi-series fixture.
- **Metadata.** Bio-Formats populates OME-XML (OME data model). Original metadata goes into the `Info` property: 820 characters for the IJ TIFF in P43 O5, 386 for the OME-TIFF in P47 O1. The exported OME-XML for the reference image contained `PhysicalSizeX="0.25"`, `PhysicalSizeXUnit="µm"`, `TimeIncrement="2.5"`, three `<Channel` elements and no `<ROI` elements (P47 M2). **VERIFIED**
- **Exporter.** `Exporter.run()` reads `outfile`/`save=`, `compression`, `splitZ/C/T`, `padded`, `saveRoi`, `imageid`, `windowless` and `skip_luts` from the plugin argument or macro options. It falls back to a `JFileChooser` when no path is given and later uses `GenericDialog`s for multi-file and compression options (`Exporter.java`, `run()` from about L139). Headless, `IJ.run(imp, "Bio-Formats Exporter", "save=[…] compression=Uncompressed")` wrote no file. The captured stderr shows `NullPointerException: Cannot invoke "java.util.Vector.size()" because "checkboxes" is null` (P46 b1/b2, `probes/P46-stdout-stderr.txt`), consistent with `HeadlessGenericDialog` not implementing `getCheckboxes()` ([A15](A15-gui-dependence-reuse-boundaries.md) §4.2). In a non-headless JVM without a visible UI the same call succeeded (P47 S2). **VERIFIED**
- The exported OME-TIFF was 9 606 596 bytes for 147 456 bytes of pixel data (P47 S2). The cause was not investigated. **UNKNOWN**. To verify, inspect the IFDs with `tiffinfo` or `showinf -nopix`.

## 6. SCIFIO and metadata between IJ1, IJ2 and SCIFIO

- SCIFIO reading of the IJ TIFF produced axes `X:64@0.25micron, Y:48@0.25micron, Channel:3@1.0, Z:4@1.5micron, Time:2@1.0` (P43 O9). The frame interval of 2.5 s was **lost**. **VERIFIED**
- Converting that `Dataset` to an `ImagePlus` with `ConvertService` gave the right dimensions, composite mode and pixel checksum, but `frameInterval=1.0`, display ranges `0-1` for every channel, no overlay, and an `Info` of 1 479 characters (SCIFIO metadata) (P43 O11). **VERIFIED**
- The legacy bridge translates the active `ImagePlus` into a `Dataset` by wrapping, then harmonises composite mode, colour tables, overlays, position and name ([A07](A07-fiji-imagej-architecture.md) §5.5). Calibration is carried by `ImgPlus` axes. Which IJ1 metadata (Info, slice labels, `Calibration` function, properties) survives this translation was not probed. **UNKNOWN**. To verify, convert the P43 reference with `ConvertService` and compare it field by field.

## 7. Metadata preservation table

Pixels: "=" means the checksum equals the reference (2.77903872E8). Columns are reopen results. Rows marked † are the GUI-capable JVM (P47); all others are headless (P43).

| Path | Pixels | Dims C/Z/T | Bit depth | XYZ cal + unit | Frame interval | Composite mode | Per-channel display ranges | Overlay | ROI | Info | Slice labels | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| IJ1 TIFF → `IJ.openImage` | = | 3/4/2 | 16 | 0.25/0.25/1.5 micron | 2.5 sec | yes | kept | 2 | kept | kept | kept | P43 O1 |
| IJ1 ZIP → `IJ.openImage` | = | 3/4/2 | 16 | kept | 2.5 sec | yes | kept | 2 | **lost** | kept | kept | P43 O2 |
| IJ1 TIFF → Bio-Formats importer (composite) | = | 3/4/2 | 16 | kept | 2.5 sec | yes | **autoscaled** (1110-4429, …) | **lost** | lost | **replaced** (original metadata) | **replaced** (`c:1/3 z:1/4 t:1/2 - ref.tif`) | P43 O5 |
| IJ1 TIFF → SCIFIO `DatasetIOService.open` | n/a | C3/Z4/T2 axes | 16-bit uint | 0.25/0.25/1.5 micron | **lost** (Time@1.0) | n/a | n/a | n/a | n/a | n/a | n/a | P43 O9 |
| …SCIFIO `Dataset` → `ImagePlus` (ConvertService) | = | 3/4/2 | 16 | kept | **1.0** | yes | **0-1** | lost | lost | replaced | replaced (`1`) | P43 O11 |
| OME-TIFF (Bio-Formats Exporter)† → Bio-Formats importer | = | 3/4/2 | 16 | kept (µm) | 2.5 sec | yes | **autoscaled** | **lost** | lost | replaced | not checked | P47 O1, M2 |
| OME-TIFF† → `IJ.openImage` (routed to Bio-Formats) | = | 3/4/2 | 16 | kept | 2.5 sec | yes | autoscaled | lost | lost | replaced | not checked | P47 O2 |
| OME-TIFF† → SCIFIO | n/a | C3/Z4/T2 | — | 0.25/0.25/1.5 (no unit printed) | **lost** (Time@1.0) | n/a | n/a | n/a | n/a | n/a | n/a | P47 O3 |
| OME-TIFF export **headless** | — no file written (NPE) — | | | | | | | | | | | P43 S3, P46 b1 |
| PNG of composite | **changed** | 1/1/1 | 24 (RGB) | **lost** (pixel) | lost | no | — | lost | lost | lost | lost | P43 O6 |
| JPEG of composite | **changed** (lossy) | 1/1/1 | 24 (RGB) | lost | lost | no | — | lost | lost | lost | lost | P43 O7 |
| PNG of one 16-bit plane with non-grey LUT | **changed** | 1/1/1 | **8** | lost | — | — | — | — | — | lost | — | P43 O8; `PNG_Writer.java#L61` (16-bit kept only with the default grey LUT) |
| `Image › Type › 8-bit` on a 16-bit plane, range 1000–4500 | **rescaled**: min/max 1110/4119 → 8/228 | — | 8 | — | — | — | — | — | — | — | — | P43 L1 |

Not tested: Stack-to-RGB of a composite. P43 L2 returned `null` from `WindowManager.getCurrentImage()` after the command, so the probe gave no result. GIF, BMP, AVI, FITS, Raw, Image Sequence, N5/Zarr and multi-series proprietary formats were not tested either. **UNKNOWN**

## 8. `IJ.openImage` versus Bio-Formats

| Aspect | `IJ.openImage` (IJ1) | Bio-Formats importer | Evidence |
|---|---|---|---|
| Format coverage | TIFF, ZIP, GIF, JPEG, PNG, BMP, PGM, FITS, DICOM, AVI, text, LUT, ROI; `UNKNOWN` types and `.ome.tif` delegated to Bio-Formats, then `HandleExtraFileTypes` | Bio-Formats readers (`formats-bsd`, `formats-gpl`) | `Opener.java#L360-L392`, `#L1335` |
| IJ-specific metadata (overlay, ROI, per-channel ranges, Info, labels) | Preserved for its own TIFF/ZIP | Not read (ranges autoscaled; Info replaced) | P43 O1 vs O5 |
| Series selection, OME-XML, original metadata, virtual stacks, crop | No | Yes (options in §5) | importer-options.txt |
| Headless | Works (P43) | `BF.openImagePlus` works headless (P43 O5); exporter does not (P46) | P43, P46 |
| Determinism of display | Uses stored ranges | `autoscale=true` by default, so display depends on data | importer-options.txt; P43 O5 |

## 9. Constraints and Limitations

- Each path's fidelity depends on IJ1 version-specific private tags. Third-party tools ignore `MetaData` 50839. **INFERRED**
- P43/P47 used one synthetic fixture. Floating-point images, calibration functions, `inverty`, origins, multi-series files and large or virtual stacks were not covered.

## 10. Alternatives and Trade-offs

| Default save format for "analysis results" | Pro | Con |
|---|---|---|
| IJ1 TIFF | Full IJ fidelity (P43 O1); fast; headless-safe | IJ-specific metadata invisible to other tools |
| OME-TIFF | Interoperable OME-XML, units | Loses overlay/ROI/Info/ranges on re-import (P47); headless export broken (P46) |
| SCIFIO save | IJ2-native | Time calibration lost on read (P43 O9); not the IJ1 user's expectation |

**PROPOSED:** IJ1 TIFF is the default for saving analysis images. OME-TIFF is an explicit "export for other software" action, run in the GUI-capable JVM. PNG/JPEG are labelled as "snapshot (lossy)".

## Requirement implications

1. **CR-A14-01**: Saving an image from the new UI shall default to the IJ1 TIFF writer (`IJ.saveAsTiff`/`File › Save As › Tiff…`) and shall preserve pixels, dimensions, calibration, frame interval, composite mode, per-channel display ranges, overlay, ROI, Info and slice labels, as verified by a round-trip test against the P43 reference. *Rationale:* only path with full fidelity (§7).
2. **CR-A14-02**: Every export to a format listed as lossy in §7 (PNG, JPEG, GIF, BMP, 8-bit conversion, RGB flattening) shall show, before writing, which properties will be lost. *Rationale:* silent loss of calibration and bit depth (P43 O6–O8, L1).
3. **CR-A14-03**: OME-TIFF export shall run in a GUI-capable (non-headless) JVM, and the system shall verify that the output file exists and is non-empty before reporting success. *Rationale:* headless exporter failure writes nothing and raises no macro error (P46).
4. **CR-A14-04**: Opening files shall use the same routing as Fiji's `File › Open` (`IJ.openImage`/`Opener`, with Bio-Formats delegation) unless the user explicitly picks the Bio-Formats importer, whose options (series, colour mode, autoscale, ROI import) shall be exposed. *Rationale:* familiar semantics; IJ1 and Bio-Formats differ in metadata (§8).
5. **CR-A14-05**: The system shall record, for each opened image, the reader used (IJ1, Bio-Formats, SCIFIO) and the key metadata (dimensions, calibration, units, frame interval) in the workflow history. *Rationale:* metadata depends on the path (§7); reproducibility.
6. **CR-A14-06**: The system shall not route IJ1 images through SCIFIO/`Dataset` conversion for saving, unless a test shows the frame interval and display ranges survive. *Rationale:* P43 O9/O11 losses.

## Risks

| ID | Risk | Likelihood | Impact | Mitigation idea |
|---|---|---|---|---|
| R-A14-01 | Users believe exported PNG/JPEG figures preserve quantitative data | High | High | Fidelity warning (CR-A14-02); separate "snapshot" action |
| R-A14-02 | Bio-Formats autoscaling changes displayed contrast between sessions, confusing "familiar semantics" | Medium | Medium | Expose `autoscale`; record display ranges in history |
| R-A14-03 | Headless pipelines silently skip OME-TIFF export | High (if headless) | High | CR-A14-03; output existence checks |
| R-A14-04 | The unexplained OME-TIFF size inflation (P47) wastes storage or indicates wrong export settings | Low | Medium | Investigate with `showinf`; set compression explicitly |

## References

- `imagej/ImageJ@v1.54p`: `ij/io/Opener.java`, `ij/io/FileOpener.java`, `ij/io/TiffDecoder.java`, `ij/io/FileSaver.java`, `ij/plugin/PNG_Writer.java`, `ij/Macro.java`; `Fiji/jars/ij-1.54p.jar!/IJ_Props.txt`.
- `ome/bioformats@v8.5.0`: [Exporter.java](https://github.com/ome/bioformats/blob/v8.5.0/components/bio-formats-plugins/src/loci/plugins/out/Exporter.java), [importer-options.txt](https://github.com/ome/bioformats/blob/v8.5.0/components/bio-formats-plugins/src/loci/plugins/in/importer-options.txt) (accessed 2026-10-17).
- `imagej/imagej-legacy@imagej-legacy-3.0.0`: `ImageJ2Options.java`, `plugin/DefaultLegacyOpener.java`.
- Probes: `probes/P43-file-format-fidelity.groovy`/`.out.txt`, `probes/P46-headless-followups.out.txt`, `probes/P46-stdout-stderr.txt`, `probes/P47-ome-tiff-gui-jvm-body.groovy`, `probes/P47-ome-tiff-gui-jvm.out.txt`, `probes/P45-class-hierarchy-javap.out.txt`, `probes/P48-plugins-config-lines.csv`.

## Change log

- 2026-10-17: Initial draft (agent G3).
