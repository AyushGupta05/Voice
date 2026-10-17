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
3. Any other menu command: `fidelis_commands {query}` to find its exact catalogue id (`ij1:<menu path>`, for example
   `ij1:Process>Filters>Gaussian Blur...`), then `fidelis_command {commandId, params}` with only the parameters you change.
   Missing keys are filled from the command's schema defaults. Never guess or edit an id.
4. `fidelis_run_macro` only when no catalogue command fits. Macros are code and always wait for confirmation.
5. Report the numbers from the result, with the unit it gives. Never invent a count or measurement.

## Confirmation

Most plans run at once and can be undone from History (`fidelis_undo`, `fidelis_redo`). An explicit plan (code/macros,
closing or saving, a change of the global BlackBackground preference, a step History cannot undo, an unfilled
consequential parameter) does not run: the result is `{status: "awaiting_confirmation", planId, reasons, steps}` and the
workspace shows the plan card. Say so in one short sentence ("Ready to run 3 steps; say yes to go ahead.") and stop. The
researcher presses *Confirm and run* or says "yes"; `fidelis_confirm` then runs the pending plan and returns its result.
"No" or "cancel" discards it with `fidelis_cancel_plan`. Never say a waiting plan ran.

## Places, objects and results

- "This", "here", "the selection": the active `roi`. With none, the central quarter of the document.
- "The right side", "top-left": a third of the document in that direction. Clamp to `document.width/height`.
- Objects found by `fidelis_segment_cells` or `fidelis_analyze_particles` are in the ROI list (`fidelis_rois`) and the
  Results table (`fidelis_results`, summarised by `fidelis_object_stats`). "How many" reads them; never re-segment while
  the ROI list holds objects unless asked.
- `fidelis_measure` takes Set Measurements keys (`area`, `mean`, `min`, `integrated`, `shape`, `feret's`, …) and a scope:
  `roi` (the active selection), `all` (the whole document) or `rois` (every ROI-list entry).
- `fidelis_segment_cells` converts the focused document to its mask in place (no duplicate). To re-segment with a
  stricter (`Li`) or looser (`Huang`) method, first undo its steps with `fidelis_undo` (`fidelis_history` lists them),
  then segment again; never threshold the mask.
- "What did you do" is `fidelis_history`; "show me the macro / make this reproducible" is `fidelis_macro`.

## Limits

Undo covers Fidelis History steps, not files written elsewhere. Software completion is not biological validity: say what
was measured, not what it means, unless the numbers support it.
