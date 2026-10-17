import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { profiles } from '../src/apps/index.js';
import { fijiTools } from '../src/apps/fiji-tools.js';
import { fijiInstructions } from '../src/apps/fiji-instructions.js';
import { requiredOperationsFor } from '../src/bridge-readiness.js';
import { validateTool } from '../src/tools.js';

const { fiji, qupath } = profiles;
/** The biology and macro operations added to docs/FIJI-PROTOCOL.md, with their risk. */
const biology = {
  track_objects: 'data', intensity_over_time: 'data', tumor_region: 'data', colocalize: 'read', classify_intensity: 'data', object_stats: 'read',
  make_macro: 'code', workflows: 'read', run_workflow: 'code', capture_macro: 'code',
} as const;
const completed = (result: unknown) => ({ status: 'completed', error: undefined, result: { result, checkpoint: 'c1' } });

test('biology and macro tools exist with their bridge operation, risk and protocol row', async () => {
  const protocol = await readFile(new URL('../../../docs/FIJI-PROTOCOL.md', import.meta.url), 'utf8');
  for (const [operation, risk] of Object.entries(biology)) {
    const tool = fijiTools.find(candidate => candidate.name === `fiji_${operation}`);
    assert.ok(tool, `fiji_${operation} exists`);
    assert.equal(tool.operation, operation); assert.equal(tool.risk, risk, `${operation} risk`);
    assert.ok(tool.description.length <= 600, `${operation} description stays short`);
    assert.ok(requiredOperationsFor(fiji).includes(operation), `${operation} is required of the Fiji bridge`);
    assert.match(protocol, new RegExp(`\\| \`${operation}\` \\| ${risk} \\|`), `${operation} has a protocol row`);
  }
  assert.ok(requiredOperationsFor(fiji).length <= 64, 'bridge readiness accepts at most 64 declared operations');
  assert.ok(!requiredOperationsFor(qupath).some(operation => operation in biology), 'QuPath is unchanged');
});

test('biology tool schemas accept the contract arguments and reject the rest', () => {
  const ok = (name: string, args: Record<string, unknown>) => assert.equal(validateTool(name, args, fiji).name, name);
  const bad = (name: string, args: Record<string, unknown>) => assert.throws(() => validateTool(name, args, fiji), /Invalid arguments/, `${name} ${JSON.stringify(args)}`);
  ok('fiji_track_objects', {}); ok('fiji_track_objects', { channel: 1, method: 'Otsu', dark: true, minSize: 5, maxSize: 500, maxDistance: 12, maxGap: 2, minLength: 3, startFrame: 1, endFrame: 40, sigma: 1, replaceOverlay: true });
  bad('fiji_track_objects', { maxGap: 11 }); bad('fiji_track_objects', { maxDistance: 0 }); bad('fiji_track_objects', { channel: 0 });
  ok('fiji_intensity_over_time', { scope: 'manager', indices: [0, 2], channel: 2 }); bad('fiji_intensity_over_time', { scope: 'all' });
  ok('fiji_tumor_region', { stain: 'he', sigma: 4, method: 'Otsu', minAreaFraction: 0.05, keepLargest: 2, name: 'Tumour' });
  bad('fiji_tumor_region', { stain: 'pas' }); bad('fiji_tumor_region', { minAreaFraction: 1.5 }); bad('fiji_tumor_region', { keepLargest: 21 });
  ok('fiji_colocalize', { channelA: 1, channelB: 2, roi: true, method: 'Costes' }); ok('fiji_colocalize', { imageA: -3, imageB: -4, thresholdA: 40, thresholdB: 55 });
  ok('fiji_classify_intensity', { channel: 2, measurement: 'mean', threshold: 812, label: 'Ki67' }); ok('fiji_classify_intensity', { method: 'Otsu' });
  bad('fiji_classify_intensity', { measurement: 'sum' }); bad('fiji_classify_intensity', { label: 'x'.repeat(41) });
  ok('fiji_object_stats', {}); bad('fiji_object_stats', { limit: 3 });
  ok('fiji_make_macro', { name: 'count-nuclei', code: 'print(nImages);', description: 'Counts nuclei', language: 'ijm', test: true, params: { channel: 1, method: 'Otsu', watershed: true } });
  bad('fiji_make_macro', { name: 'x' }); bad('fiji_make_macro', { code: 'print(1);' }); bad('fiji_make_macro', { name: 'x', code: 'y', language: 'javascript' });
  bad('fiji_make_macro', { name: 'x', code: 'y', params: { nested: { a: 1 } } }); bad('fiji_make_macro', { name: 'x'.repeat(81), code: 'y' });
  ok('fiji_workflows', {}); ok('fiji_workflows', { query: 'nuclei' });
  ok('fiji_run_workflow', { name: 'count-nuclei', target: 'folder', folder: '/Users/me/images', pattern: '*.tif', params: { min: 30 }, limit: 50, saveResults: true });
  bad('fiji_run_workflow', {}); bad('fiji_run_workflow', { name: 'x', target: 'everywhere' }); bad('fiji_run_workflow', { name: 'x', limit: 501 });
  ok('fiji_capture_macro', { name: 'blobs-count', description: 'What we just did', lastSteps: 4 }); bad('fiji_capture_macro', { lastSteps: 4 });
});

