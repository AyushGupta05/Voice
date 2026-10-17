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

test('finds slides three levels deep in the given roots, largest first, and skips other files', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-images-'));
  try {
    await mkdir(join(root, 'deep', 'deeper', 'deepest', 'abyss'), { recursive: true });
    await writeFile(join(root, 'OS-2.ndpi'), 'x'.repeat(30));
    await writeFile(join(root, 'deep', 'CMU-1.svs'), 'x'.repeat(10));
    await writeFile(join(root, 'deep', 'deeper', 'study.qpproj'), 'x');
    await writeFile(join(root, 'deep', 'deeper', 'deepest', 'abyss', 'hidden.svs'), 'x');
    await writeFile(join(root, 'notes.txt'), 'x');
    const all = await findLocalImages(root, '', 20, [root], false);
    assert.deepEqual(all.matches.map(m => [m.name, m.kind]), [['OS-2.ndpi', 'image'], ['CMU-1.svs', 'image'], ['study.qpproj', 'project']]);
    const one = await findLocalImages(root, 'os-2', 20, [root], false);
    assert.equal(one.matches.length, 1); assert.equal(one.matches[0].path, join(root, 'OS-2.ndpi'));
    const limited = await findLocalImages(root, '', 1, [root], false);
    assert.equal(limited.matches.length, 1); assert.equal(limited.truncated, true);
    assert.deepEqual((await findLocalImages(root, 'nothing-here', 20, [root, join(root, 'missing')], false)).matches, []);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('filler words are dropped and spoken aliases match abbreviated names', () => {
  assert(imageNameMatches('mitosis.tif', 'the mitosis movie'));
  assert(imageNameMatches('blobs.gif', 'my blobs sample image'));
  assert(imageNameMatches('mitosis.tif', 'the mitosis cells') === false, 'a real word still has to match');
  for (const spoken of ['H&E', 'the h and e image', 'HE picture']) assert(imageNameMatches('histopathology/IDC-sample.jpg', spoken), spoken);
  assert(imageNameMatches('eosin_section.png', 'h&e'));
  assert(imageNameMatches('hela-cells.tif', 'he') === false, '"he" is a whole word, not a substring of hela');
  for (const name of ['bbbc008 images/plate1.tif', 'DAPI_stain.tif', 'hoechst-01.tif', 'nuclei.tif']) assert(imageNameMatches(name, 'the nuclei image'), name);
});

test('matching folders come back with their image count after the images', async () => {
  const root = await mkdtemp(join(tmpdir(), 'luma-folders-'));
  try {
    await mkdir(join(root, 'bbbc008', 'images'), { recursive: true }); await mkdir(join(root, 'bbbc008', 'labels'), { recursive: true });
    await writeFile(join(root, 'bbbc008', 'images', 'a.tif'), 'xx'); await writeFile(join(root, 'bbbc008', 'images', 'b.tif'), 'x');
    await writeFile(join(root, 'bbbc008', 'readme.txt'), 'x');
    const found = await findLocalImages(root, 'the bbbc folder', 20, [root], false);
    assert.deepEqual(found.matches.map(m => [m.name, m.kind]), [['a.tif', 'image'], ['b.tif', 'image'], ['images', 'folder']]);
    assert.equal(found.matches.at(-1)?.images, 2); assert.equal(found.matches.at(-1)?.path, join(root, 'bbbc008', 'images'));
    assert.ok(!(await findLocalImages(root, '', 20, [root], false)).matches.some(m => m.kind === 'folder'), 'an empty query lists images only');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('a git worktree under .local/worktrees searches its enclosing checkout\'s data folder as well as its own', async () => {
  const { searchRoots, enclosingCheckout } = await import('../src/local-images.js');
  const checkout = '/work/Voice', worktree = join(checkout, '.local', 'worktrees', 'integration');
  assert.equal(enclosingCheckout(worktree), checkout);
  assert.equal(enclosingCheckout(worktree + '/'), checkout);
  assert.equal(enclosingCheckout(checkout), undefined);
  assert.equal(enclosingCheckout(join(checkout, '.local', 'worktrees')), undefined);
  const roots = searchRoots(worktree, '/Users/someone');
  assert.deepEqual(roots.slice(0, 4), [join(worktree, '.local', 'data'), join(worktree, '.local', 'downloads'), join(checkout, '.local', 'data'), join(checkout, '.local', 'downloads')]);
  assert.deepEqual(searchRoots(checkout, '/Users/someone').slice(0, 2), [join(checkout, '.local', 'data'), join(checkout, '.local', 'downloads')]);
  assert.deepEqual(searchRoots(checkout, '/Users/someone').length, 5);
});
