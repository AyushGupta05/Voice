// Regressions from the 17 Oct 2026 real-gateway chat battery (docs/qa/2026-10-17-chat-battery.md), mocked end to end.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ResponsesBackend, toolTask, failureSentence, type BackendClient } from '../src/backend.js';
import { Runtime } from '../src/runtime.js';
import { Knowledge } from '../src/knowledge.js';
import { TextConversation } from '../src/text-session.js';
import { compoundRequest, failureReason } from '../src/apps/profile.js';
import { fiji } from '../src/apps/fiji.js';
import { fijiInstructions } from '../src/apps/fiji-instructions.js';
import type { Adapter } from '../src/bridge.js';
import type { DelegatedTask } from '../src/live.js';

const context = { contextKey: 'fiji-1', application: 'Fiji', image: { id: 1, title: 'hela-cells.tif', slices: 1, channels: 3 }, counts: { roiManager: 30 } };
const call = (id: string, name: string, args: Record<string, unknown>) => ({ type: 'function_call', call_id: id, name, arguments: JSON.stringify(args) });
const completed = (result: Record<string, unknown>) => ({ status: 'completed', result: { result, checkpoint: 'cp', contextKey: 'fiji-1' }, error: undefined });
function spoken(text: string, id = 'task-1'): DelegatedTask {
  return { id, offsetMs: 0, context: { contextKey: 'fiji-1' }, signal: new AbortController().signal, spoken: true, acknowledge: () => {},
    conversation: () => ({ fragments: [{ role: 'user', text, startMs: 0, endMs: 1 }], truncated: false }), update: () => {} };
}
async function fijiRuntime(results: Record<string, unknown> = {}, root?: string) {
  const directory = root ? join(root, '.local', 'state') : await mkdtemp(join(tmpdir(), 'luma-battery-'));
  await mkdir(directory, { recursive: true });
  const operations: string[] = [];
  const adapter: Adapter = { execute: async operation => {
    operations.push(operation);
    if (operation === 'context') return context;
    if (operation === 'ui') return { windows: [] };
    const value = results[operation]; if (value instanceof Error) throw value; return value ?? { ok: true };
  } };
  const runtime = new Runtime(adapter, new Knowledge(fiji), directory, { autoApprove: true, profile: fiji });
  return { runtime, operations, clean: () => rm(root ?? directory, { recursive: true, force: true }) };
}

test('a second clause, or a question after a command, is a compound request; filler and plain questions are not', () => {
  for (const said of ['open hela cells, do the channels colocalise?', 'open hela cells do the channels colocalise', 'Open hela. Measure it.', 'zoom in; then count'])
    assert.equal(compoundRequest(said), true, said);
  for (const said of ['okay, zoom in', 'zoom in, please', 'Hey Fiji, find all the nuclei', 'do the channels colocalise?', 'how many cells are there?', 'count the cells that are positive', 'zoom to 2.5 times', 'Add a tumor annotation on the right.'])
    assert.equal(compoundRequest(said), false, said);
});

test('opening, switching or selecting finishes silently only for a pure view command', () => {
  assert.equal(fiji.requestContinuesAfter('fiji_activate_image', 'open hela cells, do the channels colocalise?'), true);
  assert.equal(fiji.requestContinuesAfter('fiji_select_roi', 'measure the intensity of these cells'), true);
  assert.equal(fiji.requestContinuesAfter('fiji_open_path', 'open the mitosis image and track the cells'), true);
  assert.equal(fiji.requestContinuesAfter('fiji_select_roi', 'draw a box around the middle'), false);
  assert.equal(fiji.requestContinuesAfter('fiji_activate_image', 'switch to hela cells'), false);
  assert.equal(fiji.requestContinuesAfter('fiji_display', 'make the image brighter'), false);
  assert.equal(fiji.requestContinuesAfter('fiji_zoom', 'okay, zoom in'), false);
  assert.equal(fiji.requestContinuesAfter('fiji_highlight', 'highlight the biggest cell'), false);
});

test('"open hela cells, do the channels colocalise?" keeps going after the switch and answers with the colocalisation', async () => {
  const f = await fijiRuntime({ activate_image: { image: { title: 'hela-cells.tif' } }, colocalize: { pearson: 0.62, manders: { m1: 0.7, m2: 0.5 } } });
  const shown: string[] = []; const script = [[call('a', 'fiji_activate_image', { title: 'hela-cells.tif' })], [call('c', 'fiji_colocalize', { channelA: 1, channelB: 2 })]];
  const client: BackendClient = { complete: async () => { const next = script.shift(); assert(next, 'unexpected extra model round'); return { output: next }; } };
  try {
    await new ResponsesBackend(f.runtime, client, 'fixture', (_id, text) => shown.push(text)).run(spoken('open hela cells, do the channels colocalise?'));
    assert.deepEqual(shown, ['Pearson 0.62, moderate positive correlation; Manders M1 0.70, M2 0.50.']);
    assert.ok(f.operations.includes('colocalize'));
  } finally { await f.clean(); }
});