test('biology acknowledgments are spoken at once', () => {
  for (const [name, text] of [['fiji_track_objects', 'Tracking them.'], ['fiji_tumor_region', 'Outlining the tumour.'], ['fiji_colocalize', 'Checking colocalisation.'],
    ['fiji_classify_intensity', 'Scoring positives.'], ['fiji_make_macro', 'Writing the macro.'], ['fiji_capture_macro', 'Saving the macro.']])
    assert.equal(fiji.acknowledgmentFor(name, {}, {}), text, name);
  assert.equal(fiji.acknowledgmentFor('fiji_run_workflow', { name: 'x', target: 'folder' }, {}), 'Running it on every image.');
  assert.equal(fiji.acknowledgmentFor('fiji_run_workflow', { name: 'x' }, {}), 'Running the workflow.', 'the active image is not every image');
});

test('biology local sentences speak only the reported numbers and units', () => {
  const say = (name: string, result: unknown, args: Record<string, unknown> = {}) => fiji.localSentenceFor(name, args, result);
  assert.equal(say('fiji_track_objects', completed({ tracks: 12, frames: 40, meanSpeed: 0.81, unit: 'µm', objectsPerFrame: { min: 10, max: 13, mean: 12 } })), 'Tracked 12 objects over 40 frames, mean speed 0.81 µm per frame.');
  assert.equal(say('fiji_track_objects', completed({ tracks: 5, frames: 20, meanSpeed: 1.5, unit: 'µm', frameInterval: 30, timeUnit: 'sec', divisions: 2 })), 'Tracked 5 objects over 20 frames, mean speed 1.50 µm per sec, 2 divisions.');
  assert.equal(say('fiji_track_objects', completed({ tracks: 1, frames: 8, meanSpeed: 3 })), 'Tracked 1 object over 8 frames, mean speed 3 per frame.', 'no unit reported, none spoken');
  assert.equal(say('fiji_track_objects', completed({ frames: 8 })), undefined);
  assert.equal(say('fiji_track_objects', completed({ tracks: 4, frames: 30, meanSpeed: 2.5, unit: 'pixel', speedUnit: 'pixel/frame' })), 'Tracked 4 objects over 30 frames, mean speed 2.50 pixels per frame.');
  assert.equal(say('fiji_track_objects', completed({ tracks: 4, frames: 30, meanSpeed: 0.2, unit: 'µm', speedUnit: 'µm/min', frameInterval: 2, timeUnit: 'min' })), 'Tracked 4 objects over 30 frames, mean speed 0.20 µm per min.');
  assert.equal(say('fiji_track_objects', completed({ tracks: 4, frames: 30, meanSpeed: 'NaN', unit: 'µm' })), 'Tracked 4 objects over 30 frames.', 'no measurable speed, none spoken');
  assert.equal(say('fiji_tumor_region', completed({ regions: 1, area: 500, areaFraction: 0.5, areaFractionOf: 'selection', unit: 'pixel' })), 'Tumour covers 50% of the selected region, 500 square pixels.');
  assert.equal(say('fiji_workflows', { workflows: [{ name: 'a' }], total: 7, truncated: true }), '7 saved workflows.');
  assert.equal(say('fiji_intensity_over_time', completed({ frames: 40, rois: 1, series: [{ roi: 0, first: 100, last: 66, changePercent: -34, peakFrame: 3 }] })), 'Intensity fell 34% over 40 frames, peak at frame 3.');
  assert.equal(say('fiji_intensity_over_time', completed({ frames: 10, series: [{ changePercent: -40.2 }, { changePercent: 12 }] })), 'Followed 2 regions over 10 frames; change from minus 40% to +12%.');
  assert.equal(say('fiji_intensity_over_time', completed({ frames: 10, series: [{ first: 1 }] })), undefined);
  assert.equal(say('fiji_tumor_region', completed({ regions: 1, area: 2_100_000, areaFraction: 0.381, areaFractionOf: 'tissue', unit: 'µm' })), 'Tumour covers 38% of the section, 2.10 mm².');
  assert.equal(say('fiji_tumor_region', completed({ regions: 2, area: 9472, areaFraction: 0.105 })), '2 regions cover 11% of the image, 9472.');
  assert.equal(say('fiji_tumor_region', completed({ area: 9472, areaFraction: 38 })), undefined, 'a fraction outside 0–1 is not guessed');
  assert.equal(say('fiji_colocalize', { pearson: 0.823, manders: { m1: 0.71, m2: 0.649 }, method: 'Costes', interpretation: 'x' }), 'Pearson 0.82, strong positive correlation; Manders M1 0.71, M2 0.65.');
  assert.equal(say('fiji_colocalize', { pearson: -0.45 }), 'Pearson -0.45, moderate negative correlation.');
  assert.equal(say('fiji_colocalize', { pearson: 0.05, manders: { m1: 0.2 } }), 'Pearson 0.05, no meaningful correlation.');
  assert.equal(say('fiji_colocalize', { manders: { m1: 0.2, m2: 0.3 } }), undefined);
  assert.equal(say('fiji_classify_intensity', completed({ total: 120, positive: 34, negative: 86, percentPositive: 28.33, label: 'Ki67', threshold: 812 })), '34 of 120 cells Ki67-positive, 28%.');
  assert.equal(say('fiji_classify_intensity', completed({ total: 36, positive: 3, negative: 33 })), '3 of 36 cells positive, 8.3%.');
  assert.equal(say('fiji_classify_intensity', completed({ total: 0, positive: 0 })), undefined);
  assert.equal(say('fiji_object_stats', { n: 48, area: { mean: 369.2, median: 352, sd: 90, min: 51, max: 700 }, unit: 'pixels' }), '48 objects, mean area 369 square pixels, median 352 square pixels.');
  assert.equal(say('fiji_object_stats', { area: { mean: 3 } }), undefined);
  assert.equal(say('fiji_make_macro', completed({ path: '/x/.local/workflows/count.ijm', name: 'count', language: 'ijm', lines: 12, tested: true, ok: true, log: 'nuclei=64', resultsAdded: 64, imagesOpened: 0 })), 'Macro saved and tested: 64 rows.');
  assert.equal(say('fiji_make_macro', completed({ path: '/x/count.ijm', tested: false, ok: true })), 'Saved count.ijm; not tested yet.');
  assert.equal(say('fiji_capture_macro', completed({ path: '/x/blobs.ijm', tested: true, ok: true, resultsAdded: 0 })), 'Macro saved and tested.');
  assert.equal(say('fiji_workflows', { workflows: [{ name: 'count-nuclei' }, { name: 'frap' }] }), '2 saved workflows: count-nuclei and frap.');
  assert.equal(say('fiji_workflows', { workflows: [] }), 'No saved workflows yet.');
  assert.equal(say('fiji_run_workflow', completed({ workflow: 'count-nuclei', processed: 12, failed: [{ image: 'b.tif', error: 'x' }], csv: '/x/.local/exports/count-nuclei.csv', durationMs: 900 })), 'Ran count-nuclei on 12 images, 1 failed; results in count-nuclei.csv.');
  assert.equal(say('fiji_run_workflow', completed({ workflow: 'x', processed: 0, failed: 3 })), undefined, 'nothing processed goes back to the model');
});

