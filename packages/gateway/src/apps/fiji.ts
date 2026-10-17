import type { AppProfile, FastRoute, PrepareInput } from './profile.js';
import { bindUiControl, cleanUtterance, completedPayload, compoundRequest, continuesGenerally, finite, highlightAcknowledgment } from './profile.js';
import { fijiTools, FIJI_BUNDLE_ID } from './fiji-tools.js';
import { fijiInstructions } from './fiji-instructions.js';
import { finalizeRecipeDecision, recipeTool } from './fiji-recipes.js';
import { areaCorrection, imageObjectCount, regionCount } from './fiji-intents.js';
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
  if (name === 'fiji_object_stats' && args.plot === false) return 'Counting.';
  if (name === 'fiji_run_recipe') return 'On it.';
  if (name === 'fiji_recipes') return 'Checking.';
  if (name === 'fiji_run_workflow' && args.target !== 'open' && args.target !== 'folder') return 'Running the workflow.';
  return acknowledgments[name];
}
const silentFinishTools = new Set(['fiji_zoom', 'fiji_highlight', 'fiji_overlay', 'fiji_select_roi', 'fiji_activate_image', 'fiji_stack', 'fiji_display']);
const round = (value: number) => Number.isInteger(value) ? String(value) : value >= 100 ? value.toFixed(0) : value >= 10 ? value.toFixed(1) : value.toFixed(2);
/** Unit of a result, if the bridge reports one; a sentence never guesses "px" or "µm". */
const unitOf = (payload: Record<string, unknown> | undefined) => {
  const unit = payload?.unit ?? (payload?.calibration as { unit?: unknown } | undefined)?.unit;
  if (typeof unit !== 'string' || !unit.trim()) return '';
  return /^pixels?$/i.test(unit.trim()) ? ' square pixels' : ` ${unit.trim()}²`;
};
/** A completed call that did nothing the researcher asked for, as a short spoken reason; undefined when it worked. */
function unsuccessful(name: string, _args: Record<string, unknown>, result: unknown): string | undefined {
  const inner = completedPayload(result);
  // Without a checkpoint for the active image the bridge falls back to ImageJ's single-step Undo, which may revert nothing.
  if (name === 'fiji_undo' && inner?.mode === 'imagej-undo') return 'No checkpoint to go back to; ImageJ\'s Undo only reverts the last filter.';
  return undefined;
}
/** Spoken outcome built from the real result (docs/FIJI-PROTOCOL.md local sentences); '' stays silent, undefined asks the model. */
function localSentenceFor(name: string, args: Record<string, unknown>, result: unknown): string | undefined {
  if (!result || typeof result !== 'object' || (result as { error?: unknown }).error != null) return undefined;
  if (silentFinishTools.has(name)) return '';
  // A recipe's sentence is its @say line filled from the RESULT values the macro printed (fiji-recipes.ts).
  if (name === 'fiji_run_recipe') { const spoken = (result as { status?: unknown; spoken?: unknown }).status === 'completed' ? (result as { spoken?: unknown }).spoken : undefined; return typeof spoken === 'string' && spoken ? spoken : undefined; }
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
    return `Opened ${friendlyName(title, (image as { path?: unknown }).path)}, ${width} by ${height}.`;
  }
  if (name === 'fiji_undo') {
    // Say what came back, so "undo" after the wrong step is visible: the checkpoint's label and the objects now present.
    const why = unsuccessful(name, args, result); if (why) return why;
    const counts = inner?.counts as { roiManager?: unknown; roiManagerForImage?: unknown } | undefined;
    const label = text(inner?.label), objects = finite(counts?.roiManagerForImage) ?? finite(counts?.roiManager);
    return label ? `Restored "${label.replace(/["“”]/g, '')}"${objects !== undefined ? `; ${plural(objects, 'object')} now` : ''}.` : 'Undone.';
  }
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
    if (n !== undefined && /^(?:selection|region)$/.test(String(inner.scopeUsed))) return `${plural(n, 'object')} in ${inner.scopeUsed === 'selection' ? 'the selected region' : 'that region'}.`;
    if (n === 0) return '0 detected objects in this image.';
    if (n !== undefined && args.plot === false) return `${plural(n, 'object')}.`;
    if (n === undefined || mean === undefined) return undefined;
    const range = inner.area as { min?: unknown; max?: unknown }, low = finite(range?.min), high = finite(range?.max);
    if (inner.plot && median !== undefined && low !== undefined && high !== undefined) return `Plotted ${plural(n, 'object')}: median ${spokenArea(median, unit)}, from ${round(low)} to ${spokenArea(high, unit)}.`;
    return `${plural(n, 'object')}, mean area ${spokenArea(mean, unit)}${median !== undefined ? `, median ${spokenArea(median, unit)}` : ''}.`;
  }
  if (name === 'fiji_filter_objects') {
    const removed = finite(inner.removed), kept = finite(inner.kept);
    if (removed === undefined || kept === undefined) return undefined;
    const tiny = args.minArea === undefined && args.maxArea === undefined && args.at === undefined && !/^(?:huge|large|none)$/.test(String(args.remove ?? 'tiny'));
    return removed ? `Removed ${removed}${tiny ? ` tiny ${removed === 1 ? 'one' : 'ones'}` : ` ${removed === 1 ? 'object' : 'objects'}`}; ${plural(kept, 'cell')} left.` : `${tiny ? 'Nothing that small' : 'No objects matched'}; still ${plural(kept, 'cell')}.`;
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
    return `Ran the corrected analysis on ${plural(processed, 'image')}${mean !== undefined ? `, ${round(mean)} cells each on average` : ''}${failed ? `, ${failed} failed` : ''}${fileName(run.csv) ? (fileName(run.csv)!.length > 28 ? '; results saved as a CSV' : `; results in ${fileName(run.csv)}`) : ''}${manual ? `. ${plural(manual, 'hand edit')} not replayed` : ''}.`;
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
const analysisWords = /\b(?:measur\w*|count\w*|segment\w*|detect\w*|quantif\w*|coloc\w*|correlat\w*|overlap\w*|track\w*|analy[sz]\w*|threshold\w*|classif\w*|how many|intensit\w*|percent\w*|compar\w*|subtract\w*|project\w*|export\w*|macro|tumou?r)\b/;
/** Wording the local sentence cannot answer sends the turn back to the model. */
function requestContinuesAfter(name: string, utterance: string): boolean {
  const said = utterance.toLowerCase();
  if (continuesGenerally(said)) return true;
  // A recipe's spoken sentence carries its headline numbers only; a request for another statistic, a per-object
  // answer or a comparison goes back to the model, which reads them from the recipe's summary.
  if (name === 'fiji_run_recipe') return /\b(spearman|kendall|icq|li'?s|costes|p[- ]?value|significan\w*|threshold\w*|dispersion|goodness|junctions?|end[- ]?points?|branch\w*|median|standard deviation|sd|variance|range|min\w*|max\w*|each|every|per (?:cell|nucleus|object|frame|slice|track)|which|compare|versus|vs|list|table|plot|graph|chart|histogram|why|explain)\b/.test(said);
  // Viewing, opening or selecting answers only a pure view command: "measure the intensity of these cells" after a
  // selection, or "open hela cells, do the channels colocalise?" after the open, still has its analysis to do.
  if ((silentFinishTools.has(name) || name === 'fiji_open_path') && (analysisWords.test(said) || compoundRequest(utterance))) return true;
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
  if (name === 'fiji_object_stats') return /\b(intens|bright|circular|round|shape|largest|smallest|biggest|mean|median|average)/.test(said);
  // A macro written to answer a question reports what it found; only "write/save a macro" is answered locally.
  if (name === 'fiji_make_macro') return !/\b(macro|script)\b/.test(said) || /\b(how many|what|which|find|count|measure)\b/.test(said);
  return false;
}
/** Whole-utterance instant routes from the protocol: zoom, fit, overlay, slices and undo. */
/** How a person would say a file name aloud: "hela cells", "BBBC008 channel 1", never a 60-character accession. */
export function friendlyName(title: string, path?: unknown): string {
  const base = title.replace(/\.(?:ome\.)?[a-z0-9]{2,5}$/i, '').replace(/[_]+/g, ' ').replace(/\s+-\s+/g, ' ').replace(/\s+/g, ' ').trim();
  if (base.length <= 32 && !/\d{6,}/.test(base)) return base.replace(/-/g, ' ');
  const parts = typeof path === 'string' ? path.split('/').filter(Boolean).slice(0, -1) : [];
  let folder = parts.at(-1) ?? '';
  if (/^(?:images?|raw|data|tiffs?|input|originals?)$/i.test(folder)) folder = parts.at(-2) ?? folder;
  const dataset = /^[a-z]+\d+$/i.test(folder) ? folder.toUpperCase() : folder.replace(/[_-]+/g, ' ');
  const channel = base.match(/(?:channel|ch)\s*(\d+)/i)?.[1] ?? base.match(/(?:^|[ _])w(\d)/i)?.[1];
  if (dataset) return `the ${dataset}${channel ? ` channel ${channel}` : ''} image`;
  return base.split(/[ -]+/).slice(0, 5).join(' ');
}
const lutWords: Record<string, string> = { red: 'red', green: 'green', blue: 'blue', cyan: 'cyan', magenta: 'magenta', yellow: 'yellow', grays: 'grey' };
/** "What am I looking at?" from the live metadata alone: instant, and only what Fiji reports (no guessed biology). */
export function describeImage(context: unknown): string | undefined {
  const c = context as { image?: Record<string, unknown>; counts?: Record<string, unknown> } | undefined; const image = c?.image; if (!image) return undefined;
  const width = finite(image.width), height = finite(image.height), depth = finite(image.bitDepth); if (width === undefined || height === undefined) return undefined;
  const channels = Array.isArray(image.channels) ? image.channels as Array<{ lut?: unknown }> : [];
  const slices = finite(image.slices) ?? 1, frames = finite(image.frames) ?? 1;
  const cal = image.calibration as { pixelWidth?: unknown; unit?: unknown } | undefined, unit = typeof cal?.unit === 'string' ? cal.unit : 'pixel', px = finite(cal?.pixelWidth);
  const parts: string[] = [];
  const rgb = image.type === 'COLOR_RGB' || depth === 24;
  parts.push(`${friendlyName(String(image.title ?? 'This image'), image.path)}: ${width} by ${height}${rgb ? ', RGB colour' : depth ? `, ${depth}-bit` : ''}`);
  if (channels.length > 1) {
    const colours = channels.map(channel => lutWords[String(channel.lut)] ?? String(channel.lut ?? 'grey'));
    const list = colours.length > 2 ? `${colours.slice(0, -1).join(', ')} and ${colours.at(-1)}` : colours.join(' and ');
    parts.push(`${channels.length} channels, ${list}`);
  } else if (!rgb) parts.push('one channel');
  if (frames > 1) parts.push(`a time-lapse of ${frames} frames`);
  if (slices > 1) parts.push(`a ${slices}-slice z-stack`);
  if (px !== undefined && !/^pixels?$/i.test(unit)) parts.push(`${px} ${unit === 'micron' ? 'microns' : unit} per pixel`);
  const objects = finite(c?.counts?.roiManagerForImage ?? c?.counts?.roiManager);
  if (objects) parts.push(`${objects} objects outlined`);
  const sentence = `${parts.join('; ')}.`;
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
}
function fastRoute(text: string, context: unknown): FastRoute | undefined {
  const said = cleanUtterance(text);
  // "Open the hela cells image" opens the best local match in one step (the host resolves imageQuery); digits come from the raw words.
  // Transcribe often writes "open" as "opened" and plurals as possessives ("cell's").
  const raw = text.toLowerCase().replace(/'s\b/g, 's').replace(/[.!?,]/g, ' ').replace(/\s+/g, ' ').trim();
  const opening = raw.match(/^(?:(?:hey )?(?:fiji|penny)\s+)?(?:please |can you |could you )?(?:open|opened|load|bring up|pull up)(?: up)? (?:the |my |that )?(.+?)(?: (?:image|picture|file|movie|video|stack|slide|sample))?(?: please)?$/);
  if (opening && !/\b(and|then|also)\b/.test(opening[1]) && !/^(?:blobs|a|an|new)\b/.test(opening[1]) && opening[1].length >= 2)
    return { tool: 'fiji_open_path', arguments: {}, reply: 'Opening it.', imageQuery: opening[1] };
  const image = (context as { image?: { slices?: unknown; position?: { slice?: unknown } } } | undefined)?.image;
  if (!image) return undefined;
  const area = areaCorrection(text, context); if (area) return area;
  if (/^(?:hey fiji |penny |okay |ok |so )*(?:what am i looking at|what is this(?: image)?|what's this(?: image)?|describe (?:this|the) image|tell me about (?:this|the) image|what (?:kind of )?image is this)$/.test(said)) {
    const described = describeImage(context); if (described) return { tool: 'fiji_context', arguments: {}, reply: described };
  }
  // A fast action is the whole request, never just the first half of a spoken chain.
  if (continuesGenerally(said.replace(/^(?:and|also)\s+/, ''))) return undefined;
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
  const objects = imageObjectCount(context);
  const region = regionCount(text, context); if (region) return region;
  if (objects > 0 && /^(?:show(?: me)?|highlight|outline|point out) (?:all )?(?:of )?(?:the )?(?:nuclei|cells|objects|detections)(?: again| here| in this (?:area|region|selection)| in the selection)?$/.test(said)) return { tool: 'fiji_highlight', arguments: { rois: 'manager', label: `${objects} objects` }, reply: 'Here they are.' };
  // An empty manager is still a real answer. Re-segmenting here would undo a deliberate removal of all detections.
  if (/^(?:so |and )?how many(?: (?:cells|objects|particles|nuclei|blobs|of them))?(?: are)?(?: there| left| now| remaining| do (?:we|i) have| did (?:you|that) (?:find|get|keep))?(?: now)?$/.test(said)) return { tool: 'fiji_object_stats', arguments: { plot: false }, reply: 'Counting.' };
  const pointer = (context as { pointer?: { overCanvas?: unknown; lastX?: unknown; lastAgeMs?: unknown } } | undefined)?.pointer;
  const age = finite(pointer?.lastAgeMs);
  const pointing = pointer?.overCanvas === true || finite(pointer?.lastX) !== undefined && age !== undefined && age >= 0 && age <= 30_000;
  const deictic = /\b(this|these|that|those|here|this one|that one)\b/.test(said);
  if (objects > 0 && pointing && /\bwhy\b/.test(said) && /\b(count|cell|detect|includ|this|that)\w*/.test(said)) return { tool: 'fiji_explain_object', arguments: { at: 'pointer' }, reply: 'Checking that one.' };
  if (objects > 0 && pointing && deictic && !/\b(and then|then|after that|channels?)\b/.test(said)) {
    // "These should be separate / are one cell / remove this one" act on the cells under the researcher's pointer.
    if (/\b(one cell|same cell|single cell|merge|join|combine|together|shouldn'?t be (?:split|separate))\b/.test(said) && !/\bseparate\b(?!.*shouldn)/.test(said)) return { tool: 'fiji_merge_objects', arguments: { at: 'pointer' }, reply: 'Merging them.' };
    if (/\b(separate|split|divide|break up|pull apart|two cells|not one)\b/.test(said)) return { tool: 'fiji_split_objects', arguments: { at: 'pointer' }, reply: 'Separating them.' };
    if (/\b(remove|delete|drop|get rid of|not a cell|isn'?t a cell|ignore)\b/.test(said)) return { tool: 'fiji_filter_objects', arguments: { at: 'pointer' }, reply: 'Removing it.' };
  }
  if (objects > 0 && /\b(apply|run|do)\b.*\b(this|that|it|same|analysis|workflow)\b.*\b(folder|all (?:the )?(?:other )?images|other \d+ images|every image|rest of the images|other images|whole dataset|dataset|everything)\b/.test(said)) {
    // "…in the bbbc folder" names a folder for the host to resolve; "the whole/this folder" is the active image's own.
    const named = said.match(/\b(?:in|on|for|to|from) (?:the |my )?([a-z0-9][\w'-]*(?: [a-z0-9][\w'-]*)?) (?:folder|directory|dataset)\b/)?.[1]
      ?.split(' ').filter(word => !/^(?:the|whole|entire|this|that|same|other|current|rest|its|image's|images?|all|my)$/.test(word)).join(' ');
    return { tool: 'fiji_apply_analysis', arguments: {}, reply: 'Running it on the whole folder.', ...(named ? { folderQuery: named } : {}) };
  }
  if (objects > 0 && /^(?:show (?:me )?(?:the )?(?:workflow|steps|parameters|analysis)|what (?:did you do|steps|parameters)\b)/.test(said)) return { tool: 'fiji_analysis_workflow', arguments: {}, reply: 'Here is the workflow.' };
  if (objects > 0 && !/\b(and then|then|after that|channels?)\b/.test(said)) {
    if (/\b(remove|drop|delete|get rid of|filter out|exclude|ignore|lose)\b.*\b(tiny|small|little|specks?|debris|dots?|noise|junk|fragments?)\b/.test(said)) return { tool: 'fiji_filter_objects', arguments: { remove: 'tiny' }, reply: 'Removing the tiny ones.' };
    if (/\b(remove|drop|delete|get rid of|filter out|exclude)\b.*\b(huge|big|giant|large|clumps?)\b/.test(said)) return { tool: 'fiji_filter_objects', arguments: { remove: 'huge' }, reply: 'Removing the big ones.' };
    if (/\b(separate|split|divide|break up|pull apart)\b/.test(said) && /\b(them|touching|merged|stuck|clump\w*|together|cells?|nuclei|blobs?|those)\b/.test(said)) return { tool: 'fiji_split_objects', arguments: {}, reply: 'Separating them.' };
    if (/\b(size|area) (distribution|histogram)\b|\bhistogram\b|\bdistribution of (sizes?|areas?)\b/.test(said)) return { tool: 'fiji_object_stats', arguments: { plot: true }, reply: 'Plotting the sizes.' };
    // "How many are left?" reads the ROI Manager; re-segmenting would discard the researcher's corrections.
    if (/^(?:so |and )?how many(?: (?:cells|objects|particles|nuclei|blobs|of them))?(?: are)?(?: there| left| now| remaining| do (?:we|i) have| did (?:you|that) (?:find|get|keep))?(?: now)?$/.test(said)) return { tool: 'fiji_object_stats', arguments: { plot: false }, reply: 'Counting.' };
  }
  // "How much of the tissue is tumour?" is answered by the (deterministic) tumour outline, never by a generic measure.
  if (/^(?:so |and |now )?(?:how much|what (?:fraction|percentage|proportion|percent)) of (?:the )?(?:tissue|section|slide|sample|image) is (?:tumou?r|cancer)\b/.test(said)) return { tool: 'fiji_tumor_region', arguments: {}, reply: 'Measuring the tumour.' };
  // Brightness is display-only: move the display range from the live values, never filter the pixels.
  const range = (context as { image?: { displayRange?: unknown } } | undefined)?.image?.displayRange;
  if (Array.isArray(range) && range.length === 2 && range.every(v => typeof v === 'number')) {
    const [low, high] = range as [number, number], span = Math.max(1e-6, high - low);
    if (/^(?:make (?:it|the image|this) |a (?:bit|little) )*(?:a (?:bit|little) )?(brighter|lighter)(?: please)?$/.test(said)) return { tool: 'fiji_display', arguments: { min: low, max: low + span * 0.7 }, reply: 'Brighter.' };
    if (/^(?:make (?:it|the image|this) |a (?:bit|little) )*(?:a (?:bit|little) )?darker(?: please)?$/.test(said)) return { tool: 'fiji_display', arguments: { min: low, max: low + span * 1.4 }, reply: 'Darker.' };
    if (/^(?:(?:auto|fix|improve|boost|enhance|more) (?:the )?contrast|auto(?:matic)? (?:brightness|contrast)|make (?:it|this) (?:clearer|easier to see|pop))$/.test(said)) return { tool: 'fiji_display', arguments: { auto: true }, reply: 'Auto contrast.' };
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
/** Tuning knobs the bridge picks robustly itself (nuclear channel, stable channel, tissue mask, size floors). The model tends to
 * fill every optional field (QA: tumour "Otsu, sigma 2, keepLargest 1" gave 13% instead of ~40%; tracking found nothing). */
const expertArguments: Record<string, string[]> = {
  fiji_tumor_region: ['channel', 'stain', 'sigma', 'method', 'minAreaFraction', 'keepLargest'],
  fiji_track_objects: ['channel', 'method', 'dark', 'minSize', 'maxSize', 'maxDistance', 'maxGap', 'minLength', 'sigma'],
  fiji_segment_cells: ['channel', 'method', 'sigma', 'minSize', 'watershed', 'dark', 'gaussian', 'keepArtifacts'],
  fiji_split_objects: ['factor'],
  fiji_filter_objects: ['fraction'],
  fiji_colocalize: ['method', 'thresholdA', 'thresholdB'],
  fiji_intensity_over_time: ['channel'],
};
/** Keeps an expert argument only when the researcher's words mention it: its name, its value, or (for segmentation) strictness. */
function modelArguments(name: string, args: Record<string, unknown>, utterance: string): Record<string, unknown> {
  const expert = expertArguments[name]; if (!expert) return args;
  const said = utterance.toLowerCase(), numbers = new Set((said.match(/\d+(?:\.\d+)?/g) ?? []).map(Number));
  const named: Record<string, RegExp> = { channel: /\b(channel|red|green|blue|dapi|hoechst|gfp|nuclear|c\d)\b/, minSize: /\b(size|smaller|bigger|larger|area|pixels?|microns?)\b/, maxSize: /\b(size|smaller|bigger|larger|area)\b/,
    sigma: /\b(sigma|blur|smooth)/, method: /\b(otsu|li|huang|triangle|default|yen|moments|maxentropy|isodata|mean|minimum|intermodes|renyi|shanbhag|percentile|strict\w*|loose\w*|sensitiv\w*|threshold)\b/,
    watershed: /\bwatershed\b/, keepArtifacts: /\b(?:keep|retain|include|exclude|remove|discard)\b.*\b(?:artifacts?|artefacts?|specks?|debris|labels?|text)\b/, dark: /\b(dark|bright|light) background\b/, maxDistance: /\b(distance|jump|far)\b/, minLength: /\b(length|frames?|short)\b/, keepLargest: /\b(largest|regions?|biggest)\b/, stain: /\b(h&e|he|ihc|dab|fluorescen\w*)\b/ };
  const kept: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(args)) {
    if (!expert.includes(key)) { kept[key] = value; continue; }
    const mentioned = (named[key]?.test(said) ?? said.includes(key.toLowerCase())) || typeof value === 'number' && numbers.has(value) || typeof value === 'string' && value.length > 1 && said.includes(value.toLowerCase());
    if (mentioned) kept[key] = value;
  }
  return kept;
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
  fastRoute, acknowledgmentFor, localSentenceFor, requestContinuesAfter, silentFinishTools, unsuccessful,
  longRunningTools: new Set(['fiji_run_recipe', 'fiji_apply_analysis', 'fiji_merge_objects', 'fiji_split_objects', 'fiji_segment_cells', 'fiji_run_macro', 'fiji_run_script', 'fiji_run_command', 'fiji_track_objects', 'fiji_tumor_region', 'fiji_make_macro', 'fiji_run_workflow', 'fiji_capture_macro']),
  prepareAction,
  localTool: recipeTool, finalizeDecision: finalizeRecipeDecision, modelArguments,
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
    for (let attempt = 0; attempt < 20; attempt++) {
      if (signal?.aborted) break;
      let image: Ctx['image'];
      try { image = ((await runtime.adapter.execute('context')) as Ctx)?.image; } catch { break; }
      if (image && image.id !== before && typeof image.title === 'string') return { dialog, image: { id: image.id, title: image.title, width: image.width, height: image.height } };
      await new Promise(resolve => setTimeout(resolve, 300));
    }
    return { dialog };
  },
};
