import type { Tool, Arguments } from '../tools.js';
import type { Adapter, Context } from '../bridge.js';
import type { Runtime } from '../runtime.js';

export type AppId = 'qupath' | 'fiji';
/** Instant spoken route: a whole-utterance match that dispatches one tool with a fixed reply and no model round. */
export type FastRoute = { tool: string; arguments: Record<string, unknown>; reply: string };
/** Inputs for a profile's pre-dispatch check of a data/code tool. `check()` re-validates the request after every
 * adapter read (abort signal, conversation revision, context) and must be called after each one. */
export type PrepareInput = { name: string; args: Arguments; context: Context; adapter: Adapter; check: () => void };

/**
 * Everything that differs between the scientific applications Penny drives. The generic gateway (runtime, backend,
 * server, MCP, knowledge, readiness) reads the active profile; nothing application-specific lives outside `apps/`.
 */
export type AppProfile = {
  id: AppId;
  /** Spoken/displayed application name, also the `application` value the bridge's context reports. */
  displayName: string;
  /** macOS bundle id the desktop (Accessibility) tools bring forward by default. */
  bundleId: string;
  /** Loopback port of the in-app Java bridge (`POST /execute`). */
  bridgePort: number;
  /** Tool-name prefix: `<prefix>_context`, `<prefix>_knowledge`, … */
  toolPrefix: string;
  tools: Tool[];
  /** Agent operating instructions sent on every model call (length-tested). */
  instructions: string;
  /** Profile-specific sentence appended to the backend's per-round host notes (relative view requests etc.). */
  backendNotes: string;
  /** Authored guidance folder; `SKILL.md` inside it is the MCP guide resource. */
  skillDir: string;
  /** Folders (relative to the repository root) whose Markdown/RST files the knowledge index loads. */
  knowledgeSources: string[];
  /** Whether the pinned Java API source index (`<prefix>_api_search/_api_read`) applies to this application. */
  apiSource: boolean;
  /** Script that starts the application with the bridge; quoted in adapter error messages. */
  launcherScript: string;
  /** Completed bridge artifact, its build-identity manifest and the script that produces them, relative to the repository root. */
  bridgeJar: string;
  buildManifest: string;
  buildScript: string;
  /** Local files whose presence the doctor reports: [path, name]. */
  doctorArtifacts: Array<[string, string]>;
  fastRoute(text: string, context: unknown): FastRoute | undefined;
  acknowledgmentFor(name: string, args: Record<string, unknown>, context: unknown): string | undefined;
  localSentenceFor(name: string, args: Record<string, unknown>, result: unknown): string | undefined;
  requestContinuesAfter(name: string, utterance: string): boolean;
  /** Tools whose spoken outcome is the acknowledgment already playing, so a local finish stays silent. */
  silentFinishTools: Set<string>;
  /** Tools that get a spoken "Running it now." once a human approves them under the review policy. */
  longRunningTools: Set<string>;
  /** Pre-dispatch checks for data/code tools; sets `context.reviewTarget` and throws to refuse. */
  prepareAction?(input: PrepareInput): Promise<void>;
  /** After a completed auto-approved action, settle any dialog the application raised; the returned fields are
   * merged into the tool result so the model learns what happened. */
  settleDialogs?(runtime: Runtime, name: string, args: Arguments, signal?: AbortSignal): Promise<Record<string, unknown> | undefined>;
};

export function finite(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value : undefined; }
/** Wording that asks for more than one call delivers, whatever the application. */
export function continuesGenerally(utterance: string) { return /\b(and|then|after that|also)\b/.test(utterance.toLowerCase()); }
/** Runtime decision envelopes carry `error` always; a present error or a non-completed status means no real result. */
export function completedPayload(result: unknown): Record<string, unknown> | undefined {
  if (!result || typeof result !== 'object' || (result as { error?: unknown }).error != null) return undefined;
  const envelope = result as { status?: unknown; result?: unknown };
  if (envelope.status !== 'completed') return undefined;
  // The adapter wraps mutation payloads as { result, checkpoint, contextKey }.
  const outer = envelope.result as Record<string, unknown> | undefined;
  return outer?.result && typeof outer.result === 'object' ? outer.result as Record<string, unknown> : outer;
}
/** Bind a `<prefix>_ui_action` to the exact control observed now, so the bridge can refuse a changed dialog. */
export async function bindUiControl(input: PrepareInput, appName: string) {
  const ui = await input.adapter.execute('ui') as { windows: Array<{ title: string; fingerprint: string; controls: Array<Record<string, unknown>> }> };
  input.check();
  const window = ui.windows.find(window => window.controls.some(control => control.id === input.args.nodeId));
  const control = window?.controls.find(control => control.id === input.args.nodeId);
  // QuPath's JavaFX tree reports `disabled`; Fiji's AWT/Swing tree reports `enabled`.
  if (!control || control.disabled || control.enabled === false) throw new Error(`Target control is absent or disabled. Inspect ${appName} UI again.`);
  input.context.reviewTarget = { window: window!.title, windowFingerprint: window!.fingerprint, ...control };
}
/** Short spoken label for a highlight call, shared by both applications. */
export function highlightAcknowledgment(args: Record<string, unknown>) {
  const label = typeof args.label === 'string' ? args.label.trim().replace(/[.!]+$/, '') : '';
  return label && Array.from(label).length <= 60 ? `Here: ${label}.` : 'Right here.';
}
/** Normalise a spoken command for whole-utterance matching: lowercase, letters only, politeness stripped. */
export const cleanUtterance = (text: string) => text.toLowerCase().replace(/[^a-z' ]+/g, ' ').replace(/\s+/g, ' ').trim()
  .replace(/^(?:(?:hey )?(?:penny|luma) |okay |ok |please |can you |could you )+/, '').replace(/ please$/, '').trim();
