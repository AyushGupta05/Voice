---
id: A24
title: Licensing and Redistribution
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p, imagej/imagej2@imagej-2.18.0, imagej/pyimagej@8f9b8faf, SYSTRAN/faster-whisper@v1.2.1, ggml-org/whisper.cpp@0c7be696, fiji/fiji@ddc9a45d, "Fiji Windows x64 archive 20261013-1517 (lock f6695397…)"]
related_requirements: []
related_decisions: [DEC-005]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A24 — Licensing and Redistribution

> **Not legal advice.** This document lists facts that can be checked in the pinned artefacts, the engineering consequences that follow from them, and the questions a lawyer must answer. It does not conclude that any distribution model is compliant.

## Executive Summary

- The pinned Fiji contains **662 jars**: 556 in `jars/`, 13 in `jars/win64/` and 93 in `plugins/`. Probe P70 reads their embedded metadata. **117 jars declare only GPL-family licences**: 88 of the 93 jars in `plugins/` and 29 in `jars/`. **161 jars declare no licence** in their pom, manifest or root LICENSE file, so each of those needs manual checking. Apache-2.0 (173 jars) and BSD-2 (124) are the largest permissive families. One jar is AGPL-3.0: `itextpdf-5.5.13.4.jar`. **VERIFIED** (`probes/P70-jar-licences-summary.txt`).
- The core stack that this project would call directly is permissive or public domain. ImageJ1 `ij-1.54p.jar` declares "Public domain". `imagej-2.18.0`, `imagej-legacy-3.0.0`, `ij1-patcher-2.0.0`, `scijava-common-2.101.0`, `imglib2-8.0.0`, `scifio-0.49.2`, `imagej-updater-2.0.3`, and Bio-Formats `formats-api` and `formats-bsd` 8.5.0 all declare "Simplified BSD License". Several components the user reaches through Fiji menus are GPL: `formats-gpl-8.5.0` and `bio-formats_plugins-8.5.0` (GPL-2.0+), and `fiji-2.18.1-SNAPSHOT` and `fiji-lib-2.1.3` (GPL-3.0+). **VERIFIED** (P70).
- The Fiji bundle as a whole declares "GNU General Public License v3+" ([`third_party/fiji-installed-pom.xml`](../../../third_party/fiji-installed-pom.xml#L24)). Its `LICENSES` file states that "Fiji as a bundle is licensed under the GPL, with exceptions for plugins and other components" ([Fiji-LICENSES.txt](../../../third_party/licenses/Fiji-LICENSES.txt#L3)). **VERIFIED**.
- The bundled Zulu 21.0.7 JDK is GPLv2 with the Classpath Exception, plus Azul's `DISCLAIMER` terms. Azul offers source for three years ("For a period of three years… Azul will provide upon request…"). **VERIFIED** (`Fiji/java/win64/zulu21.42.19-ca-jdk21.0.7-win_x64/readme.txt`, `legal/java.base/LICENSE#L326`).
- The prototype's speech venv has two redistribution problems that are new findings. First, the PyAV 18.1.0 wheel bundles FFmpeg DLLs that report "LGPL version 3 or later", but it also ships `libx264` and `libx265` DLLs, which are GPL-licensed upstream (INFERRED). Second, the `ctranslate2` 4.8.3 wheel bundles NVIDIA `cudnn64_9.dll` and Intel `libiomp5md.dll`, both proprietary. **VERIFIED** for file presence (P73); the licence terms of those DLLs are **UNKNOWN**.
- The project's own code licence is **undecided** ([NOTICE.md L14](../../../third_party/NOTICE.md#L14); DEC-005 is Open). §10 lists the options and their constraints. No option is chosen here.

## Purpose and Scope

This document covers the licences of everything the Fiji Modernisation Project could ship or depend on: the pinned Fiji bundle and its JDK, the prototype's Python and npm dependency trees, the speech stack (faster-whisper, CTranslate2, Whisper weights and whisper.cpp), the PyImageJ and JPype bridge, and any LLM option. It covers redistribution aspects only. It does not cover patents, export control or trademark. The Fiji and ImageJ names are trademark questions and are listed in §11.

## Sources and Evidence

| Source | What was read | Evidence |
|---|---|---|
| P70 | 662 jars: `META-INF/maven/**/pom.xml` `<licenses>`, `MANIFEST.MF` `Bundle-License`, and root and `META-INF` `LICENSE*`/`COPYING*`, read with `unzip -p` | `probes/P70-jar-licences.csv`, `probes/P70-jar-licences-summary.txt` |
| P73 | `.venv-stt` native DLLs and `METADATA` | [probes/README-G7.md](../references/probes/README-G7.md) |
| `Fiji/licenses/` | 44 notice files | directory listing, 2026-10-17 |
| `third_party/` | `NOTICE.md`, `licenses/*`, `core-packages.json` (27 entries), `speech-packages.json` (24 entries), `fiji-installed-pom.xml` | repository files at `08a1433` |
| `frontend/package-lock.json` | lockfile v3, 70 packages | repository file |
| Upstream LICENSE files | `fiji-research-cache/{ImageJ,imagej2,fiji,pyimagej,faster-whisper,whisper.cpp}` at the tags in the front matter; JPype, scyjava, jgo, CTranslate2 and Silero VAD fetched from GitHub `main`/`master` | VERIFIED as documentation |
| [ImageJ licensing](https://imagej.net/licensing/) (accessed 2026-10-17) | Project licence summary | VERIFIED as documentation |
| [GNU GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation) (accessed 2026-10-17) | FSF's position on aggregation versus combined works | Documentation of the FSF's interpretation, not law |

## Verified Findings

### 5.1 Jar licence census (P70)

| Licence family (as declared) | Jars | Notes |
|---|---|---|
| Apache-2.0 | 173 | Includes Groovy 4.0.28 via `Bundle-License`, Jackson, Google Cloud, AWS SDK and `ome-poi` |
| none declared | 161 | Includes the Bouncy Castle, Kotlin stdlib, OpenCV/FFmpeg JavaCPP presets (licence inherited from a parent pom that is not embedded), JavaFX 23.0.2, JOGL, `jai-core`/`jai-codec` 1.1.3, `jpedalSTD`, EJML, JAMA and the Scala 3 compiler. The full list is in the summary file. |
| BSD-2 ("Simplified BSD License") | 124 | ImageJ2/SciJava/ImgLib2/SCIFIO/OME core |
| GPL-3.0(+) | 92 | `fiji`, `fiji-lib`, most `sc.fiji:*` plugins, TrackMate 8.1.6, `weka-dev` 3.9.6, `jep` 2.4.2 |
| BSD-3 | 30 | Includes `jython-slim-2.7.4` (from its LICENSE file) |
| GPL-2.0(+) | 29 | `formats-gpl`, `bio-formats_plugins`, `metakit`, `scifio-lifesci`, `imglib2-algorithm-gpl`, `mpicbg`, BigStitcher, Stitching |
| LGPL | 17 | logback 1.3.16, jfreechart 1.5.6, rhino 1.7.14, `ome-mdbtools` |
| MIT | 15 | slf4j-api 2.0.17, jsoup |
| Other / unversioned BSD | 22 | R "Part of R 2.14.2" (Renjin), Unicode-3.0 (icu4j), CUP, Janelia (H5J loader) |
| EPL / MPL / CDDL / CC0 / CC-BY | 6 / 1 / 1 / 5 / 1 | CC-BY 3.0 is declared by `ome:specification` (schema documentation) |
| GPL with Classpath Exception | 6 | `java3d-core`, `vecmath`, `j3dcore`, `scijava-vecmath`, `javacpp` (also Apache-2.0), `javax.servlet-api` (CDDL+GPL-CPE) |
| AGPL-3.0 | 1 | `itextpdf-5.5.13.4.jar` |

A jar that declares several licences counts in each family, so the column does not sum to 662. Dual licences such as `javacpp` (Apache-2.0 / GPL-2.0+ with CPE), `jgrapht-core` (LGPL-2.1 / EPL-2.0) and `javassist` (MPL-1.1 / LGPL-2.1 / Apache-2.0) are offered at the recipient's choice. **VERIFIED** (P70 CSV rows).

### 5.2 `Fiji/licenses/` and `third_party/`

- `Fiji/licenses/` holds 44 notice files. They are almost all for the cloud and N5 storage stack: AWS SDK, Google Cloud/HTTP/OAuth (Apache-2.0 and BSD), protobuf (BSD-3), Jackson, `javax.annotation-api` (CDDL + GPLv2 with Classpath Exception), and the blosc, lz4, snappy, zlib, zstd and n5 codecs. It is **not** a complete licence set for the bundle. **VERIFIED** (listing).
- `Fiji-LICENSES.txt` (141 lines) is a historical per-component table. It lists components that are absent from the pin: TurboReg, StackReg, MosaicJ and other EPFL BIG plugins, Clojure and Batik. Its own "Unresolved (TODO)" section names the Example scripts folder and JAI (`jai_codec.jar`, `jai_core.jar`) (L139–141). Both are present in the pin (`jars/jai-core-1.1.3.jar` and `jars/jai-codec-1.1.3.jar` declare no licence, per P70). It also records that EPFL BIG and TurboReg permissions cover redistribution "within Fiji only" (L107–135), and that JEP 2.4.1 was "the last to be distributed under GPL" (L114). **VERIFIED**.
- `third_party/NOTICE.md` states that no upstream implementation source has been pasted in and that the owner has not chosen a code licence or redistribution policy (L14). **VERIFIED**.

### 5.3 ImageJ1 public-domain status

`ij-1.54p.jar` declares `Public domain` in its pom (P70). [ImageJ-LICENSE.txt](../../../third_party/licenses/ImageJ-LICENSE.txt) reproduces the NIH disclaimer: 17 U.S.C. §105, "not subject to copyright protection". imagej.net adds two caveats: "that waiver of copyright applies only to U.S. law, and does not apply to other countries", and "the ImageJ project includes substantial effort and code from individuals who are not U.S. government employees, making the legal status of ImageJ as a whole unclear" ([ImageJ licensing](https://imagej.net/licensing/)). **VERIFIED as documentation**. The status outside the US and the status of non-NIH contributions are **UNKNOWN** (Q-03).

### 5.4 ImageJ2 and SciJava (BSD-2)

`imagej/imagej2@imagej-2.18.0:LICENSE.txt` reads "Copyright (c) 2009 - 2026, ImageJ2 developers. All rights reserved. Redistribution and use in source and binary forms…". That is the 2-clause BSD text. All core ImageJ2, SciJava, ImgLib2 and SCIFIO jars declare "Simplified BSD License" (P70). The obligation is to keep the copyright notice and disclaimer in source and binary distributions. **VERIFIED**.

### 5.5 Bio-Formats GPL/BSD split

| Jar (8.5.0) | Declared | Role |
|---|---|---|
| `formats-api` | Simplified BSD | Reader/writer API |
| `formats-bsd` | Simplified BSD | Open formats: TIFF, OME-TIFF, OME-XML and others |
| `formats-gpl` | GNU GPL v2+ | Proprietary-format readers (vendor formats) |
| `bio-formats_plugins` | GNU GPL v2+ | ImageJ importer/exporter plugins (`loci.plugins.*`) |
| `ome-xml`, `ome-common`, `ome-codecs` | Simplified BSD | OME data model and codecs |
| `ome-mdbtools` | LGPL v2.1+ | MDB parsing |
| `metakit` | GPL v2+ | Metakit parsing |

**VERIFIED** (P70). imagej.net states that "the core of Bio-Formats is licensed under BSD-2" ([ImageJ licensing](https://imagej.net/licensing/)). Consequence (INFERRED): code that only uses `formats-api`/`formats-bsd` sits under BSD terms. Any use of `loci.plugins.BF` or the vendor readers in `formats-gpl` involves GPL-2.0+ code. The project's I/O design (A19/A20) must record which jar each I/O path loads.

### 5.6 Speech stack

| Component | Version | Declared licence | Evidence |
|---|---|---|---|
| faster-whisper | 1.2.1 | MIT ("Copyright (c) 2023 SYSTRAN") | `SYSTRAN/faster-whisper@v1.2.1:LICENSE`; [licenses/faster-whisper-MIT.txt](../../../third_party/licenses/faster-whisper-MIT.txt) — VERIFIED |
| CTranslate2 | 4.8.3 | MIT (`METADATA` `License: MIT`; upstream "Copyright (c) 2018- SYSTRAN") | P73 (ad hoc inspection; no stored output); GitHub `OpenNMT/CTranslate2` LICENSE — VERIFIED. **No notice file copied** (`speech-packages.json` lists `notices: 0`) |
| Whisper code and weights | — | MIT. The upstream README says "Whisper's code and model weights are released under the MIT License." | [openai/whisper README L160](https://github.com/openai/whisper) (accessed 2026-10-17); [Whisper-MIT.txt](../../../third_party/licenses/Whisper-MIT.txt) — VERIFIED as documentation |
| Systran `faster-whisper-tiny.en` conversion | rev `0d3d19a3…` | Model card front matter `license: mit`; "conversion of openai/whisper-tiny.en to the CTranslate2 model format" | `external/models/tiny.en/README.md`; `external/stt.lock.json` — VERIFIED |
| Silero VAD (`faster_whisper/assets/silero_vad_v6.onnx`) | v6 | MIT ("Copyright (c) 2020-present Silero Team") | `faster-whisper@v1.2.1:faster_whisper/vad.py#L13,L291`; GitHub `snakers4/silero-vad` LICENSE — VERIFIED |
| onnxruntime | 1.31.0 | MIT (`METADATA`) | P73 — VERIFIED (ad hoc inspection; no stored output); no notice copied |
| tokenizers / flatbuffers | 0.23.3 / 25.12.19 | Apache-2.0 (`Classifier` / `License: Apache 2.0`) | P73 — VERIFIED (ad hoc inspection; no stored output); no notice copied |
| tqdm | 4.70.1 | `MPL-2.0 AND MIT` | P73 — VERIFIED (ad hoc inspection; no stored output); no notice copied |
| PyAV (`av`) | 18.1.0 | `BSD-3-Clause` for the bindings. The bundled FFmpeg reports `LGPL version 3 or later`, and its `configure` string includes `--enable-version3 --enable-libx264 --enable-libx265 --enable-nvenc --enable-amf` with no `--enable-gpl`. `av.libs/` ships `libx264-165-*.dll` and `libx265-*.dll`. | P73 — VERIFIED (ad hoc inspection; no stored output) (strings and files). The licence of the shipped x264/x265 binaries in this combination is **UNKNOWN**. x264 and x265 are GPL-2.0+ upstream (INFERRED, not checked against their sources here). |
| CTranslate2 bundled DLLs | — | `ctranslate2/cudnn64_9.dll` (NVIDIA cuDNN) and `ctranslate2/libiomp5md.dll` (contains "Copyright (C) 1997-2025, Intel Corporation. All Rights Reserved.") | P73 — VERIFIED (ad hoc inspection; no stored output) presence; redistribution terms **UNKNOWN** |
| whisper.cpp | master `0c7be696` | MIT ("Copyright (c) 2023-2026 The ggml authors") | `ggml-org/whisper.cpp@0c7be696:LICENSE` — VERIFIED. Its converted `ggml-*.bin` weights are downloaded from `huggingface.co/ggerganov/whisper.cpp` (`models/README.md` L27); the licence statement for that repository is **UNKNOWN** (INFERRED to inherit Whisper MIT) |

The prototype only decodes short microphone clips. It does not need the x264/x265 encoders, the CUDA DLL or NVENC/AMF. INFERRED: if the speech path were changed to accept 16 kHz PCM WAV from the browser, PyAV and its codec DLLs could be removed from any redistributed speech runtime, and with them the GPL encoder question.

### 5.7 Python dependencies (`third_party/*-packages.json`)

- **Core (27 entries):** MIT (11), BSD-3-Clause (5), Apache-2.0 (1, `opentelemetry-api`), `Apache-2.0 OR BSD-2-Clause` (`packaging`), `MIT-CMU` (`pillow` 12.0.0), `PSF-2.0` (`typing_extensions`), BSD-2 (`Pygments`). Seven entries have no SPDX expression. Their copied LICENSE files show `h11` and `pluggy` are MIT, `httpx` and `colorama` are BSD-style, and `certifi` contains "a modified version of ca-bundle.crt" (MPL-2.0 per upstream, INFERRED). The entry `click-to-batch 0.1.0` is stale: the project is now `fidelis` 0.2.0 in `pyproject.toml`. pytest, ruff, pip and pluggy are development tools and would not ship. **VERIFIED**.
- **Speech (24 entries):** as in §5.6, plus `numpy` 2.4.6 (`BSD-3-Clause AND 0BSD AND MIT AND Zlib AND CC0-1.0`, with 17 notice files), `huggingface_hub` 2.2.0 (Apache-2.0), `hf-xet` (Apache-2.0), `protobuf` 7.36.2 (BSD-3) and `PyYAML` (MIT). **VERIFIED**. Five packages have no notice file in `third_party/`: ctranslate2, flatbuffers, onnxruntime, tokenizers and tqdm. That gap must be closed before any redistribution (CR-A24-04).

### 5.8 Frontend (`frontend/package-lock.json`)

The lockfile is v3 with 70 packages. Runtime dependencies that reach the bundle: `react` 19.3.1, `react-dom` 19.3.1 and `scheduler` 0.28.0, all MIT. Dev-only packages: Vite 8.3.4 (MIT), TypeScript 7.0.2 and 20 platform binaries (Apache-2.0), `lightningcss` 1.33.0 plus 11 platform binaries (MPL-2.0), `picocolors` (ISC), `source-map-js` (BSD-3) and `detect-libc` (Apache-2.0). MPL-2.0 is file-level copyleft. Here it attaches only to the build tool, and lightningcss output is not MPL-covered (INFERRED). The root package declares no `license`. **VERIFIED**.

### 5.9 PyImageJ and JPype

| Component | Licence | Evidence |
|---|---|---|
| PyImageJ | Apache-2.0. "Copyright (C) 2013 - 2020 Board of Regents of the University of Wisconsin-Madison. Licensed under the Apache License, Version 2.0" | `imagej/pyimagej@8f9b8faf:LICENSE.txt`; `pyproject.toml#L9` `license = "Apache-2.0"` — VERIFIED |
| JPype1 (`jpype1>=1.4.0`) | Apache-2.0 | GitHub `jpype-project/jpype` `master` LICENSE — VERIFIED as documentation (no version pinned) |
| scyjava, jgo | Unlicense (public-domain dedication) | GitHub `scijava/scyjava` and `scijava/jgo` `main` `UNLICENSE` — VERIFIED as documentation |
| imglyb, labeling, xarray | not checked | **UNKNOWN** |

PyImageJ loads Fiji's jars into a JVM **inside the Python process** through JPype. For GPL purposes that is the same address space as Fiji's GPL jars (§6, Q-01).

### 5.10 Bundled JDK

Zulu 21.42.19 (OpenJDK 21.0.7+6, `IMPLEMENTOR="Azul Systems, Inc."`). `legal/java.base/LICENSE` is GPLv2 with the "CLASSPATH" EXCEPTION (L326), and `ASSEMBLY_EXCEPTION` is present. `DISCLAIMER` binds anyone "installing, using or distributing this software" to Azul's terms, which defer to the open-source licences where they conflict. `readme.txt` contains Azul's three-year source offer. The JDK includes the module `com.azul.crs.client` (Azul Connected Runtime Service; see A25 T-17). The 72 `legal/` subfolders must be kept. **VERIFIED**.

### 5.11 LLM options (redistribution aspects only)

| Option | Redistribution aspect | Evidence |
|---|---|---|
| Local model weights shipped in the installer | Each model has its own licence. Some are OSI-style (Apache-2.0/MIT); others are custom "community" licences with use policies and notice duties. For example, the Llama 3.1 Community License requires a "Notice" text file and caps licensees above 700 million MAU ([copy on Hugging Face](https://huggingface.co/shafire/QuantumAI/blob/main/License), accessed 2026-10-17; a third-party mirror, so re-read the official text). | VERIFIED as documentation for Llama 3.1 only; every other model is **UNKNOWN** until one is selected |
| Local weights downloaded by the user at first run | The project does not redistribute the weights. The user accepts the model licence directly (INFERRED). | INFERRED |
| Hosted API (any provider) | No weights are redistributed. Obligations are contractual: terms of service, data-use and retention terms, and attribution or output terms. Sending image metadata or file names is data egress (A25 T-11). | INFERRED; each provider's terms must be read at selection |
| Runtime (llama.cpp/ggml, ONNX Runtime and similar) | Normally MIT/Apache. Bundled GPU DLLs (CUDA/cuDNN) raise the same question as in §5.6. | INFERRED |

## Technical Details

### 6.1 GPL and process topology

The relevant distinction is between (a) original code that runs **inside Fiji's JVM** and calls Fiji classes, and (b) original code that runs **in a separate process** and talks to Fiji over IPC.

| Topology | Examples | Fact base | Engineering reading (INFERRED, not legal) |
|---|---|---|---|
| A. Fiji plugin or service jar in the same JVM | A SciJava `@Plugin` bridge, an `ij.plugin.PlugIn` or a Groovy script shipped by the project | Core APIs (`ij.*` public domain, `net.imagej.*`/`org.scijava.*` BSD-2) are permissive. GPL jars are loaded in the same JVM: `fiji-lib`, `formats-gpl`, `bio-formats_plugins`, the `sc.fiji:*` plugins, TrackMate, Weka. | If the bridge imports or calls only PD/BSD classes, those APIs impose no copyleft. If it imports GPL classes (for example `loci.plugins.BF` or `fiji.util.*`), the FSF would treat the result as a combined work. Invoking a GPL plugin through a command string (`IJ.run("Bio-Formats Importer", …)`) is late-bound dispatch, and its classification is an open legal question (Q-01). |
| B. In-process JVM from Python | PyImageJ/JPype loading `Fiji/jars` into the Python process | Same JVM as A, now also hosting Python code | Same questions as A, plus whether the Python backend becomes part of a combined work with GPL jars (Q-02). |
| C. Separate process with IPC | The prototype: `fiji-windows-x64.exe --headless -macro <file>`, results through files ([fiji.py L48–L142](../../../backend/src/fidelis/adapters/fiji.py#L48)) | The FSF FAQ says pipes, sockets and command-line arguments are "normally" communication between separate programs, with exceptions when the exchange is intimate enough to make them one program ([GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation)) | This is the strongest separation argument. [upstreams.md L31](../../upstreams.md#L31) already notes that process separation "is an engineering boundary, not a claim that distribution obligations disappear". |
| D. Distribution | Shipping the Fiji folder, the JDK or speech DLLs in the installer, versus downloading the official archive at install time | GPL-3.0 §6 (object code plus Corresponding Source), GPLv2 §3 for the JDK, and the "within Fiji only" permissions in `Fiji-LICENSES.txt` | Bundling makes the project a redistributor of every GPL, LGPL, AGPL and "no licence declared" component in it. Installing from the official Fiji archive by checksum (as `docs/setup.md` L46 describes) may shift that role, but this is Q-05. |

### 6.2 Component × licence × obligation × redistribution impact

| Component (pinned) | Licence | Main obligation if redistributed | Impact on the project |
|---|---|---|---|
| ImageJ1 `ij-1.54p.jar` | Public domain (US); non-US unclear | Keep the disclaimer | Low; Q-03 |
| ImageJ2/SciJava/ImgLib2/SCIFIO/imagej-legacy/ij1-patcher/updater | BSD-2 | Keep the copyright notice and disclaimer | Low; compatible with any code licence |
| Bio-Formats `formats-api`/`formats-bsd`/`ome-*` | BSD-2 | Notices | Low |
| Bio-Formats `formats-gpl`, `bio-formats_plugins` | GPL-2.0+ | Source offer and licence text; copyleft on combined works | High if linked in-JVM (topology A/B); medium if only bundled |
| Fiji launcher and aggregate (`fiji-2.18.1-SNAPSHOT`, `fiji-lib`) | GPL-3.0+ | GPL-3 §4–6 for the bundle | Bundling Fiji means GPL-3 distribution duties |
| 88 GPL `plugins/*.jar` and 29 GPL `jars/*.jar` | GPL-2.0+/GPL-3.0+ | As above | As above; each must be checked if the project calls it directly |
| `itextpdf-5.5.13.4.jar` | AGPL-3.0 | AGPL §13 (network interaction) when modified | Avoid any direct use. The localhost bridge (A25) is a network interface, so Q-07 |
| `jep-2.4.2.jar`, `weka-dev-3.9.6.jar`, `jfreesvg-3.4.4.jar` | GPL-3 (JEP "last … under GPL") | As GPL | Same as the GPL row |
| `jars/win64/javafx-*`, `opencv`, `ffmpeg` (JavaCPP), `jai-*`, `jpedalSTD`, Bouncy Castle and the other 161 undeclared jars | Undeclared in jar metadata | Unknown until checked | Manual licence review needed before any bundled release (CR-A24-02) |
| EPFL BIG, TurboReg (not in the pin) | "Distribution within Fiji only" | Permission scoped to Fiji | A redistributed fork or a renamed bundle may fall outside the permission; Q-06 |
| Zulu JDK 21.0.7 | GPLv2 + Classpath Exception, Azul DISCLAIMER | Keep `legal/`, `DISCLAIMER` and `readme.txt`; GPLv2 §3 source availability for the JDK binaries | Medium; the Classpath Exception avoids copyleft on applications running on the JVM |
| faster-whisper, CTranslate2, onnxruntime, Silero VAD, Whisper weights, Systran conversion | MIT | Keep the notices | Low; copy the missing notices |
| tokenizers, flatbuffers, huggingface_hub, hf-xet | Apache-2.0 | Notices, plus NOTICE files if present | Low |
| tqdm | MPL-2.0 AND MIT | Source of MPL files on request | Low |
| PyAV 18.1.0 and FFmpeg DLLs | BSD-3 bindings; FFmpeg LGPL-3.0+; x264/x265 DLLs GPL (INFERRED) | LGPL relinking and source; GPL source for x264/x265 | **High until resolved**: replace or rebuild before redistribution (CR-A24-05) |
| `cudnn64_9.dll`, `libiomp5md.dll` | Proprietary (NVIDIA, Intel) | Vendor redistribution terms (UNKNOWN) | **High until resolved**: exclude from a CPU-only redistributable or confirm the terms |
| PyImageJ / JPype / scyjava / jgo | Apache-2.0 / Apache-2.0 / Unlicense / Unlicense | Notices | Low as libraries; the topology B question remains |
| React/react-dom/scheduler | MIT | Notice in the bundle | Low |
| Vite, TypeScript, lightningcss and other build tools | MIT / Apache-2.0 / MPL-2.0 | None unless shipped | Not shipped |
| whisper.cpp (candidate) | MIT; ggml weights UNKNOWN | Notices | Low; check the weights' licence |
| LLM weights (candidate) | Per model | Per model | Must be decided at selection |

## Constraints and Limitations

- P70 reads declared metadata only. Declarations can be wrong or incomplete; for example, `jfreesvg`'s pom says "GNU General Public License (GPL)" with no version. Nested jars and native libraries inside jars (the JavaCPP presets carry FFmpeg and OpenCV DLLs) are not classified.
- The classifier labels a jar whose pom inherits `<licenses>` from a parent that is not embedded as "none declared". This affects the `org.bytedeco` presets and possibly others.
- No upstream source was compared with the declared licence headers.

## Requirements or Design Implications

### 10. Project code licence options (DEC-005, not decided)

| Option | Compatibility constraints (INFERRED) |
|---|---|
| GPL-3.0-or-later | Matches the Fiji bundle and allows in-JVM linking to GPL-2.0+ and GPL-3 plugins. Not compatible with GPL-2.0-only code; P70 shows the GPL-2 jars mostly declare "v2+", but each must be checked. Upstreaming a GPL-3 component into ImageJ2/SciJava (BSD-2) would require relicensing. |
| BSD-2-Clause | Matches ImageJ2/SciJava and makes upstream contribution easiest. It can be combined with GPL code, but a distributed combination as a whole is then subject to the GPL. |
| Apache-2.0 | Includes an explicit patent grant. It is compatible with GPL-3 but not with GPL-2.0-only. Matches PyImageJ and JPype. |
| MIT | Permissive and GPL-compatible. No patent grant. |
| MPL-2.0 | File-level copyleft; GPL-compatible through its Secondary Licence clause unless marked "Incompatible With Secondary Licenses". |
| Split licence (for example BSD-2 for any Fiji-side bridge jar and another licence for the Python/TypeScript app) | Lets the bridge be contributed upstream. More notices to manage. |

Whatever is chosen: AGPL `itextpdf` must not be linked, and the "within Fiji only" permissions block redistribution of BIG and TurboReg outside an official Fiji distribution.

### Requirement implications

1. **CR-A24-01**: The project shall record, for every bundled or downloaded third-party component, its name, exact version, SHA-256, declared licence(s), notice-file path and source location in a machine-readable inventory under `docs/data/`. *Rationale:* P70 shows 161 jars without declared licences, and §5.7 shows 5 Python packages without copied notices.
2. **CR-A24-02**: A bundled-distribution release shall not proceed until every component in that bundle that is "none declared" in the P70 output has a licence identified from an upstream source, with evidence recorded. *Rationale:* undeclared licences cannot be honoured.
3. **CR-A24-03**: Original project code shall not import classes from GPL- or AGPL-declared jars (for example `loci.plugins.*`, `fiji.util.*`, `com.itextpdf.*`) unless DEC-005 explicitly allows it. *Rationale:* this keeps the licence decision open; it can be checked with `jdeps`/import scans.
4. **CR-A24-04**: Any redistributable speech runtime shall include the licence and notice texts of every installed package, including ctranslate2, flatbuffers, onnxruntime, tokenizers and tqdm. *Rationale:* those notices are currently missing (`notices: 0`).
5. **CR-A24-05**: A redistributable speech runtime shall contain no GPL-licensed or proprietary native libraries (x264, x265, cuDNN, Intel OpenMP) unless their redistribution terms have been confirmed and recorded. *Rationale:* P73 found these DLLs, and the speech path does not need them.
6. **CR-A24-06**: The installer or packaging process shall keep `Fiji/licenses/`, the JDK `legal/` folder, `DISCLAIMER`, `readme.txt`, and all jar-embedded licence files unmodified. *Rationale:* GPLv2/3, BSD and Apache notice duties; Azul's terms.
7. **CR-A24-07**: The I/O layer shall record which Bio-Formats jar (`formats-bsd` or `formats-gpl`) served each read, in the provenance record. *Rationale:* this supports licence analysis and scientific provenance (A19/A20).
8. **CR-A24-08**: Any LLM integration shall record the model or API identity, its licence or terms URL and the date it was checked, and shall not ship model weights in the installer unless their licence permits redistribution. *Rationale:* LLM licences vary widely.

## Risks and Unknowns

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A24-01 | A bundled release breaches GPL or AGPL duties (missing source offer or notices) | Medium | High | CR-A24-01/02/06; prefer installing the official Fiji archive by checksum, subject to Q-05 |
| R-A24-02 | In-JVM bridge code becomes a combined work with GPL plugins against the owner's licence choice | Medium | High | CR-A24-03; restrict the bridge to PD/BSD APIs; legal review (Q-01) |
| R-A24-03 | The speech runtime redistributes GPL encoders or proprietary DLLs unintentionally | High (they are present now) | Medium | CR-A24-05; WAV input path; build a CPU-only minimal wheel set |
| R-A24-04 | The status of ImageJ1 as public domain outside the US is challenged | Low | Medium | Keep the disclaimer; legal question Q-03 |
| R-A24-05 | Undeclared-licence jars (JAI, JPedal, JavaFX, JavaCPP presets) carry incompatible terms | Medium | Medium | CR-A24-02 |
| R-A24-06 | The project's product name or branding implies endorsement by Fiji/ImageJ | Medium | Medium | Naming decision DEC-001; trademark question Q-08 |

### Open licensing questions for the owner and counsel

- **Q-01:** Does a Fiji-side plugin that calls GPL plugins only through `IJ.run(String, String)` command strings form a combined work with them? What if it imports GPL classes directly?
- **Q-02:** Does PyImageJ/JPype (one OS process hosting Python code and Fiji's GPL jars) make the Python backend part of a combined work?
- **Q-03:** What is the copyright status of ImageJ1 1.54p outside the US, and of its non-NIH contributions?
- **Q-04:** Is the macro text the project generates and sends to Fiji (topology C) "intimate communication" in the FSF sense?
- **Q-05:** If the installer downloads the official Fiji archive from `downloads.imagej.net` and verifies its SHA-256, is the project a distributor of Fiji? What notice and source duties remain?
- **Q-06:** Do the "within Fiji only" permissions (EPFL BIG, TurboReg) extend to a modified or rebranded front end that uses an unmodified Fiji?
- **Q-07:** Does exposing Fiji commands on a localhost HTTP/WebSocket API count as "interacting with it remotely through a computer network" (AGPL §13) for any AGPL component that is used?
- **Q-08:** What are the trademark constraints on using "Fiji" and "ImageJ" in the product name, installer and UI?
- **Q-09:** Under what terms may `cudnn64_9.dll`, `libiomp5md.dll` and the PyAV x264/x265 DLLs be redistributed, if at all?
- **Q-10:** Which licence applies to the project's original code (DEC-005), and does the owner want to contribute components upstream (which favours BSD-2)?

## Decisions Required

- **DEC-005** (Open): the original-code licence and the redistribution model (bundle Fiji, JDK and models, or install them from official sources by checksum). This document informs the decision and does not make it.
- A new decision, to be raised by the lead: whether the speech runtime moves to WAV/PCM input so that PyAV can be dropped (CR-A24-05).

## References

- `probes/P70-jar-licences.csv`, `probes/P70-jar-licences-summary.txt`, `probes/README-G7.md` (P70, P73).
- [third_party/NOTICE.md](../../../third_party/NOTICE.md); [Fiji-LICENSES.txt](../../../third_party/licenses/Fiji-LICENSES.txt); [ImageJ-LICENSE.txt](../../../third_party/licenses/ImageJ-LICENSE.txt); [fiji-installed-pom.xml](../../../third_party/fiji-installed-pom.xml); [core-packages.json](../../../third_party/core-packages.json); [speech-packages.json](../../../third_party/speech-packages.json); [frontend/package-lock.json](../../../frontend/package-lock.json); [docs/upstreams.md](../../upstreams.md).
- `imagej/ImageJ@v1.54p:LICENSE.txt`; `imagej/imagej2@imagej-2.18.0:LICENSE.txt`; `fiji/fiji@ddc9a45d:LICENSE.txt`, `LICENSES`; `imagej/pyimagej@8f9b8faf:LICENSE.txt`, `pyproject.toml#L9`; `SYSTRAN/faster-whisper@v1.2.1:LICENSE`, `faster_whisper/vad.py#L13`; `ggml-org/whisper.cpp@0c7be696:LICENSE`, `models/README.md#L27`.
- Fiji install: `Fiji/java/win64/zulu21.42.19-ca-jdk21.0.7-win_x64/{DISCLAIMER,readme.txt,release,legal/java.base/LICENSE}`; `Fiji/licenses/`.
- ImageJ, [Licensing](https://imagej.net/licensing/), accessed 2026-10-17.
- Free Software Foundation, [Frequently Asked Questions about the GNU Licenses — Mere aggregation](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation), accessed 2026-10-17.
- OpenAI, [whisper README, License section](https://github.com/openai/whisper), `main`, accessed 2026-10-17.
- jpype-project, [JPype LICENSE](https://github.com/jpype-project/jpype/blob/master/LICENSE); scijava, [scyjava UNLICENSE](https://github.com/scijava/scyjava/blob/main/UNLICENSE), [jgo UNLICENSE](https://github.com/scijava/jgo/blob/main/UNLICENSE); OpenNMT, [CTranslate2 LICENSE](https://github.com/OpenNMT/CTranslate2/blob/master/LICENSE); snakers4, [silero-vad LICENSE](https://github.com/snakers4/silero-vad/blob/master/LICENSE). All accessed 2026-10-17.
- Meta, Llama 3.1 Community License (third-party copy at [huggingface.co/shafire/QuantumAI](https://huggingface.co/shafire/QuantumAI/blob/main/License)), accessed 2026-10-17.

## Change log

- 2026-10-17: Initial draft (G7).
- 2026-10-17: Phase 8 review fixes (findings #29).
