import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import type { Arguments } from './tools.js';
export type Context = { contextKey: string; image?: { name: string }; [key: string]: unknown };
export interface Adapter {
  execute(operation: string, args?: Arguments, options?: { expectedContext?: string; approved?: boolean; expectedControl?: unknown }): Promise<unknown>;
}
export class QuPathAdapter implements Adapter {
  constructor(private root: string, private port = 49556) {}
  async execute(operation: string, args: Arguments = {}, options: { expectedContext?: string; approved?: boolean; expectedControl?: unknown } = {}) {
    let token: string;
    try { token = (await readFile(resolve(this.root, '.local/state/bridge-token'), 'utf8')).trim(); }
    catch { throw new Error('QuPath bridge is not configured. Run ./script/run-qupath.sh first.'); }
    let response: Response;
    try {
      response = await fetch(`http://127.0.0.1:${this.port}/execute`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ operation, arguments: args, ...options }), signal: AbortSignal.timeout(125000),
      });
    } catch { throw new Error('Cannot reach QuPath. Start it with ./script/run-qupath.sh. A timed-out operation may still be running; inspect the application before retrying.'); }
    const body = await response.json() as { result?: unknown; error?: string };
    if (!response.ok) throw new Error(body.error ?? `QuPath returned ${response.status}`);
    return body.result;
  }
}
