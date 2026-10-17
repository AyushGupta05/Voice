import { profiles, appIds, type AppProfile } from '../packages/gateway/src/apps/index.js';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

type Schema = {
  type?: string; const?: unknown; enum?: unknown[]; items?: Schema; anyOf?: Schema[];
  minimum?: number; maximum?: number; exclusiveMinimum?: number;
  minLength?: number; maxLength?: number; minItems?: number; maxItems?: number;
  uniqueItems?: boolean; pattern?: string; description?: string;
  properties?: Record<string, Schema>; required?: string[];
};
const cell = (value: unknown) => String(value).replaceAll('|', '\\|').replaceAll('\n', ' ');
function describe(schema: Schema): string {
  if (schema.const !== undefined) return `exactly ${JSON.stringify(schema.const)}`;
  if (schema.enum) return schema.enum.map(item => JSON.stringify(item)).join(', ');
  if (schema.anyOf) return schema.anyOf.map(describe).join(' or ');
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
/** Where each application's generated reference lives; QuPath keeps the original file names. */
const outputs: Record<AppProfile['id'], { markdown: string; json: string; source: string }> = {
  qupath: { markdown: 'TOOL-REFERENCE.md', json: 'tool-schemas.json', source: 'packages/gateway/src/apps/qupath-tools.ts' },
  fiji: { markdown: 'TOOL-REFERENCE-FIJI.md', json: 'tool-schemas-fiji.json', source: 'packages/gateway/src/apps/fiji-tools.ts' },
};
function reference(profile: AppProfile): string {
  const { tools } = profile; const out = outputs[profile.id];
  const others = appIds.filter(id => id !== profile.id).map(id => `[${profiles[id].displayName}](${outputs[id].markdown})`).join(', ');
  let output = `# MCP tool reference (${profile.displayName})\n\nGenerated from \`${out.source}\`; this profile is active when \`LUMA_APP=${profile.id}\`${profile.id === 'qupath' ? ' (the default)' : ''}. Do not edit individual schemas here; regenerate after changing tool definitions. [Complete machine-readable registry](${out.json}) retains every schema constraint and description. MCP discovery reports the running gateway, which may be older than these source definitions. Other applications: ${others}.\n\nAll inputs are JSON objects. Unknown properties are rejected. Read and view tools run immediately. Data and code tools go through the recorded decision path: executed at once under the default \`LUMA_APPROVALS=auto\` policy, shown as human review cards under \`review\`. The approval control is absent from the MCP tool list.\n\n`;
  output += '| Tool | Risk | Decision path |\n|---|---|---|\n';
  for (const tool of tools) output += `| [\`${tool.name}\`](#${tool.name.replaceAll('_', '-')}) | ${tool.risk} | ${['data', 'code'].includes(tool.risk) ? 'Yes' : 'No'} |\n`;
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
  output += `\nSee [analysis semantics and verification limits](CAPABILITIES.md), [setup](PENNY-SETUP.md) and [scientific task guidance](../${profile.skillDir}/SKILL.md).\n`;
  return output;
}
const artifacts = appIds.flatMap(id => {
  const profile = profiles[id]; const out = outputs[id];
  return [
    { path: fileURLToPath(new URL(`../docs/${out.markdown}`, import.meta.url)), text: reference(profile) },
    { path: fileURLToPath(new URL(`../docs/${out.json}`, import.meta.url)), text: JSON.stringify({ schemaVersion: 1, application: profile.displayName, app: profile.id, toolCount: profile.tools.length, tools: profile.tools }, null, 2) + '\n' },
  ];
});
const summary = appIds.map(id => `${profiles[id].displayName}: ${profiles[id].tools.length}`).join(', ');
if (process.argv.includes('--check')) {
  let stale = false;
  for (const artifact of artifacts) {
    let existing = ''; try { existing = await readFile(artifact.path, 'utf8'); } catch {}
    if (existing !== artifact.text) { console.error(`Generated artifact is stale: ${artifact.path}. Run npm run docs:generate.`); stale = true; }
  }
  if (stale) process.exitCode = 1;
  else console.log(`Tool references and complete registries match the current schemas (${summary}).`);
} else {
  for (const artifact of artifacts) await writeFile(artifact.path, artifact.text);
  console.log(`Generated references and complete registries (${summary}).`);
}
