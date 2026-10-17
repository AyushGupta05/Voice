import { AsyncLocalStorage } from 'node:async_hooks';

/** The conversation task whose work is running, so tool events can say whose call they were (server.ts). */
export const toolTask = new AsyncLocalStorage<string>();
/** The request behind the running conversation task: whether it was spoken and the latest user utterance. The
 * utterance is read when a call is dispatched, so a transcript correction counts. */
export type ToolUtterance = { spoken: boolean; utterance: () => string;
  /** The application observation taken when the request arrived (what the researcher was answering); never sent to a bridge. */
  observed?: () => unknown };
export const toolUtterance = new AsyncLocalStorage<ToolUtterance>();
/** What a bridge that takes the conversation envelope (docs/FIDELIS-PROTOCOL.md) receives with a call. */
export type ConversationEnvelope = { spoken?: boolean; taskId?: string; utterance?: string };
/** Longest utterance forwarded to a bridge; typed and spoken requests are bounded at 4000 characters already. */
export const MAX_ENVELOPE_UTTERANCE = 4000;

/** The conversation fields of the current call; empty outside a conversation task (direct /tools/call and MCP calls). */
export function conversationEnvelope(): ConversationEnvelope {
  const taskId = toolTask.getStore(); const request = toolUtterance.getStore();
  const envelope: ConversationEnvelope = {};
  if (request) {
    envelope.spoken = request.spoken === true;
    let text = '';
    try { text = request.utterance().trim(); } catch { text = ''; }
    if (text) envelope.utterance = text.slice(0, MAX_ENVELOPE_UTTERANCE);
  }
  if (taskId) envelope.taskId = taskId;
  return envelope;
}
/** The observation the current request started from, if the host recorded one (Fidelis binds a "yes" to the plan pending then). */
export function observedContext(): unknown { try { return toolUtterance.getStore()?.observed?.(); } catch { return undefined; } }
/** Runs `work` inside a captured envelope (a human-reviewed action decided later, outside the task's own context). */
export function withConversation<T>(envelope: ConversationEnvelope | undefined, work: () => T): T {
  if (!envelope || (!envelope.taskId && envelope.spoken === undefined && envelope.utterance === undefined)) return work();
  const inner = envelope.spoken === undefined && envelope.utterance === undefined ? work
    : () => toolUtterance.run({ spoken: envelope.spoken === true, utterance: () => envelope.utterance ?? '' }, work);
  return envelope.taskId ? toolTask.run(envelope.taskId, inner) : inner();
}
