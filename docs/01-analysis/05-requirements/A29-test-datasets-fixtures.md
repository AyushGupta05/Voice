---
id: A29
title: Test Images, Datasets and Fixture Analysis
stage: 01-analysis
status: Draft
priority: P1
source_revisions: [imagej/ImageJ@v1.54p, "Fiji Windows x64 archive 20261013-1517 (lock f6695397…)", "prototype snapshot 08a1433"]
related_requirements: []
related_decisions: [DEC-005]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A29 — Test Images, Datasets and Fixture Analysis

## Executive Summary

- The authoritative fixture catalogue is [`docs/data/datasets.csv`](../../data/datasets.csv). It has 52 fixture rows: FX-001–FX-002 already in the repo, FX-003–FX-023 public ImageJ/Fiji samples, FX-030–FX-031 BBBC, FX-040–FX-048 OME, FX-050–FX-052 macro corpora, FX-060–FX-062 speech sets to be created, and FX-070–FX-081 deterministic synthetic edge cases. Recorded data is **metadata only**. Probes P71 and P72 read at most 64 KiB headers through HTTP Range requests and stored no image bytes.
- **All fixtures are for software regression and numerical equivalence; none is ML training data.** The project trains no image model (product vision; A01). BBBC ground truth serves as a scientific reference for counting and segmentation regression, not as a training set.
- **Licensing determines what can live in the repository.** The ImageJ sample directory states no licence (**VERIFIED**: [imagej.net/ij/images](https://imagej.net/ij/images/), accessed 2026-10-17; [ImageJ licensing](https://imagej.net/licensing/) does not mention data). Several samples credit third parties: Molecular Probes, Tony Collins, Duke CIVM/UNC and the Heisenberg lab. **BBBC001 and BBBC004 are CC BY-NC-SA 3.0, not CC0** (**VERIFIED**, BBBC pages). OME samples are CC BY 4.0 (**VERIFIED**, `readme.txt` in each folder). Only original synthetic fixtures (FX-001, FX-070–FX-081) are unencumbered, subject to DEC-005.
- Real samples already cover most edge cases. Probe P71 found **signed-16 calibration** in `ct.dcm.zip` (`c0=-32768`), an **anisotropic voxel** in Fly Brain (2.676 × 2.676 × 3.947 µm), a **12-bit-in-16-bit** stack in Organ of Corti (max 4095), a **5-D hyperstack** in Mitosis (C=2, Z=5, T=51), and **calibration that the sample's own note marks as inaccurate** in HeLa. **VERIFIED** (`probes/P71-sample-image-headers.csv`).

## Purpose and Scope

This document identifies the images, datasets, macro corpora and speech stimuli needed to verify that the modernised front end preserves Fiji's scientific behaviour, and maps them to candidate requirement areas. Those areas are IMG, VIEW, ROI, PROC, MEAS, IO, MACRO, CMD, NL and SPEECH (F04 §3). Because A26 and A27 are not yet written, the mapping is to candidate coverage areas only. Tolerances and the reference-execution method belong to A30 and D18.

## Research Questions

1. Which official samples exist, with what dimensions, bit depths and licence status?
2. What do BBBC001 and BBBC004 contain, what ground truth do they carry, and under what licence?
3. Which OME samples exercise OME-TIFF, multi-file, multi-series, pyramid and BigTIFF paths?
4. Which edge cases are not covered by public data and must be synthesised?
5. What speech material is needed, and under what consent and licence?

## Sources and Evidence

| Source | Use | Evidence |
|---|---|---|
| `Fiji/jars/ij-1.54p.jar!/IJ_Props.txt` L434–L470 | Sample list `open01`–`open30` and base URL `images.location=http://imagej.net/images/` | VERIFIED |
| `Fiji/plugins/Samples_-2.0.3.jar!/plugins.config` | Four Fiji sample links (`samples.fiji.sc`, `imagej.net/media`) | VERIFIED |
| P71 | Headers of 32 samples | `probes/P71-sample-image-headers.{py,csv}` |
| P72 | Headers of BBBC and OME files; BBBC001 counts | `probes/P72-bbbc-ome-headers.{py,csv}` |
| [BBBC001](https://bbbc.broadinstitute.org/BBBC001), [BBBC004](https://bbbc.broadinstitute.org/BBBC004) (accessed 2026-10-17) | Content, ground truth, licence, sizes | VERIFIED as documentation |
| Ljosa, Sokolnicki and Carpenter (2012), *Annotated high-throughput microscopy image sets for validation*, Nat Methods 9:637, [doi:10.1038/nmeth.2083](https://doi.org/10.1038/nmeth.2083) (PMID 22743765, PMC3627348) | BBBC citation | VERIFIED (ID lookup) |
| [OME downloads](https://downloads.openmicroscopy.org/images/OME-TIFF/2016-06/) (accessed 2026-10-17) | Listings and `readme.txt` | VERIFIED |
| [fixtures/README.md](../../../fixtures/README.md), [third_party/NOTICE.md](../../../third_party/NOTICE.md), [scripts/create_fixture.py](../../../scripts/create_fixture.py) | Repo fixture provenance | VERIFIED |

## Verified Findings

### 5.1 Repository fixtures

| ID | File | Provenance | Measured properties (2026-10-17) |
|---|---|---|---|
| FX-001 | `fixtures/two-squares.png` | `scripts/create_fixture.py`: `Image.new("L",(64,64),0)`, rectangles (8,8)–(23,23)=100 and (40,40)–(55,55)=200 | 133 B, 64×64 mode `L`, SHA-256 `e251d96a…a0e493`. Expected mean 18.75 and inverted mean 236.25 (README) |
| FX-002 | `fixtures/invert-command.wav` | Windows `System.Speech.Synthesis.SpeechSynthesizer`, text "Invert the image." (NOTICE.md) | 85,780 B, mono, 16-bit, 22,050 Hz, 42,867 frames (1.944 s), SHA-256 `97041f87…c58c8` |

Both are used by `tests/backend/test_integration.py` (L48, L76, L101). The README itself says FX-002 "does not establish accuracy on human speech, accents or noise".

### 5.2 ImageJ `File > Open Samples` (P71, header-only)

| Menu label (`IJ_Props` key) | File | Download size | Dimensions and type (from header) | Notable metadata |
|---|---|---|---|---|
| Blobs (`open04`) | `blobs.gif` | 25,765 B | 256×254, 8-bit indexed | — |
| Fluorescent Cells (`open13`) | `FluorescentCells.zip` | 436,133 B | 512×512 8-bit, 3 channels | `mode=composite` |
| HeLa Cells 48-bit RGB (`open16`) | `hela-cells.zip` | 1,374,676 B | 672×512 16-bit, 3 channels | `unit=um`, 19.42 px/µm ("not accurate") |
| T1 Head 16-bits (`open27`) | `t1-head.zip` | 2,556,794 B | 256×256×129 16-bit | 1.5 mm isotropic |
| Confocal Series (`open09`) | `confocal-series.zip` | 2,386,406 B | 400×400 8-bit, C=2 Z=25 | `hyperstack=true`, 0.0545 µm |
| Mitosis 5D (`open20`) | `Spindly-GFP.zip` (inner `mitosis.tif`) | 27,314,269 B | 171×196 16-bit, C=2 Z=5 T=51 | `finterval=0.1429` |
| Embryos (`open12`) | `embryos.jpg` | 132,040 B | 1600×1200 RGB | JPEG |
| Leaf (`open18`) | `leaf.jpg` | 36,642 B | 507×446 RGB | — |
| Bat Cochlea Volume (`open02`) | `bat-cochlea-volume.zip` | 19,538 B | 121×154×114 8-bit | Written by ImageJ 1.20n; scale only in the `.txt` (40 px/mm) |
| Fly Brain (`open14`) | `flybrain.zip` | 1,024,478 B | 256×256×57 RGB | anisotropic 2.676/3.947 µm |
| M51 Galaxy 16-bits (`open22`) | `m51.zip` | 181,190 B | 320×510 16-bit | ImageJ 1.19c header |
| CT 16-bit DICOM (`open10`) | `ct.dcm.zip` (inner `ct.dcm.tif`) | 420,716 B | 888×495 16-bit | `c0=-32768 c1=1` signed calibration |
| Cardio RGB DICOM (`open06`) | `cardio.dcm.zip` | 592,705 B | 1000×1000 RGB DICOM | DICOM header |
| Neuron 5 channels (`open23`) | `Rat_Hippocampal_Neuron.zip` | 1,661,444 B | 512×512 16-bit, C=5 | `mode=color` |
| Organ of Corti 4D (`open25`) | `organ-of-corti.zip` | 3,008,956 B | 548×249 16-bit, C=4 Z=15 | `max=4095` |
| Nile Bend (`open24`) | `NileBend.jpg` | 2,005,492 B | 4200×4200 RGB | Largest sample by pixel count |

The remaining entries are AuPbSn 40, Bat Cochlea Renderings, Boats, Cell Colony, Clown, Dot Blot, Gel, Image with Overlay, Line Graph, MRI Stack, Particles, T1 Head Renderings, TEM Filter and Tree Rings. They are in the P71 CSV. Licence: none stated for any of them (**UNKNOWN**). The base URL is plain HTTP (A25 T-09).

### 5.3 BBBC001 and BBBC004

| | BBBC001 v1 | BBBC004 v1 |
|---|---|---|
| Content | Human HT29 colon cancer cells; "6 fields of view"; 512×512, "native DIB format and converted to 8-bit TIFF" | Synthetic nuclei from the SIMCEP simulator; 5 subsets × 20 images at overlap 0, 0.15, 0.30, 0.45 and 0.60; 300 objects per image |
| Ground truth | Two manual counts per image (`BBBC001_v1_counts.txt`, 303 B, for example `A03f00d0` 350/362 and `A03f04d0` 398/533) | Count (exactly 300) plus binary foreground masks |
| Size | TIFF zip 854,168 B; DIB zip 1.9 MB | Image zips ≈13 MB each (000: 13,034,200 B; 060: 12,651,758 B); foreground zips 401–469 kB |
| Licence | **CC BY-NC-SA 3.0** (David Root, Anne Carpenter) | **CC BY-NC-SA 3.0** (Pekka Ruusuvuori) |
| Citation | Carpenter et al. 2006 + Ljosa et al. 2012 | Ruusuvuori et al. EUSIPCO 2008 + Ljosa et al. 2012 |
| Evidence | BBBC page; P72 (counts file, zip member `human_ht29_colon_cancer_1_images/AS_09125_050118150001_A03f00d0.tif`) | BBBC page; P72 (member `synthetic_000_images/10GRAY.tif`, IFD0 at uncompressed offset 909,606) |

The BBBC004 pixel dimensions are not stated on the page. 950×950 8-bit is **INFERRED**: it agrees with the IFD0 offset (≥ 902,500 bytes of 8-bit data) and with third-party SIMCEP metadata. Verify once the file is fetched under the download policy. The two BBBC001 manual counts differ by up to 135 (A03f04d0), so the counts are a band, not an exact oracle (A30).

The brief's assumption (CC0 or CC BY) is **contradicted**. The NC and SA terms prevent bundling these sets in a commercial or differently licensed distribution (INFERRED; A24).

### 5.4 OME samples (P72; all CC BY 4.0 unless noted)

| ID | Path under `OME-TIFF/2016-06/` | Header facts |
|---|---|---|
| FX-040 | `bioformats-artificial/single-channel.ome.tif` (76,095 B) | 439×167 `int8` (SampleFormat 2), XYZCT, 1 plane |
| FX-041 / FX-042 | `bioformats-artificial/multi-channel-4D-series.ome.tif` / `.btf` (7.9/8.0 MB) | Z=5 C=3 T=7, `int8`. The `.btf` is BigTIFF |
| FX-043 | `tubhiswt-4D/` (per-file 2,846,357 B; zip 108 MB) | 512×512 `uint8`, Z=10 C=2 T=43, XYZTC, multi-file |
| FX-044 | `companion/` (5 × 1,716 B + `multifile.companion.ome`) | `BinaryOnly` TIFFs plus a companion |
| FX-045 | `plate-companion/` (5 wells × 11,770 B + `hcs.companion.ome`) | Plate with 4 wells and 5 fields (readme); `BinaryOnly` |
| FX-046 | `sub-resolutions/Z-stack/retina_large.ome.tiff` (225 MB) | 2048×1567 `uint8` Z=64 C=2, 2 SubIFD levels, deflate |
| FX-047 | `sub-resolutions/Brightfield/Leica-1/Leica-1.ome.tiff` (723 MB) | BigTIFF, 1616×4668 RGB, JPEG, 2 SubIFDs, 2 OME `Image` elements (multi-series) |
| FX-048 | `MitoCheck/00001_01.ome.tiff` (132 MB) | 1344×1024 `uint8` T=93, `<Plate>`, 3.3 MB ImageDescription |

Not used: `OME-TIFF/2016-06/BBBC/NIRHTa-001.ome.tiff`. Its readme marks it deprecated because of a column offset, and it is CC BY-NC-SA 3.0.

### 5.5 Macro corpora

- The `imagej.net/ij/macros/` top level lists 403 `.txt`/`.ijm` files (index fetched 2026-10-17) plus subfolders (`bsh/`, `examples/`, `js/`, `python/`, `tools/`, `toolsets/`). No licence is stated (**UNKNOWN**). The corpus is useful for tokeniser, command-coverage and Recorder-style parsing tests. It must never be executed automatically (A25 CR-A25-05).
- The pinned Fiji ships 74 script and macro files across `macros/`, `plugins/Macros`, `plugins/Examples` and `scripts/`: 19 MATLAB `.m`, 15 `.py`, 11 `.txt`, 11 `.ijm`, 10 `.bsh`, 6 `.rb`, 6 `.clj`, 5 `.js` and 1 `.groovy`. `scripts/File/Open_Samples/*.ijm` holds 5 macros that **generate** sample images deterministically (FX-052). **VERIFIED** (directory listing).

### 5.6 Speech material

The only speech fixture is FX-002 (synthetic, one sentence, one voice). The material needed is listed below; all of it is **PROPOSED**.

| Need | Detail |
|---|---|
| Vocabulary | Fiji menu labels as spoken ("Gaussian Blur", "Convert to Mask", "Z Project", "Analyze Particles"); numbers and decimals ("sigma two point five"); units ("micrometres", "microns", "µm"); channel and slice references; ambiguous homophones ("mean" versus "median"); and negations and corrections ("no, sigma three") |
| Synthetic (FX-060) | Several TTS engines and voices at several rates. Record engine, voice, version and parameters. The output-licence terms differ by engine. Windows System.Speech voice terms for FX-002 are **UNKNOWN** |
| Human (FX-061) | Written consent covering storage, sharing licence and withdrawal. Coverage of accents, including L2 English, and speaking rates. No personal content in the prompts. Store outside the repo unless the consent and licence allow redistribution |
| Noise (FX-062) | Lab noise (fans, centrifuge, HVAC, keyboard) mixed at fixed SNRs, so that runs can be reproduced |
| Purpose | Acceptance and regression of ASR plus command parsing (A16/A22). The model is not fine-tuned |

### 5.7 Synthetic edge-case fixtures (FX-070–FX-081)

Each fixture is generated at test time by a macro in the reference Fiji, using `newImage`, `setPixel`, `setVoxelSize` and `run("Properties...")`, or by a Python writer. The generator script and the SHA-256 of its output are stored, not the image. Useful APIs: `ij.measure.Calibration#setSigned16BitCalibration()` (`Calibration.java#L495`); `File > Import > TIFF Virtual Stack...` (`IJ_Props.txt` `import10`, `ij.plugin.FileInfoVirtualStack`). For FX-075, per-plane values encode (c, z, t) so that dimension-order errors are detectable. FX-080 is a header-only TIFF/BigTIFF that claims 100,000 × 100,000 × 16-bit and contains no pixel data. FX-081 carries hostile metadata and file names for the A25 prompt-injection and macro-injection tests.

## Technical Details

### 6.1 Coverage matrix (candidate areas)

| Area | Primary fixtures | Gap filled by synthesis |
|---|---|---|
| IMG (types and conversion) | FX-001, 004, 005, 006, 009, 013, 014, 017 | FX-070 (>4095), FX-071 (NaN/Inf), FX-072 (signed-16), FX-073 (RGB), FX-079 (degenerate) |
| VIEW (display and navigation) | FX-007, 008, 016, 021, 046 | FX-074 (LUTs), FX-075 (5-D) |
| ROI | FX-003, 018, 020, 030, 031 | FX-077 |
| PROC | FX-001, 003, 006, 019, 031 | FX-075, FX-076 |
| MEAS | FX-001, 003, 005, 014, 030, 031 | FX-072, FX-077, FX-078 |
| IO | FX-014, 015, 040–048 | FX-076, FX-079, FX-080, FX-081 |
| MACRO / CMD | FX-001, 050, 051, 052 | FX-075, FX-081 |
| NL | FX-081 | FX-060 transcripts as text |
| SPEECH | FX-002 | FX-060, 061, 062 |

### 6.2 Regression versus ML

| Class | Fixtures | Oracle |
|---|---|---|
| Analytic oracle | FX-001, FX-070–FX-081 | Values known by construction |
| Reference-execution oracle | FX-003–FX-023, FX-040–FX-048 | Output of the pinned Fiji run headless (A30). Visual similarity is not equivalence (F04 §9) |
| External ground truth | FX-030 (counts band), FX-031 (300 objects; masks) | Scientific plausibility check of the Fiji pipeline, not of a learned model |
| Speech evaluation | FX-002, FX-060–FX-062 | Transcript and command-parse accuracy |
| **Training data** | **None** | The project trains no image model. The ASR model is used as published |

### 6.3 Download policy (PROPOSED)

- In repository: only original synthetic fixtures under 1 MB, plus generator scripts.
- Fetch on demand: public samples under 50 MB. Fetch into a git-ignored cache (for example `data/fixtures-cache/`), pin the SHA-256 on first fetch in a lock file, and re-verify on every use. Fetch over HTTPS where the host supports it. `imagej.net/ij/images/` is served over HTTPS (P71 fetched it so), even though `IJ_Props.txt` uses HTTP.
- Manual only: anything over 50 MB (FX-043 zip, FX-046, FX-047, FX-048). CI uses header-only checks for these.
- Never redistribute NC-licensed sets (FX-030, FX-031) in a release artefact.

## Constraints and Limitations

- Pixel statistics (min/max values, histograms) were not measured; only headers were read. For example, whether M51 contains values above 4095 is **UNKNOWN**.
- BBBC004 dimensions and the BBBC001 TIFF compression are **UNKNOWN** until fetched, because the IFDs sit beyond the probed range.
- DICOM samples may contain identifying header fields. Inspect them before use (A25 T-02).

## Requirements or Design Implications

### Requirement implications

1. **CR-A29-01**: Every test fixture shall have a row in `docs/data/datasets.csv` with source URL, licence, size, SHA-256 (once fetched) and coverage areas before any test uses it. *Rationale:* traceability and licence control.
2. **CR-A29-02**: The repository shall contain only fixtures whose licence permits redistribution under the project licence. Other fixtures shall be fetched to a git-ignored cache and verified by SHA-256. *Rationale:* ImageJ samples have no stated licence; BBBC is NC-SA.
3. **CR-A29-03**: Each fixture used for scientific equivalence shall have a stored reference result from the pinned Fiji (lock `f6695397…`), produced by a recorded headless macro. *Rationale:* reference-execution oracle (A30).
4. **CR-A29-04**: The synthetic edge-case fixtures FX-070–FX-081 shall be produced by versioned generator scripts whose outputs are byte-identical across runs (same SHA-256). *Rationale:* determinism.
5. **CR-A29-05**: Per-commit CI shall use only fixtures under 5 MB (PROPOSED threshold, to keep runtime short). Larger fixtures shall run in a scheduled or manual suite. *Rationale:* FX-008 is 27 MB and the OME pyramids are 0.2–0.7 GB.
6. **CR-A29-06**: Human speech recordings shall be collected only with written consent that covers storage location, licence and withdrawal, and shall be stored with speaker metadata limited to accent group and recording condition. *Rationale:* privacy (A25).
7. **CR-A29-07**: No fixture, BBBC ground truth included, shall be used to train or fine-tune a model in this project. *Rationale:* product scope; the NC-SA terms.

## Risks and Unknowns

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A29-01 | Upstream sample URLs change or disappear (HTTP-only, 2017 timestamps) | M | M | CR-A29-02 hash pinning; synthetic replacements |
| R-A29-02 | NC-SA data enters a redistributed artefact | L | H | CR-A29-02; licence column checked in CI |
| R-A29-03 | Equivalence tests rely on visual comparison | M | H | CR-A29-03; numerical tolerances (A30) |
| R-A29-04 | Speech tests overfit synthetic voices and fail on real users | H | M | FX-061/FX-062; report results per accent group |
| R-A29-05 | DICOM samples carry identifiers | L | M | Inspect headers; prefer synthetic DICOM if needed |

## References

- `probes/P71-sample-image-headers.csv`, `probes/P72-bbbc-ome-headers.csv`, `probes/README-G7.md`.
- [docs/data/datasets.csv](../../data/datasets.csv).
- `Fiji/jars/ij-1.54p.jar!/IJ_Props.txt` L434–L470; `Fiji/plugins/Samples_-2.0.3.jar!/plugins.config`; `imagej/ImageJ@v1.54p:ij/measure/Calibration.java#L495`, `ij/plugin/FileInfoVirtualStack.java#L11`.
- ImageJ, [sample images directory](https://imagej.net/ij/images/) and [macros directory](https://imagej.net/ij/macros/), accessed 2026-10-17.
- Broad Institute, [BBBC001](https://bbbc.broadinstitute.org/BBBC001) and [BBBC004](https://bbbc.broadinstitute.org/BBBC004), accessed 2026-10-17.
- Ljosa V, Sokolnicki KL, Carpenter AE (2012). Annotated high-throughput microscopy image sets for validation. *Nat Methods* 9:637. [doi:10.1038/nmeth.2083](https://doi.org/10.1038/nmeth.2083).
- OME Consortium, [OME-TIFF sample files 2016-06](https://downloads.openmicroscopy.org/images/OME-TIFF/2016-06/), accessed 2026-10-17.
- Neumann B et al. (2010). Phenotypic profiling of the human genome by time-lapse microscopy reveals cell division genes. *Nature* 464:721–727. doi:10.1038/nature08869 (as cited in the MitoCheck `readme.txt`).

## Change log

- 2026-10-17: Initial draft (G7).
