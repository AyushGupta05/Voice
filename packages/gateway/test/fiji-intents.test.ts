import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fiji } from '../src/apps/fiji.js';

const context = { image: { id: -1, width: 600, height: 400, calibration: { unit: 'micron', pixelWidth: 0.5, pixelHeight: 0.25 } }, counts: { roiManager: 40, roiManagerForImage: 10 } };

test('spoken area corrections preserve decimals, number words and physical calibration', () => {
  for (const request of ['Remove anything smaller than 20.5 square microns.', 'Remove anything under twenty point five square microns', 'Exclude cells below twenty point five microns squared']) {
    assert.deepEqual(fiji.fastRoute(request, context)?.arguments, { minArea: 20.5 }, request);
  }
  const millimetres = { ...context, image: { ...context.image, calibration: { unit: 'mm' } } };
  assert.deepEqual(fiji.fastRoute('Remove anything under twenty square microns', millimetres)?.arguments, { minArea: 0.00002 });
  assert.deepEqual(fiji.fastRoute('Remove anything under twenty square pixels', context)?.arguments, { minArea: 2.5 });
  const pixels = { ...context, image: { ...context.image, calibration: { unit: 'pixel' } } };
  assert.deepEqual(fiji.fastRoute('Remove anything under twenty square pixels', pixels)?.arguments, { minArea: 20 });
  assert.match(fiji.fastRoute('Remove anything under twenty square microns', pixels)?.refusal ?? '', /no physical scale/);
});

test('fast corrections do not consume the first step of a longer request', () => {
  for (const request of ['Remove the tiny ones and show the size distribution', 'Separate those cells and measure them', 'Remove anything under twenty square microns and count the remainder']) {
    assert.equal(fiji.fastRoute(request, context), undefined, request);
  }
});

test('showing existing nuclei highlights them and preserves the corrected analysis', () => {
  for (const phrase of ['Show me the nuclei', 'Show me the nuclei here', 'Show me the nuclei in this area', 'Outline the nuclei in this region']) {
    const route = fiji.fastRoute(phrase, context);
    assert.equal(route?.tool, 'fiji_highlight', phrase);
    assert.deepEqual(route?.arguments, { rois: 'manager', label: '10 objects' });
  }
  assert.equal(fiji.fastRoute('Find all the nuclei', context)?.tool, 'fiji_segment_cells');
  const differentImage = { ...context, counts: { roiManager: 40, roiManagerForImage: 0 } };
  assert.equal(fiji.fastRoute('Remove the tiny ones', differentImage), undefined);
  assert.equal(fiji.fastRoute('How many cells are left', differentImage)?.tool, 'fiji_object_stats');
  assert.equal(fiji.localSentenceFor('fiji_object_stats', { plot: false }, { n: 0 }), '0 detected objects in this image.');
  assert.equal(fiji.localSentenceFor('fiji_object_stats', { plot: false }, { n: 10, area: { mean: 77.8 } }), '10 objects.');
});

test('artifact retention is an explicit researcher choice rather than a model-filled default', () => {
  assert.deepEqual(fiji.modelArguments?.('fiji_segment_cells', { keepArtifacts: true }, 'Find the nuclei'), {});
  assert.deepEqual(fiji.modelArguments?.('fiji_segment_cells', { keepArtifacts: true, watershed: false }, 'Find nuclei without watershed and keep the small artifacts for now'), { keepArtifacts: true, watershed: false });
});

test('region counts read the existing population without moving or replacing the selection', () => {
  assert.deepEqual(fiji.fastRoute('How many cells are in the top-left?', context)?.arguments, { plot: false, region: { x: 0, y: 0, width: 200, height: 400 / 3 } });
  assert.deepEqual(fiji.fastRoute('Count the nuclei in the bottom right corner', context)?.arguments, { plot: false, region: { x: 400, y: 800 / 3, width: 200, height: 400 / 3 } });
  const zoomed = { ...context, viewer: { srcRect: { x: 300, y: 100, width: 120, height: 90 } } };
  assert.deepEqual(fiji.fastRoute('Count the nuclei in the top left', zoomed)?.arguments, { plot: false, region: { x: 300, y: 100, width: 40, height: 30 } });
  assert.deepEqual(fiji.fastRoute('Count the nuclei in this area', zoomed)?.arguments, { plot: false, region: { x: 330, y: 122.5, width: 60, height: 45 } });
  assert.deepEqual(fiji.fastRoute('How many are in this selection?', context)?.arguments, { plot: false, scope: 'selection' });
  assert.equal(fiji.localSentenceFor('fiji_object_stats', { plot: false }, { n: 0, scopeUsed: 'region' }), '0 objects in that region.');
  assert.equal(fiji.localSentenceFor('fiji_object_stats', { plot: false }, { n: 3, scopeUsed: 'selection' }), '3 objects in the selected region.');
});

test('deictic corrections require a current or recent pointer, not a legacy default coordinate', () => {
  for (const pointer of [{ overCanvas: false, lastX: 0, lastY: 0 }, { overCanvas: false, lastX: 120, lastY: 80, lastAgeMs: 31_000 }]) {
    assert.equal(fiji.fastRoute('Remove this one', { ...context, pointer }), undefined);
  }
  assert.deepEqual(fiji.fastRoute('Remove this one', { ...context, pointer: { overCanvas: false, lastX: 120, lastY: 80, lastAgeMs: 500 } })?.arguments, { at: 'pointer' });
});
