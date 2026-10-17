import AjvModule from 'ajv';
import { activeProfile, type AppProfile } from './apps/index.js';

export type Arguments = Record<string, unknown>;
export type Tool = { name: string; description: string; operation?: string; risk: 'read' | 'view' | 'data' | 'code'; inputSchema: Record<string, unknown> };

/** The active application's tools (selected by LUMA_APP, QuPath by default). Definitions live in `apps/`. */
export const tools: Tool[] = activeProfile().tools;
const Ajv = AjvModule.default ?? AjvModule;
const ajv = new Ajv({ allErrors: true, strict: true });
const compiled = new WeakMap<AppProfile, Map<string, ReturnType<typeof ajv.compile>>>();
function validators(profile: AppProfile) {
  let map = compiled.get(profile);
  if (!map) { map = new Map(profile.tools.map(tool => [tool.name, ajv.compile(tool.inputSchema)])); compiled.set(profile, map); }
  return map;
}
export function validateTool(name: string, args: unknown, profile: AppProfile = activeProfile()): Tool {
  const spec = profile.tools.find(tool => tool.name === name);
  if (!spec) throw new Error(`Unknown tool: ${name}`);
  const validator = validators(profile).get(name)!;
  if (!validator(args)) throw new Error(`Invalid arguments for ${name}: ${ajv.errorsText(validator.errors)}`);
  return spec;
}
export function realtimeTools(profile: AppProfile = activeProfile()) { return profile.tools.map(({ name, description, inputSchema }) => ({ type: 'function', name, description, parameters: inputSchema })); }
