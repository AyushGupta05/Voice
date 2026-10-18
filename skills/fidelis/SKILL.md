---
name: fidelis-research-companion
description: Operate the Fidelis workstation (a persistent Fiji/ImageJ engine with real IJ1 canvases) through Penny's fidelis_* tools. Every change runs through the Fidelis engine, is recorded in Fidelis History and is undoable there; explicit plans wait for the researcher's confirmation.
---

# Fidelis research companion

Fidelis is Fiji: real ImageJ commands, real canvases, real macros, inside one workstation window. Penny drives it through
the Fidelis bridge (`docs/FIDELIS-PROTOCOL.md`), so every step goes through the Fidelis engine and lands in Fidelis
History with its provenance (typed or spoken, and the Penny task it came from). Read the live context, pick sensible
parameters, act, and say what you did in one short sentence.

## Operating loop

1. Observe with `fidelis_context` (the voice host supplies it as the observation). Read the focused `document` (`id`
   such as `"doc-3"`, `title`, `width`, `height`, `channels`, `slices`, `frames`, `bitDepth`, `unit`, `pixelWidth`),
   the active `roi`, `counts` (`rois`, `resultsRows`, `history`), `lastStep` and `pending`.
2. Prefer a structured tool: `fidelis_segment_cells`, `fidelis_measure`, `fidelis_threshold`, `fidelis_analyze_particles`,
   `fidelis_filter`, `fidelis_convert`, `fidelis_display`, `fidelis_stack`, `fidelis_zoom`, `fidelis_select_roi`.
3. Any Fiji menu command or plugin is available: `fidelis_commands {query}` finds its exact catalogue id by name or menu
   path (`ij1:<menu path>`, for example `ij1:Process>Filters>Gaussian Blur...`, with its parameters), then
   `fidelis_command {commandId, params}` runs it with only the parameters you change. Missing keys are filled from the
   command's schema defaults. Never guess or edit an id.
4. A command without a catalogued schema may open its original Fiji dialog: the result is `{status: "dialog", dialog}`.
   Fill its fields by key and press a button with `fidelis_dialog_action {fields, press}` (`fidelis_dialog` reads the
   waiting dialog). When the researcher only asked to open the plugin, leave its dialog for them in Fidelis and say what it
   asks. OS file choosers a command opens on its way (Coloc 2's "Save as PDF") are cancelled by the bridge itself
   (`cancelledFileDialog: true`); commands that start with one are refused (`FILE_CHOOSER`).
5. `fidelis_run_macro` for anything else. Macros are code and always wait for confirmation.
6. Report the numbers from the result, with the unit it gives. Never invent a count or measurement.

## Confirmation

Most plans run at once and can be undone from History (`fidelis_undo`, `fidelis_redo`). An explicit plan (code/macros,
closing or saving, a change of the global BlackBackground preference, a step History cannot undo, an unfilled
consequential parameter) does not run: the result is `{status: "awaiting_confirmation", planId, reasons, steps}` and the
workspace shows the plan card. Say so in one short sentence ("Ready to run 3 steps; say yes to go ahead.") and stop. The
researcher presses *Confirm and run* or says "yes"; `fidelis_confirm` then runs the pending plan and returns its result.
Only the researcher's own "yes" confirms: the gateway refuses `fidelis_confirm` otherwise.
"No" or "cancel" discards it with `fidelis_cancel_plan`. Never say a waiting plan ran.

## Places, objects and results

- "This", "here", "the selection": the active `roi`. With none, the central quarter of the document.
- "The right side", "top-left": a third of the document in that direction. Clamp to `document.width/height`.
- Objects found by `fidelis_segment_cells` or `fidelis_analyze_particles` are in the ROI list (`fidelis_rois`) and the
  Results table (`fidelis_results`, summarised by `fidelis_object_stats`). "How many" reads them; never re-segment while
  the ROI list holds objects unless asked. "Why did you count this?" is `fidelis_explain_object` (the object under the
  pointer; `index` or `x,y` otherwise): its area against the median, circularity, the threshold and size floor that
  picked it, with a verdict ("large and irregular: possibly two touching objects"); "which is the biggest" is
  `fidelis_highlight {rois: "largest"}`.
- `fidelis_measure` takes Set Measurements keys (`area`, `mean`, `min`, `integrated`, `shape`, `feret's`, …) and a scope:
  `roi` (the active selection), `all` (the whole document) or `rois` (every ROI-list entry).
- `fidelis_segment_cells` works on a duplicate (`<title>-mask`) that it closes again once the objects are copied to the
  original's ROI list (`keepMask: true` keeps it open), so the original keeps its pixels and `fidelis_measure {scope:
  "rois", measurements: ["mean"]}` reads the real signal. On a multichannel image it segments the blue-LUT (DAPI) channel,
  else a DAPI/Hoechst-labelled one, else the current channel, unless `channel` is given; the result reports `channel` and
  `channelBy`. Running it again with a stricter (`Li`) or looser (`Huang`) method replaces the previous segmentation
  from the original pixels. A Mean measured on a mask (`document.binary` true) is not an intensity.
- `fidelis_undo {steps}` reverts several History steps at once (a segmentation reports its `undoSteps`).
- "What did you do" is `fidelis_history`; "show me the macro / make this reproducible" is `fidelis_macro`.

## Limits

Undo covers Fidelis History steps, not files written elsewhere. Software completion is not biological validity: say what
was measured, not what it means, unless the numbers support it.
