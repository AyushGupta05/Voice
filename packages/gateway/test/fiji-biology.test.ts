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

test('pointing at cells routes "these should be separate", "one cell" and "remove this one" to the pointer', () => {
  const ctx = { image: { slices: 1 }, counts: { roiManager: 40 }, pointer: { overCanvas: true, x: 120, y: 80 } };
  assert.deepEqual(fiji.fastRoute!('These should be separate.', ctx), { tool: 'fiji_split_objects', arguments: { at: 'pointer' }, reply: 'Separating them.' });
  assert.equal(fiji.fastRoute!('These two are one cell', ctx)?.tool, 'fiji_merge_objects');
  assert.equal(fiji.fastRoute!('Remove this one, it is not a cell', ctx)?.tool, 'fiji_filter_objects');
  assert.deepEqual(fiji.fastRoute!('Remove this one', { ...ctx, pointer: { overCanvas: false } }), undefined);
  assert.equal(fiji.localSentenceFor!('fiji_merge_objects', {}, { status: 'completed', result: { before: 41, after: 40, merged: 2 } }), 'Merged 2 into one; 40 cells now.');
});

test('the Hey Fiji closing lines route to the corrected pipeline', () => {
  const ctx = { image: { slices: 1, calibration: { unit: 'micron' } }, counts: { roiManager: 64 }, pointer: { overCanvas: true, x: 10, y: 10 } };
  assert.deepEqual(fiji.fastRoute!('Remove anything smaller than 20 square microns.', ctx), { tool: 'fiji_filter_objects', arguments: { minArea: 20 }, reply: 'Removing anything under 20.' });
  assert.equal(fiji.fastRoute!('That looks right. Apply this analysis to the entire folder.', ctx)?.tool, 'fiji_apply_analysis');
  assert.equal(fiji.fastRoute!('Why did you count this as a cell?', ctx)?.tool, 'fiji_explain_object');
  assert.match(String(fiji.fastRoute!('Remove anything smaller than 20 square pixels', ctx)?.refusal), /pixel calibration/);
  assert.equal(fiji.localSentenceFor!('fiji_apply_analysis', {}, { status: 'completed', result: { notReplayed: ['merged 2 objects by hand'], run: { processed: 3, failed: [], perImage: [{ resultsRows: 10 }, { resultsRows: 10 }, { resultsRows: 10 }], csv: '/x/analysis-1.csv' } } }),
    'Ran the corrected analysis on 3 images, 10 cells each on average; results in analysis-1.csv. 1 hand edit not replayed.');
});

test('misheard image names still find the sample, and "find all the nuclei" is instant', async () => {
  const { fuzzyNameScore, findLocalImages } = await import('../src/local-images.js');
  assert.equal(fuzzyNameScore('hela-cells.tif', 'gala cells'), 2);
  assert.equal(fuzzyNameScore('blobs.gif', 'gala cells'), undefined);
  const { mkdtemp, mkdir, writeFile } = await import('node:fs/promises'); const { join } = await import('node:path'); const { tmpdir } = await import('node:os');
  const root = await mkdtemp(join(tmpdir(), 'penny-find-')); await mkdir(join(root, 'samples'), { recursive: true });
  await writeFile(join(root, 'samples', 'hela-cells.tif'), 'x'); await writeFile(join(root, 'samples', 'FluorescentCells.tif'), 'xx');
  const found = await findLocalImages(root, 'Gala cells', 5, [root], false);
  assert.equal(found.matches[0]?.name, 'hela-cells.tif');
  const ctx = { image: { slices: 1 }, counts: { roiManager: 0 } };
  assert.deepEqual(fiji.fastRoute!('Hey Fiji, find all the nuclei.', ctx), { tool: 'fiji_segment_cells', arguments: {}, reply: 'Finding the nuclei.' });
  assert.equal(fiji.fastRoute!('Find all the nuclei and tell me which are Ki67 positive', ctx), undefined);
});

test('expert arguments the researcher never said are dropped so the bridge defaults apply', () => {
  const clean = fiji.modelArguments!;
  assert.deepEqual(clean('fiji_tumor_region', { channel: 1, stain: 'he', sigma: 2, method: 'Otsu', minAreaFraction: 0.05, keepLargest: 1, name: 'Tumor' }, 'Open the H&E image and outline the tumour.'), { stain: 'he', name: 'Tumor' });
  assert.deepEqual(clean('fiji_track_objects', { channel: 1, minSize: 0.5, minLength: 1 }, 'track the cells'), {});
  assert.deepEqual(clean('fiji_segment_cells', { method: 'Li' }, 'make the threshold stricter'), { method: 'Li' });
  assert.deepEqual(clean('fiji_segment_cells', { channel: 2, minSize: 50 }, 'segment the green channel, ignore anything smaller than 50 pixels'), { channel: 2, minSize: 50 });
  assert.deepEqual(clean('fiji_measure', { scope: 'manager' }, 'measure'), { scope: 'manager' });
});

