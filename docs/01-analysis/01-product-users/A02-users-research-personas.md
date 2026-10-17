---
id: A02
title: Users and Research Personas
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p, "Fiji pinned install (lock f6695397…)"]
related_requirements: []
related_decisions: [DEC-001]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A02 — Users and Research Personas

## Executive Summary

Fiji's users range from biologists with no programming background to developers who write plugins. The Fiji and ImageJ2 papers both state this explicitly (**VERIFIED**: Schindelin et al. 2012; Rueden et al. 2017). Two independent community surveys found the same thing. Open-source point-and-click tools, a category that includes Fiji, are the most used class of image-analysis tool. Respondents' top requests are documentation, step-by-step tutorials and user-friendly software (**VERIFIED**: Jamali et al. 2021, n=484; Sivagurunathan et al. 2023, n=493). About three quarters of the 2022 respondents use scripts or computational libraries at least sometimes (**VERIFIED**: Sivagurunathan et al. 2023). The GUI user and the scripter are therefore often the same person at different moments, not two separate populations.

This document defines six personas (PER-1…PER-6), each grounded in published evidence, and separates beginner workflows from expert ones. The design-relevant output is the **must-stay-familiar** matrix (§Diagrams and Inventories). It lists the menu paths, keyboard shortcuts, macro language, ROI Manager and Results-table habits that each persona depends on. Changing any of them invalidates tutorials, forum answers and published methods, and the macro language has deliberately stayed stable for that reason (**VERIFIED**: Schneider et al. 2012).

No demographic statistics are given. None of the sources supports population-level percentages for Fiji users specifically.

## Purpose and Scope

- **In scope:** who uses Fiji, what they do, what they already know, and what must not change for them.
- **Out of scope:**
  - Workflow step detail (A03).
  - Problem inventory (A05).
  - Competitor users (A06).
- **Not a user study.** The personas are synthesised from literature, documentation and community-forum structure. They are **INFERRED** archetypes, not measured segments. Validating them with real users is an open action (R-A02-01).

## Research Questions

1. Which user groups does the Fiji project itself claim to serve?
2. What do community surveys say about tool use, skills and needs?
3. Which interaction habits are load-bearing for each group?
4. How do beginner and expert workflows differ in the same tool?

## Sources and Evidence

