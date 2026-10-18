import type { Tool, Arguments } from '../tools.js';
import type { Adapter, Context } from '../bridge.js';
import type { Runtime } from '../runtime.js';

export type AppId = 'qupath' | 'fiji' | 'fidelis';
/** Instant spoken route: a whole-utterance match that dispatches one tool with a fixed reply and no model round. */
export type FastRoute = { tool: string; arguments: Record<string, unknown>; reply: string;
  /** A known prerequisite is missing; report this reason without dispatching an action or asking the model to guess. */
  refusal?: string;
  /** A folder the utterance names ("the bbbc folder"): the host resolves it with `<prefix>_find_images` and passes the
   * first matching folder as `arguments.folder`; when nothing matches the route runs with its default. */
  folderQuery?: string;
  /** An image the utterance names ("open the hela cells image"): the host resolves it with `<prefix>_find_images` and
   * passes the first image match as `arguments.path`; when nothing matches, the model takes the request instead. */
  imageQuery?: string;
  /** "Run this macro": the host reads the session's macro with `<prefix>_macro` and passes its text as `arguments.code`
   * before dispatching the route (Fidelis); when there is no macro yet the request is answered with that. */
  macroFromHistory?: boolean };
/** Inputs for a profile's pre-dispatch check of a data/code tool. `check()` re-validates the request after every
 * adapter read (abort signal, conversation revision, context) and must be called after each one. */
export type PrepareInput = { name: string; args: Arguments; context: Context; adapter: Adapter; check: () => void };
/** Inputs for a profile-answered tool without a bridge operation (see `AppProfile.localTool`). */
export type LocalToolInput = { runtime: Runtime; name: string; args: Arguments; root: string; signal?: AbortSignal; beforeDispatch?: (context?: Context) => void };

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
  /** How readiness identifies the loaded bridge. 'build' (the default) compares the context's `adapter` identity with
   * the completed build manifest and JAR (QuPath, Fiji). 'operations' checks the operations the bridge's `GET /health`
   * declares in `adapter.operations` (Fidelis, built by its own tools with no separate bridge artifact). */
  bridgeIdentity?: 'build' | 'operations';
  /** The bridge takes the conversation envelope with every `/execute`: `spoken`, `taskId` and the latest user
   * `utterance` (docs/FIDELIS-PROTOCOL.md). Other bridges receive the original body unchanged. */
  conversationEnvelope?: boolean;
  /** Instant routes are dispatched bound to the context they were chosen from: a changed contextKey refuses the route
   * before dispatch, and view operations carry it as `expectedContext` (a "no" meant for one plan never cancels another). */
  bindInstantRoutes?: boolean;
  /** Spoken stop words ("stop", "cancel that"), which the voice transport consumes as barge-in, are also offered to this
   * profile's instant routes (Fidelis: cancel a waiting plan); without a route nothing else happens. */
  controlRoutes?: boolean;
  fastRoute(text: string, context: unknown): FastRoute | undefined;
  acknowledgmentFor(name: string, args: Record<string, unknown>, context: unknown): string | undefined;
  /** `context` is the application observation the call was dispatched from, when the host has it. */
  localSentenceFor(name: string, args: Record<string, unknown>, result: unknown, context?: unknown): string | undefined;
  /** Whether the request still needs the model after this call; `result` is the call's outcome when known (a plan that
   * waits for confirmation is the whole answer, whatever else the request asked for). */
  requestContinuesAfter(name: string, utterance: string, result?: unknown): boolean;
  /** A completed instant-route result that still needs the model (a Fidelis command whose original dialog waits): the
   * request continues with the backend instead of finishing. */
  needsModel?(name: string, result: unknown, context?: unknown): boolean;
  /** What a completed bridge call achieved short of running (Fidelis: a waiting plan or dialog, a command cancelled at its
   * dialog), recorded as the action's `outcome` so the audit and action status never read it as run. */
  outcomeOf?(name: string, result: unknown): 'awaiting_confirmation' | 'dialog' | 'cancelled' | undefined;
  /** A completed call whose plan now waits for the researcher (Fidelis `awaiting_confirmation`), as the sentence that
   * ends the request; nothing later in the request runs. Undefined for every other result. */
  pausesRequest?(name: string, result: unknown): string | undefined;
  /** A completed call that achieved nothing (e.g. an undo with no checkpoint), as a short spoken reason; undefined when it worked. */
  unsuccessful?(name: string, args: Record<string, unknown>, result: unknown): string | undefined;
  /** The application's error text as a short spoken reason (an engine diagnostic listing possible causes becomes one
   * sentence); undefined keeps the text as it is. */
  failurePhrase?(name: string, reason: string): string | undefined;
  /** Tools whose spoken outcome is the acknowledgment already playing, so a local finish stays silent. */
  silentFinishTools: Set<string>;
  /** Tools that get a spoken "Running it now." once a human approves them under the review policy. */
  longRunningTools: Set<string>;
  /** Pre-dispatch checks for data/code tools; sets `context.reviewTarget` and throws to refuse. */
  prepareAction?(input: PrepareInput): Promise<void>;
  /** Arguments the model filled in that the researcher never asked for, removed so the bridge's tested defaults apply. */
  modelArguments?(name: string, args: Record<string, unknown>, utterance: string): Record<string, unknown>;
  /** What the model is shown for a tool result, when the real result is too large to send whole (a Fidelis macro of
   * hundreds of lines): a bounded view of the same data. The runtime, audit and companion keep the full result. */
  resultForModel?(name: string, result: unknown): unknown;
  /** Answers a tool that has no bridge operation of its own (`name` without the prefix), or returns undefined. A tool
   * that acts must do so through `runtime.call` of a real operation tool, so the decision, audit and checkpoint path
   * is the same as for a direct call. `root` is the repository root. */
  localTool?(input: LocalToolInput): Promise<unknown> | undefined;
  /** Finishes the result of a human-reviewed call from its `approval_required` proposal and the settled decision, the
   * same way the tool finishes an immediately executed call; undefined keeps the decision as it is. */
  finalizeDecision?(name: string, proposal: unknown, decision: unknown): unknown;
  /** After a completed auto-approved action, settle any dialog the application raised; the returned fields are
   * merged into the tool result so the model learns what happened. */
  settleDialogs?(runtime: Runtime, name: string, args: Arguments, signal?: AbortSignal): Promise<Record<string, unknown> | undefined>;
};

