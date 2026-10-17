import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { LocalToolInput } from './profile.js';
import { completedPayload } from './profile.js';

/** Verified, parameterised ImageJ macro recipes under skills/fiji/recipes (see VERIFIED.md there). Each file starts
 * with a header block (`// @recipe`, `@title`, `@when`, `@param name = default | note`, `@output`, `@say`) and prints one
 * `RESULT key=value …` line at the end. Parameters reach the macro as getArgument() `key=value` pairs, the same
 * convention as make_macro/run_workflow (`key=[text with spaces]`), read by the recipe's own pennyParam(). */
export const RECIPE_DIR = 'skills/fiji/recipes';
export type RecipeParam = { name: string; default: string | number | boolean; type: 'number' | 'boolean' | 'string'; note: string };
export type Recipe = { id: string; title: string; when: string; params: RecipeParam[]; output: string; say?: string; file: string; code: string };
export type RecipeValue = string | number | boolean;

const ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
function typed(literal: string): Pick<RecipeParam, 'default' | 'type'> {
  if (literal === 'true' || literal === 'false') return { default: literal === 'true', type: 'boolean' };
  if (/^-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?$/i.test(literal)) return { default: Number(literal), type: 'number' };
  return { default: literal, type: 'string' };
}
/** Parses one recipe file; throws with the file name when a required header field is missing or malformed. */
export function parseRecipe(file: string, code: string): Recipe {
  const header = new Map<string, string>(); const params: RecipeParam[] = [];
  for (const line of code.split('\n')) {
    const match = line.match(/^\/\/ @(\w+)\s+(.*)$/);
    if (!match) { if (line.startsWith('//')) continue; break; }
    const [, key, value] = match;
    if (key === 'param') {
      const param = value.match(/^(\w+)\s*=\s*(.*?)\s*(?:\|\s*(.*))?$/);
      if (!param) throw new Error(`${file}: malformed @param "${value}"`);
      params.push({ name: param[1], ...typed(param[2]), note: param[3]?.trim() ?? '' });
    } else header.set(key, value.trim());
  }
  const id = header.get('recipe');
  if (!id || !ID.test(id)) throw new Error(`${file}: missing or invalid @recipe id`);
  for (const field of ['title', 'when', 'output']) if (!header.get(field)) throw new Error(`${file}: missing @${field}`);
  for (const param of params) if (!code.includes(`pennyParam("${param.name}", `)) throw new Error(`${file}: @param ${param.name} is never read with pennyParam`);
  if (params.length && !/function pennyParam\(key, fallback\)/.test(code)) throw new Error(`${file}: declares params but has no pennyParam function`);
  if (!/print\("RESULT /.test(code)) throw new Error(`${file}: never prints a RESULT line`);
  return { id, title: header.get('title')!, when: header.get('when')!, params, output: header.get('output')!, say: header.get('say'), file, code };
}
/** Every recipe in `<root>/skills/fiji/recipes`, sorted by id. Read fresh each time: the folder is small and edits apply at once. */
export async function loadRecipes(root: string): Promise<Recipe[]> {
  const dir = join(root, RECIPE_DIR);
  const files = (await readdir(dir)).filter(name => name.endsWith('.ijm')).sort();
  const recipes = await Promise.all(files.map(async name => parseRecipe(`${RECIPE_DIR}/${name}`, await readFile(join(dir, name), 'utf8'))));
  const ids = new Set<string>();
  for (const recipe of recipes) { if (ids.has(recipe.id)) throw new Error(`Duplicate recipe id ${recipe.id}`); ids.add(recipe.id); }
  return recipes.sort((a, b) => a.id.localeCompare(b.id));
}
const words = (text: string) => text.toLowerCase().match(/[a-z0-9]+/g) ?? [];
const stop = new Set(['the', 'an', 'of', 'in', 'on', 'my', 'me', 'this', 'that', 'these', 'those', 'to', 'for', 'and', 'with', 'how', 'what', 'is', 'are', 'do', 'it', 'can', 'you', 'please', 'all', 'from']);
/** Recipes ranked by how many query words their id, title and "when" text contain; all of them without a query. */
export function searchRecipes(recipes: Recipe[], query = '', limit = 30) {
  const terms = [...new Set(words(query).filter(word => word.length > 1 && !stop.has(word)))];
  const scored = recipes.map(recipe => {
    const haystack = `${recipe.id} ${recipe.title} ${recipe.when}`.toLowerCase();
    const score = terms.reduce((sum, term) => sum + (haystack.includes(term) ? (recipe.id.includes(term) || recipe.title.toLowerCase().includes(term) ? 2 : 1) : 0), 0);
    return { recipe, score };
  }).filter(entry => !terms.length || entry.score > 0).sort((a, b) => b.score - a.score || a.recipe.id.localeCompare(b.recipe.id));
  return {
    total: scored.length,
    recipes: scored.slice(0, limit).map(({ recipe }) => ({ id: recipe.id, title: recipe.title, when: recipe.when, output: recipe.output,
      params: recipe.params.map(param => ({ name: param.name, default: param.default, ...(param.note ? { note: param.note } : {}) })) })),
    note: 'Run one with fiji_run_recipe {id, params}; it acts on the active image. Only pass params you need to change.',
  };
}
/** The macro argument for the given params, exactly as make_macro/run_workflow format it. Unknown names and wrong
 * types are refused, so a model typo never silently falls back to a default. */
export function recipeArgument(recipe: Recipe, params: Record<string, RecipeValue> = {}): string {
  const parts: string[] = [];
  for (const [name, given] of Object.entries(params)) {
    const declared = recipe.params.find(param => param.name === name);
    if (!declared) throw new Error(`Recipe ${recipe.id} has no parameter "${name}". Parameters: ${recipe.params.map(param => param.name).join(', ') || 'none'}.`);
    // Spoken numbers and flags often arrive as text ("5", "true"); they are accepted when they parse exactly.
    const value = declared.type === 'number' && typeof given === 'string' && given.trim() !== '' && Number.isFinite(Number(given)) ? Number(given)
      : declared.type === 'boolean' && (given === 'true' || given === 'false') ? given === 'true' : given;
    if (declared.type === 'number' && (typeof value !== 'number' || !Number.isFinite(value))) throw new Error(`Recipe ${recipe.id} parameter ${name} must be a number.`);
    if (declared.type === 'boolean' && typeof value !== 'boolean') throw new Error(`Recipe ${recipe.id} parameter ${name} must be true or false.`);
    const text = String(value);
    // pennyParam() finds " key=" anywhere in the argument and ends a bracketed value at the first "]".
    if (/[\]=\n\r]/.test(text)) throw new Error(`Recipe ${recipe.id} parameter ${name} cannot contain "]", "=" or line breaks.`);
    parts.push(`${name}=${text === '' || /\s/.test(text) ? `[${text}]` : text}`);
  }
  return parts.join(' ');
}
/** key=value pairs of the last `RESULT …` line in the macro's Log text; values in [brackets] may hold spaces. */
export function parseResultLine(log: unknown): Record<string, string | number> | undefined {
  if (typeof log !== 'string') return undefined;
  const line = log.split(/\r?\n/).map(entry => entry.trim()).filter(entry => entry.startsWith('RESULT ')).at(-1);
  if (!line) return undefined;
  const values: Record<string, string | number> = {};
  for (const match of line.slice(7).matchAll(/(\w+)=(\[[^\]]*\]|\S+)/g)) {
    const raw = match[2].startsWith('[') ? match[2].slice(1, -1) : match[2];
    const number = Number(raw);
    values[match[1]] = raw !== '' && /^-?[\d.]+(?:e-?\d+)?$/i.test(raw) && Number.isFinite(number) ? number : raw;
  }
  return values;
}
const round = (value: number) => Number.isInteger(value) ? String(value) : Math.abs(value) >= 100 ? value.toFixed(0) : Math.abs(value) >= 10 ? value.toFixed(1) : value.toFixed(2);
const micron = (unit: string) => /^(?:µm|um|μm|micron|microns)$/i.test(unit);
const pixel = (unit: string) => !unit || /^pixels?$/i.test(unit);
/** A spoken quantity: `area` in unit² (square pixels, mm² from 10⁶ µm²), `len` in the unit, `volume` in unit³, `%` a 0–1 fraction as a percentage. */
function spoken(value: number, kind: string | undefined, unit: string): string {
  if (kind === '%') { const percent = value * 100; return `${Math.abs(percent) >= 10 ? Math.round(percent) : Number(percent.toFixed(1))}%`; }
  if (kind === 'area') return pixel(unit) ? `${round(value)} square pixels` : micron(unit) && value >= 1e6 ? `${round(value / 1e6)} mm²` : `${round(value)} ${micron(unit) ? 'µm' : unit}²`;
  if (kind === 'volume') return pixel(unit) ? `${round(value)} cubic pixels` : `${round(value)} ${micron(unit) ? 'µm' : unit}³`;
  if (kind === 'len') return `${round(value)} ${pixel(unit) ? 'pixels' : micron(unit) ? 'µm' : unit}`;
  return round(value);
}
/** The recipe's @say sentence filled from its RESULT values ({key}, {key:%}, {key:area}, {key:len}, {key:volume}).
 * Undefined when the template is absent, an error was reported or any placeholder has no finite number or text,
 * so the turn goes back to the model instead of speaking a guess. */
