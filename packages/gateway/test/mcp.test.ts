import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createMCPServer } from '../src/mcp.js';
import { MCPGateway } from '../src/mcp-gateway.js';
import type { Tool } from '../src/tools.js';

const legacy: Tool = { name: 'qupath_focus', description: 'Actually loaded legacy focus', risk: 'view', inputSchema: { type: 'object', properties: { legacyTarget: { type: 'string' } }, required: ['legacyTarget'], additionalProperties: false } };
const checkpoint: Tool = { name: 'qupath_checkpoint', description: 'Save volatile recovery point', risk: 'read', inputSchema: { type: 'object' } };
async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'luma-mcp-'));
  await mkdir(join(root, '.local/state'), { recursive: true }); await mkdir(join(root, 'skills/qupath'), { recursive: true });
  await writeFile(join(root, '.local/state/gateway-token'), 'agent-only');
  await writeFile(join(root, '.local/state/companion-token'), 'never-send-human-secret');
  await writeFile(join(root, 'skills/qupath/SKILL.md'), 'Fixture guide');
  let catalog: unknown = [legacy, checkpoint];
  const calls: Array<{ method: string; path: string; authorization?: string; value?: unknown }> = [];
  const http = createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const value = raw ? JSON.parse(raw) : undefined;
    calls.push({ method: req.method!, path: req.url!, authorization: req.headers.authorization, value });
    res.setHeader('Content-Type', 'application/json');
    if (req.headers.authorization !== 'Bearer agent-only') { res.statusCode = 401; res.end(JSON.stringify({ error: 'Unauthorized' })); return; }
    if (req.url === '/tools' && req.method === 'GET') { res.end(JSON.stringify({ tools: catalog })); return; }
    if (req.url === '/tools/call' && req.method === 'POST') {
      if (value.name !== 'qupath_focus' || typeof value.arguments?.legacyTarget !== 'string' || Object.keys(value.arguments).length !== 1) {
        res.statusCode = 400; res.end(JSON.stringify({ error: 'Running gateway schema rejected this call' })); return;
      }
      res.end(JSON.stringify({ result: { actualTarget: value.arguments.legacyTarget } })); return;
    }
    res.statusCode = 404; res.end('{}');
  });
  http.listen(0, '127.0.0.1'); await once(http, 'listening');
  const address = http.address(); assert(address && typeof address !== 'string');
  const gateway = new MCPGateway(root, address.port);
  const server = createMCPServer(root, gateway); const client = new Client({ name: 'catalog-contract', version: '1' });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport); await client.connect(clientTransport);
  return { root, gateway, client, calls, setCatalog: (value: unknown) => { catalog = value; }, close: async () => {
    await client.close(); await server.close(); http.closeAllConnections(); await new Promise<void>((resolve, reject) => http.close(error => error ? reject(error) : resolve())); await rm(root, { recursive: true, force: true });
  } };
}
test('MCP discovery uses the authenticated running catalog including its exact legacy schema', async () => {
  const f = await fixture(); try {
    const inventory = await f.client.listTools(); assert.equal(inventory.tools.length, 2);
    assert.deepEqual(inventory.tools[0].inputSchema, legacy.inputSchema); assert.equal(inventory.tools[0].description, legacy.description);
    assert(!inventory.tools.some(tool => tool.name === 'qupath_import_annotations'));
    const annotations = inventory.tools[1].annotations;
    assert.equal(annotations?.readOnlyHint, false); assert.equal(annotations?.idempotentHint, false); assert.equal(annotations?.destructiveHint, true);
    f.setCatalog([checkpoint]); assert.deepEqual((await f.client.listTools()).tools.map(tool => tool.name), ['qupath_checkpoint']);
    assert(f.calls.every(call => call.authorization === 'Bearer agent-only' && call.path === '/tools'));
  } finally { await f.close(); }
});
test('MCP calls defer to the running schema and cannot expose human approval routes', async () => {
  const f = await fixture(); try {
    const result = await f.client.callTool({ name: 'qupath_focus', arguments: { legacyTarget: 'observed-object' } });
    assert(!result.isError); assert.deepEqual(result.content, [{ type: 'text', text: '{"actualTarget":"observed-object"}' }]);
    const invalid = await f.client.callTool({ name: 'qupath_focus', arguments: { x: 3 } });
    assert.equal(invalid.isError, true); assert.match(JSON.stringify(invalid.content), /Running gateway schema/);
    const approval = await f.client.callTool({ name: '/approvals/123', arguments: { approved: true } });
    assert.equal(approval.isError, true);
    assert(f.calls.every(call => call.path === '/tools/call' && call.authorization === 'Bearer agent-only'));
    assert(!JSON.stringify(f.calls).includes('never-send-human-secret'));
  } finally { await f.close(); }
});
test('MCP rejects malformed or duplicate live definitions without inventing a source fallback', async () => {
  const f = await fixture(); try {
    for (const catalog of [null, {}, [legacy, legacy], [{ ...legacy, risk: 'approve' }], [{ ...legacy, inputSchema: { type: 'array' } }], [{ ...legacy, name: '/approvals/id' }]]) {
      f.setCatalog(catalog); await assert.rejects(f.client.listTools(), /Invalid running gateway tool catalog/);
    }
  } finally { await f.close(); }
});
test('MCP guide and prompt explicitly distinguish local documentation from loaded capabilities', async () => {
  const f = await fixture(); try {
    const resources = await f.client.listResources(); assert.equal(resources.resources[0].uri, 'qupath://guide');
    const guide = await f.client.readResource({ uri: 'qupath://guide' }); assert.match(JSON.stringify(guide), /newer build than the running gateway/);
    const prompt = await f.client.getPrompt({ name: 'qupath-researcher' }); assert.match(JSON.stringify(prompt), /Fixture guide/);
    await assert.rejects(f.client.readResource({ uri: 'qupath://unknown' }), /Unknown resource/);
  } finally { await f.close(); }
});
test('MCP gateway accepts only valid loopback port numbers and fails clearly when unavailable', async () => {
  for (const port of [0, -1, 65536, NaN, 1.5]) assert.throws(() => new MCPGateway('/tmp', port), /Invalid local gateway port/);
  const f = await fixture(); try {
    await writeFile(join(f.root, '.local/state/gateway-token'), 'invalid'); await assert.rejects(f.gateway.catalog(), /Unauthorized/);
  } finally { await f.close(); }
  await assert.rejects(f.gateway.catalog());
});
