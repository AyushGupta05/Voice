import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { CallToolRequestSchema, ListToolsRequestSchema, ListResourcesRequestSchema, ReadResourceRequestSchema, ListPromptsRequestSchema, GetPromptRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { MCPGateway } from './mcp-gateway.js';
import { activeProfile, type AppProfile } from './apps/index.js';

export function createMCPServer(root: string, gateway = new MCPGateway(root), profile: AppProfile = activeProfile()) {
  const p = profile.toolPrefix; const guideUri = `${profile.id}://guide`; const promptName = `${profile.id}-researcher`; const checkpoint = `${p}_checkpoint`;
  const guideNotice = `Use the tools advertised by this MCP connection. This local guide may describe a newer build than the running gateway. Refresh ${p}_context and inspect loaded adapter readiness before planning operations.\n\n`;
  const server = new Server({ name: `luma-${profile.id}`, version: '0.1.0' }, { capabilities: { tools: {}, resources: {}, prompts: {} } });
  const guide = async () => guideNotice + await readFile(resolve(root, profile.skillDir, 'SKILL.md'), 'utf8');
  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: (await gateway.catalog()).map(({ name, description, inputSchema, risk }) => ({
      name, description, inputSchema: inputSchema as { type: 'object' },
      annotations: {
        readOnlyHint: risk === 'read' && name !== checkpoint,
        destructiveHint: risk === 'data' || risk === 'code' || name === checkpoint,
        idempotentHint: risk === 'read' && name !== checkpoint,
        openWorldHint: risk === 'code',
      },
    })),
  }));
  server.setRequestHandler(CallToolRequestSchema, async request => {
    try {
      const result = await gateway.call(request.params.name, request.params.arguments ?? {});
      if (request.params.name === `${p}_snapshot` && result && typeof result === 'object' && 'data' in result)
        return { content: [{ type: 'image', data: String(result.data), mimeType: 'image/png' }] };
      return { content: [{ type: 'text', text: JSON.stringify(result) }] };
    } catch (error) {
      return { isError: true, content: [{ type: 'text', text: error instanceof Error ? error.message : String(error) }] };
    }
  });
  server.setRequestHandler(ListResourcesRequestSchema, async () => ({ resources: [{ uri: guideUri, name: `${profile.displayName} agent operating guide`, mimeType: 'text/markdown' }] }));
  server.setRequestHandler(ReadResourceRequestSchema, async request => {
    if (request.params.uri !== guideUri) throw new Error('Unknown resource');
    return { contents: [{ uri: request.params.uri, mimeType: 'text/markdown', text: await guide() }] };
  });
  server.setRequestHandler(ListPromptsRequestSchema, async () => ({ prompts: [{ name: promptName, description: `Ground an agent in the current ${profile.displayName} session and approval rules.` }] }));
  server.setRequestHandler(GetPromptRequestSchema, async request => {
    if (request.params.name !== promptName) throw new Error('Unknown prompt');
    return { messages: [{ role: 'user', content: { type: 'text', text: await guide() } }] };
  });
  return server;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await createMCPServer(resolve(process.env.LUMA_ROOT ?? process.cwd())).connect(new StdioServerTransport());
}
