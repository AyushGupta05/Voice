import type { Tool } from '../tools.js';
import { toolFactory } from '../tool-schema.js';

/** Fidelis runs as a plain JVM (`java @fidelis-app/build/run.args`) from the Zulu JDK bundled with Fiji, so macOS knows
 * it by the identifier embedded in that `java` launcher, not by a Fidelis bundle; the companion uses it to find the
 * windows holding the canvas. Shared with any other Zulu `java` GUI process and not yet confirmed on a live run;
 * override with LUMA_FIDELIS_BUNDLE_ID without a rebuild. */
export const FIDELIS_BUNDLE_ID = process.env.LUMA_FIDELIS_BUNDLE_ID?.trim() || 'com.azul.zulu.java';

const number = { type: 'number' };
const boolean = { type: 'boolean' };
const positive = { type: 'number', exclusiveMinimum: 0 };
const limit = { type: 'integer', minimum: 1, maximum: 500 };
const method = { type: 'string', minLength: 1, maxLength: 40 };
// Fidelis document ids are DocumentId strings ("doc-3"); imageId is ImageJ's, never used to address a document.
const documentId = { type: 'string', pattern: '^doc-[0-9]+$', maxLength: 40, description: 'Fidelis document id exactly as fidelis_context/fidelis_images report it, e.g. "doc-3".' };
// Catalogue ids are `ij1:<menu path>` (docs/data/commands.csv), e.g. "ij1:Process>Filters>Gaussian Blur...".
const commandId = { type: 'string', pattern: '^[a-z0-9]+:.+$', minLength: 5, maxLength: 300, description: 'Exact catalogue id from fidelis_commands, e.g. "ij1:Process>Filters>Gaussian Blur...".' };
const params = { type: 'object', maxProperties: 40, additionalProperties: { anyOf: [{ type: 'string', maxLength: 4000 }, { type: 'number' }, { type: 'boolean' }] },
  description: 'Catalogue parameter keys (as the macro recorder writes them, e.g. {"sigma": 2}); only the ones you change, schema defaults fill the rest.' };
// Set Measurements keys, as ImageJ's macro recorder writes them.
const measurement = { type: 'string', enum: ['area', 'mean', 'standard', 'modal', 'min', 'centroid', 'center', 'perimeter', 'bounding', 'fit', 'shape', "feret's", 'integrated', 'median', 'skewness', 'kurtosis', 'area_fraction', 'stack'] };
const range = (description: string) => ({ type: 'string', pattern: '^[0-9]*\\.?[0-9]+-(?:[0-9]*\\.?[0-9]+|Infinity)$', maxLength: 40, description });
const tool = toolFactory('fidelis');

/** Fidelis workstation tools: one per operation in docs/FIDELIS-PROTOCOL.md (`fidelis_<operation>`), plus the local
 * image search, knowledge and action-status tools. Every change runs through the Fidelis engine, so it is recorded in
 * Fidelis History and undoable there. Session operations (`open_path`) take the data decision path. */
