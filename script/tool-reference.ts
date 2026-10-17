import { tools } from '../packages/gateway/src/tools.js';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const cell=(value:unknown)=>String(value).replaceAll('|','\\|').replaceAll('\n',' ');
type Schema = {
 type?: string; const?: unknown; enum?: unknown[]; items?: Schema;
 minimum?: number; maximum?: number; exclusiveMinimum?: number;
 minLength?: number; maxLength?: number; maxItems?: number;
 properties?: Record<string, Schema>; required?: string[];
};
function describe(schema:Schema):string {
  if(schema.const!==undefined)return `exactly ${JSON.stringify(schema.const)}`;
  if(schema.enum)return schema.enum.map((item:unknown)=>JSON.stringify(item)).join(', ');
  let type=schema.type??'value';
  if(type==='array')type=`array of ${describe(schema.items??{})}`;
  const limits=[schema.minimum!==undefined?`min ${schema.minimum}`:'',schema.maximum!==undefined?`max ${schema.maximum}`:'',schema.exclusiveMinimum!==undefined?`greater than ${schema.exclusiveMinimum}`:'',schema.minLength!==undefined?`min ${schema.minLength} characters`:'',schema.maxLength!==undefined?`max ${schema.maxLength} characters`:'',schema.maxItems!==undefined?`max ${schema.maxItems} items`:''].filter(Boolean);
  return [type,...limits].join('; ');
}
let output='# MCP tool reference\n\nGenerated from `packages/gateway/src/tools.ts`. Do not edit individual schemas here; regenerate after changing tool definitions.\n\nAll inputs are JSON objects. Unknown properties are rejected. Read and view tools run immediately; data and code tools create human-review proposals. The approval control is absent from the MCP tool list.\n\n';
output+='| Tool | Risk | Human approval |\n|---|---|---|\n';
for(const tool of tools)output+=`| [\`${tool.name}\`](#${tool.name.replaceAll('_','-')}) | ${tool.risk} | ${['data','code'].includes(tool.risk)?'Required':'No'} |\n`;
for(const tool of tools){
 output+=`\n<a id="${tool.name.replaceAll('_','-')}"></a>\n\n## ${tool.name}\n\n${tool.description}\n\n`;
 const schema=tool.inputSchema as Schema;const properties=Object.entries(schema.properties??{});
 if(!properties.length){output+='Input: `{}`.\n';continue;}
 output+='| Parameter | Required | Accepted value |\n|---|---|---|\n';
 for(const [key,value]of properties)output+=`| \`${key}\` | ${schema.required?.includes(key)?'Yes':'No'} | ${cell(describe(value))} |\n`;
 for(const [key,value]of properties){const nested=value as Schema;if(nested.type!=='object'||!nested.properties)continue;output+=`\nAllowed \`${key}\` properties (all optional unless listed as required):\n\n| Property | Accepted value |\n|---|---|\n`;for(const [sub,definition]of Object.entries(nested.properties))output+=`| \`${sub}\` | ${cell(describe(definition))} |\n`;}
}
output+='\nSee [analysis semantics and verification limits](CAPABILITIES.md), [setup](SETUP.md) and [scientific task guidance](../skills/qupath/SKILL.md).\n';
const destination=fileURLToPath(new URL('../docs/TOOL-REFERENCE.md',import.meta.url));
if(process.argv.includes('--check')) {
  let existing='';try{existing=await readFile(destination,'utf8');}catch{}
  if(existing!==output){console.error('Tool reference is stale. Run npm run docs:generate.');process.exitCode=1;}
  else console.log(`Tool reference matches all ${tools.length} current schemas.`);
} else {
  await writeFile(destination,output);console.log(`Generated reference for ${tools.length} tools.`);
}