| Source | What it supports | Evidence |
|---|---|---|
| Schindelin et al. (2012), Fiji, [doi:10.1038/nmeth.2019](https://doi.org/10.1038/nmeth.2019) | Range of target users; scripting by biologists; ImageJ "developed primarily by biologists for biologists" | **VERIFIED** (full text, PMC3855844) |
| Rueden et al. (2017), ImageJ2, [doi:10.1186/s12859-017-1934-z](https://doi.org/10.1186/s12859-017-1934-z) | Non-programmers through to professional developers; fluid roles; backwards compatibility with macros and plugins | **VERIFIED** (abstract and full text, PMC5708080) |
| Schneider, Rasband & Eliceiri (2012), [doi:10.1038/nmeth.2089](https://doi.org/10.1038/nmeth.2089) | Macro language usable without programming experience and stable for 15 years | **VERIFIED** (full text, PMC5554542) |
| Jamali et al. (2021), 2020 BioImage Analysis Survey, [doi:10.1017/S2633903X21000039](https://doi.org/10.1017/S2633903X21000039) | n=484; point-and-click open-source tools are the most reported category; requests for documentation and tutorials | **VERIFIED**. The paper notes that recruitment via image.sc and open-source channels likely over-represents those communities. |
| Sivagurunathan et al. (2023), [doi:10.1111/jmi.13229](https://doi.org/10.1111/jmi.13229) | n=493 (2022 survey); point-and-click most used; about ¾ use scripts at least sometimes; needs: documentation, tutorials, intuitive software, segmentation | **VERIFIED** (PMC10950841) |
| Rueden et al. (2019), image.sc forum, [doi:10.1371/journal.pbio.3000340](https://doi.org/10.1371/journal.pbio.3000340) | The forum is the main support channel; earlier fragmentation left "many scientists not knowing where to begin" | **VERIFIED** |
| Martins et al. (2021), NEUBIAS training schools, [doi:10.12688/f1000research.25485.1](https://doi.org/10.12688/f1000research.25485.1) | Definition of the Bioimage Analyst role; facility staff design and script reproducible workflows | **VERIFIED** |
| Cimini et al. (2020), NEUBIAS Gateway, [doi:10.12688/f1000research.24759.1](https://doi.org/10.12688/f1000research.24759.1) | Analysts bridge tool developers and biologists | **VERIFIED** |
| Levet et al. (2021), [doi:10.12688/f1000research.52531.1](https://doi.org/10.12688/f1000research.52531.1) | Tool use "usually requires expertise", which makes accessibility a bottleneck | **VERIFIED** |
| ImageJ docs: [shortcuts.html](https://imagej.net/ij/docs/shortcuts.html), [Guide 146-4](https://imagej.net/ij/docs/guide/146-4.html) (accessed 2026-10-17) | Keyboard-shortcut conventions | **VERIFIED as documentation** |
| `Fiji/jars/ij-1.54p.jar!/IJ_Props.txt`, `imagej/ImageJ@v1.54p:ij/Menus.java#L110-L238` | Exact menu paths and shortcuts that users rely on | **VERIFIED** (source inspection) |

The Jamali and Sivagurunathan surveys are not Fiji-specific. They report on the bioimage-analysis community and were recruited partly through image.sc. They support statements about *needs* and *skill mix*, not about Fiji's market share.

## Verified Findings

1. **The intended audience spans the full skill range.** Quoting Schindelin et al. (2012): "The Fiji project aims to provide useful functionality for a broad range of researchers, starting from programming agnostic biologists, through bioinformaticians and software engineers to professional computer vision researchers". Rueden et al. (2017): "ImageJ enjoys contributions from non-programmers, amateur programmers, and professional developers alike." **VERIFIED**.
2. **Roles are fluid.** Rueden et al. (2017): "these roles are not rigid; many users write scripts and macros to facilitate their image analysis". Sivagurunathan et al. (2023): "approximately ¾ of participants reporting using computational libraries and scripts for analyzing their images at least sometimes". **VERIFIED**.
3. **Point-and-click is the dominant mode.** Jamali et al. (2021) report that "the vast majority of respondents reported the category of 'open-source point and click software'", a category whose description named ImageJ and Fiji. Sivagurunathan et al. (2023): "open-source point-and-click software are the most used image analysis tools". **VERIFIED**.
4. **The stated needs are documentation, tutorials and intuitive software, not new algorithms.** Jamali et al. (2021) report requests for "general improvement of tool documentation and easy-to-follow tutorials", and that "written step-by-step and video tutorials are preferred methods of learning". Sivagurunathan et al. (2023) list "documentation, detailed tutorials on the usage of image analysis tools, user-friendly intuitive software, and better solutions for segmentation". **VERIFIED**.
5. **Stability of the macro language is a deliberate value.** Schneider et al. (2012): "ImageJ's macro language has stayed very stable over the last 15 years" and "Rasband wanted a stable program interface that wouldn't change on users". Rueden et al. (2017) rejected direct changes to ImageJ 1.x because they "would break backwards compatibility with existing macros and plugins, ruining established scientific workflows". **VERIFIED**.
6. **A professional intermediary role exists.** Martins et al. (2021) define the Bioimage Analyst's "minimal common denominator" as "the ability to code proficiently in at least one of major programming languages used in bioimage analysis (for example, ImageJ macro, R, Java, Python...)". They describe facility staff "whose daily tasks involve supporting researchers in BIA and who require solid skills at designing and scripting effective and reproducible workflows". **VERIFIED**.
7. **Peer support happens on image.sc.** Rueden et al. (2019): "Forums and email lists play a major role in assisting scientists in using software." **VERIFIED**. **INFERRED** design consequence: forum answers phrased as menu paths and macro snippets are a de facto user manual, so those paths must remain valid.

## Technical Details

### Personas

Each persona is an **INFERRED** archetype built from the findings above. "Evidence" names the findings it rests on.

#### PER-1 — Wet-lab biologist (occasional, GUI-only)

| Field | Content |
|---|---|
| Goal | Get a defensible number or figure from their own microscopy images for a paper or lab meeting |
| Typical work | W01 (open, B&C, measure), W04 (figure with scale bar), W07 (manual counting), W02 by following a tutorial step by step (A03) |
| Knowledge | Menu paths learned from a tutorial, a colleague or a forum answer; a few shortcuts (Ctrl+Shift+C for B&C, Ctrl+M Measure); little awareness of global options |
| Failure modes | Measures display-adjusted rather than raw data after B&C Apply on 8-bit images; inverted masks due to BlackBackground; loses track of which window is active (A05 UP-01, UP-04, UP-07) |
| What helps | Guidance in context, undo, visible state, plain-language command entry (PROPOSED) |
| Evidence | Findings 1, 3, 4; Schindelin et al. (2012) "programming agnostic biologists"; Levet et al. (2021) |

#### PER-2 — Microscopy researcher (regular quantitative user)

| Field | Content |
|---|---|
| Goal | Repeated quantification across experiments: intensities per ROI over channels and time, particle counts, colocalisation |
| Typical work | W01–W06, W08, with occasional W10 (records a macro and edits titles) and W09 via Process › Batch › Macro |
| Knowledge | Bio-Formats importer options; hyperstack navigation; ROI Manager (Add [t], Measure, Multi Measure, save .zip); Set Measurements; Results table export; the Recorder |
| Failure modes | Stale ROI Manager or Results rows from the previous image; recorded macro tied to window titles; hidden parameter persistence between dialog runs (A05 UP-08, UP-09, UP-10) |
| What helps | Explicit per-image state, reproducible step history that can be exported as a macro, safe batch application |
| Evidence | Findings 2, 3; Rueden et al. (2017) on scripting users |

#### PER-3 — Image analyst / core-facility staff (Bioimage Analyst)

| Field | Content |
|---|---|
| Goal | Build, validate and hand over workflows for many research groups; teach; ensure reproducibility |
| Typical work | Every A03 workflow, especially W09, W10, W11 (TrackMate) and W12 (TWS); writes macros or Jython/Groovy; supports PER-1 and PER-2 on image.sc and in person |
| Knowledge | Macro language, script parameters, the batch-processing templates, plugin internals, Fiji update sites |
| Failure modes | Workflows that work on their machine but fail for users because of different global options or plugin versions; time spent explaining hidden state (A05 UP-04, UP-05, UP-15) |
| What helps | Workflows exportable with all settings; a way to give users a guided version of a pipeline; provenance records |
| Evidence | Finding 6; Martins et al. (2021); Cimini et al. (2020) |

#### PER-4 — Technical Fiji user / macro writer (power user)

| Field | Content |
|---|---|
| Goal | Speed and control. Automate everything and keep decades-old macros working. |
| Typical work | Keyboard-driven GUI use (bare-key shortcuts with "Require control key" unchecked), Command Finder [l], installed macro sets (StartupMacros, `Plugins › Macros › Install…`), headless `-macro`/`-batch` runs |
| Knowledge | `run("Command", "options")` strings, `setBatchMode`, `getResult`, `roiManager(...)`, IJ1 Java API |
| Failure modes | Any change to command names, option keys or Results column names breaks their code. They regard a new UI layer as an obstacle if it hides the menus. |
| What helps | Unchanged command names and macro semantics, all shortcuts preserved, the macro editor and Recorder still available, and a natural-language feature that can be ignored |
| Evidence | Finding 5; Schneider et al. (2012) |

#### PER-5 — Student / trainee

| Field | Content |
|---|---|
| Goal | Learn image analysis concepts and Fiji at the same time, usually within a course or training school |
| Typical work | Guided tutorials (W01, W02, W04), sample images (File › Open Samples), the step-by-step workflows taught at training schools |
| Knowledge | Little; relies on tutorials, which the surveys identify as the preferred learning mode (Finding 4) |
| Failure modes | Cannot map concepts to menu locations; cannot discover commands among about 1,100 menu declarations (A05 UP-12); copies forum macros without understanding the state they assume |
| What helps | Explanation of what each step does to the data, and discoverable commands. A natural-language request that shows the equivalent menu path and macro line teaches as well as executes (PROPOSED). |
| Evidence | Findings 3, 4; Martins et al. (2021) on training schools |

#### PER-6 — Plugin developer

| Field | Content |
|---|---|
| Goal | Ship an algorithm to biologists through Fiji's menus and update sites |
| Typical work | IJ1 `PlugIn`/`PlugInFilter` or SciJava `@Plugin(type = Command.class)` with `menuPath`; `plugins.config`; GenericDialog or script parameters |
| Knowledge | ImageJ1/ImageJ2 APIs, the Maven/SciJava ecosystem, the Updater |
| Failure modes | A new shell that does not host AWT `Frame`/`GenericDialog` or SciJava UIs breaks their plugin for users |
| What helps | Guaranteed plugin compatibility; a documented way to expose commands to natural-language and voice front ends without code changes |
| Evidence | Finding 1; Schindelin et al. (2012) "platform for productive collaboration between computer science and biology"; Rueden et al. (2017) on backwards compatibility |

**Secondary stakeholders (not personas):** reviewers, readers and PIs. They consume methods sections and need reproducible descriptions of processing. Image-processing reporting in the literature is often incomplete (Aaron & Chew 2021; Schmied et al. 2024). See A05.

### Beginner versus expert workflows

| Dimension | Beginner (PER-1, PER-5) | Intermediate (PER-2) | Expert (PER-3, PER-4, PER-6) |
|---|---|---|---|
| Entry point | Menus, toolbar, tutorials | Menus plus shortcuts plus the ROI Manager | Shortcuts, Command Finder, macros, scripts, headless |
| Unit of work | One image, one measurement | One experiment (tens of images, hyperstacks) | A pipeline applied to datasets; reusable tools |
| State awareness | Low: unaware of global options | Partial: knows the ROI Manager and Results but trips on stale state | High: sets options explicitly in code (`setOption("BlackBackground", true)`, `run("Set Measurements...", …)`) |
| Reproducibility mechanism | None, or screenshots and notes | Recorder plus manual editing | Version-controlled macros and scripts |
| Batch | Rarely; repeats by hand | Process › Batch › Macro… | `getFileList` loops, headless runs, cluster |
| Where they get help | Tutorials, colleagues, image.sc | image.sc, docs | Source code, developer docs, mailing lists |
| Risk from a UI change | Retraining; broken tutorials | Broken habits and recorded macros | Broken code and plugins; loss of speed |

## Diagrams and Inventories

### Must-stay-familiar matrix

Legend: **M** = must stay unchanged; **R** = must remain reachable (it may be relocated, provided the original path still works); **–** = low dependence. Each row is **PROPOSED**, derived from the persona evidence above. The rows' existence in the pinned runtime is **VERIFIED** where a source is given.

| Behaviour / artefact | Pinned evidence | PER-1 | PER-2 | PER-3 | PER-4 | PER-5 | PER-6 |
|---|---|---|---|---|---|---|---|
| Top-level menu structure File/Edit/Image/Process/Analyze/Plugins/Window/Help and submenu paths (e.g. Process › Binary › Convert to Mask) | `Menus.java#L110-L238`, IJ_Props | M | M | M | M | M | M |
| Exact command names used by `run()` (e.g. `"Analyze Particles..."`, `"Z Project..."`) | IJ_Props, `Menus.java` | R | M | M | M | R | M |
| Keyboard shortcuts: m Measure, t Add to Manager, k Plot Profile, z Undo, Shift+C B&C, Shift+T Threshold, l Find Commands, Shift+D Duplicate, h Histogram, Shift+E Restore Selection, Shift+F Flatten, Shift+Z Channels Tool | [shortcuts.html](https://imagej.net/ij/docs/shortcuts.html); IJ_Props `[x]` suffixes | R | M | M | M | R | – |
| Bare-key shortcuts (no Ctrl) when "Require control key" is off | [shortcuts.html](https://imagej.net/ij/docs/shortcuts.html), [Guide 146-4](https://imagej.net/ij/docs/guide/146-4.html) | – | R | M | M | – | – |
| Toolbar tools and their modifiers: Shift-add, Alt-subtract, double-click for options, Shift constrains a line | [Tools](https://imagej.net/ij/docs/tools.html), [Guide 146-19](https://imagej.net/ij/docs/guide/146-19.html) | M | M | M | M | M | – |
| Macro language syntax and built-ins; option-string keys | [macros.html](https://imagej.net/ij/developer/macro/macros.html) | – | M | M | M | R | M |
| Recorder (Plugins › Macros › Record…) producing `run()` lines | IJ_Props `macros06` | R | M | M | M | R | M |
| ROI Manager: Add/Update/Delete/Measure/Multi Measure, Show All, save/open .zip, ROIs measured when none selected | `RoiManager.java`, [Guide 146-30](https://imagej.net/ij/docs/guide/146-30.html) | R | M | M | M | R | R |
| Results table: one row per Measure, column names (Area, Mean, IntDen, RawIntDen, …), Save As .csv, `getResult()` | `Analyzer.java`, [Analyze menu](https://imagej.net/ij/docs/menus/analyze.html) | M | M | M | M | M | M |
| Set Measurements semantics (incl. Limit to threshold, Redirect to, Decimal places) | `Analyzer.java#L40-L47` | R | M | M | M | R | R |
| Dialog field labels (they define macro keys) | e.g. `ParticleAnalyzer.java#L342-L360` | R | M | M | M | R | M |
| Hyperstack C/Z/T sliders, Channels Tool, composite modes | IJ_Props `color05`, `hyperstacks*` | M | M | M | R | M | – |
| B&C display-versus-Apply semantics (display range does not alter data unless applied) | [Image menu](https://imagej.net/ij/docs/menus/image.html) | M | M | M | M | M | – |
| Command Finder [l] and the Fiji search bar | IJ_Props `utilities02`; `scijava-search-3.0.2.jar`; [keyboard shortcuts](https://imagej.net/learn/keyboard-shortcuts) | R | R | R | M | R | – |
| Plugin menu placement via `plugins.config` / SciJava `menuPath` | plugin jars (A03) | R | R | R | R | R | M |
| Plugin-owned windows (TrackMate wizard, TWS, Cell Counter) behave as they do now | A03 W07, W11, W12 | R | M | M | M | R | M |
| Headless `-macro` / `-batch` execution | [macros.html#cli](https://imagej.net/ij/developer/macro/macros.html#cli) | – | – | M | M | – | R |

**Reading of the matrix (INFERRED).** Behaviours that are M for PER-4 and PER-6 are code contracts: command names, option keys, Results columns, macro semantics, plugin hosting. They cannot change at all. Behaviours that are M only for PER-1, PER-2 and PER-5 are habits. They can be visually modernised so long as the same paths, shortcuts and meanings work.

## Constraints and Limitations

- The personas are not validated with real users (R-A02-01).
- Both surveys recruited partly through image.sc and open-source channels. Jamali et al. state this "presumably increas[ed] their final representation". The finding that point-and-click tools dominate may be biased towards open-source users.
- "About 1,100 menu declarations" is a static count over the pinned install, not a runtime count. The method is described in A05 UP-12.

## Requirement implications

- **CR-A02-01:** The system shall keep every existing ImageJ/Fiji menu path and command name reachable and executable unchanged, including those contributed by plugins. *Rationale:* every persona depends on menu paths; tutorials and forum answers reference them (Findings 4, 7).
- **CR-A02-02:** The system shall preserve all default ImageJ keyboard shortcuts, user-added shortcuts, and the "Require control key" behaviour. *Rationale:* PER-2, PER-3 and PER-4 depend on them.
- **CR-A02-03:** The system shall execute existing ImageJ macros and recorded `run()` strings with unchanged semantics and output, including Results-table column names. *Rationale:* Finding 5; PER-3, PER-4 and PER-6 code contracts.
- **CR-A02-04:** The system shall present, for every natural-language or voice-initiated action, the equivalent menu path and macro line before or upon execution. *Rationale:* this teaches PER-1 and PER-5 and lets PER-3 and PER-4 audit the action (Findings 2, 4).
- **CR-A02-05:** The system shall let users ignore natural-language and voice features completely and work only through menus, shortcuts and macros with no loss of function. *Rationale:* PER-4 failure mode; the product vision keeps the user in control.
- **CR-A02-06:** The system shall host existing plugin UIs (AWT/Swing frames, GenericDialog, SciJava widgets) without requiring plugin changes. *Rationale:* PER-6; Rueden et al. (2017) on backwards compatibility.
- **CR-A02-07:** The system shall provide in-context explanation of what a command does to pixel data versus display (at least for B&C Apply, type conversion, binary operations). *Rationale:* PER-1 and PER-5 failure modes; survey needs for documentation (Finding 4).

## Risks

- **R-A02-01:** The personas do not match the actual user mix of the target deployment. *Likelihood:* Medium. *Impact:* Medium. *Mitigation:* a short structured interview with 3–5 users per persona before D-stage UI commitments; check against image.sc question categories.
- **R-A02-02:** Modernisation alienates PER-4 (power users), who are disproportionately the people writing forum answers and training material. *Likelihood:* Medium. *Impact:* High (community adoption). *Mitigation:* CR-A02-01/02/05; ship a "classic" layout; keep the macro tooling unchanged.
- **R-A02-03:** Survey-derived needs reflect respondents recruited through open-source channels, not wet-lab users who never post. *Likelihood:* Medium. *Impact:* Low–Medium. *Mitigation:* treat the PER-1 needs as hypotheses and test them with usability sessions (D-stage).

## References

- Schindelin J et al. (2012). Fiji: an open-source platform for biological-image analysis. Nat Methods 9:676–682. [doi:10.1038/nmeth.2019](https://doi.org/10.1038/nmeth.2019)
- Rueden CT et al. (2017). ImageJ2: ImageJ for the next generation of scientific image data. BMC Bioinformatics 18:529. [doi:10.1186/s12859-017-1934-z](https://doi.org/10.1186/s12859-017-1934-z)
- Schneider CA, Rasband WS, Eliceiri KW (2012). NIH Image to ImageJ: 25 years of image analysis. Nat Methods 9:671–675. [doi:10.1038/nmeth.2089](https://doi.org/10.1038/nmeth.2089)
- Jamali N et al. (2021). 2020 BioImage Analysis Survey: Community experiences and needs for the future. Biological Imaging 1:e4. [doi:10.1017/S2633903X21000039](https://doi.org/10.1017/S2633903X21000039)
- Sivagurunathan S et al. (2023). Bridging imaging users to imaging analysis – A community survey. J Microsc 296:199–213. [doi:10.1111/jmi.13229](https://doi.org/10.1111/jmi.13229)
- Rueden CT et al. (2019). Scientific Community Image Forum. PLoS Biol 17:e3000340. [doi:10.1371/journal.pbio.3000340](https://doi.org/10.1371/journal.pbio.3000340)
- Martins GG et al. (2021). Highlights from the 2016-2020 NEUBIAS training schools for Bioimage Analysts. F1000Research 10:334. [doi:10.12688/f1000research.25485.1](https://doi.org/10.12688/f1000research.25485.1)
- Cimini BA et al. (2020). The NEUBIAS Gateway. F1000Research 9:613. [doi:10.12688/f1000research.24759.1](https://doi.org/10.12688/f1000research.24759.1)
- Levet F et al. (2021). Developing open-source software for bioimage analysis: opportunities and challenges. F1000Research 10:302. [doi:10.12688/f1000research.52531.1](https://doi.org/10.12688/f1000research.52531.1)
- Aaron J, Chew TL (2021). A guide to accurate reporting in digital image processing. J Cell Sci 134:jcs254151. [doi:10.1242/jcs.254151](https://doi.org/10.1242/jcs.254151)
- Schmied C et al. (2024). Community-developed checklists for publishing images and image analyses. Nat Methods 21:170–181. [doi:10.1038/s41592-023-01987-9](https://doi.org/10.1038/s41592-023-01987-9)
- ImageJ docs: [Keyboard shortcuts](https://imagej.net/ij/docs/shortcuts.html), [Tools](https://imagej.net/ij/docs/tools.html), [Macro language](https://imagej.net/ij/developer/macro/macros.html), User Guide [146-4](https://imagej.net/ij/docs/guide/146-4.html), [146-19](https://imagej.net/ij/docs/guide/146-19.html), [146-30](https://imagej.net/ij/docs/guide/146-30.html); imagej.net [Keyboard Shortcuts](https://imagej.net/learn/keyboard-shortcuts). All accessed 2026-10-17.
- Upstream: `imagej/ImageJ@v1.54p` (03382be1) `ij/Menus.java`, `ij/plugin/filter/Analyzer.java`, `ij/plugin/frame/RoiManager.java`, `ij/plugin/filter/ParticleAnalyzer.java`; `Fiji/jars/ij-1.54p.jar!/IJ_Props.txt`.

## Change log

- 2026-10-17: Initial draft (agent G6).
