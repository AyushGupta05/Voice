import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import type { Arguments } from './tools.js';
import { bridgeReadiness } from './bridge-readiness.js';
import { activeProfile, profiles, type AppProfile } from './apps/index.js';
import { conversationEnvelope } from './tool-request.js';
export type Context = { contextKey: string; image?: { name: string }; [key: string]: unknown };
export interface Adapter {
  execute(operation: string, args?: Arguments, options?: { expectedContext?: string; approved?: boolean; expectedControl?: unknown }): Promise<unknown>;
}
/** The loaded adapter identity a bridge reports on `GET /health` (Fidelis: `{application, version, adapter:{…}}`), or
 * undefined when it cannot be read. Read-only; used by readiness for profiles whose context carries no identity. */
export async function bridgeHealth(port: number, token?: string): Promise<{ application?: unknown; version?: unknown; adapter?: unknown } | undefined> {
  try {
    const response = await fetch(`http://127.0.0.1:${port}/health`, { headers: token ? { Authorization: `Bearer ${token}` } : {}, signal: AbortSignal.timeout(2000) });
    if (!response.ok) return undefined;
    const body = await response.json() as unknown;
    return body && typeof body === 'object' && !Array.isArray(body) ? body as { application?: unknown; version?: unknown; adapter?: unknown } : undefined;
  } catch { return undefined; }
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
    // A bridge that takes the conversation envelope gets exactly the contract body: no expectedControl (it has no
    // dialog controls), plus the task, whether it was spoken and the latest words (docs/FIDELIS-PROTOCOL.md).
    const body = this.profile.conversationEnvelope
      ? { operation, arguments: args, ...(options.expectedContext !== undefined ? { expectedContext: options.expectedContext } : {}), ...(options.approved !== undefined ? { approved: options.approved } : {}), ...conversationEnvelope() }
      : { operation, arguments: args, ...options };
    let response: Response;
    try {
      response = await fetch(`http://127.0.0.1:${this.port}/execute`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body), signal: AbortSignal.timeout(125000),
      });
    } catch { throw new Error(`Cannot reach ${app}. Start it with ${this.profile.launcherScript}. A timed-out operation may still be running; inspect the application before retrying.`); }
    const reply = await response.json() as { result?: unknown; error?: string };
    if (!response.ok) throw new Error(reply.error ?? `${app} returned ${response.status}`);
    if(operation==='context'&&reply.result&&typeof reply.result==='object'){
      // A bridge identified by its operations (Fidelis) reports them in context.adapter; an older one only on GET /health.
      const identity = this.profile.bridgeIdentity === 'operations' && !(reply.result as { adapter?: unknown }).adapter
        ? { ...reply.result, adapter: (await bridgeHealth(this.port, token))?.adapter } : reply.result;
      return{...reply.result,adapterReadiness:await bridgeReadiness(this.root,identity,this.profile)};
    }
    return reply.result;
  }
}
export class QuPathAdapter extends BridgeAdapter { constructor(root: string, port = 49556) { super(root, profiles.qupath, port); } }
export class FijiAdapter extends BridgeAdapter { constructor(root: string, port = 49557) { super(root, profiles.fiji, port); } }
export class FidelisAdapter extends BridgeAdapter { constructor(root: string, port = 49558) { super(root, profiles.fidelis, port); } }
