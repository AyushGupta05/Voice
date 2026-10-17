import { tools } from '../packages/gateway/src/tools.js';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

type Schema = {
  type?: string; const?: unknown; enum?: unknown[]; items?: Schema;
  minimum?: number; maximum?: number; exclusiveMinimum?: number;
  minLength?: number; maxLength?: number; minItems?: number; maxItems?: number;
  uniqueItems?: boolean; pattern?: string; description?: string;
  properties?: Record<string, Schema>; required?: string[];
};
const cell = (value: unknown) => String(value).replaceAll('|', '\\|').replaceAll('\n', ' ');
function describe(schema: Schema): string {
  if (schema.const !== undefined) return `exactly ${JSON.stringify(schema.const)}`;
  if (schema.enum) return schema.enum.map(item => JSON.stringify(item)).join(', ');
  const type = schema.type === 'array' ? `array of (${describe(schema.items ?? {})})` : schema.type ?? 'value';
  const limits = [
    schema.minimum !== undefined ? `min ${schema.minimum}` : '',
    schema.maximum !== undefined ? `max ${schema.maximum}` : '',
    schema.exclusiveMinimum !== undefined ? `greater than ${schema.exclusiveMinimum}` : '',
    schema.minLength !== undefined ? `min ${schema.minLength} characters` : '',
    schema.maxLength !== undefined ? `max ${schema.maxLength} characters` : '',
    schema.minItems !== undefined ? `min ${schema.minItems} items` : '',
    schema.maxItems !== undefined ? `max ${schema.maxItems} items` : '',
    schema.uniqueItems ? 'unique items' : '',
    schema.pattern ? `pattern \`${schema.pattern}\`` : '',
  ].filter(Boolean);
  return [type, ...limits].join('; ');
}
function parameterTable(schema: Schema): string {
  const properties = Object.entries(schema.properties ?? {});
  const withDescription = properties.some(([, value]) => value.description);
  const headers = ['Parameter', 'Required', 'Accepted value', ...(withDescription ? ['Description'] : [])];
  let output = `| ${headers.join(' | ')} |\n|${headers.map(() => '---').join('|')}|\n`;
  for (const [name, value] of properties) {
    const row = [`\`${name}\``, schema.required?.includes(name) ? 'Yes' : 'No', describe(value), ...(withDescription ? [value.description ?? ''] : [])];
    output += `| ${row.map(cell).join(' | ')} |\n`;
  }
  return output;
}
let output = '# MCP tool reference\n\nGenerated from `packages/gateway/src/tools.ts`. Do not edit individual schemas here; regenerate after changing tool definitions. [Complete machine-readable registry](tool-schemas.json) retains every schema constraint and description. MCP discovery reports the running gateway, which may be older than these source definitions.\n\nAll inputs are JSON objects. Unknown properties are rejected. Read and view tools run immediately; data and code tools create human-review proposals. The approval control is absent from the MCP tool list.\n\n';
output += '| Tool | Risk | Human approval |\n|---|---|---|\n';
for (const tool of tools) output += `| [\`${tool.name}\`](#${tool.name.replaceAll('_', '-')}) | ${tool.risk} | ${['data', 'code'].includes(tool.risk) ? 'Required' : 'No'} |\n`;
for (const tool of tools) {
  output += `\n<a id="${tool.name.replaceAll('_', '-')}"></a>\n\n## ${tool.name}\n\n${tool.description}\n\n`;
  const schema = tool.inputSchema as Schema;
  const properties = Object.entries(schema.properties ?? {});
  if (!properties.length) { output += 'Input: `{}`.\n'; continue; }
  output += parameterTable(schema);
  for (const [name, value] of properties) {
    if (value.type !== 'object' || !value.properties) continue;
    output += `\nAllowed \`${name}\` properties:\n\n${parameterTable(value)}`;
  }
}
output += '\nSee [analysis semantics and verification limits](CAPABILITIES.md), [setup](SETUP.md) and [scientific task guidance](../skills/qupath/SKILL.md).\n';
const artifacts = [
  { path: fileURLToPath(new URL('../docs/TOOL-REFERENCE.md', import.meta.url)), text: output },
  { path: fileURLToPath(new URL('../docs/tool-schemas.json', import.meta.url)), text: JSON.stringify({ schemaVersion: 1, application: 'QuPath', toolCount: tools.length, tools }, null, 2) + '\n' },
];
if (process.argv.includes('--check')) {
  let stale = false;
  for (const artifact of artifacts) {
    let existing = ''; try { existing = await readFile(artifact.path, 'utf8'); } catch {}
    if (existing !== artifact.text) { console.error(`Generated artifact is stale: ${artifact.path}. Run npm run docs:generate.`); stale = true; }
  }
  if (stale) process.exitCode = 1;
  else console.log(`Tool reference and complete registry match all ${tools.length} current schemas.`);
} else {
  for (const artifact of artifacts) await writeFile(artifact.path, artifact.text);
  console.log(`Generated reference and complete registry for ${tools.length} tools.`);
}
