---
id: A06
title: Existing Solutions and Competitive Analysis
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p, "Fiji pinned install (lock f6695397…)", scijava-ui-swing-1.0.5, scijava-search-3.0.2, "napari@main (docs fetched 2026-10-17)", "fiji/fiji-llm@main (fetched 2026-10-17)"]
related_requirements: []
related_decisions: [DEC-001]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A06 — Existing Solutions and Competitive Analysis

## Executive Summary

The project's three headline ideas all exist already in some form:

1. **A modern single-window workstation.** QuPath, napari and Icy are single-window. ImageJ2 itself ships an MDI Swing UI in the pinned install.
2. **Reproducible command history.** The ImageJ Recorder, QuPath's Command history with *Create script*, napari-assistant and CellProfiler pipelines all provide this.
3. **Natural-language driving of image-analysis software.**
   - **Fiji/ImageJ:** the official `fiji/fiji-llm` project, ImageJAI, fiji-mcp and Agentic-J.
   - **napari:** Omega.
   - **General:** BioImage.IO Chatbot and bia-bob.

None of these is a novel contribution, and the design documents must not claim otherwise (**VERIFIED**: sources in §Verified Findings).

Two areas have no direct precedent:

- **Voice control.** This search found no voice- or speech-driven control for Fiji, napari, QuPath, Icy or CellProfiler. The only voice-capable example was a research mixed-reality pathology viewer (PathVis, arXiv 2505.02780). This is **UNKNOWN** beyond the search performed; it is not a verified gap.
- **The specific combination.** No tool in the search pairs a modernised shell over unchanged ImageJ1 semantics with natural-language and voice input that resolves to existing Fiji commands under explicit user confirmation, and that records each step as a replayable `run()` line. This combination is **PROPOSED** as the project's differentiator. It is a positioning claim, not a technical first.

The most consequential finding is that **`fiji/fiji-llm` is an official Fiji repository**. It provides an in-app chat, an MCP server, and SciJava-discovered tools such as `fiji_command_run`, `fiji_script_run`, `fiji_macro_run` and read access to the Results table and ROI Manager, under BSD-2-Clause. Its update site is "currently unlisted". The lead must decide whether to build on it, interoperate with it, or diverge from it, and justify the choice (§Decisions Required).

## Purpose and Scope

- Compare Fiji/ImageJ1, the ImageJ2 Swing UI, napari, CellProfiler, QuPath, Icy, and AI-assisted tools on the following dimensions: UI model, image-state model, scripting reproducibility, plugin ecosystem, natural-language features, viewer performance and licence.
- Map existing solutions onto the problems in [A05](A05-usability-workflow-problems.md) (UP-nn) and say what to learn from or reuse.
- Out of scope: algorithm quality, benchmarks (none were found or run), and commercial tools.

## Sources and Evidence

Each web source was fetched on 2026-10-17. Local claims come from the pinned Fiji jars (lock `f66953972bb5…bafa`). Software described from documentation is **VERIFIED as documentation**. None of it was installed or run in this stage.

## Verified Findings

### F1 — ImageJ1 / Fiji legacy UI

