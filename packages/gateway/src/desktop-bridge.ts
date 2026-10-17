import { randomUUID } from 'node:crypto';
import type { Adapter } from './bridge.js';
import type { Arguments } from './tools.js';

/** Operations the companion's DesktopControl understands (apps/companion/Sources/LumaCompanion/DesktopControl.swift). */
export type DesktopOp = 'inspect' | 'act' | 'click' | 'type' | 'key';
export const DESKTOP_OPS: readonly DesktopOp[] = ['inspect', 'act', 'click', 'type', 'key'];
/** Reading a window tree is bounded; acting includes the ~250 ms cursor flight plus synthetic typing. */
export const DESKTOP_TIMEOUTS_MS: Record<DesktopOp, number> = { inspect: 10_000, act: 15_000, click: 15_000, type: 15_000, key: 15_000 };

/** The subset of a `ws` WebSocket the bridge needs, so tests can use a fake. */
export type DesktopPeer = { readyState: number; send(data: string): void };
const OPEN = 1;

export class DesktopBridgeError extends Error { constructor(message: string, readonly code: 'no_companion' | 'timeout' | 'companion' | 'aborted' | 'invalid') { super(message); this.name = 'DesktopBridgeError'; } }

type Pending = { peer: DesktopPeer; resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout>; abort?: () => void; signal?: AbortSignal };

/**
 * Correlates `desktop_request` events sent to the native companion with its `desktop_response` replies.
 * The companion (not the gateway) owns the Accessibility permission and the real mouse/keyboard, so every
 * desktop tool call becomes one request over the already-authenticated `/events` socket.
 */
export class DesktopBridge {
  private readonly peers: DesktopPeer[] = [];
  private readonly pending = new Map<string, Pending>();

  /** Register a connected companion socket (the most recently attached one is preferred). */
  attach(peer: DesktopPeer) { const index = this.peers.indexOf(peer); if (index >= 0) this.peers.splice(index, 1); this.peers.push(peer); }

  /** Forget a socket and fail every request still waiting on it. */
  detach(peer: DesktopPeer) {
    const index = this.peers.indexOf(peer); if (index >= 0) this.peers.splice(index, 1);
    for (const [id, entry] of this.pending) if (entry.peer === peer) this.settle(id, undefined, new DesktopBridgeError('The companion disconnected before answering the desktop request.', 'no_companion'));
  }

  get connected() { return this.peers.some(peer => peer.readyState === OPEN); }
  get pendingCount() { return this.pending.size; }

  /** Routes a parsed companion message. Returns true when it was a desktop_response this bridge consumed. */
  handle(message: unknown): boolean {
    if (!message || typeof message !== 'object') return false;
    const event = message as { type?: unknown; requestId?: unknown; result?: unknown; error?: unknown };
    if (event.type !== 'desktop_response') return false;
    if (typeof event.requestId !== 'string' || !this.pending.has(event.requestId)) return true;
    if (typeof event.error === 'string') this.settle(event.requestId, undefined, new DesktopBridgeError(event.error, 'companion'));
    else this.settle(event.requestId, event.result ?? {});
    return true;
  }

  request(op: DesktopOp, args: Arguments = {}, signal?: AbortSignal): Promise<unknown> {
    if (!DESKTOP_OPS.includes(op)) return Promise.reject(new DesktopBridgeError(`Unknown desktop operation "${op}".`, 'invalid'));
    if (signal?.aborted) return Promise.reject(new DesktopBridgeError('Desktop request cancelled.', 'aborted'));
    const peer = [...this.peers].reverse().find(peer => peer.readyState === OPEN);
    if (!peer) return Promise.reject(new DesktopBridgeError('No Luma companion is connected, so nothing can operate the desktop. Open the Luma app (dist/Luma.app) and try again; the in-app ui/ui_action tools do not need it.', 'no_companion'));
    const requestId = randomUUID();
    const timeoutMs = DESKTOP_TIMEOUTS_MS[op];
    return new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => this.settle(requestId, undefined, new DesktopBridgeError(`The companion did not answer desktop ${op} within ${timeoutMs / 1000} s. Check that Luma is trusted for Accessibility and not blocked by a modal dialog of its own.`, 'timeout')), timeoutMs);
      const entry: Pending = { peer, resolve, reject, timer, signal };
      if (signal) { entry.abort = () => this.settle(requestId, undefined, new DesktopBridgeError('Desktop request cancelled.', 'aborted')); signal.addEventListener('abort', entry.abort, { once: true }); }
      this.pending.set(requestId, entry);
      try { peer.send(JSON.stringify({ type: 'desktop_request', requestId, op, args })); }
      catch (error) { this.settle(requestId, undefined, new DesktopBridgeError(`Could not send the desktop request to the companion: ${error instanceof Error ? error.message : String(error)}`, 'no_companion')); }
    });
  }

  private settle(requestId: string, value: unknown, error?: Error) {
    const entry = this.pending.get(requestId); if (!entry) return;
    this.pending.delete(requestId); clearTimeout(entry.timer);
    if (entry.abort && entry.signal) entry.signal.removeEventListener('abort', entry.abort);
    if (error) entry.reject(error); else entry.resolve(value);
  }
}

/** Operation name used by desktop tool definitions: `desktop_<op>`. */
export const desktopOperation = (op: DesktopOp) => `desktop_${op}`;
export function parseDesktopOperation(operation: string): DesktopOp | undefined {
  const op = operation.startsWith('desktop_') ? operation.slice('desktop_'.length) : undefined;
  return op && (DESKTOP_OPS as readonly string[]).includes(op) ? op as DesktopOp : undefined;
}

/**
 * Wraps the application adapter so `desktop_*` operations go to the companion while everything else, including the
 * `context` observation and audit path every data/code tool already takes, keeps going to the in-app bridge.
 * `defaultApp` (the profile's bundle id) fills in which application to bring forward when the model names none.
 */
export function withDesktop(inner: Adapter, bridge: DesktopBridge, defaultApp?: string): Adapter {
  return {
    async execute(operation, args = {}, options) {
      const op = parseDesktopOperation(operation);
      if (!op) return inner.execute(operation, args, options);
      if (defaultApp) {
        if (op === 'inspect' && args.apps === undefined) args = { ...args, apps: [defaultApp] };
        else if (op !== 'inspect' && op !== 'act' && args.app === undefined) args = { ...args, app: defaultApp };
      }
      const result = await bridge.request(op, args) as Record<string, unknown> | null;
      // A missing permission is a clear, non-exceptional answer the model must relay, not a silent failure.
      if (result && typeof result === 'object' && result.trusted === false) throw new Error(String(result.error ?? 'Luma is not trusted for Accessibility.'));
      return result;
    },
  };
}
