import type { AppProfile, FastRoute, PrepareInput } from './profile.js';
import { cleanUtterance, completedPayload, compoundRequest, continuesGenerally, finite, highlightAcknowledgment } from './profile.js';
import { fidelisTools, FIDELIS_BUNDLE_ID } from './fidelis-tools.js';
import { fidelisInstructions } from './fidelis-instructions.js';
import { friendlyName } from './fiji.js';
import { imageNameMatches, queryWords } from '../local-images.js';

/** The parts of the bridge's `context` result (docs/FIDELIS-PROTOCOL.md) the instant routes and sentences read. */
type FidelisDocument = { id?: unknown; title?: unknown; width?: unknown; height?: unknown; channels?: unknown; slices?: unknown; frames?: unknown; bitDepth?: unknown;
  unit?: unknown; pixelWidth?: unknown; pixelHeight?: unknown; path?: unknown;
  /** Not in the first contract table; used when the bridge reports them (brighter/darker, next/previous slice). */
  displayRange?: unknown; display?: { min?: unknown; max?: unknown }; position?: { channel?: unknown; slice?: unknown; frame?: unknown } };
type FidelisContext = { document?: FidelisDocument | null; documents?: Array<{ id?: unknown; title?: unknown; focused?: unknown }>; roi?: unknown;
  counts?: { rois?: unknown; resultsRows?: unknown; history?: unknown }; pending?: { planId?: unknown; reasons?: unknown } | null };

