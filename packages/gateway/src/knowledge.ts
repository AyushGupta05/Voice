import { readdir, readFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
type Document = { title: string; source: string; text: string };
export class Knowledge {
  private documents: Document[] = [];
  async load(root: string) {
    for (const directory of ['skills/qupath', '.local/upstream/qupath-docs/docs']) {
      const walk = async (path: string): Promise<void> => {
        let entries;
        try { entries = await readdir(path, { withFileTypes: true }); } catch { return; }
        for (const entry of entries) {
          if (entry.name.startsWith('.')) continue;
          const full = join(path, entry.name);
          if (entry.isDirectory()) await walk(full);
          else if (/\.(md|rst)$/.test(entry.name)) {
            const text = (await readFile(full, 'utf8')).replace(/```\{raw\} html[\s\S]*?```/g, '').replace(/<[^>]+>/g, '').replace(/^:::[^\n]*$/gm, '');
            this.documents.push({ title: text.match(/^#\s+(.+)$/m)?.[1] ?? entry.name, source: relative(root, full), text });
          }
        }
      };
      await walk(join(root, directory));
    }
  }
  search(query: string) {
    const terms = query.toLowerCase().match(/[a-z0-9_]+/g) ?? [];
    const scored = this.documents.map(doc => {
      const lower = doc.text.toLowerCase();
      const score = terms.reduce((n, term) => n + (doc.title.toLowerCase().includes(term) ? 12 : 0) + Math.min(8, lower.split(term).length - 1), 0);
      const lines = doc.text.split('\n');
      const best = lines.map((line,index) => ({ index, score: terms.reduce((score,term) => score + (line.toLowerCase().includes(term) ? 1 : 0),0) })).sort((a,b) => b.score-a.score)[0]?.index ?? 0;
      return { title: doc.title, source: doc.source, score, excerpt: lines.slice(Math.max(0,best-2),Math.max(0,best-2)+35).join('\n').slice(0,3500) };
    }).filter(doc => doc.score > 0).sort((a,b) => b.score-a.score).slice(0,5);
    return { documentCount: this.documents.length, results: scored, note: 'Local knowledge and official upstream docs are guidance, not current application state. Inspect live tools before acting.' };
  }
}