test('a failed action is never followed by a success phrase', async () => {
  const f = await fijiRuntime({ classify_intensity: new Error('channel must be 1') });
  const shown: string[] = []; const script: Array<{ output: Record<string, unknown>[]; output_text?: string }> = [
    { output: [call('k', 'fiji_classify_intensity', { channel: 2, measurement: 'mean', method: 'Otsu' })] }, { output: [], output_text: 'Done.' },
    { output: [call('k2', 'fiji_classify_intensity', { channel: 3, measurement: 'mean', method: 'Otsu' })] }, { output: [call('z', 'fiji_zoom', { fit: true })] }];
  const client: BackendClient = { complete: async () => { const next = script.shift(); assert(next, 'unexpected extra model round'); return next; } };
  const backend = new ResponsesBackend(f.runtime, client, 'fixture', (_id, text) => shown.push(text));
  try {
    await backend.run(spoken('what percentage are positive for the green channel?', 'first'));
    assert.deepEqual(shown, ['That did not work: channel must be 1.']);
    // A later silent view finish after the failed action says so too, instead of "Done.".
    await backend.run(spoken('fit to window', 'second'));
    assert.deepEqual(shown.at(-1), 'That did not work: channel must be 1.');
  } finally { await f.clean(); }
  assert.equal(failureReason(fiji, 'fiji_capture_macro', {}, completed({ ok: false, error: 'No recorded steps' })), 'No recorded steps');
  assert.equal(failureReason(fiji, 'fiji_measure', {}, completed({ rows: 2 })), undefined);
  assert.equal(failureSentence('Nothing restored.'), 'That did not work: Nothing restored.');
});

test('undo says what came back, and says so when there was no checkpoint to restore', async () => {
  assert.equal(fiji.localSentenceFor('fiji_undo', {}, completed({ mode: 'checkpoint', label: 'Before segment cells', counts: { roiManager: 30 } })), 'Restored "Before segment cells"; 30 objects now.');
  assert.match(String(fiji.localSentenceFor('fiji_undo', {}, completed({ mode: 'imagej-undo' }))), /^No checkpoint to go back to/);
  const f = await fijiRuntime({ undo: { mode: 'imagej-undo', note: 'ImageJ Undo' } });
  const events: Array<Record<string, unknown>> = [];
  const conversation = new TextConversation(f.runtime, { complete: async () => { throw new Error('no model round expected'); } }, 'fixture', event => events.push(event));
  try {
    await conversation.submit('go back', true);
    assert.match(String(events.find(event => event.type === 'text_result')?.text), /^That did not work: No checkpoint to go back to/);
  } finally { conversation.stop(); await f.clean(); }
});

test('the instant folder route passes the folder the utterance names, else the default', async () => {
  assert.equal(fiji.fastRoute('do the same for all the images in the bbbc folder', context)?.folderQuery, 'bbbc');
  assert.equal(fiji.fastRoute('apply this to the whole folder', context)?.folderQuery, undefined);
  const root = await mkdtemp(join(tmpdir(), 'luma-battery-folder-'));
  await mkdir(join(root, '.local', 'data', 'bbbc008', 'images'), { recursive: true });
  await writeFile(join(root, '.local', 'data', 'bbbc008', 'images', 'a.tif'), 'x');
  const f = await fijiRuntime({ apply_analysis: { run: { processed: 1, perImage: [{ resultsRows: 4 }] } } }, root);
  const calls: unknown[] = [];
  f.runtime.on('tool', event => calls.push(event));
  const events: Array<Record<string, unknown>> = [];
  const conversation = new TextConversation(f.runtime, { complete: async () => { throw new Error('no model round expected'); } }, 'fixture', event => events.push(event));
  try {
    await conversation.submit('do the same for all the images in the bbbc folder', true);
    const action = [...f.runtime.actions.values()].find(entry => entry.tool === 'fiji_apply_analysis');
    assert.deepEqual(action?.arguments, { folder: join(root, '.local', 'data', 'bbbc008', 'images') });
    assert.match(String(events.find(event => event.type === 'text_result')?.text), /^Running it on the images folder\. Ran the corrected analysis on 1 image/);
  } finally { conversation.stop(); await f.clean(); }
});

test('tool calls run inside their conversation task, so events can carry its taskId', async () => {
  const f = await fijiRuntime({ zoom: { viewer: { magnification: 2 } } });
  const seen: Array<string | undefined> = [];
  f.runtime.on('tool', (event: { name: string }) => { if (event.name === 'fiji_zoom') seen.push(toolTask.getStore()); });
  const events: Array<Record<string, unknown>> = [];
  const client: BackendClient = { complete: async () => ({ output: [call('z', 'fiji_zoom', { magnification: 2 })] }) };
  const conversation = new TextConversation(f.runtime, client, 'fixture', event => events.push(event));
  try {
    await conversation.submit('zoom in', true);
    const fast = events.find(event => event.type === 'text_result')?.taskId;
    await new ResponsesBackend(f.runtime, client, 'fixture', () => {}).run(spoken('zoom to two times', 'model-task'));
    assert.deepEqual(seen, [fast, 'model-task']);
    assert.equal(toolTask.getStore(), undefined, 'the scope does not leak to the caller');
  } finally { conversation.stop(); await f.clean(); }
});

test('instructions say a new request replaces an unanswered question and carry the battery defaults', () => {
  for (const phrase of ['a new request replaces any unanswered question', 'the two non-nuclear channels', 'never re-segment', 'lower the display max', 'active channel (all when asked)', "the active image's folder"])
    assert.ok(fijiInstructions.includes(phrase), phrase);
  assert.ok(fijiInstructions.length <= 4400);
});
