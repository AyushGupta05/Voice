import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import type { Tool } from './tools.js';
import { appIds } from './apps/index.js';
const toolName = new RegExp(`^(?:${appIds.join('|')})_[a-z_]+$`);

/** Uses only the agent credential. Human approval routes are deliberately absent. */
export class MCPGateway {
  private readonly base: string;
  constructor(private readonly root: string, port = 49555) {
    if (!Number.isSafeInteger(port) || port < 1 || port > 65535) throw new Error('Invalid local gateway port.');
    this.base = `http://127.0.0.1:${port}`;
  }
  private async request(path: '/tools' | '/tools/call', payload?: unknown): Promise<unknown> {
    const token = (await readFile(resolve(this.root, '.local/state/gateway-token'), 'utf8')).trim();
    const response = await fetch(this.base + path, {
      method: payload === undefined ? 'GET' : 'POST', redirect: 'error',
      headers: { Authorization: `Bearer ${token}`, ...(payload === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(payload === undefined ? {} : { body: JSON.stringify(payload) }),
      signal: AbortSignal.timeout(payload === undefined ? 10000 : 130000),
    });
    const value = await response.json() as { result?: unknown; error?: string };
    if (!response.ok) throw new Error(typeof value.error === 'string' ? value.error : 'Local gateway request failed.');
    return value;
  }
  async catalog(): Promise<Tool[]> {
    const value = await this.request('/tools') as { tools?: unknown };
    if (!Array.isArray(value?.tools) || value.tools.length > 256) throw new Error('Invalid running gateway tool catalog.');
    const names = new Set<string>();
    for (const tool of value.tools) {
      if (!tool || typeof tool !== 'object' || typeof tool.name !== 'string' || !toolName.test(tool.name) ||
          names.has(tool.name) || typeof tool.description !== 'string' || !['read', 'view', 'data', 'code'].includes(tool.risk) ||
          !tool.inputSchema || typeof tool.inputSchema !== 'object' || tool.inputSchema.type !== 'object')
        throw new Error('Invalid running gateway tool catalog.');
      names.add(tool.name);
    }
    return value.tools as Tool[];
  }
  async call(name: string, args: Record<string, unknown>): Promise<unknown> {
    // The running Runtime owns argument validation and approval/context binding.
    // Applying newer source schemas here can reject valid calls to an older gateway.
    const value = await this.request('/tools/call', { name, arguments: args }) as { result?: unknown };
    return value.result;
  }
}
