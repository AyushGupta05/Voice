import type { AppProfile, FastRoute, PrepareInput } from './profile.js';
import { bindUiControl, cleanUtterance, completedPayload, continuesGenerally, finite, highlightAcknowledgment } from './profile.js';
import { qupathTools } from './qupath-tools.js';
import { qupathInstructions } from './qupath-instructions.js';
import type { Runtime } from '../runtime.js';
import type { Arguments } from '../tools.js';

/** Immediate spoken phrase for an instant view tool, derived from the call itself (no extra model round). */
function acknowledgmentFor(name: string, args: Record<string, unknown>, context: unknown): string | undefined {
  const viewer = (context as { viewer?: { downsample?: unknown } } | undefined)?.viewer;
  if (name === 'qupath_focus') {
    const next = Number(args.downsample), current = Number(viewer?.downsample);
    if (Number.isFinite(next) && Number.isFinite(current) && next > current * 1.05) return 'Zooming out.';
    if (Number.isFinite(next) && Number.isFinite(current) && next < current / 1.05) return 'Zooming in.';
    return args.objectId ? 'Taking you there.' : 'Moving there.';
  }
  if (name === 'qupath_highlight') return highlightAcknowledgment(args);
  if (name === 'qupath_overlays') return 'Updating the view.';
  if (name === 'qupath_detect_cells') return 'Detecting cells.';
  if (name === 'qupath_classify') return 'Classifying.';
  if (name === 'qupath_annotate') return 'Drawing the region.';
  if (name === 'qupath_delete_selected') return 'Deleting them.';
  if (name === 'qupath_restore') return 'Restoring.';
  if (name === 'qupath_open_path' || name === 'qupath_open_project_image') return 'Opening it.';
  if (name === 'qupath_run_command' || name === 'qupath_ui_action') return 'On it.';
  if (name === 'qupath_run_script') return 'Running the script.';
  if (name === 'qupath_image_type') return 'Setting the image type.';
  if (name === 'qupath_import_annotations') return 'Importing.';
  if (name === 'qupath_save_workflow') return 'Saving the workflow.';
  if (name === 'qupath_class_summary') return 'Counting.';
  if (name === 'qupath_find_images') return 'Finding it.';
  if (name === 'qupath_select') return 'Selecting them.';
  if (name === 'qupath_measurement_summary' || name === 'qupath_measurements' || name === 'qupath_objects') return 'Checking.';
  return undefined;
}
const silentFinishTools = new Set(['qupath_focus', 'qupath_highlight', 'qupath_overlays', 'qupath_select']);
/** The spoken sentence for a successful tool call, built from its real result with no model round.
 * '' means nothing more to say; undefined means the model must phrase (or continue) the answer. */
