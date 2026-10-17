import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import type { Arguments } from './tools.js';
import { bridgeReadiness } from './bridge-readiness.js';
import { activeProfile, profiles, type AppProfile } from './apps/index.js';
export type Context = { contextKey: string; image?: { name: string }; [key: string]: unknown };
export interface Adapter {
  execute(operation: string, args?: Arguments, options?: { expectedContext?: string; approved?: boolean; expectedControl?: unknown }): Promise<unknown>;
}
/** HTTP client for an application's in-app Java bridge (`POST /execute` on its loopback port, bearer token from
 * `.local/state/bridge-token`). The profile supplies the port, the launcher to name in errors and the readiness check. */
export class BridgeAdapter implements Adapter {
  readonly port: number;
  constructor(private root: string, readonly profile: AppProfile = activeProfile(), port?: number) { this.port = port ?? profile.bridgePort; }
  async execute(operation: string, args: Arguments = {}, options: { expectedContext?: string; approved?: boolean; expectedControl?: unknown } = {}) {
    const app = this.profile.displayName;
    let token: string;
    try { token = (await readFile(resolve(this.root, '.local/state/bridge-token'), 'utf8')).trim(); }
    catch { throw new Error(`${app} bridge is not configured. Run ${this.profile.launcherScript} first.`); }
    let response: Response;
    try {
      response = await fetch(`http://127.0.0.1:${this.port}/execute`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ operation, arguments: args, ...options }), signal: AbortSignal.timeout(125000),
      });
    } catch { throw new Error(`Cannot reach ${app}. Start it with ${this.profile.launcherScript}. A timed-out operation may still be running; inspect the application before retrying.`); }
    const body = await response.json() as { result?: unknown; error?: string };
    if (!response.ok) throw new Error(body.error ?? `${app} returned ${response.status}`);
    if(operation==='context'&&body.result&&typeof body.result==='object')
      return{...body.result,adapterReadiness:await bridgeReadiness(this.root,body.result,this.profile)};
    return body.result;
  }
}
export class QuPathAdapter extends BridgeAdapter { constructor(root: string, port = 49556) { super(root, profiles.qupath, port); } }
export class FijiAdapter extends BridgeAdapter { constructor(root: string, port = 49557) { super(root, profiles.fiji, port); } }