test('failed biology results and broken macros go back to the model', () => {
  const say = (name: string, result: unknown) => fiji.localSentenceFor(name, {}, result);
  for (const name of Object.keys(biology).map(operation => `fiji_${operation}`)) {
    assert.equal(say(name, { status: 'failed', error: 'No image open' }), undefined, name);
    assert.equal(say(name, { error: 'Bridge refused' }), undefined, name);
  }
  assert.equal(say('fiji_make_macro', completed({ path: '/x/a.ijm', ok: false, tested: true, error: 'Undefined variable', errorLine: 4, log: '' })), undefined);
  assert.equal(say('fiji_capture_macro', completed({ path: '/x/a.ijm', ok: false, tested: true })), undefined);
  assert.equal(say('fiji_make_macro', completed({ path: '/x/a.ijm', ok: true, tested: true, error: 'late failure' })), undefined);
});

test('requests that need more than the local sentence continue with the model', () => {
  assert.equal(fiji.requestContinuesAfter('fiji_segment_cells', 'how many cells are ki67 positive'), true, 'positivity needs classify after segmentation');
  assert.equal(fiji.requestContinuesAfter('fiji_segment_cells', 'count the cells'), false);
  assert.equal(fiji.requestContinuesAfter('fiji_track_objects', 'track the cells'), false);
  assert.equal(fiji.requestContinuesAfter('fiji_track_objects', 'which cell moves fastest'), true);
  assert.equal(fiji.requestContinuesAfter('fiji_intensity_over_time', 'what is the half time of recovery'), true);
  assert.equal(fiji.requestContinuesAfter('fiji_colocalize', 'do the channels colocalise'), false);
  assert.equal(fiji.requestContinuesAfter('fiji_make_macro', 'write a macro that blurs every slice'), false);
  assert.equal(fiji.requestContinuesAfter('fiji_make_macro', 'how many nuclei touch the border'), true, 'a macro written to answer a question reports the answer');
  assert.ok(fiji.longRunningTools.has('fiji_track_objects') && fiji.longRunningTools.has('fiji_run_workflow'));
});