function localSentenceFor(name: string, args: Record<string, unknown>, result: unknown): string | undefined {
  // Runtime envelopes always carry an error key; only a present error means failure.
  if (!result || typeof result !== 'object' || (result as { error?: unknown }).error != null) return undefined;
  if (silentFinishTools.has(name)) return '';
  if (name === 'qupath_class_summary') {
    const summary = (result as { summary?: { total?: unknown; classes?: unknown } }).summary;
    const total = finite(summary?.total); const classes = Array.isArray(summary?.classes) ? summary.classes : undefined;
    if (total === undefined || !classes) return undefined;
    if (total === 0) return 'No objects to count.';
    const parts = classes.filter(row => row && typeof row === 'object' && finite(row.count) !== undefined)
      .sort((a, b) => Number(b.count) - Number(a.count)).slice(0, 3)
      .map(row => `${typeof row.classification === 'string' && row.classification.trim() ? row.classification : 'unclassified'} ${row.count}${finite(row.percent) !== undefined ? ` (${Math.round(row.percent)}%)` : ''}`);
    return parts.length ? `${total} objects: ${parts.join(', ')}.` : `${total} objects.`;
  }
  // Data tools return the Runtime decision envelope; only a completed policy decision has a real result.
  const inner = completedPayload(result);
  if (inner === undefined && (result as { status?: unknown }).status !== 'completed') return undefined;
  if (name === 'qupath_annotate') {
    const classification = typeof args.classification === 'string' ? args.classification.trim() : '';
    return `Added a ${classification && Array.from(classification).length <= 40 ? classification : 'new'} region.`;
  }
  if (name === 'qupath_detect_cells') {
    const cells = finite((inner?.counts as { cells?: unknown } | undefined)?.cells);
    return cells === undefined ? undefined : `Detected ${cells} cells.`;
  }
  if (name === 'qupath_classify') {
    const positive = finite(inner?.positive), negative = finite(inner?.negative);
    if (positive === undefined || negative === undefined || positive + negative === 0) return undefined;
    return `${positive} of ${positive + negative} cells are positive (${Math.round(positive * 100 / (positive + negative))}%).`;
  }
  const counts = (inner?.counts && typeof inner.counts === 'object' ? inner.counts : undefined) as Record<string, unknown> | undefined;
  const describeCounts = () => {
    if (!counts) return '';
    const cells = finite(counts.cells), positive = finite(counts['class:Positive']), annotations = finite(counts.annotations);
    if (cells !== undefined && positive !== undefined) return ` ${positive} of ${cells} cells positive.`;
    if (cells !== undefined) return ` ${cells} cells.`;
    if (annotations !== undefined) return ` ${annotations} annotation${annotations === 1 ? '' : 's'}.`;
    return '';
  };
  if (name === 'qupath_restore') return `Went back to the earlier result.${describeCounts()}`;
  if (name === 'qupath_delete_objects') { const removed = finite(inner?.deleted ?? inner?.removed); return removed === undefined ? `Deleted them.${describeCounts()}` : `Deleted ${removed} object${removed === 1 ? '' : 's'}.`; }
  if (name === 'qupath_export_measurements' || name === 'qupath_export_objects' || name === 'qupath_export_image') {
    const path = typeof inner?.path === 'string' ? inner.path.split('/').pop() : undefined; const rows = finite(inner?.rows ?? inner?.objects);
    return path ? `Exported${rows !== undefined ? ` ${rows} rows` : ''} to ${path}.` : 'Exported.';
  }
  if (name === 'qupath_save') return 'Saved.';
  if (name === 'qupath_detect_positive_cells') { const cells = finite(counts?.cells), positive = finite(counts?.['class:Positive']); return cells === undefined ? undefined : positive === undefined ? `Detected ${cells} cells.` : `Detected ${cells} cells, ${positive} positive (${Math.round(positive * 100 / cells)}%).`; }
  if (name === 'qupath_image_type') return 'Image type set.';
  if (name === 'qupath_set_stains' || name === 'qupath_estimate_stains') return 'Stain vectors updated.';
  if (name === 'qupath_display') return 'Display updated.';
  return undefined;
}
/** A request whose wording asks for more than this call delivers must go back to the model. */
function requestContinuesAfter(name: string, utterance: string): boolean {
  const said = utterance.toLowerCase();
  if (continuesGenerally(said)) return true;
  if (name === 'qupath_detect_cells') return !/\b(detect|segment|find|identify)\b/.test(said) || /\b(analy|count|positiv|percent|classif|threshold|stain)/.test(said);
  if (name === 'qupath_annotate') return /\b(analy|count|detect|cells?|positiv|percent)\b/.test(said);
  if (name === 'qupath_classify') return /\b(summar|breakdown)/.test(said);
  return false;
}
/** Whole-utterance instant routes: zoom steps around the viewer centre and overlay toggles. */
function fastRoute(text: string, context: unknown): FastRoute | undefined {
  const said = cleanUtterance(text);
  const viewer = (context as { viewer?: { centerX?: unknown; centerY?: unknown; downsample?: unknown }; image?: unknown } | undefined);
  const zoom = said.match(/^zoom (in|out)(?: (a (?:little|bit)|slightly|a lot|way (?:in|out)|more))?$/);
  if (zoom) {
    const x = Number(viewer?.viewer?.centerX), y = Number(viewer?.viewer?.centerY), current = Number(viewer?.viewer?.downsample);
    if (!viewer?.image || ![x, y, current].every(Number.isFinite) || current <= 0) return undefined;
    const factor = /lot|way/.test(zoom[2] ?? '') ? 4 : 2;
    const next = Math.min(256, Math.max(0.05, zoom[1] === 'in' ? current / factor : current * factor));
    if (Math.abs(next - current) < 1e-9) return undefined;
    return { tool: 'qupath_focus', arguments: { x, y, downsample: next }, reply: zoom[1] === 'in' ? 'Zooming in.' : 'Zooming out.' };
  }
  if (!viewer?.image) return undefined;
  const toggle = said.match(/^(show|hide|turn (?:on|off)) (?:the |all )?(cells|detections|annotations)$/);
  if (toggle) {
    const on = toggle[1] === 'show' || toggle[1] === 'turn on';
    const key = toggle[2] === 'annotations' ? 'annotations' : 'detections';
    return { tool: 'qupath_overlays', arguments: { [key]: on }, reply: `${on ? 'Showing' : 'Hiding'} ${toggle[2]}.` };
  }
  const fill = said.match(/^(fill|unfill|outline) (?:the )?(cells|detections)$/);
  if (fill) return { tool: 'qupath_overlays', arguments: { detections: true, fill: fill[1] === 'fill' }, reply: fill[1] === 'fill' ? 'Filling cells.' : 'Showing outlines.' };
  return undefined;
}
/** Pre-dispatch checks that bind a proposal to what was observed (dialog control, import file, candidate count, project entry). */
async function prepareAction(input: PrepareInput) {
  const { name, args, context, adapter, check } = input;
  if (name === 'qupath_ui_action') await bindUiControl(input, 'QuPath');
  if (name === 'qupath_import_annotations') {
    const preview = await adapter.execute('inspect_import', { path: args.path }, { expectedContext: context.contextKey }) as Record<string, unknown>;
    check();
    if (preview.sha256 !== args.sha256) throw new Error('Import file changed since inspection. Inspect and review again.');
    context.reviewTarget = { type: 'GeoJSON annotations', text: `${preview.annotations} annotations in full-resolution image pixels`, ...preview };
  }
  if (name === 'qupath_classify') {
    const filter: Arguments = { kind: args.kind === 'cell' ? 'cell' : 'detection', limit: 1 };
    for (const key of ['scope', 'classification', 'ids']) if (args[key] !== undefined) filter[key] = args[key];
    const candidates = await adapter.execute('objects', filter, { expectedContext: context.contextKey }) as { total?: unknown };
    check();
    if (!Number.isSafeInteger(candidates.total) || Number(candidates.total) < 0) throw new Error('Cannot verify the matching detection count. Inspect the scope before classification.');
    const count = Number(candidates.total);
    if (count === 0) throw new Error(args.classification
      ? 'No detections match this existing-class filter. classification filters CURRENT labels; omit it only if the researcher requested thresholding all matching detections. No action was prepared.'
      : 'No detections match the requested scope. Inspect the selected region and existing detections before classification. No action was prepared.');
    context.reviewTarget = { type: 'Threshold classification', candidateCount: count, measurement: args.measurement, threshold: args.threshold,
      existingClassFilter: args.classification || null, assignedClasses: ['Positive', 'Negative'],
      text: `${count} matching detection${count === 1 ? '' : 's'}${args.classification ? ` (current class: ${args.classification})` : ''}; assign Positive / Negative` };
  }
  if (name === 'qupath_open_project_image') {
    const listing = await adapter.execute('project_images', { entryId: args.entryId }, { expectedContext: context.contextKey }) as { images: Array<Record<string, unknown>> };
    check();
    const entry = listing.images.find(entry => entry.id === args.entryId);
    if (!entry || entry.fingerprint !== args.entryFingerprint) throw new Error('Project entry changed. Refresh project_images and review again.');
    context.reviewTarget = { type: 'Project image', text: entry.name, ...entry };
  }
}
/** After opening an image, QuPath may ask for the image type. Accept its own preselected stain so the
 * researcher is never left looking at a modal, and tell the model what happened. */
