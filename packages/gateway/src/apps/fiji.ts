import type { AppProfile, FastRoute, PrepareInput } from './profile.js';
import { bindUiControl, cleanUtterance, completedPayload, continuesGenerally, finite, highlightAcknowledgment } from './profile.js';
import { fijiTools, FIJI_BUNDLE_ID } from './fiji-tools.js';
import { fijiInstructions } from './fiji-instructions.js';
import type { Runtime } from '../runtime.js';

/** Spoken at once when the model's first call is one of these (docs/FIJI-PROTOCOL.md, "Voice specifics"). */
const acknowledgments: Record<string, string> = {
  fiji_segment_cells: 'Segmenting cells.', fiji_measure: 'Measuring.', fiji_threshold: 'Thresholding.', fiji_analyze_particles: 'Counting particles.',
  fiji_filter: 'Applying the filter.', fiji_open_path: 'Opening it.', fiji_run_command: 'On it.', fiji_select_roi: 'Selecting the region.', fiji_zoom: 'Zooming.',
  fiji_ui_action: 'On it.', fiji_undo: 'Undoing.', fiji_restore: 'Restoring.', fiji_run_macro: 'Running it.', fiji_run_script: 'Running it.',
  fiji_save_workflow: 'Saving the macro.', fiji_find_images: 'Finding it.', fiji_overlay: 'Updating the view.', fiji_stack: 'Changing slice.',
  fiji_activate_image: 'Switching image.', fiji_project_stack: 'Projecting.', fiji_convert: 'Converting.', fiji_save_image: 'Saving.', fiji_export_results: 'Exporting.',
  fiji_results: 'Checking.', fiji_roi_manager: 'Checking.', fiji_images: 'Checking.',
  fiji_track_objects: 'Tracking them.', fiji_intensity_over_time: 'Following the intensity.', fiji_tumor_region: 'Outlining the tumour.',
  fiji_colocalize: 'Checking colocalisation.', fiji_classify_intensity: 'Scoring positives.', fiji_object_stats: 'Plotting the sizes.', fiji_filter_objects: 'Removing them.', fiji_split_objects: 'Separating them.', fiji_merge_objects: 'Merging them.', fiji_apply_analysis: 'Running it on the whole folder.', fiji_analysis_workflow: 'Here is the workflow.', fiji_explain_object: 'Checking that one.',
  fiji_make_macro: 'Writing the macro.', fiji_workflows: 'Checking.', fiji_run_workflow: 'Running it on every image.', fiji_capture_macro: 'Saving the macro.',
};
function acknowledgmentFor(name: string, args: Record<string, unknown>): string | undefined {
  if (name === 'fiji_highlight') return highlightAcknowledgment(args);
  if (name === 'fiji_run_workflow' && args.target !== 'open' && args.target !== 'folder') return 'Running the workflow.';
  return acknowledgments[name];
}
const silentFinishTools = new Set(['fiji_zoom', 'fiji_highlight', 'fiji_overlay', 'fiji_select_roi', 'fiji_activate_image', 'fiji_stack', 'fiji_display']);
const round = (value: number) => Number.isInteger(value) ? String(value) : value >= 100 ? value.toFixed(0) : value >= 10 ? value.toFixed(1) : value.toFixed(2);
/** Unit of a result, if the bridge reports one; a sentence never guesses "px" or "µm". */
const unitOf = (payload: Record<string, unknown> | undefined) => {
  const unit = payload?.unit ?? (payload?.calibration as { unit?: unknown } | undefined)?.unit;
  return typeof unit === 'string' && unit.trim() ? ` ${unit.trim()}²` : '';
};
/** Spoken outcome built from the real result (docs/FIJI-PROTOCOL.md local sentences); '' stays silent, undefined asks the model. */
function localSentenceFor(name: string, args: Record<string, unknown>, result: unknown): string | undefined {
  if (!result || typeof result !== 'object' || (result as { error?: unknown }).error != null) return undefined;
  if (silentFinishTools.has(name)) return '';
  const inner = completedPayload(result);
  // Read operations return the bridge result itself, without a decision envelope.
  if (biologyReads.has(name)) return biologySentence(name, args, inner ?? result as Record<string, unknown>) ?? undefined;
  if (inner === undefined && (result as { status?: unknown }).status !== 'completed') return undefined;
  if (name === 'fiji_segment_cells' || name === 'fiji_analyze_particles') {
    const count = finite(inner?.count); if (count === undefined) return undefined;
    const mean = finite(inner?.averageSize);
    return `Found ${count} cells${mean !== undefined ? `, mean area ${round(mean)}${unitOf(inner)}` : ''}.`;
  }
  if (name === 'fiji_measure') {
    // ImageJ's Results table accumulates across calls, so a single-ROI measurement is read from its own row; the
    // multi-measure summary is used only for the ROI Manager scope, where the bridge measures every entry.
    const scope = args.scope === 'manager' ? 'manager' : 'roi';
    if (scope === 'manager') {
      // `rows` is the whole accumulated Results table; the summary's count covers only the rows this call added.
      const summary = inner?.summary as Record<string, { mean?: unknown; count?: unknown }> | undefined;
      const mean = finite(summary?.mean?.mean), rows = finite(summary?.mean?.count) ?? finite(inner?.rows);
      if (mean === undefined || rows === undefined) return undefined;
      return `Mean intensity ${round(mean)} over ${rows} region${rows === 1 ? '' : 's'}.`;
    }
    const last = inner?.lastRow as Record<string, unknown> | undefined;
    const mean = finite(last?.Mean ?? last?.mean);
    return mean === undefined ? undefined : `Mean intensity ${round(mean)} in this region.`;
  }
  if (name === 'fiji_threshold') {
    const lower = finite(inner?.lower), upper = finite(inner?.upper);
    if (lower === undefined || upper === undefined) return undefined;
    const method = typeof inner?.method === 'string' && inner.method.trim() ? ` (${inner.method.trim()})` : '';
    return `Thresholded at ${round(lower)}–${round(upper)}${method}.`;
  }
  if (name === 'fiji_open_path') {
    // An import that went through a confirmed dialog reports its image at the envelope level (settleDialogs), not inside.
    const image = (inner?.image ?? (result as { image?: unknown }).image) as { title?: unknown; width?: unknown; height?: unknown } | undefined;
    const title = typeof image?.title === 'string' ? image.title : undefined, width = finite(image?.width), height = finite(image?.height);
    if (!title || width === undefined || height === undefined) return undefined;
    return `Opened ${title}, ${width}×${height}.`;
  }
  if (name === 'fiji_undo') return 'Undone.';
  if (name === 'fiji_restore') return 'Went back to the earlier state.';
  if (name === 'fiji_filter') return 'Filter applied.';
  if (name === 'fiji_convert') return 'Converted.';
  if (name === 'fiji_duplicate') return 'Duplicated.';
  if (name === 'fiji_project_stack') return 'Projected the stack.';
  if (name === 'fiji_clear_results') return 'Cleared the results.';
  if (name === 'fiji_add_to_manager') return 'Added it to the ROI Manager.';
  if (name === 'fiji_close') return 'Closed it.';
  if (name === 'fiji_save_workflow') { const recorded = inner?.steps; const steps = Array.isArray(recorded) ? recorded.length : finite(recorded); const file = typeof inner?.path === 'string' ? inner.path.split('/').pop() : undefined; return file ? `Saved${steps !== undefined ? ` ${steps} steps` : ''} as ${file}.` : 'Saved the macro.'; }
  if (name === 'fiji_export_results') { const rows = finite(inner?.rows); const file = typeof inner?.path === 'string' ? inner.path.split('/').pop() : undefined; return file ? `Exported${rows !== undefined ? ` ${rows} rows` : ''} to ${file}.` : 'Exported.'; }
  if (name === 'fiji_save_image') { const file = typeof inner?.path === 'string' ? inner.path.split('/').pop() : undefined; return file ? `Saved ${file}.` : 'Saved.'; }
  if (inner) { const sentence = biologySentence(name, args, inner); if (sentence !== null) return sentence; }
  return undefined;
}
const biologyReads = new Set(['fiji_colocalize', 'fiji_object_stats', 'fiji_workflows', 'fiji_analysis_workflow', 'fiji_explain_object']);
const text = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : undefined;
const fileName = (value: unknown) => text(value)?.split('/').pop();
const percent = (value: number) => `${Math.abs(value) >= 10 ? Math.round(value) : Number(value.toFixed(1))}%`;
const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? '' : 's'}`;
/** An area spoken with the unit the bridge reported; µm² of a millimetre or more is said in mm² (exact factor 10⁶). */
function spokenArea(area: number, unit: string | undefined): string {
  if (!unit) return round(area);
  if (/^pixels?$/i.test(unit)) return `${round(area)} square pixels`;
  if (/^(?:µm|um|μm|micron)$/i.test(unit) && area >= 1e6) return `${round(area / 1e6)} mm²`;
  return `${round(area)} ${unit}²`;
}
const correlation = (r: number) => { const size = Math.abs(r); return size >= 0.7 ? 'strong' : size >= 0.4 ? 'moderate' : size >= 0.1 ? 'weak' : undefined; };
/**
 * Spoken outcomes of the biology and macro operations, built only from numbers the bridge reported. `undefined` sends the
 * turn back to the model (missing numbers, a failed macro, nothing processed); `null` means the tool is not one of these.
 */
function biologySentence(name: string, args: Record<string, unknown>, inner: Record<string, unknown>): string | undefined | null {
  const unit = text(inner.unit);
  if (name === 'fiji_track_objects') {
    const tracks = finite(inner.tracks), frames = finite(inner.frames), speed = finite(inner.meanSpeed);
    if (tracks === undefined || frames === undefined) return undefined;
    // The bridge reports speedUnit as "<unit>/<timeUnit>": per second etc. with a frame interval, per frame (or slice) without.
    const [distance, per = 'frame'] = text(inner.speedUnit)?.split('/').map(part => part.trim()) ?? [unit, finite(inner.frameInterval) !== undefined ? text(inner.timeUnit) : undefined];
    const length = distance && !/^pixels?$/i.test(distance) ? ` ${distance}` : distance ? ' pixels' : '';
    const divisions = finite(inner.divisions);
    return `Tracked ${plural(tracks, 'object')} over ${frames} frames${speed !== undefined ? `, mean speed ${round(speed)}${length} per ${per || 'frame'}` : ''}${divisions ? `, ${plural(divisions, 'division')}` : ''}.`;
  }
  if (name === 'fiji_intensity_over_time') {
    const frames = finite(inner.frames), series = Array.isArray(inner.series) ? inner.series as Array<Record<string, unknown>> : [];
    const changes = series.map(entry => finite(entry?.changePercent));
    if (frames === undefined || !series.length || changes.some(change => change === undefined)) return undefined;
    if (series.length === 1) {
      const change = changes[0]!, peak = finite(series[0].peakFrame);
      return `Intensity ${change >= 0 ? 'rose' : 'fell'} ${percent(Math.abs(change))} over ${frames} frames${peak !== undefined ? `, peak at frame ${peak}` : ''}.`;
    }
    const low = Math.min(...changes as number[]), high = Math.max(...changes as number[]);
    const signed = (value: number) => `${value > 0 ? '+' : value < 0 ? 'minus ' : ''}${percent(Math.abs(value))}`;
    return `Followed ${series.length} regions over ${frames} frames; change ${low === high ? signed(low) : `from ${signed(low)} to ${signed(high)}`}.`;
  }
  if (name === 'fiji_tumor_region') {
    const fraction = finite(inner.areaFraction), area = finite(inner.area), regions = finite(inner.regions);
    if (fraction === undefined || fraction < 0 || fraction > 1) return undefined;
    const where = regions !== undefined && regions > 1 ? `${regions} regions cover` : 'Tumour covers';
    // areaFractionOf names the basis: the drawn selection, a ROI named "Tissue", or the whole image (glass included).
    const basis = inner.areaFractionOf === 'tissue' ? 'section' : inner.areaFractionOf === 'selection' ? 'selected region' : 'image';
    return `${where} ${percent(fraction * 100)} of the ${basis}${area !== undefined ? `, ${spokenArea(area, unit)}` : ''}.`;
  }
  if (name === 'fiji_colocalize') {
    const r = finite(inner.pearson); if (r === undefined) return undefined;
    const manders = inner.manders as { m1?: unknown; m2?: unknown } | undefined;
    const m1 = finite(manders?.m1), m2 = finite(manders?.m2), band = correlation(r);
    const relation = band ? `${band} ${r >= 0 ? 'positive' : 'negative'} correlation` : 'no meaningful correlation';
    return `Pearson ${r.toFixed(2)}, ${relation}${m1 !== undefined && m2 !== undefined ? `; Manders M1 ${m1.toFixed(2)}, M2 ${m2.toFixed(2)}` : ''}.`;
  }
  if (name === 'fiji_classify_intensity') {
    const total = finite(inner.total), positive = finite(inner.positive);
    if (total === undefined || positive === undefined || total <= 0) return undefined;
    const share = finite(inner.percentPositive) ?? positive / total * 100;
    const label = text(inner.label) ?? text(args.label);
    const marker = !label ? 'positive' : /positive|\+$/i.test(label) ? label : `${label}-positive`;
    return `${positive} of ${total} cells ${marker}, ${percent(share)}.`;
  }
  if (name === 'fiji_object_stats') {
    const n = finite(inner.n), area = inner.area as { mean?: unknown; median?: unknown } | undefined;
    const mean = finite(area?.mean), median = finite(area?.median);
    if (n === undefined || mean === undefined) return undefined;
    const range = inner.area as { min?: unknown; max?: unknown }, low = finite(range?.min), high = finite(range?.max);
    if (inner.plot && median !== undefined && low !== undefined && high !== undefined) return `Plotted ${plural(n, 'object')}: median ${spokenArea(median, unit)}, from ${round(low)} to ${spokenArea(high, unit)}.`;
    return `${plural(n, 'object')}, mean area ${spokenArea(mean, unit)}${median !== undefined ? `, median ${spokenArea(median, unit)}` : ''}.`;
  }
  if (name === 'fiji_filter_objects') {
    const removed = finite(inner.removed), kept = finite(inner.kept);
    if (removed === undefined || kept === undefined) return undefined;
    return removed ? `Removed ${removed} tiny ${removed === 1 ? 'one' : 'ones'}; ${plural(kept, 'cell')} left.` : `Nothing that small; still ${plural(kept, 'cell')}.`;
  }
  if (name === 'fiji_split_objects') {
    const split = finite(inner.split), before = finite(inner.before), after = finite(inner.after);
    if (split === undefined || before === undefined || after === undefined) return undefined;
    return split ? `Separated ${plural(split, 'clump')}: ${before} to ${after} cells.` : `Nothing looked merged; still ${plural(after, 'cell')}.`;
  }
  if (name === 'fiji_apply_analysis') {
    const run = inner.run as Record<string, unknown> | undefined;
    if (!run) return inner.workflow ? 'Saved the analysis as a macro.' : undefined;
    const processed = finite(run.processed), failed = Array.isArray(run.failed) ? run.failed.length : 0;
    if (processed === undefined || processed === 0) return undefined;
    const per = Array.isArray(run.perImage) ? (run.perImage as Array<{ resultsRows?: unknown }>).map(entry => finite(entry?.resultsRows)).filter((v): v is number => v !== undefined) : [];
    const mean = per.length ? per.reduce((a, b) => a + b, 0) / per.length : undefined;
    const manual = Array.isArray(inner.notReplayed) ? inner.notReplayed.length : 0;
    return `Ran the corrected analysis on ${plural(processed, 'image')}${mean !== undefined ? `, ${round(mean)} cells each on average` : ''}${failed ? `, ${failed} failed` : ''}${fileName(run.csv) ? `; results in ${fileName(run.csv)}` : ''}${manual ? `. ${plural(manual, 'hand edit')} not replayed` : ''}.`;
  }
  if (name === 'fiji_analysis_workflow') {
    const params = inner.params as Record<string, unknown> | undefined; if (!params) return undefined;
    const min = finite(params.minArea), split = finite(params.splitFactor);
    return `${text(params.method) ?? 'Auto'} threshold on channel ${params.channel}${split ? `, split clumps over ${round(split)} times the median` : ''}${min ? `, keep ${round(min)} and up` : ''}; ready to replay.`;
  }
  if (name === 'fiji_explain_object') {
    const reasons = Array.isArray(inner.reasons) ? (inner.reasons as unknown[]).filter((r): r is string => typeof r === 'string') : [];
    const area = finite(inner.area); if (!reasons.length || area === undefined) return undefined;
    const flag = reasons.find(r => /debris|touching/.test(r));
    return `Counted because ${reasons[0]}; ${spokenArea(area, unit)}${flag ? `, but ${flag}` : ''}.`;
  }
  if (name === 'fiji_merge_objects') {
    const merged = finite(inner.merged), after = finite(inner.after);
    if (merged === undefined || after === undefined) return undefined;
    return `Merged ${merged} into one; ${plural(after, 'cell')} now.`;
  }
  if (name === 'fiji_make_macro' || name === 'fiji_capture_macro') {
    if (inner.ok !== true || inner.error != null) return undefined;
    const file = fileName(inner.path) ?? text(inner.name);
    if (inner.tested !== true) return file ? `Saved ${file}; not tested yet.` : undefined;
    const rows = finite(inner.resultsAdded);
    return `Macro saved and tested${rows ? `: ${plural(rows, 'row')}` : ''}.`;
  }
  if (name === 'fiji_workflows') {
    const list = Array.isArray(inner.workflows) ? inner.workflows as Array<{ name?: unknown }> : undefined;
    if (!list) return undefined;
    const total = Math.max(finite(inner.total) ?? 0, list.length);
    if (!total) return 'No saved workflows yet.';
    const names = list.map(entry => text(entry?.name)).filter(Boolean) as string[];
    if (total > 3 || names.length !== total) return `${plural(total, 'saved workflow')}.`;
    return `${plural(list.length, 'saved workflow')}: ${names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`}.`;
  }
  if (name === 'fiji_run_workflow') {
    const processed = finite(inner.processed), failed = Array.isArray(inner.failed) ? inner.failed.length : finite(inner.failed) ?? 0;
    if (processed === undefined || processed === 0) return undefined;
    const workflow = text(inner.workflow) ?? text(args.name), csv = fileName(inner.csv);
    return `Ran ${workflow ?? 'the workflow'} on ${plural(processed, 'image')}${failed ? `, ${failed} failed` : ''}${csv ? `; results in ${csv}` : ''}.`;
  }
  return null;
}
/** Wording the local sentence cannot answer sends the turn back to the model. */
function requestContinuesAfter(name: string, utterance: string): boolean {
  const said = utterance.toLowerCase();
  if (continuesGenerally(said)) return true;
  // Marker positivity needs fiji_classify_intensity after the segmentation.
  // Re-segmenting with a stricter/looser method is the whole answer to a threshold-strictness request.
  if (name === 'fiji_segment_cells' && /\b(strict\w*|loose\w*|tight\w*|sensitiv\w*|aggressive|conservative)\b/.test(said)) return false;
  if (name === 'fiji_segment_cells') return !/\b(segment|count|find|detect|identify|how many)\b/.test(said) || /\b(area|size|intens|measur|mean|bright|perimeter|circular)/.test(said)
    || /\b(positive|negative|ki-?67|marker|percent|fraction|proportion|index|express)/.test(said);
  if (name === 'fiji_measure') return /\b(area|size|how many|count|perimeter|circular|feret|min|max|integrated|median)\b/.test(said);
  // A threshold change in a counting conversation must be followed by a recount, so the model continues.
  if (name === 'fiji_threshold') return /\b(count|particles?|how many|cells?|segment\w*|strict\w*|loose\w*|tight\w*|sensitiv\w*|aggressive|conservative|more|less|higher|lower|again|redo)\b/.test(said);
  if (name === 'fiji_analyze_particles') return /\b(intens|mean gray|brightness)\b/.test(said);
  if (name === 'fiji_track_objects') return /\b(intens|bright|which|fastest|slowest|longest|compare|direction)\b/.test(said);
  if (name === 'fiji_intensity_over_time') return /\b(recover|half|mobile|immobile|fit|rate|constant)/.test(said);
  if (name === 'fiji_tumor_region') return /\b(intens|stain|perimeter|circular|solid|shape|cells?|nucle)/.test(said);
  if (name === 'fiji_colocalize') return /\b(icq|costes|overlap|significan|random)/.test(said);
  if (name === 'fiji_object_stats') return /\b(intens|bright|circular|round|shape|largest|smallest|biggest)/.test(said);
  // A macro written to answer a question reports what it found; only "write/save a macro" is answered locally.
  if (name === 'fiji_make_macro') return !/\b(macro|script)\b/.test(said) || /\b(how many|what|which|find|count|measure)\b/.test(said);
  return false;
}
const imageUnit = (context: unknown) => { const unit = (context as { image?: { calibration?: { unit?: unknown } } } | undefined)?.image?.calibration?.unit; return typeof unit === 'string' ? unit : 'pixel'; };
/** Whole-utterance instant routes from the protocol: zoom, fit, overlay, slices and undo. */
function fastRoute(text: string, context: unknown): FastRoute | undefined {
  const said = cleanUtterance(text);
  const image = (context as { image?: { slices?: unknown; position?: { slice?: unknown } } } | undefined)?.image;
  if (!image) return undefined;
  const zoom = said.match(/^zoom (in|out)(?: (a (?:little|bit)|slightly|a lot|way (?:in|out)|more))?$/);
  if (zoom) return { tool: 'fiji_zoom', arguments: { direction: zoom[1] }, reply: zoom[1] === 'in' ? 'Zooming in.' : 'Zooming out.' };
  if (/^(?:zoom to fit|fit (?:to|the) (?:window|screen|view)|fit to window)$/.test(said)) return { tool: 'fiji_zoom', arguments: { fit: true }, reply: 'Fitting to the window.' };
  // Threshold strictness is answered deterministically by re-segmenting with a stricter or looser method.
  const strictness = said.match(/^(?:(?:now |please )?(?:make (?:it|that|the threshold|the segmentation) )?)(?:a (?:bit|little) )?(stricter|tighter|more strict|more conservative|less sensitive|looser|more sensitive|less strict|more aggressive)(?: threshold)?$/);
  if (strictness) {
    const stricter = /strict(?!.*less)|tight|conservative|less sensitive/.test(strictness[1]) && !/less strict/.test(strictness[1]);
    return { tool: 'fiji_segment_cells', arguments: { method: stricter ? 'Li' : 'Huang' }, reply: stricter ? 'Segmenting with a stricter threshold.' : 'Segmenting with a looser threshold.' };
  }
  // Corrections to a segmentation act on the ROI Manager directly, instantly, without re-segmenting.
  const objects = finite((context as { counts?: { roiManager?: unknown } } | undefined)?.counts?.roiManager) ?? 0;
  const pointer = (context as { pointer?: { overCanvas?: unknown; lastX?: unknown } } | undefined)?.pointer;
  const pointing = pointer?.overCanvas === true || finite(pointer?.lastX) !== undefined;
  const deictic = /\b(this|these|that|those|here|this one|that one)\b/.test(said);
  if (objects > 0 && pointing && /\bwhy\b/.test(said) && /\b(count|cell|detect|includ|this|that)\w*/.test(said)) return { tool: 'fiji_explain_object', arguments: { at: 'pointer' }, reply: 'Checking that one.' };
  if (objects > 0 && pointing && deictic && !/\b(and then|then|after that|channels?)\b/.test(said)) {
    // "These should be separate / are one cell / remove this one" act on the cells under the researcher's pointer.
    if (/\b(one cell|same cell|single cell|merge|join|combine|together|shouldn'?t be (?:split|separate))\b/.test(said) && !/\bseparate\b(?!.*shouldn)/.test(said)) return { tool: 'fiji_merge_objects', arguments: { at: 'pointer' }, reply: 'Merging them.' };
    if (/\b(separate|split|divide|break up|pull apart|two cells|not one)\b/.test(said)) return { tool: 'fiji_split_objects', arguments: { at: 'pointer' }, reply: 'Separating them.' };
    if (/\b(remove|delete|drop|get rid of|not a cell|isn'?t a cell|ignore)\b/.test(said)) return { tool: 'fiji_filter_objects', arguments: { at: 'pointer' }, reply: 'Removing it.' };
  }
  // Numbers matter here, and cleanUtterance drops digits: match the raw words.
  const smaller = text.toLowerCase().replace(/[.!?,]/g, ' ').match(/\b(?:remove|drop|delete|exclude|get rid of|filter out)\b.*\b(?:smaller|less) than (\d+(?:\.\d+)?) ?(square microns?|square micrometres?|square micrometers?|µm²|um2|microns? squared|square pixels?|pixels?)\b/);
  if (objects > 0 && smaller) {
    const calibrated = !/pixel/.test(smaller[2]) || imageUnit(context) === 'pixel';
    if (calibrated) return { tool: 'fiji_filter_objects', arguments: { minArea: Number(smaller[1]) }, reply: `Removing anything under ${smaller[1]}.` };
  }
  if (objects > 0 && /\b(apply|run|do)\b.*\b(this|that|it|same|analysis|workflow)\b.*\b(folder|all (?:the )?(?:other )?images|other \d+ images|every image|rest of the images|other images|whole dataset|dataset|everything)\b/.test(said)) return { tool: 'fiji_apply_analysis', arguments: {}, reply: 'Running it on the whole folder.' };
  if (objects > 0 && /^(?:show (?:me )?(?:the )?(?:workflow|steps|parameters|analysis)|what (?:did you do|steps|parameters)\b)/.test(said)) return { tool: 'fiji_analysis_workflow', arguments: {}, reply: 'Here is the workflow.' };
  if (objects > 0 && !/\b(and then|then|after that|channels?)\b/.test(said)) {
    if (/\b(remove|drop|delete|get rid of|filter out|exclude|ignore|lose)\b.*\b(tiny|small|little|specks?|debris|dots?|noise|junk|fragments?)\b/.test(said)) return { tool: 'fiji_filter_objects', arguments: { remove: 'tiny' }, reply: 'Removing the tiny ones.' };
    if (/\b(remove|drop|delete|get rid of|filter out|exclude)\b.*\b(huge|big|giant|large|clumps?)\b/.test(said)) return { tool: 'fiji_filter_objects', arguments: { remove: 'huge' }, reply: 'Removing the big ones.' };
    if (/\b(separate|split|divide|break up|pull apart)\b/.test(said) && /\b(them|touching|merged|stuck|clump\w*|together|cells?|nuclei|blobs?|those)\b/.test(said)) return { tool: 'fiji_split_objects', arguments: {}, reply: 'Separating them.' };
    if (/\b(size|area) (distribution|histogram)\b|\bhistogram\b|\bdistribution of (sizes?|areas?)\b/.test(said)) return { tool: 'fiji_object_stats', arguments: { plot: true }, reply: 'Plotting the sizes.' };
    // "How many are left?" reads the ROI Manager; re-segmenting would discard the researcher's corrections.
    if (/^(?:so |and )?how many(?: (?:cells|objects|particles|nuclei|blobs|of them))?(?: are)?(?: there| left| now| remaining| do (?:we|i) have| did (?:you|that) (?:find|get|keep))?(?: now)?$/.test(said)) return { tool: 'fiji_object_stats', arguments: { plot: false }, reply: 'Counting.' };
  }
  // "Find all the nuclei" is the whole request: the bridge picks the nuclear channel and respects a selection.
  if (/^(?:hey fiji |penny |okay |ok |now |please |can you |could you )*(?:find|segment|detect|identify|outline|count|show me|highlight) (?:all )?(?:of )?(?:the )?(?:nuclei|nucleus|cells|cell nuclei)(?: (?:here|in this (?:area|region|selection)|in the selection|please))?$/.test(said)) return { tool: 'fiji_segment_cells', arguments: {}, reply: 'Finding the nuclei.' };
  const overlay = said.match(/^(show|hide) (?:the )?(?:overlay|outlines)$/);
  if (overlay) return { tool: 'fiji_overlay', arguments: { show: overlay[1] === 'show' }, reply: overlay[1] === 'show' ? 'Showing the overlay.' : 'Hiding the overlay.' };
  const slice = said.match(/^(next|previous|prev|last) slice$/);
  if (slice) {
    // The bridge takes a 1-based slice number, so the route needs the current position and the slice count from the context.
    const slices = finite(image.slices), current = finite(image.position?.slice);
    if (slices === undefined || current === undefined || slices < 2) return undefined;
    const next = slice[1] === 'next' ? current + 1 : current - 1;
    if (next < 1 || next > slices) return undefined;
    return { tool: 'fiji_stack', arguments: { slice: next }, reply: slice[1] === 'next' ? 'Next slice.' : 'Previous slice.' };
  }
  if (/^undo(?: that| it)?$|^go back$/.test(said)) return { tool: 'fiji_undo', arguments: {}, reply: 'Undoing.' };
  return undefined;
}
async function prepareAction(input: PrepareInput) {
  if (input.name === 'fiji_ui_action') await bindUiControl(input, 'Fiji');
}
/** Dialogs that Bio-Formats or File > Open raise while importing, whose defaults are right for the demo. Exporter and
 * other parameter dialogs never match: the model configures those itself from the run_command result. */
const importDialog = (title: string) => !/export/i.test(title) && /^bio-formats (?:import|series|file stitching)|^import options|^open\b/i.test(title);
/** After opening an image (or running an importer command), press OK on the import dialog so the researcher never
 * waits on a modal, and say so. */
async function settleOpenDialog(runtime: Runtime, signal?: AbortSignal): Promise<string | undefined> {
  type Control = { id?: string; type?: string; text?: string; label?: string; enabled?: boolean; disabled?: boolean };
  type Ui = { windows?: Array<{ title?: string; controls?: Control[] }> };
  // Bio-Formats chains dialogs (Import Options, then Series Options for multi-series files such as whole slides), so
  // confirmation continues until no import dialog has appeared for two seconds; each confirmation is reported.
  const confirmed: string[] = []; let idle = 0; let lastFired: string | undefined;
  for (let attempt = 0; attempt < 60 && idle < 4; attempt++) {
    if (signal?.aborted) break;
    await new Promise(resolve => setTimeout(resolve, 500));
    let ui: Ui | undefined;
    try { ui = await runtime.adapter.execute('ui') as Ui; } catch { break; }
    const dialog = ui?.windows?.find(window => importDialog(window.title ?? ''));
    if (!dialog) { idle++; if (!confirmed.length && attempt >= 3) return undefined; continue; }
    idle = 0;
    const ok = dialog.controls?.find(control => /^button$/i.test(control.type ?? '') && /^ok$/i.test((control.text ?? control.label ?? '').trim()) && control.enabled !== false && !control.disabled);
    if (!ok?.id) return [...confirmed, `A "${dialog.title}" dialog is open without an enabled OK button; inspect it with fiji_ui.`].join(' ');
    // The same control seen again is a dialog still closing, not a new one.
    if (ok.id === lastFired) continue;
    try {
      const outcome = await runtime.call('fiji_ui_action', { nodeId: ok.id, action: 'fire' }, signal) as { status?: unknown };
      if (outcome?.status !== 'completed') return [...confirmed, `Could not confirm the "${dialog.title}" dialog; inspect it with fiji_ui.`].join(' ');
      lastFired = ok.id; confirmed.push(`Confirmed the "${dialog.title}" dialog with its default settings.`);
    } catch (error) { return [...confirmed, `The "${dialog.title}" dialog is open; confirming it failed: ${error instanceof Error ? error.message : String(error)}`].join(' '); }
  }
  return confirmed.length ? confirmed.join(' ') : undefined;
}

export const fiji: AppProfile = {
  id: 'fiji', displayName: 'Fiji', bundleId: FIJI_BUNDLE_ID, bridgePort: 49557, toolPrefix: 'fiji',
  tools: fijiTools, instructions: fijiInstructions,
  backendNotes: 'Relative view requests use fiji_zoom: direction in/out for "zoom in/out", fit true for "fit to window", magnification for an exact factor; "next/previous slice" uses fiji_stack with the 1-based slice number from image.position.slice.',
  skillDir: 'skills/fiji', knowledgeSources: ['skills/fiji', '.local/upstream/imagej-docs'], apiSource: false,
  launcherScript: './script/run-fiji.sh', bridgeJar: 'dist/luma-fiji-bridge.jar', buildManifest: '.local/fiji-bridge-build.json', buildScript: 'script/build-fiji-bridge.sh',
  doctorArtifacts: [['dist/luma-fiji-bridge.jar', 'Fiji bridge build'], ['.local/fiji/Fiji.app/Contents/MacOS/ImageJ-macosx', 'Fiji launcher']],
  fastRoute, acknowledgmentFor, localSentenceFor, requestContinuesAfter, silentFinishTools,
  longRunningTools: new Set(['fiji_apply_analysis', 'fiji_merge_objects', 'fiji_split_objects', 'fiji_segment_cells', 'fiji_run_macro', 'fiji_run_script', 'fiji_run_command', 'fiji_track_objects', 'fiji_tumor_region', 'fiji_make_macro', 'fiji_run_workflow', 'fiji_capture_macro']),
  prepareAction,
  async settleDialogs(runtime, name, args, signal) {
    // Only operations that import an image get their dialog confirmed; other commands keep their dialogs for the model.
    const importing = name === 'fiji_open_path' || (name === 'fiji_run_command' && /import|^open\b/i.test(String(args.name ?? '')));
    if (!importing) return undefined;
    type Ctx = { image?: { id?: unknown; title?: unknown; width?: unknown; height?: unknown } | null };
    let before: unknown;
    try { before = ((await runtime.adapter.execute('context')) as Ctx)?.image?.id; } catch { before = undefined; }
    const dialog = await settleOpenDialog(runtime, signal);
    if (!dialog) return undefined;
    // The worker-thread import shows its image after the last dialog closes; wait for it so the reply can name it.
    for (let attempt = 0; attempt < 30; attempt++) {
      if (signal?.aborted) break;
      let image: Ctx['image'];
      try { image = ((await runtime.adapter.execute('context')) as Ctx)?.image; } catch { break; }
      if (image && image.id !== before && typeof image.title === 'string') return { dialog, image: { id: image.id, title: image.title, width: image.width, height: image.height } };
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    return { dialog };
  },
};
