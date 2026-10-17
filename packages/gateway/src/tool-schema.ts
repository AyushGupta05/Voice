import type { Tool } from './tools.js';

/** JSON-schema helpers shared by every application's tool definitions. Unknown properties are always rejected. */
export function schema(properties: Record<string, unknown> = {}, required: string[] = []) { return { type: 'object', properties, required, additionalProperties: false }; }
/** Builds `<prefix>_<name>` tool definitions for one application. */
export function toolFactory(prefix: string) {
  return function tool(name: string, description: string, operation: string | undefined, risk: Tool['risk'], properties: Record<string, unknown> = {}, required: string[] = []): Tool {
    return { name: `${prefix}_${name}`, description, operation, risk, inputSchema: schema(properties, required) };
  };
}