async function settleImageTypeDialog(runtime: Runtime, signal?: AbortSignal): Promise<string | undefined> {
  type Control = { id?: string; type?: string; text?: string; selected?: boolean; disabled?: boolean };
  type Ui = { windows?: Array<{ title?: string; controls?: Control[] }> };
  for (let attempt = 0; attempt < 12; attempt++) {
    if (signal?.aborted) return undefined;
    await new Promise(resolve => setTimeout(resolve, 500));
    let ui: Ui | undefined;
    try { ui = await runtime.adapter.execute('ui') as Ui; } catch { return undefined; }
    const dialog = ui?.windows?.find(window => window.title === 'Set image type');
    if (!dialog) {
      // Give the reader a moment to raise the dialog before deciding none is coming.
      if (attempt >= 3) return undefined;
      continue;
    }
    const chosen = dialog.controls?.find(control => control.type === 'ToggleButton' && control.selected)?.text?.replace(/\s+/g, ' ');
    const apply = dialog.controls?.find(control => control.type === 'Button' && control.text === 'Apply' && !control.disabled);
    if (!apply?.id) return 'A "Set image type" dialog is open but has no enabled Apply button; inspect it with qupath_ui.';
    try {
      const outcome = await runtime.call('qupath_ui_action', { nodeId: apply.id, action: 'fire' }, signal) as { status?: unknown };
      return outcome?.status === 'completed' ? `Applied QuPath's preselected image type${chosen ? ` (${chosen})` : ''}.` : 'Could not apply the image type; inspect the dialog with qupath_ui.';
    } catch (error) { return `Image type dialog is open; applying failed: ${error instanceof Error ? error.message : String(error)}`; }
  }
  return undefined;
}

