import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { findLocalImages, imageNameMatches } from '../src/local-images.js';

test('loose slide names match file names regardless of case, spaces and dashes', () => {
  assert(imageNameMatches('OS-2.ndpi', 'os 2'));
  assert(imageNameMatches('CMU-1-Small-Region.svs', 'the cmu slide') === false);
  assert(imageNameMatches('CMU-1-Small-Region.svs', 'cmu small'));
  assert(imageNameMatches('anything.tif', ''));
});

test('finds slides two levels deep in the given roots, largest first, and skips other files', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-images-'));
  try {
    await mkdir(join(root, 'deep', 'deeper', 'deepest'), { recursive: true });
    await writeFile(join(root, 'OS-2.ndpi'), 'x'.repeat(30));
    await writeFile(join(root, 'deep', 'CMU-1.svs'), 'x'.repeat(10));
    await writeFile(join(root, 'deep', 'deeper', 'study.qpproj'), 'x');
    await writeFile(join(root, 'deep', 'deeper', 'deepest', 'hidden.svs'), 'x');
    await writeFile(join(root, 'notes.txt'), 'x');
    const all = await findLocalImages(root, '', 20, [root]);
    assert.deepEqual(all.matches.map(m => [m.name, m.kind]), [['OS-2.ndpi', 'image'], ['CMU-1.svs', 'image'], ['study.qpproj', 'project']]);
    const one = await findLocalImages(root, 'os-2', 20, [root]);
    assert.equal(one.matches.length, 1); assert.equal(one.matches[0].path, join(root, 'OS-2.ndpi'));
    const limited = await findLocalImages(root, '', 1, [root]);
    assert.equal(limited.matches.length, 1); assert.equal(limited.truncated, true);
    assert.deepEqual((await findLocalImages(root, 'nothing-here', 20, [root, join(root, 'missing')])).matches, []);
  } finally { await rm(root, { recursive: true, force: true }); }
});
