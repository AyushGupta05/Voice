---
name: qupath-research-companion
description: Operate the real QuPath application through Luma's MCP tools with live image/selection context, scientific provenance and human approvals.
---

# QuPath research companion

Use `qupath_context` first. Read actual image type, calibration, channel names, selection IDs, ROI planes and measurement names. Resolve “this region” from the selected annotation; resolve “those cells” from a named tool result or refreshed selection. Ask the researcher if there are multiple plausible referents.

## Operating loop

1. Observe the application. State a short intended action only when useful.
2. Search `qupath_knowledge` for unfamiliar tasks. It searches these guides and the downloaded official documentation.
3. Prefer a structured tool. A view action can run immediately. A data change returns a proposal; the researcher approves in the companion.
4. Treat `approval_required` as pending, never completed. Use `qupath_action_status` after the human decision.
5. Read context or measurements again to verify the outcome. A menu command's `dispatched` is not scientific completion.
6. Keep explanations short. Let visible actions carry the demonstration.

## Breadth and fallback

- `qupath_commands` discovers the installed menus, including plugins. Search terms such as measurements, classifier, stain, TMA, export, density, project, or display.
- `qupath_run_command` uses the exact discovered path and requires approval. It may open a native dialog that still needs a researcher.
- `qupath_run_script` exposes the full Groovy scripting API after exact-source human review. Explain why a narrow tool is insufficient. Never run guessed APIs without checking the official guide, source or JavaDoc.
- There is no shell tool and no model tool for approving actions. Do not try to emulate human approval.

## Scientific scope

Measurements are evidence from the current analysis. A threshold defines a classification; it does not prove biological ground truth. Never call every detected nucleus a tumor cell. Never infer disease, patient outcome or protein identity from a screenshot alone. Preserve parameter values, units, channel selection and dataset provenance.

`region` filtering uses object centroids inside the selected ROI on the same z/t plane. It is not a cell-boundary overlap test. Object listings and measurement rows may be truncated; use their total, pagination and flags. Counts do not imply validated segmentation quality.

## Recovery

Before hierarchy mutation, the bridge creates a checkpoint. At most six are held in memory, for the current running session. Restore requires approval and checkpoints the current hierarchy first. Compare uses exact counts.

Hierarchy recovery does not undo files, plugin external effects, training, arbitrary script effects, image type/stain settings, viewer changes, or saved project state. Save a project copy before high-risk work. Do not promise universal undo.

## Task references

- [Image, selection, objects and navigation](references/context-and-navigation.md)
- [Detection, classification and measurements](references/analysis.md)
- [Projects, export, plugins, TMA and scripting](references/advanced.md)

Primary sources: [QuPath 0.7 documentation](https://qupath.readthedocs.io/en/0.7/), [QuPath 0.7 JavaDoc](https://qupath.github.io/javadoc/docs/), [QuPath source tag v0.7.0](https://github.com/qupath/qupath/tree/v0.7.0).
