# Plugins: finding, running and adding them

Fiji is ImageJ plus a curated set of plugins kept current by its updater. Three things are true at once: a plugin is a menu command (so `fiji_run_command` runs it), its dialog is macro-recordable (so an option string drives it), and plugins not installed come from update sites (a researcher action with a restart). The installed Fiji is 2.18.0 / ImageJ 1.54p with 93 plugin jars.

## Finding what is installed

- `fiji_plugins {query}` lists commands under `Plugins ▷` matching the query (name and menu path). `fiji_commands {query}` searches every ImageJ1 menu command and SciJava command, including `Analyze ▷`, `Image ▷ Adjust ▷` entries that plugins add (Auto Threshold lives under Image ▷ Adjust, 3D Objects Counter under Analyze, Coloc 2 under Analyze ▷ Colocalization).
- ImageJ's own finder: `Plugins ▷ Utilities ▷ Find Commands...` (`L`) and Fiji's search bar; in macros `List.setCommands; List.get("Analyze Particles...")` returns the implementing class (empty string when absent). `getList("image.titles")` for windows.
- `Help ▷ About Plugins ▷ …` and `Help ▷ Update...` show versions. The jar list in `Fiji.app/plugins` and `Fiji.app/jars` is the ground truth when a menu entry is in doubt.

## Running a plugin

1. `fiji_run_command {name: "<exact label>", options: "<recorded option string>"}`. Labels are the menu text without the shortcut (`"Trainable Weka Segmentation"`, `"Bio-Formats Importer"`, `"3D Objects Counter"`, `"TrackMate"`, `"Grid/Collection stitching"`, `"Analyze Skeleton (2D/3D)"`, `"Coloc 2"`, `"Auto Threshold"`, `"Auto Local Threshold"`).
2. If the result carries `dialog`, drive it with `fiji_ui` → `fiji_ui_action {nodeId, action: "set_text"|"select"|"toggle"|"fire"}`, or rerun with the complete options the dialog reveals. Interactive plugins (Weka, Labkit, TrackMate wizard, Cell Counter) are meant to stay open: say what to do in them and highlight the control; do not force them closed.
3. SciJava commands (ImageJ2 plugins, StarDist, CLIJ, DeepImageJ, Labkit) appear in `fiji_commands` with `source: scijava` and run by label like any other command; the recorder writes them as `run("Command From Macro", "command=[<class>], args=[...]")`, which works in the GUI session (not headless). From a script: `fiji_run_script {language: "groovy"}` with `#@ CommandService cs` and `cs.run(Class.forName("..."), true, "input", imp, ...)`.
4. Headless-safe plugins (anything GenericDialog-based) also work inside `fiji_run_macro`.

## Installed and commonly asked for (menu label → path)