/** Spoken at once when the model's first call is one of these. A plan may still wait for confirmation, so a macro is "prepared", never "running". */
const acknowledgments: Record<string, string> = {
  fidelis_segment_cells: 'Segmenting cells.', fidelis_measure: 'Measuring.', fidelis_threshold: 'Thresholding.', fidelis_analyze_particles: 'Counting particles.',
  fidelis_filter: 'Applying the filter.', fidelis_open_path: 'Opening it.', fidelis_command: 'On it.', fidelis_select_roi: 'Selecting the region.', fidelis_zoom: 'Zooming.',
  fidelis_undo: 'Undoing.', fidelis_redo: 'Redoing.', fidelis_run_macro: 'Preparing the macro.', fidelis_confirm: 'Running it.', fidelis_cancel_plan: 'Cancelling.',
  fidelis_convert: 'Converting.', fidelis_duplicate: 'Duplicating.', fidelis_display: 'Adjusting the display.', fidelis_stack: 'Changing slice.', fidelis_activate_image: 'Switching image.',
  fidelis_find_images: 'Finding it.', fidelis_images: 'Checking.', fidelis_rois: 'Checking.', fidelis_results: 'Checking.', fidelis_history: 'Checking.', fidelis_object_stats: 'Counting.',
  fidelis_commands: 'Looking it up.', fidelis_macro: 'Here is the macro.',
};
function acknowledgmentFor(name: string, args: Record<string, unknown>): string | undefined {
  if (name === 'fidelis_highlight') return highlightAcknowledgment(args);
  return acknowledgments[name];
}
const silentFinishTools = new Set(['fidelis_zoom', 'fidelis_highlight', 'fidelis_select_roi', 'fidelis_activate_image', 'fidelis_stack', 'fidelis_display']);
const round = (value: number) => Number.isInteger(value) ? String(value) : Math.abs(value) >= 100 ? value.toFixed(0) : Math.abs(value) >= 10 ? value.toFixed(1) : value.toFixed(2);
const text = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : undefined;
const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? '' : 's'}`;
const record = (value: unknown) => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
/** An area in the unit the bridge reported; a sentence never guesses "px" or "µm". */
function spokenArea(area: number, unit: unknown): string {
  const u = text(unit);
  if (!u) return round(area);
  if (/^pixels?$/i.test(u)) return `${round(area)} square pixels`;
  if (/^(?:µm|um|μm|microns?)$/i.test(u) && area >= 1e6) return `${round(area / 1e6)} mm²`;
  return `${round(area)} ${/^(?:um|microns?)$/i.test(u) ? 'µm' : u}²`;
}
/** A Results column by its ImageJ name, whatever the case ("Mean", "mean"). */
function column(source: unknown, name: string): unknown {
  const table = record(source); if (!table) return undefined;
  const key = Object.keys(table).find(candidate => candidate.toLowerCase() === name.toLowerCase());
  return key === undefined ? undefined : table[key];
}
/** The bridge payload when it holds a plan waiting for the researcher's Confirm or "yes" (docs/FIDELIS-PROTOCOL.md, Confirmation). */
export function awaitingConfirmation(result: unknown): Record<string, unknown> | undefined {
  const inner = completedPayload(result) ?? record(result);
  return inner?.status === 'awaiting_confirmation' ? inner : undefined;
}
/** "Ready to run 3 steps; say yes to go ahead." — nothing has run. */
export function awaitingSentence(plan: Record<string, unknown>): string {
  const steps = Array.isArray(plan.steps) ? plan.steps.length : finite(plan.steps);
  return steps === undefined || steps < 1 ? 'Ready to run it; say yes to go ahead.' : `Ready to run ${plural(steps, 'step')}; say yes to go ahead.`;
}
/** "Gaussian Blur..." → "Gaussian Blur"; a catalogue id's last menu item when the step has no label. */
function stepName(step: unknown, fallbackId?: unknown): string | undefined {
  const label = text(record(step)?.label) ?? text(fallbackId)?.split('>').pop();
  return label?.replace(/^ij1:/, '').replace(/(?:\.{3}|…)$/, '').trim() || undefined;
}
function countSentence(inner: Record<string, unknown>): string | undefined {
  const count = finite(inner.count); if (count === undefined) return undefined;
  const mean = finite(inner.averageSize);
  return `Found ${count} cells${mean !== undefined ? `, mean area ${spokenArea(mean, inner.unit)}` : ''}.`;
}
function measureSentence(args: Record<string, unknown>, inner: Record<string, unknown>): string | undefined {
  if (args.scope === 'rois') {
    // The summary covers the rows this call measured (one per ROI-list entry).
    const mean = column(inner.summary, 'Mean') as { mean?: unknown; count?: unknown } | undefined;
    const value = finite(mean?.mean), rows = finite(mean?.count) ?? finite(inner.rows);
    if (value === undefined) return undefined;
    return rows === undefined ? `Mean intensity ${round(value)} across the regions.` : `Mean intensity ${round(value)} over ${plural(rows, 'region')}.`;
  }
  const last = record(inner.lastRow); const where = args.scope === 'all' ? 'in the image' : 'in this region';
  const mean = finite(column(last, 'Mean'));
  if (mean !== undefined) return `Mean intensity ${round(mean)} ${where}.`;
  const area = finite(column(last, 'Area'));
  return area === undefined ? undefined : `Area ${spokenArea(area, inner.unit)} ${where}.`;
}
function thresholdSentence(inner: Record<string, unknown>): string | undefined {
  const lower = finite(inner.lower), upper = finite(inner.upper);
  if (lower === undefined || upper === undefined) return undefined;
  const method = text(inner.method) ? ` (${text(inner.method)})` : '';
  return `Thresholded at ${round(lower)}–${round(upper)}${method}.`;
}
/** A confirmed plan answers with the result of what it ran: a count, a measurement, a threshold or its steps. */
function confirmSentence(inner: Record<string, unknown>): string {
  const counted = countSentence(inner); if (counted) return counted;
  const threshold = thresholdSentence(inner); if (threshold) return threshold;
  if (inner.lastRow || inner.summary) { const measured = measureSentence({ scope: inner.summary && !inner.lastRow ? 'rois' : 'roi' }, inner); if (measured) return measured; }
  const name = stepName(inner.step, record(inner.step)?.commandId);
  if (name) return /macro/i.test(name) ? 'Ran the macro.' : `Ran ${name}.`;
  if (Array.isArray(inner.steps) && inner.steps.length) return `Ran ${plural(inner.steps.length, 'step')}.`;
  return 'Done.';
}
/** Spoken outcome built from the real result; '' stays silent, undefined asks the model to phrase or continue. */
function localSentenceFor(name: string, args: Record<string, unknown>, result: unknown): string | undefined {
  if (!result || typeof result !== 'object' || (result as { error?: unknown }).error != null) return undefined;
  const plan = awaitingConfirmation(result); if (plan) return awaitingSentence(plan);
  if (silentFinishTools.has(name)) return '';
  const inner = completedPayload(result);
  // Read and view operations return the bridge result itself, without a decision envelope.
  const raw = inner ?? record(result) ?? {};
  if (name === 'fidelis_rois') { const total = finite(raw.total); return total === undefined ? undefined : total === 0 ? 'No objects outlined yet.' : `${plural(total, 'object')}.`; }
  if (name === 'fidelis_object_stats') {
    const n = finite(raw.n); if (n === undefined) return undefined;
    if (n === 0) return 'No measured objects yet.';
    const area = record(raw.area), mean = finite(area?.mean), median = finite(area?.median);
    return `${plural(n, 'object')}${mean !== undefined ? `, mean area ${spokenArea(mean, raw.unit)}` : ''}${median !== undefined ? `, median ${spokenArea(median, raw.unit)}` : ''}.`;
  }
  if (name === 'fidelis_cancel_plan') return 'Cancelled; nothing ran.';
  if (inner === undefined) return undefined;
  if (name === 'fidelis_segment_cells' || name === 'fidelis_analyze_particles') return countSentence(inner);
  if (name === 'fidelis_measure') return measureSentence(args, inner);
  if (name === 'fidelis_threshold') return thresholdSentence(inner);
  if (name === 'fidelis_open_path') {
    const document = record(inner.document); const title = text(document?.title), width = finite(document?.width), height = finite(document?.height);
    if (!title || width === undefined || height === undefined) return undefined;
    return `Opened ${friendlyName(title, document?.path)}, ${width} by ${height}.`;
  }
  if (name === 'fidelis_undo' || name === 'fidelis_redo') {
    const undo = name === 'fidelis_undo';
    const what = stepName(inner.step)?.replace(undo ? /^undo\b[:\s-]*/i : /^redo\b[:\s-]*/i, '').trim();
    return what ? `${undo ? 'Undid' : 'Redid'} ${what}.` : undo ? 'Undone.' : 'Redone.';
  }
  if (name === 'fidelis_command') { const what = stepName(inner.step, args.commandId); return what ? `Ran ${what}.` : 'Done.'; }
  if (name === 'fidelis_filter') return 'Filter applied.';
  if (name === 'fidelis_convert') return typeof args.to === 'string' ? `Converted to ${args.to}.` : 'Converted.';
  if (name === 'fidelis_duplicate') return 'Duplicated.';
  if (name === 'fidelis_run_macro') return 'Ran the macro.';
  if (name === 'fidelis_confirm') return confirmSentence(inner);
  return undefined;
}
const analysisWords = /\b(?:measur\w*|count\w*|segment\w*|detect\w*|quantif\w*|coloc\w*|correlat\w*|overlap\w*|track\w*|analy[sz]\w*|threshold\w*|classif\w*|how many|intensit\w*|percent\w*|compar\w*|subtract\w*|project\w*|export\w*|macro)\b/;
/** Wording the local sentence cannot answer sends the turn back to the model. A plan waiting for confirmation is the
 * whole answer: nothing later in the request may run before the researcher confirms it. */
function requestContinuesAfter(name: string, utterance: string, result?: unknown): boolean {
  if (awaitingConfirmation(result)) return false;
  const said = utterance.toLowerCase();
  if (continuesGenerally(said)) return true;
  if ((silentFinishTools.has(name) || name === 'fidelis_open_path') && (analysisWords.test(said) || compoundRequest(utterance))) return true;
  if (name === 'fidelis_segment_cells' && /\b(strict\w*|loose\w*|tight\w*|sensitiv\w*|aggressive|conservative)\b/.test(said)) return false;
  if (name === 'fidelis_segment_cells') return !/\b(segment|count|find|detect|identify|outline|how many)\b/.test(said) || /\b(area|size|intens|measur|mean|bright|perimeter|circular)/.test(said)
    || /\b(positive|negative|ki-?67|marker|percent|fraction|proportion|index|express)/.test(said);
  if (name === 'fidelis_measure') return /\b(area|size|how many|count|perimeter|circular|feret|min|max|integrated|median)\b/.test(said);
  if (name === 'fidelis_threshold') return /\b(count|particles?|how many|cells?|nuclei|segment\w*|strict\w*|loose\w*|tight\w*|sensitiv\w*|aggressive|conservative|more|less|higher|lower|again|redo)\b/.test(said);
  if (name === 'fidelis_analyze_particles') return /\b(intens|mean gray|brightness)\b/.test(said);
  if (name === 'fidelis_rois') return !/\b(how many|count|number of)\b/.test(said);
  if (name === 'fidelis_object_stats') return /\b(intens|bright|circular|round|shape|largest|smallest|biggest|total)/.test(said);
  return false;
}
const lengthWords = (unit: string) => /^(?:microns?|[µμu]m)$/i.test(unit) ? 'microns' : unit;
/** "What am I looking at?" from the live document metadata alone: instant, and only what Fidelis reports (no guessed biology). */
export function describeDocument(context: unknown): string | undefined {
  const c = context as FidelisContext | undefined; const doc = c?.document; if (!doc) return undefined;
  const width = finite(doc.width), height = finite(doc.height), depth = finite(doc.bitDepth); if (width === undefined || height === undefined) return undefined;
  const channels = finite(doc.channels) ?? 1, slices = finite(doc.slices) ?? 1, frames = finite(doc.frames) ?? 1;
  const rgb = depth === 24;
  const parts = [`${friendlyName(String(doc.title ?? 'This image'), doc.path)}: ${width} by ${height}${rgb ? ', RGB colour' : depth ? `, ${depth}-bit` : ''}`];
  if (channels > 1) parts.push(`${channels} channels`); else if (!rgb) parts.push('one channel');
  if (frames > 1) parts.push(`a time-lapse of ${frames} frames`);
  if (slices > 1) parts.push(`a ${slices}-slice z-stack`);
  const unit = text(doc.unit) ?? 'pixel', px = finite(doc.pixelWidth), py = finite(doc.pixelHeight);
  if (px !== undefined && !/^pixels?$/i.test(unit)) {
    const spoken = (value: number) => { const rounded = new Intl.NumberFormat('en-US', { maximumSignificantDigits: 3, useGrouping: false }).format(value); return `${Number(rounded) === value ? '' : 'about '}${rounded}`; };
    parts.push(py === undefined || px === py ? `${spoken(px)} ${lengthWords(unit)} per pixel` : `${spoken(px)} by ${spoken(py)} ${lengthWords(unit)} per pixel (horizontal by vertical)`);
  }
  const objects = finite(c?.counts?.rois);
  if (objects) parts.push(`${objects} objects outlined`);
  const sentence = `${parts.join('; ')}.`;
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
}
const confirmWords = /^(?:(?:yes|yeah|yep|yup|sure)(?: please)?(?: (?:go ahead|do it|run it|go|confirm|proceed|thanks|thank you))?|go ahead|go for it|do it|run it|confirm(?: it| that| the plan)?|confirmed|proceed|confirm and run)$/;
const cancelWords = /^(?:no|nope|nah|no thanks|no thank you|cancel(?: it| that| this| the plan)?|no cancel(?: it| that)?|stop(?: that| it)?|don't(?: run it| do it| do that)?|do not run it|no don't|never ?mind|forget it)$/;
/** Whole-utterance instant routes: confirm or cancel a pending plan, open, describe, zoom, count, brightness, find the nuclei, slices, undo and redo. */
function fastRoute(spokenText: string, context: unknown): FastRoute | undefined {
  const said = cleanUtterance(spokenText);
  const state = context as FidelisContext | undefined;
  // A plan waiting for confirmation is answered by "yes" or "no" before anything else (docs/FIDELIS-PROTOCOL.md, Confirmation).
  const pending = record(state?.pending);
  if (pending) {
    const planId = text(pending.planId);
    if (confirmWords.test(said)) return { tool: 'fidelis_confirm', arguments: planId ? { planId } : {}, reply: 'Running it.' };
    if (cancelWords.test(said)) return { tool: 'fidelis_cancel_plan', arguments: {}, reply: 'Cancelled; nothing ran.' };
  }
  // "Open the hela cells image": the host resolves imageQuery with fidelis_find_images; an already-open document is focused instead.
  const raw = spokenText.toLowerCase().replace(/'s\b/g, 's').replace(/[.!?,]/g, ' ').replace(/\s+/g, ' ').trim();
  const opening = raw.match(/^(?:(?:hey )?(?:fidelis|fiji|penny)\s+)?(?:please |can you |could you )?(?:open|opened|load|bring up|pull up)(?: up)? (?:the |my |that )?(.+?)(?: (?:image|picture|file|movie|video|stack|slide|sample))?(?: please)?$/);
  if (opening && !/\b(and|then|also)\b/.test(opening[1]) && !/^(?:a|an|new)\b/.test(opening[1]) && opening[1].length >= 2) {
    const words = queryWords(opening[1]);
    const open = Array.isArray(state?.documents) ? state!.documents : [];
    const squash = (value: string) => value.toLowerCase().replace(/[^a-z0-9]+/g, '');
    const canonical = (value: string) => squash(queryWords(value).join(' '));
    const requested = canonical(opening[1]);
    const exactTitle = open.find(entry => typeof entry.title === 'string' && canonical(entry.title) === requested);
    const exactStem = open.find(entry => typeof entry.title === 'string' && canonical(entry.title.replace(/\.(?:ome\.)?[^.]+$/i, '')) === requested);
    const hit = exactTitle ?? exactStem ?? (words.length ? open.find(entry => typeof entry.title === 'string' && imageNameMatches(entry.title, opening[1])
      && words.filter(word => /\d$/.test(word)).every(word => new RegExp(`${squash(word)}(?!\\d)`).test(squash(entry.title as string)))) : undefined);
    if (hit && typeof hit.title === 'string') {
      const name = friendlyName(hit.title);
      if (hit.focused === true) return { tool: 'fidelis_context', arguments: {}, reply: `${name.charAt(0).toUpperCase()}${name.slice(1)} is already open.` };
      if (typeof hit.id === 'string') return { tool: 'fidelis_activate_image', arguments: { id: hit.id }, reply: `Switched to ${name}.` };
    }
    // ImageJ's built-in samples are catalogue commands (File > Open Samples), which the model finds with fidelis_commands.
    if (/^blobs(?: sample)?$/.test(opening[1]) && !hit) return undefined;
    return { tool: 'fidelis_open_path', arguments: {}, reply: 'Opening it.', imageQuery: opening[1] };
  }
  const doc = state?.document;
  if (!doc) return undefined;
  if (/^(?:so )*(?:what am i looking at|what is this(?: image)?|what's this(?: image)?|describe (?:this|the) image|tell me about (?:this|the) image|what (?:kind of )?image is this)$/.test(said)) {
    const described = describeDocument(context); if (described) return { tool: 'fidelis_context', arguments: {}, reply: described };
  }
  // A fast action is the whole request, never just the first half of a spoken chain.
  if (continuesGenerally(said.replace(/^(?:and|also)\s+/, ''))) return undefined;
  const zoom = said.match(/^zoom (in|out)(?: (a (?:little|bit)|slightly|a lot|way (?:in|out)|more))?$/);
  if (zoom) return { tool: 'fidelis_zoom', arguments: { direction: zoom[1] }, reply: zoom[1] === 'in' ? 'Zooming in.' : 'Zooming out.' };
  if (/^(?:zoom to fit|fit (?:to|the) (?:window|screen|view)|fit to window)$/.test(said)) return { tool: 'fidelis_zoom', arguments: { fit: true }, reply: 'Fitting to the window.' };
  if (/^zoom (?:in )?(?:to|on) (?:the |this |my )?(?:selection|selected region|roi)$/.test(said)) return { tool: 'fidelis_zoom', arguments: { selection: true }, reply: 'Zooming to the selection.' };
  // Threshold strictness is answered deterministically by re-segmenting with a stricter or looser method.
  const strictness = said.match(/^(?:(?:now |please )?(?:make (?:it|that|the threshold|the segmentation) )?)(?:a (?:bit|little) )?(stricter|tighter|more strict|more conservative|less sensitive|looser|more sensitive|less strict|more aggressive)(?: threshold)?$/);
  if (strictness) {
    const stricter = /strict(?!.*less)|tight|conservative|less sensitive/.test(strictness[1]) && !/less strict/.test(strictness[1]);
    return { tool: 'fidelis_segment_cells', arguments: { method: stricter ? 'Li' : 'Huang' }, reply: stricter ? 'Segmenting with a stricter threshold.' : 'Segmenting with a looser threshold.' };
  }
  const objects = finite(state?.counts?.rois);
  // "How many?" reads the ROI list (the segmentation output); re-segmenting would discard the researcher's corrections.
  if (/^(?:so |and )?how many(?: (?:cells|objects|particles|nuclei|blobs|of them))?(?: are)?(?: there| left| now| remaining| do (?:we|i) have| did (?:you|that) (?:find|get|keep))?(?: now)?$/.test(said))
    return objects !== undefined ? { tool: 'fidelis_rois', arguments: { limit: 1 }, reply: 'Counting.' } : { tool: 'fidelis_object_stats', arguments: {}, reply: 'Counting.' };
  if (objects && /^(?:show(?: me)?|highlight|outline|point out) (?:all )?(?:of )?(?:the )?(?:nuclei|cells|objects|detections)(?: again| here)?$/.test(said)) return { tool: 'fidelis_highlight', arguments: { rois: 'all', label: `${objects} objects` }, reply: 'Here they are.' };
  // "What is the mean intensity of the nuclei?" measures the existing objects instead of re-segmenting.
  if (objects && /\b(intensit\w*|brightness|how bright|signal)\b/.test(said) && /\b(nuclei|nucleus|cells?|objects?|them|those|each)\b/.test(said) && !/\b(over time|frame|change|channel \w+|green|red|blue|positive|threshold)\b/.test(said))
    return { tool: 'fidelis_measure', arguments: { scope: 'rois', measurements: ['area', 'mean', 'min', 'integrated'] }, reply: 'Measuring them.' };
  // Brightness is display-only: move the display range from the live values, never filter the pixels.
  const range = Array.isArray(doc.displayRange) ? doc.displayRange : [doc.display?.min, doc.display?.max];
  if (range.length === 2 && range.every(value => finite(value) !== undefined)) {
    const [low, high] = range as [number, number], span = Math.max(1e-6, high - low);
    if (/^(?:make (?:it|the image|this) |a (?:bit|little) )*(?:a (?:bit|little) )?(brighter|lighter)$/.test(said)) return { tool: 'fidelis_display', arguments: { min: low, max: low + span * 0.7 }, reply: 'Brighter.' };
    if (/^(?:make (?:it|the image|this) |a (?:bit|little) )*(?:a (?:bit|little) )?darker$/.test(said)) return { tool: 'fidelis_display', arguments: { min: low, max: low + span * 1.4 }, reply: 'Darker.' };
  }
  if (/^(?:(?:auto|fix|improve|boost|enhance|more) (?:the )?contrast|auto(?:matic)? (?:brightness|contrast)|make (?:it|this) (?:clearer|easier to see|pop))$/.test(said)) return { tool: 'fidelis_display', arguments: { auto: true }, reply: 'Auto contrast.' };
  // "Find all the nuclei" is the whole request: the bridge runs its segmentation plan on the focused document.
  if (/^(?:now )*(?:find|segment|detect|identify|outline|count|show me|highlight) (?:all )?(?:of )?(?:the )?(?:nuclei|nucleus|cells|cell nuclei)(?: here)?$/.test(said)) return { tool: 'fidelis_segment_cells', arguments: {}, reply: 'Finding the nuclei.' };
  const slice = said.match(/^(next|previous|prev) slice$/);
  if (slice) {
    const slices = finite(doc.slices), current = finite(doc.position?.slice);
    if (slices === undefined || current === undefined || slices < 2) return undefined;
    const next = slice[1] === 'next' ? current + 1 : current - 1;
    if (next < 1 || next > slices) return undefined;
    return { tool: 'fidelis_stack', arguments: { slice: next }, reply: slice[1] === 'next' ? 'Next slice.' : 'Previous slice.' };
  }
  if (/^undo(?: that| it| the last step)?$|^go back$|^take that back$/.test(said)) return { tool: 'fidelis_undo', arguments: {}, reply: 'Undoing.' };
  if (/^redo(?: that| it)?$|^go forward$|^put it back$/.test(said)) return { tool: 'fidelis_redo', arguments: {}, reply: 'Redoing.' };
  return undefined;
}
/** Segmentation tuning the bridge picks itself; the model tends to fill every optional field. Kept only when the researcher mentions it. */
const expertArguments: Record<string, string[]> = { fidelis_segment_cells: ['channel', 'method', 'minSize', 'watershed'] };
const mentionedBy: Record<string, RegExp> = {
  channel: /\b(channel|red|green|blue|dapi|hoechst|gfp|nuclear|c\d)\b/, minSize: /\b(size|smaller|bigger|larger|area|pixels?|microns?)\b/, watershed: /\bwatershed\b/,
  method: /\b(otsu|li|huang|triangle|default|yen|moments|maxentropy|isodata|mean|minimum|intermodes|renyi|shanbhag|percentile|strict\w*|loose\w*|sensitiv\w*|threshold)\b/,
};
function modelArguments(name: string, args: Record<string, unknown>, utterance: string): Record<string, unknown> {
  const expert = expertArguments[name]; if (!expert) return args;
  const said = utterance.toLowerCase(), numbers = new Set((said.match(/\d+(?:\.\d+)?/g) ?? []).map(Number));
  const kept: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(args)) {
    if (!expert.includes(key)) { kept[key] = value; continue; }
    const mentioned = (mentionedBy[key]?.test(said) ?? said.includes(key.toLowerCase())) || typeof value === 'number' && numbers.has(value) || typeof value === 'string' && value.length > 1 && said.includes(value.toLowerCase());
    if (mentioned) kept[key] = value;
  }
  return kept;
}
/** `fidelis_confirm` is bound to the plan observed now: the pending planId is filled in (or checked), so a plan that
 * replaced it in the meantime is never run by an earlier "yes". */
async function prepareAction(input: PrepareInput) {
  if (input.name !== 'fidelis_confirm') return;
  const pending = record((input.context as FidelisContext).pending);
  const planId = text(pending?.planId);
  if (!planId) throw new Error('Nothing is waiting for confirmation.');
  if (input.args.planId === undefined) input.args.planId = planId;
  else if (input.args.planId !== planId) throw new Error('That plan is no longer the one waiting; check the plan card in Fidelis.');
}

export const fidelis: AppProfile = {
  id: 'fidelis', displayName: 'Fidelis', bundleId: FIDELIS_BUNDLE_ID, bridgePort: 49558, toolPrefix: 'fidelis',
  tools: fidelisTools, instructions: fidelisInstructions,
  backendNotes: 'Relative view requests use fidelis_zoom: direction in/out for "zoom in/out", fit true for "fit to window", selection true for "zoom to the selection"; "next/previous slice" uses fidelis_stack with a 1-based slice number. A data result with status awaiting_confirmation has not run: say in one sentence that it waits for Confirm or "yes", and stop.',
  skillDir: 'skills/fidelis', knowledgeSources: ['skills/fidelis', '.local/upstream/imagej-docs'], apiSource: false,
  // Fidelis is built by its own tools; there is no separate bridge artifact or build manifest (bridgeIdentity 'operations').
  launcherScript: './script/run-fidelis.sh', bridgeJar: 'fidelis-app/build/fidelis-app.jar', buildManifest: '', buildScript: 'python3 -I fidelis-app/tools/build.py --no-tests',
  doctorArtifacts: [['fidelis-app/build/fidelis-app.jar', 'Fidelis build'], ['.local/fiji/Fiji.app/jars', 'Fiji runtime for Fidelis']],
  bridgeIdentity: 'operations', conversationEnvelope: true,
  fastRoute, acknowledgmentFor, localSentenceFor, requestContinuesAfter, silentFinishTools,
  pausesRequest: (_name, result) => { const plan = awaitingConfirmation(result); return plan ? awaitingSentence(plan) : undefined; },
  longRunningTools: new Set(['fidelis_segment_cells', 'fidelis_analyze_particles', 'fidelis_command', 'fidelis_confirm', 'fidelis_open_path']),
  prepareAction, modelArguments,
};