export const fidelisTools: Tool[] = [
  tool('context', 'Read Fidelis’s live state: the focused document (id, title, size, channels/slices/frames, bit depth, calibration, path, unsaved changes), open documents, the active ROI, counts (ROIs, Results rows, History steps), the last History step, the viewer and any plan waiting for confirmation (pending). Refresh before resolving “this” or any change.', 'context', 'read'),
  tool('images', 'List open Fidelis documents with ids, titles, sizes, which one is focused and whether each has unsaved changes.', 'images', 'read'),
  tool('open_path', 'Open an exact absolute local image path in Fidelis as a new document (recorded in History). Returns the document and a highlight.', 'open_path', 'data', { path: { type: 'string', minLength: 1, maxLength: 4096 } }, ['path']),
  tool('activate_image', 'Focus an open document by id ("doc-3") or title. Display only.', 'activate_image', 'view', { id: documentId, title: { type: 'string', minLength: 1, maxLength: 500 } }),
  tool('commands', 'Search the Fidelis command catalogue (every Fiji/ImageJ menu command) by name: returns commandId, label, menuPath and whether its parameter schema is complete. Use before fidelis_command and pass the exact commandId.', 'commands', 'read', { query: { type: 'string', minLength: 1, maxLength: 200 }, limit: { type: 'integer', minimum: 1, maximum: 100 } }, ['query']),
  tool('command', 'Run one catalogue command on the focused document by its exact commandId from fidelis_commands, with only the params you change (missing keys come from the schema defaults). One History step; returns the step.', 'command', 'data', { commandId, params }, ['commandId']),
  tool('segment_cells', 'Segment nuclei/cells on the focused document: 8-bit if needed, Gaussian blur, auto threshold (method, default Otsu), mask, fill holes, watershed, then Analyze Particles into the ROI list and Results. Returns count, averageSize, unit, the steps and a highlight. Leave tuning at defaults unless asked.', 'segment_cells', 'data', { channel: { type: 'integer', minimum: 1 }, method, minSize: { type: 'number', minimum: 0 }, watershed: boolean }),
  tool('measure', 'Measure with ImageJ: Set Measurements from keys (area, mean, min, integrated, shape, …), then measure the active ROI (scope roi), the whole document (all) or every ROI-list entry (rois). Returns rows, the last row, per-column min/max/mean and unit. Numbers come from here.', 'measure', 'data', { measurements: { type: 'array', items: measurement, maxItems: 18, uniqueItems: true }, scope: { type: 'string', enum: ['roi', 'all', 'rois'] } }, ['scope']),
  tool('threshold', 'Threshold the focused document: an auto method (Otsu, Default, Huang, Li, Triangle, Moments, MaxEntropy, Yen, …; dark true for bright objects on dark) or explicit lower/upper. Returns lower, upper and method.', 'threshold', 'data', { method, dark: boolean, lower: number, upper: number }),
  tool('analyze_particles', 'Count and outline objects in the thresholded document (Analyze Particles): size and circularity ranges ("50-Infinity", "0.50-1.00"; pixel true for pixel units), exclude edges, include holes, show, add to the ROI list. Returns count, averageSize, unit and a highlight.', 'analyze_particles', 'data', {
    size: range('Area range in calibrated units² (pixels with pixel true), e.g. "50-Infinity".'), pixel: boolean, circularity: range('Circularity range 0–1, e.g. "0.50-1.00".'),
    show: { type: 'string', enum: ['Nothing', 'Overlay', 'Overlay Masks', 'Outlines', 'Bare Outlines', 'Ellipses', 'Masks', 'Count Masks'] },
    display: boolean, exclude: boolean, clear: boolean, include: boolean, summarize: boolean, overlay: boolean, add: boolean, composite: boolean }),
  tool('filter', 'Apply an ImageJ filter to the focused document: gaussian/median/mean (sigma or radius), unsharp, subtract_background (rolling radius), enhance_contrast (saturated %), invert, smooth, sharpen, find_edges. One History step.', 'filter', 'data', { name: { type: 'string', enum: ['gaussian', 'median', 'mean', 'unsharp', 'subtract_background', 'enhance_contrast', 'invert', 'smooth', 'sharpen', 'find_edges'] }, sigma: positive, radius: positive, rolling: positive, saturated: { type: 'number', minimum: 0, maximum: 100 } }, ['name']),
  tool('convert', 'Convert the focused document to 8-bit, 16-bit, 32-bit or RGB.', 'convert', 'data', { to: { type: 'string', enum: ['8-bit', '16-bit', '32-bit', 'RGB'] } }, ['to']),
  tool('duplicate', 'Duplicate the focused document into a new document with an optional title. Returns the new document.', 'duplicate', 'data', { title: { type: 'string', minLength: 1, maxLength: 200 } }),
  tool('select_roi', 'Set the active selection in image pixels: rectangle or oval by x, y, width, height; polygon by points [[x,y],…]; none clears it. Infer the region from the document size, never ask for coordinates. Returns the ROI and a highlight.', 'select_roi', 'view', { type: { type: 'string', enum: ['rectangle', 'oval', 'polygon', 'none'] }, x: number, y: number, width: positive, height: positive, points: { type: 'array', items: { type: 'array', items: number, minItems: 2, maxItems: 2 }, minItems: 3, maxItems: 10000 } }, ['type']),
  tool('rois', 'List the focused document’s ROI-list entries (index, name, bounds). Bounded by limit; total is the full count.', 'rois', 'read', { limit }),
  tool('results', 'Read Results table rows (bounded by limit and offset) with their column names and the total row count.', 'results', 'read', { limit, offset: { type: 'integer', minimum: 0 } }),
  tool('object_stats', 'Summarise the measured objects in the Results table without re-segmenting: n and area mean/median/min/max with the unit. Use for “how many / how big”.', 'object_stats', 'read'),
  tool('display', 'Brightness/contrast of the focused document (display only): min/max of the display range, or auto from the image’s values.', 'display', 'view', { min: number, max: number, auto: boolean }),
  tool('stack', 'Move through a stack or hyperstack: slice, channel, frame (1-based). Display only.', 'stack', 'view', { slice: { type: 'integer', minimum: 1 }, channel: { type: 'integer', minimum: 1 }, frame: { type: 'integer', minimum: 1 } }),
  tool('zoom', 'Change the focused document’s view: direction in/out (one zoom step), fit true to fit the window, or selection true to zoom to the active ROI. Display only.', 'zoom', 'view', { direction: { type: 'string', enum: ['in', 'out'] }, fit: boolean, selection: boolean }),
  tool('highlight', 'Point at what you are talking about: flash all ROI-list entries (rois all) or the given indices (rois indices) on the Fidelis canvas, with a short label beside Penny’s cursor. Display only.', 'highlight', 'view', { rois: { type: 'string', enum: ['all', 'indices'] }, indices: { type: 'array', items: { type: 'integer', minimum: 0 }, minItems: 1, maxItems: 1000 }, label: { type: 'string', maxLength: 80, description: 'Two to six words shown beside the cursor, e.g. "64 nuclei".' } }, ['rois']),
  tool('undo', 'Undo the last Fidelis History step. Returns the step.', 'undo', 'data'),
  tool('redo', 'Redo the last undone Fidelis History step. Returns the step.', 'redo', 'data'),
  tool('history', 'Read Fidelis History, newest last: action id, label, origin (typed, spoken, menu), provenance and outcome of each step.', 'history', 'read', { limit }),
  tool('macro', 'Export the recorded History as an ImageJ macro (the reproducible workflow) with its step count. Read only.', 'macro', 'read'),
  tool('run_macro', 'Run ImageJ macro code in Fidelis. Always waits for the researcher’s confirmation: returns awaiting_confirmation with the reasons and runs after fidelis_confirm. Prefer fidelis_command.', 'run_macro', 'code', { code: { type: 'string', minLength: 1, maxLength: 32000 } }, ['code']),
  tool('confirm', 'Run the plan waiting for confirmation (context.pending) after the researcher said yes or pressed Confirm; planId defaults to the pending plan. Returns that plan’s result.', 'confirm', 'data', { planId: { type: 'string', minLength: 1, maxLength: 100 } }),
  tool('cancel_plan', 'Discard the plan waiting for confirmation; nothing runs.', 'cancel_plan', 'view'),
  tool('knowledge', 'Search Penny’s Fidelis guidance and the locally downloaded official ImageJ documentation (user guide, menus, macro functions). Use before unfamiliar commands, filters, measurements or macros.', undefined, 'read', { query: { type: 'string', minLength: 2, maxLength: 200 } }, ['query']),
  tool('find_images', 'Find images on this computer by a loose name ("the blobs tif", "embryos"): searches Penny’s data folder, Desktop, Downloads and Documents. Use before fidelis_open_path when the researcher names an image instead of a path; never ask them for a folder.', undefined, 'read', { query: { type: 'string', maxLength: 200 }, limit: { type: 'integer', minimum: 1, maximum: 50 } }),
  tool('action_status', 'Read a recorded action (completed, failed, denied or expired) by ID. Decisions are taken by policy or the researcher; there is no model approval tool.', undefined, 'read', { actionId: { type: 'string' } }, ['actionId']),
];
