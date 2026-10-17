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
};
function acknowledgmentFor(name: string, args: Record<string, unknown>): string | undefined {
  if (name === 'fiji_highlight') return highlightAcknowledgment(args);
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
      const summary = inner?.summary as Record<string, { mean?: unknown }> | undefined;
      const mean = finite(summary?.mean?.mean), rows = finite(inner?.rows);
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
    const image = inner?.image as { title?: unknown; width?: unknown; height?: unknown } | undefined;
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
  return undefined;
}
/** Wording the local sentence cannot answer sends the turn back to the model. */
function requestContinuesAfter(name: string, utterance: string): boolean {
  const said = utterance.toLowerCase();
  if (continuesGenerally(said)) return true;
  if (name === 'fiji_segment_cells') return !/\b(segment|count|find|detect|identify|how many)\b/.test(said) || /\b(area|size|intens|measur|mean|bright|perimeter|circular)/.test(said);
  if (name === 'fiji_measure') return /\b(area|size|how many|count|perimeter|circular|feret|min|max|integrated|median)\b/.test(said);
  if (name === 'fiji_threshold') return /\b(count|particle|how many|cells?|segment)\b/.test(said);
  if (name === 'fiji_analyze_particles') return /\b(intens|mean gray|brightness)\b/.test(said);
  return false;
}
/** Whole-utterance instant routes from the protocol: zoom, fit, overlay, slices and undo. */
function fastRoute(text: string, context: unknown): FastRoute | undefined {
  const said = cleanUtterance(text);
  const image = (context as { image?: { slices?: unknown } } | undefined)?.image;
  if (!image) return undefined;
  const zoom = said.match(/^zoom (in|out)(?: (a (?:little|bit)|slightly|a lot|way (?:in|out)|more))?$/);
  if (zoom) return { tool: 'fiji_zoom', arguments: { direction: zoom[1] }, reply: zoom[1] === 'in' ? 'Zooming in.' : 'Zooming out.' };
  if (/^(?:zoom to fit|fit (?:to|the) (?:window|screen|view)|fit to window)$/.test(said)) return { tool: 'fiji_zoom', arguments: { fit: true }, reply: 'Fitting to the window.' };
  const overlay = said.match(/^(show|hide) (?:the )?(?:overlay|outlines)$/);
  if (overlay) return { tool: 'fiji_overlay', arguments: { show: overlay[1] === 'show' }, reply: overlay[1] === 'show' ? 'Showing the overlay.' : 'Hiding the overlay.' };
  const slice = said.match(/^(next|previous|prev|last) slice$/);
  if (slice) {
    const slices = finite(image.slices);
    if (slices !== undefined && slices < 2) return undefined;
    const direction = slice[1] === 'next' ? 'next' : 'previous';
    return { tool: 'fiji_stack', arguments: { slice: direction }, reply: direction === 'next' ? 'Next slice.' : 'Previous slice.' };
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
  for (let attempt = 0; attempt < 12; attempt++) {
    if (signal?.aborted) return undefined;
    await new Promise(resolve => setTimeout(resolve, 500));
    let ui: Ui | undefined;
    try { ui = await runtime.adapter.execute('ui') as Ui; } catch { return undefined; }
    const dialog = ui?.windows?.find(window => importDialog(window.title ?? ''));
    if (!dialog) { if (attempt >= 3) return undefined; continue; }
    const ok = dialog.controls?.find(control => /^button$/i.test(control.type ?? '') && /^ok$/i.test((control.text ?? control.label ?? '').trim()) && control.enabled !== false && !control.disabled);
    if (!ok?.id) return `A "${dialog.title}" dialog is open without an enabled OK button; inspect it with fiji_ui.`;
    try {
      const outcome = await runtime.call('fiji_ui_action', { nodeId: ok.id, action: 'fire' }, signal) as { status?: unknown };
      return outcome?.status === 'completed' ? `Confirmed the "${dialog.title}" dialog with its default settings.` : `Could not confirm the "${dialog.title}" dialog; inspect it with fiji_ui.`;
    } catch (error) { return `The "${dialog.title}" dialog is open; confirming it failed: ${error instanceof Error ? error.message : String(error)}`; }
  }
  return undefined;
}

export const fiji: AppProfile = {
  id: 'fiji', displayName: 'Fiji', bundleId: FIJI_BUNDLE_ID, bridgePort: 49557, toolPrefix: 'fiji',
  tools: fijiTools, instructions: fijiInstructions,
  backendNotes: 'Relative view requests use fiji_zoom: direction in/out for "zoom in/out", fit true for "fit to window", magnification for an exact factor; "next/previous slice" uses fiji_stack with slice "next"/"previous".',
  skillDir: 'skills/fiji', knowledgeSources: ['skills/fiji', '.local/upstream/imagej-docs'], apiSource: false,
  launcherScript: './script/run-fiji.sh', bridgeJar: 'dist/luma-fiji-bridge.jar', buildManifest: '.local/fiji-bridge-build.json', buildScript: 'script/build-fiji-bridge.sh',
  doctorArtifacts: [['dist/luma-fiji-bridge.jar', 'Fiji bridge build'], ['.local/fiji/Fiji.app/Contents/MacOS/ImageJ-macosx', 'Fiji launcher']],
  fastRoute, acknowledgmentFor, localSentenceFor, requestContinuesAfter, silentFinishTools,
  longRunningTools: new Set(['fiji_segment_cells', 'fiji_run_macro', 'fiji_run_script', 'fiji_run_command']),
  prepareAction,
  async settleDialogs(runtime, name, args, signal) {
    // Only operations that import an image get their dialog confirmed; other commands keep their dialogs for the model.
    const importing = name === 'fiji_open_path' || (name === 'fiji_run_command' && /import|^open\b/i.test(String(args.name ?? '')));
    if (!importing) return undefined;
    const dialog = await settleOpenDialog(runtime, signal);
    return dialog ? { dialog } : undefined;
  },
};