| Label (for `fiji_run_command`) | Menu path | Use |
|---|---|---|
| `Auto Threshold` | Image ▷ Adjust | `options: "method=Otsu white"`; `method=[Try all]` tiles all methods |
| `Auto Local Threshold` | Image ▷ Adjust | `options: "method=Phansalkar radius=15 parameter_1=0 parameter_2=0 white"` |
| `Trainable Weka Segmentation`, `… 3D` | Plugins ▷ Segmentation | interactive pixel classifier |
| `Color Clustering` | Plugins ▷ Segmentation | unsupervised colour segmentation |
| `Open Current Image With Labkit` | Plugins ▷ Labkit | fast interactive labelling |
| `3D Objects Counter`, `3D OC Options` | Analyze | 3D particles: `options: "threshold=100 slice=1 min.=10 max.=1000000 objects statistics summary"` |
| `Analyze Skeleton (2D/3D)` | Analyze ▷ Skeleton | branch/junction statistics of a skeletonized binary |
| `Coloc 2`, `Colocalization Threshold`, `Colocalization Test` | Analyze ▷ Colocalization | colocalisation statistics (two channels) |
| `Cell Counter` | Plugins ▷ Analyze ▷ Cell Counter | manual click counting by type |
| `TrackMate`, `Manual tracking with TrackMate`, `Load a TrackMate file` | Plugins ▷ Tracking | spot detection and tracking over T |
| `Bio-Formats Importer`, `Bio-Formats Exporter`, `Bio-Formats Windowless Importer`, `Stack Slicer`, `Bio-Formats Macro Extensions` | Plugins ▷ Bio-Formats (also File ▷ Import ▷ Bio-Formats) | proprietary formats; see stacks-and-channels |
| `Grid/Collection stitching`, `Pairwise stitching` | Plugins ▷ Stitching | tile stitching |
| `bUnwarpJ`, `Register Virtual Stack Slices`, `Descriptor-based registration (2d/3d)` | Plugins ▷ Registration | elastic / rigid registration |
| `Linear Stack Alignment with SIFT`, `Extract SIFT Correspondences` | Plugins ▷ Registration / Feature Extraction | alignment |
| `Colour Deconvolution` | Image ▷ Color | H&E / H-DAB stain separation (`options: "vectors=[H DAB]"`) |
| `Enhance Local Contrast (CLAHE)` | Process | `options: "blocksize=127 histogram=256 maximum=3 mask=*None*"` |
| `Exact Euclidean Distance Transform (3D)` | Plugins ▷ Process | 3D EDT |
| `Skeletonize (2D/3D)` | Plugins ▷ Skeleton | 3D thinning |
| `Directionality`, `Anisotropic Diffusion 2D`, `Gray Morphology` | Analyze / Plugins ▷ Process / Process ▷ Morphology | orientation analysis, denoising, grayscale morphology |
| `3D Viewer` | Plugins ▷ 3D Viewer | volume rendering (needs Java3D/GPU; may fail headless or over remote desktop) |
| `Macro Recorder` = `Record...` | Plugins ▷ Macros | the recorder; `fiji_recorder` reads it |

## Not installed by default: update sites

`Help ▷ Update...` → `Manage Update Sites` → tick the site → Apply changes → restart Fiji. This is the researcher's action (restarting closes their images); Luma says exactly which site and offers the equivalent that is installed meanwhile.

| Wanted | Update site | Adds |
|---|---|---|
| MorphoLibJ (label images, marker-controlled watershed, Analyze Regions, morphological filters) | **IJPB-plugins** | `Plugins ▷ MorphoLibJ ▷ …` |
| StarDist 2D nuclei | **CSBDeep** + **StarDist** | `Plugins ▷ StarDist ▷ StarDist 2D` |
| Cellpose / Omnipose bridge | **BIOP** (PTBIOP) | `Plugins ▷ BIOP ▷ Cellpose/Omnipose ▷ …` (needs a local Python env) |
| CLIJ2 GPU filters | **clij** + **clij2** | `Plugins ▷ ImageJ on GPU (CLIJ2) ▷ …` |
| ilastik import/export | **ilastik** | `Plugins ▷ ilastik ▷ …` |
| DeepImageJ | **DeepImageJ** | `Plugins ▷ DeepImageJ ▷ …` |
| BigStitcher, BigDataViewer | **BigStitcher** (BDV ships with Fiji) | large multi-tile stitching |
| Bio-Formats daily builds | **Bio-Formats** (daily) | newer format readers |
| 3D ImageJ Suite | **3D ImageJ Suite** | `Plugins ▷ 3D ▷ …` 3D segmentation/measurement |
| Neuroanatomy (SNT) | **Neuroanatomy** | neuron tracing |

Check with `fiji_plugins` before claiming either way; the wiki page for each (`_pages/plugins/<name>.md` in the local docs, searchable with `fiji_knowledge`) has the exact menu path and parameters.

## Writing a tiny plugin-equivalent instead

Many “is there a plugin for…” requests are a ten-line macro: a loop over ROI Manager entries, a per-slice measurement, a batch over a folder, a custom column in Results. Prefer the macro in [macros](macros.md); it runs now, is recorded in the workflow, and needs no restart. Use `fiji_run_script` (Groovy/Python/JavaScript via SciJava) when the macro language lacks the API (ImageJ Ops, imglib2 views, SciJava commands with typed inputs).

Sources: `plugins.config` entries of the installed jars (`Fiji.app/plugins/*.jar`, `Fiji.app/jars/TrackMate-8.1.6.jar`), read 17 Oct 2026; imagej.net `list-of-update-sites.md`, `update-sites/following.md`, `plugins/index.md` and the per-plugin pages (local copies under `.local/upstream/imagej-docs/imagej.github.io/_pages`).
