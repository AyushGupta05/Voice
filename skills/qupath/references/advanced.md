# Projects, exports, plugins, TMA and scripting

## Application breadth

QuPath's real menu catalog is the discoverable surface for installed functionality. Search it for: project/image import; image type and stain estimation; annotations and ROI operations; cell detection; positive-cell detection; object and pixel classifiers; density maps; superpixels; tissue microarrays; measurement tables; image/data export; ImageJ; scripting; preferences; and installed extensions.

Discovery reports exact menu paths and enabled state. It does not report modal dialog fields, every contextual menu or every plugin method. Native dialogs may require manual work. Use the official downloaded docs for task context.

## Project and batch work

A project groups images, annotations, classifiers and workflows. Before processing a whole project, prove parameters on a representative image, save project copies, then use QuPath's documented project scripting methods. Batch operations are risky: state the scope and show the source before running. Never silently save changes to every project image.

## Export and interoperability

QuPath supports measurement-table export and GeoJSON object export through its menus and scripting API. Prefer an explicit output path selected by the researcher and avoid overwriting. Hierarchy checkpoint recovery cannot undo file writes. Source images may contain private metadata; exporting or sending them is a separate action.

## TMA, density maps and spatial workflows

TMA dearraying, grid edits, density maps and classifiers have specialized prerequisites. Search task-specific docs, discover the installed commands, and inspect the native result. Do not assume a generic cell detection tool covers every analysis.

## Workflows

QuPath records every scripted step (detection, classification, annotations) in the image's workflow history. `qupath_workflow_steps` lists it. “Save this procedure / save as a workflow so I can use it on other samples” → `qupath_save_workflow {name}` with a short kebab-case name derived from the conversation (e.g. `dab-positive-cells`); it writes a Groovy script under `.local/workflows` and returns the script and step count. Say where it was saved and how many steps it has (“Saved dab-positive-cells with 3 steps.”). Running it on another image is a `qupath_run_script` of that exact file after the researcher has seen the code; parameters tuned on one slide are not validated for another.

## Groovy escape hatch

The approved script runs in QuPath's own context with default QPEx imports. Common APIs include getCurrentImageData(), getCurrentHierarchy(), getSelectedObject(), getSelectedObjects(), getCurrentViewer() and getProject(). Use stable object IDs, explicit image data and exact measurement names.

Scripts can access files, network and the local machine. They are not sandboxed. Show the complete source and reason before running; each run is bound to exact code and context. Use short scripts, print structured summaries, and inspect the resulting application state.

Sources:
- https://qupath.readthedocs.io/en/0.7/docs/scripting/overview.html
- https://qupath.readthedocs.io/en/0.7/docs/tutorials/density_maps.html
- https://qupath.readthedocs.io/en/0.7/docs/tutorials/tma.html

Scripts execute in the background with captured project/image bindings. Prefer exact object IDs over a later read of mutable selection. Explicit JavaFX UI changes require `Platform.runLater`; keep analysis and plugins off the JavaFX thread. Close active modal dialogs before invoking main-window menu commands.

The prototype refuses Groovy execution unless both a project and image are open, preventing QuPath’s null-binding fallback from targeting later GUI defaults. Structured tools work on standalone images. Use the explicit `qupath_run_script` path for exact-source review; RichTextFX editor source/range changes also invalidate UI approvals.

Native editable source-window actions are refused because QuPath binds their worker context after dispatch. Submit exact source through `qupath_run_script` instead.
