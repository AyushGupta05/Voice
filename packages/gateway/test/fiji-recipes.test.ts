import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { profiles } from '../src/apps/index.js';
import { fijiTools } from '../src/apps/fiji-tools.js';
import { fijiInstructions } from '../src/apps/fiji-instructions.js';
import { loadRecipes, parseRecipe, parseResultLine, recipeArgument, recipeSentence, searchRecipes, RECIPE_DIR } from '../src/apps/fiji-recipes.js';
import { requiredOperationsFor } from '../src/bridge-readiness.js';
import { validateTool } from '../src/tools.js';
import { Runtime } from '../src/runtime.js';
import { Knowledge } from '../src/knowledge.js';
import type { Adapter } from '../src/bridge.js';

const { fiji } = profiles;
const root = resolve(fileURLToPath(new URL('../../..', import.meta.url)));

test('every recipe parses with its header fields, reads each declared param and is listed in VERIFIED.md', async () => {
  const recipes = await loadRecipes(root);
  assert.ok(recipes.length >= 20 && recipes.length <= 30, `${recipes.length} recipes`);
  const verified = await readFile(join(root, RECIPE_DIR, 'VERIFIED.md'), 'utf8');
  for (const recipe of recipes) {
    assert.equal(recipe.file, `${RECIPE_DIR}/${recipe.id}.ijm`, 'file name is the id');
    assert.ok(recipe.title.length > 5 && recipe.when.length > 20 && recipe.output.length > 20, `${recipe.id} header`);
    assert.ok(recipe.say, `${recipe.id} has a spoken sentence`);
    for (const param of recipe.params) assert.ok(param.note, `${recipe.id}.${param.name} is described`);
    // Non-interactive: no dialogs, waits or file pickers inside a recipe.
    assert.doesNotMatch(recipe.code, /waitForUser|getBoolean\(|getNumber\(|getString\(|Dialog\.create|File\.openDialog|getDirectory\(/, `${recipe.id} never asks`);
    assert.match(verified, new RegExp(`\\| \`${recipe.id}\` \\|`), `${recipe.id} has a VERIFIED.md row`);
  }
  assert.ok(recipes.some(recipe => recipe.id === 'nuclei-count-3d') && recipes.some(recipe => recipe.id === 'trackmate-spots') && recipes.some(recipe => recipe.id === 'coloc2'));
});

test('recipe headers are validated', () => {
  const good = '// @recipe demo-one\n// @title Demo recipe\n// @when a request that is long enough\n// @param size = 3 | radius, px\n// @output One RESULT line and nothing else.\n// @say Got {count}.\nsize = parseFloat(pennyParam("size", "3"));\nprint("RESULT count=1");\nfunction pennyParam(key, fallback) { return fallback; }\n';
  const recipe = parseRecipe('demo-one.ijm', good);
  assert.deepEqual(recipe.params, [{ name: 'size', default: 3, type: 'number', note: 'radius, px' }]);
  assert.throws(() => parseRecipe('x.ijm', good.replace('// @recipe demo-one\n', '')), /missing or invalid @recipe/);
  assert.throws(() => parseRecipe('x.ijm', good.replace('// @output One RESULT line and nothing else.\n', '')), /missing @output/);
  assert.throws(() => parseRecipe('x.ijm', good.replace('pennyParam("size", "3")', '3')), /never read with pennyParam/);
  assert.throws(() => parseRecipe('x.ijm', good.replace('print("RESULT count=1");', '')), /never prints a RESULT line/);
});

test('params are formatted like make_macro arguments and refused when unknown or mistyped', async () => {
  const recipes = await loadRecipes(root);
  const bleach = recipes.find(recipe => recipe.id === 'bleach-correction')!, nuclei = recipes.find(recipe => recipe.id === 'nuclei-count-2d')!;
  assert.equal(recipeArgument(bleach, { method: 'Exponential Fit', channel: 2 }), 'method=[Exponential Fit] channel=2');
  assert.equal(recipeArgument(nuclei, { watershed: false, minSize: 100, method: 'Li' }), 'watershed=false minSize=100 method=Li');
  assert.equal(recipeArgument(nuclei, { minSize: '120', exclude: 'true' }), 'minSize=120 exclude=true', 'spoken numbers and flags as text are accepted');
  assert.equal(recipeArgument(nuclei), '');
  assert.equal(recipeArgument(recipes.find(recipe => recipe.id === 'pairwise-stitch')!, { second: '[tileA' }), 'second=[[tileA]', 'a leading bracket is bracketed');
  assert.throws(() => recipeArgument(nuclei, { minsize: 5 }), /has no parameter "minsize"/);
  assert.throws(() => recipeArgument(nuclei, { minSize: 'big' }), /must be a number/);
  assert.throws(() => recipeArgument(nuclei, { watershed: 'yes' }), /must be true or false/);
  assert.throws(() => recipeArgument(nuclei, { method: "Otsu] channel=[2" }), /cannot contain/);
  assert.throws(() => recipeArgument(nuclei, { method: "Otsu channel=5" }), /cannot contain/, "a value cannot smuggle another key");
});

test('search finds the recipe for what researchers say', async () => {
  const recipes = await loadRecipes(root);
  const first = (query: string) => searchRecipes(recipes, query, 3).recipes[0]?.id;
  assert.equal(first('count nuclei in a 3D stack'), 'nuclei-count-3d');
  assert.equal(first('skeleton length of neurites'), 'skeleton-network');
  assert.equal(first('local thickness'), 'local-thickness');
  assert.equal(first('fibre orientation'), 'directionality');
  assert.equal(first('bleach correction for the time-lapse'), 'bleach-correction');
  assert.equal(first('spot detection with TrackMate'), 'trackmate-spots');
  assert.equal(first('Coloc 2 report'), 'coloc2');
  assert.equal(first('find maxima and count the spots'), 'spot-count');
  assert.equal(first('local threshold for uneven illumination'), 'local-threshold');
  assert.equal(first('kymograph'), 'kymograph');
  assert.equal(first('FRAP mobile fraction'), 'frap-recovery');
  const all = searchRecipes(recipes);
  assert.equal(all.total, recipes.length);
  assert.deepEqual(Object.keys(all.recipes[0]).sort(), ['id', 'output', 'params', 'title', 'when']);
});

test('the RESULT line becomes the spoken sentence, never a guess', () => {
  const values = parseResultLine('Coloc 2 report\nRESULT pearson=0.5320 tM1=0.0266 tM2=0.0213 costesP=1.0000 method=[Simple Ratio] unit=µm\n');
  assert.deepEqual(values, { pearson: 0.532, tM1: 0.0266, tM2: 0.0213, costesP: 1, method: 'Simple Ratio', unit: 'µm' });
  assert.equal(parseResultLine('no result here'), undefined);
  assert.equal(parseResultLine('RESULT count=1\nRESULT count=2')!.count, 2, 'the last RESULT line wins');
  assert.deepEqual(parseResultLine('RESULT dx=300.00 dy=0.00 r=1.0000 fused=[tile] dx=999 dy=888 r=0.99 x<->y]'), { dx: 300, dy: 0, r: 1, fused: 'tile' }, 'a title cannot overwrite a measurement');
  assert.equal(recipeSentence('Found {count} objects, mean area {meanArea:area}.', { count: 15, meanArea: 496.4, unit: 'pixels' }), 'Found 15 objects, mean area 496 square pixels.');
  assert.equal(recipeSentence('Mean volume {v:volume}.', { v: 3.96, unit: 'µm' }), 'Mean volume 3.96 µm³.');
  assert.equal(recipeSentence('Covers {f:%} of the {basis}, {area:area}.', { f: 0.3421, basis: 'image', area: 2.5e6, unit: 'micron' }), 'Covers 34% of the image, 2.50 mm².');
  assert.equal(recipeSentence('Length {l:len}.', { l: 15.05, unit: 'pixels' }), 'Length 15.1 pixels.');
  assert.equal(recipeSentence('Found {count}.', { error: 'no_image' }), undefined, 'a reported error goes to the model');
  assert.equal(recipeSentence('Pearson {pearson}.', { pearson: 'NaN' }), undefined, 'NaN is not spoken');
  assert.equal(recipeSentence('Half-time {halfTime} {timeUnit}.', parseResultLine('RESULT halfTime=Infinity timeUnit=frames')), undefined, 'infinity is not spoken');
  assert.equal(recipeSentence('Shift {d}.', { d: '-Infinity' }), undefined);
  assert.equal(recipeSentence('Found {count}.', {}), undefined);
});

test('fiji_recipes and fiji_run_recipe are declared without new bridge operations', async () => {
  const protocol = await readFile(join(root, 'docs/FIJI-PROTOCOL.md'), 'utf8');
  const list = fijiTools.find(tool => tool.name === 'fiji_recipes')!, run = fijiTools.find(tool => tool.name === 'fiji_run_recipe')!;
  assert.equal(list.operation, undefined); assert.equal(list.risk, 'read');
  assert.equal(run.operation, undefined); assert.equal(run.risk, 'code');
  assert.ok(list.description.length <= 600 && run.description.length <= 600);
  assert.ok(!requiredOperationsFor(fiji).includes('run_recipe'), 'the bridge is unchanged');
  assert.match(protocol, /fiji_recipes/); assert.match(protocol, /fiji_run_recipe/);
  validateTool('fiji_run_recipe', { id: 'nuclei-count-2d', params: { channel: 3, method: 'Otsu', exclude: true } }, fiji);
  assert.throws(() => validateTool('fiji_run_recipe', {}, fiji), /Invalid arguments/);
  assert.throws(() => validateTool('fiji_run_recipe', { id: '../etc/passwd' }, fiji), /Invalid arguments/);
  assert.throws(() => validateTool('fiji_run_recipe', { id: 'x', code: 'print(1);' }, fiji), /Invalid arguments/, 'the model cannot pass code through a recipe');
  validateTool('fiji_recipes', { query: 'nuclei' }, fiji);
  assert.equal(fiji.acknowledgmentFor('fiji_run_recipe', { id: 'coloc2' }, {}), 'On it.');
  assert.ok(fiji.longRunningTools.has('fiji_run_recipe'));
  assert.equal(fiji.requestContinuesAfter('fiji_run_recipe', 'run Coloc 2 on these channels'), false, 'the headline sentence answers it');
  assert.equal(fiji.requestContinuesAfter('fiji_run_recipe', 'what is the Spearman correlation with Coloc 2'), true, 'another statistic goes to the model');
  assert.equal(fiji.requestContinuesAfter('fiji_run_recipe', 'count the nuclei in 3D and give me the volume of each'), true);
  assert.match(fijiInstructions, /fiji_recipes/);
  assert.ok(fijiInstructions.length <= 4400, `instructions are ${fijiInstructions.length} characters`);
});

test('fiji_run_recipe dispatches the recipe file as run_macro with the params as its argument, through the decision path', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'luma-fiji-recipes-'));
  await symlink(join(root, 'skills'), join(directory, 'skills'));
  const state = join(directory, '.local/state'); await mkdir(state, { recursive: true });
  const calls: Array<{ operation: string; args: Record<string, unknown> }> = [];
  const log = { value: 'RESULT count=15 meanArea=496.40 meanIntensity=124.42 unit=pixels channel=3 threshold=Otsu:64-255\n' };
  const adapter: Adapter = { execute: async (operation, args = {}) => {
    calls.push({ operation, args });
    if (operation === 'context') return { contextKey: 'fiji-1', application: 'Fiji', image: { id: -2, title: 'FluorescentCells.tif', channels: 3 } };
    if (operation === 'run_macro') return { result: { elapsedMs: 80, completed: true, result: '', aborted: false, log: log.value }, checkpoint: 'c1' };
    return { ok: true };
  } };
  try {
    const runtime = new Runtime(adapter, new Knowledge(fiji), state, { autoApprove: true, profile: fiji });
    const listed = await runtime.call('fiji_recipes', { query: 'count nuclei' }) as { recipes: Array<{ id: string }> };
    assert.equal(listed.recipes[0].id, 'nuclei-count-2d'); assert.equal(calls.length, 0, 'the catalog is answered by the gateway');
    const outcome = await runtime.call('fiji_run_recipe', { id: 'nuclei-count-2d', params: { channel: 3, minSize: 100, exclude: true } }) as Record<string, unknown>;
    const macro = calls.find(call => call.operation === 'run_macro')!;
    const file = await readFile(join(root, RECIPE_DIR, 'nuclei-count-2d.ijm'), 'utf8');
    assert.equal(macro.args.code, file, 'the exact recipe file is the macro');
    assert.equal(macro.args.argument, 'channel=3 minSize=100 exclude=true');
    assert.equal(outcome.status, 'completed'); assert.equal(outcome.autoApproved, true); assert.equal(outcome.tool, 'fiji_run_macro');
    assert.deepEqual(outcome.arguments, { recipe: 'nuclei-count-2d', argument: 'channel=3 minSize=100 exclude=true' }, 'the code is not echoed back to the model');
    assert.deepEqual(outcome.summary, { count: 15, meanArea: 496.4, meanIntensity: 124.42, unit: 'pixels', channel: 3, threshold: 'Otsu:64-255' });
    assert.equal(fiji.localSentenceFor('fiji_run_recipe', {}, outcome), 'Found 15 objects, mean area 496 square pixels.');
    const action = runtime.actions.get(outcome.actionId as string)!;
    assert.equal(action.tool, 'fiji_run_macro'); assert.equal(action.decidedBy, 'policy');
    log.value = 'RESULT error=needs_two_channels\n';
    const stopped = await runtime.call('fiji_run_recipe', { id: 'coloc2' }) as Record<string, unknown>;
    assert.equal(stopped.status, 'failed'); assert.match(String(stopped.error), /coloc2 stopped: needs_two_channels/);
    assert.equal(fiji.localSentenceFor('fiji_run_recipe', {}, stopped), undefined, 'a reported error goes back to the model');
        await assert.rejects(runtime.call('fiji_run_recipe', { id: 'no-such-recipe' }), /No recipe "no-such-recipe"/);
    await assert.rejects(runtime.call('fiji_run_recipe', { id: 'nuclei-count-2d', params: { radius: 3 } }), /has no parameter "radius"/);
    assert.equal(calls.filter(call => call.operation === 'run_macro').length, 2, 'refused requests never reach the bridge');

    const review = new Runtime(adapter, new Knowledge(fiji), state, { autoApprove: false, profile: fiji });
    const pending = await review.call('fiji_run_recipe', { id: 'coloc2' }) as Record<string, unknown>;
    assert.equal(pending.status, 'approval_required'); assert.equal(pending.spoken, undefined);
    assert.equal(fiji.localSentenceFor('fiji_run_recipe', {}, pending), undefined);
    assert.equal(calls.filter(call => call.operation === 'run_macro').length, 2, 'review mode dispatches nothing');
    const card = review.actions.get(pending.actionId as string)!;
    assert.equal(card.tool, 'fiji_run_macro'); assert.match(String(card.arguments.code), /@recipe coloc2/, 'the review card shows the exact code');
    // After the human approves, the decision is finished like an immediate run: summary, sentence, failure mapping.
    log.value = 'RESULT pearson=0.5320 tM1=0.0266 tM2=0.0213 costesP=1.0000 channelA=1 channelB=2\n';
    const decided = await review.decide(card.id, true);
    const decision = { actionId: decided.id, tool: decided.tool, status: decided.status, result: decided.result, error: decided.error };
    const finished = fiji.finalizeDecision!('fiji_run_recipe', pending, decision) as Record<string, unknown>;
    assert.equal(finished.status, 'completed'); assert.deepEqual(finished.arguments, { recipe: 'coloc2', argument: '' });
    assert.equal((finished.summary as Record<string, unknown>).pearson, 0.532);
    assert.equal(fiji.localSentenceFor('fiji_run_recipe', {}, finished), 'Coloc 2: Pearson 0.53, Manders tM1 0.03 and tM2 0.02 above the Costes thresholds, Costes P 1.');
    log.value = 'RESULT error=needs_two_channels\n';
    const second = await review.call('fiji_run_recipe', { id: 'coloc2' }) as Record<string, unknown>;
    const secondCard = await review.decide(second.actionId as string, true);
    const stoppedAfterReview = fiji.finalizeDecision!('fiji_run_recipe', second, { actionId: secondCard.id, tool: secondCard.tool, status: secondCard.status, result: secondCard.result, error: secondCard.error }) as Record<string, unknown>;
    assert.equal(stoppedAfterReview.status, 'failed'); assert.match(String(stoppedAfterReview.error), /needs_two_channels/);
    assert.equal(fiji.finalizeDecision!('fiji_run_macro', pending, decision), undefined, 'other tools keep their decision');
  } finally { await rm(directory, { recursive: true, force: true }); }
});