export function recipeSentence(template: string | undefined, values: Record<string, string | number> | undefined): string | undefined {
  if (!template || !values || values.error !== undefined) return undefined;
  const unit = typeof values.unit === 'string' ? values.unit : '';
  let missing = false;
  const sentence = template.replace(/\{(\w+)(?::(%|area|len|volume))?\}/g, (_, key: string, kind: string | undefined) => {
    const value = values[key];
    if (typeof value === 'number' && Number.isFinite(value)) return spoken(value, kind, unit);
    if (typeof value === 'string' && value && !/^nan$/i.test(value) && !kind) return value.replace(/_/g, ' ');
    missing = true; return '';
  });
  return missing ? undefined : sentence;
}
/** fiji_recipes and fiji_run_recipe. A recipe runs as a nested fiji_run_macro call, so it takes the same recorded
 * decision, audit and checkpoint path (and, under LUMA_APPROVALS=review, the same review card with the exact code). */
export function recipeTool(input: LocalToolInput): Promise<unknown> | undefined {
  if (input.name === 'recipes') return loadRecipes(input.root).then(recipes => searchRecipes(recipes, input.args.query as string | undefined, (input.args.limit as number | undefined) ?? 30));
  if (input.name !== 'run_recipe') return undefined;
  return (async () => {
    const id = input.args.id as string;
    const recipe = (await loadRecipes(input.root)).find(entry => entry.id === id);
    if (!recipe) throw new Error(`No recipe "${id}". List them with fiji_recipes.`);
    const argument = recipeArgument(recipe, (input.args.params ?? {}) as Record<string, RecipeValue>);
    const outcome = await input.runtime.call('fiji_run_macro', { code: recipe.code, argument }, input.signal, input.beforeDispatch) as Record<string, unknown>;
    const payload = completedPayload(outcome);
    const summary = parseResultLine(payload?.log);
    const spoken = payload?.completed === true ? recipeSentence(recipe.say, summary) : undefined;
    const note = payload?.running === true ? 'Still running; fiji_status shows when it finishes and fiji_log then holds the RESULT line.'
      : payload && !summary ? 'No RESULT line came back; read the log for the macro error.' : summary?.error !== undefined ? `The recipe stopped: ${summary.error}.` : undefined;
    // The macro text is the recipe file; the model gets its id and argument instead of 32 kB of code.
    return { ...outcome, arguments: { recipe: recipe.id, argument }, recipe: recipe.id, title: recipe.title, ...(summary ? { summary } : {}), ...(spoken ? { spoken } : {}), ...(note ? { note } : {}) };
  })();
}