export const qupath: AppProfile = {
  id: 'qupath', displayName: 'QuPath', bundleId: 'science.luma.qupath', bridgePort: 49556, toolPrefix: 'qupath',
  tools: qupathTools, instructions: qupathInstructions,
  backendNotes: 'Relative view requests (zoom in/out, pan) use qupath_focus with qupathObservation.viewer centerX/centerY and a new downsample: larger zooms out, smaller zooms in, ×2 for "a little" and ×4 for "a lot".',
  skillDir: 'skills/qupath', knowledgeSources: ['skills/qupath', '.local/upstream/qupath-docs/docs'], apiSource: true,
  launcherScript: './script/run-qupath.sh', bridgeJar: 'dist/luma-qupath-bridge.jar', buildManifest: '.local/bridge-build.json', buildScript: 'script/build-bridge.sh',
  doctorArtifacts: [['dist/luma-qupath-bridge.jar', 'QuPath bridge build'], ['dist/QuPathLuma.app/Contents/MacOS/QuPath-0.7.0-arm64', 'QuPath launcher']],
  fastRoute, acknowledgmentFor, localSentenceFor, requestContinuesAfter, silentFinishTools,
  longRunningTools: new Set(['qupath_detect_cells', 'qupath_run_script', 'qupath_run_command']),
  prepareAction,
  async settleDialogs(runtime, name, _args, signal) {
    if (name !== 'qupath_open_path' && name !== 'qupath_open_project_image') return undefined;
    const dialog = await settleImageTypeDialog(runtime, signal);
    return dialog ? { imageTypeDialog: dialog } : undefined;
  },
};
