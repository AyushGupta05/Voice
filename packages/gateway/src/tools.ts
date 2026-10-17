import AjvModule from 'ajv';

export type Arguments = Record<string, unknown>;
export type Tool = { name: string; description: string; operation?: string; risk: 'read' | 'view' | 'data' | 'code'; inputSchema: Record<string, unknown> };
const text = { type: 'string' };
const number = { type: 'number' };
const boolean = { type: 'boolean' };
const scope = { type: 'string', enum: ['all', 'selection', 'region'] };
const filter = { scope, kind: { type: 'string', enum: ['all', 'annotation', 'cell', 'detection'] }, classification: text, ids: { type: 'array', items: text, maxItems: 1000 } };
function schema(properties: Record<string, unknown> = {}, required: string[] = []) { return { type: 'object', properties, required, additionalProperties: false }; }
function tool(name: string, description: string, operation: string | undefined, risk: Tool['risk'], properties: Record<string, unknown> = {}, required: string[] = []): Tool {
  return { name: `qupath_${name}`, description, operation, risk, inputSchema: schema(properties, required) };
}
export const tools: Tool[] = [
  tool('context', 'Read the real active image/project, primary selection, full selectionCount and an explicit bounded selection preview, image type, measurements, viewer and result counts. Refresh before resolving “this”, “those” or a mutation.', 'context', 'read'),
  tool('objects', 'List real objects with IDs, ROIs and classifications. region means centroids inside the selected ROI on the same image plane. Results are paginated; total is untruncated.', 'objects', 'read', { ...filter, offset: { type: 'integer', minimum: 0 }, limit: { type: 'integer', minimum: 1, maximum: 500 } }),
  tool('measurements', 'Read measured values, never infer them from appearance. Returns at most 200 rows and an explicit truncation flag.', 'measurements', 'read', { ...filter, limit: { type: 'integer', minimum: 1, maximum: 200 } }),
  tool('select', 'Change the real selection using IDs or exact classification. Set ids to target specific objects; an empty filter selects all objects. Does not change scientific data.', 'select', 'view', filter),
  tool('focus', 'Center the real viewer on an object, current selection or image-pixel coordinates. Smaller downsample zooms in. Returns a real screen-coordinate pointer target.', 'focus', 'view', { objectId: text, x: number, y: number, downsample: { type: 'number', minimum: 0.05, maximum: 256 } }),
  tool('overlays', 'Show/hide detections and annotations, fill cells, change opacity, or show only an exact classification. Empty classification clears the filter. Changes display only.', 'overlays', 'view', { detections: boolean, annotations: boolean, fill: boolean, opacity: { type: 'number', minimum: 0, maximum: 1 }, classification: text }),
  tool('snapshot', 'Capture only the current QuPath viewer as PNG for visual inspection. No unrelated desktop content is captured.', 'snapshot', 'read'),
  tool('commands', 'Discover the installed application’s live menu command paths, including extensions and enabled state. Search before invoking an exact path. A command may open a dialog; dispatch does not prove completion.', 'commands', 'read', { query: text }),
  tool('ui', 'Inspect visible JavaFX QuPath windows and controls, including exact node IDs, text, disabled state, combo options and screen bounds. Use for native dialogs after menu dispatch. Excludes other applications and OS-owned dialogs.', 'ui', 'read'),
  tool('ui_action', 'Operate a previously observed visible QuPath JavaFX control after human approval. fire presses a button, set_text edits an input, select chooses an exact combo-box option. May have irreversible file/plugin effects; re-inspect afterward. Never bypass disabled controls.', 'ui_action', 'code', { nodeId: text, action: { type: 'string', enum: ['fire', 'set_text', 'select'] }, value: { type: 'string', maxLength: 32000 } }, ['nodeId', 'action']),
  tool('annotate', 'Create a rectangle or ellipse annotation using full-resolution image pixels on the active z/t plane. Requires human approval; a hierarchy checkpoint is made first.', 'annotate', 'data', { x: number, y: number, width: { type: 'number', exclusiveMinimum: 0 }, height: { type: 'number', exclusiveMinimum: 0 }, shape: { type: 'string', enum: ['rectangle', 'ellipse'] }, name: text }, ['x', 'y', 'width', 'height']),
  tool('detect_cells', 'Run QuPath watershed cell detection in selected annotations with explicit parameters. Check image type/calibration and read detection guidance first. Requires human approval; may take time.', 'detect', 'data', { parameters: { type: 'object', properties: { detectionImage: text, detectionImageBrightfield: text, backgroundByReconstruction: boolean, excludeDAB: boolean, maxBackground: number, backgroundRadius: number, medianRadius: number, sigma: number, minArea: number, maxArea: number, cellExpansion: number, requestedPixelSizeMicrons: number, backgroundRadiusMicrons: number, medianRadiusMicrons: number, sigmaMicrons: number, minAreaMicrons: number, maxAreaMicrons: number, threshold: number, watershedPostProcess: boolean, cellExpansionMicrons: number, includeNuclei: boolean, smoothBoundaries: boolean, makeMeasurements: boolean }, additionalProperties: false } }, ['parameters']),
  tool('classify', 'Classify detections Positive when the named existing measurement is >= threshold, Negative otherwise. Exact scope is shown for human approval. This replaces existing object classes, not a biological diagnosis.', 'classify', 'data', { ...filter, measurement: text, threshold: number }, ['measurement', 'threshold']),
  tool('image_type', 'Set the image interpretation after checking metadata (e.g. BRIGHTFIELD_H_E, BRIGHTFIELD_H_DAB, FLUORESCENCE). Requires approval. Hierarchy undo does not restore this setting.', 'image_type', 'data', { type: { type: 'string', enum: ['BRIGHTFIELD_H_E', 'BRIGHTFIELD_H_DAB', 'BRIGHTFIELD_OTHER', 'FLUORESCENCE', 'OTHER', 'UNSET'] } }, ['type']),
  tool('checkpoint', 'Save an in-memory object hierarchy checkpoint for this image (maximum 6, 200k objects / 64MiB each). Does not save files, viewer settings or external script effects.', 'checkpoint', 'read', { label: text }),
  tool('checkpoints', 'List recoverable in-memory hierarchy checkpoints and whether they belong to the active image.', 'checkpoints', 'read'),
  tool('compare', 'Compare exact object and classification counts now against a named checkpoint for the same image. Returns baseline/current/delta.', 'compare', 'read', { checkpointId: text }, ['checkpointId']),
  tool('restore', 'Replace the current image’s object hierarchy with a checkpoint. Requires approval and creates a checkpoint of the current hierarchy first. Not a general undo for files or settings.', 'restore', 'data', { checkpointId: text }, ['checkpointId']),
  tool('delete_selected', 'Remove only selected objects, retaining their children. Human approval and automatic hierarchy checkpoint required.', 'delete', 'data', { scope: { type: 'string', const: 'selection' } }, ['scope']),
  tool('run_command', 'Invoke an exact live menu path after human approval. May open a native dialog. Check context afterward; dispatch is not completion. File and plugin side effects may not be reversible.', 'command', 'code', { path: text }, ['path']),
  tool('run_script', 'Advanced escape hatch: requires both an open project and image. Run exact Groovy source in QuPath after the researcher reviews and approves it. Full local privileges: can read/write files or run code. Read guidance/API docs; never use this to bypass denied approval. Automatic hierarchy checkpoint cannot undo external side effects.', 'script', 'code', { source: { type: 'string', minLength: 1, maxLength: 32000 }, reason: { type: 'string', minLength: 1, maxLength: 1000 } }, ['source', 'reason']),
  tool('knowledge', 'Search versioned task guidance and the locally downloaded official QuPath documentation. Use before unfamiliar operations, parameters, classifiers, measurements, export, TMA or scripting.', undefined, 'read', { query: { type: 'string', minLength: 2, maxLength: 200 } }, ['query']),
  tool('action_status', 'Read a pending/completed/denied action by ID. Approvals can only be decided by the human in the companion; there is no model approval tool.', undefined, 'read', { actionId: text }, ['actionId']),
];
const Ajv = AjvModule.default ?? AjvModule;
const ajv = new Ajv({ allErrors: true, strict: true });
const validators = new Map(tools.map(tool => [tool.name, ajv.compile(tool.inputSchema)]));
export function validateTool(name: string, args: unknown): Tool {
  const spec = tools.find(tool => tool.name === name);
  if (!spec) throw new Error(`Unknown tool: ${name}`);
  const validator = validators.get(name)!;
  if (!validator(args)) throw new Error(`Invalid arguments for ${name}: ${ajv.errorsText(validator.errors)}`);
  return spec;
}
export function realtimeTools() { return tools.map(({ name, description, inputSchema }) => ({ type: 'function', name, description, parameters: inputSchema })); }