export function finite(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value : undefined; }
/** Wording that asks for more than one call delivers, whatever the application. */
export function continuesGenerally(utterance: string) { return /\b(and|then|after that|also)\b/.test(utterance.toLowerCase()); }
const fillerWord = /^(?:ok|okay|hey|penny|luma|fiji|qupath|so|now|right|please|thanks|thank|you|um|uh|well|alright|yes|yeah|no|sure)$/;
const questionWord = /^(?:do|does|did|is|are|how|what|which|can|could|will|would|should|was|were)$/;
/**
 * A second clause after the first request ("open hela cells, do the channels colocalise?", "open hela do the channels
 * colocalise"): clauses split by commas, semicolons or sentence ends, ignoring filler-only clauses ("okay, zoom in"),
 * or a question word after the opening command. A request that starts as a question is one intent.
 */
export function compoundRequest(utterance: string) {
  const clauses = utterance.toLowerCase().split(/[,;:]|[?!](?=\s*\S)|\.(?=\s+\S)/).map(clause => clause.replace(/[^a-z0-9' ]+/g, ' ').trim())
    .filter(clause => clause && !clause.split(/\s+/).every(word => fillerWord.test(word)));
  if (clauses.length > 1) return true;
  const words = (clauses[0] ?? '').split(/\s+/).filter(Boolean);
  if (!words.length || questionWord.test(words[0])) return false;
  // "the cells that are positive" is a relative clause, not a second question.
  for (let i = 2; i < words.length; i++) if (questionWord.test(words[i]) && !/^(?:that|which|who|there|it|they|this|these|those|what|where|how)$/.test(words[i - 1])) return true;
  return false;
}
/** Runtime decision envelopes carry `error` always; a present error or a non-completed status means no real result. */
export function completedPayload(result: unknown): Record<string, unknown> | undefined {
  if (!result || typeof result !== 'object' || (result as { error?: unknown }).error != null) return undefined;
  const envelope = result as { status?: unknown; result?: unknown };
  if (envelope.status !== 'completed') return undefined;
  // The adapter wraps mutation payloads as { result, checkpoint, contextKey }.
  const outer = envelope.result as Record<string, unknown> | undefined;
  return outer?.result && typeof outer.result === 'object' ? outer.result as Record<string, unknown> : outer;
}
/** Why a tool call did not work, as a short sentence, or undefined when it did: a thrown error, a failed or denied
 * decision, a payload reporting `ok:false`/`error`, or the profile's own "completed but achieved nothing" check. */
export function failureReason(profile: Pick<AppProfile, 'unsuccessful' | 'failurePhrase'>, name: string, args: Record<string, unknown>, result: unknown): string | undefined {
  const short = (value: unknown, fallback: string) => {
    const raw = typeof value === 'string' && value.trim() ? value.trim().split('\n')[0] : fallback;
    const message = profile.failurePhrase?.(name, raw) ?? raw;
    return message.length > 140 ? `${message.slice(0, 137).trimEnd()}...` : message;
  };
  if (!result || typeof result !== 'object') return undefined;
  const envelope = result as { status?: unknown; error?: unknown; ok?: unknown; message?: unknown };
  // A plan that waits for the researcher (Fidelis) has not failed; a view operation reports it without an envelope.
  if (envelope.status === 'duplicate' || envelope.status === 'approval_required' || envelope.status === 'awaiting_confirmation') return undefined;
  if (envelope.error != null) return short(envelope.error, 'it reported an error');
  if (typeof envelope.status === 'string' && envelope.status !== 'completed') return short(undefined, `it ended ${envelope.status}`);
  if (envelope.ok === false) return short(envelope.message, 'it reported failure');
  const inner = completedPayload(result);
  if (inner && inner !== result && (inner.ok === false || (inner.error != null && inner.error !== false))) return short(inner.error ?? inner.message, 'it reported failure');
  return profile.unsuccessful?.(name, args, result);
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
