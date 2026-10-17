import { readdir, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, extname, basename } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execFileAsync = promisify(execFile);

const IMAGE_EXTENSIONS = new Set(['.ndpi', '.svs', '.tif', '.tiff', '.czi', '.scn', '.mrxs', '.vsi', '.bif', '.qptiff', '.ome.tif', '.ome.tiff', '.qpproj', '.jpg', '.jpeg', '.png']);
export type LocalImage = { path: string; name: string; sizeBytes: number; kind: 'image' | 'project' };

function extension(name: string) {
  const lower = name.toLowerCase();
  if (lower.endsWith('.ome.tif')) return '.ome.tif';
  if (lower.endsWith('.ome.tiff')) return '.ome.tiff';
  return extname(lower);
}
/** Loose match: every word of the query appears in the file name, ignoring case, spaces, dashes and underscores. */
export function imageNameMatches(name: string, query: string) {
  const normalise = (value: string) => value.toLowerCase().replace(/[\s_\-.]+/g, '');
  const target = normalise(name);
  const words = query.toLowerCase().split(/[\s,]+/).filter(Boolean).map(normalise);
  return words.length === 0 || words.every(word => target.includes(word));
}
/** Where a researcher's slides usually are: the project's data folder first, then common user folders, two levels deep. */
export function searchRoots(root: string, home = homedir()) {
  return [join(root, '.local', 'data'), join(home, 'Desktop'), join(home, 'Downloads'), join(home, 'Documents')];
}
export async function findLocalImages(root: string, query = '', limit = 20, roots = searchRoots(root)): Promise<{ matches: LocalImage[]; searched: string[]; truncated: boolean }> {
  const matches: LocalImage[] = [];
  const seen = new Set<string>();
  let truncated = false;
  async function visit(directory: string, depth: number) {
    let entries;
    try { entries = await readdir(directory, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      if (entry.name.startsWith('.')) continue;
      const path = join(directory, entry.name);
      if (entry.isDirectory()) { if (depth < 2) await visit(path, depth + 1); continue; }
      if (!entry.isFile()) continue;
      const ext = extension(entry.name);
      if (!IMAGE_EXTENSIONS.has(ext) || !imageNameMatches(entry.name, query) || seen.has(path)) continue;
      if (matches.length >= limit) { truncated = true; return; }
      let size = 0;
      try { size = (await stat(path)).size; } catch { continue; }
      seen.add(path);
      matches.push({ path, name: basename(entry.name), sizeBytes: size, kind: ext === '.qpproj' ? 'project' : 'image' });
    }
  }
  for (const directory of roots) await visit(directory, 0);
  // Spotlight covers the rest of the home folder (any depth) for slides the researcher saved elsewhere.
  for (const path of await spotlightImages(query)) {
    if (seen.has(path) || matches.length >= limit) { if (!seen.has(path)) truncated = true; continue; }
    let size = 0;
    try { size = (await stat(path)).size; } catch { continue; }
    seen.add(path);
    matches.push({ path, name: basename(path), sizeBytes: size, kind: extension(path) === '.qpproj' ? 'project' : 'image' });
  }
  matches.sort((a, b) => b.sizeBytes - a.sizeBytes);
  return { matches, searched: [...roots, 'Spotlight: home folder'], truncated };
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