test('instructions route the biology requests within the length budget', () => {
  assert.ok(fijiInstructions.length <= 4400, `instructions are ${fijiInstructions.length} characters`);
  for (const phrase of ['time-lapse -> fiji_track_objects', 'fiji_intensity_over_time', 'fiji_tumor_region', 'fiji_colocalize', 'fiji_segment_cells then fiji_classify_intensity',
    'fiji_make_macro, fix errors silently (max 3 tries)', 'fiji_run_workflow', 'fiji_capture_macro', 'fiji_highlight when you name cells, regions or results'])
    assert.ok(fijiInstructions.includes(phrase), phrase);
});

test('the biology tools reference is linked from the skill and its templates read parameters with defaults', async () => {
  const skill = await readFile(new URL('../../../skills/fiji/SKILL.md', import.meta.url), 'utf8');
  assert.match(skill, /\(references\/biology-tools\.md\)/);
  const recipes = await readFile(new URL('../../../skills/fiji/references/biology-tools.md', import.meta.url), 'utf8');
  const templates = [...recipes.matchAll(/```ijm\n([\s\S]*?)```/g)].map(match => match[1]);
  assert.ok(templates.length >= 5 && templates.length <= 8, `${templates.length} templates`);
  for (const code of templates) {
    assert.match(code, /print\(/, 'every template ends with a spoken line');
    assert.doesNotMatch(code, /waitForUser|getString\(|Dialog\.create/, 'templates never wait for a person');
  }
  assert.ok(templates.filter(code => code.includes('call("ij.Macro.getValue", ')).length >= 5);
  assert.match(recipes, /setBatchMode\(true\)/);
});

test('segmentation corrections from the Hey Fiji demo take the instant route only when objects exist', () => {
  const withCells = { image: { slices: 1 }, counts: { roiManager: 82 } }, empty = { image: { slices: 1 }, counts: { roiManager: 0 } };
  assert.deepEqual(fiji.fastRoute!('Some of these detections look wrong. Remove the tiny ones.', withCells), { tool: 'fiji_filter_objects', arguments: { remove: 'tiny' }, reply: 'Removing the tiny ones.' });
  assert.equal(fiji.fastRoute!('Those two cells are touching. Can you separate them?', withCells)?.tool, 'fiji_split_objects');
  assert.deepEqual(fiji.fastRoute!('Show me the size distribution.', withCells)?.arguments, { plot: true });
  assert.equal(fiji.fastRoute!('Remove the tiny ones.', empty), undefined);
  assert.equal(fiji.fastRoute!('Split the channels', withCells), undefined);
  assert.equal(fiji.fastRoute!('Remove the small ones and then measure intensity', withCells), undefined);
  assert.equal(fiji.localSentenceFor!('fiji_filter_objects', {}, { status: 'completed', result: { before: 82, removed: 9, kept: 73 } }), 'Removed 9 tiny ones; 73 cells left.');
  assert.equal(fiji.localSentenceFor!('fiji_split_objects', {}, { status: 'completed', result: { before: 73, after: 79, split: 5 } }), 'Separated 5 clumps: 73 to 79 cells.');
});