| Dimension | Finding | Evidence |
|---|---|---|
| UI model | AWT main window (menu bar, toolbar, status bar). Each image, table, plot and tool lives in its own top-level frame. | `imagej/ImageJ@v1.54p:ij/Menus.java#L110-L238`; window list under Window › (Show All, Cascade, Tile), `Menus.java#L233-L238` — **VERIFIED** |
| Image state | `ImagePlus` per window; implicit current image via `WindowManager.getCurrentImage()`; single process-wide Undo; per-image ROI plus a global ROI Manager; one system Results table | `ij/WindowManager.java#L69-L75`, `ij/Undo.java#L30`, `ij/plugin/filter/Analyzer.java#L40` — **VERIFIED** |
| Scripting / reproducibility | Macro language plus the Recorder ("each menu command you use generates a macro run() function call"); also JavaScript/BeanShell/Java recording modes | [Guide 146-31](https://imagej.net/ij/docs/guide/146-31.html) (accessed 2026-10-17) — **VERIFIED as documentation** |
| Command discovery | Command Finder, Plugins › Utilities › Find Commands… [l] | IJ_Props `utilities02`; Guide 146-31 — **VERIFIED** |
| Plugins | `PlugIn`/`PlugInFilter` classes, `plugins.config`, the Fiji Updater with update sites | A03 §Sources — **VERIFIED** |
| Viewer performance | Virtual stacks: "disk resident (as opposed to RAM resident)" and "read-only" | [Guide 146-8](https://imagej.net/ij/docs/guide/146-8.html) — **VERIFIED as documentation** |
| Licence | ImageJ1 is public domain ("no copyright applies", U.S. only). Fiji's dominant licence is GPL, which varies by component. | [imagej.net/licensing](https://imagej.net/licensing/) — **VERIFIED as documentation** |

### F2 — ImageJ2 / SciJava UI layer (inside the pinned Fiji)

| Dimension | Finding | Evidence |
|---|---|---|
| UI model | SciJava UI plugins exist: `org.scijava.ui.swing.sdi.SwingSDIUI` and **`org.scijava.ui.swing.mdi.SwingMdiUI`**, a multiple-document interface with `JMDIDesktopPane`. The Look & Feel option is `org.scijava.ui.swing.laf.OptionsLookAndFeel` (Edit menu), with FlatLaf bundled (`flatlaf-3.6.1.jar`). | `unzip -l Fiji/jars/scijava-ui-swing-1.0.5.jar` — **VERIFIED** (presence only; whether Fiji can start in MDI mode is UNKNOWN) |
| Search | Fiji search bar (`org.scijava.ui.swing.search.SwingSearchBar`, `scijava-search-3.0.2.jar`) injected into the legacy main window (`net.imagej.legacy.search.SearchBarHacker`, imagej-legacy-3.0.0). It is "a convenient way to quickly find and launch commands, search for documentation, and more". Ctrl/Cmd+L. | Jar listings; [imagej.net/learn](https://imagej.net/learn/) — **VERIFIED** |
| Scripting | Script parameters `#@` generate input dialogs automatically and allow headless runs | [Script parameters](https://imagej.net/scripting/parameters) — **VERIFIED as documentation** |
| Modules | ModuleService "Tracks available modules, and provides the infrastructure for executing them"; menus are "UI-agnostic" trees | [imagej.net/libs/scijava](https://imagej.net/libs/scijava) — **VERIFIED as documentation** |
| Licence | BSD-2-Clause (ImageJ2, SciJava) | `docs/data/upstream-manifest.yaml` UP-02; [licensing](https://imagej.net/licensing/) — **VERIFIED** |

### F3 — napari

| Dimension | Finding | Evidence |
|---|---|---|
| UI model | Single Qt window (via `qtpy`; PyQt6/PySide6 optional extras) with VisPy GPU rendering; docked widgets; a command palette merged in v0.6.0 (PR #5483) | [github.com/napari/napari](https://github.com/napari/napari); [pyproject.toml](https://raw.githubusercontent.com/napari/napari/main/pyproject.toml); [PR #5483](https://github.com/napari/napari/pull/5483) — **VERIFIED as documentation** |
| Image state | `ViewerModel` ("Viewer containing the rendered scene, layers, and controlling elements") holds a layer list of seven types: Image, Labels, Points, Shapes, Surface, Tracks, Vectors. Qt is excluded from `napari.components` and `napari.layers` by import-linter contracts. | [viewer_model.py](https://raw.githubusercontent.com/napari/napari/main/src/napari/components/viewer_model.py); [layers guide](https://napari.org/stable/getting_started/layers.html); pyproject import-linter contracts — **VERIFIED as documentation** |
| Scripting / reproducibility | Python and IPython console. **No native macro or action recorder.** Issue [#2579](https://github.com/napari/napari/issues/2579) "Undo/ Redo & Macro Recording" (2021) is still open. Third-party options: napari-macrokit ("Executable script generation for napari plugins") and napari-assistant (exports the workflow as a Python script or notebook). | Issue and repos linked — **VERIFIED as documentation** |
| Plugins | npe2 engine; each plugin ships a `napari.yaml` manifest with contributions (commands, readers, writers, widgets, sample_data, themes, menus…). npe1 only works through auto-conversion from 0.7.0. | [Plugins](https://napari.org/stable/plugins/index.html); [manifest reference](https://napari.org/stable/plugins/technical_references/manifest.html) — **VERIFIED as documentation** |
| Viewer performance | Multiscale pyramids; lazy array materialisation; asynchronous slicing (experimental setting) | [rendering guide](https://napari.org/stable/guides/rendering.html); [image layer how-to](https://raw.githubusercontent.com/napari/docs/main/docs/howtos/layers/image.md) — **VERIFIED as documentation** |
| Licence / citation | BSD-3-Clause; cited as napari contributors (2019), [doi:10.5281/zenodo.3555620](https://doi.org/10.5281/zenodo.3555620). No peer-reviewed napari paper was found. | GitHub README — **VERIFIED** |

### F4 — CellProfiler

| Dimension | Finding | Evidence |
|---|---|---|
| UI model | Pipeline editor. "A pipeline is a sequential set of image analysis modules." | [Manual 4.2.6: building pipelines](https://cellprofiler-manual.s3.amazonaws.com/CellProfiler-4.2.6/help/pipelines_building.html) |
| Image state | Named images and objects flow between modules per image set. There is no interactive mutable image. | Same; **INFERRED** from the pipeline model |
| Reproducibility | The pipeline file *is* the record. Test mode "allows you to run the pipeline on a selected image, preview the results and adjust the module settings on the fly". Headless run: `cellprofiler -c -r -p <pipeline.cppipe> -o <out>`. | [Test mode](https://cellprofiler-manual.s3.amazonaws.com/CellProfiler-4.2.6/help/navigation_test_menu.html); [command-line blog](https://carpenter-singh-lab.broadinstitute.org/blog/getting-started-using-cellprofiler-command-line) |
| Batch | CreateBatchFiles for cluster processing | [File processing modules](https://cellprofiler-manual.s3.amazonaws.com/CellProfiler-4.2.6/modules/fileprocessing.html) |
| Licence / papers | BSD-3-Clause; Carpenter et al. (2006) [doi:10.1186/gb-2006-7-10-r100](https://doi.org/10.1186/gb-2006-7-10-r100); Stirling et al. (2021) [doi:10.1186/s12859-021-04344-9](https://doi.org/10.1186/s12859-021-04344-9) | [LICENSE](https://raw.githubusercontent.com/CellProfiler/CellProfiler/main/LICENSE) |

### F5 — QuPath

| Dimension | Finding | Evidence |
|---|---|---|
| UI model | Single window with an Analysis panel; project-based ("it is highly recommended to use a Project"; "Some commands and scripts only work within projects") | [Projects](https://qupath.readthedocs.io/en/stable/docs/tutorials/projects.html) |
| Image state | Objects (annotations, detections, TMA cores) "stored in a hierarchical, tree-like structure" per image | [Object hierarchy](https://qupath.readthedocs.io/en/stable/docs/concepts/object_hierarchy.html); [Objects](https://qupath.readthedocs.io/en/stable/docs/concepts/objects.html) |
| Reproducibility | "QuPath automatically logs many of the commands that are run in a Command history" (Workflow tab). *Create script* turns the history into a script; *Run for project* applies it to all images. | [Workflows](https://qupath.readthedocs.io/en/stable/docs/scripting/workflows.html); [Workflows to scripts](https://qupath.readthedocs.io/en/stable/docs/scripting/workflows_to_scripts.html); [Scripting overview](https://qupath.readthedocs.io/en/stable/docs/scripting/overview.html) |
| Scripting | Groovy (examples in the docs) | Scripting overview |
| Licence / paper | GPL-3.0; Bankhead et al. (2017) [doi:10.1038/s41598-017-17204-5](https://doi.org/10.1038/s41598-017-17204-5) | [github.com/qupath/qupath](https://github.com/qupath/qupath) |

### F6 — Icy

| Dimension | Finding | Evidence |
|---|---|---|
| UI model | Single application window with viewers; **Protocols**, a graphical workflow editor: "graphically design the workflow of plugins and scripts to be executed" | [icy.bioimageanalysis.org](https://icy.bioimageanalysis.org/) |
| ImageJ interop | "Yes, ImageJ is integrated in Icy"; Protocols include "Call IJ Plugin" blocks | [Icy FAQ](https://icy.bioimageanalysis.org/?p=3217); [Protocols](https://icy.bioimageanalysis.org/?p=464) |
| Plugins | In-app plugin search and install | Home page |
| Licence / paper | Current Icy-Kernel main branch: LGPL-3.0 ("Icy is free and open source (LGPL3 license)"); older README and plugins state GPL-3. de Chaumont et al. (2012) [doi:10.1038/nmeth.2075](https://doi.org/10.1038/nmeth.2075). | [Icy-Kernel README](https://raw.githubusercontent.com/Icy-imaging/Icy-Kernel/main/README.md) |

### F7 — AI-assisted and LLM-driven tools

| Tool | Host | What it does | Licence | Evidence |
|---|---|---|---|---|
| **fiji/fiji-llm** (official Fiji org) | Fiji (SciJava, langchain4j) | In-app chat; MCP server exposing Fiji tools to external clients; tool registry via SciJava plugins; context from "installed commands, open images, analysis metadata, the Script Editor, and macro recorder"; tools `fiji_command_run`, `fiji_script_run`, `fiji_macro_run`; read-only Results and ROI Manager access; models: Gemini, Ollama, Claude, OpenAI. Update site "currently unlisted". | BSD-2-Clause | [github.com/fiji/fiji-llm](https://github.com/fiji/fiji-llm) — **VERIFIED as documentation** |
| ImageJAI | Fiji plugin (community) | "describing image analysis in plain language"; generates macros and self-corrects on failure (up to 3 retries); cloud or local Ollama models | BSD-3 | [Jay2owe/ImageJAI](https://github.com/Jay2owe/ImageJAI) |
| fiji-mcp | Fiji (community) | "live scripting interface into a running Fiji instance via WebSocket"; tools `run_ij_macro`, `run_command`, `get_thumbnail` | MIT | [SteffenPL/fiji-mcp](https://github.com/SteffenPL/fiji-mcp) |
| Agentic-J | ImageJ/Fiji (research) | "containerised, multi-agent AI assistant"; generates executable scripts "so every analysis decision is traceable" | — | Johanns et al. (2026), [arXiv:2606.02080](https://arxiv.org/abs/2606.02080) |
| Omega (napari-chatgpt) | napari | "LLM-based and tool-armed autonomous agent"; writes and executes Python and widgets and controls the viewer. The README warns "it will download libraries of its own volition" and recommends a sandboxed VM. | BSD-3 | Royer (2024) [doi:10.1038/s41592-024-02310-w](https://doi.org/10.1038/s41592-024-02310-w); [royerlab/napari-chatgpt](https://github.com/royerlab/napari-chatgpt) |
| BioImage.IO Chatbot | Web / ImJoy / hypha | Retrieval-augmented documentation Q&A with a ReAct loop; extensions can run tools such as Cellpose | — | Lei et al. (2024) [doi:10.1038/s41592-024-02370-y](https://doi.org/10.1038/s41592-024-02370-y); [repo](https://github.com/bioimage-io/bioimageio-chatbot) |
| bia-bob | Jupyter | `%bob` and `%%bob` magics write Python code and notebooks. "Users are responsible to verify the generated code according to good scientific practice." | — | [haesleinhuepf/bia-bob](https://github.com/haesleinhuepf/bia-bob) |
| LLM code-generation benchmark | — | "quantitative benchmark to estimate the capability of LLMs to generate code for solving common BIA tasks" (15 LLMs) | — | Haase et al. (2024) [doi:10.1101/2024.04.19.590278](https://doi.org/10.1101/2024.04.19.590278) |
| Non-LLM AI in Fiji | Fiji | deepImageJ (pre-trained DL models in ImageJ); SAMJ (Segment-Anything annotation for ImageJ/Fiji); TWS (W12) | — | Gómez-de-Mariscal et al. (2021) [doi:10.1038/s41592-021-01262-9](https://doi.org/10.1038/s41592-021-01262-9); García-López-de-Haro et al. (2026) [doi:10.1038/s41467-026-71752-x](https://doi.org/10.1038/s41467-026-71752-x) |
| Non-LLM AI elsewhere | napari / Python | Cellpose; micro-sam; BioImage Model Zoo | — | Stringer et al. (2021) [doi:10.1038/s41592-020-01018-x](https://doi.org/10.1038/s41592-020-01018-x); Archit et al. (2025) [doi:10.1038/s41592-024-02580-4](https://doi.org/10.1038/s41592-024-02580-4); Ouyang et al. (2022) [doi:10.1101/2022.06.07.495102](https://doi.org/10.1101/2022.06.07.495102) |
| Voice control | — | None found for Fiji, napari, QuPath, Icy or CellProfiler. The closest is PathVis, a mixed-reality pathology viewer using gaze, gesture and voice (arXiv 2505.02780). | — | **UNKNOWN** (absence within a search, not proof) |

An image.sc thread, "AI-assisted code-generation in Fiji's script editor" (`forum.image.sc/t/…/100877`), appeared in search results but returned HTTP 403. Its content is **UNKNOWN**.

## Diagrams and Inventories

### Comparison matrix

| Dimension | Fiji / IJ1 legacy UI | ImageJ2 Swing (SciJava) | napari | CellProfiler | QuPath | Icy |
|---|---|---|---|---|---|---|
| UI model | Floating AWT frames | SDI and MDI Swing UIs present; Fiji uses the legacy UI plus the search bar | Single Qt window, docks, command palette | Pipeline editor | Single window, project-based | Single window, Protocols editor |
| Image state | Implicit current `ImagePlus`; global ROI Manager and Results; one Undo | `Dataset`/display services; synchronised with IJ1 in Fiji | Explicit `ViewerModel` and layer list | Named images and objects per image set | Per-image object hierarchy in a project | Sequences with ROIs |
| Reproducibility | Recorder to macro (gaps: A05 UP-10) | Script parameters; modules | No native recorder (#2579 open); add-ons | Pipeline file is the record | Command history, *Create script*, *Run for project* | Protocol file |
| Plugin ecosystem | `plugins.config`, update sites | SciJava `@Plugin` | npe2 `napari.yaml` | Modules and plugins | Extensions | Plugin repository |
| Natural language | fiji-llm (official, unlisted), ImageJAI, fiji-mcp, Agentic-J | via fiji-llm (SciJava) | Omega | None found | None found in this search | None found |
| Large data | Virtual stacks; BigDataViewer (tiled multi-resolution HDF5) | — | Multiscale, lazy, async (experimental) | Batch-oriented | UNKNOWN (not checked) | UNKNOWN |
| Licence | Public domain (IJ1); GPL dominant (Fiji) | BSD-2 | BSD-3 | BSD-3 | GPL-3 | LGPL-3 (kernel, current) |

### Existing solutions to the problems in A05

| A05 problem | Existing solution | Where | Learn / reuse (PROPOSED) |
|---|---|---|---|
| UP-01 floating windows | Single-window docked layouts; ImageJ2 MDI UI | napari, QuPath, Icy; `SwingMdiUI` in the pinned jar | Reuse the docking pattern. Investigate why the MDI UI is not the default; check image.sc and git history before repeating it. |
| UP-02 implicit active image | Explicit layer selection; named layers addressable from code (`viewer.layers[...]`) | napari | Make the target image explicit in every command and in natural-language confirmations. |
| UP-03 single-level undo | None complete. napari undo/redo is also an open request (#2579). | — | This is an open problem in the field too. History plus snapshots must be designed (D-stage). |
| UP-05, UP-08 hidden or persistent settings | Pipeline modules show every setting inline | CellProfiler | Show the resolved options of every step in the history. |
| UP-09 global Results and ROI state | Per-image object hierarchy in a project | QuPath | Bind ROIs and results to an image or project entry, while still exporting the IJ1 Results table. |
| UP-10, UP-15 recording gaps, provenance | Command history to script; workflow export; pipeline files | QuPath, napari-assistant, CellProfiler | Adopt the "history panel, then *Create script*" interaction. The output must stay IJ1 macro. |
| UP-11 batch for non-programmers | *Run for project*; Test mode then batch; CreateBatchFiles | QuPath, CellProfiler | Provide "test on one, then run on folder" with an overwrite preview. |
| UP-12 discoverability | Command Finder [l]; Fiji search bar; napari command palette | Pinned Fiji; napari 0.6 | **Reuse** SciJava search (`scijava-search`) rather than build a new index. |
| Natural-language driving | Tool-calling agents over existing commands | fiji-llm, ImageJAI, fiji-mcp, Omega | Reuse or interoperate with the fiji-llm tool registry and MCP. Do **not** adopt Omega's autonomous code execution model; it conflicts with "user stays in control". |
| Teaching and documentation needs (A02 Finding 4) | RAG documentation chatbot | BioImage.IO Chatbot | Answer "how do I…" by citing imagej.net pages and menu paths. |

### What is and is not novel

| Idea | Novel? | Basis |
|---|---|---|
| Driving Fiji commands from natural language | **No** | fiji-llm, ImageJAI, fiji-mcp, Agentic-J |
| LLM agent controlling an image viewer | **No** | Omega |
| Single-window modern bioimage workstation | **No** | napari, QuPath, Icy; ImageJ2 MDI |
| Command history that becomes a script | **No** | ImageJ Recorder, QuPath, napari-assistant |
| Searchable command launcher | **No** | Command Finder, Fiji search bar, napari palette |
| Voice input driving Fiji commands | **No precedent found** (UNKNOWN) | Search in F7 |
| Natural-language or voice requests resolved to *existing* `run()` commands, shown as menu path plus macro line and confirmed by the user before running, inside a modernised shell that keeps IJ1 semantics | **Combination not found**; positioning only (PROPOSED) | F1–F7 |

## Alternatives and Trade-offs

| Option | For | Against |
|---|---|---|
| A. Build on fiji-llm (contribute the shell and voice layer; reuse its tool registry and MCP) | Official Fiji org; already SciJava-native; BSD-2; avoids duplicating the command-tool layer | Unlisted and evolving (34 open issues); its chat-centred UX may conflict with "not an AI prompt-box dashboard"; dependency on an external roadmap |
| B. Interoperate only (consume Fiji through MCP, as fiji-mcp and fiji-llm expose it) | Loose coupling | Two parallel tool definitions; possible duplication of effort |
| C. Independent implementation | Full control | Duplicates official work; weak justification under F04 §10.3 |

No recommendation is made here. This is decision DEC-proposed (§Decisions Required).

## Constraints and Limitations

- Nothing in this document was installed or executed. All third-party behaviour is documentation-level.
- QuPath large-image handling, Icy performance and napari's "no recorder" status were not checked beyond the cited sources.
- fiji-llm was inspected at its GitHub landing page only. The page shows no commit SHA or release, so the reviewed revision is UNKNOWN. A shallow clone into the research cache, with its SHA recorded, is needed before any reuse decision.

## Requirement implications

- **CR-A06-01:** The system shall not be described in any project document as the first natural-language or LLM interface to Fiji. Design documents shall cite fiji-llm, ImageJAI, fiji-mcp and Agentic-J as prior work. *Rationale:* F7.
- **CR-A06-02:** Natural-language and voice requests shall resolve to existing Fiji commands or macros, shown to the user with the target image and resolved options. The system shall require explicit user confirmation before any data-modifying execution. Generated free-form code shall not execute without review. *Rationale:* this distinguishes the project from Omega's autonomous model and serves A02 CR-A02-04.
- **CR-A06-03:** The command-discovery feature shall reuse the SciJava search infrastructure already in the pinned Fiji (`scijava-search-3.0.2`), or demonstrate why it cannot. *Rationale:* reuse over reimplementation (F04 §10.3).
- **CR-A06-04:** The system shall provide a step-history view whose contents can be exported as an ImageJ macro and re-run on a folder ("test on one, run on all"). *Rationale:* proven by QuPath and CellProfiler; addresses UP-10, UP-11 and UP-15.
- **CR-A06-05:** If the natural-language layer exposes Fiji operations as tools, it shall use a definition compatible with fiji-llm's SciJava tool registry or MCP interface, unless DEC-proposed decides otherwise. *Rationale:* avoids a parallel, incompatible tool ecosystem.

## Risks

- **R-A06-01:** The official fiji-llm project makes this project's natural-language layer redundant, or the two become incompatible. *Likelihood:* High. *Impact:* High. *Mitigation:* decide on Option A/B/C early; contact the fiji-llm maintainers through image.sc or GitHub before the D-stage.
- **R-A06-02:** Reviewers or users perceive the product as an "AI wrapper" without a differentiator. *Likelihood:* Medium. *Impact:* Medium. *Mitigation:* lead with fidelity (unchanged semantics, replayable `run()` history) and the workstation UX, not with AI.
- **R-A06-03:** Third-party tool facts change quickly; fiji-llm and napari are both under active development. *Likelihood:* High. *Impact:* Low–Medium. *Mitigation:* re-check F3 and F7 at each stage gate and record revisions in the upstream manifest.
- **R-A06-04:** LLM-generated macros may be wrong in ways that are hard to see. The benchmark (Haase et al. 2024) and bia-bob's own disclaimer both call for verification. *Likelihood:* High. *Impact:* High (scientific validity). *Mitigation:* restrict execution to catalogued commands with validated parameters (CR-A06-02); numerical parity tests (A30/D18).

## Decisions Required

- **DEC-proposed (A06-1):** Choose the relationship to `fiji/fiji-llm`: build on it, interoperate, or stay independent (§Alternatives). Owner: project lead. Needed before D09 interface design.

## References

- Schindelin J et al. (2012) [doi:10.1038/nmeth.2019](https://doi.org/10.1038/nmeth.2019); Rueden CT et al. (2017) [doi:10.1186/s12859-017-1934-z](https://doi.org/10.1186/s12859-017-1934-z); Pietzsch T et al. (2015) [doi:10.1038/nmeth.3392](https://doi.org/10.1038/nmeth.3392).
- napari contributors (2019) [doi:10.5281/zenodo.3555620](https://doi.org/10.5281/zenodo.3555620); [napari repo](https://github.com/napari/napari); [layers](https://napari.org/stable/getting_started/layers.html); [plugins](https://napari.org/stable/plugins/index.html); [manifest](https://napari.org/stable/plugins/technical_references/manifest.html); [rendering](https://napari.org/stable/guides/rendering.html); [issue #2579](https://github.com/napari/napari/issues/2579); [PR #5483](https://github.com/napari/napari/pull/5483); [napari-assistant](https://github.com/haesleinhuepf/napari-assistant); [napari-macrokit](https://github.com/hanjinliu/napari-macrokit).
- Carpenter AE et al. (2006) [doi:10.1186/gb-2006-7-10-r100](https://doi.org/10.1186/gb-2006-7-10-r100); Stirling DR et al. (2021) [doi:10.1186/s12859-021-04344-9](https://doi.org/10.1186/s12859-021-04344-9); CellProfiler manual 4.2.6 pages cited in F4.
- Bankhead P et al. (2017) [doi:10.1038/s41598-017-17204-5](https://doi.org/10.1038/s41598-017-17204-5); QuPath docs cited in F5.
- de Chaumont F et al. (2012) [doi:10.1038/nmeth.2075](https://doi.org/10.1038/nmeth.2075); Icy pages cited in F6.
- Royer LA (2024) [doi:10.1038/s41592-024-02310-w](https://doi.org/10.1038/s41592-024-02310-w); Lei W et al. (2024) [doi:10.1038/s41592-024-02370-y](https://doi.org/10.1038/s41592-024-02370-y); Haase R et al. (2024) [doi:10.1101/2024.04.19.590278](https://doi.org/10.1101/2024.04.19.590278); Johanns L et al. (2026) [arXiv:2606.02080](https://arxiv.org/abs/2606.02080); Gómez-de-Mariscal E et al. (2021) [doi:10.1038/s41592-021-01262-9](https://doi.org/10.1038/s41592-021-01262-9); García-López-de-Haro C et al. (2026) [doi:10.1038/s41467-026-71752-x](https://doi.org/10.1038/s41467-026-71752-x); Stringer C et al. (2021) [doi:10.1038/s41592-020-01018-x](https://doi.org/10.1038/s41592-020-01018-x); Archit A et al. (2025) [doi:10.1038/s41592-024-02580-4](https://doi.org/10.1038/s41592-024-02580-4); Ouyang W et al. (2022) [doi:10.1101/2022.06.07.495102](https://doi.org/10.1101/2022.06.07.495102).
- [fiji/fiji-llm](https://github.com/fiji/fiji-llm); [ImageJAI](https://github.com/Jay2owe/ImageJAI); [fiji-mcp](https://github.com/SteffenPL/fiji-mcp); [bia-bob](https://github.com/haesleinhuepf/bia-bob); [BioImage.IO Chatbot](https://github.com/bioimage-io/bioimageio-chatbot); [napari-chatgpt](https://github.com/royerlab/napari-chatgpt); PathVis [arXiv:2505.02780](https://arxiv.org/abs/2505.02780).
- imagej.net: [licensing](https://imagej.net/licensing/), [learn](https://imagej.net/learn/), [script parameters](https://imagej.net/scripting/parameters), [SciJava](https://imagej.net/libs/scijava), [BigDataViewer](https://imagej.net/plugins/bdv/); ImageJ User Guide [146-8](https://imagej.net/ij/docs/guide/146-8.html), [146-31](https://imagej.net/ij/docs/guide/146-31.html).
- Context article: Lampert M (2025), "From Click to Code: Reproducibility in Bio-image Analysis", [FocalPlane](https://focalplane.biologists.com/?p=26825).
- All web sources were accessed on 2026-10-17.

## Change log

- 2026-10-17: Initial draft (agent G6).
