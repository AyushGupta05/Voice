import type { Adapter, Context } from '../packages/gateway/src/bridge.js';
import { Runtime } from '../packages/gateway/src/runtime.js';
import type { Knowledge } from '../packages/gateway/src/knowledge.js';

export type PublicFixtureContext = Context & { viewer: { centerX: number; centerY: number; downsample: number }; counts: Record<string, number>; selection: unknown[]; selectionCount: number };
export function assertPublicFixture(value: unknown, expectedKey?: string): asserts value is PublicFixtureContext {
  const context = value as any;
  if (!context || context.image?.name !== 'OS-2.ndpi' || context.image?.width !== 126976 || context.image?.height !== 73728
    || typeof context.contextKey !== 'string' || !context.contextKey || (expectedKey !== undefined && context.contextKey !== expectedKey)
    || !context.counts || typeof context.counts !== 'object' || Array.isArray(context.counts) || Object.keys(context.counts).length !== 0
    || !context.viewer || ![context.viewer.centerX, context.viewer.centerY, context.viewer.downsample].every(Number.isFinite) || context.viewer.downsample <= 0
    || context.selectionCount !== 0 || !Array.isArray(context.selection) || context.selection.length !== 0) throw new Error('The authorized empty public fixture is no longer active. Stop before sending further application data or focusing.');
}

/** Only the fixed public image identity and numeric viewer state may leave this fixture. */
export function publicFixtureContext(value: unknown, expectedKey?: string): PublicFixtureContext {
  assertPublicFixture(value, expectedKey);
  return {
    application: 'QuPath', contextKey: value.contextKey,
    image: { name: 'OS-2.ndpi', width: 126976, height: 73728 } as any,
    counts: {}, selection: [], selectionCount: 0, selectionTruncated: false, primarySelectedId: null,
    viewer: { centerX: value.viewer.centerX, centerY: value.viewer.centerY, downsample: value.viewer.downsample },
    note: 'Authorized empty public OS-2 display fixture. Other session metadata deliberately excluded.',
  };
}

/** Test-only authority: no data/code tool or automatic approval exists in this Runtime. */
export class PublicFixtureRuntime extends Runtime {
  private readonly fixtureKey: string;
  constructor(adapter: Adapter, knowledge: Knowledge, directory: string, baseline: Context) {
    super(adapter, knowledge, directory); assertPublicFixture(baseline); this.fixtureKey = baseline.contextKey;
  }
  override async call(...args: Parameters<Runtime['call']>) {
    const [name, parameters, signal, originalGuard] = args;
    if (!['qupath_context', 'qupath_focus'].includes(name)) throw new Error('This bounded model fixture permits context and exact focus only. No approval or data tool is available.');
    if (name === 'qupath_focus' && (parameters.x !== 51100 || parameters.y !== 27100 || parameters.downsample !== 3 || Object.keys(parameters).some(key => !['x', 'y', 'downsample'].includes(key)))) throw new Error('Only the exact public-fixture focus target is allowed.');
    try {
      const result = await super.call(name, parameters, signal, context => {
        if (context) assertPublicFixture(context, this.fixtureKey);
        originalGuard?.(context);
      });
      if (name === 'qupath_context') return publicFixtureContext(result, this.fixtureKey);
      const focus = result as { x?: unknown; y?: unknown; downsample?: unknown };
      if (focus?.x !== 51100 || focus.y !== 27100 || focus.downsample !== 3) throw new Error('Unexpected public focus result.');
      return { x: focus.x, y: focus.y, downsample: focus.downsample };
    } catch {
      signal?.throwIfAborted();
      throw new Error('The public fixture operation no longer meets the bounded test contract. No session diagnostics are sent.');
    }
  }
}