test('brighter/darker move the live display range instantly and never filter pixels', () => {
  const ctx = { image: { slices: 1, displayRange: [0, 255] }, counts: { roiManager: 0 } };
  assert.deepEqual(fiji.fastRoute!('Make it brighter.', ctx), { tool: 'fiji_display', arguments: { min: 0, max: 178.5 }, reply: 'Brighter.' });
  assert.equal(fiji.fastRoute!('a bit darker', ctx)?.arguments.max, 357);
  assert.deepEqual(fiji.fastRoute!('auto contrast', ctx)?.arguments, { auto: true });
  assert.equal(fiji.fastRoute!('make it brighter and count the cells', ctx), undefined);
});

test('raw images rank above same-named ground-truth masks; tumour fraction is instant', async () => {
  const { findLocalImages } = await import('../src/local-images.js');
  const { mkdtemp, mkdir, writeFile } = await import('node:fs/promises'); const { join } = await import('node:path'); const { tmpdir } = await import('node:os');
  const root = await mkdtemp(join(tmpdir(), 'penny-masks-'));
  await mkdir(join(root, 'bbbc008', 'images'), { recursive: true }); await mkdir(join(root, 'bbbc008', 'foreground'), { recursive: true });
  await writeFile(join(root, 'bbbc008', 'images', 'A24_channel1.tif'), 'x'); await writeFile(join(root, 'bbbc008', 'foreground', 'A24_channel1.tif'), 'xxxxxxxx');
  assert.match((await findLocalImages(root, 'bbbc channel1', 5, [root], false)).matches[0].path, /images/);
  assert.match((await findLocalImages(root, 'bbbc channel1 mask', 5, [root], false)).matches[0]?.path ?? 'foreground', /foreground/);
  assert.equal(fiji.fastRoute!('How much of the tissue is tumour?', { image: { slices: 1 }, counts: { roiManager: 3 } })?.tool, 'fiji_tumor_region');
});

test('spoken dataset numbers and channels normalise like file names', async () => {
  const { queryWords } = await import('../src/local-images.js');
  assert.deepEqual(queryWords('the BBBCOO8 channel one image'), ['bbbc008', 'channel1']);
  assert.deepEqual(queryWords('bbbc zero zero eight slice two'), ['bbbc008', 'slice2']);
});

test('magic polish: friendly spoken names, instant description and one-step open', async () => {
  const { friendlyName, describeImage } = await import('../src/apps/fiji.js');
  assert.equal(friendlyName('hela-cells.tif'), 'hela cells');
  assert.equal(friendlyName('AS_09125_050116000001_A24f00d0_slice2_channel1.tif', '/x/samples/bbbc008/images/AS_09125_050116000001_A24f00d0_slice2_channel1.tif'), 'the BBBC008 channel 1 image');
  assert.equal(friendlyName('Invasive_cervical_squamous_cell_carcinoma_HE_and_Ki-67.jpg', '/x/samples/ki67-cervical-scc/a.jpg'), 'the ki67 cervical scc image');
  const hela = { image: { title: 'hela-cells.tif', width: 672, height: 512, bitDepth: 16, slices: 1, frames: 1, channels: [{ lut: 'red' }, { lut: 'green' }, { lut: 'blue' }], calibration: { pixelWidth: 0.0515, unit: 'micron' } }, counts: { roiManagerForImage: 0 } };
  assert.equal(describeImage(hela), 'hela cells: 672 by 512, 16-bit; 3 channels, red, green and blue; blue is usually the nuclear stain; 0.05 microns per pixel.');
  assert.deepEqual(fiji.fastRoute!('What am I looking at?', hela), { tool: 'fiji_context', arguments: {}, reply: describeImage(hela) });
  assert.deepEqual(fiji.fastRoute!('Hey Fiji, open the BBBC 008 channel one image.', undefined), { tool: 'fiji_open_path', arguments: {}, reply: 'Opening it.', imageQuery: 'bbbc 008 channel one' });
  assert.equal(fiji.fastRoute!('open the hela cells image and tell me if the channels colocalise', undefined), undefined);
  assert.equal(fiji.fastRoute!('open the blobs sample', undefined), undefined);
  assert.equal(fiji.localSentenceFor!('fiji_open_path', {}, { status: 'completed', result: { image: { title: 'hela-cells.tif', width: 672, height: 512 } } }), 'Opened hela cells, 672 by 512.');
});
