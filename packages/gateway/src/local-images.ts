import { readdir, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, extname, basename } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execFileAsync = promisify(execFile);

const IMAGE_EXTENSIONS = new Set(['.ndpi', '.svs', '.tif', '.tiff', '.czi', '.scn', '.mrxs', '.vsi', '.bif', '.qptiff', '.ome.tif', '.ome.tiff', '.qpproj', '.jpg', '.jpeg', '.png', '.gif', '.lsm', '.nd2', '.lif', '.oib', '.oif', '.ims']);
/** `folder` entries are directories whose name matches and that directly hold `images` image files ("the bbbc folder"). */
export type LocalImage = { path: string; name: string; sizeBytes: number; kind: 'image' | 'project' | 'folder'; images?: number };

function extension(name: string) {
  const lower = name.toLowerCase();
  if (lower.endsWith('.ome.tif')) return '.ome.tif';
  if (lower.endsWith('.ome.tiff')) return '.ome.tiff';
  return extname(lower);
}
/** Words that describe what the researcher wants rather than name it ("the mitosis movie" is mitosis.tif). */
const FILLER = /^(?:open|show|load|find|get|bring|up|please|the|a|an|my|this|that|one|movie|video|image|images|picture|pictures|photo|file|files|stack|sample|series|folder|directory|dataset)$/;
/** Spoken names for what file names abbreviate: a query word matches when the name contains any of these. */
const ALIASES: Array<[RegExp, RegExp]> = [
  [/^(?:h&e|he|hne)$/, /histopath|h&e|hne|eosin|idc|(?:^|[^a-z])he(?:[^a-z]|$)/],
  [/^(?:nuclei|nucleus|nuclear)$/, /nucle|bbbc|dapi|hoechst/],
];
/** Query words with spoken "h and e" joined and filler dropped; every word is kept when all of them are filler. */
/** 1 for ground-truth masks/labels (often named exactly like the raw image) unless the query asks for them, else 0. */
function derivedRank(query: string) {
  const wantsMask = /\b(mask|masks|foreground|label|labels|ground ?truth|annotation|segmentation)\b/i.test(query);
  return (path: string) => !wantsMask && /\/(foreground|masks?|labels?|ground[_ -]?truth|gt|annotations?|segmentations?)\//i.test(path) ? 1 : 0;
}
export function queryWords(query: string) {
  const digits: Record<string, string> = { zero: '0', oh: '0', one: '1', two: '2', three: '3', four: '4', five: '5', six: '6', seven: '7', eight: '8', nine: '9' };
  const spoken = query.toLowerCase().replace(/\bh\s*(?:&|and|n)\s*e\b/g, 'h&e')
    // "BBBC zero zero eight" → "bbbc008"; Transcribe also writes 008 as "OO8".
    .replace(/\b([a-z]+)((?:\s+(?:zero|oh|one|two|three|four|five|six|seven|eight|nine)){2,})\b/g, (_, word: string, run: string) => word + run.trim().split(/\s+/).map(d => digits[d]).join(''))
    // "channel one" / "slice 2" → channel1 / slice2, as microscopy file names write them.
    .replace(/\b(channel|slice|series|field|well|ch|w|c|s)\s+(zero|one|two|three|four|five|six|seven|eight|nine|\d+)\b/g, (_, word: string, d: string) => word + (digits[d] ?? d))
    .replace(/\b([a-z]{2,}?)(o+)(\d+)\b/g, (_, word: string, os: string, rest: string) => word + '0'.repeat(os.length) + rest);
  const words = spoken.split(/[\s,]+/).filter(Boolean);
  const kept = words.filter(word => !FILLER.test(word));
  return kept.length ? kept : words;
}
/** Loose match: every query word (filler dropped, aliases expanded) appears in the name, ignoring case, spaces, dashes and underscores. */
export function imageNameMatches(name: string, query: string) {
  const normalise = (value: string) => value.toLowerCase().replace(/[\s_\-.]+/g, '');
  const target = normalise(name), raw = name.toLowerCase();
  const words = queryWords(query);
  // An aliased word matches through its alias only, so a short "he" never matches inside "hela".
  return words.length === 0 || words.every(word => { const alias = ALIASES.find(([spoken]) => spoken.test(word)); return alias ? alias[1].test(raw) : target.includes(normalise(word)); });
}
/** Edit distance between a spoken word and the closest same-length window of a file name ("gala" vs "hela-cells" = 2). */
export function nameDistance(name: string, word: string) {
  const target = name.toLowerCase().replace(/[\s_\-.]+/g, ''), w = word.toLowerCase();
  if (target.includes(w)) return 0;
  let best = Infinity;
  for (let start = 0; start + w.length <= target.length; start++) {
    const window = target.slice(start, start + w.length); let d = 0;
    for (let i = 0; i < w.length; i++) if (window[i] !== w[i]) d++;
    best = Math.min(best, d);
  }
  return best;
}
/** Misheard names ("Gala cells" for HeLa cells): every word must match exactly or within half its letters, and at least one word exactly. */
export function fuzzyNameScore(name: string, query: string): number | undefined {
  const words = queryWords(query).filter(word => word.length >= 3 && !/^(the|image|picture|file|one|open)$/.test(word));
  if (!words.length) return undefined;
  let total = 0, exact = 0;
  for (const word of words) { const d = nameDistance(name, word); if (d === 0) exact++; else if (word.length < 4 || d > Math.ceil(word.length / 2)) return undefined; total += d; }
  return exact > 0 ? total : undefined;
}
/** The last two folder names of a path, e.g. "bbbc008 images". */
const folderWords = (directory: string) => directory.split('/').filter(Boolean).slice(-2).join(' ');
async function directImageCount(directory: string) {
  try { return (await readdir(directory, { withFileTypes: true })).filter(entry => entry.isFile() && !entry.name.startsWith('.') && IMAGE_EXTENSIONS.has(extension(entry.name)) && extension(entry.name) !== '.qpproj').length; }
  catch { return 0; }
}
/** Where a researcher's slides usually are: the project's data folder first, then common user folders, two levels deep. */
export function searchRoots(root: string, home = homedir()) {
  return [join(root, '.local', 'data'), join(home, 'Desktop'), join(home, 'Downloads'), join(home, 'Documents')];
}
export async function findLocalImages(root: string, query = '', limit = 20, roots = searchRoots(root), useSpotlight = true): Promise<{ matches: LocalImage[]; searched: string[]; truncated: boolean }> {
  const pool = Math.max(limit, 500);
  const matches: LocalImage[] = [];
  const seen = new Set<string>();
  let truncated = false;
  async function visit(directory: string, depth: number) {
    let entries;
    try { entries = await readdir(directory, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      if (entry.name.startsWith('.')) continue;
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        // A named folder ("run it on the bbbc folder") is a match of its own when it directly holds images.
        if (query.trim() && !seen.has(path) && imageNameMatches(folderWords(path), query)) {
          const images = await directImageCount(path);
          if (images > 0) { if (matches.length >= pool) { truncated = true; return; } seen.add(path); matches.push({ path, name: entry.name, sizeBytes: 0, kind: 'folder', images }); }
        }
        if (depth < 3) await visit(path, depth + 1); continue;
      }
      if (!entry.isFile()) continue;
      const ext = extension(entry.name);
      // The folder names count too: "the BBBC nuclei image" lives in …/bbbc008/images/.
      if (!IMAGE_EXTENSIONS.has(ext) || !imageNameMatches(`${folderWords(directory)} ${entry.name}`, query) || seen.has(path)) continue;
      if (matches.length >= pool) { truncated = true; return; }
      let size = 0;
      try { size = (await stat(path)).size; } catch { continue; }
      seen.add(path);
      matches.push({ path, name: basename(entry.name), sizeBytes: size, kind: ext === '.qpproj' ? 'project' : 'image' });
    }
  }
  for (const directory of roots) await visit(directory, 0);
  if (!matches.length && query.trim()) {
    // Nothing matched exactly: retry with misheard-word tolerance and return the closest names first.
    const scored: Array<LocalImage & { score: number }> = [];
    async function fuzzy(directory: string, depth: number) {
      let entries; try { entries = await readdir(directory, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        if (entry.name.startsWith('.')) continue;
        const path = join(directory, entry.name);
        if (entry.isDirectory()) { if (depth < 3) await fuzzy(path, depth + 1); continue; }
        const ext = extension(entry.name); if (!entry.isFile() || !IMAGE_EXTENSIONS.has(ext) || seen.has(path)) continue;
        // Misheard words are scored against the file name only: folder names (temp or dated folders) would add noise.
        const score = fuzzyNameScore(entry.name, query); if (score === undefined) continue;
        let size = 0; try { size = (await stat(path)).size; } catch { continue; }
        seen.add(path); scored.push({ path, name: basename(entry.name), sizeBytes: size, kind: ext === '.qpproj' ? 'project' : 'image', score });
      }
    }
    for (const directory of roots) await fuzzy(directory, 0);
    const derivedFuzzy = derivedRank(query);
    scored.sort((a, b) => derivedFuzzy(a.path) - derivedFuzzy(b.path) || a.score - b.score || b.sizeBytes - a.sizeBytes);
    for (const { score: _score, ...match } of scored.slice(0, limit)) matches.push(match);
    if (matches.length) return { matches, searched: roots, truncated: scored.length > limit };
  }
  // Spotlight covers the rest of the home folder (any depth) for slides the researcher saved elsewhere.
  // Spotlight (up to 3 s) only when the usual folders found nothing: "open the hela cells image" must feel instant.
  for (const path of useSpotlight && !matches.some(match => match.kind !== 'folder') ? await spotlightImages(query) : []) {
    if (seen.has(path) || matches.length >= pool) { if (!seen.has(path)) truncated = true; continue; }
    let size = 0;
    try { size = (await stat(path)).size; } catch { continue; }
    seen.add(path);
    matches.push({ path, name: basename(path), sizeBytes: size, kind: extension(path) === '.qpproj' ? 'project' : 'image' });
  }
  // Images largest first, then folders with the most images.
  // Ground-truth masks and label images often share the raw image's file name (BBBC008 images/ vs foreground/): the raw image
  // comes first unless the researcher asked for the mask.
  const derived = derivedRank(query);
  matches.sort((a, b) => Number(a.kind === 'folder') - Number(b.kind === 'folder') || derived(a.path) - derived(b.path) || b.sizeBytes - a.sizeBytes || (b.images ?? 0) - (a.images ?? 0));
  // Ranked over the whole pool, then cut: walk order (foreground/ before images/) must not decide what comes first.
  if (matches.length > limit) { matches.length = limit; truncated = true; }
  return { matches, searched: useSpotlight ? [...roots, 'Spotlight: home folder'] : roots, truncated };
}
/** macOS Spotlight query for slide/project files by extension; silent on any failure (non-macOS, indexing off, timeout). */
export async function spotlightImages(query: string, home = homedir(), timeoutMs = 3000): Promise<string[]> {
  if (process.platform !== 'darwin') return [];
  const names = [...IMAGE_EXTENSIONS].filter(ext => !ext.startsWith('.ome') && ext !== '.jpg' && ext !== '.jpeg' && ext !== '.png').map(ext => `kMDItemFSName == "*${ext}"c`).join(' || ');
  try {
    const { stdout } = await execFileAsync('mdfind', ['-onlyin', home, names], { timeout: timeoutMs, maxBuffer: 4 * 1024 * 1024 });
    return stdout.split('\n').map(line => line.trim()).filter(line => line && imageNameMatches(basename(line), query)).slice(0, 200);
  } catch { return []; }
}
